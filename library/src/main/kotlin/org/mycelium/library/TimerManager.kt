package org.mycelium.library

import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject

// Host-side (Kotlin) single-threaded event loop backing `setTimeout`/`setInterval`/
// `setImmediate` (and their `clear*` counterparts). Replaces an earlier implementation
// that spawned a dedicated `java.lang.Thread` per timer to sleep for real wall-clock time
// and then call back into the shared GraalJS `Context` from that thread — which crashed
// with `IllegalStateException: Multi threaded access requested by thread ... but is not
// allowed` the moment two timers' background threads (or a timer thread and the guest's
// own thread) happened to touch the Context at overlapping instants, since GraalJS
// Contexts only permit one thread inside them at a time. See TIMERS_INIT_SCRIPT's old doc
// comment in Mycelium.kt (removed) for the two rejected alternatives that led there.
//
// The fix here is the standard single-threaded event-loop pattern every real JS runtime
// (Node, Deno, Bun, browsers) uses: no timer ever gets its own thread. Instead, every
// scheduled callback is just a `(dueAt, callback, args)` record sitting in this class'
// `entries` map, and exactly one thread — whichever thread owns the GraalJS Context and
// calls `runUntilIdle()` (see Mycelium.runFile) — actually invokes callbacks, one at a
// time, via a plain loop: find the earliest-due entry, run it if it's due, otherwise sleep
// (via `Object#wait(timeoutMs)`, so a timer scheduled concurrently from another thread —
// e.g. a request-handling thread spun up by http.mjs's `com.sun.net.httpserver.HttpServer`
// — can `notifyAll()` this loop awake early rather than making it oversleep past a newly
// registered, earlier-firing timer) until the next one is due.
//
// `entries` itself (the bookkeeping map) *is* touched from more than one OS thread — a
// guest callback running on some other thread that has temporarily "won" entry into the
// Context (see http.mjs's own doc comment on this pattern) can still call
// `setTimeout`/`clearTimeout`/etc. from within its own callback — so every method here
// synchronizes on `lock` around any read/write of `entries`. That's independent of (and
// does not conflict with) GraalJS's own single-thread-in-Context-at-a-time rule: this
// class never holds `lock` while a guest callback (`Value#execute`) is actually running,
// so a second thread is always free to enter the Context and schedule/cancel a timer while
// this loop is either idle (`wait`-ing) or between ticks.
class TimerManager {
    private class Entry(
        val id: Long,
        var nextFireAt: Long,
        var delayMs: Long,
        val repeating: Boolean,
        var ref: Boolean,
        val callback: Value,
        val args: List<Value>,
    )

    // A plain `java.lang.Object` (not Kotlin's `Any`, which deliberately hides
    // `wait`/`notifyAll` from its public surface) so `runUntilIdle` below can call
    // `lock.wait(timeoutMs)` directly.
    private val lock = java.lang.Object()
    private var nextId = 1L
    private val entries = LinkedHashMap<Long, Entry>()

    // Registers a new timer and returns its id. `delayMs < 0` is clamped to 0, matching
    // Node's own `setTimeout`/`setInterval` behavior for a negative/NaN delay (fire "as
    // soon as possible" rather than throwing or never firing).
    fun schedule(callback: Value, delayMs: Long, repeating: Boolean, args: List<Value>): Long {
        val safeDelay = if (delayMs < 0) 0L else delayMs
        synchronized(lock) {
            val id = nextId++
            entries[id] = Entry(
                id = id,
                nextFireAt = System.currentTimeMillis() + safeDelay,
                delayMs = safeDelay,
                repeating = repeating,
                ref = true,
                callback = callback,
                args = args,
            )
            lock.notifyAll()
            return id
        }
    }

    fun clear(id: Long) {
        synchronized(lock) {
            entries.remove(id)
            lock.notifyAll()
        }
    }

    fun ref(id: Long) {
        synchronized(lock) { entries[id]?.ref = true }
    }

    fun unref(id: Long) {
        synchronized(lock) {
            entries[id]?.ref = false
            lock.notifyAll()
        }
    }

    fun hasRef(id: Long): Boolean = synchronized(lock) { entries[id]?.ref ?: false }

    // Resets a timer's countdown to start from now — Node's `Timeout#refresh()`.
    fun refresh(id: Long) {
        synchronized(lock) {
            entries[id]?.let { it.nextFireAt = System.currentTimeMillis() + it.delayMs }
            lock.notifyAll()
        }
    }

    // Builds the JS-facing `Timer` object `setTimeout`/`setInterval`/`setImmediate` return
    // — a host `ProxyObject` exposing Node/Bun/Deno's `ref()`/`unref()`/`refresh()`/
    // `hasRef()`/`close()` methods, each just delegating back to this manager by id.
    fun handleFor(id: Long): ProxyObject {
        val members = linkedMapOf<String, Any>(
            "ref" to ProxyExecutable { ref(id); handleValue(id) },
            "unref" to ProxyExecutable { unref(id); handleValue(id) },
            "refresh" to ProxyExecutable { refresh(id); handleValue(id) },
            "hasRef" to ProxyExecutable { hasRef(id) },
            "close" to ProxyExecutable { clear(id); null },
        )
        return ProxyObject.fromMap(members)
    }

    // `ref()`/`unref()`/`refresh()` return `this` in Node so callers can chain
    // (`setTimeout(fn, ms).unref()`), but a `ProxyExecutable` can't return "itself" without
    // already holding its own wrapping `Value` — harmless to just rebuild an equivalent
    // handle here since `handleFor` is a pure function of `id` with no per-call state.
    private fun handleValue(id: Long): ProxyObject = handleFor(id)

    // True while at least one *ref'd* timer is pending — mirrors Node's own "the event
    // loop stays alive as long as there's a ref'd handle" rule. An unref'd timer never
    // keeps this true by itself, even if it's still pending.
    private fun hasActiveRefdTimers(): Boolean = entries.values.any { it.ref }

    // Drives the event loop on the calling thread (which must be the thread that owns the
    // GraalJS Context — see Mycelium.runFile) until no ref'd timer remains pending. Each
    // iteration: run every currently-due timer (there may be several with the same
    // deadline), then either loop again immediately (more became due while running those
    // callbacks) or sleep until the next one is due. Microtasks queued during a callback
    // (Promises, `queueMicrotask`) are drained automatically by GraalJS itself whenever a
    // top-level `Value#execute` call returns control to this host loop — no separate
    // microtask-draining step is needed here.
    fun runUntilIdle() {
        while (true) {
            val due = synchronized(lock) {
                if (!hasActiveRefdTimers()) return
                val now = System.currentTimeMillis()
                val next = entries.values.filter { it.nextFireAt <= now }.minByOrNull { it.nextFireAt }
                if (next == null) {
                    val earliest = entries.values.minOf { it.nextFireAt }
                    val waitMs = (earliest - System.currentTimeMillis()).coerceAtLeast(1)
                    try {
                        lock.wait(waitMs)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
                next
            } ?: continue

            try {
                due.callback.execute(*due.args.toTypedArray())
            } catch (e: Exception) {
                // Best-effort: report to stderr rather than letting an uncaught exception
                // inside a callback take down the whole event loop (or get silently
                // swallowed) — real Node prints an uncaught exception and, by default,
                // terminates the process; printing without terminating is the safer
                // default for an embedded runtime with other work potentially still
                // pending (e.g. an unrelated in-flight HTTP request).
                System.err.println("mycelium: uncaught exception in timer callback: ${e.message}")
            }

            synchronized(lock) {
                val current = entries[due.id] ?: return@synchronized
                if (current.repeating) {
                    current.nextFireAt = System.currentTimeMillis() + current.delayMs
                } else {
                    entries.remove(due.id)
                }
            }
        }
    }
}

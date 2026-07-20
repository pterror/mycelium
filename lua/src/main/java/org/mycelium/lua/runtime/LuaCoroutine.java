package org.mycelium.lua.runtime;

import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.interop.TruffleObject;
import java.util.concurrent.SynchronousQueue;
import org.mycelium.lua.LuaContext;
import org.mycelium.lua.LuaException;

/**
 * Represents a Lua 5.1 coroutine ({@code thread} type, &sect;2.11/&sect;5.2), implemented as
 * "thread-per-coroutine": each coroutine that is actually started gets its own backing Java
 * thread (created via {@link Env#newTruffleThreadBuilder}, so it is a proper Truffle-aware thread
 * that may enter the language's context), but coroutines are never allowed to make progress
 * concurrently &mdash; {@link #resume} and {@link #yield} rendezvous through a pair of {@link
 * SynchronousQueue}s so that at any instant exactly one of {the resumer, the coroutine} is
 * actually running Lua code while the other blocks.
 *
 * <p>This class knows nothing about Lua's calling convention or the {@code __call} metamethod
 * chain (that logic lives in {@code org.mycelium.lua.stdlib.LuaCallSupport}, a different package)
 * &mdash; callers supply an {@link Invoker} callback that performs the actual invocation of the
 * coroutine's body function.
 *
 * <p><b>Shutdown:</b> a coroutine that yielded at least once but is never resumed to completion
 * (e.g. simply dropped by the Lua program) leaves its backing thread parked forever inside {@link
 * #yield}'s {@code resumeChannel.take()} &mdash; unlike a real Lua VM, that thread is a real
 * resource Truffle tracks, and {@code Context.close()} refuses to finish while any such thread is
 * still entered. {@link LuaContext} therefore tracks every started coroutine and calls {@link
 * #interruptForShutdown()} on each during {@code finalizeContext} (see {@code
 * LuaLanguage#finalizeContext}), which interrupts that blocked {@code take()} so the thread can
 * exit instead of hanging the context shutdown.
 */
public final class LuaCoroutine implements TruffleObject {

    /** Lua 5.1 &sect;5.2: the four states a coroutine can be in, as returned by {@code coroutine.status}. */
    public enum Status {
        /** Created but not yet started, or suspended by a {@code yield}. */
        SUSPENDED,
        /** Currently executing (on its own backing thread). */
        RUNNING,
        /** Active (resumed) but not running right now &mdash; it resumed another coroutine. */
        NORMAL,
        /** Finished (returned or errored) &mdash; cannot be resumed again. */
        DEAD
    }

    /**
     * Invokes a Lua-callable value with arguments, honoring the {@code __call} metamethod chain.
     * Supplied by the stdlib layer ({@code LuaCoroutineLib}) since the underlying implementation
     * ({@code LuaCallSupport}) is package-private to a different package.
     */
    @FunctionalInterface
    public interface Invoker {
        Object[] invoke(Object callee, Object[] args);
    }

    /** Thrown internally to unwind a coroutine's call stack when its backing thread is
     * interrupted for context shutdown (see class doc); never escapes to Lua code (in particular,
     * {@code pcall}/{@code xpcall} only catch {@link LuaException} and let this propagate). */
    private static final class ShutdownSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ShutdownSignal() {
            super(null, null, false, false);
        }
    }

    /** The {@link LuaCoroutine} whose body is currently executing on the calling Java thread, or
     * {@code null} if the calling thread is the "main" coroutine (never itself created via {@code
     * coroutine.create}). Used both by {@code coroutine.yield} (to find "self") and by {@link
     * #resume} (to find "who is resuming me", so its status can be flipped to {@link
     * Status#NORMAL} for the duration). */
    private static final ThreadLocal<LuaCoroutine> RUNNING_ON_THIS_THREAD = new ThreadLocal<>();

    private final LuaFunction function;
    private volatile Status status = Status.SUSPENDED;
    private Thread thread;

    /** Resumer &rarr; coroutine: resume()'s (or the initial call's) arguments. */
    private final SynchronousQueue<Object[]> resumeChannel = new SynchronousQueue<>();
    /** Coroutine &rarr; resumer: the result of a yield, a normal return, or an error. */
    private final SynchronousQueue<Outcome> yieldChannel = new SynchronousQueue<>();

    public LuaCoroutine(LuaFunction function) {
        this.function = function;
    }

    public LuaFunction getFunction() {
        return function;
    }

    public Status getStatus() {
        return status;
    }

    /** The coroutine currently running on this Java thread, or {@code null} for the main thread. */
    public static LuaCoroutine current() {
        return RUNNING_ON_THIS_THREAD.get();
    }

    private record Outcome(boolean failed, Object[] values, Object error) {
        static Outcome ok(Object[] values) {
            return new Outcome(false, values, null);
        }

        static Outcome fail(Object error) {
            return new Outcome(true, null, error);
        }
    }

    /**
     * Lua 5.1 &sect;5.2's {@code coroutine.resume}. On the first call, spins up the coroutine's
     * backing thread and starts running its function with {@code args}; on subsequent calls,
     * delivers {@code args} as the return values of the {@code yield} call that suspended it.
     * Never throws: mirrors {@code pcall} semantics, returning {@code {true, results...}} on a
     * yield or normal return, or {@code {false, errorMessage}} if the coroutine could not be
     * resumed (wrong status) or raised an error while running.
     */
    public Object[] resume(LuaContext context, Invoker invoker, Object[] args) {
        if (status == Status.DEAD) {
            return new Object[] {Boolean.FALSE, "cannot resume dead coroutine"};
        }
        if (status != Status.SUSPENDED) {
            return new Object[] {Boolean.FALSE, "cannot resume non-suspended coroutine"};
        }

        LuaCoroutine resumer = RUNNING_ON_THIS_THREAD.get();
        if (resumer != null) {
            resumer.status = Status.NORMAL;
        }
        status = Status.RUNNING;

        if (thread == null) {
            thread = context.getEnv().newTruffleThreadBuilder(() -> runBody(invoker, context)).build();
            thread.setDaemon(true);
            context.registerCoroutine(this);
            thread.start();
        }

        Outcome outcome;
        try {
            resumeChannel.put(args);
            outcome = yieldChannel.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while resuming a coroutine", e);
        }

        if (resumer != null) {
            resumer.status = Status.RUNNING;
        }

        if (outcome.failed()) {
            return new Object[] {Boolean.FALSE, outcome.error()};
        }
        Object[] values = outcome.values();
        Object[] result = new Object[values.length + 1];
        result[0] = Boolean.TRUE;
        System.arraycopy(values, 0, result, 1, values.length);
        return result;
    }

    /** Runs on the coroutine's own backing thread (see {@link #resume}). */
    private void runBody(Invoker invoker, LuaContext context) {
        RUNNING_ON_THIS_THREAD.set(this);
        Object[] initialArgs;
        try {
            initialArgs = resumeChannel.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = Status.DEAD;
            return;
        }
        Outcome outcome;
        try {
            Object[] results = invoker.invoke(function, initialArgs);
            outcome = Outcome.ok(results);
        } catch (ShutdownSignal shutdown) {
            // Context is closing and nobody will ever consume an outcome — see class doc. Don't
            // attempt to publish one (that would block forever on an unattended SynchronousQueue).
            status = Status.DEAD;
            context.unregisterCoroutine(this);
            return;
        } catch (LuaException e) {
            outcome = Outcome.fail(e.getErrorObject());
        } catch (Throwable t) {
            outcome = Outcome.fail(String.valueOf(t.getMessage()));
        }
        status = Status.DEAD;
        context.unregisterCoroutine(this);
        try {
            yieldChannel.put(outcome);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Lua 5.1 &sect;5.2's {@code coroutine.yield}, called from the currently running coroutine's
     * own backing thread. Suspends this coroutine, handing {@code values} back as {@code
     * resume}'s return values, and blocks until the next {@code resume} call, whose arguments
     * become this call's return value.
     */
    public Object[] yield(Object[] values) {
        status = Status.SUSPENDED;
        try {
            yieldChannel.put(Outcome.ok(values));
            return resumeChannel.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ShutdownSignal();
        }
    }

    /**
     * Called by {@link LuaContext} during context shutdown for every coroutine that was ever
     * started (see class doc). A no-op unless this coroutine is currently parked inside {@link
     * #yield}, in which case it interrupts that wait so the backing thread can exit and the
     * context can finish closing.
     */
    public void interruptForShutdown() {
        Thread t = thread;
        if (t == null || status == Status.DEAD) {
            return;
        }
        t.interrupt();
        try {
            t.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String toString() {
        return "thread: 0x" + Integer.toHexString(System.identityHashCode(this));
    }
}

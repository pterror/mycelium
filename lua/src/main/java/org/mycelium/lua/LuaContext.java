package org.mycelium.lua;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.mycelium.lua.runtime.LuaCoroutine;
import org.mycelium.lua.runtime.LuaFunction;
import org.mycelium.lua.runtime.LuaTable;

/**
 * Per-{@link com.oracle.truffle.api.TruffleContext} runtime state for the Lua language: the
 * global environment table (Lua 5.1's implicit top-level table that every chunk/closure reads
 * and writes global variables through, mirrored here rather than via an explicit {@code _ENV}
 * upvalue) plus a handle back to the {@link TruffleLanguage.Env} for I/O (Lua's {@code print}/
 * {@code io.*} should go through {@link TruffleLanguage.Env#out()}/{@link TruffleLanguage.Env#in()}
 * rather than {@code System.out}/{@code System.in} so embedders can redirect them).
 */
public final class LuaContext {

    private final LuaLanguage language;
    private final TruffleLanguage.Env env;
    private final LuaTable globals;

    /** Every {@link LuaCoroutine} that has ever been started (i.e. resumed at least once), so
     * that {@link #shutdownCoroutines()} can interrupt any still-suspended backing threads before
     * the Truffle context finishes closing &mdash; see {@link LuaCoroutine}'s class doc. */
    private final Set<LuaCoroutine> startedCoroutines =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Lua 5.1 §5.9 debug.sethook/gethook state. ThreadLocal so each coroutine's backing thread
    // (see LuaCoroutine) gets its own independent hook — real Lua likewise scopes hooks per
    // lua_State/coroutine. Coroutines never run concurrently with each other or the main thread
    // (see LuaCoroutine's class doc), so plain ThreadLocals (no synchronization) are sufficient.
    private final ThreadLocal<LuaFunction> hookFunction = new ThreadLocal<>();
    private final ThreadLocal<String> hookMask = ThreadLocal.withInitial(() -> "");
    private final ThreadLocal<Integer> hookCount = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Integer> hookCounter = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Integer> lastHookLine = ThreadLocal.withInitial(() -> -1);
    private final ThreadLocal<Boolean> inHook = ThreadLocal.withInitial(() -> false);

    public LuaContext(LuaLanguage language, TruffleLanguage.Env env) {
        this.language = language;
        this.env = env;
        this.globals = new LuaTable();
        installStdLib();
    }

    /**
     * Installs the standard library into {@link #globals}. Uses reflection-free direct calls to
     * {@code org.mycelium.lua.stdlib.LuaStdLib}, but tolerates that class (or its dependencies)
     * not yet being compiled/available &mdash; e.g. during incremental development where the
     * stdlib and language-registration pieces are being built out in parallel &mdash; by falling
     * back to just {@code _VERSION}/{@code _G} so the rest of the runtime still works.
     */
    private void installStdLib() {
        globals.rawset("_VERSION", "Lua 5.1");
        globals.rawset("_G", globals);
        try {
            org.mycelium.lua.stdlib.LuaStdLib.installAll(language, this);
        } catch (LinkageError e) {
            // Standard library not available yet — base runtime (parsing/execution) still works.
        }
    }

    public LuaTable getGlobals() {
        return globals;
    }

    public LuaLanguage getLanguage() {
        return language;
    }

    public TruffleLanguage.Env getEnv() {
        return env;
    }

    /** Registers {@code coroutine} as started, so its backing thread gets a shutdown chance. */
    public void registerCoroutine(LuaCoroutine coroutine) {
        startedCoroutines.add(coroutine);
    }

    /** Unregisters {@code coroutine} once it's finished on its own (normal return or error), so
     * the registry doesn't grow unbounded over a long-running program's lifetime. */
    public void unregisterCoroutine(LuaCoroutine coroutine) {
        startedCoroutines.remove(coroutine);
    }

    /**
     * Interrupts every still-registered coroutine's backing thread (see {@link
     * LuaCoroutine#interruptForShutdown()}), called from {@link LuaLanguage#finalizeContext} so
     * that a Lua program that dropped a suspended coroutine without resuming it to completion
     * doesn't prevent the enclosing polyglot {@code Context} from closing.
     */
    public void shutdownCoroutines() {
        for (LuaCoroutine coroutine : new java.util.ArrayList<>(startedCoroutines)) {
            coroutine.interruptForShutdown();
        }
    }

    /** Retrieves the {@link LuaContext} for the language currently executing at {@code node}. */
    public static LuaContext get(Node node) {
        return LuaLanguage.getContext(node);
    }

    // --- debug.sethook/gethook state (see class field docs above) ---

    /** Cheap pre-check so hot execution paths (block/root nodes) skip the rest of the hook
     * machinery entirely when no hook is installed on the current thread. */
    public boolean isHookActive() {
        return hookFunction.get() != null;
    }

    public LuaFunction getHookFunction() {
        return hookFunction.get();
    }

    public String getHookMask() {
        return hookMask.get();
    }

    public int getHookCount() {
        return hookCount.get();
    }

    /** Installs (or, with {@code fn == null}, clears) the current thread's hook. */
    public void setHook(LuaFunction fn, String mask, int count) {
        hookFunction.set(fn);
        hookMask.set(mask != null ? mask : "");
        hookCount.set(count);
        hookCounter.set(0);
        lastHookLine.set(-1);
    }

    public void clearHook() {
        hookFunction.remove();
        hookMask.remove();
        hookCount.remove();
        hookCounter.remove();
        lastHookLine.remove();
    }

    /** Increments and returns the per-statement instruction counter used for the {@code count}
     * hook mask, resetting it to {@code 0} whenever it reaches {@link #getHookCount()}. */
    public int incrementHookCounter() {
        int next = hookCounter.get() + 1;
        hookCounter.set(next);
        return next;
    }

    public void resetHookCounter() {
        hookCounter.set(0);
    }

    public int getLastHookLine() {
        return lastHookLine.get();
    }

    public void setLastHookLine(int line) {
        lastHookLine.set(line);
    }

    /** Whether a hook function is currently executing on this thread, to prevent a hook's own
     * statements from re-triggering line/count/call/return hooks (infinite recursion). */
    public boolean isInHook() {
        return inHook.get();
    }

    public void setInHook(boolean value) {
        inHook.set(value);
    }
}

package org.mycelium.lua;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.mycelium.lua.runtime.LuaCoroutine;
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
}

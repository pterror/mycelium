package org.mycelium.lua;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;
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
            org.mycelium.lua.stdlib.LuaStdLib.installAll(language, globals);
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

    /** Retrieves the {@link LuaContext} for the language currently executing at {@code node}. */
    public static LuaContext get(Node node) {
        return LuaLanguage.getContext(node);
    }
}

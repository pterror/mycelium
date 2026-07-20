package org.mycelium.lua.stdlib;

import com.oracle.truffle.api.TruffleLanguage;
import org.mycelium.lua.runtime.LuaTable;

/**
 * Entry point for installing Lua 5.1's standard library (&sect;5) into a global environment
 * table. Each {@code install*} method is independent and idempotent with respect to the others
 * (they touch disjoint keys), so callers may install any subset.
 */
public final class LuaStdLib {

    private LuaStdLib() {}

    /** Lua 5.1 &sect;5.1: the base library (print, type, pcall, pairs, ...), installed directly as globals. */
    public static void installBase(TruffleLanguage<?> language, LuaTable globals) {
        LuaBaseLib.install(language, globals);
    }

    /** Lua 5.1 &sect;5.6: the {@code math} table. */
    public static void installMath(TruffleLanguage<?> language, LuaTable globals) {
        LuaMathLib.install(language, globals);
    }

    /** Lua 5.1 &sect;5.4: the {@code string} table, also installed as the shared string metatable. */
    public static void installString(TruffleLanguage<?> language, LuaTable globals) {
        LuaStringLib.install(language, globals);
    }

    /** Lua 5.1 &sect;5.5: the {@code table} table. */
    public static void installTable(TruffleLanguage<?> language, LuaTable globals) {
        LuaTableLib.install(language, globals);
    }

    /** Lua 5.1 &sect;5.2: the {@code coroutine} table. */
    public static void installCoroutine(TruffleLanguage<?> language, LuaTable globals) {
        LuaCoroutineLib.install(language, globals);
    }

    /** Installs every implemented library ({@code base}, {@code math}, {@code string}, {@code
     * table}, {@code coroutine}) into {@code globals}. */
    public static void installAll(TruffleLanguage<?> language, LuaTable globals) {
        installBase(language, globals);
        installMath(language, globals);
        installString(language, globals);
        installTable(language, globals);
        installCoroutine(language, globals);
    }
}

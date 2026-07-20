package org.mycelium.lua.runtime;

/**
 * Holds the shared metatable used when indexing Lua string values (Lua 5.1 &sect;5.4: "strings
 * ... have a common metatable, that is set as the metatable of every string, and whose
 * {@code __index} field points to the string library"). This lets {@code ("hi"):upper()} resolve
 * {@code upper} as a method even though strings are represented as plain {@link String} instances
 * with no per-value metatable slot.
 *
 * <p>Populated once by {@code org.mycelium.lua.stdlib.LuaStdLib#installString} during standard
 * library bootstrap; consulted by the table-indexing logic in the {@code nodes} package so that
 * ordinary {@code __index} dispatch also covers strings.
 */
public final class LuaStringMetatable {
    private static volatile LuaTable metatable;

    private LuaStringMetatable() {}

    public static LuaTable get() {
        return metatable;
    }

    public static void set(LuaTable table) {
        metatable = table;
    }
}

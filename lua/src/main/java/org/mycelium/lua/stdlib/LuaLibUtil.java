package org.mycelium.lua.stdlib;

import org.mycelium.lua.LuaException;
import org.mycelium.lua.runtime.LuaFunction;
import org.mycelium.lua.runtime.LuaNil;
import org.mycelium.lua.runtime.LuaTable;
import org.mycelium.lua.runtime.LuaTypeUtils;

/** Shared argument-checking/coercion helpers used across the standard library packages. */
final class LuaLibUtil {

    private LuaLibUtil() {}

    static boolean isNil(Object v) {
        return v == null || v == LuaNil.INSTANCE;
    }

    static LuaException argError(String fname, int argN, String expected, Object got) {
        return LuaException.runtimeError(
                null,
                "bad argument #" + argN + " to '" + fname + "' (" + expected + " expected, got "
                        + (isNil(got) ? "no value" : LuaTypeUtils.typeName(got)) + ")");
    }

    static LuaTable checkTable(String fname, int argN, Object v) {
        if (v instanceof LuaTable t) {
            return t;
        }
        throw argError(fname, argN, "table", v);
    }

    static String checkString(String fname, int argN, Object v) {
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Double) {
            return LuaTypeUtils.tostring(v);
        }
        throw argError(fname, argN, "string", v);
    }

    static double checkNumber(String fname, int argN, Object v) {
        Double d = LuaTypeUtils.toNumber(v);
        if (d == null) {
            throw argError(fname, argN, "number", v);
        }
        return d;
    }

    static double optNumber(Object v, double defaultValue) {
        if (isNil(v)) {
            return defaultValue;
        }
        Double d = LuaTypeUtils.toNumber(v);
        return d == null ? defaultValue : d;
    }

    static String optString(Object v, String defaultValue) {
        if (isNil(v)) {
            return defaultValue;
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Double) {
            return LuaTypeUtils.tostring(v);
        }
        return defaultValue;
    }

    /** Lua 5.1 &sect;2.5.2 raw (non-metamethod) equality, as used by {@code rawequal} and table
     * lookups of composite keys. */
    static boolean rawEquals(Object a, Object b) {
        boolean aNil = isNil(a);
        boolean bNil = isNil(b);
        if (aNil || bNil) {
            return aNil == bNil;
        }
        if (a instanceof Double da && b instanceof Double db) {
            return da.doubleValue() == db.doubleValue();
        }
        if (a instanceof String sa && b instanceof String sb) {
            return sa.equals(sb);
        }
        if (a instanceof Boolean ba && b instanceof Boolean bb) {
            return ba.equals(bb);
        }
        if (a instanceof LuaTable && b instanceof LuaTable) {
            return a == b;
        }
        if (a instanceof LuaFunction && b instanceof LuaFunction) {
            return a == b;
        }
        return a.equals(b);
    }
}

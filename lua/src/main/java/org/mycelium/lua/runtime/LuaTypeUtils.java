package org.mycelium.lua.runtime;

/**
 * Static utility methods for Lua type operations: type names, coercions, truthiness, and the
 * integer/double distinction used for table array indexing.
 *
 * <p>Lua 5.1 &sect;2.2: Lua is dynamically typed; there are eight basic types: nil, boolean,
 * number, string, function, userdata, thread, and table. All numbers are represented as IEEE
 * 754 double-precision floats &mdash; there is no separate integer subtype in the language, but
 * table array indices only make sense for doubles that happen to hold whole-number values.
 */
public final class LuaTypeUtils {

    private LuaTypeUtils() {}

    /** Lua 5.1 &sect;2.2: type names, as returned by the {@code type()} builtin. */
    public static String typeName(Object value) {
        if (value == null || value == LuaNil.INSTANCE) {
            return "nil";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Double || value instanceof Long || value instanceof Integer) {
            return "number";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof LuaTable) {
            return "table";
        }
        if (value instanceof LuaFunction) {
            return "function";
        }
        if (value instanceof LuaCoroutine) {
            return "thread";
        }
        // Anything else is treated as opaque host/user data.
        return "userdata";
    }

    /**
     * Lua 5.1 &sect;2.2.1: coercion of a value to a number. Numbers convert to themselves;
     * strings convert if (and only if) they represent a valid Lua numeral. Anything else
     * returns {@code null} (not convertible), rather than throwing, so callers can decide how
     * to report the failure.
     */
    public static Double toNumber(Object value) {
        if (value instanceof Double) {
            return (Double) value;
        }
        if (value instanceof Long) {
            return ((Long) value).doubleValue();
        }
        if (value instanceof Integer) {
            return ((Integer) value).doubleValue();
        }
        if (value instanceof String) {
            String s = ((String) value).trim();
            if (s.isEmpty()) {
                return null;
            }
            try {
                if (s.startsWith("0x") || s.startsWith("0X")
                        || s.startsWith("-0x") || s.startsWith("-0X")) {
                    boolean negative = s.startsWith("-");
                    String hex = negative ? s.substring(3) : s.substring(2);
                    long parsed = Long.parseLong(hex, 16);
                    return negative ? -(double) parsed : (double) parsed;
                }
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Lua's default {@code tostring} conversion (not metamethod-aware &mdash; callers that need
     * to honor {@code __tostring} must check the metatable themselves before falling back to
     * this method).
     */
    public static String tostring(Object value) {
        if (value == null || value == LuaNil.INSTANCE) {
            return "nil";
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "true" : "false";
        }
        if (value instanceof Double) {
            double d = (Double) value;
            if (Double.isNaN(d)) {
                return "nan";
            }
            if (Double.isInfinite(d)) {
                return d > 0 ? "inf" : "-inf";
            }
            if (isInteger(d)) {
                return Long.toString((long) d);
            }
            return formatFloat(d);
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof LuaTable) {
            return "table: 0x" + Integer.toHexString(System.identityHashCode(value));
        }
        if (value instanceof LuaFunction) {
            return "function: 0x" + Integer.toHexString(System.identityHashCode(value));
        }
        return value.toString();
    }

    // Lua 5.1's default number formatting uses "%.14g".
    private static String formatFloat(double d) {
        String s = String.format("%.14g", d);
        // Trim trailing zeros in the fractional part (but keep at least one digit after '.').
        if (s.contains(".") && !s.contains("e") && !s.contains("E")) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) {
                s = s + "0";
            }
        }
        return s;
    }

    /** Lua 5.1 &sect;2.2: only {@code nil} and {@code false} are falsy; everything else, including {@code 0} and {@code ""}, is truthy. */
    public static boolean toBoolean(Object value) {
        if (value == null || value == LuaNil.INSTANCE) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return true;
    }

    /** Whether a double value represents a whole number that fits in a Java int (used for array indexing). */
    public static boolean isInteger(double d) {
        return !Double.isNaN(d) && !Double.isInfinite(d) && d == Math.floor(d)
                && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE;
    }

    /** Only call if {@link #isInteger(double)} returned {@code true} for {@code d}. */
    public static int asInteger(double d) {
        return (int) d;
    }
}

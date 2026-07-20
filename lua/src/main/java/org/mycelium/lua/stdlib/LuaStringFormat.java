package org.mycelium.lua.stdlib;

import org.mycelium.lua.LuaException;
import org.mycelium.lua.runtime.LuaTypeUtils;

/**
 * Lua 5.1 &sect;5.4 {@code string.format}: a printf-style formatter. Supports the common
 * conversions ({@code %d %i %u %f %e %E %g %G %x %X %o %s %c %q %%}) with width/precision/flag
 * modifiers, by re-emitting each conversion spec through {@link String#format} after coercing the
 * Lua argument to the Java type that conversion expects.
 *
 * <p><b>Known approximation:</b> {@code %x}/{@code %X}/{@code %o} of a negative number format as
 * 64-bit two's complement (via Java's {@code long} formatting) rather than C's 32-bit unsigned
 * wraparound &mdash; flagging this as a divergence from the reference implementation for values
 * that rely on that specific width.
 */
final class LuaStringFormat {

    private LuaStringFormat() {}

    static String format(String fmt, Object[] args) {
        StringBuilder out = new StringBuilder();
        int argIndex = 0;
        int i = 0;
        int len = fmt.length();
        while (i < len) {
            char c = fmt.charAt(i);
            if (c != '%') {
                out.append(c);
                i++;
                continue;
            }
            i++;
            if (i >= len) {
                throw error("invalid conversion to 'format'");
            }
            if (fmt.charAt(i) == '%') {
                out.append('%');
                i++;
                continue;
            }
            int specStart = i;
            while (i < len && "-+ #0".indexOf(fmt.charAt(i)) >= 0) {
                i++;
            }
            while (i < len && Character.isDigit(fmt.charAt(i))) {
                i++;
            }
            if (i < len && fmt.charAt(i) == '.') {
                i++;
                while (i < len && Character.isDigit(fmt.charAt(i))) {
                    i++;
                }
            }
            if (i >= len) {
                throw error("invalid conversion to 'format'");
            }
            char conv = fmt.charAt(i);
            String spec = fmt.substring(specStart, i);
            i++;
            Object argVal = argIndex < args.length ? args[argIndex++] : null;
            switch (conv) {
                case 'd':
                case 'i':
                case 'u':
                    out.append(String.format("%" + spec + "d", toLong(argVal)));
                    break;
                case 'o':
                    out.append(String.format("%" + spec + "o", toLong(argVal)));
                    break;
                case 'x':
                    out.append(String.format("%" + spec + "x", toLong(argVal)));
                    break;
                case 'X':
                    out.append(String.format("%" + spec + "X", toLong(argVal)));
                    break;
                case 'c':
                    out.append((char) toLong(argVal));
                    break;
                case 'f':
                case 'F':
                    out.append(String.format("%" + spec + "f", toDouble(argVal)));
                    break;
                case 'e':
                    out.append(String.format("%" + spec + "e", toDouble(argVal)));
                    break;
                case 'E':
                    out.append(String.format("%" + spec + "E", toDouble(argVal)));
                    break;
                case 'g':
                    out.append(String.format("%" + spec + "g", toDouble(argVal)));
                    break;
                case 'G':
                    out.append(String.format("%" + spec + "G", toDouble(argVal)));
                    break;
                case 's': {
                    String s = argVal == null ? "nil" : LuaTypeUtils.tostring(argVal);
                    out.append(String.format("%" + spec + "s", s));
                    break;
                }
                case 'q':
                    out.append(quote(argVal));
                    break;
                default:
                    throw error("invalid conversion '%" + conv + "' to 'format'");
            }
        }
        return out.toString();
    }

    private static long toLong(Object v) {
        Double d = LuaTypeUtils.toNumber(v);
        if (d == null) {
            throw error("bad argument to 'format' (number expected, got " + LuaTypeUtils.typeName(v) + ")");
        }
        return (long) (double) d;
    }

    private static double toDouble(Object v) {
        Double d = LuaTypeUtils.toNumber(v);
        if (d == null) {
            throw error("bad argument to 'format' (number expected, got " + LuaTypeUtils.typeName(v) + ")");
        }
        return d;
    }

    /** {@code %q}: a string literal that, read back by Lua, yields exactly {@code v} (Lua 5.1 &sect;5.4). */
    private static String quote(Object v) {
        if (!(v instanceof String s)) {
            return LuaTypeUtils.tostring(v);
        }
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append('\\').append('\n');
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\0') {
                sb.append("\\0");
            } else if (c < 32 || c == 127) {
                sb.append('\\').append((int) c);
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static LuaException error(String message) {
        return LuaException.runtimeError(null, message);
    }
}

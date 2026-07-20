package org.mycelium.lua.stdlib;

import com.oracle.truffle.api.nodes.IndirectCallNode;
import org.mycelium.lua.LuaException;
import org.mycelium.lua.runtime.LuaTypeUtils;

/**
 * Lua 5.1 &sect;5.4 {@code string.format}: a printf-style formatter. Supports the common
 * conversions ({@code %d %i %u %f %e %E %g %G %x %X %o %s %c %q %%}) with width/precision/flag
 * modifiers, by re-emitting each conversion spec through {@link String#format} after coercing the
 * Lua argument to the Java type that conversion expects.
 *
 * <p>{@code %x}/{@code %X}/{@code %o}/{@code %u} mask their operand to 32 bits before formatting,
 * matching C's {@code unsigned int} width used by PUC-Rio Lua 5.1's {@code lstrlib.c} (e.g.
 * {@code string.format("%x", -1)} yields {@code "ffffffff"}, not 64-bit {@code
 * "ffffffffffffffff"}). {@code %d}/{@code %i} likewise truncate to a 32-bit signed {@code int}.
 */
final class LuaStringFormat {

    private LuaStringFormat() {}

    static String format(String fmt, Object[] args, IndirectCallNode callNode) {
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
            int flagsStart = i;
            while (i < len && "-+ #0".indexOf(fmt.charAt(i)) >= 0) {
                i++;
            }
            String flags = fmt.substring(flagsStart, i);
            int widthStart = i;
            while (i < len && Character.isDigit(fmt.charAt(i))) {
                i++;
            }
            boolean hasWidth = i > widthStart;
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
            // Java's String.format rejects a bare '0' flag with no explicit width. In C (and Lua)
            // this is a harmless no-op — "pad with zeros" is meaningless without a width — so strip
            // it rather than passing it through.
            if (!hasWidth && flags.indexOf('0') >= 0) {
                spec = flags.replace("0", "") + spec.substring(flags.length());
            }
            i++;
            Object argVal = argIndex < args.length ? args[argIndex++] : null;
            switch (conv) {
                case 'd':
                case 'i':
                    out.append(String.format("%" + spec + "d", (int) toLong(argVal)));
                    break;
                case 'u':
                    out.append(String.format("%" + spec + "d", toUnsigned32(argVal)));
                    break;
                case 'o':
                    out.append(String.format("%" + spec + "o", toUnsigned32(argVal)));
                    break;
                case 'x':
                    out.append(String.format("%" + spec + "x", toUnsigned32(argVal)));
                    break;
                case 'X':
                    out.append(String.format("%" + spec + "X", toUnsigned32(argVal)));
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
                case 'g': {
                    String r = String.format("%" + spec + "g", toDouble(argVal));
                    if (spec.indexOf('#') < 0) {
                        r = trimGTrailingZeros(r);
                    }
                    out.append(r);
                    break;
                }
                case 'G': {
                    String r = String.format("%" + spec + "G", toDouble(argVal));
                    if (spec.indexOf('#') < 0) {
                        r = trimGTrailingZeros(r);
                    }
                    out.append(r);
                    break;
                }
                case 's': {
                    String s = argVal == null ? "nil" : LuaBaseLib.tostring(argVal, callNode);
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

    /**
     * {@code %x}/{@code %X}/{@code %o}/{@code %u}: mask to 32 bits, matching C's {@code unsigned
     * int} (32-bit) width used by PUC-Rio Lua 5.1's {@code lstrlib.c}, rather than Java's 64-bit
     * {@code long} formatting.
     */
    private static long toUnsigned32(Object v) {
        return toLong(v) & 0xFFFFFFFFL;
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

    /**
     * {@code %g}/{@code %G}: C's printf strips trailing zeros after the decimal point (and the
     * decimal point itself, if no digits remain), unlike Java's {@link String#format} which
     * always pads the mantissa to the requested precision. Applied to the mantissa only, so an
     * exponent suffix ({@code e+02}, {@code E-05}, ...) is left untouched.
     */
    private static String trimGTrailingZeros(String s) {
        int ePos = -1;
        for (int idx = 0; idx < s.length(); idx++) {
            char ch = s.charAt(idx);
            if (ch == 'e' || ch == 'E') {
                ePos = idx;
                break;
            }
        }
        String mantissa = ePos >= 0 ? s.substring(0, ePos) : s;
        String exponent = ePos >= 0 ? s.substring(ePos) : "";
        if (mantissa.indexOf('.') >= 0) {
            int end = mantissa.length();
            while (end > 0 && mantissa.charAt(end - 1) == '0') {
                end--;
            }
            if (end > 0 && mantissa.charAt(end - 1) == '.') {
                end--;
            }
            mantissa = mantissa.substring(0, end);
        }
        return mantissa + exponent;
    }

    private static LuaException error(String message) {
        return LuaException.runtimeError(null, message);
    }
}

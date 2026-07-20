package org.mycelium.lua.stdlib;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import java.util.Arrays;
import org.mycelium.lua.LuaException;
import org.mycelium.lua.nodes.controlflow.LuaReturnException;
import org.mycelium.lua.runtime.LuaFunction;
import org.mycelium.lua.runtime.LuaNil;
import org.mycelium.lua.runtime.LuaStringMetatable;
import org.mycelium.lua.runtime.LuaTable;
import org.mycelium.lua.runtime.LuaTypeUtils;

import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.arg;
import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.allArgs;
import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.wrap;
import static org.mycelium.lua.stdlib.LuaLibUtil.*;

/**
 * Lua 5.1 &sect;5.4: the {@code string} library, installed both as the {@code string} global and
 * (via {@link org.mycelium.lua.runtime.LuaStringMetatable}) as the shared metatable for every
 * string value, so {@code ("hi"):upper()} works the same as {@code string.upper("hi")}.
 */
final class LuaStringLib {

    private LuaStringLib() {}

    static void install(TruffleLanguage<?> language, LuaTable globals) {
        LuaTable string = new LuaTable();
        globals.rawset("string", string);

        LuaTable metatable = new LuaTable();
        metatable.rawset("__index", string);
        LuaStringMetatable.set(metatable);

        string.rawset("len", wrap(language, "len", (frame, cn) ->
                (double) checkString("len", 1, arg(frame, 1)).length()));

        string.rawset("sub", wrap(language, "sub", (frame, cn) -> {
            String s = checkString("sub", 1, arg(frame, 1));
            int len = s.length();
            long iArg = (long) optNumber(arg(frame, 2), 1);
            long jArg = (long) optNumber(arg(frame, 3), -1);
            int i = posrelat(iArg, len);
            int j = posrelat(jArg, len);
            if (i < 1) {
                i = 1;
            }
            if (j > len) {
                j = len;
            }
            if (i > j) {
                return "";
            }
            return s.substring(i - 1, j);
        }));

        string.rawset("byte", wrap(language, "byte", (frame, cn) -> {
            String s = checkString("byte", 1, arg(frame, 1));
            int len = s.length();
            long iArgRaw = (long) optNumber(arg(frame, 2), 1);
            int posi = posrelat(iArgRaw, len);
            long jArgRaw = isNil(arg(frame, 3)) ? posi : (long) checkNumber("byte", 3, arg(frame, 3));
            int pose = posrelat(jArgRaw, len);
            if (posi < 1) {
                posi = 1;
            }
            if (pose > len) {
                pose = len;
            }
            if (posi > pose) {
                throw new LuaReturnException(new Object[0]);
            }
            int count = pose - posi + 1;
            Object[] result = new Object[count];
            for (int k = 0; k < count; k++) {
                result[k] = (double) (int) s.charAt(posi - 1 + k);
            }
            throw new LuaReturnException(result);
        }));

        string.rawset("char", wrap(language, "char", (frame, cn) -> {
            Object[] args = allArgs(frame);
            StringBuilder sb = new StringBuilder(args.length);
            for (int i = 0; i < args.length; i++) {
                double d = checkNumber("char", i + 1, args[i]);
                sb.append((char) (int) d);
            }
            return sb.toString();
        }));

        string.rawset("rep", wrap(language, "rep", (frame, cn) -> {
            String s = checkString("rep", 1, arg(frame, 1));
            long n = (long) checkNumber("rep", 2, arg(frame, 2));
            if (n <= 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder(s.length() * (int) n);
            for (long i = 0; i < n; i++) {
                sb.append(s);
            }
            return sb.toString();
        }));

        string.rawset("reverse", wrap(language, "reverse", (frame, cn) ->
                new StringBuilder(checkString("reverse", 1, arg(frame, 1))).reverse().toString()));

        string.rawset("upper", wrap(language, "upper", (frame, cn) ->
                checkString("upper", 1, arg(frame, 1)).toUpperCase()));

        string.rawset("lower", wrap(language, "lower", (frame, cn) ->
                checkString("lower", 1, arg(frame, 1)).toLowerCase()));

        string.rawset("format", wrap(language, "format", (frame, cn) -> {
            String fmt = checkString("format", 1, arg(frame, 1));
            Object[] all = allArgs(frame);
            Object[] rest = Arrays.copyOfRange(all, Math.min(1, all.length), all.length);
            return LuaStringFormat.format(fmt, rest, cn);
        }));

        string.rawset("dump", wrap(language, "dump", (frame, cn) -> {
            // Lua 5.1 §5.4: string.dump produces a binary chunk from a Lua function's bytecode.
            // This is a tree-walking interpreter with no bytecode representation to dump, so this
            // exists only so `string.dump ~= nil` holds and callers get a clean pcall-able error.
            throw LuaException.runtimeError(null, "unable to dump given function");
        }));

        string.rawset("find", wrap(language, "find", (frame, cn) -> findImpl(frame)));
        string.rawset("match", wrap(language, "match", (frame, cn) -> matchImpl(frame)));
        string.rawset("gmatch", wrap(language, "gmatch", (frame, cn) -> gmatchImpl(language, frame)));
        string.rawset("gsub", wrap(language, "gsub", (frame, cn) -> gsubImpl(language, frame, cn)));
    }

    /** Lua's {@code posrelat}: negative positions count from the end of a {@code len}-length string, clamped at 0. */
    private static int posrelat(long pos, int len) {
        if (pos >= 0) {
            return (int) pos;
        }
        if (-pos > len) {
            return 0;
        }
        return (int) (len + pos + 1);
    }

    private static Object findImpl(com.oracle.truffle.api.frame.VirtualFrame frame) {
        String s = checkString("find", 1, arg(frame, 1));
        String pattern = checkString("find", 2, arg(frame, 2));
        int len = s.length();
        long initArg = (long) optNumber(arg(frame, 3), 1);
        boolean plain = LuaTypeUtils.toBoolean(arg(frame, 4));
        int init = posrelat(initArg, len);
        if (init < 1) {
            init = 1;
        } else if (init > len + 1) {
            return LuaNil.INSTANCE;
        }
        int init0 = init - 1;
        if (plain) {
            int idx = s.indexOf(pattern, init0);
            if (idx < 0) {
                return LuaNil.INSTANCE;
            }
            throw new LuaReturnException(new Object[] {(double) (idx + 1), (double) (idx + pattern.length())});
        }
        LuaPattern.Result r = LuaPattern.find(s, pattern, init0);
        if (r == null) {
            return LuaNil.INSTANCE;
        }
        Object[] caps = r.captures;
        Object[] result = new Object[2 + caps.length];
        result[0] = (double) (r.start + 1);
        result[1] = (double) r.end;
        System.arraycopy(caps, 0, result, 2, caps.length);
        throw new LuaReturnException(result);
    }

    private static Object matchImpl(com.oracle.truffle.api.frame.VirtualFrame frame) {
        String s = checkString("match", 1, arg(frame, 1));
        String pattern = checkString("match", 2, arg(frame, 2));
        int len = s.length();
        long initArg = (long) optNumber(arg(frame, 3), 1);
        int init = posrelat(initArg, len);
        if (init < 1) {
            init = 1;
        } else if (init > len + 1) {
            return LuaNil.INSTANCE;
        }
        LuaPattern.Result r = LuaPattern.find(s, pattern, init - 1);
        if (r == null) {
            return LuaNil.INSTANCE;
        }
        if (r.captures.length == 0) {
            return s.substring(r.start, r.end);
        }
        throw new LuaReturnException(r.captures);
    }

    private static Object gmatchImpl(TruffleLanguage<?> language, com.oracle.truffle.api.frame.VirtualFrame frame) {
        String s = checkString("gmatch", 1, arg(frame, 1));
        String pattern = checkString("gmatch", 2, arg(frame, 2));
        int[] pos = {0};
        LuaFunction iterator = wrap(language, "gmatch_iterator", (iterFrame, iterCn) -> {
            while (pos[0] <= s.length()) {
                LuaPattern.Result r = LuaPattern.find(s, pattern, pos[0]);
                if (r == null) {
                    return LuaNil.INSTANCE;
                }
                // Advance past this match; on an empty match, step by one char to avoid looping forever.
                pos[0] = r.end > r.start ? r.end : r.end + 1;
                if (r.captures.length == 0) {
                    return s.substring(r.start, r.end);
                }
                throw new LuaReturnException(r.captures);
            }
            return LuaNil.INSTANCE;
        });
        return iterator;
    }

    private static Object gsubImpl(TruffleLanguage<?> language, com.oracle.truffle.api.frame.VirtualFrame frame, IndirectCallNode callNode) {
        String s = checkString("gsub", 1, arg(frame, 1));
        String pattern = checkString("gsub", 2, arg(frame, 2));
        Object repl = arg(frame, 3);
        Object maxArg = arg(frame, 4);
        long maxN = isNil(maxArg) ? Long.MAX_VALUE : (long) checkNumber("gsub", 4, maxArg);

        boolean anchor = pattern.startsWith("^");
        StringBuilder out = new StringBuilder();
        int pos = 0;
        long count = 0;
        int len = s.length();
        while (pos <= len && count < maxN) {
            LuaPattern.Result r = LuaPattern.find(s, pattern, pos);
            if (r == null) {
                break;
            }
            // find() already scans forward from pos for unanchored patterns, so r.start >= pos.
            out.append(s, pos, r.start);
            String wholeMatch = s.substring(r.start, r.end);
            Object[] caps = r.captures.length > 0 ? r.captures : new Object[] {wholeMatch};
            Object replacement = computeReplacement(callNode, repl, wholeMatch, caps);
            if (replacement == null || replacement == LuaNil.INSTANCE || Boolean.FALSE.equals(replacement)) {
                out.append(wholeMatch);
            } else {
                out.append(LuaTypeUtils.tostring(replacement));
            }
            count++;
            if (r.end > pos) {
                pos = r.end;
            } else {
                // Empty match: keep the character we're on and advance by one to avoid looping forever.
                if (pos < len) {
                    out.append(s.charAt(pos));
                }
                pos++;
            }
            if (anchor) {
                break;
            }
        }
        if (pos < len) {
            out.append(s, pos, len);
        }
        throw new LuaReturnException(new Object[] {out.toString(), (double) count});
    }

    private static Object computeReplacement(IndirectCallNode callNode, Object repl, String wholeMatch, Object[] caps) {
        if (repl instanceof String replStr) {
            return substitutePercents(replStr, wholeMatch, caps);
        }
        if (repl instanceof LuaTable t) {
            Object v = t.rawget(caps.length > 0 ? caps[0] : wholeMatch);
            return v;
        }
        if (repl instanceof LuaFunction) {
            Object[] result = LuaCallSupport.call(null, callNode, repl, caps);
            return LuaCallSupport.first(result);
        }
        throw LuaException.runtimeError(null, "bad argument #3 to 'gsub' (string/function/table expected)");
    }

    private static String substitutePercents(String repl, String wholeMatch, Object[] caps) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < repl.length(); i++) {
            char c = repl.charAt(i);
            if (c == '%' && i + 1 < repl.length()) {
                char nx = repl.charAt(++i);
                if (nx == '%') {
                    sb.append('%');
                } else if (nx == '0') {
                    sb.append(wholeMatch);
                } else if (Character.isDigit(nx)) {
                    int idx = nx - '1';
                    if (idx < 0 || idx >= caps.length) {
                        throw LuaException.runtimeError(null, "invalid capture index in 'gsub'");
                    }
                    sb.append(LuaTypeUtils.tostring(caps[idx]));
                } else {
                    sb.append(nx);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}

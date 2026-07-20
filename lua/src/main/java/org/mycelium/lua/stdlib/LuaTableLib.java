package org.mycelium.lua.stdlib;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import java.util.Arrays;
import java.util.Comparator;
import org.mycelium.lua.LuaException;
import org.mycelium.lua.runtime.LuaNil;
import org.mycelium.lua.runtime.LuaTable;
import org.mycelium.lua.runtime.LuaTypeUtils;

import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.arg;
import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.argCount;
import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.wrap;
import static org.mycelium.lua.stdlib.LuaLibUtil.*;

/** Lua 5.1 &sect;5.5: the {@code table} library. */
final class LuaTableLib {

    private LuaTableLib() {}

    static void install(TruffleLanguage<?> language, LuaTable globals) {
        LuaTable table = new LuaTable();
        globals.rawset("table", table);

        table.rawset("insert", wrap(language, "insert", (frame, cn) -> {
            LuaTable t = checkTable("insert", 1, arg(frame, 1));
            int len = t.rawlen();
            Object posArg = arg(frame, 2);
            Object valueArg = arg(frame, 3);
            int pos;
            Object value;
            if (argCount(frame) <= 2) {
                // table.insert(t, value) — the two-argument form.
                pos = len + 1;
                value = posArg;
            } else {
                pos = (int) checkNumber("insert", 2, posArg);
                value = valueArg;
                if (pos < 1 || pos > len + 1) {
                    throw LuaException.runtimeError(null, "bad argument #2 to 'insert' (position out of bounds)");
                }
            }
            for (int i = len; i >= pos; i--) {
                Object v = t.rawget((long) i);
                t.rawset((long) (i + 1), v == null ? LuaNil.INSTANCE : v);
            }
            t.rawset((long) pos, value);
            return LuaNil.INSTANCE;
        }));

        table.rawset("remove", wrap(language, "remove", (frame, cn) -> {
            LuaTable t = checkTable("remove", 1, arg(frame, 1));
            int size = t.rawlen();
            int pos = (int) optNumber(arg(frame, 2), size);
            if (size == 0) {
                return LuaNil.INSTANCE;
            }
            if (pos != size && (pos < 1 || pos > size + 1)) {
                throw LuaException.runtimeError(null, "bad argument #2 to 'remove' (position out of bounds)");
            }
            Object removed = t.rawget((long) pos);
            for (int i = pos; i < size; i++) {
                Object v = t.rawget((long) (i + 1));
                t.rawset((long) i, v == null ? LuaNil.INSTANCE : v);
            }
            t.rawset((long) size, LuaNil.INSTANCE);
            return removed == null ? LuaNil.INSTANCE : removed;
        }));

        table.rawset("concat", wrap(language, "concat", (frame, cn) -> {
            LuaTable t = checkTable("concat", 1, arg(frame, 1));
            String sep = optString(arg(frame, 2), "");
            long i = (long) optNumber(arg(frame, 3), 1);
            long j = (long) optNumber(arg(frame, 4), t.rawlen());
            StringBuilder sb = new StringBuilder();
            for (long k = i; k <= j; k++) {
                Object v = t.rawget(k);
                if (!(v instanceof String) && !(v instanceof Double)) {
                    throw LuaException.runtimeError(
                            null, "invalid value (" + LuaTypeUtils.typeName(v) + ") at index " + k + " in table for 'concat'");
                }
                sb.append(LuaTypeUtils.tostring(v));
                if (k < j) {
                    sb.append(sep);
                }
            }
            return sb.toString();
        }));

        table.rawset("sort", wrap(language, "sort", (frame, callNode) -> {
            LuaTable t = checkTable("sort", 1, arg(frame, 1));
            Object compArg = arg(frame, 2);
            int n = t.rawlen();
            Object[] arr = new Object[n];
            for (int i = 0; i < n; i++) {
                arr[i] = t.rawget((long) (i + 1));
            }
            Comparator<Object> comparator = isNil(compArg)
                    ? LuaTableLib::defaultLess0
                    : (a, b) -> compareWith(callNode, compArg, a, b);
            Arrays.sort(arr, comparator);
            for (int i = 0; i < n; i++) {
                t.rawset((long) (i + 1), arr[i]);
            }
            return LuaNil.INSTANCE;
        }));

        table.rawset("maxn", wrap(language, "maxn", (frame, cn) -> {
            LuaTable t = checkTable("maxn", 1, arg(frame, 1));
            double maxn = 0;
            Object[] kv = t.next(null);
            while (kv != null) {
                Object k = kv[0];
                double numeric = -1;
                if (k instanceof Long l) {
                    numeric = l;
                } else if (k instanceof Double d) {
                    numeric = d;
                }
                if (numeric > maxn) {
                    maxn = numeric;
                }
                kv = t.next(k);
            }
            return maxn;
        }));
    }

    private static int defaultLess0(Object a, Object b) {
        if (defaultLess(a, b)) {
            return -1;
        }
        if (defaultLess(b, a)) {
            return 1;
        }
        return 0;
    }

    /** Widens a {@link Double} or {@link Long} to {@code double}; {@link LuaTable} normalizes
     * whole-number keys to {@code Long} internally, so table-sourced values can arrive here as
     * either type even though Lua 5.1 itself only knows doubles. */
    private static double toDouble(Object value) {
        return value instanceof Long l ? l.doubleValue() : (Double) value;
    }

    private static boolean isNumber(Object value) {
        return value instanceof Double || value instanceof Long;
    }

    private static boolean defaultLess(Object a, Object b) {
        if (isNumber(a) && isNumber(b)) {
            return toDouble(a) < toDouble(b);
        }
        if (a instanceof String sa && b instanceof String sb) {
            return sa.compareTo(sb) < 0;
        }
        throw LuaException.runtimeError(
                null, "attempt to compare two " + LuaTypeUtils.typeName(a) + " values");
    }

    private static int compareWith(IndirectCallNode callNode, Object compFn, Object a, Object b) {
        boolean lt = LuaTypeUtils.toBoolean(
                LuaCallSupport.first(LuaCallSupport.call(null, callNode, compFn, new Object[] {a, b})));
        if (lt) {
            return -1;
        }
        boolean gt = LuaTypeUtils.toBoolean(
                LuaCallSupport.first(LuaCallSupport.call(null, callNode, compFn, new Object[] {b, a})));
        return gt ? 1 : 0;
    }
}

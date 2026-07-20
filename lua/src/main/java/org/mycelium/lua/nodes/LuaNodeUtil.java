package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import org.mycelium.lua.LuaException;
import org.mycelium.lua.runtime.LuaFunction;
import org.mycelium.lua.runtime.LuaNil;
import org.mycelium.lua.runtime.LuaStringMetatable;
import org.mycelium.lua.runtime.LuaTable;
import org.mycelium.lua.runtime.LuaTypeUtils;

/**
 * Shared, non-Truffle-DSL helper logic used by several node implementations: metamethod lookup
 * and dispatch, the {@code __index}/{@code __newindex}/{@code __call} chains, list-expression
 * evaluation with Lua's "only the last expression expands" rule, upvalue frame-chain walking,
 * and the comparison/equality semantics of Lua 5.1 &sect;2.5.2.
 *
 * <p>Kept as a single utility class (rather than duplicated per node) because nearly identical
 * "try the metamethod, else raise a type error" logic is needed by a dozen otherwise-unrelated
 * node classes.
 */
final class LuaNodeUtil {

    /** Lua reference implementations cap metamethod chains (e.g. {@code __index} chasing another
     * table's {@code __index}) to guard against accidental infinite loops; 100 mirrors that. */
    private static final int MAX_METAMETHOD_DEPTH = 100;

    private static final Object[] NO_VALUES = new Object[0];

    private LuaNodeUtil() {}

    static boolean isNil(Object value) {
        return value == null || value == LuaNil.INSTANCE;
    }

    private static Object nilSafe(Object value) {
        return value == null ? LuaNil.INSTANCE : value;
    }

    static Object first(Object[] values) {
        return values.length > 0 ? nilSafe(values[0]) : LuaNil.INSTANCE;
    }

    /** Looks up a metamethod by event name ({@code "__add"}, {@code "__index"}, ...). Only tables
     * carry metatables in this implementation; every other type has none. */
    static Object getMetamethod(Object value, String event) {
        if (value instanceof LuaTable table) {
            return table.getMetamethod(event);
        }
        return null;
    }

    /**
     * Calls {@code callee} with {@code args}, following the {@code __call} metamethod chain
     * (Lua 5.1 &sect;2.8) when {@code callee} is not itself a function. Returns the raw
     * (possibly empty) result array.
     */
    static Object[] invoke(Node location, IndirectCallNode callNode, Object callee, Object[] args) {
        Object target = callee;
        Object[] callArgs = args;
        for (int depth = 0; depth < MAX_METAMETHOD_DEPTH; depth++) {
            if (target instanceof LuaFunction fn) {
                Object[] frameArgs = new Object[callArgs.length + 1];
                frameArgs[0] = fn;
                System.arraycopy(callArgs, 0, frameArgs, 1, callArgs.length);
                Object result = callNode.call(fn.getCallTarget(), (Object[]) frameArgs);
                return result instanceof Object[] resultArray ? resultArray : new Object[] {nilSafe(result)};
            }
            Object handler = getMetamethod(target, "__call");
            if (handler == null) {
                throw LuaException.typeError(location, "call", callee);
            }
            Object[] newArgs = new Object[callArgs.length + 1];
            newArgs[0] = target;
            System.arraycopy(callArgs, 0, newArgs, 1, callArgs.length);
            callArgs = newArgs;
            target = handler;
        }
        throw LuaException.runtimeError(location, "'__call' chain too long; possible loop");
    }

    /**
     * Evaluates a list of expressions applying Lua 5.1 &sect;2.5.7's adjustment rule: every
     * expression except the last is truncated to its first value; the last expression, if it can
     * produce multiple values (a call or {@code ...}), contributes all of them.
     */
    static Object[] evaluateExpressionList(VirtualFrame frame, LuaExpressionNode[] exprs) {
        if (exprs.length == 0) {
            return NO_VALUES;
        }
        int fixedCount = exprs.length - 1;
        Object[] tail = exprs[fixedCount].executeMultiple(frame);
        Object[] result = new Object[fixedCount + tail.length];
        for (int i = 0; i < fixedCount; i++) {
            result[i] = nilSafe(exprs[i].executeGeneric(frame));
        }
        System.arraycopy(tail, 0, result, fixedCount, tail.length);
        return result;
    }

    /** Walks {@code depth} enclosing-function hops starting from the closure executing {@code frame}. */
    static Frame findEnclosingFrame(Frame frame, int depth) {
        LuaFunction fn = LuaRootNode.getCurrentFunction(frame);
        if (fn == null) {
            throw new IllegalStateException("no enclosing function reference in frame arguments");
        }
        Frame target = fn.getEnclosingFrame();
        for (int i = 1; i < depth; i++) {
            LuaFunction outer = LuaRootNode.getCurrentFunction(target);
            if (outer == null) {
                throw new IllegalStateException("no enclosing function reference in frame arguments");
            }
            target = outer.getEnclosingFrame();
        }
        return target;
    }

    /** {@code table[key]} with the {@code __index} chain (Lua 5.1 &sect;2.8). */
    static Object tableIndex(Node location, Object receiver, Object key, IndirectCallNode callNode) {
        Object current = receiver;
        for (int depth = 0; depth < MAX_METAMETHOD_DEPTH; depth++) {
            if (current instanceof LuaTable table) {
                Object value = table.rawget(key);
                if (!isNil(value)) {
                    return value;
                }
                Object handler = table.getMetamethod("__index");
                if (handler == null) {
                    return LuaNil.INSTANCE;
                }
                if (handler instanceof LuaFunction) {
                    return first(invoke(location, callNode, handler, new Object[] {current, key}));
                }
                current = handler;
            } else if (current instanceof String) {
                // Lua 5.1 §5.4: strings share a metatable whose __index is the string library
                // table, letting `("hi"):upper()` resolve as a method call. __index lives as a
                // plain field directly on stringMetatable (rawget), not behind a further nested
                // metatable — stringMetatable.getMetamethod("__index") would incorrectly look at
                // stringMetatable's own (nonexistent) metatable instead.
                LuaTable stringMetatable = LuaStringMetatable.get();
                Object rawHandler = stringMetatable != null ? stringMetatable.rawget("__index") : null;
                Object handler = (rawHandler == null || isNil(rawHandler)) ? null : rawHandler;
                if (handler == null) {
                    throw LuaException.typeError(location, "index", current);
                }
                if (handler instanceof LuaFunction) {
                    return first(invoke(location, callNode, handler, new Object[] {current, key}));
                }
                current = handler;
            } else {
                throw LuaException.typeError(location, "index", current);
            }
        }
        throw LuaException.runtimeError(location, "'__index' chain too long; possible loop");
    }

    /** {@code table[key] = value} with the {@code __newindex} chain (Lua 5.1 &sect;2.8). */
    static void tableNewIndex(Node location, Object receiver, Object key, Object value, IndirectCallNode callNode) {
        Object current = receiver;
        for (int depth = 0; depth < MAX_METAMETHOD_DEPTH; depth++) {
            if (current instanceof LuaTable table) {
                Object existing = table.rawget(key);
                if (!isNil(existing)) {
                    table.rawset(key, value);
                    return;
                }
                Object handler = table.getMetamethod("__newindex");
                if (handler == null) {
                    requireValidKey(location, key);
                    table.rawset(key, value);
                    return;
                }
                if (handler instanceof LuaFunction) {
                    invoke(location, callNode, handler, new Object[] {current, key, value});
                    return;
                }
                current = handler;
            } else {
                throw LuaException.typeError(location, "index", current);
            }
        }
        throw LuaException.runtimeError(location, "'__newindex' chain too long; possible loop");
    }

    private static void requireValidKey(Node location, Object key) {
        if (isNil(key)) {
            throw LuaException.runtimeError(location, "table index is nil");
        }
        if (key instanceof Double d && Double.isNaN(d)) {
            throw LuaException.runtimeError(location, "table index is NaN");
        }
    }

    /** Shared "coerce both operands, else metamethod, else error" logic for the arithmetic
     * operators (Lua 5.1 &sect;2.5.1). {@code leftNum}/{@code rightNum} are the results of
     * {@link LuaTypeUtils#toNumber} on {@code left}/{@code right}; callers apply the operator
     * themselves when both are non-null so each node keeps its own arithmetic inline. */
    static Object arithMetamethodOrError(
            Node location, Object left, Object right, Double leftNum, Double rightNum, String event, IndirectCallNode callNode) {
        Object handler = getMetamethod(left, event);
        if (handler == null) {
            handler = getMetamethod(right, event);
        }
        if (handler != null) {
            return first(invoke(location, callNode, handler, new Object[] {left, right}));
        }
        Object bad = leftNum == null ? left : right;
        throw LuaException.arithError(location, bad);
    }

    static Object unaryMetamethodOrError(Node location, Object operand, String event, IndirectCallNode callNode) {
        Object handler = getMetamethod(operand, event);
        if (handler != null) {
            return first(invoke(location, callNode, handler, new Object[] {operand, operand}));
        }
        throw LuaException.arithError(location, operand);
    }

    /** {@code numberValue instanceof Double} coercion to a Lua-default string, or {@code null}
     * if {@code value} cannot participate in concatenation without a metamethod. */
    static String concatCoerce(Object value) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Double) {
            return LuaTypeUtils.tostring(value);
        }
        return null;
    }

    /**
     * Lua 5.1 &sect;2.5.2 equality: different types are never equal (no coercion); numbers,
     * strings, and booleans compare by value; tables and functions compare by reference.
     *
     * <p><b>Flagged uncertainty:</b> per this task's brief, {@code __eq} is invoked only when
     * both operands are tables that are not primitively equal <em>and expose the same
     * {@code __eq} metamethod value</em>. Other Lua 5.1 descriptions (e.g. the reference manual's
     * &sect;2.8 prose) can be read as trying the left operand's {@code __eq} unconditionally,
     * falling back to the right operand's if the left has none, with no "same handler" test. This
     * implementation follows the brief's literal wording; flagging so it can be corrected if the
     * "same handler" requirement turns out not to match the intended target semantics.
     */
    static boolean luaEquals(Node location, Object a, Object b, IndirectCallNode callNode) {
        Object av = nilSafe(a);
        Object bv = nilSafe(b);
        if (isNil(av) && isNil(bv)) {
            return true;
        }
        if (isNil(av) != isNil(bv)) {
            return false;
        }
        if (isNumber(av) && isNumber(bv)) {
            return toDouble(av) == toDouble(bv);
        }
        if (av instanceof String sa && bv instanceof String sb) {
            return sa.equals(sb);
        }
        if (av instanceof Boolean ba && bv instanceof Boolean bb) {
            return ba.equals(bb);
        }
        if (av instanceof LuaTable ta && bv instanceof LuaTable tb) {
            if (ta == tb) {
                return true;
            }
            Object h1 = ta.getMetamethod("__eq");
            Object h2 = tb.getMetamethod("__eq");
            if (h1 != null && h1 == h2) {
                return LuaTypeUtils.toBoolean(first(invoke(location, callNode, h1, new Object[] {av, bv})));
            }
            return false;
        }
        if (av instanceof LuaFunction && bv instanceof LuaFunction) {
            return av == bv;
        }
        if (av.getClass() != bv.getClass()) {
            return false;
        }
        return av.equals(bv);
    }

    /** Whether {@code value} is one of the numeric representations that can appear in this
     * implementation: Lua source and arithmetic always produce {@link Double} (Lua 5.1 has no
     * integer subtype), but {@link LuaTable} normalizes whole-number keys to {@link Long}
     * internally for array-part indexing (see {@link LuaTable}'s class doc). {@link LuaTable#next}
     * converts those back to {@code Double} before they reach user code, but comparisons stay
     * defensive here in case a {@code Long} reaches this code by some other path. */
    private static boolean isNumber(Object value) {
        return value instanceof Double || value instanceof Long;
    }

    /** Widens a {@link Double} or {@link Long} to {@code double}; only call after {@link #isNumber}. */
    private static double toDouble(Object value) {
        return value instanceof Long l ? l.doubleValue() : (Double) value;
    }

    /** Lua 5.1 &sect;2.5.2 {@code <}: numeric or lexicographic comparison, else {@code __lt}. */
    static boolean lessThan(Node location, Object a, Object b, IndirectCallNode callNode) {
        if (isNumber(a) && isNumber(b)) {
            return toDouble(a) < toDouble(b);
        }
        if (a instanceof String sa && b instanceof String sb) {
            return sa.compareTo(sb) < 0;
        }
        Object handler = getMetamethod(a, "__lt");
        if (handler == null) {
            handler = getMetamethod(b, "__lt");
        }
        if (handler != null) {
            return LuaTypeUtils.toBoolean(first(invoke(location, callNode, handler, new Object[] {a, b})));
        }
        throw compareError(location, a, b);
    }

    /** Lua 5.1 &sect;2.5.2 {@code <=}: numeric/lexicographic, else {@code __le}, else {@code not (b < a)}. */
    static boolean lessEqual(Node location, Object a, Object b, IndirectCallNode callNode) {
        if (isNumber(a) && isNumber(b)) {
            return toDouble(a) <= toDouble(b);
        }
        if (a instanceof String sa && b instanceof String sb) {
            return sa.compareTo(sb) <= 0;
        }
        Object handler = getMetamethod(a, "__le");
        if (handler == null) {
            handler = getMetamethod(b, "__le");
        }
        if (handler != null) {
            return LuaTypeUtils.toBoolean(first(invoke(location, callNode, handler, new Object[] {a, b})));
        }
        Object ltHandler = getMetamethod(a, "__lt");
        if (ltHandler == null) {
            ltHandler = getMetamethod(b, "__lt");
        }
        if (ltHandler != null) {
            return !LuaTypeUtils.toBoolean(first(invoke(location, callNode, ltHandler, new Object[] {b, a})));
        }
        throw compareError(location, a, b);
    }

    private static LuaException compareError(Node location, Object a, Object b) {
        String ta = LuaTypeUtils.typeName(a);
        String tb = LuaTypeUtils.typeName(b);
        String message = ta.equals(tb)
                ? "attempt to compare two " + ta + " values"
                : "attempt to compare " + ta + " with " + tb;
        return LuaException.runtimeError(location, message);
    }
}

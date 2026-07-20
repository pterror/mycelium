package org.mycelium.lua.stdlib;

import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import org.mycelium.lua.LuaException;
import org.mycelium.lua.runtime.LuaFunction;
import org.mycelium.lua.runtime.LuaNil;
import org.mycelium.lua.runtime.LuaTable;

/**
 * Standard-library-local mirror of {@code org.mycelium.lua.nodes.LuaNodeUtil}'s {@code invoke}
 * helper (that class is package-private to {@code org.mycelium.lua.nodes}, so builtins that need
 * to call back into Lua &mdash; {@code pcall}, {@code xpcall}, {@code pairs}/{@code ipairs}
 * iterators, {@code table.sort} comparators, ... &mdash; need their own copy of the {@code
 * __call} metamethod chain logic (Lua 5.1 &sect;2.8).
 */
final class LuaCallSupport {

    private static final int MAX_CALL_METAMETHOD_DEPTH = 100;

    private LuaCallSupport() {}

    static Object[] call(Node location, IndirectCallNode callNode, Object callee, Object[] args) {
        Object target = callee;
        Object[] callArgs = args;
        for (int depth = 0; depth < MAX_CALL_METAMETHOD_DEPTH; depth++) {
            if (target instanceof LuaFunction fn) {
                Object[] frameArgs = new Object[callArgs.length + 1];
                frameArgs[0] = fn;
                System.arraycopy(callArgs, 0, frameArgs, 1, callArgs.length);
                Object result = callNode.call(fn.getCallTarget(), frameArgs);
                if (result instanceof Object[] arr) {
                    return arr;
                }
                return new Object[] {result == null ? LuaNil.INSTANCE : result};
            }
            Object handler = callMetamethod(target);
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

    private static Object callMetamethod(Object value) {
        if (value instanceof LuaTable table) {
            return table.getMetamethod("__call");
        }
        return null;
    }

    static Object first(Object[] values) {
        if (values.length == 0) {
            return LuaNil.INSTANCE;
        }
        Object v = values[0];
        return v == null ? LuaNil.INSTANCE : v;
    }
}

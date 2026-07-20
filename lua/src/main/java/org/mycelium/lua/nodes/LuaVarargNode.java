package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import org.mycelium.lua.runtime.LuaNil;

/** Lua 5.1 &sect;2.5.9 {@code ...}. Reads the vararg values stashed by the calling convention at
 * {@link LuaRootNode#VARARGS_FRAME_SLOT}. In a single-value context yields the first vararg (or
 * {@code nil} if there are none); in a multi-value context yields all of them. */
public final class LuaVarargNode extends LuaExpressionNode {
    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object[] values = getVarargs(frame);
        return values.length > 0 ? values[0] : LuaNil.INSTANCE;
    }

    @Override
    public Object[] executeMultiple(VirtualFrame frame) {
        return getVarargs(frame);
    }

    private Object[] getVarargs(VirtualFrame frame) {
        Object stored = frame.getObject(LuaRootNode.VARARGS_FRAME_SLOT);
        return stored instanceof Object[] values ? values : new Object[0];
    }
}

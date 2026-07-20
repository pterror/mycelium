package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import org.mycelium.lua.runtime.LuaTypeUtils;

/** Lua 5.1 &sect;2.5.3 {@code not}: always returns a boolean. */
public final class LuaNotNode extends LuaExpressionNode {
    @Child private LuaExpressionNode operand;

    public LuaNotNode(LuaExpressionNode operand) {
        this.operand = operand;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return !LuaTypeUtils.toBoolean(operand.executeGeneric(frame));
    }
}

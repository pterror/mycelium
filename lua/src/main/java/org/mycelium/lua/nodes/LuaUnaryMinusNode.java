package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node.Child;
import org.mycelium.lua.runtime.LuaTypeUtils;

/** Lua 5.1 &sect;2.5.1 unary {@code -}. Falls back to {@code __unm}. */
public final class LuaUnaryMinusNode extends LuaExpressionNode {
    @Child private LuaExpressionNode operand;
    @Child private IndirectCallNode callNode = IndirectCallNode.create();

    public LuaUnaryMinusNode(LuaExpressionNode operand) {
        this.operand = operand;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object v = operand.executeGeneric(frame);
        Double d = LuaTypeUtils.toNumber(v);
        if (d != null) {
            return -d;
        }
        return LuaNodeUtil.unaryMetamethodOrError(this, v, "__unm", callNode);
    }
}

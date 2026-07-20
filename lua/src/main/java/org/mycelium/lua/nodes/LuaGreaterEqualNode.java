package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node.Child;

/** Lua 5.1 &sect;2.5.2 {@code >=}, implemented as {@code b <= a} (left operand still evaluated
 * first). */
public final class LuaGreaterEqualNode extends LuaExpressionNode {
    @Child private LuaExpressionNode left;
    @Child private LuaExpressionNode right;
    @Child private IndirectCallNode callNode = IndirectCallNode.create();

    public LuaGreaterEqualNode(LuaExpressionNode left, LuaExpressionNode right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object l = left.executeGeneric(frame);
        Object r = right.executeGeneric(frame);
        return LuaNodeUtil.lessEqual(this, r, l, callNode);
    }
}

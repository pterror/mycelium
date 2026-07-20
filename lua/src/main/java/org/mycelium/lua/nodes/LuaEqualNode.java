package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node.Child;

/** Lua 5.1 &sect;2.5.2 {@code ==}. See {@link LuaNodeUtil#luaEquals} for the exact semantics
 * (including a flagged uncertainty about the {@code __eq} lookup rule). */
public final class LuaEqualNode extends LuaExpressionNode {
    @Child private LuaExpressionNode left;
    @Child private LuaExpressionNode right;
    @Child private IndirectCallNode callNode = IndirectCallNode.create();

    public LuaEqualNode(LuaExpressionNode left, LuaExpressionNode right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object l = left.executeGeneric(frame);
        Object r = right.executeGeneric(frame);
        return LuaNodeUtil.luaEquals(this, l, r, callNode);
    }
}

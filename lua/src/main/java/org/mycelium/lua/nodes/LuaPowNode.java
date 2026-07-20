package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node.Child;
import org.mycelium.lua.runtime.LuaTypeUtils;

/** Lua 5.1 &sect;2.5.1 {@code ^}, always float exponentiation via {@link Math#pow}. Falls back to
 * {@code __pow}. */
public final class LuaPowNode extends LuaExpressionNode {
    @Child private LuaExpressionNode left;
    @Child private LuaExpressionNode right;
    @Child private IndirectCallNode callNode = IndirectCallNode.create();

    public LuaPowNode(LuaExpressionNode left, LuaExpressionNode right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object l = left.executeGeneric(frame);
        Object r = right.executeGeneric(frame);
        Double ld = LuaTypeUtils.toNumber(l);
        Double rd = LuaTypeUtils.toNumber(r);
        if (ld != null && rd != null) {
            return Math.pow(ld, rd);
        }
        return LuaNodeUtil.arithMetamethodOrError(this, l, r, ld, rd, "__pow", callNode);
    }
}

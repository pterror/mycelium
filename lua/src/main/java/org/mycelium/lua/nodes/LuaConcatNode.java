package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node.Child;
import org.mycelium.lua.LuaException;

/** Lua 5.1 &sect;2.5.4 {@code ..}. Numbers coerce to their default string form; falls back to
 * {@code __concat} when either operand is neither a string nor a number. */
public final class LuaConcatNode extends LuaExpressionNode {
    @Child private LuaExpressionNode left;
    @Child private LuaExpressionNode right;
    @Child private IndirectCallNode callNode = IndirectCallNode.create();

    public LuaConcatNode(LuaExpressionNode left, LuaExpressionNode right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object l = left.executeGeneric(frame);
        Object r = right.executeGeneric(frame);
        String ls = LuaNodeUtil.concatCoerce(l);
        String rs = LuaNodeUtil.concatCoerce(r);
        if (ls != null && rs != null) {
            return ls + rs;
        }
        Object handler = LuaNodeUtil.getMetamethod(l, "__concat");
        if (handler == null) {
            handler = LuaNodeUtil.getMetamethod(r, "__concat");
        }
        if (handler != null) {
            return LuaNodeUtil.first(LuaNodeUtil.invoke(this, callNode, handler, new Object[] {l, r}));
        }
        Object bad = ls == null ? l : r;
        throw LuaException.typeError(this, "concatenate", bad);
    }
}

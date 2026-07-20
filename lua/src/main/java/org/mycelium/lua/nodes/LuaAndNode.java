package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import org.mycelium.lua.runtime.LuaTypeUtils;

/** Lua 5.1 &sect;2.5.3 {@code and}: short-circuits on a falsy first operand, returning it
 * unchanged (no boolean coercion); otherwise returns the second operand's value. */
public final class LuaAndNode extends LuaExpressionNode {
    @Child private LuaExpressionNode left;
    @Child private LuaExpressionNode right;

    public LuaAndNode(LuaExpressionNode left, LuaExpressionNode right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object l = left.executeGeneric(frame);
        if (!LuaTypeUtils.toBoolean(l)) {
            return l;
        }
        return right.executeGeneric(frame);
    }
}

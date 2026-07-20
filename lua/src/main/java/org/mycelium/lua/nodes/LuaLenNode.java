package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node.Child;
import org.mycelium.lua.LuaException;
import org.mycelium.lua.runtime.LuaTable;

/** Lua 5.1 &sect;2.5.5 unary {@code #}. Strings yield their length; tables use {@code __len} if
 * present, else {@link LuaTable#rawlen()}. */
public final class LuaLenNode extends LuaExpressionNode {
    @Child private LuaExpressionNode operand;
    @Child private IndirectCallNode callNode = IndirectCallNode.create();

    public LuaLenNode(LuaExpressionNode operand) {
        this.operand = operand;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object v = operand.executeGeneric(frame);
        if (v instanceof String s) {
            return (double) s.length();
        }
        if (v instanceof LuaTable table) {
            Object handler = table.getMetamethod("__len");
            if (handler != null) {
                return LuaNodeUtil.first(LuaNodeUtil.invoke(this, callNode, handler, new Object[] {v}));
            }
            return (double) table.rawlen();
        }
        throw LuaException.typeError(this, "get length of", v);
    }
}

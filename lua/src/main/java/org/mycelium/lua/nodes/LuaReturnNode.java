package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Children;
import org.mycelium.lua.nodes.controlflow.LuaReturnException;

/** {@code return expr1, ..., exprn} (Lua 5.1 &sect;2.4.4). Values follow the usual list-expression
 * adjustment (only the last expression may expand to multiple values). */
public final class LuaReturnNode extends LuaStatementNode {
    @Children private final LuaExpressionNode[] values;

    public LuaReturnNode(LuaExpressionNode[] values) {
        this.values = values;
    }

    @Override
    public void executeVoid(VirtualFrame frame) {
        Object[] result = LuaNodeUtil.evaluateExpressionList(frame, values);
        throw new LuaReturnException(result);
    }
}

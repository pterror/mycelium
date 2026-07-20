package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;

/** Wraps an expression (typically a function/method call) so it can be used as a statement,
 * discarding its result. */
public final class LuaExpressionStatementNode extends LuaStatementNode {
    @Child private LuaExpressionNode expression;

    public LuaExpressionStatementNode(LuaExpressionNode expression) {
        this.expression = expression;
    }

    @Override
    public void executeVoid(VirtualFrame frame) {
        expression.executeGeneric(frame);
    }
}

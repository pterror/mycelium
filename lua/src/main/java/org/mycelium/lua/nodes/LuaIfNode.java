package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import org.mycelium.lua.runtime.LuaTypeUtils;

/**
 * {@code if cond then ... else ... end} (Lua 5.1 &sect;2.4.4). {@code elseif} chains are simply
 * represented as a nested {@code LuaIfNode} in the {@code elseBody} position; {@code elseBody} is
 * {@code null} when there is no {@code else}/{@code elseif} clause.
 */
public final class LuaIfNode extends LuaStatementNode {
    @Child private LuaExpressionNode condition;
    @Child private LuaStatementNode thenBody;
    @Child private LuaStatementNode elseBody;

    public LuaIfNode(LuaExpressionNode condition, LuaStatementNode thenBody, LuaStatementNode elseBody) {
        this.condition = condition;
        this.thenBody = thenBody;
        this.elseBody = elseBody;
    }

    @Override
    public void executeVoid(VirtualFrame frame) {
        if (LuaTypeUtils.toBoolean(condition.executeGeneric(frame))) {
            thenBody.executeVoid(frame);
        } else if (elseBody != null) {
            elseBody.executeVoid(frame);
        }
    }
}

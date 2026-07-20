package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Children;

/** A sequence of statements, executed in order. */
public final class LuaBlockNode extends LuaStatementNode {
    @Children private final LuaStatementNode[] statements;

    public LuaBlockNode(LuaStatementNode[] statements) {
        this.statements = statements;
    }

    @Override
    public void executeVoid(VirtualFrame frame) {
        for (LuaStatementNode statement : statements) {
            statement.executeVoid(frame);
        }
    }
}

package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import org.mycelium.lua.nodes.controlflow.LuaBreakException;
import org.mycelium.lua.runtime.LuaTypeUtils;

/** {@code while cond do ... end} (Lua 5.1 &sect;2.4.5). */
public final class LuaWhileNode extends LuaStatementNode {
    @Child private LuaExpressionNode condition;
    @Child private LuaStatementNode body;

    public LuaWhileNode(LuaExpressionNode condition, LuaStatementNode body) {
        this.condition = condition;
        this.body = body;
    }

    @Override
    public void executeVoid(VirtualFrame frame) {
        try {
            while (LuaTypeUtils.toBoolean(condition.executeGeneric(frame))) {
                body.executeVoid(frame);
            }
        } catch (LuaBreakException e) {
            // `break` terminates the loop.
        }
    }
}

package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import org.mycelium.lua.nodes.controlflow.LuaBreakException;
import org.mycelium.lua.runtime.LuaTypeUtils;

/** {@code repeat ... until cond} (Lua 5.1 &sect;2.4.4). The body executes at least once, and the
 * condition is evaluated in the same frame as the body, so it can see locals the body declared
 * (&sect;2.4.4's "the scope of a local variable declared inside the loop... extends till the end
 * of the block, and the loop's condition can access it"). */
public final class LuaRepeatNode extends LuaStatementNode {
    @Child private LuaStatementNode body;
    @Child private LuaExpressionNode condition;

    public LuaRepeatNode(LuaStatementNode body, LuaExpressionNode condition) {
        this.body = body;
        this.condition = condition;
    }

    @Override
    public void executeVoid(VirtualFrame frame) {
        try {
            do {
                body.executeVoid(frame);
            } while (!LuaTypeUtils.toBoolean(condition.executeGeneric(frame)));
        } catch (LuaBreakException e) {
            // `break` terminates the loop.
        }
    }
}

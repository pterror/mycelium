package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import org.mycelium.lua.nodes.controlflow.LuaBreakException;

/** {@code break} (Lua 5.1 &sect;2.4.4): terminates the innermost enclosing loop. */
public final class LuaBreakNode extends LuaStatementNode {
    @Override
    public void executeVoid(VirtualFrame frame) {
        throw LuaBreakException.INSTANCE;
    }
}

package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;

/** {@code do ... end}: a block that only exists for lexical scoping purposes; execution simply
 * delegates to the wrapped body. */
public final class LuaDoBlockNode extends LuaStatementNode {
    @Child private LuaStatementNode body;

    public LuaDoBlockNode(LuaStatementNode body) {
        this.body = body;
    }

    @Override
    public void executeVoid(VirtualFrame frame) {
        body.executeVoid(frame);
    }
}

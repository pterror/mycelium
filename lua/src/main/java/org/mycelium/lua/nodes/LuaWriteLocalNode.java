package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;

/** Writes a local variable to a frame slot. Lua's own {@code local}/assignment statements are not
 * expressions, but this node is modeled as one (returning the written value) so it can be reused
 * as a primitive both directly as a statement (via {@code LuaExpressionStatementNode}) and inside
 * compound constructs (e.g. loop-variable initialization) that want the value back. */
public final class LuaWriteLocalNode extends LuaExpressionNode {
    private final int slot;
    @Child private LuaExpressionNode value;

    public LuaWriteLocalNode(int slot, LuaExpressionNode value) {
        this.slot = slot;
        this.value = value;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object v = value.executeGeneric(frame);
        frame.setObject(slot, v);
        return v;
    }
}

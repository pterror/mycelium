package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;

/** Writes a variable captured from an enclosing function's frame. See {@link LuaReadUpvalueNode}
 * for the {@code scopeDepth} convention. Modeled as an expression (returns the written value),
 * matching {@link LuaWriteLocalNode}. */
public final class LuaWriteUpvalueNode extends LuaExpressionNode {
    private final int scopeDepth;
    private final int slot;
    @Child private LuaExpressionNode value;

    public LuaWriteUpvalueNode(int scopeDepth, int slot, LuaExpressionNode value) {
        this.scopeDepth = scopeDepth;
        this.slot = slot;
        this.value = value;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object v = value.executeGeneric(frame);
        Frame target = LuaNodeUtil.findEnclosingFrame(frame, scopeDepth);
        target.setObject(slot, v);
        return v;
    }
}

package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

/** Reads a local variable from a frame slot. Assumes the parser's {@code FrameDescriptor}
 * allocates every local-variable slot with {@code FrameSlotKind.Object} (this implementation
 * does not yet use Truffle DSL specializations, so slots are not narrowed to primitive kinds). */
public final class LuaReadLocalNode extends LuaExpressionNode {
    private final int slot;

    public LuaReadLocalNode(int slot) {
        this.slot = slot;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return frame.getObject(slot);
    }
}

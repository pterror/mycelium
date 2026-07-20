package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;

/** Reads a variable captured from an enclosing function's frame. {@code scopeDepth} counts how
 * many enclosing-function hops to walk (1 = the immediately enclosing function) &mdash; see
 * {@link LuaRootNode} for how the frame chain is recovered at runtime. */
public final class LuaReadUpvalueNode extends LuaExpressionNode {
    private final int scopeDepth;
    private final int slot;

    public LuaReadUpvalueNode(int scopeDepth, int slot) {
        this.scopeDepth = scopeDepth;
        this.slot = slot;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Frame target = LuaNodeUtil.findEnclosingFrame(frame, scopeDepth);
        return target.getObject(slot);
    }
}

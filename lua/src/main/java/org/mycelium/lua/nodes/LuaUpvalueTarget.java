package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;

/** Assigns to a variable captured from an enclosing function's frame. See
 * {@link LuaReadUpvalueNode} for the {@code scopeDepth} convention. */
public final class LuaUpvalueTarget extends LuaAssignmentTarget {
    private final int scopeDepth;
    private final int slot;

    public LuaUpvalueTarget(int scopeDepth, int slot) {
        this.scopeDepth = scopeDepth;
        this.slot = slot;
    }

    @Override
    public void assign(VirtualFrame frame, Object value) {
        Frame target = LuaNodeUtil.findEnclosingFrame(frame, scopeDepth);
        target.setObject(slot, value);
    }
}

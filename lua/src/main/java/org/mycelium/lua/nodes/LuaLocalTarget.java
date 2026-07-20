package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

/** Assigns to a local variable's frame slot. */
public final class LuaLocalTarget extends LuaAssignmentTarget {
    private final int slot;

    public LuaLocalTarget(int slot) {
        this.slot = slot;
    }

    @Override
    public void assign(VirtualFrame frame, Object value) {
        frame.setObject(slot, value);
    }
}

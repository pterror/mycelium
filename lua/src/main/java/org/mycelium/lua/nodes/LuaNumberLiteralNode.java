package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

public final class LuaNumberLiteralNode extends LuaExpressionNode {
    private final double value;

    public LuaNumberLiteralNode(double value) {
        this.value = value;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return value;
    }
}

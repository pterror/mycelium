package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

public final class LuaBooleanLiteralNode extends LuaExpressionNode {
    private final boolean value;

    public LuaBooleanLiteralNode(boolean value) {
        this.value = value;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return value;
    }
}

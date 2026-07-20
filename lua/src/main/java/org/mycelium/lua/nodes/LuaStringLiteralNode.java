package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

public final class LuaStringLiteralNode extends LuaExpressionNode {
    private final String value;

    public LuaStringLiteralNode(String value) {
        this.value = value;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return value;
    }
}

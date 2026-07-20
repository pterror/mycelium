package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import org.mycelium.lua.runtime.LuaNil;

public final class LuaNilLiteralNode extends LuaExpressionNode {
    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return LuaNil.INSTANCE;
    }
}

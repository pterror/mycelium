package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;

public abstract class LuaStatementNode extends Node {
    public abstract void executeVoid(VirtualFrame frame);
}

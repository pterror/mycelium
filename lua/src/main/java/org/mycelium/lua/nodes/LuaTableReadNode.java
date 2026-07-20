package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node.Child;

/** {@code table[key]}. Falls back to the {@code __index} chain (Lua 5.1 &sect;2.8) when the key
 * is absent: if {@code __index} is a function it is called as {@code __index(table, key)}; if
 * it is a table, indexing recurses into it. */
public final class LuaTableReadNode extends LuaExpressionNode {
    @Child private LuaExpressionNode table;
    @Child private LuaExpressionNode key;
    @Child private IndirectCallNode callNode = IndirectCallNode.create();

    public LuaTableReadNode(LuaExpressionNode table, LuaExpressionNode key) {
        this.table = table;
        this.key = key;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object t = table.executeGeneric(frame);
        Object k = key.executeGeneric(frame);
        return LuaNodeUtil.tableIndex(this, t, k, callNode);
    }
}

package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node.Child;

/** {@code table[key] = value}. Falls back to the {@code __newindex} chain (Lua 5.1 &sect;2.8)
 * when the key is absent. Modeled as an expression (returns the assigned value), matching
 * {@link LuaWriteLocalNode}. */
public final class LuaTableWriteNode extends LuaExpressionNode {
    @Child private LuaExpressionNode table;
    @Child private LuaExpressionNode key;
    @Child private LuaExpressionNode value;
    @Child private IndirectCallNode callNode = IndirectCallNode.create();

    public LuaTableWriteNode(LuaExpressionNode table, LuaExpressionNode key, LuaExpressionNode value) {
        this.table = table;
        this.key = key;
        this.value = value;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object t = table.executeGeneric(frame);
        Object k = key.executeGeneric(frame);
        Object v = value.executeGeneric(frame);
        LuaNodeUtil.tableNewIndex(this, t, k, v, callNode);
        return v;
    }
}

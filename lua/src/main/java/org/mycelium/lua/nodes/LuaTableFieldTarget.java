package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node.Child;

/** Assigns to {@code table[key]}, honoring the {@code __newindex} chain. */
public final class LuaTableFieldTarget extends LuaAssignmentTarget {
    @Child private LuaExpressionNode table;
    @Child private LuaExpressionNode key;
    @Child private IndirectCallNode callNode = IndirectCallNode.create();

    public LuaTableFieldTarget(LuaExpressionNode table, LuaExpressionNode key) {
        this.table = table;
        this.key = key;
    }

    @Override
    public void assign(VirtualFrame frame, Object value) {
        Object t = table.executeGeneric(frame);
        Object k = key.executeGeneric(frame);
        LuaNodeUtil.tableNewIndex(this, t, k, value, callNode);
    }
}

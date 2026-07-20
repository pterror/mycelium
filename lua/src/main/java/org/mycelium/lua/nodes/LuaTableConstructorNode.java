package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Children;
import org.mycelium.lua.LuaException;
import org.mycelium.lua.runtime.LuaTable;

/**
 * {@code { fields }}. {@code keys[i] == null} marks a list-style entry (no explicit
 * {@code [k] =} or {@code name =} prefix); those get sequential integer keys starting at 1
 * (Lua 5.1 &sect;2.5.7). As with call arguments, only the <em>last</em> field &mdash; and only
 * if it is a list-style entry &mdash; expands to all of its values when it is a call or
 * {@code ...}; every other entry (list or keyed) is truncated to a single value.
 */
public final class LuaTableConstructorNode extends LuaExpressionNode {
    @Children private final LuaExpressionNode[] keys;
    @Children private final LuaExpressionNode[] values;

    public LuaTableConstructorNode(LuaExpressionNode[] keys, LuaExpressionNode[] values) {
        if (keys.length != values.length) {
            throw new IllegalArgumentException("keys and values arrays must have the same length");
        }
        this.keys = keys;
        this.values = values;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        LuaTable table = new LuaTable();
        int listIndex = 1;
        int lastIndex = values.length - 1;
        for (int i = 0; i < values.length; i++) {
            LuaExpressionNode keyNode = keys[i];
            if (keyNode == null) {
                if (i == lastIndex) {
                    Object[] expanded = values[i].executeMultiple(frame);
                    for (Object v : expanded) {
                        table.rawset((double) listIndex++, v);
                    }
                } else {
                    table.rawset((double) listIndex++, values[i].executeGeneric(frame));
                }
            } else {
                Object k = keyNode.executeGeneric(frame);
                Object v = values[i].executeGeneric(frame);
                if (LuaNodeUtil.isNil(k)) {
                    throw LuaException.runtimeError(this, "table index is nil");
                }
                if (k instanceof Double d && Double.isNaN(d)) {
                    throw LuaException.runtimeError(this, "table index is NaN");
                }
                table.rawset(k, v);
            }
        }
        return table;
    }
}

package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node.Child;
import org.mycelium.lua.LuaException;
import org.mycelium.lua.runtime.LuaFunction;
import org.mycelium.lua.runtime.LuaTable;

/** Writes a global variable, i.e. a field of the current function's environment table. Routed
 * through {@link LuaNodeUtil#tableNewIndex} so an {@code __newindex} metamethod on the
 * environment table (e.g. sandboxing) is honored. Modeled as an expression (returns the written
 * value), matching {@link LuaWriteLocalNode}. */
public final class LuaWriteGlobalNode extends LuaExpressionNode {
    private final String name;
    @Child private LuaExpressionNode value;
    @Child private IndirectCallNode callNode = IndirectCallNode.create();

    public LuaWriteGlobalNode(String name, LuaExpressionNode value) {
        this.name = name;
        this.value = value;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        LuaFunction fn = LuaRootNode.getCurrentFunction(frame);
        LuaTable env = fn != null ? fn.getEnvironment() : null;
        if (env == null) {
            throw LuaException.runtimeError(this, "no environment table available for global '" + name + "'");
        }
        Object v = value.executeGeneric(frame);
        LuaNodeUtil.tableNewIndex(this, env, name, v, callNode);
        return v;
    }
}

package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node.Child;
import org.mycelium.lua.LuaException;
import org.mycelium.lua.runtime.LuaFunction;
import org.mycelium.lua.runtime.LuaTable;

/** Reads a global variable, i.e. a field of the current function's environment table. Routed
 * through {@link LuaNodeUtil#tableIndex} (rather than a bare {@code rawget}) so that a sandboxed
 * environment table with an {@code __index} metamethod is honored, mirroring ordinary table
 * indexing (Lua 5.1's globals are, at heart, just fields of a table). */
public final class LuaReadGlobalNode extends LuaExpressionNode {
    private final String name;
    @Child private IndirectCallNode callNode = IndirectCallNode.create();

    public LuaReadGlobalNode(String name) {
        this.name = name;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        LuaFunction fn = LuaRootNode.getCurrentFunction(frame);
        LuaTable env = fn != null ? fn.getEnvironment() : null;
        if (env == null) {
            throw LuaException.runtimeError(this, "no environment table available for global '" + name + "'");
        }
        return LuaNodeUtil.tableIndex(this, env, name, callNode);
    }
}

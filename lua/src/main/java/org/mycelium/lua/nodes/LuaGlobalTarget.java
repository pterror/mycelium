package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node.Child;
import org.mycelium.lua.LuaException;
import org.mycelium.lua.runtime.LuaFunction;
import org.mycelium.lua.runtime.LuaTable;

/** Assigns to a global variable, i.e. a field of the current function's environment table. */
public final class LuaGlobalTarget extends LuaAssignmentTarget {
    private final String name;
    @Child private IndirectCallNode callNode = IndirectCallNode.create();

    public LuaGlobalTarget(String name) {
        this.name = name;
    }

    @Override
    public void assign(VirtualFrame frame, Object value) {
        LuaFunction fn = LuaRootNode.getCurrentFunction(frame);
        LuaTable env = fn != null ? fn.getEnvironment() : null;
        if (env == null) {
            throw LuaException.runtimeError(this, "no environment table available for global '" + name + "'");
        }
        LuaNodeUtil.tableNewIndex(this, env, name, value, callNode);
    }
}

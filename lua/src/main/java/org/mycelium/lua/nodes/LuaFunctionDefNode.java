package org.mycelium.lua.nodes;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.mycelium.lua.runtime.LuaFunction;

/**
 * {@code function(...) ... end}. Creates a {@link LuaFunction} closure over the compiled body
 * ({@code callTarget}), capturing the current frame (materialized) so nested
 * {@code LuaReadUpvalueNode}/{@code LuaWriteUpvalueNode} instances in the new function's body can
 * find their captured variables, and inheriting the enclosing function's environment table so
 * global lookups keep working inside the closure.
 */
public final class LuaFunctionDefNode extends LuaExpressionNode {
    private final CallTarget callTarget;
    private final int numUpvalues;

    public LuaFunctionDefNode(CallTarget callTarget, int numUpvalues) {
        this.callTarget = callTarget;
        this.numUpvalues = numUpvalues;
    }

    public int getNumUpvalues() {
        return numUpvalues;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        LuaFunction fn = new LuaFunction(callTarget, frame.materialize());
        LuaFunction enclosing = LuaRootNode.getCurrentFunction(frame);
        if (enclosing != null) {
            fn.setEnvironment(enclosing.getEnvironment());
        }
        return fn;
    }
}

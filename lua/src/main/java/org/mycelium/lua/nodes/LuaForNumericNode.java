package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import org.mycelium.lua.LuaException;
import org.mycelium.lua.nodes.controlflow.LuaBreakException;
import org.mycelium.lua.runtime.LuaTypeUtils;

/**
 * {@code for i = start, stop, step do ... end} (Lua 5.1 &sect;2.4.5). The loop runs while
 * {@code (step > 0 and i <= stop) or (step < 0 and i >= stop)}. The parser is expected to always
 * supply a {@code step} expression, synthesizing a literal {@code 1} when the source omits it.
 */
public final class LuaForNumericNode extends LuaStatementNode {
    @Child private LuaExpressionNode start;
    @Child private LuaExpressionNode stop;
    @Child private LuaExpressionNode step;
    @Child private LuaStatementNode body;
    private final int loopVarSlot;

    public LuaForNumericNode(
            LuaExpressionNode start, LuaExpressionNode stop, LuaExpressionNode step, int loopVarSlot, LuaStatementNode body) {
        this.start = start;
        this.stop = stop;
        this.step = step;
        this.loopVarSlot = loopVarSlot;
        this.body = body;
    }

    @Override
    public void executeVoid(VirtualFrame frame) {
        Double startNum = LuaTypeUtils.toNumber(start.executeGeneric(frame));
        Double stopNum = LuaTypeUtils.toNumber(stop.executeGeneric(frame));
        Double stepNum = LuaTypeUtils.toNumber(step.executeGeneric(frame));
        if (startNum == null) {
            throw LuaException.runtimeError(this, "'for' initial value must be a number");
        }
        if (stopNum == null) {
            throw LuaException.runtimeError(this, "'for' limit must be a number");
        }
        if (stepNum == null) {
            throw LuaException.runtimeError(this, "'for' step must be a number");
        }
        double limit = stopNum;
        double increment = stepNum;
        if (increment == 0) {
            throw LuaException.runtimeError(this, "'for' step is zero");
        }
        try {
            if (increment > 0) {
                for (double i = startNum; i <= limit; i += increment) {
                    frame.setObject(loopVarSlot, i);
                    body.executeVoid(frame);
                }
            } else {
                for (double i = startNum; i >= limit; i += increment) {
                    frame.setObject(loopVarSlot, i);
                    body.executeVoid(frame);
                }
            }
        } catch (LuaBreakException e) {
            // `break` terminates the loop.
        }
    }
}

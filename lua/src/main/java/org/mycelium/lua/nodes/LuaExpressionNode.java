package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Base class for all Lua expression nodes. An expression always produces at least one value
 * ({@link #executeGeneric}); a handful of expressions (function calls, method calls, and
 * {@code ...}) may additionally produce more than one value when they appear in a
 * multi-value context such as the last entry of a call's argument list, a {@code return}
 * statement, or a table constructor's last list field &mdash; see {@link #executeMultiple}.
 */
public abstract class LuaExpressionNode extends Node {
    public abstract Object executeGeneric(VirtualFrame frame);

    // Convenience typed execute methods
    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        Object val = executeGeneric(frame);
        if (val instanceof Double d) return d;
        throw new UnexpectedResultException(val);
    }

    public boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException {
        Object val = executeGeneric(frame);
        if (val instanceof Boolean b) return b;
        throw new UnexpectedResultException(val);
    }

    /**
     * Executes this expression in a context where it may legally yield more than one value
     * (Lua 5.1 &sect;2.5.7's "adjustment" rules: only the last expression of a list may expand).
     * The default implementation simply wraps the single {@link #executeGeneric} result; nodes
     * that can genuinely produce multiple values ({@code LuaFunctionCallNode},
     * {@code LuaMethodCallNode}, {@code LuaVarargNode}) override this.
     */
    public Object[] executeMultiple(VirtualFrame frame) {
        return new Object[] {executeGeneric(frame)};
    }
}

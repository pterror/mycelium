package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node.Child;
import com.oracle.truffle.api.nodes.Node.Children;

/**
 * A function call {@code f(args)}. Follows the {@code __call} metamethod chain (via
 * {@link LuaNodeUtil#invoke}) when the callee isn't itself a {@code LuaFunction}. In a
 * single-value context ({@link #executeGeneric}) only the first return value is produced; in a
 * multi-value context ({@link #executeMultiple}) all of them are.
 */
public final class LuaFunctionCallNode extends LuaExpressionNode {
    @Child private LuaExpressionNode function;
    @Children private final LuaExpressionNode[] arguments;
    @Child private IndirectCallNode callNode = IndirectCallNode.create();

    public LuaFunctionCallNode(LuaExpressionNode function, LuaExpressionNode[] arguments) {
        this.function = function;
        this.arguments = arguments;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return LuaNodeUtil.first(doCall(frame));
    }

    @Override
    public Object[] executeMultiple(VirtualFrame frame) {
        return doCall(frame);
    }

    private Object[] doCall(VirtualFrame frame) {
        Object callee = function.executeGeneric(frame);
        Object[] args = LuaNodeUtil.evaluateExpressionList(frame, arguments);
        return LuaNodeUtil.invoke(this, callNode, callee, args);
    }
}

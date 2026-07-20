package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node.Child;
import com.oracle.truffle.api.nodes.Node.Children;

/**
 * {@code obj:method(args)}, sugar for evaluating {@code obj} once and calling
 * {@code obj.method(obj, args)} (Lua 5.1 &sect;2.5.8). The object expression is evaluated exactly
 * once and used both as the {@code __index} receiver (to look up {@code method}) and as the
 * implicit first ({@code self}) argument.
 */
public final class LuaMethodCallNode extends LuaExpressionNode {
    @Child private LuaExpressionNode object;
    private final String methodName;
    @Children private final LuaExpressionNode[] arguments;
    @Child private IndirectCallNode callNode = IndirectCallNode.create();

    public LuaMethodCallNode(LuaExpressionNode object, String methodName, LuaExpressionNode[] arguments) {
        this.object = object;
        this.methodName = methodName;
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
        Object self = object.executeGeneric(frame);
        Object method = LuaNodeUtil.tableIndex(this, self, methodName, callNode);
        Object[] callArgs = LuaNodeUtil.evaluateExpressionList(frame, arguments);
        Object[] args = new Object[callArgs.length + 1];
        args[0] = self;
        System.arraycopy(callArgs, 0, args, 1, callArgs.length);
        return LuaNodeUtil.invoke(this, callNode, method, args);
    }
}

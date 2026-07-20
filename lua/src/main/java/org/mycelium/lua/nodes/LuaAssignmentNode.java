package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Children;
import org.mycelium.lua.runtime.LuaNil;

/**
 * {@code var1, ..., varn = expr1, ..., exprm} (Lua 5.1 &sect;2.4.3). All value expressions are
 * evaluated (with the usual last-expression expansion and padding/truncation to the number of
 * targets) <em>before</em> any assignment is performed, per the manual: "Lua first evaluates all
 * values and only then executes the assignments."
 */
public final class LuaAssignmentNode extends LuaStatementNode {
    @Children private final LuaAssignmentTarget[] targets;
    @Children private final LuaExpressionNode[] values;

    public LuaAssignmentNode(LuaAssignmentTarget[] targets, LuaExpressionNode[] values) {
        this.targets = targets;
        this.values = values;
    }

    @Override
    public void executeVoid(VirtualFrame frame) {
        Object[] vals = LuaNodeUtil.evaluateExpressionList(frame, values);
        for (int i = 0; i < targets.length; i++) {
            Object v = i < vals.length ? vals[i] : LuaNil.INSTANCE;
            targets[i].assign(frame, v);
        }
    }
}

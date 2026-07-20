package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node.Child;
import com.oracle.truffle.api.nodes.Node.Children;
import org.mycelium.lua.nodes.controlflow.LuaBreakException;
import org.mycelium.lua.runtime.LuaNil;

/**
 * {@code for var_1, ..., var_n in explist do ... end} (Lua 5.1 &sect;2.4.5), desugared per the
 * manual as:
 * <pre>
 * local f, s, var = explist
 * while true do
 *   local var_1, ..., var_n = f(s, var)
 *   var = var_1
 *   if var == nil then break end
 *   body
 * end
 * </pre>
 * {@code expressions} supplies the (up to three) iterator/state/control expressions; missing
 * ones are treated as {@code nil}, matching normal list-expression adjustment.
 */
public final class LuaForGenericNode extends LuaStatementNode {
    @Children private final LuaExpressionNode[] expressions;
    private final int[] varSlots;
    @Child private LuaStatementNode body;
    @Child private IndirectCallNode callNode = IndirectCallNode.create();

    public LuaForGenericNode(LuaExpressionNode[] expressions, int[] varSlots, LuaStatementNode body) {
        this.expressions = expressions;
        this.varSlots = varSlots;
        this.body = body;
    }

    @Override
    public void executeVoid(VirtualFrame frame) {
        Object[] initial = LuaNodeUtil.evaluateExpressionList(frame, expressions);
        Object iterator = initial.length > 0 ? initial[0] : LuaNil.INSTANCE;
        Object state = initial.length > 1 ? initial[1] : LuaNil.INSTANCE;
        Object control = initial.length > 2 ? initial[2] : LuaNil.INSTANCE;
        try {
            while (true) {
                Object[] results = LuaNodeUtil.invoke(this, callNode, iterator, new Object[] {state, control});
                Object firstResult = results.length > 0 ? results[0] : LuaNil.INSTANCE;
                if (LuaNodeUtil.isNil(firstResult)) {
                    break;
                }
                control = firstResult;
                for (int i = 0; i < varSlots.length; i++) {
                    Object v = i < results.length ? results[i] : LuaNil.INSTANCE;
                    frame.setObject(varSlots[i], v);
                }
                body.executeVoid(frame);
            }
        } catch (LuaBreakException e) {
            // `break` terminates the loop.
        }
    }
}

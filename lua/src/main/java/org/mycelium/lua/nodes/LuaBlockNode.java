package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Children;
import com.oracle.truffle.api.source.SourceSection;
import org.mycelium.lua.LuaContext;
import org.mycelium.lua.runtime.LuaFunction;

/**
 * A sequence of statements, executed in order.
 *
 * <p>Every {@code function}/{@code if}/{@code while}/{@code for}/{@code repeat} body in the
 * parser is built as a {@link LuaBlockNode} (see {@code LuaParser#parseBlock}), so this is the
 * single universal statement-execution path in the tree &mdash; hence it's also the one place
 * {@code debug.sethook}'s {@code "l"} (line) and {@code count} masks are checked (Lua 5.1
 * &sect;5.9): every statement the interpreter runs, in every kind of block, passes through here
 * exactly once per execution.
 */
public final class LuaBlockNode extends LuaStatementNode {
    @Children private final LuaStatementNode[] statements;

    public LuaBlockNode(LuaStatementNode[] statements) {
        this.statements = statements;
    }

    @Override
    public void executeVoid(VirtualFrame frame) {
        LuaContext context = LuaContext.get(this);
        for (LuaStatementNode statement : statements) {
            // Re-checked per statement (not hoisted above the loop): a hook installed by a
            // debug.sethook() call earlier in this very block must take effect for the
            // statements that follow it in the same block.
            if (context.isHookActive() && !context.isInHook()) {
                fireLineAndCountHooks(context, statement);
            }
            statement.executeVoid(frame);
        }
    }

    /** Fires the {@code count} hook (every N statements) and the {@code "l"} line hook (on
     * reaching a source line different from the last one reported) ahead of executing
     * {@code statement}. No-op if no hook function is installed. */
    private static void fireLineAndCountHooks(LuaContext context, LuaStatementNode statement) {
        LuaFunction hook = context.getHookFunction();
        if (hook == null) {
            return;
        }

        int count = context.getHookCount();
        if (count > 0 && context.incrementHookCounter() >= count) {
            context.resetHookCounter();
            context.setInHook(true);
            try {
                hook.getCallTarget().call(hook, "count");
            } finally {
                context.setInHook(false);
            }
        }

        if (context.getHookMask().indexOf('l') >= 0) {
            SourceSection section = statement.getSourceSection();
            if (section != null) {
                int line = section.getStartLine();
                if (line != context.getLastHookLine()) {
                    context.setLastHookLine(line);
                    context.setInHook(true);
                    try {
                        hook.getCallTarget().call(hook, "line", (double) line);
                    } finally {
                        context.setInHook(false);
                    }
                }
            }
        }
    }
}

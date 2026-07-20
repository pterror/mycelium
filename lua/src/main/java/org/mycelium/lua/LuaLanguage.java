package org.mycelium.lua;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import org.mycelium.lua.nodes.LuaRootNode;
import org.mycelium.lua.parser.LuaParser;
import org.mycelium.lua.runtime.LuaFunction;
import org.mycelium.lua.runtime.LuaNil;

/**
 * Truffle language registration for Lua 5.1. Wires the {@link LuaParser}/{@link LuaRootNode}
 * pair (which compile a source into an ordinary Lua closure's call target) into Truffle's
 * {@link #parse} contract, and owns the per-context runtime state via {@link LuaContext}.
 */
@TruffleLanguage.Registration(
        id = LuaLanguage.ID,
        name = "Lua",
        defaultMimeType = LuaLanguage.MIME_TYPE,
        characterMimeTypes = LuaLanguage.MIME_TYPE,
        contextPolicy = TruffleLanguage.ContextPolicy.SHARED)
public final class LuaLanguage extends TruffleLanguage<LuaContext> {

    public static final String ID = "lua";
    public static final String MIME_TYPE = "application/x-lua";

    private static final ContextReference<LuaContext> CONTEXT_REF = ContextReference.create(LuaLanguage.class);

    /** Retrieves the {@link LuaContext} associated with {@code node}'s current execution. */
    public static LuaContext getContext(Node node) {
        return CONTEXT_REF.get(node);
    }

    @Override
    protected LuaContext createContext(Env env) {
        return new LuaContext(this, env);
    }

    /**
     * Lua 5.1 coroutines (&sect;2.11/&sect;5.2) are implemented as "thread-per-coroutine": each
     * coroutine that is actually started runs on its own backing Java thread (see {@link
     * org.mycelium.lua.runtime.LuaCoroutine}), created via {@link Env#newTruffleThreadBuilder}.
     * That means
     * more than one thread may be entered in this context at once &mdash; the resumer blocks
     * (still entered) while the coroutine's thread runs. Truffle's default policy rejects that
     * ({@code isThreadAccessAllowed} defaults to {@code singleThreaded}), so it's overridden here:
     * this is still safe because {@code LuaCoroutine}'s resume/yield rendezvous guarantees that
     * only one of those entered threads is ever actually executing Lua code at a time.
     */
    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        return true;
    }

    /**
     * Gives any coroutine backing threads that are still parked (suspended, never resumed to
     * completion) a chance to exit before the context finishes closing &mdash; see {@link
     * org.mycelium.lua.runtime.LuaCoroutine}'s class doc for why this is necessary.
     */
    @Override
    protected void finalizeContext(LuaContext context) {
        context.shutdownCoroutines();
    }

    @Override
    protected CallTarget parse(ParsingRequest request) throws Exception {
        Source source = request.getSource();
        LuaParser parser = new LuaParser(this, source);
        LuaRootNode chunkRootNode = parser.parse();
        CallTarget chunkCallTarget = chunkRootNode.getCallTarget();
        return new TopLevelRootNode(this, chunkCallTarget).getCallTarget();
    }

    /**
     * Every real Lua chunk body ({@link LuaRootNode}) expects {@code frame.getArguments()[0]} to
     * be the {@link LuaFunction} closure it is running as (see {@link LuaRootNode} class doc) —
     * that's how global lookups find the environment table and how upvalue reads find the
     * enclosing frame chain. The top-level chunk has no such closure to begin with, so this
     * wrapper root node (the one Truffle actually calls for a top-level {@code parse()} result)
     * manufactures one: a {@link LuaFunction} with no enclosing frame, whose environment is the
     * current {@link LuaContext}'s globals table, and re-invokes the real chunk call target with
     * that closure prepended to the argument list.
     */
    private static final class TopLevelRootNode extends RootNode {
        @Child private DirectCallNode callNode;

        TopLevelRootNode(LuaLanguage language, CallTarget chunkCallTarget) {
            super(language);
            this.callNode = DirectCallNode.create(chunkCallTarget);
        }

        /** This wrapper has no Lua source position of its own (see class doc) &mdash; the real
         * chunk {@link LuaRootNode} it immediately delegates to does. Marking it internal keeps it
         * out of {@code debug.traceback}/{@code debug.getinfo}'s frame walks, which would otherwise
         * report a spurious extra {@code "?: in function <?>"} frame beneath the actual chunk. */
        @Override
        public boolean isInternal() {
            return true;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            LuaContext context = LuaLanguage.getContext(this);
            LuaFunction chunkFunction = new LuaFunction(callNode.getCallTarget(), null);
            chunkFunction.setEnvironment(context.getGlobals());

            Object[] callerArgs = frame.getArguments();
            Object[] args = new Object[callerArgs.length + 1];
            args[0] = chunkFunction;
            System.arraycopy(callerArgs, 0, args, 1, callerArgs.length);
            Object result = callNode.call(args);

            // A Lua chunk body (like any Lua function) always yields an Object[] of its `return`
            // values (see LuaRootNode class doc) — but this wrapper's own execute() result crosses
            // the polyglot boundary (Context.eval()'s return value), which requires a single legal
            // interop value, not a raw Java array. Truncate to the first return value, matching
            // Lua's own "adjust to one value" rule for single-value contexts (Lua 5.1 &sect;2.5.7).
            if (result instanceof Object[] values) {
                return values.length > 0 ? values[0] : LuaNil.INSTANCE;
            }
            return result;
        }
    }
}

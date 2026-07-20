package org.mycelium.lua.nodes;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import org.mycelium.lua.nodes.controlflow.LuaReturnException;
import org.mycelium.lua.runtime.LuaFunction;

/**
 * Root node for every compiled Lua function body (including the top-level chunk).
 *
 * <p><b>Calling convention:</b> a Lua call target is invoked as {@code callTarget.call(args)}
 * where {@code args[0]} is the {@link LuaFunction} closure being invoked (never the raw callee
 * expression result &mdash; see {@code LuaNodeUtil#invoke}) and {@code args[1..]} are the actual
 * Lua call arguments, one Java array slot per Lua argument. Storing the closure itself at
 * {@code args[0]} lets any node in the body recover "the function currently executing"
 * ({@link #getCurrentFunction}) without a dedicated frame slot, which in turn is how
 * {@code LuaReadUpvalueNode}/{@code LuaReadGlobalNode} find the enclosing frame chain and the
 * environment (global) table.
 *
 * <p><b>Varargs:</b> the vararg values ({@code ...}) are stored as an {@code Object[]} in the
 * well-known frame slot {@link #VARARGS_FRAME_SLOT}. The parser must reserve this slot (index 0)
 * in every frame descriptor and is responsible for populating it from the tail of the positional
 * arguments when the function is declared with {@code ...}.
 *
 * <p><b>Return values:</b> {@link #execute} always returns an {@code Object[]} (possibly empty),
 * extracted from a thrown {@link LuaReturnException}, or empty if the body falls off the end
 * without an explicit {@code return}.
 *
 * <p><b>Debug info:</b> {@link #name} (surfaced via {@link #getName()}) and {@link #sourceSection}
 * (via {@link #getSourceSection()}) are set by the parser so that {@code debug.traceback}/
 * {@code debug.getinfo} and Truffle's own stack-trace machinery can report a meaningful
 * {@code file:line: in function 'name'}.
 */
public final class LuaRootNode extends RootNode {

    /** Well-known frame slot index (reserved by the parser) holding the current vararg values. */
    public static final int VARARGS_FRAME_SLOT = 0;

    private static final Object[] NO_VALUES = new Object[0];

    @Child private LuaStatementNode body;

    private final String name;
    private SourceSection sourceSection;

    public LuaRootNode(TruffleLanguage<?> language, FrameDescriptor frameDescriptor, LuaStatementNode body) {
        this(language, frameDescriptor, body, null);
    }

    public LuaRootNode(
            TruffleLanguage<?> language, FrameDescriptor frameDescriptor, LuaStatementNode body, String name) {
        super(language, frameDescriptor);
        this.body = body;
        this.name = name;
    }

    /** Attaches the source range spanning this function's definition (from {@code function}/its
     * name through the matching {@code end}, or the whole chunk for a top-level compile). */
    public void setSourceSection(SourceSection sourceSection) {
        this.sourceSection = sourceSection;
    }

    @Override
    public SourceSection getSourceSection() {
        return sourceSection;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        try {
            body.executeVoid(frame);
        } catch (LuaReturnException e) {
            return e.getValues();
        }
        return NO_VALUES;
    }

    /** Recovers the {@link LuaFunction} closure currently executing in {@code frame} (see class doc). */
    public static LuaFunction getCurrentFunction(Frame frame) {
        Object[] args = frame.getArguments();
        return args.length > 0 && args[0] instanceof LuaFunction fn ? fn : null;
    }

    /** The actual Lua call arguments (excluding the leading closure self-reference). */
    public static Object[] getPositionalArguments(Frame frame) {
        Object[] args = frame.getArguments();
        if (args.length <= 1) {
            return NO_VALUES;
        }
        Object[] result = new Object[args.length - 1];
        System.arraycopy(args, 1, result, 0, result.length);
        return result;
    }
}

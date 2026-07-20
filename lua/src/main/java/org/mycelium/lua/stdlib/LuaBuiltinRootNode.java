package org.mycelium.lua.stdlib;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import org.mycelium.lua.runtime.LuaFunction;
import org.mycelium.lua.runtime.LuaNil;

/**
 * Base class for a standard-library builtin's Truffle {@link RootNode}.
 *
 * <p>Follows the same calling convention as ordinary Lua closures ({@link
 * org.mycelium.lua.nodes.LuaRootNode}): {@code frame.getArguments()[0]} is the {@link
 * LuaFunction} wrapper for this builtin, and {@code [1..]} are the actual call arguments. Unlike
 * user Lua functions, a builtin's {@link #execute} returns either a single {@code Object} (one
 * Lua return value) or an {@code Object[]} (multiple return values / no values); callers that
 * invoke a builtin's call target must unwrap accordingly, exactly as {@link
 * org.mycelium.lua.nodes.LuaNodeUtil#invoke} already does for ordinary closures returning
 * {@code Object[]}.
 */
public abstract class LuaBuiltinRootNode extends RootNode {

    private final String name;

    protected LuaBuiltinRootNode(TruffleLanguage<?> language, String name) {
        super(language);
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    /** Argument at 1-based Lua index {@code index} (i.e. Java {@code frame.getArguments()[index]}), or nil if not supplied. */
    protected static Object arg(VirtualFrame frame, int index) {
        Object[] args = frame.getArguments();
        if (index < args.length) {
            Object v = args[index];
            return v == null ? LuaNil.INSTANCE : v;
        }
        return LuaNil.INSTANCE;
    }

    /** Number of actual Lua call arguments, excluding the leading closure self-reference at index 0. */
    protected static int argCount(VirtualFrame frame) {
        return frame.getArguments().length - 1;
    }

    /** All actual Lua call arguments (excluding the leading closure self-reference), nil-safe. */
    protected static Object[] allArgs(VirtualFrame frame) {
        Object[] raw = frame.getArguments();
        if (raw.length <= 1) {
            return new Object[0];
        }
        Object[] result = new Object[raw.length - 1];
        for (int i = 1; i < raw.length; i++) {
            Object v = raw[i];
            result[i - 1] = v == null ? LuaNil.INSTANCE : v;
        }
        return result;
    }

    /**
     * Functional body used by the generic {@link LuaBuiltin} root node. {@code callNode} is a
     * Truffle-adopted {@link IndirectCallNode} owned by the enclosing {@link LuaBuiltin}, for
     * bodies that need to call back into Lua (following the {@code __call} metamethod chain via
     * {@link LuaCallSupport}) &mdash; e.g. {@code pcall}, {@code pairs}' iterator,
     * {@code table.sort}'s comparator.
     */
    @FunctionalInterface
    public interface Body {
        Object execute(VirtualFrame frame, IndirectCallNode callNode);
    }

    /** Builds and wraps a {@link CallTarget} for {@code body} into a {@link LuaFunction}, ready to {@code rawset} into a table. */
    public static LuaFunction wrap(TruffleLanguage<?> language, String name, Body body) {
        LuaBuiltin node = new LuaBuiltin(language, name, body);
        CallTarget target = node.getCallTarget();
        return new LuaFunction(target, null);
    }
}

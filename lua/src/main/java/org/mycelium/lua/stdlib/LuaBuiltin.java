package org.mycelium.lua.stdlib;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node.Child;
import org.mycelium.lua.nodes.controlflow.LuaReturnException;

/**
 * A generic {@link LuaBuiltinRootNode} parameterized by a {@link Body} lambda, so each standard
 * library function doesn't need its own hand-written {@code RootNode} subclass &mdash; the
 * lambda passed to {@link LuaBuiltinRootNode#wrap} plays that role.
 *
 * <p>Mirrors {@link org.mycelium.lua.nodes.LuaRootNode}'s own convention: a body that wants to
 * produce more than one Lua return value throws {@link LuaReturnException} with the full value
 * array, and this root node is the one that catches it and turns it into the {@code Object[]}
 * result &mdash; the same shape {@link org.mycelium.lua.nodes.LuaNodeUtil#invoke} already expects
 * from any call target, builtin or not. A body that throws nothing is treated as a single-value
 * return of whatever it returned.
 */
final class LuaBuiltin extends LuaBuiltinRootNode {

    private final Body body;
    @Child private IndirectCallNode callNode = IndirectCallNode.create();

    LuaBuiltin(TruffleLanguage<?> language, String name, Body body) {
        super(language, name);
        this.body = body;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        try {
            return body.execute(frame, callNode);
        } catch (LuaReturnException e) {
            return e.getValues();
        }
    }
}

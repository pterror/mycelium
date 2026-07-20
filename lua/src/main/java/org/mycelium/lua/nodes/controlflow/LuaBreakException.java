package org.mycelium.lua.nodes.controlflow;

import com.oracle.truffle.api.nodes.ControlFlowException;

/**
 * Lua 5.1 &sect;2.4.4: {@code break} terminates the innermost enclosing loop. A single shared
 * instance is used since it carries no payload.
 */
public final class LuaBreakException extends ControlFlowException {
    private static final long serialVersionUID = 1L;

    public static final LuaBreakException INSTANCE = new LuaBreakException();

    private LuaBreakException() {}
}

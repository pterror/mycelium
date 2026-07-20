package org.mycelium.lua.nodes.controlflow;

import com.oracle.truffle.api.nodes.ControlFlowException;

/**
 * Lua 5.1 &sect;2.4.4: the {@code return} statement performs a non-local exit from the current
 * function body. Modeled as a {@link ControlFlowException} so Truffle can compile it without
 * the overhead of a real exception (no stack trace capture).
 */
public final class LuaReturnException extends ControlFlowException {
    private static final long serialVersionUID = 1L;

    private final Object[] values;

    public LuaReturnException(Object[] values) {
        this.values = values;
    }

    public Object[] getValues() {
        return values;
    }
}

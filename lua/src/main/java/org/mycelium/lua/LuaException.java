package org.mycelium.lua;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;
import org.mycelium.lua.runtime.LuaTypeUtils;

/**
 * Runtime error type for Lua.
 *
 * <p>Lua 5.1 &sect;2.4.7: error handling. Unlike many languages, Lua errors are not restricted
 * to strings &mdash; {@code error()} may be called with any value, and that value is what
 * propagates to a {@code pcall}/{@code xpcall} handler unchanged. This class therefore carries
 * the raw Lua error object alongside the Truffle-required message.
 */
public final class LuaException extends AbstractTruffleException {
    private static final long serialVersionUID = 1L;

    private final Object errorObject;

    public LuaException(Object errorObject, Node location) {
        super(LuaTypeUtils.tostring(errorObject), location);
        this.errorObject = errorObject;
    }

    public Object getErrorObject() {
        return errorObject;
    }

    /** Convenience factory for plain string errors. */
    public static LuaException runtimeError(Node location, String message) {
        return new LuaException(message, location);
    }

    /** Convenience factory for "attempt to <operation> a <type> value" style errors. */
    public static LuaException typeError(Node location, String operation, Object value) {
        String message =
                "attempt to " + operation + " a " + LuaTypeUtils.typeName(value) + " value";
        return new LuaException(message, location);
    }

    /** Convenience factory for arithmetic-on-non-number errors. */
    public static LuaException arithError(Node location, Object value) {
        String message =
                "attempt to perform arithmetic on a " + LuaTypeUtils.typeName(value) + " value";
        return new LuaException(message, location);
    }
}

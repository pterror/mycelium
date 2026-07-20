package org.mycelium.lua.runtime;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * Represents a Lua function (closure).
 *
 * <p>A {@link LuaFunction} pairs a Truffle {@link CallTarget} with the (optional) enclosing
 * frame it closes over, so that inner functions can access the local variables of the frames
 * in which they were created (upvalues). It also carries a reference to the environment table
 * used for global variable lookup, mirroring Lua 5.1's {@code _ENV}-like global table concept.
 */
@ExportLibrary(InteropLibrary.class)
public final class LuaFunction implements TruffleObject {
    private final CallTarget callTarget;
    private final MaterializedFrame enclosingFrame;
    private LuaTable environment;

    public LuaFunction(CallTarget callTarget, MaterializedFrame enclosingFrame) {
        this.callTarget = callTarget;
        this.enclosingFrame = enclosingFrame;
    }

    public CallTarget getCallTarget() {
        return callTarget;
    }

    public MaterializedFrame getEnclosingFrame() {
        return enclosingFrame;
    }

    public LuaTable getEnvironment() {
        return environment;
    }

    public void setEnvironment(LuaTable env) {
        this.environment = env;
    }

    @ExportMessage
    boolean isExecutable() {
        return true;
    }

    /**
     * Calls this function from another language. Lua's calling convention (see
     * {@code LuaRootNode}) expects {@code args[0]} to be the {@link LuaFunction} closure itself,
     * so the incoming polyglot arguments are shifted down by one slot to make room for it.
     *
     * <p>A Lua call target's {@code execute()} always returns an {@code Object[]} of the
     * function's return values (possibly empty) &mdash; not a legal interop value on its own
     * &mdash; so this unwraps it to the first return value (or {@link LuaNil#INSTANCE} if the
     * function returned nothing), matching Lua's "adjust to one value" rule (Lua 5.1 &sect;2.5.7).
     */
    @ExportMessage
    Object execute(Object[] arguments) throws UnsupportedTypeException, ArityException {
        Object[] luaArgs = new Object[arguments.length + 1];
        luaArgs[0] = this;
        System.arraycopy(arguments, 0, luaArgs, 1, arguments.length);
        Object result = callTarget.call(luaArgs);
        if (result instanceof Object[] values) {
            return values.length > 0 ? values[0] : LuaNil.INSTANCE;
        }
        return result == null ? LuaNil.INSTANCE : result;
    }

    @Override
    public String toString() {
        return "function: 0x" + Integer.toHexString(System.identityHashCode(this));
    }
}

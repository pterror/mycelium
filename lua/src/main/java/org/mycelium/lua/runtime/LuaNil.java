package org.mycelium.lua.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * Singleton representing Lua's {@code nil} value.
 *
 * <p>Lua 5.1 &sect;2.2: nil &mdash; the type of the value nil, whose main property is to be
 * different from any other value; it usually represents the absence of a useful value.
 *
 * <p>Exported as an {@link InteropLibrary} null value so that a Lua chunk returning {@code nil}
 * (or nothing at all) is a legal polyglot return value, rather than tripping Truffle's "invalid
 * return value" check.
 */
@ExportLibrary(InteropLibrary.class)
public final class LuaNil implements TruffleObject {
    public static final LuaNil INSTANCE = new LuaNil();

    private LuaNil() {}

    @ExportMessage
    boolean isNull() {
        return true;
    }

    @Override
    public String toString() {
        return "nil";
    }
}

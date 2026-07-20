package org.mycelium.lua.stdlib;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import org.mycelium.lua.LuaContext;
import org.mycelium.lua.LuaException;
import org.mycelium.lua.LuaLanguage;
import org.mycelium.lua.nodes.controlflow.LuaReturnException;
import org.mycelium.lua.runtime.LuaCoroutine;
import org.mycelium.lua.runtime.LuaFunction;
import org.mycelium.lua.runtime.LuaNil;
import org.mycelium.lua.runtime.LuaTable;

import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.allArgs;
import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.arg;
import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.wrap;
import static org.mycelium.lua.stdlib.LuaLibUtil.argError;

/**
 * Lua 5.1 &sect;5.2: the {@code coroutine} table &mdash; {@code create}, {@code resume}, {@code
 * yield}, {@code status}, {@code wrap}, {@code running}.
 *
 * <p>Backed by {@link LuaCoroutine}'s thread-per-coroutine implementation: {@code resume}/{@code
 * yield} only shuttle values through {@link LuaCoroutine}'s synchronous queues, while the actual
 * "call this Lua value" logic is supplied here via a {@link LuaCoroutine.Invoker} that defers to
 * {@link LuaCallSupport} (which stays package-private, hence the indirection).
 */
final class LuaCoroutineLib {

    private LuaCoroutineLib() {}

    static void install(TruffleLanguage<?> language, LuaTable globals) {
        LuaTable coroutine = new LuaTable();
        globals.rawset("coroutine", coroutine);

        coroutine.rawset("create", wrap(language, "create", (frame, callNode) -> {
            Object f = arg(frame, 1);
            if (!(f instanceof LuaFunction fn)) {
                throw argError("create", 1, "function", f);
            }
            return new LuaCoroutine(fn);
        }));

        coroutine.rawset("resume", wrap(language, "resume", (frame, callNode) -> {
            LuaCoroutine co = checkCoroutine("resume", 1, arg(frame, 1));
            Object[] args = allArgs(frame);
            Object[] callArgs = args.length > 0 ? java.util.Arrays.copyOfRange(args, 1, args.length) : args;
            Object[] result = co.resume(context(callNode), invoker(callNode), callArgs);
            throw new LuaReturnException(result);
        }));

        coroutine.rawset("yield", wrap(language, "yield", (frame, callNode) -> {
            LuaCoroutine self = LuaCoroutine.current();
            if (self == null) {
                throw LuaException.runtimeError(null, "attempt to yield from outside a coroutine");
            }
            Object[] values = allArgs(frame);
            Object[] result = self.yield(values);
            throw new LuaReturnException(result);
        }));

        coroutine.rawset("status", wrap(language, "status", (frame, callNode) -> {
            LuaCoroutine co = checkCoroutine("status", 1, arg(frame, 1));
            return switch (co.getStatus()) {
                case SUSPENDED -> "suspended";
                case RUNNING -> co == LuaCoroutine.current() ? "running" : "normal";
                case NORMAL -> "normal";
                case DEAD -> "dead";
            };
        }));

        coroutine.rawset("running", wrap(language, "running", (frame, callNode) -> {
            LuaCoroutine self = LuaCoroutine.current();
            return self == null ? LuaNil.INSTANCE : self;
        }));

        coroutine.rawset("wrap", wrap(language, "wrap", (frame, callNode) -> {
            Object f = arg(frame, 1);
            if (!(f instanceof LuaFunction fn)) {
                throw argError("wrap", 1, "function", f);
            }
            LuaCoroutine co = new LuaCoroutine(fn);
            LuaContext context = context(callNode);
            LuaCoroutine.Invoker invoker = invoker(callNode);
            return wrap(language, "wrapped-coroutine", (innerFrame, innerCallNode) -> {
                Object[] callArgs = allArgs(innerFrame);
                Object[] result = co.resume(context, invoker, callArgs);
                if (result.length > 0 && Boolean.FALSE.equals(result[0])) {
                    Object error = result.length > 1 ? result[1] : LuaNil.INSTANCE;
                    throw new LuaException(error, null);
                }
                Object[] values = java.util.Arrays.copyOfRange(result, 1, result.length);
                throw new LuaReturnException(values);
            });
        }));
    }

    private static LuaContext context(IndirectCallNode callNode) {
        return LuaLanguage.getContext(callNode);
    }

    /**
     * A coroutine body may call back into arbitrary Lua/host values ({@code pcall}, other
     * coroutines, ...), so the {@link LuaCoroutine.Invoker} needs its own {@link IndirectCallNode}
     * rather than borrowing the enclosing builtin's {@code @Child} one &mdash; that node's calls
     * happen on the coroutine's own backing thread, not the resumer's, so it must not be a node
     * adopted into another thread's executing tree. The uncached singleton has no such affinity.
     */
    private static LuaCoroutine.Invoker invoker(IndirectCallNode callNode) {
        IndirectCallNode uncached = IndirectCallNode.getUncached();
        return (callee, args) -> LuaCallSupport.call(null, uncached, callee, args);
    }

    private static LuaCoroutine checkCoroutine(String fname, int argN, Object v) {
        if (v instanceof LuaCoroutine co) {
            return co;
        }
        throw argError(fname, argN, "coroutine", v);
    }
}

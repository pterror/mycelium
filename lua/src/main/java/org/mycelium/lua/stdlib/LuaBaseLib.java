package org.mycelium.lua.stdlib;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.source.Source;
import java.util.Arrays;
import org.mycelium.lua.LuaContext;
import org.mycelium.lua.LuaException;
import org.mycelium.lua.LuaLanguage;
import org.mycelium.lua.nodes.LuaRootNode;
import org.mycelium.lua.nodes.controlflow.LuaReturnException;
import org.mycelium.lua.parser.LuaLexer;
import org.mycelium.lua.parser.LuaParseError;
import org.mycelium.lua.parser.LuaParser;
import org.mycelium.lua.runtime.LuaFunction;
import org.mycelium.lua.runtime.LuaNil;
import org.mycelium.lua.runtime.LuaStringMetatable;
import org.mycelium.lua.runtime.LuaTable;
import org.mycelium.lua.runtime.LuaTypeUtils;

import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.arg;
import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.allArgs;
import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.wrap;
import static org.mycelium.lua.stdlib.LuaLibUtil.*;

/** Lua 5.1 &sect;5.1: the base library, installed directly as fields of the global table. */
final class LuaBaseLib {

    private static final Object[] NO_VALUES = new Object[0];

    private LuaBaseLib() {}

    static void install(TruffleLanguage<?> language, LuaTable globals) {
        globals.rawset("_VERSION", "Lua 5.1");
        globals.rawset("_G", globals);

        globals.rawset("print", wrap(language, "print", (frame, callNode) -> {
            Object[] args = allArgs(frame);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    sb.append('\t');
                }
                sb.append(tostring(args[i], callNode));
            }
            System.out.println(sb);
            return LuaNil.INSTANCE;
        }));

        globals.rawset("type", wrap(language, "type", (frame, callNode) ->
                LuaTypeUtils.typeName(arg(frame, 1))));

        globals.rawset("tostring", wrap(language, "tostring", (frame, callNode) ->
                tostring(arg(frame, 1), callNode)));

        globals.rawset("tonumber", wrap(language, "tonumber", (frame, callNode) -> {
            Object v = arg(frame, 1);
            Object baseArg = arg(frame, 2);
            if (isNil(baseArg)) {
                Double n = LuaTypeUtils.toNumber(v);
                return n == null ? LuaNil.INSTANCE : n;
            }
            String s = (v instanceof String str) ? str.trim() : null;
            if (s == null) {
                throw argError("tonumber", 1, "string", v);
            }
            int base = (int) checkNumber("tonumber", 2, baseArg);
            if (base < 2 || base > 36) {
                throw LuaException.runtimeError(null, "bad argument #2 to 'tonumber' (base out of range)");
            }
            boolean negative = s.startsWith("-");
            String digits = negative ? s.substring(1) : (s.startsWith("+") ? s.substring(1) : s);
            if (digits.isEmpty()) {
                return LuaNil.INSTANCE;
            }
            try {
                long parsed = Long.parseLong(digits, base);
                return negative ? -(double) parsed : (double) parsed;
            } catch (NumberFormatException e) {
                return LuaNil.INSTANCE;
            }
        }));

        globals.rawset("assert", wrap(language, "assert", (frame, callNode) -> {
            Object[] args = allArgs(frame);
            Object v = args.length > 0 ? args[0] : LuaNil.INSTANCE;
            if (!LuaTypeUtils.toBoolean(v)) {
                Object message = args.length > 1 ? args[1] : "assertion failed!";
                throw new LuaException(message, null);
            }
            throw new LuaReturnException(args);
        }));

        globals.rawset("error", wrap(language, "error", (frame, callNode) -> {
            Object message = arg(frame, 1);
            // Level (arg 2) governs where a string message's position prefix points; ignored here
            // since this tree-walk interpreter has no separate "caller" source position to splice
            // in without deeper plumbing.
            throw new LuaException(message, null);
        }));

        globals.rawset("pcall", wrap(language, "pcall", (frame, callNode) -> {
            Object[] args = allArgs(frame);
            if (args.length == 0) {
                throw LuaException.runtimeError(null, "bad argument #1 to 'pcall' (value expected)");
            }
            Object fn = args[0];
            Object[] callArgs = Arrays.copyOfRange(args, 1, args.length);
            try {
                Object[] results = LuaCallSupport.call(null, callNode, fn, callArgs);
                Object[] out = new Object[results.length + 1];
                out[0] = Boolean.TRUE;
                System.arraycopy(results, 0, out, 1, results.length);
                throw new LuaReturnException(out);
            } catch (LuaException e) {
                throw new LuaReturnException(new Object[] {Boolean.FALSE, e.getErrorObject()});
            }
        }));

        globals.rawset("xpcall", wrap(language, "xpcall", (frame, callNode) -> {
            Object fn = arg(frame, 1);
            Object handler = arg(frame, 2);
            try {
                Object[] results = LuaCallSupport.call(null, callNode, fn, NO_VALUES);
                Object[] out = new Object[results.length + 1];
                out[0] = Boolean.TRUE;
                System.arraycopy(results, 0, out, 1, results.length);
                throw new LuaReturnException(out);
            } catch (LuaException e) {
                Object[] handlerResult = LuaCallSupport.call(null, callNode, handler, new Object[] {e.getErrorObject()});
                throw new LuaReturnException(new Object[] {Boolean.FALSE, LuaCallSupport.first(handlerResult)});
            }
        }));

        LuaFunction nextFn = wrap(language, "next", (frame, callNode) -> nextImpl(frame));
        globals.rawset("next", nextFn);

        globals.rawset("pairs", wrap(language, "pairs", (frame, callNode) -> {
            LuaTable t = checkTable("pairs", 1, arg(frame, 1));
            throw new LuaReturnException(new Object[] {nextFn, t, LuaNil.INSTANCE});
        }));

        LuaFunction inext = wrap(language, "inext", (frame, callNode) -> {
            LuaTable t = checkTable("ipairs", 1, arg(frame, 1));
            double prev = checkNumber("ipairs", 2, arg(frame, 2));
            long idx = (long) prev + 1;
            Object v = t.rawget(idx);
            if (isNil(v)) {
                return LuaNil.INSTANCE;
            }
            throw new LuaReturnException(new Object[] {(double) idx, v});
        });
        globals.rawset("ipairs", wrap(language, "ipairs", (frame, callNode) -> {
            LuaTable t = checkTable("ipairs", 1, arg(frame, 1));
            throw new LuaReturnException(new Object[] {inext, t, 0.0});
        }));

        globals.rawset("select", wrap(language, "select", (frame, callNode) -> {
            Object idx = arg(frame, 1);
            Object[] all = allArgs(frame);
            if (idx instanceof String s && s.equals("#")) {
                return (double) (all.length - 1);
            }
            double n = checkNumber("select", 1, idx);
            int i = (int) n;
            int available = all.length - 1;
            if (i < 0) {
                i = available + i + 1;
            }
            if (i < 1) {
                throw LuaException.runtimeError(null, "bad argument #1 to 'select' (index out of range)");
            }
            if (i > available) {
                throw new LuaReturnException(NO_VALUES);
            }
            throw new LuaReturnException(Arrays.copyOfRange(all, i, all.length));
        }));

        globals.rawset("unpack", wrap(language, "unpack", (frame, callNode) -> {
            LuaTable t = checkTable("unpack", 1, arg(frame, 1));
            long i = (long) optNumber(arg(frame, 2), 1);
            long j = (long) optNumber(arg(frame, 3), t.rawlen());
            if (i > j) {
                throw new LuaReturnException(NO_VALUES);
            }
            int count = (int) (j - i + 1);
            Object[] result = new Object[count];
            for (int k = 0; k < count; k++) {
                Object v = t.rawget(i + k);
                result[k] = v == null ? LuaNil.INSTANCE : v;
            }
            throw new LuaReturnException(result);
        }));

        globals.rawset("rawget", wrap(language, "rawget", (frame, callNode) -> {
            LuaTable t = checkTable("rawget", 1, arg(frame, 1));
            Object v = t.rawget(arg(frame, 2));
            return v == null ? LuaNil.INSTANCE : v;
        }));

        globals.rawset("rawset", wrap(language, "rawset", (frame, callNode) -> {
            LuaTable t = checkTable("rawset", 1, arg(frame, 1));
            try {
                t.rawset(arg(frame, 2), arg(frame, 3));
            } catch (IllegalArgumentException e) {
                throw LuaException.runtimeError(null, e.getMessage());
            }
            return t;
        }));

        globals.rawset("rawequal", wrap(language, "rawequal", (frame, callNode) ->
                rawEquals(arg(frame, 1), arg(frame, 2))));

        globals.rawset("setmetatable", wrap(language, "setmetatable", (frame, callNode) -> {
            LuaTable t = checkTable("setmetatable", 1, arg(frame, 1));
            Object metaArg = arg(frame, 2);
            if (t.getMetatable() != null && rawMetatableField(t.getMetatable(), "__metatable") != null) {
                throw LuaException.runtimeError(null, "cannot change a protected metatable");
            }
            if (isNil(metaArg)) {
                t.setMetatable(null);
            } else if (metaArg instanceof LuaTable mt) {
                t.setMetatable(mt);
            } else {
                throw argError("setmetatable", 2, "nil or table", metaArg);
            }
            return t;
        }));

        globals.rawset("getmetatable", wrap(language, "getmetatable", (frame, callNode) -> {
            Object v = arg(frame, 1);
            LuaTable mt = null;
            if (v instanceof LuaTable t) {
                mt = t.getMetatable();
            } else if (v instanceof String) {
                mt = LuaStringMetatable.get();
            }
            if (mt == null) {
                return LuaNil.INSTANCE;
            }
            Object protectedMeta = rawMetatableField(mt, "__metatable");
            return protectedMeta != null ? protectedMeta : mt;
        }));

        globals.rawset("collectgarbage", wrap(language, "collectgarbage", (frame, callNode) -> 0.0));

        globals.rawset("require", wrap(language, "require", (frame, callNode) -> {
            throw LuaException.runtimeError(null, "require not yet implemented");
        }));
        globals.rawset("dofile", wrap(language, "dofile", (frame, callNode) -> {
            throw LuaException.runtimeError(null, "dofile not yet implemented");
        }));
        globals.rawset("load", wrap(language, "load", (frame, callNode) -> {
            Object func = arg(frame, 1);
            String chunkname = optString(arg(frame, 2), "=(load)");
            StringBuilder sb = new StringBuilder();
            while (true) {
                Object[] result = LuaCallSupport.call(null, callNode, func, new Object[0]);
                Object piece = LuaCallSupport.first(result);
                if (isNil(piece)) {
                    break;
                }
                String s = (piece instanceof String str) ? str : null;
                if (s == null) {
                    throw new LuaReturnException(
                            new Object[] {LuaNil.INSTANCE, "reader function must return a string"});
                }
                if (s.isEmpty()) {
                    break;
                }
                sb.append(s);
            }
            throw new LuaReturnException(loadChunk(language, callNode, sb.toString(), chunkname));
        }));
        globals.rawset("loadstring", wrap(language, "loadstring", (frame, callNode) -> {
            String code = checkString("loadstring", 1, arg(frame, 1));
            String chunkname = optString(arg(frame, 2), code);
            throw new LuaReturnException(loadChunk(language, callNode, code, chunkname));
        }));
    }

    /**
     * Shared {@code load}/{@code loadstring} implementation (Lua 5.1 &sect;5.1): parses {@code code}
     * as a chunk and, on success, returns a one-element array holding the compiled function (its
     * environment set to the current context's globals); on a parse error, returns
     * {@code {nil, errorMessage}} rather than throwing, per Lua semantics.
     */
    private static Object[] loadChunk(
            TruffleLanguage<?> language, IndirectCallNode callNode, String code, String chunkname) {
        try {
            Source source = Source.newBuilder(LuaLanguage.ID, code, chunkname).build();
            LuaParser parser = new LuaParser(language, source);
            LuaRootNode chunkRootNode = parser.parse();
            CallTarget chunkCallTarget = chunkRootNode.getCallTarget();
            LuaFunction chunkFunction = new LuaFunction(chunkCallTarget, null);
            LuaContext context = LuaLanguage.getContext(callNode);
            chunkFunction.setEnvironment(context.getGlobals());
            return new Object[] {chunkFunction};
        } catch (LuaParseError | LuaLexer.LuaLexerException e) {
            return new Object[] {LuaNil.INSTANCE, e.getMessage()};
        }
    }

    /** {@code tostring}: honors a table's {@code __tostring} metamethod before falling back to
     * {@link LuaTypeUtils#tostring}. */
    static String tostring(Object v, IndirectCallNode callNode) {
        if (v instanceof LuaTable t) {
            Object handler = t.getMetamethod("__tostring");
            if (handler != null) {
                Object[] result = LuaCallSupport.call(null, callNode, handler, new Object[] {v});
                Object first = LuaCallSupport.first(result);
                return LuaTypeUtils.tostring(first);
            }
        }
        return LuaTypeUtils.tostring(v);
    }

    /**
     * Reads a field directly off a metatable itself (e.g. {@code mt.__metatable}), as opposed to
     * {@link LuaTable#getMetamethod}, which reads a field off a value's <em>own</em> metatable.
     * {@code __metatable} lives as a plain field on the metatable, not behind a further nested
     * metatable, so {@code mt.getMetamethod(name)} would incorrectly look at {@code mt}'s own
     * metatable (almost always absent) rather than {@code mt} itself. Returns {@code null} if the
     * field is absent or nil.
     */
    private static Object rawMetatableField(LuaTable mt, String name) {
        Object value = mt.rawget(name);
        return (value == null || value == LuaNil.INSTANCE) ? null : value;
    }

    private static Object nextImpl(com.oracle.truffle.api.frame.VirtualFrame frame) {
        LuaTable t = checkTable("next", 1, arg(frame, 1));
        Object key = arg(frame, 2);
        Object[] kv;
        try {
            kv = t.next(isNil(key) ? null : key);
        } catch (IllegalArgumentException e) {
            throw LuaException.runtimeError(null, e.getMessage());
        }
        if (kv == null) {
            return LuaNil.INSTANCE;
        }
        throw new LuaReturnException(kv);
    }
}

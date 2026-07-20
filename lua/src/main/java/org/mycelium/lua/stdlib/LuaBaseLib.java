package org.mycelium.lua.stdlib;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.source.Source;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
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
            int level = (int) optNumber(arg(frame, 2), 1);
            if (message instanceof String s && level > 0) {
                String prefix = locationPrefixAtLevel(level);
                if (prefix != null) {
                    message = prefix + s;
                }
            }
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
            } catch (StackOverflowError e) {
                throw new LuaReturnException(new Object[] {Boolean.FALSE, "stack overflow"});
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
            } catch (StackOverflowError e) {
                Object[] handlerResult = LuaCallSupport.call(null, callNode, handler, new Object[] {"stack overflow"});
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

        // `require` and the `package` table (Lua 5.1 §5.3) are installed separately by
        // LuaPackageLib.install, which needs the full LuaContext (not just globals) to resolve
        // package.path relative to the working directory — see LuaStdLib#installPackage.

        globals.rawset("dofile", wrap(language, "dofile", (frame, callNode) -> {
            Object filenameArg = arg(frame, 1);
            if (isNil(filenameArg)) {
                // Lua 5.1 §5.1: with no argument, dofile executes stdin. Not implemented — no
                // existing caller in this codebase feeds an interactive/stdin chunk this way yet.
                throw LuaException.runtimeError(null, "dofile from stdin not yet implemented");
            }
            String filename = checkString("dofile", 1, filenameArg);
            String code;
            try {
                code = java.nio.file.Files.readString(
                        java.nio.file.Path.of(filename), java.nio.charset.StandardCharsets.ISO_8859_1);
            } catch (java.io.IOException e) {
                throw LuaException.runtimeError(null, "cannot open " + filename + ": " + e.getMessage());
            }
            Object[] chunkResult = loadChunk(language, callNode, code, filename);
            Object chunkOrError = chunkResult[0];
            if (isNil(chunkOrError)) {
                throw LuaException.runtimeError(null, String.valueOf(chunkResult[1]));
            }
            Object[] results = LuaCallSupport.call(null, callNode, chunkOrError, new Object[0]);
            throw new LuaReturnException(results);
        }));
        globals.rawset("loadfile", wrap(language, "loadfile", (frame, callNode) -> {
            Object filenameArg = arg(frame, 1);
            if (isNil(filenameArg)) {
                // Lua 5.1 §5.1: with no argument, loadfile reads stdin. Not implemented — no
                // existing caller in this codebase feeds an interactive/stdin chunk this way yet.
                throw LuaException.runtimeError(null, "loadfile from stdin not yet implemented");
            }
            String filename = checkString("loadfile", 1, filenameArg);
            String code;
            try {
                code = java.nio.file.Files.readString(
                        java.nio.file.Path.of(filename), java.nio.charset.StandardCharsets.ISO_8859_1);
            } catch (java.io.IOException e) {
                throw new LuaReturnException(
                        new Object[] {LuaNil.INSTANCE, "cannot open " + filename + ": " + e.getMessage()});
            }
            throw new LuaReturnException(loadChunk(language, callNode, code, "@" + filename));
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

        // setfenv/getfenv (Lua 5.1 §5.1): get/set a function's environment table (the table its
        // global reads/writes resolve against &mdash; see LuaReadGlobalNode/LuaWriteGlobalNode).
        // `f` may be a function directly, or a stack level (1 = the function calling
        // setfenv/getfenv, 2 = that function's caller, ...). Level 0 ("the running thread's
        // globals") isn't meaningfully distinct from _G in this implementation and isn't supported.
        globals.rawset("setfenv", wrap(language, "setfenv", (frame, callNode) -> {
            Object target = arg(frame, 1);
            LuaTable env = checkTable("setfenv", 2, arg(frame, 2));
            if (target instanceof LuaFunction fn) {
                fn.setEnvironment(env);
                return fn;
            }
            int level = (int) checkNumber("setfenv", 1, target);
            LuaFunction fn = functionAtLevel(level);
            if (fn == null) {
                throw LuaException.runtimeError(null, "bad argument #1 to 'setfenv' (invalid level)");
            }
            fn.setEnvironment(env);
            return fn;
        }));

        globals.rawset("getfenv", wrap(language, "getfenv", (frame, callNode) -> {
            Object target = arg(frame, 1);
            if (target instanceof LuaFunction fn) {
                LuaTable env = fn.getEnvironment();
                return env != null ? env : LuaLanguage.getContext(callNode).getGlobals();
            }
            int level = isNil(target) ? 1 : (int) checkNumber("getfenv", 1, target);
            LuaFunction fn = functionAtLevel(level);
            if (fn == null) {
                throw LuaException.runtimeError(null, "bad argument #1 to 'getfenv' (invalid level)");
            }
            LuaTable env = fn.getEnvironment();
            return env != null ? env : LuaLanguage.getContext(callNode).getGlobals();
        }));
    }

    /**
     * Lua 5.1 &sect;5.1 {@code error}'s {@code level} argument: {@code "chunkname:currentline: "}
     * for the stack frame {@code level} steps above {@code error} itself (level 1 = the function
     * that called {@code error}, level 2 = that function's caller, ...), or {@code null} if no
     * source position is available (e.g. the level is out of range, or that frame is a builtin
     * with no Lua source). Mirrors {@link #functionAtLevel}'s frame-walking approach, but collects
     * {@link RootNode}s (for their {@link SourceSection}) rather than {@link LuaFunction}s, and
     * &mdash; like {@code debug.getinfo}'s {@code currentline} (see {@link LuaDebugLib}) &mdash;
     * approximates the current line with the frame's root {@code SourceSection} start line, since
     * this tree-walk interpreter doesn't track a separate per-statement "current line" once a call
     * is in progress.
     */
    private static String locationPrefixAtLevel(int level) {
        if (level < 1) {
            return null;
        }
        java.util.List<com.oracle.truffle.api.nodes.RootNode> frames = new java.util.ArrayList<>();
        Truffle.getRuntime().iterateFrames(frameInstance -> {
            com.oracle.truffle.api.CallTarget ct = frameInstance.getCallTarget();
            if (ct instanceof com.oracle.truffle.api.RootCallTarget rct
                    && !rct.getRootNode().isInternal()) {
                frames.add(rct.getRootNode());
            }
            return null;
        });
        // frames.get(0) is error()'s own builtin frame, so level 1 (the caller of error) is at
        // index 1, level 2 at index 2, and so on.
        if (level >= frames.size()) {
            return null;
        }
        com.oracle.truffle.api.nodes.RootNode root = frames.get(level);
        com.oracle.truffle.api.source.SourceSection section = root.getSourceSection();
        if (section == null || !section.isAvailable()) {
            return null;
        }
        return section.getSource().getName() + ":" + section.getStartLine() + ": ";
    }

    /**
     * Finds the {@link LuaFunction} closure executing at the given stack {@code level}, where
     * level 1 is the function that called the setfenv/getfenv builtin itself, level 2 its caller,
     * and so on &mdash; matching {@code debug.getinfo}'s level numbering (Lua 5.1 &sect;5.9). On
     * this runtime configuration {@link Truffle#iterateFrames} reports the currently-executing
     * frame first (i.e. the setfenv/getfenv builtin's own frame), so that first frame is skipped
     * before counting down to {@code level}.
     */
    private static LuaFunction functionAtLevel(int level) {
        if (level < 1) {
            return null;
        }
        AtomicInteger remaining = new AtomicInteger(level);
        boolean[] skippedSelf = {false};
        Object found = Truffle.getRuntime().iterateFrames(frameInstance -> {
            Object[] args = frameInstance.getFrame(FrameInstance.FrameAccess.READ_ONLY).getArguments();
            if (args.length == 0 || !(args[0] instanceof LuaFunction fn)) {
                return null;
            }
            if (!skippedSelf[0]) {
                skippedSelf[0] = true;
                return null;
            }
            if (remaining.decrementAndGet() == 0) {
                return fn;
            }
            return null;
        });
        return found instanceof LuaFunction fn ? fn : null;
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

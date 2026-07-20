package org.mycelium.lua.stdlib;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import java.util.ArrayList;
import java.util.List;
import org.mycelium.lua.LuaContext;
import org.mycelium.lua.LuaLanguage;
import org.mycelium.lua.nodes.controlflow.LuaReturnException;
import org.mycelium.lua.runtime.LuaFunction;
import org.mycelium.lua.runtime.LuaNil;
import org.mycelium.lua.runtime.LuaStringMetatable;
import org.mycelium.lua.runtime.LuaTable;
import org.mycelium.lua.runtime.LuaTypeUtils;

import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.arg;
import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.wrap;
import static org.mycelium.lua.stdlib.LuaLibUtil.isNil;
import static org.mycelium.lua.stdlib.LuaLibUtil.optNumber;

/**
 * Lua 5.1 &sect;5.9: the {@code debug} library.
 *
 * <p>The parser attaches a {@link SourceSection} to every compiled function's {@link RootNode}
 * (spanning its {@code function}/name through the matching {@code end}, or the whole chunk for a
 * top-level compile), which is what {@code source}/{@code short_src}/{@code linedefined} below are
 * read from. Some introspection facilities are still not available in this tree-walk interpreter
 * &mdash; per-statement {@code currentline} while a call is in progress, precise upvalue/local
 * names &mdash; and those are implemented on a best-effort basis: they never throw for missing
 * information, returning {@code nil} (or a placeholder) instead, exactly as real Lua programs
 * expect when probing optional debug info.
 */
final class LuaDebugLib {

    private LuaDebugLib() {}

    static void install(TruffleLanguage<?> language, LuaTable globals) {
        LuaTable debug = new LuaTable();
        globals.rawset("debug", debug);

        // A private registry table, created once per install() call (i.e. once per LuaContext),
        // for debug.getregistry(). Captured by the closure below rather than stored on LuaContext.
        LuaTable registry = new LuaTable();

        debug.rawset("traceback", wrap(language, "traceback", (frame, callNode) -> {
            Object msgArg = arg(frame, 1);
            Object levelArg = arg(frame, 2);
            // Lua 5.1: a non-nil, non-string message is returned unchanged (so xpcall handlers
            // that receive a non-string error object can just pass it straight through).
            if (!isNil(msgArg) && !(msgArg instanceof String)) {
                return msgArg;
            }
            int level = (int) optNumber(levelArg, 1);
            if (level < 0) {
                level = 0;
            }

            List<RootNode> frames = collectFrames();
            StringBuilder sb = new StringBuilder();
            if (msgArg instanceof String s) {
                sb.append(s).append('\n');
            }
            sb.append("stack traceback:");
            for (int i = level; i < frames.size(); i++) {
                sb.append("\n\t").append(formatFrameLine(frames.get(i)));
            }
            return sb.toString();
        }));

        debug.rawset("getinfo", wrap(language, "getinfo", (frame, callNode) -> {
            Object target = arg(frame, 1);
            String what = arg(frame, 2) instanceof String s ? s : "flnSu";

            LuaFunction fn = null;
            RootNode root = null;

            if (target instanceof LuaFunction f) {
                fn = f;
                CallTarget ct = f.getCallTarget();
                if (ct instanceof RootCallTarget rct) {
                    root = rct.getRootNode();
                }
            } else {
                Double levelNum = LuaTypeUtils.toNumber(target);
                if (levelNum == null) {
                    return LuaNil.INSTANCE;
                }
                int level = levelNum.intValue();
                List<FrameInstance> instances = collectFrameInstances();
                if (level < 0 || level >= instances.size()) {
                    return LuaNil.INSTANCE;
                }
                FrameInstance instance = instances.get(level);
                CallTarget ct = instance.getCallTarget();
                if (ct instanceof RootCallTarget rct) {
                    root = rct.getRootNode();
                }
                Frame calleeFrame = instance.getFrame(FrameInstance.FrameAccess.READ_ONLY);
                Object[] args = calleeFrame.getArguments();
                if (args.length > 0 && args[0] instanceof LuaFunction f) {
                    fn = f;
                }
            }

            if (root == null && fn == null) {
                return LuaNil.INSTANCE;
            }

            boolean isBuiltin = root instanceof LuaBuiltinRootNode;
            LuaTable info = new LuaTable();

            SourceSection rootSection = root != null ? root.getSourceSection() : null;
            if (what.indexOf('S') >= 0) {
                if (isBuiltin) {
                    info.rawset("source", "=[C]");
                    info.rawset("short_src", "[C]");
                    info.rawset("what", "C");
                    info.rawset("linedefined", -1.0);
                    info.rawset("lastlinedefined", -1.0);
                } else if (rootSection != null) {
                    String srcName = rootSection.getSource().getName();
                    info.rawset("source", "@" + srcName);
                    info.rawset("short_src", srcName);
                    info.rawset("what", "Lua");
                    info.rawset("linedefined", (double) rootSection.getStartLine());
                    info.rawset("lastlinedefined", (double) rootSection.getEndLine());
                } else {
                    info.rawset("source", "=?");
                    info.rawset("short_src", "?");
                    info.rawset("what", "Lua");
                    info.rawset("linedefined", -1.0);
                    info.rawset("lastlinedefined", -1.0);
                }
            }
            if (what.indexOf('l') >= 0) {
                info.rawset("currentline", rootSection != null ? (double) rootSection.getStartLine() : -1.0);
            }
            if (what.indexOf('n') >= 0) {
                String name = root != null ? root.getName() : null;
                info.rawset("name", name == null ? LuaNil.INSTANCE : name);
                info.rawset("namewhat", name == null ? "" : "global");
            }
            if (what.indexOf('u') >= 0) {
                info.rawset("nups", 0.0);
            }
            if (what.indexOf('f') >= 0) {
                info.rawset("func", fn == null ? LuaNil.INSTANCE : fn);
            }
            return info;
        }));

        debug.rawset("getmetatable", wrap(language, "getmetatable", (frame, callNode) -> {
            Object v = arg(frame, 1);
            if (v instanceof LuaTable t) {
                LuaTable mt = t.getMetatable();
                return mt == null ? LuaNil.INSTANCE : mt;
            }
            if (v instanceof String) {
                LuaTable mt = LuaStringMetatable.get();
                return mt == null ? LuaNil.INSTANCE : mt;
            }
            return LuaNil.INSTANCE;
        }));

        debug.rawset("setmetatable", wrap(language, "setmetatable", (frame, callNode) -> {
            Object v = arg(frame, 1);
            Object mtArg = arg(frame, 2);
            LuaTable mt = mtArg instanceof LuaTable t ? t : null;
            if (v instanceof LuaTable t) {
                t.setMetatable(mt);
            } else if (v instanceof String) {
                LuaStringMetatable.set(mt);
            }
            return v;
        }));

        debug.rawset("getupvalue", wrap(language, "getupvalue", (frame, callNode) -> {
            // Upvalue names/slots aren't tracked separately from the enclosing frame in this
            // interpreter; report "no such upvalue" uniformly.
            throw new LuaReturnException(new Object[] {LuaNil.INSTANCE});
        }));

        debug.rawset("setupvalue", wrap(language, "setupvalue", (frame, callNode) ->
                LuaNil.INSTANCE));

        debug.rawset("getlocal", wrap(language, "getlocal", (frame, callNode) ->
                LuaNil.INSTANCE));

        debug.rawset("setlocal", wrap(language, "setlocal", (frame, callNode) ->
                LuaNil.INSTANCE));

        debug.rawset("sethook", wrap(language, "sethook", (frame, callNode) ->
                LuaNil.INSTANCE));

        debug.rawset("gethook", wrap(language, "gethook", (frame, callNode) ->
                LuaNil.INSTANCE));

        debug.rawset("getfenv", wrap(language, "getfenv", (frame, callNode) -> {
            Object o = arg(frame, 1);
            if (o instanceof LuaFunction fn) {
                LuaTable env = fn.getEnvironment();
                if (env != null) {
                    return env;
                }
            }
            LuaContext context = LuaLanguage.getContext(callNode);
            return context.getGlobals();
        }));

        debug.rawset("setfenv", wrap(language, "setfenv", (frame, callNode) -> {
            Object o = arg(frame, 1);
            Object tableArg = arg(frame, 2);
            if (o instanceof LuaFunction fn && tableArg instanceof LuaTable t) {
                fn.setEnvironment(t);
            }
            return o;
        }));

        debug.rawset("getregistry", wrap(language, "getregistry", (frame, callNode) -> registry));
    }

    /** {@code RootNode}s of every frame currently on the Truffle call stack, from the caller of
     * whichever debug-library builtin invoked this (index 0) outward to the outermost chunk. */
    private static List<RootNode> collectFrames() {
        List<RootNode> frames = new ArrayList<>();
        Truffle.getRuntime().iterateFrames(frameInstance -> {
            CallTarget ct = frameInstance.getCallTarget();
            if (ct instanceof RootCallTarget rct && !rct.getRootNode().isInternal()) {
                frames.add(rct.getRootNode());
            }
            return null;
        });
        return frames;
    }

    /** Same traversal as {@link #collectFrames()}, but keeping the raw {@link FrameInstance}s so
     * callers can also recover the actual {@link LuaFunction} closure (frame argument 0). */
    private static List<FrameInstance> collectFrameInstances() {
        List<FrameInstance> frames = new ArrayList<>();
        Truffle.getRuntime().iterateFrames(frameInstance -> {
            CallTarget ct = frameInstance.getCallTarget();
            if (!(ct instanceof RootCallTarget rct) || !rct.getRootNode().isInternal()) {
                frames.add(frameInstance);
            }
            return null;
        });
        return frames;
    }

    private static String formatFrameLine(RootNode root) {
        if (root instanceof LuaBuiltinRootNode) {
            String name = root.getName();
            return "[C]: in function '" + (name == null ? "?" : name) + "'";
        }
        SourceSection section = root.getSourceSection();
        String location = section != null ? section.getSource().getName() + ":" + section.getStartLine() : "?";
        String name = root.getName();
        if (name == null || name.isEmpty()) {
            return location + ": in function <" + location + ">";
        }
        return location + ": in function '" + name + "'";
    }
}

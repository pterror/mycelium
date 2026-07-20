package org.mycelium.lua.stdlib;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import java.util.ArrayList;
import java.util.List;
import org.mycelium.lua.LuaContext;
import org.mycelium.lua.LuaLanguage;
import org.mycelium.lua.nodes.LuaRootNode;
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
                String namewhat = "";
                if (name != null && fn != null) {
                    LuaContext context = LuaLanguage.getContext(callNode);
                    Object globalValue = context.getGlobals().rawget(name);
                    if (globalValue == fn) {
                        namewhat = "global";
                    }
                }
                info.rawset("namewhat", namewhat);
            }
            if (what.indexOf('u') >= 0) {
                MaterializedFrame enclosing = fn == null ? null : fn.getEnclosingFrame();
                info.rawset("nups", (double) countNamedSlots(enclosing));
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
            // Upvalues aren't tracked separately from the enclosing (closed-over) frame in this
            // interpreter, so every named slot of that frame is exposed as an upvalue slot; see
            // countNamedSlots/upvalueSlotName below.
            Object target = arg(frame, 1);
            if (!(target instanceof LuaFunction fn)) {
                return LuaNil.INSTANCE;
            }
            MaterializedFrame enclosing = fn.getEnclosingFrame();
            if (enclosing == null) {
                return LuaNil.INSTANCE;
            }
            Double indexNum = LuaTypeUtils.toNumber(arg(frame, 2));
            if (indexNum == null) {
                return LuaNil.INSTANCE;
            }
            int slot = upvalueSlot(enclosing.getFrameDescriptor(), indexNum.intValue());
            if (slot < 0) {
                return LuaNil.INSTANCE;
            }
            String name = (String) enclosing.getFrameDescriptor().getSlotName(slot);
            Object value = enclosing.getObject(slot);
            return new Object[] {name, value == null ? LuaNil.INSTANCE : value};
        }));

        debug.rawset("setupvalue", wrap(language, "setupvalue", (frame, callNode) -> {
            Object target = arg(frame, 1);
            if (!(target instanceof LuaFunction fn)) {
                return LuaNil.INSTANCE;
            }
            MaterializedFrame enclosing = fn.getEnclosingFrame();
            if (enclosing == null) {
                return LuaNil.INSTANCE;
            }
            Double indexNum = LuaTypeUtils.toNumber(arg(frame, 2));
            if (indexNum == null) {
                return LuaNil.INSTANCE;
            }
            int slot = upvalueSlot(enclosing.getFrameDescriptor(), indexNum.intValue());
            if (slot < 0) {
                return LuaNil.INSTANCE;
            }
            Object value = arg(frame, 3);
            enclosing.setObject(slot, value);
            return enclosing.getFrameDescriptor().getSlotName(slot);
        }));

        debug.rawset("getlocal", wrap(language, "getlocal", (frame, callNode) -> {
            Double levelNum = LuaTypeUtils.toNumber(arg(frame, 1));
            Double indexNum = LuaTypeUtils.toNumber(arg(frame, 2));
            if (levelNum == null || indexNum == null) {
                return LuaNil.INSTANCE;
            }
            Frame target = resolveLevelFrame(levelNum.intValue(), FrameInstance.FrameAccess.READ_ONLY);
            if (target == null) {
                return LuaNil.INSTANCE;
            }
            int index = indexNum.intValue();
            if (index < 0) {
                Object[] varargs = readVarargs(target);
                int vi = varargs.length + index;
                if (vi < 0 || vi >= varargs.length) {
                    return LuaNil.INSTANCE;
                }
                Object value = varargs[vi];
                return new Object[] {"(vararg)", value == null ? LuaNil.INSTANCE : value};
            }
            FrameDescriptor descriptor = target.getFrameDescriptor();
            if (index < 1 || index >= descriptor.getNumberOfSlots()) {
                return LuaNil.INSTANCE;
            }
            String name = (String) descriptor.getSlotName(index);
            Object value = target.getObject(index);
            return new Object[] {name, value == null ? LuaNil.INSTANCE : value};
        }));

        debug.rawset("setlocal", wrap(language, "setlocal", (frame, callNode) -> {
            Double levelNum = LuaTypeUtils.toNumber(arg(frame, 1));
            Double indexNum = LuaTypeUtils.toNumber(arg(frame, 2));
            if (levelNum == null || indexNum == null) {
                return LuaNil.INSTANCE;
            }
            Frame target = resolveLevelFrame(levelNum.intValue(), FrameInstance.FrameAccess.READ_WRITE);
            if (target == null) {
                return LuaNil.INSTANCE;
            }
            int index = indexNum.intValue();
            if (index < 1) {
                // Varargs aren't named locals; setting them isn't a meaningful operation here.
                return LuaNil.INSTANCE;
            }
            FrameDescriptor descriptor = target.getFrameDescriptor();
            if (index >= descriptor.getNumberOfSlots()) {
                return LuaNil.INSTANCE;
            }
            Object value = arg(frame, 3);
            target.setObject(index, value);
            return descriptor.getSlotName(index);
        }));

        debug.rawset("sethook", wrap(language, "sethook", (frame, callNode) -> {
            LuaContext context = LuaLanguage.getContext(callNode);
            Object hookArg = arg(frame, 1);
            if (isNil(hookArg)) {
                // debug.sethook() with no arguments removes the current hook (Lua 5.1 §5.9).
                context.clearHook();
                return LuaNil.INSTANCE;
            }
            if (!(hookArg instanceof LuaFunction fn)) {
                return LuaNil.INSTANCE;
            }
            Object maskArg = arg(frame, 2);
            String mask = maskArg instanceof String s ? s : "";
            int count = (int) optNumber(arg(frame, 3), 0);
            context.setHook(fn, mask, count);
            return LuaNil.INSTANCE;
        }));

        debug.rawset("gethook", wrap(language, "gethook", (frame, callNode) -> {
            LuaContext context = LuaLanguage.getContext(callNode);
            LuaFunction hook = context.getHookFunction();
            if (hook == null) {
                return new Object[] {LuaNil.INSTANCE};
            }
            return new Object[] {hook, context.getHookMask(), (double) context.getHookCount()};
        }));

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

        // debug.debug() (Lua 5.1 §5.9): in PUC-Rio this enters an interactive prompt for running
        // arbitrary commands until "cont" is typed. There's no interactive console to drive here,
        // so this is a no-op that returns immediately; the important part is that the function
        // exists, so code that probes `debug.debug ~= nil` still works.
        debug.rawset("debug", wrap(language, "debug", (frame, callNode) -> {
            throw new LuaReturnException(new Object[0]);
        }));
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

    /** Resolves a {@code debug.getlocal}/{@code setlocal}-style stack {@code level} (same numbering
     * as {@code debug.getinfo}'s numeric-level form: 0 is the currently running builtin itself, 1
     * is its caller, and so on) to that frame, accessed the way {@code access} requests. Returns
     * {@code null} if {@code level} is out of range. */
    private static Frame resolveLevelFrame(int level, FrameInstance.FrameAccess access) {
        List<FrameInstance> instances = collectFrameInstances();
        if (level < 0 || level >= instances.size()) {
            return null;
        }
        return instances.get(level).getFrame(access);
    }

    /** The vararg values ({@code ...}) captured in {@code frame}'s reserved slot 0, or an empty
     * array if the frame has no slot 0 or it doesn't (yet) hold an {@code Object[]}. */
    private static Object[] readVarargs(Frame frame) {
        FrameDescriptor descriptor = frame.getFrameDescriptor();
        if (descriptor.getNumberOfSlots() <= LuaRootNode.VARARGS_FRAME_SLOT) {
            return new Object[0];
        }
        Object varargs = frame.getObject(LuaRootNode.VARARGS_FRAME_SLOT);
        return varargs instanceof Object[] arr ? arr : new Object[0];
    }

    /** Maps a 1-based {@code debug.getupvalue}/{@code setupvalue} index to a frame slot in {@code
     * descriptor}, per the simplification noted on the library class doc: every named slot of the
     * enclosing frame (i.e. every slot except the unnamed/vararg slot 0) is treated as one upvalue,
     * in slot order. Returns -1 if {@code index} is out of range. */
    private static int upvalueSlot(FrameDescriptor descriptor, int index) {
        if (index < 1) {
            return -1;
        }
        int seen = 0;
        int numSlots = descriptor.getNumberOfSlots();
        for (int slot = 0; slot < numSlots; slot++) {
            if (!(descriptor.getSlotName(slot) instanceof String)) {
                continue;
            }
            if (slot == LuaRootNode.VARARGS_FRAME_SLOT) {
                continue;
            }
            seen++;
            if (seen == index) {
                return slot;
            }
        }
        return -1;
    }

    /** Number of upvalue-eligible named slots in {@code enclosing} (see {@link #upvalueSlot}), used
     * for {@code debug.getinfo}'s {@code nups} field. {@code enclosing} is {@code null} for builtin
     * functions and closures with no captured enclosing frame, which report zero upvalues. */
    private static int countNamedSlots(MaterializedFrame enclosing) {
        if (enclosing == null) {
            return 0;
        }
        FrameDescriptor descriptor = enclosing.getFrameDescriptor();
        int count = 0;
        int numSlots = descriptor.getNumberOfSlots();
        for (int slot = 0; slot < numSlots; slot++) {
            if (slot == LuaRootNode.VARARGS_FRAME_SLOT) {
                continue;
            }
            if (descriptor.getSlotName(slot) instanceof String) {
                count++;
            }
        }
        return count;
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

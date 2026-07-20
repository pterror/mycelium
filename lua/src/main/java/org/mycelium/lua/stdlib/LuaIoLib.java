package org.mycelium.lua.stdlib;

import com.oracle.truffle.api.TruffleLanguage;
import java.io.IOException;
import org.mycelium.lua.LuaContext;
import org.mycelium.lua.LuaException;
import org.mycelium.lua.nodes.controlflow.LuaReturnException;
import org.mycelium.lua.runtime.LuaFunction;
import org.mycelium.lua.runtime.LuaIOFile;
import org.mycelium.lua.runtime.LuaNil;
import org.mycelium.lua.runtime.LuaTable;
import org.mycelium.lua.runtime.LuaTypeUtils;

import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.allArgs;
import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.arg;
import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.wrap;
import static org.mycelium.lua.stdlib.LuaLibUtil.*;

/**
 * Lua 5.1 &sect;5.7: the {@code io} table, plus the shared file-handle metatable that backs every
 * value {@code io.open}/{@code io.lines}/{@code io.tmpfile}/{@code io.stdin} et al. hand back.
 *
 * <p>A file handle is a plain {@link LuaTable} carrying its {@link LuaIOFile} state under a private
 * sentinel key ({@link #FILE_KEY}, a bare {@code Object} that no Lua-level key can ever equal,
 * since Lua source can only produce string/number/boolean/table/function keys) and a metatable
 * whose {@code __index} points at a shared {@code fileMethods} table &mdash; exactly the same
 * trick {@code LuaStringMetatable} uses so that {@code ("s"):upper()} works even though strings
 * have no per-value metatable slot of their own (see that class's doc). This means {@code
 * file:read()} etc. fall out of the ordinary {@code __index} method-call dispatch in {@code
 * LuaMethodCallNode}/{@code LuaNodeUtil} with no changes to the interpreter core.
 *
 * <p><b>Known limitation:</b> because the sentinel key lives in the same hash part {@link
 * LuaTable#next} walks, {@code pairs(fileHandle)} would surface it as a (meaningless) extra key;
 * {@code LuaTable#getMembers} already filters interop-visible members to {@code String} keys, so
 * this only affects direct {@code pairs}/{@code next} use on a file handle, which real Lua
 * programs essentially never do.
 */
final class LuaIoLib {

    /** Private sentinel key under which a file handle table stores its {@link LuaIOFile}; see
     * {@link LuaIOFile#FILE_KEY} for why it lives on that class rather than here. */
    private static final Object FILE_KEY = LuaIOFile.FILE_KEY;

    private LuaIoLib() {}

    static void install(TruffleLanguage<?> language, LuaContext context) {
        LuaTable globals = context.getGlobals();
        TruffleLanguage.Env env = context.getEnv();

        LuaTable io = new LuaTable();
        globals.rawset("io", io);

        LuaTable fileMethods = new LuaTable();
        LuaTable fileMetatable = new LuaTable();
        fileMetatable.rawset("__index", fileMethods);
        fileMetatable.rawset("__tostring", wrap(language, "__tostring", (frame, cn) -> {
            LuaIOFile f = checkFileHandle("__tostring", 1, arg(frame, 1));
            return f.isClosed() ? "file (closed)" : "file (0x" + Integer.toHexString(System.identityHashCode(f)) + ")";
        }));

        installFileMethods(language, fileMethods);

        LuaTable stdinHandle = newFileHandle(LuaIOFile.forInputStream(env.in()), fileMetatable);
        LuaTable stdoutHandle = newFileHandle(LuaIOFile.forOutputStream(env.out()), fileMetatable);
        LuaTable stderrHandle = newFileHandle(LuaIOFile.forOutputStream(env.err()), fileMetatable);
        io.rawset("stdin", stdinHandle);
        io.rawset("stdout", stdoutHandle);
        io.rawset("stderr", stderrHandle);

        // Mutable "current default" boxes, captured by the closures below (same trick LuaStringLib's
        // gmatch uses for its position counter).
        LuaTable[] defaultInput = {stdinHandle};
        LuaTable[] defaultOutput = {stdoutHandle};

        io.rawset("open", wrap(language, "open", (frame, cn) -> {
            String path = checkString("open", 1, arg(frame, 1));
            String mode = optString(arg(frame, 2), "r");
            try {
                LuaIOFile f = LuaIOFile.open(path, mode);
                return newFileHandle(f, fileMetatable);
            } catch (IOException e) {
                throw new LuaReturnException(new Object[] {LuaNil.INSTANCE, path + ": " + e.getMessage()});
            }
        }));

        io.rawset("close", wrap(language, "close", (frame, cn) -> {
            Object v = arg(frame, 1);
            LuaIOFile f = isNil(v) ? getIOFile(defaultOutput[0]) : checkFileHandle("close", 1, v);
            return closeFile(f);
        }));

        io.rawset("read", wrap(language, "read", (frame, cn) -> {
            LuaIOFile f = getIOFile(defaultInput[0]);
            throw new LuaReturnException(readFormats(f, allArgs(frame)));
        }));

        io.rawset("write", wrap(language, "write", (frame, cn) -> {
            LuaIOFile f = getIOFile(defaultOutput[0]);
            try {
                writeValues("write", f, allArgs(frame));
            } catch (IOException e) {
                throw new LuaReturnException(new Object[] {LuaNil.INSTANCE, e.getMessage()});
            }
            return defaultOutput[0];
        }));

        io.rawset("lines", wrap(language, "lines", (frame, cn) -> {
            Object arg1 = arg(frame, 1);
            if (isNil(arg1)) {
                return makeLinesIterator(language, getIOFile(defaultInput[0]), false);
            }
            String path = checkString("lines", 1, arg1);
            try {
                LuaIOFile f = LuaIOFile.open(path, "r");
                return makeLinesIterator(language, f, true);
            } catch (IOException e) {
                throw LuaException.runtimeError(null, path + ": " + e.getMessage());
            }
        }));

        io.rawset("input", wrap(language, "input", (frame, cn) -> {
            Object v = arg(frame, 1);
            if (isNil(v)) {
                return defaultInput[0];
            }
            defaultInput[0] = resolveDefaultFile("input", v, "r", fileMetatable);
            return defaultInput[0];
        }));

        io.rawset("output", wrap(language, "output", (frame, cn) -> {
            Object v = arg(frame, 1);
            if (isNil(v)) {
                return defaultOutput[0];
            }
            defaultOutput[0] = resolveDefaultFile("output", v, "w", fileMetatable);
            return defaultOutput[0];
        }));

        io.rawset("type", wrap(language, "type", (frame, cn) -> {
            Object v = arg(frame, 1);
            if (v instanceof LuaTable t) {
                LuaIOFile f = getIOFile(t);
                if (f != null) {
                    return f.isClosed() ? "closed file" : "file";
                }
            }
            return LuaNil.INSTANCE;
        }));

        io.rawset("tmpfile", wrap(language, "tmpfile", (frame, cn) -> {
            try {
                return newFileHandle(LuaIOFile.createTempFile(), fileMetatable);
            } catch (IOException e) {
                throw new LuaReturnException(new Object[] {LuaNil.INSTANCE, e.getMessage()});
            }
        }));

        io.rawset("flush", wrap(language, "flush", (frame, cn) -> {
            try {
                getIOFile(defaultOutput[0]).flush();
            } catch (IOException e) {
                throw LuaException.runtimeError(null, e.getMessage());
            }
            return defaultOutput[0];
        }));
    }

    private static void installFileMethods(TruffleLanguage<?> language, LuaTable fileMethods) {
        fileMethods.rawset("read", wrap(language, "read", (frame, cn) -> {
            LuaIOFile f = checkFileHandle("read", 1, arg(frame, 1));
            Object[] all = allArgs(frame);
            Object[] formats = java.util.Arrays.copyOfRange(all, 1, all.length);
            throw new LuaReturnException(readFormats(f, formats));
        }));

        fileMethods.rawset("write", wrap(language, "write", (frame, cn) -> {
            Object selfArg = arg(frame, 1);
            LuaIOFile f = checkFileHandle("write", 1, selfArg);
            Object[] all = allArgs(frame);
            Object[] values = java.util.Arrays.copyOfRange(all, 1, all.length);
            try {
                writeValues("write", f, values);
            } catch (IOException e) {
                throw new LuaReturnException(new Object[] {LuaNil.INSTANCE, e.getMessage()});
            }
            return selfArg;
        }));

        fileMethods.rawset("close", wrap(language, "close", (frame, cn) -> {
            LuaIOFile f = checkFileHandle("close", 1, arg(frame, 1));
            return closeFile(f);
        }));

        fileMethods.rawset("lines", wrap(language, "lines", (frame, cn) -> {
            LuaIOFile f = checkFileHandle("lines", 1, arg(frame, 1));
            return makeLinesIterator(language, f, false);
        }));

        fileMethods.rawset("seek", wrap(language, "seek", (frame, cn) -> {
            LuaIOFile f = checkFileHandle("seek", 1, arg(frame, 1));
            String whence = optString(arg(frame, 2), "cur");
            long offset = (long) optNumber(arg(frame, 3), 0);
            try {
                return (double) f.seek(whence, offset);
            } catch (IOException e) {
                throw new LuaReturnException(new Object[] {LuaNil.INSTANCE, e.getMessage()});
            }
        }));

        fileMethods.rawset("flush", wrap(language, "flush", (frame, cn) -> {
            Object selfArg = arg(frame, 1);
            LuaIOFile f = checkFileHandle("flush", 1, selfArg);
            try {
                f.flush();
            } catch (IOException e) {
                throw new LuaReturnException(new Object[] {LuaNil.INSTANCE, e.getMessage()});
            }
            return selfArg;
        }));

        fileMethods.rawset("setvbuf", wrap(language, "setvbuf", (frame, cn) -> {
            // Buffering mode is a no-op here: every write already goes straight to the underlying
            // RandomAccessFile/stream with no intermediate Lua-level buffer to configure.
            checkFileHandle("setvbuf", 1, arg(frame, 1));
            return Boolean.TRUE;
        }));
    }

    // -------------------------------------------------------------------
    // Shared read/write/handle helpers
    // -------------------------------------------------------------------

    private static Object closeFile(LuaIOFile f) {
        try {
            f.close();
            return Boolean.TRUE;
        } catch (IOException e) {
            throw new LuaReturnException(new Object[] {LuaNil.INSTANCE, e.getMessage()});
        }
    }

    /** Shared {@code io.read}/{@code file:read} implementation (Lua 5.1 &sect;5.7). */
    private static Object[] readFormats(LuaIOFile file, Object[] formats) {
        if (formats.length == 0) {
            formats = new Object[] {"*l"};
        }
        Object[] results = new Object[formats.length];
        try {
            for (int i = 0; i < formats.length; i++) {
                Object fmt = formats[i];
                Object value;
                if (fmt instanceof Double d) {
                    value = readN(file, (int) (double) d);
                } else if (fmt instanceof String s) {
                    String norm = s.startsWith("*") ? s.substring(1) : s;
                    value = switch (norm) {
                        case "n" -> readNumber(file);
                        case "l" -> readLine(file);
                        case "a" -> readAll(file);
                        default -> throw LuaException.runtimeError(null, "invalid format '" + s + "' to 'read'");
                    };
                } else {
                    throw argError("read", i + 1, "string or number", fmt);
                }
                if (value == null) {
                    for (int j = i; j < results.length; j++) {
                        results[j] = LuaNil.INSTANCE;
                    }
                    return results;
                }
                results[i] = value;
            }
        } catch (IOException e) {
            throw LuaException.runtimeError(null, e.getMessage());
        }
        return results;
    }

    private static void writeValues(String fname, LuaIOFile file, Object[] values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            Object v = values[i];
            String s;
            if (v instanceof String str) {
                s = str;
            } else if (v instanceof Double) {
                s = LuaTypeUtils.tostring(v);
            } else {
                throw argError(fname, i + 1, "string", v);
            }
            file.write(s);
        }
    }

    private static String readLine(LuaIOFile file) throws IOException {
        int c = file.nextByte();
        if (c == -1) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        while (c != -1 && c != '\n') {
            sb.append((char) c);
            c = file.nextByte();
        }
        return sb.toString();
    }

    private static String readAll(LuaIOFile file) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = file.nextByte()) != -1) {
            sb.append((char) c);
        }
        return sb.toString();
    }

    private static String readN(LuaIOFile file, int n) throws IOException {
        if (n <= 0) {
            int c = file.peekByte();
            return c == -1 ? null : "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int c = file.nextByte();
            if (c == -1) {
                break;
            }
            sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * {@code "*n"}: skips leading whitespace, then scans a Lua numeral (optional sign, optional
     * {@code 0x}/{@code 0X} hex prefix, digits, and &mdash; for decimal numerals only &mdash; an
     * optional fractional part and exponent), returning {@code null} if no valid numeral was
     * present. Unlike PUC-Rio's reference implementation, a failed scan does not push every
     * consumed character back onto the stream; this is a reasonable approximation for straight-
     * line reads and only matters if a program deliberately probes {@code read("*n")} against
     * non-numeric input and then tries to recover the raw bytes it consumed.
     */
    private static Double readNumber(LuaIOFile file) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = file.peekByte()) != -1 && Character.isWhitespace(c)) {
            file.nextByte();
        }
        c = file.peekByte();
        if (c == '+' || c == '-') {
            sb.append((char) c);
            file.nextByte();
            c = file.peekByte();
        }
        boolean hex = false;
        if (c == '0') {
            sb.append('0');
            file.nextByte();
            int c2 = file.peekByte();
            if (c2 == 'x' || c2 == 'X') {
                sb.append((char) c2);
                file.nextByte();
                hex = true;
            }
        }
        String digitChars = hex ? "0123456789abcdefABCDEF" : "0123456789";
        while ((c = file.peekByte()) != -1 && digitChars.indexOf(c) >= 0) {
            sb.append((char) c);
            file.nextByte();
        }
        if (!hex) {
            if (file.peekByte() == '.') {
                sb.append('.');
                file.nextByte();
                while ((c = file.peekByte()) != -1 && Character.isDigit(c)) {
                    sb.append((char) c);
                    file.nextByte();
                }
            }
            c = file.peekByte();
            if (c == 'e' || c == 'E') {
                sb.append((char) c);
                file.nextByte();
                c = file.peekByte();
                if (c == '+' || c == '-') {
                    sb.append((char) c);
                    file.nextByte();
                }
                while ((c = file.peekByte()) != -1 && Character.isDigit(c)) {
                    sb.append((char) c);
                    file.nextByte();
                }
            }
        }
        String s = sb.toString();
        if (s.isEmpty() || s.equals("+") || s.equals("-") || s.equals("0x") || s.equals("0X")
                || s.equals("+0x") || s.equals("-0x")) {
            return null;
        }
        try {
            if (hex) {
                boolean negative = s.startsWith("-");
                String withoutSign = negative || s.startsWith("+") ? s.substring(1) : s;
                String hexDigits = withoutSign.substring(2); // strip "0x"/"0X"
                long parsed = Long.parseLong(hexDigits, 16);
                return negative ? -(double) parsed : (double) parsed;
            }
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LuaFunction makeLinesIterator(TruffleLanguage<?> language, LuaIOFile file, boolean closeOnEnd) {
        return wrap(language, "lines_iterator", (frame, cn) -> {
            String line;
            try {
                line = readLine(file);
            } catch (IOException e) {
                throw LuaException.runtimeError(null, e.getMessage());
            }
            if (line == null) {
                if (closeOnEnd) {
                    try {
                        file.close();
                    } catch (IOException ignored) {
                        // Nothing sensible to do with a failure to close at end-of-iteration.
                    }
                }
                return LuaNil.INSTANCE;
            }
            return line;
        });
    }

    /** Shared {@code io.input}/{@code io.output} argument resolution: a string opens a new file
     * (in {@code openMode}), an existing file handle is adopted directly. */
    private static LuaTable resolveDefaultFile(String fname, Object v, String openMode, LuaTable fileMetatable) {
        if (v instanceof String path) {
            try {
                return newFileHandle(LuaIOFile.open(path, openMode), fileMetatable);
            } catch (IOException e) {
                throw LuaException.runtimeError(null, path + ": " + e.getMessage());
            }
        }
        if (v instanceof LuaTable t && getIOFile(t) != null) {
            return t;
        }
        throw argError(fname, 1, "string or file", v);
    }

    private static LuaTable newFileHandle(LuaIOFile file, LuaTable metatable) {
        LuaTable t = new LuaTable();
        t.rawset(FILE_KEY, file);
        t.setMetatable(metatable);
        return t;
    }

    private static LuaIOFile getIOFile(LuaTable t) {
        if (t == null) {
            return null;
        }
        Object raw = t.rawget(FILE_KEY);
        return raw instanceof LuaIOFile f ? f : null;
    }

    private static LuaIOFile checkFileHandle(String fname, int argN, Object v) {
        if (v instanceof LuaTable t) {
            LuaIOFile f = getIOFile(t);
            if (f != null) {
                return f;
            }
        }
        throw argError(fname, argN, "file", v);
    }
}

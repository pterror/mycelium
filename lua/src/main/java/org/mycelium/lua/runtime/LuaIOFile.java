package org.mycelium.lua.runtime;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Backing state for a Lua 5.1 &sect;5.7 file handle.
 *
 * <p>Lua's io library has no separate "userdata" representation in this implementation; instead,
 * a file handle is a {@link LuaTable} (see {@code org.mycelium.lua.stdlib.LuaIoLib}) whose
 * metatable's {@code __index} points at a shared methods table, exactly like the shared string
 * metatable ({@link LuaStringMetatable}) does for {@code ("s"):upper()}. This class holds the
 * actual Java I/O resources that a file handle's table wraps.
 *
 * <p>A seekable, on-disk file (opened via {@code io.open}/{@code io.tmpfile}) is backed by a
 * {@link RandomAccessFile}, which supports both reading and writing plus {@code seek}. The three
 * predefined streams ({@code io.stdin}/{@code io.stdout}/{@code io.stderr}) are backed directly by
 * the embedder-supplied {@link InputStream}/{@link OutputStream} (via {@link
 * com.oracle.truffle.api.TruffleLanguage.Env#in()}/{@code out()}/{@code err()}) and are not
 * seekable; closing one of these marks the handle closed without touching the underlying stream,
 * since that stream is owned by the embedder, not this handle.
 *
 * <p>Bytes are mapped 1:1 to {@code char}s using ISO-8859-1 so that every byte value (0-255)
 * round-trips exactly through a Lua string, matching Lua's usual treatment of strings as byte
 * arrays rather than as Unicode text.
 */
public final class LuaIOFile {

    /**
     * Private sentinel key under which a file handle table (see {@code
     * org.mycelium.lua.stdlib.LuaIoLib}) stores its {@link LuaIOFile}. Lives here (rather than in
     * {@code LuaIoLib}) so that {@link LuaTypeUtils#typeName} &mdash; in this same package &mdash;
     * can recognize a file-handle table and report it as {@code "userdata"} rather than {@code
     * "table"}, matching real Lua 5.1 (&sect;5.7: file handles are userdata).
     */
    public static final Object FILE_KEY = new Object();

    private final RandomAccessFile raf;
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final boolean standardStream;
    private final boolean readable;
    private final boolean writable;
    private final String mode;
    private boolean closed;

    /** A single byte of lookahead, used by {@link #peekByte()}/{@link #nextByte()} so that
     * {@code read("*n")}'s number scanner and {@code read(0)}'s EOF check can look at the next
     * byte without permanently consuming it. {@code -2} means "nothing buffered"; {@code -1} is a
     * legitimate cached EOF result. */
    private int pushback = -2;

    private LuaIOFile(
            RandomAccessFile raf,
            InputStream inputStream,
            OutputStream outputStream,
            boolean standardStream,
            boolean readable,
            boolean writable,
            String mode) {
        this.raf = raf;
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.standardStream = standardStream;
        this.readable = readable;
        this.writable = writable;
        this.mode = mode;
    }

    public static LuaIOFile forInputStream(InputStream in) {
        return new LuaIOFile(null, in, null, true, true, false, "r");
    }

    public static LuaIOFile forOutputStream(OutputStream out) {
        return new LuaIOFile(null, null, out, true, false, true, "w");
    }

    /**
     * Opens {@code path} per Lua's {@code io.open} mode strings: {@code "r"}/{@code "r+"}
     * (read/update, file must already exist), {@code "w"}/{@code "w+"} (write/update, truncates or
     * creates), {@code "a"}/{@code "a+"} (append/update, creates if missing, initial position at
     * end of file). A trailing {@code "b"} (binary) is accepted and ignored &mdash; every mode here
     * is already binary-safe.
     */
    public static LuaIOFile open(String path, String mode) throws IOException {
        String m = mode.replace("b", "");
        boolean append = m.startsWith("a");
        boolean write = m.startsWith("w");
        boolean read = m.startsWith("r");
        boolean plus = m.endsWith("+");
        if (!append && !write && !read) {
            throw new IOException("invalid mode '" + mode + "'");
        }
        File file = new File(path);
        if (read && !write && !append && !file.exists()) {
            // "r"/"r+" both require the file to already exist; "w"/"a" variants may create it.
            throw new FileNotFoundException(path + ": No such file or directory");
        }
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        if (write) {
            raf.setLength(0);
        }
        if (append) {
            raf.seek(raf.length());
        } else if (!plus || write) {
            raf.seek(0);
        }
        boolean readable = read || plus;
        // Writes in "a"/"a+" mode are only approximated: real POSIX append semantics force every
        // write to the current end of file regardless of the handle's seek position, which plain
        // RandomAccessFile cannot express directly. Good enough for straightforward sequential use.
        boolean writable = write || append || plus;
        return new LuaIOFile(raf, null, null, false, readable, writable, mode);
    }

    /** {@code io.tmpfile()}: a fresh, empty temporary file opened for update ({@code "w+"}-like), deleted on JVM exit. */
    public static LuaIOFile createTempFile() throws IOException {
        File file = File.createTempFile("lua", ".tmp");
        file.deleteOnExit();
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        return new LuaIOFile(raf, null, null, false, true, true, "w+");
    }

    public boolean isClosed() {
        return closed;
    }

    public String getMode() {
        return mode;
    }

    public boolean isSeekable() {
        return raf != null;
    }

    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (standardStream) {
            // Standard streams are owned by the embedder (TruffleLanguage.Env), not this handle.
            return;
        }
        if (raf != null) {
            raf.close();
        }
        if (inputStream != null) {
            inputStream.close();
        }
        if (outputStream != null) {
            outputStream.close();
        }
    }

    public void flush() throws IOException {
        requireOpen();
        if (outputStream != null) {
            outputStream.flush();
        }
        // RandomAccessFile writes go straight through to the OS; nothing to flush.
    }

    public long seek(String whence, long offset) throws IOException {
        requireOpen();
        if (raf == null) {
            throw new IOException("cannot seek this file");
        }
        long newPos;
        switch (whence) {
            case "set" -> newPos = offset;
            case "cur" -> newPos = raf.getFilePointer() + offset;
            case "end" -> newPos = raf.length() + offset;
            default -> throw new IOException("invalid option '" + whence + "'");
        }
        if (newPos < 0) {
            throw new IOException("Invalid argument");
        }
        raf.seek(newPos);
        pushback = -2; // discard any pending lookahead byte; it belongs to the old position
        return newPos;
    }

    public void write(String s) throws IOException {
        requireOpen();
        if (!writable) {
            throw new IOException("file not opened for writing");
        }
        byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
        if (raf != null) {
            raf.write(bytes);
        } else if (outputStream != null) {
            outputStream.write(bytes);
        } else {
            throw new IOException("file not opened for writing");
        }
    }

    /** Returns the next byte (0-255) without consuming it, or {@code -1} at EOF. */
    public int peekByte() throws IOException {
        if (pushback == -2) {
            pushback = rawReadByte();
        }
        return pushback;
    }

    /** Returns and consumes the next byte (0-255), or {@code -1} at EOF. */
    public int nextByte() throws IOException {
        if (pushback != -2) {
            int v = pushback;
            pushback = -2;
            return v;
        }
        return rawReadByte();
    }

    private int rawReadByte() throws IOException {
        requireOpen();
        if (!readable) {
            throw new IOException("file not opened for reading");
        }
        if (raf != null) {
            return raf.read();
        }
        if (inputStream != null) {
            return inputStream.read();
        }
        throw new IOException("file not opened for reading");
    }

    private void requireOpen() throws IOException {
        if (closed) {
            throw new IOException("attempt to use a closed file");
        }
    }
}

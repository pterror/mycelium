// Minimal Node.js-compatible `fs` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "fs"` / `"node:fs"`. Backed by `java.nio.file` via
// `Java.type(...)` (GraalJS) for all actual I/O.

import { Readable, Writable } from "stream";

const JFiles = Java.type("java.nio.file.Files");
const JPaths = Java.type("java.nio.file.Paths");
const JPath = Java.type("java.nio.file.Path");
const JStandardOpenOption = Java.type("java.nio.file.StandardOpenOption");
const JStandardCopyOption = Java.type("java.nio.file.StandardCopyOption");
const JBasicFileAttributes = Java.type("java.nio.file.attribute.BasicFileAttributes");
const JFileVisitOption = Java.type("java.nio.file.FileVisitOption");
const JCharset = Java.type("java.nio.charset.StandardCharsets");
const JFile = Java.type("java.io.File");
const JPosixFilePermissions = Java.type("java.nio.file.attribute.PosixFilePermissions");
const JArrayList = Java.type("java.util.ArrayList");
const JCollectors = Java.type("java.util.stream.Collectors");
const JCharsetClass = Java.type("java.nio.charset.Charset");
const JRandomAccessFile = Java.type("java.io.RandomAccessFile");
const JByteArrayType = Java.type("byte[]");

// Runs `fn` on a microtask, same convention as stream.mjs's own `nextTick` — used so
// fd-backed streams' 'open'/'ready'/'close' events fire asynchronously (as in real
// Node) rather than synchronously from the constructor, even though the underlying
// `RandomAccessFile` open/close calls here are themselves synchronous.
function nextTick(fn) {
    Promise.resolve().then(fn);
}

function p(pathLike) {
    return JPaths.get(String(pathLike));
}

function charsetFor(encoding) {
    return JCharsetClass.forName(encoding);
}

export const constants = {
    F_OK: 0,
    R_OK: 4,
    W_OK: 2,
    X_OK: 1,
    O_RDONLY: 0,
    O_WRONLY: 1,
    O_RDWR: 2,
    O_CREAT: 64,
    O_TRUNC: 512,
    O_APPEND: 1024,
    COPYFILE_EXCL: 1,
};

function toBufferLike(bytes) {
    // Real Buffer isn't implemented in Mycelium's guest environment yet; represent binary
    // reads as a plain array of unsigned byte values (indexable like Node's Buffer for
    // simple use), with a toString() that decodes as UTF-8 the way Buffer#toString does
    // by default.
    const arr = [];
    for (let i = 0; i < bytes.length; i++) {
        arr.push(bytes[i] & 0xff);
    }
    arr.toString = function (encoding) {
        return String(new (Java.type("java.lang.String"))(bytes, encoding || "utf8"));
    };
    return arr;
}

function normalizeEncoding(encoding) {
    if (encoding === undefined || encoding === null) return null;
    if (typeof encoding === "string") return encoding;
    if (typeof encoding === "object" && encoding.encoding !== undefined) return encoding.encoding;
    return null;
}

// ---- Sync API ----

export function existsSync(path) {
    try {
        return JFiles.exists(p(path));
    } catch (e) {
        return false;
    }
}

export function readFileSync(path, options) {
    const target = p(path);
    if (!JFiles.exists(target)) {
        throw new Error(`ENOENT: no such file or directory, open '${path}'`);
    }
    const bytes = JFiles.readAllBytes(target);
    const encoding = normalizeEncoding(options);
    if (encoding) {
        return String(new (Java.type("java.lang.String"))(bytes, encoding));
    }
    return toBufferLike(bytes);
}

// Encodes a JS string to a Java byte[] via Charset#encode, which returns a ByteBuffer (a
// host object, unaffected by GraalJS's auto-unboxing of java.lang.String results) rather
// than going through java.lang.String#getBytes — calling that on a `new
// java.lang.String(...)` result doesn't work, because GraalJS automatically unboxes
// host String instances crossing back into JS to plain JS string primitives, which have
// no `.getBytes` method.
function encodeString(str, encoding) {
    const buf = charsetFor(encoding).encode(str);
    const len = buf.remaining();
    const JByteArray = Java.type("byte[]");
    const bytes = new JByteArray(len);
    buf.get(bytes);
    return bytes;
}

function toJavaByteArray(data) {
    if (typeof data === "string") return null; // handled separately by caller
    const JByteArray = Java.type("byte[]");
    const bytes = new JByteArray(data.length);
    for (let i = 0; i < data.length; i++) bytes[i] = data[i];
    return bytes;
}

export function writeFileSync(path, data, options) {
    const target = p(path);
    const encoding = normalizeEncoding(options) || "utf8";
    const bytes = typeof data === "string" ? encodeString(data, encoding) : toJavaByteArray(data);
    JFiles.write(target, bytes);
}

export function appendFileSync(path, data, options) {
    const target = p(path);
    const encoding = normalizeEncoding(options) || "utf8";
    const bytes = typeof data === "string" ? encodeString(data, encoding) : toJavaByteArray(data);
    JFiles.write(target, bytes, JStandardOpenOption.CREATE, JStandardOpenOption.APPEND);
}

export function mkdirSync(path, options) {
    const target = p(path);
    const recursive = options && typeof options === "object" && options.recursive;
    try {
        if (recursive) {
            JFiles.createDirectories(target);
        } else {
            JFiles.createDirectory(target);
        }
    } catch (e) {
        throw new Error(`mkdir failed for '${path}': ${e.message || e}`);
    }
}

// Node's fs.mkdtempSync(prefix) creates a new directory in prefix's parent whose name is
// `prefix` (which itself may embed a path, e.g. path.join(os.tmpdir(), "foo-")) followed
// by random characters, and returns the full path to the created directory —
// java.nio.file.Files.createTempDirectory(parentDir, prefixName) implements exactly that
// (appending its own random suffix), so this just needs to split `prefix` into its parent
// directory and basename first.
export function mkdtempSync(prefix, options) {
    const prefixStr = String(prefix);
    const slashIdx = Math.max(prefixStr.lastIndexOf("/"), prefixStr.lastIndexOf("\\"));
    const dir = slashIdx >= 0 ? prefixStr.slice(0, slashIdx + 1) : "./";
    const base = slashIdx >= 0 ? prefixStr.slice(slashIdx + 1) : prefixStr;
    const parent = p(dir);
    if (!JFiles.exists(parent)) {
        JFiles.createDirectories(parent);
    }
    const created = JFiles.createTempDirectory(parent, base);
    return String(created.toString());
}

export function readdirSync(path, options) {
    const target = p(path);
    const withFileTypes = options && typeof options === "object" && options.withFileTypes;
    const stream = JFiles.list(target);
    try {
        const names = [];
        const it = stream.iterator();
        while (it.hasNext()) {
            const entry = it.next();
            const name = String(entry.getFileName().toString());
            if (withFileTypes) {
                names.push(makeDirent(entry, name));
            } else {
                names.push(name);
            }
        }
        return names;
    } finally {
        stream.close();
    }
}

function makeDirent(fullPath, name) {
    const isDir = JFiles.isDirectory(fullPath);
    const isSymlink = JFiles.isSymbolicLink(fullPath);
    const isFileFlag = JFiles.isRegularFile(fullPath);
    return {
        name,
        isFile: () => isFileFlag,
        isDirectory: () => isDir,
        isSymbolicLink: () => isSymlink,
        isBlockDevice: () => false,
        isCharacterDevice: () => false,
        isFIFO: () => false,
        isSocket: () => false,
    };
}

function makeStats(target, follow) {
    const linkOpts = follow
        ? []
        : [Java.type("java.nio.file.LinkOption").NOFOLLOW_LINKS];
    const attrs = JFiles.readAttributes(target, JBasicFileAttributes.class, ...linkOpts);
    const isDir = attrs.isDirectory();
    const isFileFlag = attrs.isRegularFile();
    const isSymlink = attrs.isSymbolicLink();
    return {
        isFile: () => isFileFlag,
        isDirectory: () => isDir,
        isSymbolicLink: () => isSymlink,
        isBlockDevice: () => false,
        isCharacterDevice: () => false,
        isFIFO: () => false,
        isSocket: () => false,
        size: Number(attrs.size()),
        mtime: new Date(Number(attrs.lastModifiedTime().toMillis())),
        mtimeMs: Number(attrs.lastModifiedTime().toMillis()),
        atime: new Date(Number(attrs.lastAccessTime().toMillis())),
        atimeMs: Number(attrs.lastAccessTime().toMillis()),
        ctime: new Date(Number(attrs.creationTime().toMillis())),
        ctimeMs: Number(attrs.creationTime().toMillis()),
        birthtime: new Date(Number(attrs.creationTime().toMillis())),
        birthtimeMs: Number(attrs.creationTime().toMillis()),
    };
}

export function statSync(path) {
    return makeStats(p(path), true);
}

export function lstatSync(path) {
    return makeStats(p(path), false);
}

export function unlinkSync(path) {
    JFiles.delete(p(path));
}

export function rmdirSync(path) {
    JFiles.delete(p(path));
}

export function rmSync(path, options) {
    const target = p(path);
    const recursive = options && typeof options === "object" && options.recursive;
    const force = options && typeof options === "object" && options.force;
    try {
        if (recursive && JFiles.isDirectory(target)) {
            const walk = JFiles.walk(target);
            try {
                const list = walk.sorted(Java.type("java.util.Comparator").reverseOrder()).collect(JCollectors.toList());
                for (let i = 0; i < list.size(); i++) {
                    JFiles.delete(list.get(i));
                }
            } finally {
                walk.close();
            }
        } else {
            JFiles.delete(target);
        }
    } catch (e) {
        if (!force) throw e;
    }
}

export function renameSync(oldPath, newPath) {
    JFiles.move(p(oldPath), p(newPath), JStandardCopyOption.REPLACE_EXISTING);
}

export function copyFileSync(src, dest) {
    JFiles.copy(p(src), p(dest), JStandardCopyOption.REPLACE_EXISTING);
}

export function accessSync(path, mode) {
    const target = p(path);
    if (!JFiles.exists(target)) {
        throw new Error(`ENOENT: no such file or directory, access '${path}'`);
    }
    const m = mode === undefined ? constants.F_OK : mode;
    const JAccessMode = Java.type("java.nio.file.AccessMode");
    if (m & constants.R_OK) {
        if (!JFiles.isReadable(target)) throw new Error(`EACCES: permission denied, access '${path}'`);
    }
    if (m & constants.W_OK) {
        if (!JFiles.isWritable(target)) throw new Error(`EACCES: permission denied, access '${path}'`);
    }
    if (m & constants.X_OK) {
        if (!JFiles.isExecutable(target)) throw new Error(`EACCES: permission denied, access '${path}'`);
    }
}

export function readlinkSync(path) {
    return String(JFiles.readSymbolicLink(p(path)).toString());
}

export function symlinkSync(target, path) {
    JFiles.createSymbolicLink(p(path), p(target));
}

export function chmodSync(path, mode) {
    try {
        const modeStr = typeof mode === "number" ? mode.toString(8) : String(mode);
        const perms = octalToPosixPermString(modeStr);
        JFiles.setPosixFilePermissions(p(path), JPosixFilePermissions.fromString(perms));
    } catch (e) {
        // Not all filesystems (e.g. non-POSIX) support POSIX permissions; ignore.
    }
}

function octalToPosixPermString(octal) {
    const digits = octal.slice(-3).padStart(3, "0");
    const table = [
        "---", "--x", "-w-", "-wx", "r--", "r-x", "rw-", "rwx",
    ];
    return digits.split("").map((d) => table[parseInt(d, 8)]).join("");
}

export function realpathSync(path) {
    return String(p(path).toRealPath().toString());
}

// ---- fd-based API ----
//
// Backed by `java.io.RandomAccessFile`, which (unlike `FileInputStream`/
// `FileOutputStream`) supports both reading and writing plus arbitrary `seek`, so a
// single table entry can serve read, write, and positional I/O uniformly — matching
// the fact that a Node fd isn't inherently read-only or write-only either (it's
// `open()`'s `flags` argument that determines what's actually allowed). `createReadStream`
// / `createWriteStream` below (when not handed an existing `fd` via `options.fd`) open
// their own fd through this same table, so `fs.close(stream.fd, ...)` and similar
// interop with plain fd-based code works as expected.

let __nextFd = 3; // 0/1/2 are conventionally stdin/stdout/stderr
const __fdTable = new Map();

function flagsToRafMode(flags) {
    const f = String(flags === undefined ? "r" : flags);
    return f.startsWith("r") && !f.includes("+") ? "r" : "rw";
}

export function openSync(path, flags, mode) {
    flags = flags === undefined ? "r" : flags;
    const target = p(path);
    const flagStr = String(flags);
    const exclusive = flagStr.includes("x");
    const readOnly = flagStr === "r";
    const truncate = flagStr[0] === "w";
    const append = flagStr[0] === "a";
    const exists = JFiles.exists(target);

    if (exclusive && exists) {
        throw new Error(`EEXIST: file already exists, open '${path}'`);
    }
    if (readOnly && !exists) {
        throw new Error(`ENOENT: no such file or directory, open '${path}'`);
    }
    if (!exists && (truncate || append || flagStr.includes("+"))) {
        if (target.getParent() !== null && !JFiles.exists(target.getParent())) {
            throw new Error(`ENOENT: no such file or directory, open '${path}'`);
        }
        JFiles.createFile(target);
    }

    const raf = new JRandomAccessFile(target.toFile(), flagsToRafMode(flagStr));
    if (truncate) raf.setLength(0);
    if (append) raf.seek(raf.length());

    const fd = __nextFd++;
    __fdTable.set(fd, { raf, flags: flagStr, path: String(path) });
    return fd;
}

export function closeSync(fd) {
    const entry = __fdTable.get(fd);
    if (!entry) throw new Error("EBADF: bad file descriptor, close");
    entry.raf.close();
    __fdTable.delete(fd);
}

function bytesToBuffer(dest, offset, jBytes, n) {
    for (let i = 0; i < n; i++) dest[offset + i] = jBytes[i] & 0xff;
}

export function readSync(fd, buffer, offset, length, position) {
    const entry = __fdTable.get(fd);
    if (!entry) throw new Error("EBADF: bad file descriptor, read");
    offset = offset || 0;
    length = length === undefined || length === null ? buffer.length - offset : length;
    if (position !== undefined && position !== null) entry.raf.seek(position);
    const tmp = new JByteArrayType(length);
    const n = entry.raf.read(tmp, 0, length);
    if (n > 0) bytesToBuffer(buffer, offset, tmp, n);
    return n < 0 ? 0 : n;
}

export function writeSync(fd, buffer, offset, length, position) {
    const entry = __fdTable.get(fd);
    if (!entry) throw new Error("EBADF: bad file descriptor, write");
    let bytes;
    if (typeof buffer === "string") {
        // Node overload: write(fd, string, position, encoding, callback)
        const encoding = typeof length === "string" ? length : "utf8";
        bytes = encodeString(buffer, encoding);
        position = offset;
    } else {
        offset = offset || 0;
        length = length === undefined || length === null ? buffer.length - offset : length;
        bytes = new JByteArrayType(length);
        for (let i = 0; i < length; i++) bytes[i] = buffer[offset + i];
    }
    if (position !== undefined && position !== null) entry.raf.seek(position);
    entry.raf.write(bytes, 0, bytes.length);
    return bytes.length;
}

export const open = wrapAsyncFd(openSync, (fd) => [null, fd]);
export const close = wrapAsyncFd(closeSync, () => [null]);
export const read = wrapAsyncFd(readSync, (n, buffer) => [null, n, buffer], true);
export const write = wrapAsyncFd(writeSync, (n, buffer) => [null, n, buffer], true);

// Like `wrapAsync` below, but calls back with the (possibly multi-value) Node
// convention for fd operations (`(err, result...)`) rather than the single-value
// `(err, result)` used by the rest of this module's async wrappers, and — for
// `read`/`write` — needs the original `buffer` argument back in the callback alongside
// the sync function's plain numeric return value.
function wrapAsyncFd(syncFn, formatResult, includeBuffer) {
    return function (...args) {
        let callback;
        if (typeof args[args.length - 1] === "function") {
            callback = args.pop();
        }
        const bufferArg = includeBuffer ? args[1] : undefined;
        Promise.resolve().then(() => {
            try {
                const result = syncFn(...args);
                if (callback) callback(...formatResult(result, bufferArg));
            } catch (e) {
                if (callback) callback(e);
            }
        });
    };
}

// ---- Streams (createReadStream / createWriteStream) ----

function bufferLikeSlice(jBytes, n, encoding) {
    if (encoding) {
        return String(new (Java.type("java.lang.String"))(jBytes, 0, n, encoding));
    }
    const arr = [];
    for (let i = 0; i < n; i++) arr.push(jBytes[i] & 0xff);
    arr.toString = function (enc) {
        return String(new (Java.type("java.lang.String"))(jBytes, 0, n, enc || "utf8"));
    };
    return arr;
}

export class ReadStream extends Readable {
    constructor(path, options) {
        options = options || {};
        super(options);
        this.path = path;
        this.fd = null;
        this.bytesRead = 0;
        this.pending = true;
        this._autoClose = options.autoClose !== false;
        this._highWaterMark = options.highWaterMark || 64 * 1024;
        this._start = options.start;
        this._end = options.end !== undefined ? options.end : Infinity;
        this._pos = this._start || 0;
        this._encoding = normalizeEncoding(options);
        this._raf = null;
        this._ownsFd = true;
        this._closed = false;
        this._pendingRead = false;

        this.on("end", () => {
            if (this._autoClose) this.close();
        });

        if (options.fd !== undefined && options.fd !== null) {
            const entry = __fdTable.get(options.fd);
            if (!entry) throw new Error("EBADF: bad file descriptor, open");
            this.fd = options.fd;
            this._raf = entry.raf;
            this._ownsFd = false;
            if (this._start !== undefined) this._raf.seek(this._start);
            nextTick(() => this._onOpen());
        } else {
            nextTick(() => {
                try {
                    this.fd = openSync(path, options.flags || "r", options.mode);
                    this._raf = __fdTable.get(this.fd).raf;
                    if (this._start !== undefined) this._raf.seek(this._start);
                    this._onOpen();
                } catch (e) {
                    this.emit("error", e);
                }
            });
        }
    }

    _onOpen() {
        this.pending = false;
        this.emit("open", this.fd);
        this.emit("ready");
        if (this._pendingRead) {
            this._pendingRead = false;
            this._doRead(this._highWaterMark);
        }
    }

    _read(size) {
        if (!this._raf) {
            this._pendingRead = true;
            return;
        }
        this._doRead(size);
    }

    _doRead(size) {
        try {
            const want = size && size > 0 ? size : this._highWaterMark;
            const remaining = this._end === Infinity ? want : Math.min(want, this._end - this._pos + 1);
            if (remaining <= 0) {
                this.push(null);
                return;
            }
            const tmp = new JByteArrayType(remaining);
            const n = this._raf.read(tmp, 0, remaining);
            if (n === -1) {
                this.push(null);
                return;
            }
            this._pos += n;
            this.bytesRead += n;
            this.push(bufferLikeSlice(tmp, n, this._encoding));
        } catch (e) {
            this.emit("error", e);
        }
    }

    close(callback) {
        if (this._closed) {
            if (callback) nextTick(callback);
            return;
        }
        this._closed = true;
        try {
            if (this._ownsFd && this._raf) this._raf.close();
            if (this._ownsFd && this.fd !== null) __fdTable.delete(this.fd);
        } catch (e) {
            // best-effort close
        }
        nextTick(() => {
            this.emit("close");
            if (callback) callback();
        });
    }

    destroy(err) {
        this.close();
        if (err) nextTick(() => this.emit("error", err));
        return this;
    }
}

export class WriteStream extends Writable {
    constructor(path, options) {
        options = options || {};
        super(options);
        this.path = path;
        this.fd = null;
        this.bytesWritten = 0;
        this.pending = true;
        this._autoClose = options.autoClose !== false;
        this._encoding = normalizeEncoding(options) || "utf8";
        this._raf = null;
        this._ownsFd = true;
        this._closed = false;
        this._pendingWrites = [];

        this.on("finish", () => {
            if (this._autoClose) this.close();
        });

        if (options.fd !== undefined && options.fd !== null) {
            const entry = __fdTable.get(options.fd);
            if (!entry) throw new Error("EBADF: bad file descriptor, open");
            this.fd = options.fd;
            this._raf = entry.raf;
            this._ownsFd = false;
            if (options.start !== undefined) this._raf.seek(options.start);
            nextTick(() => this._onOpen());
        } else {
            nextTick(() => {
                try {
                    this.fd = openSync(path, options.flags || "w", options.mode);
                    this._raf = __fdTable.get(this.fd).raf;
                    if (options.start !== undefined) this._raf.seek(options.start);
                    this._onOpen();
                } catch (e) {
                    this.emit("error", e);
                }
            });
        }
    }

    _onOpen() {
        this.pending = false;
        this.emit("open", this.fd);
        this.emit("ready");
        const pending = this._pendingWrites;
        this._pendingWrites = [];
        for (const [chunk, encoding, callback] of pending) {
            this._doWrite(chunk, encoding, callback);
        }
    }

    _write(chunk, encoding, callback) {
        if (!this._raf) {
            this._pendingWrites.push([chunk, encoding, callback]);
            return;
        }
        this._doWrite(chunk, encoding, callback);
    }

    _doWrite(chunk, encoding, callback) {
        try {
            const bytes =
                typeof chunk === "string" ? encodeString(chunk, encoding || this._encoding) : toJavaByteArray(chunk);
            this._raf.write(bytes, 0, bytes.length);
            this.bytesWritten += bytes.length;
            callback();
        } catch (e) {
            callback(e);
        }
    }

    close(callback) {
        if (this._closed) {
            if (callback) nextTick(callback);
            return;
        }
        this._closed = true;
        try {
            if (this._ownsFd && this._raf) this._raf.close();
            if (this._ownsFd && this.fd !== null) __fdTable.delete(this.fd);
        } catch (e) {
            // best-effort close
        }
        nextTick(() => {
            this.emit("close");
            if (callback) callback();
        });
    }

    destroy(err) {
        this.close();
        if (err) nextTick(() => this.emit("error", err));
        return this;
    }
}

export function createReadStream(path, options) {
    return new ReadStream(path, options);
}

export function createWriteStream(path, options) {
    return new WriteStream(path, options);
}

// ---- Async API (wraps sync in Promises) ----

function wrapAsync(syncFn) {
    return function (...args) {
        let callback;
        if (typeof args[args.length - 1] === "function") {
            callback = args.pop();
        }
        const promise = new Promise((resolve, reject) => {
            try {
                resolve(syncFn(...args));
            } catch (e) {
                reject(e);
            }
        });
        if (callback) {
            promise.then(
                (result) => callback(null, result),
                (err) => callback(err)
            );
            return undefined;
        }
        return promise;
    };
}

export const readFile = wrapAsync(readFileSync);
export const writeFile = wrapAsync(writeFileSync);
export const appendFile = wrapAsync(appendFileSync);
export const mkdir = wrapAsync(mkdirSync);
export const mkdtemp = wrapAsync(mkdtempSync);
export const readdir = wrapAsync(readdirSync);
export const stat = wrapAsync(statSync);
export const lstat = wrapAsync(lstatSync);
export const unlink = wrapAsync(unlinkSync);
export const rmdir = wrapAsync(rmdirSync);
export const rm = wrapAsync(rmSync);
export const rename = wrapAsync(renameSync);
export const copyFile = wrapAsync(copyFileSync);
export const access = wrapAsync(accessSync);
export const readlink = wrapAsync(readlinkSync);
export const symlink = wrapAsync(symlinkSync);
export const chmod = wrapAsync(chmodSync);
export const realpath = wrapAsync(realpathSync);
export const exists = function (path, callback) {
    const result = existsSync(path);
    if (typeof callback === "function") {
        callback(result);
        return undefined;
    }
    return Promise.resolve(result);
};

// `fs.promises.open`'s FileHandle: a thin object wrapper around an fd from the same
// `__fdTable` used by the plain fd-based API and by createReadStream/createWriteStream
// (`options.fd`), so a FileHandle's fd can be handed to any of those interchangeably.
export class FileHandle {
    constructor(fd) {
        this.fd = fd;
    }

    read(buffer, offset, length, position) {
        return Promise.resolve().then(() => {
            const n = readSync(this.fd, buffer, offset, length, position);
            return { bytesRead: n, buffer };
        });
    }

    write(buffer, offset, length, position) {
        return Promise.resolve().then(() => {
            const n = writeSync(this.fd, buffer, offset, length, position);
            return { bytesWritten: n, buffer };
        });
    }

    readFile(options) {
        return Promise.resolve().then(() => {
            const entry = __fdTable.get(this.fd);
            if (!entry) throw new Error("EBADF: bad file descriptor, read");
            return readFileSync(entry.path, options);
        });
    }

    close() {
        return Promise.resolve().then(() => closeSync(this.fd));
    }
}

function fileHandleOpen(path, flags, mode) {
    return Promise.resolve().then(() => new FileHandle(openSync(path, flags, mode)));
}

export const promises = {
    readFile: (path, options) => readFile(path, options),
    writeFile: (path, data, options) => writeFile(path, data, options),
    appendFile: (path, data, options) => appendFile(path, data, options),
    mkdir: (path, options) => mkdir(path, options),
    mkdtemp: (prefix, options) => mkdtemp(prefix, options),
    readdir: (path, options) => readdir(path, options),
    stat: (path) => stat(path),
    lstat: (path) => lstat(path),
    unlink: (path) => unlink(path),
    rmdir: (path) => rmdir(path),
    rm: (path, options) => rm(path, options),
    rename: (oldPath, newPath) => rename(oldPath, newPath),
    copyFile: (src, dest) => copyFile(src, dest),
    access: (path, mode) => access(path, mode),
    readlink: (path) => readlink(path),
    symlink: (target, path) => symlink(target, path),
    chmod: (path, mode) => chmod(path, mode),
    realpath: (path) => realpath(path),
    open: (path, flags, mode) => fileHandleOpen(path, flags, mode),
};

export default {
    constants,
    existsSync,
    readFileSync,
    writeFileSync,
    appendFileSync,
    mkdirSync,
    mkdtempSync,
    readdirSync,
    statSync,
    lstatSync,
    unlinkSync,
    rmdirSync,
    rmSync,
    renameSync,
    copyFileSync,
    accessSync,
    readlinkSync,
    symlinkSync,
    chmodSync,
    realpathSync,
    openSync,
    closeSync,
    readSync,
    writeSync,
    open,
    close,
    read,
    write,
    readFile,
    writeFile,
    appendFile,
    mkdir,
    mkdtemp,
    readdir,
    stat,
    lstat,
    unlink,
    rmdir,
    rm,
    rename,
    copyFile,
    access,
    readlink,
    symlink,
    chmod,
    realpath,
    exists,
    createReadStream,
    createWriteStream,
    ReadStream,
    WriteStream,
    FileHandle,
    promises,
};

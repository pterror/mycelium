// Minimal Node.js-compatible `fs` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "fs"` / `"node:fs"`. Backed by `java.nio.file` via
// `Java.type(...)` (GraalJS) for all actual I/O.

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

export const promises = {
    readFile: (path, options) => readFile(path, options),
    writeFile: (path, data, options) => writeFile(path, data, options),
    appendFile: (path, data, options) => appendFile(path, data, options),
    mkdir: (path, options) => mkdir(path, options),
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
};

export default {
    constants,
    existsSync,
    readFileSync,
    writeFileSync,
    appendFileSync,
    mkdirSync,
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
    readFile,
    writeFile,
    appendFile,
    mkdir,
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
    promises,
};

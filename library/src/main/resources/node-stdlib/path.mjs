// Minimal Node.js-compatible `path` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "path"` / `"node:path"`. The top-level exports
// (and `default` export) use POSIX semantics — Mycelium always runs on a POSIX host, same
// as real Node picks POSIX semantics for the top-level `path` module on POSIX hosts. A
// separate, real win32 implementation (`\`-separated, drive letters, UNC-root-aware) is
// exposed as `path.win32` (and via the `path/win32` subpath module, path-win32.mjs) rather
// than reusing the posix implementation as a stub — see the "win32" section below.
// Ported from https://github.com/nodejs/node/blob/main/lib/path.js.

function assertPath(path) {
    if (typeof path !== "string") {
        throw new TypeError(
            `The "path" argument must be of type string. Received ${JSON.stringify(path)}`
        );
    }
}

// Resolves `.`/`..` segments and collapses repeated separators in `path`. When
// `allowAboveRoot` is false, leading `..` segments that would escape the root are dropped
// (used by normalize() on an absolute path); when true they're kept (used by resolve() /
// normalize() on a relative path).
function normalizeStringPosix(path, allowAboveRoot) {
    let res = "";
    let lastSegmentLength = 0;
    let lastSlash = -1;
    let dots = 0;
    let code = 0;
    for (let i = 0; i <= path.length; ++i) {
        if (i < path.length) {
            code = path.charCodeAt(i);
        } else if (code === 47 /* / */) {
            break;
        } else {
            code = 47;
        }
        if (code === 47) {
            if (lastSlash === i - 1 || dots === 1) {
                // NOOP — empty segment or "." segment.
            } else if (lastSlash !== i - 1 && dots === 2) {
                if (
                    res.length < 2 ||
                    lastSegmentLength !== 2 ||
                    res.charCodeAt(res.length - 1) !== 46 ||
                    res.charCodeAt(res.length - 2) !== 46
                ) {
                    if (res.length > 2) {
                        const lastSlashIndex = res.lastIndexOf("/");
                        if (lastSlashIndex !== res.length - 1) {
                            if (lastSlashIndex === -1) {
                                res = "";
                                lastSegmentLength = 0;
                            } else {
                                res = res.slice(0, lastSlashIndex);
                                lastSegmentLength = res.length - 1 - res.lastIndexOf("/");
                            }
                            lastSlash = i;
                            dots = 0;
                            continue;
                        }
                    } else if (res.length === 2 || res.length === 1) {
                        res = "";
                        lastSegmentLength = 0;
                        lastSlash = i;
                        dots = 0;
                        continue;
                    }
                }
                if (allowAboveRoot) {
                    if (res.length > 0) res += "/..";
                    else res = "..";
                    lastSegmentLength = 2;
                }
            } else {
                if (res.length > 0) res += "/" + path.slice(lastSlash + 1, i);
                else res = path.slice(lastSlash + 1, i);
                lastSegmentLength = i - lastSlash - 1;
            }
            lastSlash = i;
            dots = 0;
        } else if (code === 46 /* . */ && dots !== -1) {
            ++dots;
        } else {
            dots = -1;
        }
    }
    return res;
}

function formatExt(ext) {
    return ext ? (ext[0] === "." ? ext : "." + ext) : "";
}

export const sep = "/";
export const delimiter = ":";

export function isAbsolute(path) {
    assertPath(path);
    return path.length > 0 && path.charCodeAt(0) === 47;
}

export function normalize(path) {
    assertPath(path);
    if (path.length === 0) return ".";
    const isAbs = path.charCodeAt(0) === 47;
    const trailingSeparator = path.charCodeAt(path.length - 1) === 47;
    path = normalizeStringPosix(path, !isAbs);
    if (path.length === 0 && !isAbs) path = ".";
    if (path.length > 0 && trailingSeparator) path += "/";
    return isAbs ? "/" + path : path;
}

export function join(...paths) {
    if (paths.length === 0) return ".";
    let joined;
    for (const arg of paths) {
        assertPath(arg);
        if (arg.length > 0) {
            joined = joined === undefined ? arg : joined + "/" + arg;
        }
    }
    if (joined === undefined) return ".";
    return normalize(joined);
}

export function resolve(...paths) {
    let resolvedPath = "";
    let resolvedAbsolute = false;
    for (let i = paths.length - 1; i >= -1 && !resolvedAbsolute; i--) {
        const path = i >= 0 ? paths[i] : cwd();
        assertPath(path);
        if (path.length === 0) continue;
        resolvedPath = path + "/" + resolvedPath;
        resolvedAbsolute = path.charCodeAt(0) === 47;
    }
    resolvedPath = normalizeStringPosix(resolvedPath, !resolvedAbsolute);
    if (resolvedAbsolute) return "/" + resolvedPath;
    return resolvedPath.length > 0 ? resolvedPath : ".";
}

function cwd() {
    // No process.cwd() equivalent is wired up in Mycelium's guest JS environment yet, so
    // relative resolve() calls resolve against "/" — same fallback Node uses when it can't
    // determine a working directory. Callers that need real cwd-relative behavior should
    // pass absolute paths.
    return "/";
}

export function relative(from, to) {
    assertPath(from);
    assertPath(to);
    if (from === to) return "";
    from = resolve(from);
    to = resolve(to);
    if (from === to) return "";

    const fromStart = 1;
    const fromEnd = from.length;
    const fromLen = fromEnd - fromStart;
    const toStart = 1;
    const toLen = to.length - toStart;

    const length = fromLen < toLen ? fromLen : toLen;
    let lastCommonSep = -1;
    let i = 0;
    for (; i < length; i++) {
        const fromCode = from.charCodeAt(fromStart + i);
        if (fromCode !== to.charCodeAt(toStart + i)) break;
        else if (fromCode === 47) lastCommonSep = i;
    }
    if (i === length) {
        if (toLen > length) {
            if (to.charCodeAt(toStart + i) === 47) {
                return to.slice(toStart + i + 1);
            }
            if (i === 0) {
                return to.slice(toStart + i);
            }
        } else if (fromLen > length) {
            if (from.charCodeAt(fromStart + i) === 47) {
                lastCommonSep = i;
            } else if (i === 0) {
                lastCommonSep = 0;
            }
        }
    }

    let out = "";
    for (i = fromStart + lastCommonSep + 1; i <= fromEnd; ++i) {
        if (i === fromEnd || from.charCodeAt(i) === 47) {
            out += out.length === 0 ? ".." : "/..";
        }
    }
    if (out.length > 0) return out + to.slice(toStart + lastCommonSep);
    let toStartIndex = toStart + lastCommonSep;
    if (to.charCodeAt(toStartIndex) === 47) toStartIndex++;
    return to.slice(toStartIndex);
}

export function toNamespacedPath(path) {
    // No-op on POSIX — this exists for Windows UNC-path compatibility only.
    return path;
}

export function dirname(path) {
    assertPath(path);
    if (path.length === 0) return ".";
    const hasRoot = path.charCodeAt(0) === 47;
    let end = -1;
    let matchedSlash = true;
    for (let i = path.length - 1; i >= 1; --i) {
        if (path.charCodeAt(i) === 47) {
            if (!matchedSlash) {
                end = i;
                break;
            }
        } else {
            matchedSlash = false;
        }
    }
    if (end === -1) return hasRoot ? "/" : ".";
    if (hasRoot && end === 1) return "//";
    return path.slice(0, end);
}

export function basename(path, suffix) {
    if (suffix !== undefined) assertPath(suffix);
    assertPath(path);
    let start = 0;
    let end = -1;
    let matchedSlash = true;
    let i;

    if (suffix !== undefined && suffix.length > 0 && suffix.length <= path.length) {
        if (suffix === path) return "";
        let extIdx = suffix.length - 1;
        let firstNonSlashEnd = -1;
        for (i = path.length - 1; i >= 0; --i) {
            const code = path.charCodeAt(i);
            if (code === 47) {
                if (!matchedSlash) {
                    start = i + 1;
                    break;
                }
            } else {
                if (firstNonSlashEnd === -1) {
                    matchedSlash = false;
                    firstNonSlashEnd = i + 1;
                }
                if (extIdx >= 0) {
                    if (code === suffix.charCodeAt(extIdx)) {
                        if (--extIdx === -1) {
                            end = i;
                        }
                    } else {
                        extIdx = -1;
                        end = firstNonSlashEnd;
                    }
                }
            }
        }
        if (start === end) end = firstNonSlashEnd;
        else if (end === -1) end = path.length;
        return path.slice(start, end);
    }

    for (i = path.length - 1; i >= 0; --i) {
        if (path.charCodeAt(i) === 47) {
            if (!matchedSlash) {
                start = i + 1;
                break;
            }
        } else if (end === -1) {
            matchedSlash = false;
            end = i + 1;
        }
    }
    if (end === -1) return "";
    return path.slice(start, end);
}

export function extname(path) {
    assertPath(path);
    let startDot = -1;
    let startPart = 0;
    let end = -1;
    let matchedSlash = true;
    let preDotState = 0;
    for (let i = path.length - 1; i >= 0; --i) {
        const code = path.charCodeAt(i);
        if (code === 47) {
            if (!matchedSlash) {
                startPart = i + 1;
                break;
            }
            continue;
        }
        if (end === -1) {
            matchedSlash = false;
            end = i + 1;
        }
        if (code === 46 /* . */) {
            if (startDot === -1) startDot = i;
            else if (preDotState !== 1) preDotState = 1;
        } else if (startDot !== -1) {
            preDotState = -1;
        }
    }
    if (
        startDot === -1 ||
        end === -1 ||
        preDotState === 0 ||
        (preDotState === 1 && startDot === end - 1 && startDot === startPart + 1)
    ) {
        return "";
    }
    return path.slice(startDot, end);
}

export function format(pathObject) {
    if (pathObject === null || typeof pathObject !== "object") {
        throw new TypeError(
            `The "pathObject" argument must be of type Object. Received ${JSON.stringify(pathObject)}`
        );
    }
    const dir = pathObject.dir || pathObject.root || "";
    const base =
        pathObject.base || `${pathObject.name || ""}${formatExt(pathObject.ext)}`;
    if (!dir) return base;
    return dir === pathObject.root ? `${dir}${base}` : `${dir}${sep}${base}`;
}

export function parse(path) {
    assertPath(path);
    const ret = { root: "", dir: "", base: "", ext: "", name: "" };
    if (path.length === 0) return ret;
    const isAbsolutePath = path.charCodeAt(0) === 47;
    let start;
    if (isAbsolutePath) {
        ret.root = "/";
        start = 1;
    } else {
        start = 0;
    }
    let startDot = -1;
    let startPart = 0;
    let end = -1;
    let matchedSlash = true;
    let i = path.length - 1;
    let preDotState = 0;

    for (; i >= start; --i) {
        const code = path.charCodeAt(i);
        if (code === 47) {
            if (!matchedSlash) {
                startPart = i + 1;
                break;
            }
            continue;
        }
        if (end === -1) {
            matchedSlash = false;
            end = i + 1;
        }
        if (code === 46) {
            if (startDot === -1) startDot = i;
            else if (preDotState !== 1) preDotState = 1;
        } else if (startDot !== -1) {
            preDotState = -1;
        }
    }

    if (
        startDot === -1 ||
        end === -1 ||
        preDotState === 0 ||
        (preDotState === 1 && startDot === end - 1 && startDot === startPart + 1)
    ) {
        if (end !== -1) {
            ret.base = ret.name = path.slice(startPart, end);
        }
    } else {
        ret.base = path.slice(startPart, end);
        ret.name = path.slice(startPart, startDot);
        ret.ext = path.slice(startDot, end);
    }

    if (startPart > 0) ret.dir = path.slice(0, startPart - 1);
    else if (isAbsolutePath) ret.dir = "/";

    return ret;
}

const posixApi = {
    sep,
    delimiter,
    isAbsolute,
    normalize,
    join,
    resolve,
    relative,
    toNamespacedPath,
    dirname,
    basename,
    extname,
    format,
    parse,
};

// --- win32 ---
//
// A real (not stubbed) Windows path implementation — `\`-separated, drive-letter- and
// UNC-root-aware — ported from the win32 half of
// https://github.com/nodejs/node/blob/main/lib/path.js. One deliberate simplification:
// Node's `resolve()` consults `process.cwd()` and per-drive-letter environment variables
// (`process.env['=C:']` etc.) to resolve drive-relative paths like `C:foo` and bare
// relative paths against the *real* current directory; Mycelium has no such concept (see
// `cwd()` above, which posix's own `resolve()` hits the same limitation with), so win32's
// resolve falls back to a fixed `C:\` root the same way posix falls back to `/`.

function isPathSeparatorWin32(code) {
    return code === 92 /* \ */ || code === 47 /* / */;
}

function isWindowsDeviceRoot(code) {
    return (code >= 65 /* A */ && code <= 90) /* Z */ || (code >= 97 /* a */ && code <= 122) /* z */;
}

function normalizeStringWin32(path, allowAboveRoot) {
    let res = "";
    let lastSegmentLength = 0;
    let lastSlash = -1;
    let dots = 0;
    let code = 0;
    for (let i = 0; i <= path.length; ++i) {
        if (i < path.length) {
            code = path.charCodeAt(i);
        } else if (isPathSeparatorWin32(code)) {
            break;
        } else {
            code = 47;
        }
        if (isPathSeparatorWin32(code)) {
            if (lastSlash === i - 1 || dots === 1) {
                // NOOP — empty segment or "." segment.
            } else if (lastSlash !== i - 1 && dots === 2) {
                if (
                    res.length < 2 ||
                    lastSegmentLength !== 2 ||
                    res.charCodeAt(res.length - 1) !== 46 ||
                    res.charCodeAt(res.length - 2) !== 46
                ) {
                    if (res.length > 2) {
                        const lastSlashIndex = res.lastIndexOf("\\");
                        if (lastSlashIndex !== res.length - 1) {
                            if (lastSlashIndex === -1) {
                                res = "";
                                lastSegmentLength = 0;
                            } else {
                                res = res.slice(0, lastSlashIndex);
                                lastSegmentLength = res.length - 1 - res.lastIndexOf("\\");
                            }
                            lastSlash = i;
                            dots = 0;
                            continue;
                        }
                    } else if (res.length === 2 || res.length === 1) {
                        res = "";
                        lastSegmentLength = 0;
                        lastSlash = i;
                        dots = 0;
                        continue;
                    }
                }
                if (allowAboveRoot) {
                    if (res.length > 0) res += "\\..";
                    else res = "..";
                    lastSegmentLength = 2;
                }
            } else {
                if (res.length > 0) res += "\\" + path.slice(lastSlash + 1, i);
                else res = path.slice(lastSlash + 1, i);
                lastSegmentLength = i - lastSlash - 1;
            }
            lastSlash = i;
            dots = 0;
        } else if (code === 46 /* . */ && dots !== -1) {
            ++dots;
        } else {
            dots = -1;
        }
    }
    return res;
}

function win32Cwd() {
    // See the module doc comment above the "win32" section — no real cwd is available.
    return "C:\\";
}

export const win32Sep = "\\";
export const win32Delimiter = ";";

export function win32IsAbsolute(path) {
    assertPath(path);
    const len = path.length;
    if (len === 0) return false;
    const code = path.charCodeAt(0);
    if (isPathSeparatorWin32(code)) return true;
    if (isWindowsDeviceRoot(code) && len > 2 && path.charCodeAt(1) === 58 /* : */) {
        return isPathSeparatorWin32(path.charCodeAt(2));
    }
    return false;
}

export function win32Normalize(path) {
    assertPath(path);
    const len = path.length;
    if (len === 0) return ".";
    let rootEnd = 0;
    let device;
    let isAbsolute = false;
    const code = path.charCodeAt(0);
    if (len === 1) {
        return isPathSeparatorWin32(code) ? "\\" : path;
    }
    if (isPathSeparatorWin32(code)) {
        isAbsolute = true;
        if (isPathSeparatorWin32(path.charCodeAt(1))) {
            let j = 2;
            let last = j;
            while (j < len && !isPathSeparatorWin32(path.charCodeAt(j))) j++;
            if (j < len && j !== last) {
                const firstPart = path.slice(last, j);
                last = j;
                while (j < len && isPathSeparatorWin32(path.charCodeAt(j))) j++;
                if (j < len && j !== last) {
                    last = j;
                    while (j < len && !isPathSeparatorWin32(path.charCodeAt(j))) j++;
                    if (j === len) {
                        return `\\\\${firstPart}\\${path.slice(last)}\\`;
                    }
                    if (j !== last) {
                        device = `\\\\${firstPart}\\${path.slice(last, j)}`;
                        rootEnd = j;
                    }
                }
            }
        } else {
            rootEnd = 1;
        }
    } else if (isWindowsDeviceRoot(code) && path.charCodeAt(1) === 58) {
        device = path.slice(0, 2);
        rootEnd = 2;
        if (len > 2 && isPathSeparatorWin32(path.charCodeAt(2))) {
            isAbsolute = true;
            rootEnd = 3;
        }
    }
    let tail = rootEnd < len ? normalizeStringWin32(path.slice(rootEnd), !isAbsolute) : "";
    if (tail.length === 0 && !isAbsolute) tail = ".";
    if (tail.length > 0 && isPathSeparatorWin32(path.charCodeAt(len - 1))) tail += "\\";
    if (device === undefined) {
        return isAbsolute ? (tail.length > 0 ? `\\${tail}` : "\\") : tail;
    }
    return isAbsolute ? `${device}\\${tail}` : `${device}${tail}`;
}

export function win32Join(...paths) {
    if (paths.length === 0) return ".";
    let joined;
    let firstPart;
    for (const arg of paths) {
        assertPath(arg);
        if (arg.length > 0) {
            if (joined === undefined) {
                joined = firstPart = arg;
            } else {
                joined += `\\${arg}`;
            }
        }
    }
    if (joined === undefined) return ".";
    // Node normalizes `\\?\` and other verbatim/UNC-prefixed first-args specially before
    // running normalize() over the joined result — not implemented here since it only
    // matters for edge cases (extended-length/verbatim paths) no consumer needs.
    return win32Normalize(joined);
}

export function win32Resolve(...paths) {
    let resolvedDevice = "";
    let resolvedTail = "";
    let resolvedAbsolute = false;

    for (let i = paths.length - 1; i >= -1; i--) {
        let path;
        if (i >= 0) {
            path = paths[i];
            assertPath(path);
            if (path.length === 0) continue;
        } else if (resolvedDevice.length === 0) {
            path = win32Cwd();
        } else {
            // Drive-relative resolution against a real per-drive cwd isn't available (see
            // module doc comment) — fall back to that drive's root.
            path = `${resolvedDevice}\\`;
        }

        const len = path.length;
        let rootEnd = 0;
        let device = "";
        let isAbsolute = false;
        const code = path.charCodeAt(0);

        if (len === 1) {
            if (isPathSeparatorWin32(code)) {
                rootEnd = 1;
                isAbsolute = true;
            }
        } else if (isPathSeparatorWin32(code)) {
            isAbsolute = true;
            if (isPathSeparatorWin32(path.charCodeAt(1))) {
                let j = 2;
                let last = j;
                while (j < len && !isPathSeparatorWin32(path.charCodeAt(j))) j++;
                if (j < len && j !== last) {
                    const firstPart = path.slice(last, j);
                    last = j;
                    while (j < len && isPathSeparatorWin32(path.charCodeAt(j))) j++;
                    if (j < len && j !== last) {
                        last = j;
                        while (j < len && !isPathSeparatorWin32(path.charCodeAt(j))) j++;
                        if (j === len || j !== last) {
                            device = `\\\\${firstPart}\\${path.slice(last, j)}`;
                            rootEnd = j;
                        }
                    }
                }
            } else {
                rootEnd = 1;
            }
        } else if (isWindowsDeviceRoot(code) && path.charCodeAt(1) === 58) {
            device = path.slice(0, 2);
            rootEnd = 2;
            if (len > 2 && isPathSeparatorWin32(path.charCodeAt(2))) {
                isAbsolute = true;
                rootEnd = 3;
            }
        }

        if (device.length > 0) {
            if (resolvedDevice.length > 0) {
                if (device.toLowerCase() !== resolvedDevice.toLowerCase()) continue;
            } else {
                resolvedDevice = device;
            }
        }

        if (resolvedAbsolute) {
            if (resolvedDevice.length > 0) break;
        } else {
            resolvedTail = `${path.slice(rootEnd)}\\${resolvedTail}`;
            resolvedAbsolute = isAbsolute;
            if (isAbsolute && resolvedDevice.length > 0) break;
        }
    }

    resolvedTail = normalizeStringWin32(resolvedTail, !resolvedAbsolute);
    return resolvedAbsolute
        ? `${resolvedDevice}\\${resolvedTail}`
        : `${resolvedDevice}${resolvedTail}` || ".";
}

export function win32ToNamespacedPath(path) {
    if (typeof path !== "string" || path.length === 0) return path;
    const resolvedPath = win32Resolve(path);
    if (resolvedPath.length <= 2) return path;
    if (resolvedPath.charCodeAt(0) === 92 /* \ */) {
        if (resolvedPath.charCodeAt(1) === 92 /* \ */) {
            const firstTwo = resolvedPath.slice(0, 2);
            if (firstTwo !== "\\\\") return path;
            const j = resolvedPath.indexOf("\\", 2);
            if (j === -1) return path;
            return `\\\\?\\UNC\\${resolvedPath.slice(2)}`;
        }
    } else if (isWindowsDeviceRoot(resolvedPath.charCodeAt(0)) && resolvedPath.charCodeAt(1) === 58) {
        return `\\\\?\\${resolvedPath}`;
    }
    return path;
}

export function win32Dirname(path) {
    assertPath(path);
    const len = path.length;
    if (len === 0) return ".";
    let rootEnd = -1;
    let end = -1;
    let matchedSlash = true;
    let offset = 0;
    const code = path.charCodeAt(0);

    if (len === 1) return isPathSeparatorWin32(code) ? path : ".";

    if (isPathSeparatorWin32(code)) {
        rootEnd = offset = 1;
        if (isPathSeparatorWin32(path.charCodeAt(1))) {
            let j = 2;
            let last = j;
            while (j < len && !isPathSeparatorWin32(path.charCodeAt(j))) j++;
            if (j < len && j !== last) {
                last = j;
                while (j < len && isPathSeparatorWin32(path.charCodeAt(j))) j++;
                if (j < len && j !== last) {
                    last = j;
                    while (j < len && !isPathSeparatorWin32(path.charCodeAt(j))) j++;
                    if (j === len) return path;
                    if (j !== last) rootEnd = offset = j + 1;
                }
            }
        }
    } else if (isWindowsDeviceRoot(code) && path.charCodeAt(1) === 58) {
        rootEnd = len > 2 && isPathSeparatorWin32(path.charCodeAt(2)) ? 3 : 2;
        offset = rootEnd;
    }

    for (let i = len - 1; i >= offset; --i) {
        if (isPathSeparatorWin32(path.charCodeAt(i))) {
            if (!matchedSlash) {
                end = i;
                break;
            }
        } else {
            matchedSlash = false;
        }
    }

    if (end === -1) {
        if (rootEnd === -1) return ".";
        end = rootEnd;
    }
    return path.slice(0, end);
}

export function win32Basename(path, suffix) {
    if (suffix !== undefined) assertPath(suffix);
    assertPath(path);
    let start = 0;
    let end = -1;
    let matchedSlash = true;
    let i;

    if (path.length >= 2 && isWindowsDeviceRoot(path.charCodeAt(0)) && path.charCodeAt(1) === 58) {
        start = 2;
    }

    if (suffix !== undefined && suffix.length > 0 && suffix.length <= path.length) {
        if (suffix === path) return "";
        let extIdx = suffix.length - 1;
        let firstNonSlashEnd = -1;
        for (i = path.length - 1; i >= start; --i) {
            const code = path.charCodeAt(i);
            if (isPathSeparatorWin32(code)) {
                if (!matchedSlash) {
                    start = i + 1;
                    break;
                }
            } else {
                if (firstNonSlashEnd === -1) {
                    matchedSlash = false;
                    firstNonSlashEnd = i + 1;
                }
                if (extIdx >= 0) {
                    if (code === suffix.charCodeAt(extIdx)) {
                        if (--extIdx === -1) end = i;
                    } else {
                        extIdx = -1;
                        end = firstNonSlashEnd;
                    }
                }
            }
        }
        if (start === end) end = firstNonSlashEnd;
        else if (end === -1) end = path.length;
        return path.slice(start, end);
    }

    for (i = path.length - 1; i >= start; --i) {
        if (isPathSeparatorWin32(path.charCodeAt(i))) {
            if (!matchedSlash) {
                start = i + 1;
                break;
            }
        } else if (end === -1) {
            matchedSlash = false;
            end = i + 1;
        }
    }
    if (end === -1) return "";
    return path.slice(start, end);
}

export function win32Extname(path) {
    assertPath(path);
    let start = 0;
    let startDot = -1;
    let startPart = 0;
    let end = -1;
    let matchedSlash = true;
    let preDotState = 0;

    if (path.length >= 2 && path.charCodeAt(1) === 58 && isWindowsDeviceRoot(path.charCodeAt(0))) {
        start = startPart = 2;
    }

    for (let i = path.length - 1; i >= start; --i) {
        const code = path.charCodeAt(i);
        if (isPathSeparatorWin32(code)) {
            if (!matchedSlash) {
                startPart = i + 1;
                break;
            }
            continue;
        }
        if (end === -1) {
            matchedSlash = false;
            end = i + 1;
        }
        if (code === 46 /* . */) {
            if (startDot === -1) startDot = i;
            else if (preDotState !== 1) preDotState = 1;
        } else if (startDot !== -1) {
            preDotState = -1;
        }
    }

    if (
        startDot === -1 ||
        end === -1 ||
        preDotState === 0 ||
        (preDotState === 1 && startDot === end - 1 && startDot === startPart + 1)
    ) {
        return "";
    }
    return path.slice(startDot, end);
}

export function win32Format(pathObject) {
    if (pathObject === null || typeof pathObject !== "object") {
        throw new TypeError(
            `The "pathObject" argument must be of type Object. Received ${JSON.stringify(pathObject)}`
        );
    }
    const dir = pathObject.dir || pathObject.root || "";
    const base = pathObject.base || `${pathObject.name || ""}${formatExt(pathObject.ext)}`;
    if (!dir) return base;
    return dir === pathObject.root ? `${dir}${base}` : `${dir}${win32Sep}${base}`;
}

export function win32Parse(path) {
    assertPath(path);
    const ret = { root: "", dir: "", base: "", ext: "", name: "" };
    if (path.length === 0) return ret;

    const len = path.length;
    let rootEnd = 0;
    let code = path.charCodeAt(0);

    if (len === 1) {
        if (isPathSeparatorWin32(code)) {
            ret.root = ret.dir = path;
            return ret;
        }
        ret.base = ret.name = path;
        return ret;
    }

    if (isPathSeparatorWin32(code)) {
        rootEnd = 1;
        if (isPathSeparatorWin32(path.charCodeAt(1))) {
            let j = 2;
            let last = j;
            while (j < len && !isPathSeparatorWin32(path.charCodeAt(j))) j++;
            if (j < len && j !== last) {
                last = j;
                while (j < len && isPathSeparatorWin32(path.charCodeAt(j))) j++;
                if (j < len && j !== last) {
                    last = j;
                    while (j < len && !isPathSeparatorWin32(path.charCodeAt(j))) j++;
                    if (j === len) {
                        rootEnd = j;
                    } else if (j !== last) {
                        rootEnd = j + 1;
                    }
                }
            }
        }
    } else if (isWindowsDeviceRoot(code) && path.charCodeAt(1) === 58) {
        if (len <= 2) {
            ret.root = ret.dir = path;
            return ret;
        }
        rootEnd = 2;
        if (isPathSeparatorWin32(path.charCodeAt(2))) {
            if (len === 3) {
                ret.root = ret.dir = path;
                return ret;
            }
            rootEnd = 3;
        }
    }
    if (rootEnd > 0) ret.root = path.slice(0, rootEnd);

    let startDot = -1;
    let startPart = rootEnd;
    let end = -1;
    let matchedSlash = true;
    let i = path.length - 1;
    let preDotState = 0;

    for (; i >= rootEnd; --i) {
        code = path.charCodeAt(i);
        if (isPathSeparatorWin32(code)) {
            if (!matchedSlash) {
                startPart = i + 1;
                break;
            }
            continue;
        }
        if (end === -1) {
            matchedSlash = false;
            end = i + 1;
        }
        if (code === 46) {
            if (startDot === -1) startDot = i;
            else if (preDotState !== 1) preDotState = 1;
        } else if (startDot !== -1) {
            preDotState = -1;
        }
    }

    if (
        startDot === -1 ||
        end === -1 ||
        preDotState === 0 ||
        (preDotState === 1 && startDot === end - 1 && startDot === startPart + 1)
    ) {
        if (end !== -1) ret.base = ret.name = path.slice(startPart, end);
    } else {
        ret.name = path.slice(startPart, startDot);
        ret.base = path.slice(startPart, end);
        ret.ext = path.slice(startDot, end);
    }

    if (startPart > 0 && startPart !== rootEnd) ret.dir = path.slice(0, startPart - 1);
    else ret.dir = ret.root;

    return ret;
}

export function win32Relative(from, to) {
    assertPath(from);
    assertPath(to);
    if (from === to) return "";
    const fromOrig = win32Resolve(from);
    const toOrig = win32Resolve(to);
    if (fromOrig === toOrig) return "";
    const lowerFrom = fromOrig.toLowerCase();
    const lowerTo = toOrig.toLowerCase();
    if (lowerFrom === lowerTo) return "";

    let fromStart = 0;
    while (fromStart < fromOrig.length && fromOrig.charCodeAt(fromStart) === 92) fromStart++;
    let fromEnd = fromOrig.length;
    while (fromEnd - 1 > fromStart && fromOrig.charCodeAt(fromEnd - 1) === 92) fromEnd--;
    const fromLen = fromEnd - fromStart;

    let toStart = 0;
    while (toStart < toOrig.length && toOrig.charCodeAt(toStart) === 92) toStart++;
    let toEnd = toOrig.length;
    while (toEnd - 1 > toStart && toOrig.charCodeAt(toEnd - 1) === 92) toEnd--;
    const toLen = toEnd - toStart;

    const length = fromLen < toLen ? fromLen : toLen;
    let lastCommonSep = -1;
    let i = 0;
    for (; i < length; i++) {
        const fromCode = lowerFrom.charCodeAt(fromStart + i);
        if (fromCode !== lowerTo.charCodeAt(toStart + i)) break;
        else if (fromCode === 92) lastCommonSep = i;
    }
    if (i !== length) {
        if (i === 0) return toOrig;
    } else {
        if (toLen > length) {
            if (toOrig.charCodeAt(toStart + i) === 92) return toOrig.slice(toStart + i + 1);
            if (i === 2) return toOrig.slice(toStart + i);
        }
        if (fromLen > length) {
            if (fromOrig.charCodeAt(fromStart + i) === 92) lastCommonSep = i;
            else if (i === 2) lastCommonSep = 3;
        }
        if (lastCommonSep === -1) lastCommonSep = 0;
    }

    let out = "";
    for (i = fromStart + lastCommonSep + 1; i <= fromEnd; ++i) {
        if (i === fromEnd || fromOrig.charCodeAt(i) === 92) {
            out += out.length === 0 ? ".." : "\\..";
        }
    }
    toStart += lastCommonSep;
    if (out.length > 0) return out + toOrig.slice(toStart, toEnd);
    if (toOrig.charCodeAt(toStart) === 92) toStart++;
    return toOrig.slice(toStart, toEnd);
}

const win32Api = {
    sep: win32Sep,
    delimiter: win32Delimiter,
    isAbsolute: win32IsAbsolute,
    normalize: win32Normalize,
    join: win32Join,
    resolve: win32Resolve,
    relative: win32Relative,
    toNamespacedPath: win32ToNamespacedPath,
    dirname: win32Dirname,
    basename: win32Basename,
    extname: win32Extname,
    format: win32Format,
    parse: win32Parse,
};

export const posix = posixApi;
export const win32 = win32Api;
posixApi.posix = posixApi;
posixApi.win32 = win32Api;
win32Api.posix = posixApi;
win32Api.win32 = win32Api;

// `_makeLong` is an old, undocumented alias for `toNamespacedPath` that Node still ships
// on every path flavor for backward compatibility.
export const _makeLong = toNamespacedPath;
posixApi._makeLong = toNamespacedPath;
win32Api._makeLong = win32ToNamespacedPath;

export default {
    sep,
    delimiter,
    isAbsolute,
    normalize,
    join,
    resolve,
    relative,
    toNamespacedPath,
    dirname,
    basename,
    extname,
    format,
    parse,
    posix,
    win32,
    _makeLong,
};

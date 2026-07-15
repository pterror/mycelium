// Minimal Node.js-compatible `path` module (POSIX semantics), served by Mycelium as the
// synthetic core module resolved for `import ... from "path"` / `"node:path"`.
// Ported from https://github.com/nodejs/node/blob/main/lib/path.js (posix half only —
// `win32` below is a stub that reuses the posix implementation, since Mycelium runs on
// POSIX hosts and no test/consumer needs real win32 semantics yet).

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

// `win32` is a stub: real Windows path semantics (`\` separators, drive letters, UNC
// paths) aren't implemented — Mycelium runs on POSIX hosts and nothing exercises this
// yet. It reuses the posix implementation so `require("path").win32.join(...)` etc. at
// least don't crash outright.
export const posix = posixApi;
export const win32 = posixApi;

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
};

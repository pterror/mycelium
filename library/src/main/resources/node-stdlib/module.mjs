// Minimal Node.js-compatible `module` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "module"` / `"node:module"`. Needed by packages
// (notably yargs) that import this just to call `createRequire` or check
// `isBuiltin`/`builtinModules` — not a full reimplementation of Node's module system.
//
// `builtinModules` is hand-maintained to match InterceptingFileSystem.kt's `coreModules`
// map (the actual set of core modules Mycelium serves) — see that file for the source of
// truth if this list ever drifts.

const BUILTIN_MODULES = [
    "assert", "assert/strict", "async_hooks", "buffer", "child_process",
    "diagnostics_channel", "events", "fs", "fs/promises", "http", "http2", "https",
    "module", "net", "os", "path", "process", "querystring", "readline",
    "readline/promises", "stream", "string_decoder", "tls", "tty", "url", "util",
    "worker_threads", "zlib",
];

export const builtinModules = BUILTIN_MODULES.slice();

export function isBuiltin(moduleName) {
    if (typeof moduleName !== "string") return false;
    const name = moduleName.startsWith("node:") ? moduleName.slice("node:".length) : moduleName;
    return BUILTIN_MODULES.includes(name);
}

// Backed by the same `__mycelium_require__(specifier, fromDir)` bridge the transpiled-CJS
// `require()` shim itself calls (see InterceptingFileSystem.requireModule) — `filename` is
// only used to compute the "from directory" a relative `require('./x')` inside the
// returned function would resolve against, matching Node's own `createRequire(filename)`
// semantics (the directory containing `filename`, not `filename` itself).
export function createRequire(filename) {
    // `String(...)` handles both a plain path string and a `URL`/file-URL object (both are
    // valid per Node's own `createRequire` signature) — a `URL` instance's `toString()`
    // returns its `href`, which is exactly the "file://..." form the `file://`-stripping
    // below expects.
    const filenameStr = String(filename);
    // Strip a trailing filename component (and a `file://` prefix, in case a URL/file URL
    // was passed, per Node's own `createRequire` accepting either) to get the directory
    // require() calls should resolve relative specifiers against.
    let path = filenameStr.startsWith("file://") ? filenameStr.slice("file://".length) : filenameStr;
    const lastSlash = path.lastIndexOf("/");
    const fromDir = lastSlash === -1 ? "." : path.slice(0, lastSlash) || "/";

    function requireFn(specifier) {
        return globalThis.__mycelium_require__(specifier, fromDir);
    }
    requireFn.resolve = function resolve(specifier) {
        // Backed by the same resolution logic `__mycelium_require__` itself uses (relative
        // paths, core modules, node_modules walk) via the `__mycelium_require_resolve__`
        // bridge (see CjsRuntime.resolveRequireSpecifier) — returns the resolved path/
        // specifier without evaluating the module, matching Node's own `require.resolve`.
        return globalThis.__mycelium_require_resolve__(specifier, fromDir);
    };
    requireFn.cache = Object.create(null);
    requireFn.extensions = Object.create(null);
    requireFn.main = undefined;
    return requireFn;
}

// `Module` class: a shape-compatible stub covering the handful of static members
// third-party code sometimes reaches for (e.g. `module.Module._resolveFilename` patching,
// a common monkeypatch pattern in module-loader shims) — not a real implementation of
// Node's module loader/cache.
export class Module {
    constructor(id = "", parent = undefined) {
        this.id = id;
        this.exports = {};
        this.parent = parent;
        this.filename = null;
        this.loaded = false;
        this.children = [];
        this.paths = [];
    }

    require(specifier) {
        return globalThis.__mycelium_require__(specifier, ".");
    }

    static _resolveFilename(request, parent) {
        const fromDir = parent && parent.filename
            ? parent.filename.slice(0, Math.max(parent.filename.lastIndexOf("/"), 0)) || "."
            : ".";
        return globalThis.__mycelium_require_resolve__(request, fromDir);
    }

    static _cache = Object.create(null);
    static _extensions = Object.create(null);
    static _pathCache = Object.create(null);
    static builtinModules = BUILTIN_MODULES.slice();
    static isBuiltin = isBuiltin;
    static createRequire = createRequire;
    static wrap(script) {
        return `(function (exports, require, module, __filename, __dirname) {${script}\n});`;
    }
}

export default { builtinModules, isBuiltin, createRequire, Module };

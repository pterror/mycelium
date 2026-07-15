// Minimal Node.js-compatible `util` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "util"` / `"node:util"`.
// Mostly pure JS; TextEncoder/TextDecoder are re-exported from the guest global scope
// (GraalJS provides them) rather than reimplemented.

function inspectValue(value, seen) {
    if (value === null) return "null";
    if (value === undefined) return "undefined";
    const t = typeof value;
    if (t === "string") return `'${value.replace(/'/g, "\\'")}'`;
    if (t === "number" || t === "boolean" || t === "bigint") return String(value);
    if (t === "function") {
        return value.name ? `[Function: ${value.name}]` : "[Function (anonymous)]";
    }
    if (t === "symbol") return value.toString();
    if (value instanceof Date) return value.toISOString();
    if (value instanceof RegExp) return value.toString();
    if (value instanceof Error) return value.stack || `${value.name}: ${value.message}`;
    if (t === "object") {
        if (seen.has(value)) return "[Circular *1]";
        seen.add(value);
        try {
            if (Array.isArray(value)) {
                return `[ ${value.map((v) => inspectValue(v, seen)).join(", ")} ]`;
            }
            const entries = Object.keys(value).map(
                (k) => `${k}: ${inspectValue(value[k], seen)}`
            );
            return entries.length === 0 ? "{}" : `{ ${entries.join(", ")} }`;
        } finally {
            seen.delete(value);
        }
    }
    return String(value);
}

export function inspect(obj, opts) {
    try {
        if (obj === null || obj === undefined) return String(obj);
        if (typeof obj === "string") return opts && opts.raw ? obj : `'${obj}'`;
        return inspectValue(obj, new Set());
    } catch (e) {
        try {
            return JSON.stringify(obj);
        } catch (e2) {
            return String(obj);
        }
    }
}

export function format(fmt, ...args) {
    if (typeof fmt !== "string") {
        return [fmt, ...args].map((a) => (typeof a === "string" ? a : inspect(a))).join(" ");
    }
    let i = 0;
    let str = fmt.replace(/%[sdifjoO%]/g, (match) => {
        if (match === "%%") return "%";
        if (i >= args.length) return match;
        const arg = args[i++];
        switch (match) {
            case "%s":
                return typeof arg === "string" ? arg : inspect(arg);
            case "%d":
                return Number.isNaN(arg) ? "NaN" : String(Number(arg));
            case "%i":
                return Number.isNaN(arg) ? "NaN" : String(parseInt(arg, 10));
            case "%f":
                return String(parseFloat(arg));
            case "%j":
                try {
                    return JSON.stringify(arg);
                } catch (e) {
                    return "[Circular]";
                }
            case "%o":
            case "%O":
                return inspect(arg);
            default:
                return match;
        }
    });
    for (; i < args.length; i++) {
        const arg = args[i];
        str += " " + (typeof arg === "string" ? arg : inspect(arg));
    }
    return str;
}

export function inherits(ctor, superCtor) {
    if (ctor === undefined || ctor === null) {
        throw new TypeError('The constructor to "inherits" must not be null or undefined');
    }
    if (superCtor === undefined || superCtor === null) {
        throw new TypeError('The super constructor to "inherits" must not be null or undefined');
    }
    if (superCtor.prototype === undefined) {
        throw new TypeError('The super constructor to "inherits" must have a prototype');
    }
    ctor.super_ = superCtor;
    Object.setPrototypeOf(ctor.prototype, superCtor.prototype);
}

export function deprecate(fn, msg) {
    let warned = false;
    function deprecated(...args) {
        if (!warned) {
            warned = true;
            console.warn(`(mycelium) DeprecationWarning: ${msg}`);
        }
        return fn.apply(this, args);
    }
    deprecated.unwrapped = fn;
    return deprecated;
}

// Symbol libraries use to tag a function with its own custom promisified version — e.g.
// `fn[util.promisify.custom] = myPromisifiedFn`, which `promisify(fn)` then prefers over
// generating an errback-wrapping shim. `Symbol.for(...)` (a *registry* symbol, not a
// locally-created one) matches Node's own `util.promisify.custom`, which is likewise a
// well-known registered symbol rather than module-private — this is what lets a package
// that does its own `require("util").promisify.custom` (independent of Mycelium's util.mjs
// instance) still interoperate with code that sets/reads the symbol through this module.
const promisifyCustomSymbol = Symbol.for("nodejs.util.promisify.custom");

export function promisify(fn) {
    if (typeof fn !== "function") {
        throw new TypeError("The 'fn' argument must be of type Function");
    }
    if (fn[promisifyCustomSymbol]) {
        return fn[promisifyCustomSymbol];
    }
    function promisified(...args) {
        return new Promise((resolve, reject) => {
            fn.call(this, ...args, (err, ...results) => {
                if (err) {
                    reject(err);
                } else {
                    resolve(results.length > 1 ? results : results[0]);
                }
            });
        });
    }
    if (fn.name) {
        Object.defineProperty(promisified, "name", { value: fn.name });
    }
    return promisified;
}

promisify.custom = promisifyCustomSymbol;

export function callbackify(fn) {
    if (typeof fn !== "function") {
        throw new TypeError("The 'fn' argument must be of type Function");
    }
    function callbackified(...args) {
        const callback = args.pop();
        if (typeof callback !== "function") {
            throw new TypeError("The last argument must be of type Function");
        }
        fn.apply(this, args).then(
            (result) => callback(null, result),
            (err) => callback(err instanceof Error ? err : new Error(String(err)))
        );
    }
    if (fn.name) {
        Object.defineProperty(callbackified, "name", { value: `${fn.name}Callbackified` });
    }
    return callbackified;
}

export const types = {
    isDate: (v) => v instanceof Date,
    isRegExp: (v) => v instanceof RegExp,
    isNativeError: (v) => v instanceof Error,
    isAsyncFunction: (v) => typeof v === "function" && v.constructor.name === "AsyncFunction",
    isGeneratorFunction: (v) => typeof v === "function" && v.constructor.name === "GeneratorFunction",
    isPromise: (v) => v instanceof Promise,
    isArrayBuffer: (v) => v instanceof ArrayBuffer,
    isTypedArray: (v) => ArrayBuffer.isView(v) && !(v instanceof DataView),
    isMap: (v) => v instanceof Map,
    isSet: (v) => v instanceof Set,
    isBooleanObject: (v) => typeof v === "object" && v !== null && v instanceof Boolean,
    isNumberObject: (v) => typeof v === "object" && v !== null && v instanceof Number,
    isStringObject: (v) => typeof v === "object" && v !== null && v instanceof String,
};

// Re-export the guest environment's globals (GraalJS provides both) rather than
// reimplementing text encoding.
export const TextEncoder = globalThis.TextEncoder;
export const TextDecoder = globalThis.TextDecoder;

// Strips ANSI/VT escape sequences (color codes, cursor movement, etc.) from a string.
// Mirrors Node's own regex (lib/internal/util.js), which additionally matches "partial"
// escape sequences (no final letter yet, e.g. a truncated `\x1B[3` at the end of the
// string) — included here since some CLI libs strip incrementally-buffered chunks.
const vtControlCharsPattern = new RegExp(
    "[\\u001B\\u009B][[\\]()#;?]*" +
        "(?:(?:(?:(?:;[-a-zA-Z\\d\\/#&.:=?%@~_]+)*|[a-zA-Z\\d]+(?:;[-a-zA-Z\\d\\/#&.:=?%@~_]*)*)?\\u0007)" +
        "|(?:(?:\\d{1,4}(?:;\\d{0,4})*)?[\\dA-PRZcf-ntqry=><~]))",
    "g"
);

export function stripVTControlCharacters(str) {
    if (typeof str !== "string") {
        throw new TypeError("The 'str' argument must be of type string");
    }
    return str.replace(vtControlCharsPattern, "");
}

// `debuglog`/`debug` return a no-op function unless the given `section` (case-insensitive)
// appears in the comma/space-separated `NODE_DEBUG` env var — mirrors Node's own opt-in,
// stderr-only debug logging convention. `enabled`/`enabled.enabled` on the returned
// function let callers check the flag without invoking it (as commander-style libraries
// sometimes do).
function debuglogSection(section) {
    const raw = (globalThis.process && globalThis.process.env && globalThis.process.env.NODE_DEBUG) || "";
    const enabled = raw
        .split(/[,\s]+/)
        .filter(Boolean)
        .some((s) => s.toUpperCase() === String(section).toUpperCase());
    function log(...args) {
        if (!enabled) return;
        console.error(`${String(section).toUpperCase()} ${(globalThis.process && globalThis.process.pid) || 0}: ${format(...args)}`);
    }
    log.enabled = enabled;
    return log;
}

export function debuglog(section, callback) {
    const logger = debuglogSection(section);
    if (typeof callback === "function") callback(logger);
    return logger;
}

export const debug = debuglog;

// Minimal implementation of Node's built-in CLI argument parser (`util.parseArgs`).
// Supports the documented shape used by libraries like commander: `{ args, options,
// strict, allowPositionals, tokens }` -> `{ values, positionals, tokens? }`. `options` maps
// a long option name to `{ type: "string" | "boolean", short, multiple, default }`.
export function parseArgs(config) {
    const cfg = config || {};
    const args = cfg.args || (globalThis.process ? globalThis.process.argv.slice(2) : []);
    const optionsSpec = cfg.options || {};
    const strict = cfg.strict !== false;
    const allowPositionals = cfg.allowPositionals !== undefined ? cfg.allowPositionals : !strict;
    const tokensRequested = !!cfg.tokens;

    const shortToLong = {};
    for (const [name, spec] of Object.entries(optionsSpec)) {
        if (spec && spec.short) shortToLong[spec.short] = name;
    }

    const values = Object.create(null);
    const positionals = [];
    const tokens = [];
    for (const [name, spec] of Object.entries(optionsSpec)) {
        if (spec && spec.default !== undefined) values[name] = spec.default;
    }

    function specFor(name) {
        return optionsSpec[name] || {};
    }

    function setValue(name, value, index) {
        const spec = specFor(name);
        if (spec.type === "boolean") {
            if (spec.multiple) {
                values[name] = values[name] || [];
                values[name].push(value);
            } else {
                values[name] = value;
            }
        } else {
            if (spec.multiple) {
                values[name] = values[name] || [];
                values[name].push(value);
            } else {
                values[name] = value;
            }
        }
        if (tokensRequested) {
            tokens.push({ kind: "option", name, rawName: name, value, index });
        }
    }

    let i = 0;
    let sawDoubleDash = false;
    while (i < args.length) {
        const arg = args[i];
        if (!sawDoubleDash && arg === "--") {
            sawDoubleDash = true;
            if (tokensRequested) tokens.push({ kind: "option-terminator", index: i });
            i++;
            continue;
        }
        if (!sawDoubleDash && arg.startsWith("--")) {
            let name = arg.slice(2);
            let inlineValue;
            const eqIdx = name.indexOf("=");
            if (eqIdx !== -1) {
                inlineValue = name.slice(eqIdx + 1);
                name = name.slice(0, eqIdx);
            }
            if (strict && !Object.prototype.hasOwnProperty.call(optionsSpec, name)) {
                throw new TypeError(`Unknown option '--${name}'`);
            }
            const spec = specFor(name);
            if (spec.type === "boolean") {
                setValue(name, true, i);
                i++;
            } else {
                const value = inlineValue !== undefined ? inlineValue : args[++i];
                setValue(name, value, i);
                i++;
            }
            continue;
        }
        if (!sawDoubleDash && arg.startsWith("-") && arg.length > 1) {
            const short = arg.slice(1, 2);
            const name = shortToLong[short] || short;
            if (strict && !Object.prototype.hasOwnProperty.call(optionsSpec, name)) {
                throw new TypeError(`Unknown option '-${short}'`);
            }
            const spec = specFor(name);
            if (spec.type === "boolean") {
                setValue(name, true, i);
                i++;
            } else {
                const inline = arg.slice(2);
                const value = inline.length > 0 ? inline : args[++i];
                setValue(name, value, i);
                i++;
            }
            continue;
        }
        if (!allowPositionals) {
            throw new TypeError(`Unexpected positional argument '${arg}'`);
        }
        positionals.push(arg);
        if (tokensRequested) tokens.push({ kind: "positional", index: i, value: arg });
        i++;
    }

    const result = { values, positionals };
    if (tokensRequested) result.tokens = tokens;
    return result;
}

// ANSI SGR codes for the format names Node's `util.styleText` recognizes (a subset —
// styles/colors most commonly used; unrecognized names throw, per Node's own behavior).
const styleTextCodes = {
    reset: [0, 0],
    bold: [1, 22],
    dim: [2, 22],
    italic: [3, 23],
    underline: [4, 24],
    overline: [53, 55],
    inverse: [7, 27],
    hidden: [8, 28],
    strikethrough: [9, 29],
    black: [30, 39],
    red: [31, 39],
    green: [32, 39],
    yellow: [33, 39],
    blue: [34, 39],
    magenta: [35, 39],
    cyan: [36, 39],
    white: [37, 39],
    gray: [90, 39],
    grey: [90, 39],
    blackBright: [90, 39],
    redBright: [91, 39],
    greenBright: [92, 39],
    yellowBright: [93, 39],
    blueBright: [94, 39],
    magentaBright: [95, 39],
    cyanBright: [96, 39],
    whiteBright: [97, 39],
    bgBlack: [40, 49],
    bgRed: [41, 49],
    bgGreen: [42, 49],
    bgYellow: [43, 49],
    bgBlue: [44, 49],
    bgMagenta: [45, 49],
    bgCyan: [46, 49],
    bgWhite: [47, 49],
};

export function styleText(format, text) {
    const formats = Array.isArray(format) ? format : [format];
    let open = "";
    let close = "";
    for (const f of formats) {
        const codes = styleTextCodes[f];
        if (!codes) {
            throw new TypeError(`The value "${f}" is invalid for argument 'format'`);
        }
        open += `\x1b[${codes[0]}m`;
        close = `\x1b[${codes[1]}m${close}`;
    }
    return `${open}${text}${close}`;
}

export function formatWithOptions(inspectOptions, fmt, ...args) {
    if (typeof fmt !== "string") {
        return [fmt, ...args].map((a) => (typeof a === "string" ? a : inspect(a, inspectOptions))).join(" ");
    }
    let i = 0;
    let str = fmt.replace(/%[sdifjoO%]/g, (match) => {
        if (match === "%%") return "%";
        if (i >= args.length) return match;
        const arg = args[i++];
        switch (match) {
            case "%s":
                return typeof arg === "string" ? arg : inspect(arg, inspectOptions);
            case "%d":
                return Number.isNaN(arg) ? "NaN" : String(Number(arg));
            case "%i":
                return Number.isNaN(arg) ? "NaN" : String(parseInt(arg, 10));
            case "%f":
                return String(parseFloat(arg));
            case "%j":
                try {
                    return JSON.stringify(arg);
                } catch (e) {
                    return "[Circular]";
                }
            case "%o":
            case "%O":
                return inspect(arg, inspectOptions);
            default:
                return match;
        }
    });
    for (; i < args.length; i++) {
        const arg = args[i];
        str += " " + (typeof arg === "string" ? arg : inspect(arg, inspectOptions));
    }
    return str;
}

// Maps a subset of common libuv/POSIX errno numbers to their Node-style names — a small
// fixed table rather than a full errno database, covering the ones most likely to
// surface from Mycelium's own Java-backed fs/net shims.
const systemErrorNames = {
    "-1": "EPERM",
    "-2": "ENOENT",
    "-4": "EINTR",
    "-9": "EBADF",
    "-11": "EAGAIN",
    "-13": "EACCES",
    "-16": "EBUSY",
    "-17": "EEXIST",
    "-20": "ENOTDIR",
    "-21": "EISDIR",
    "-22": "EINVAL",
    "-24": "EMFILE",
    "-28": "ENOSPC",
    "-30": "EROFS",
    "-32": "EPIPE",
    "-36": "ENAMETOOLONG",
    "-39": "ENOTEMPTY",
    "-4077": "ECONNRESET",
    "-4078": "ECONNREFUSED",
    "-4079": "EADDRINUSE",
    "-4090": "ETIMEDOUT",
};

export function getSystemErrorName(errno) {
    return systemErrorNames[String(errno)] || `Unknown system error ${errno}`;
}

// Replaces lone (unpaired) UTF-16 surrogates with U+FFFD, matching the WHATWG "USVString"
// coercion Node's `util.toUSVString` performs.
export function toUSVString(str) {
    const s = String(str);
    let result = "";
    for (let i = 0; i < s.length; i++) {
        const code = s.charCodeAt(i);
        if (code >= 0xd800 && code <= 0xdbff) {
            const next = s.charCodeAt(i + 1);
            if (next >= 0xdc00 && next <= 0xdfff) {
                result += s[i] + s[i + 1];
                i++;
            } else {
                result += "�";
            }
        } else if (code >= 0xdc00 && code <= 0xdfff) {
            result += "�";
        } else {
            result += s[i];
        }
    }
    return result;
}

export function isDeepStrictEqual(a, b) {
    if (a === b) return true;
    if (typeof a !== typeof b) return false;
    if (a === null || b === null) return a === b;
    if (typeof a !== "object") return false;
    const aKeys = Object.keys(a);
    const bKeys = Object.keys(b);
    if (aKeys.length !== bKeys.length) return false;
    return aKeys.every((k) => isDeepStrictEqual(a[k], b[k]));
}

export default {
    format,
    formatWithOptions,
    inspect,
    inherits,
    deprecate,
    promisify,
    callbackify,
    types,
    TextEncoder,
    TextDecoder,
    isDeepStrictEqual,
    stripVTControlCharacters,
    debuglog,
    debug,
    parseArgs,
    styleText,
    getSystemErrorName,
    toUSVString,
};

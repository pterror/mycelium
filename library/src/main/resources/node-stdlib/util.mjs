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

export function promisify(fn) {
    if (typeof fn !== "function") {
        throw new TypeError("The 'fn' argument must be of type Function");
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
    inspect,
    inherits,
    deprecate,
    promisify,
    callbackify,
    types,
    TextEncoder,
    TextDecoder,
    isDeepStrictEqual,
};

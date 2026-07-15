// Minimal Node.js-compatible `assert` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "assert"` / `"node:assert"` /
// `"assert/strict"` / `"node:assert/strict"`. Pure JS — no Java interop needed.
//
// `assert/strict` is Node's variant where the loose (`==`) comparisons (`equal`/
// `notEqual`) are aliased to their strict (`===`) counterparts; InterceptingFileSystem
// maps all four specifiers to this same resource, so that remapping happens here based on
// nothing external — both the loose and strict named exports are always provided, and
// callers importing via the `*_strict` core-module alias just don't happen to use the
// loose ones. A true "importing assert/strict makes assert.equal itself throw on ===
// mismatches" isn't implemented, since it would require the strict/non-strict variants to
// be genuinely different module instances, which the shared-resource mapping here doesn't
// support.

export class AssertionError extends Error {
    constructor(options) {
        const opts = options || {};
        super(opts.message || generateMessage(opts));
        this.name = "AssertionError";
        this.code = "ERR_ASSERTION";
        this.actual = opts.actual;
        this.expected = opts.expected;
        this.operator = opts.operator;
        this.generatedMessage = !opts.message;
    }
}

function generateMessage(opts) {
    if (opts.operator) {
        return `${safeStringify(opts.actual)} ${opts.operator} ${safeStringify(opts.expected)}`;
    }
    return "Assertion failed";
}

function safeStringify(value) {
    try {
        if (typeof value === "string") return JSON.stringify(value);
        if (value === undefined) return "undefined";
        return JSON.stringify(value);
    } catch (e) {
        return String(value);
    }
}

function fail(actual, expected, message, operator) {
    throw new AssertionError({ actual, expected, message, operator });
}

function isDeepEqual(a, b, strict) {
    if (Object.is(a, b)) return true;
    if (!strict) {
        // eslint-disable-next-line eqeqeq
        if (a == null || b == null) return a == b;
    }
    if (typeof a !== "object" || typeof b !== "object" || a === null || b === null) {
        return strict ? false : a == b;
    }
    if (strict && Object.getPrototypeOf(a) !== Object.getPrototypeOf(b)) return false;
    if (Array.isArray(a) !== Array.isArray(b)) return false;
    if (a instanceof Date || b instanceof Date) {
        return a instanceof Date && b instanceof Date && a.getTime() === b.getTime();
    }
    if (a instanceof RegExp || b instanceof RegExp) {
        return a instanceof RegExp && b instanceof RegExp && String(a) === String(b);
    }
    const aKeys = Object.keys(a);
    const bKeys = Object.keys(b);
    if (aKeys.length !== bKeys.length) return false;
    for (const key of aKeys) {
        if (!Object.prototype.hasOwnProperty.call(b, key)) return false;
        if (!isDeepEqual(a[key], b[key], strict)) return false;
    }
    return true;
}

export function ok(value, message) {
    if (!value) {
        fail(value, true, message, "==");
    }
}

function assertFn(value, message) {
    ok(value, message);
}

export function equal(actual, expected, message) {
    // eslint-disable-next-line eqeqeq
    if (!(actual == expected)) {
        fail(actual, expected, message, "==");
    }
}

export function notEqual(actual, expected, message) {
    // eslint-disable-next-line eqeqeq
    if (actual == expected) {
        fail(actual, expected, message, "!=");
    }
}

export function strictEqual(actual, expected, message) {
    if (!Object.is(actual, expected) && actual !== expected) {
        fail(actual, expected, message, "===");
    }
}

export function notStrictEqual(actual, expected, message) {
    if (Object.is(actual, expected) || actual === expected) {
        fail(actual, expected, message, "!==");
    }
}

export function deepEqual(actual, expected, message) {
    if (!isDeepEqual(actual, expected, false)) {
        fail(actual, expected, message, "deepEqual");
    }
}

export function notDeepEqual(actual, expected, message) {
    if (isDeepEqual(actual, expected, false)) {
        fail(actual, expected, message, "notDeepEqual");
    }
}

export function deepStrictEqual(actual, expected, message) {
    if (!isDeepEqual(actual, expected, true)) {
        fail(actual, expected, message, "deepStrictEqual");
    }
}

export function notDeepStrictEqual(actual, expected, message) {
    if (isDeepEqual(actual, expected, true)) {
        fail(actual, expected, message, "notDeepStrictEqual");
    }
}

function expectedMatches(error, expected) {
    if (expected === undefined) return true;
    if (typeof expected === "function") {
        if (Object.prototype.isPrototypeOf.call(expected.prototype, error) || error instanceof expected) {
            return true;
        }
        // Also allow a plain validator function (returns truthy to accept the error).
        return !!expected(error);
    }
    if (expected instanceof RegExp) {
        return expected.test(String(error && error.message !== undefined ? error.message : error));
    }
    if (typeof expected === "object" && expected !== null) {
        for (const key of Object.keys(expected)) {
            if (error[key] !== expected[key]) return false;
        }
        return true;
    }
    return true;
}

export function throws(fn, expected, message) {
    let threw = false;
    let caught;
    try {
        fn();
    } catch (e) {
        threw = true;
        caught = e;
    }
    if (!threw) {
        fail(undefined, undefined, message || "Missing expected exception", "throws");
    }
    if (!expectedMatches(caught, typeof expected === "string" ? undefined : expected)) {
        throw caught;
    }
}

export function doesNotThrow(fn, expected, message) {
    try {
        fn();
    } catch (e) {
        const msg = typeof expected === "string" ? expected : message;
        throw new AssertionError({
            message: msg || `Got unwanted exception: ${e && e.message}`,
            actual: e,
            operator: "doesNotThrow",
        });
    }
}

export async function rejects(asyncFn, expected, message) {
    let threw = false;
    let caught;
    try {
        const value = typeof asyncFn === "function" ? asyncFn() : asyncFn;
        await value;
    } catch (e) {
        threw = true;
        caught = e;
    }
    if (!threw) {
        fail(undefined, undefined, message || "Missing expected rejection", "rejects");
    }
    if (!expectedMatches(caught, typeof expected === "string" ? undefined : expected)) {
        throw caught;
    }
}

export async function doesNotReject(asyncFn, expected, message) {
    try {
        const value = typeof asyncFn === "function" ? asyncFn() : asyncFn;
        await value;
    } catch (e) {
        const msg = typeof expected === "string" ? expected : message;
        throw new AssertionError({
            message: msg || `Got unwanted rejection: ${e && e.message}`,
            actual: e,
            operator: "doesNotReject",
        });
    }
}

export function match(string, regexp, message) {
    if (!regexp.test(string)) {
        fail(string, regexp, message, "match");
    }
}

export function doesNotMatch(string, regexp, message) {
    if (regexp.test(string)) {
        fail(string, regexp, message, "doesNotMatch");
    }
}

export function assertFail(message) {
    throw new AssertionError({ message: message || "Failed", operator: "fail" });
}

export function ifError(value) {
    if (value !== undefined && value !== null && value !== false) {
        throw new AssertionError({
            actual: value,
            message: `ifError got unwanted exception: ${value && value.message ? value.message : value}`,
            operator: "ifError",
        });
    }
}

const assert = Object.assign(assertFn, {
    AssertionError,
    ok,
    equal,
    notEqual,
    strictEqual,
    notStrictEqual,
    deepEqual,
    notDeepEqual,
    deepStrictEqual,
    notDeepStrictEqual,
    throws,
    doesNotThrow,
    rejects,
    doesNotReject,
    match,
    doesNotMatch,
    fail: assertFail,
    ifError,
});

// `assert.strict` mirrors Node's own `assert.strict` namespace, where the loose
// comparisons are aliased to their strict counterparts.
assert.strict = Object.assign(assertFn.bind ? strictEqual.bind(null) : strictEqual, {
    AssertionError,
    ok,
    equal: strictEqual,
    notEqual: notStrictEqual,
    strictEqual,
    notStrictEqual,
    deepEqual: deepStrictEqual,
    notDeepEqual: notDeepStrictEqual,
    deepStrictEqual,
    notDeepStrictEqual,
    throws,
    doesNotThrow,
    rejects,
    doesNotReject,
    match,
    doesNotMatch,
    fail: assertFail,
    ifError,
});

export default assert;

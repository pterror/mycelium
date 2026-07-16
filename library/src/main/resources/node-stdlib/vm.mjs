// Minimal Node.js-compatible `vm` module, served by Mycelium as the synthetic core module
// resolved for `import ... from "vm"` / `"node:vm"`. Needed by npm packages that
// unconditionally `require('vm')` at module load time even when the actual sandboxing
// features are never exercised on the path Mycelium drives — e.g. CoffeeScript's
// `lib/coffeescript/index.js` requires `vm` for its (optional, only-used-by-`eval`/`register`)
// REPL-context helpers, but Mycelium's own compile-to-JS pipeline only ever calls
// `CoffeeScript.compile`, which never touches `vm` at all. Without a `vm` module registered
// here at all, that top-level `require('vm')` itself throws "Cannot find module 'vm'" before
// `compile` is ever reached.
//
// GraalJS's shared, single Context has no notion of separate V8-style "realms"/isolates the
// way real Node's `vm` module provides (`vm.createContext` in real Node backs a genuinely
// separate global object) — Mycelium has exactly one JS global scope, shared by every guest
// script. So this is deliberately NOT true sandboxing: `runInContext`/`runInNewContext`
// evaluate against the *same* `globalThis` every other Mycelium script shares, with the
// `sandbox` object's own properties copied onto it first (best-effort) rather than used as a
// genuinely separate global. That's sufficient for "just don't crash on `require('vm')`" and
// for any straightforward "compile this string as JS and run it" use, but a package genuinely
// relying on `vm`'s isolation guarantees (e.g. leaking no globals between sandboxed runs) will
// not get that here. Track real per-Context sandboxing as future work if a concrete package
// needs it (see TODO.md).
const globalEval = (0, eval);

const contextMarker = new WeakSet();

// One-time warning, emitted the first time any code actually asks `vm` for isolation
// (`createContext`/`runInContext`/`runInNewContext` — NOT `runInThisContext`, which never
// claimed isolation in the first place). Many packages `require('vm')` purely to `eval`
// arbitrary strings (no isolation expected), so unconditionally throwing here would break
// those working call sites for the sake of ones that need real sandboxing. A loud one-time
// warning is the honest middle ground: code keeps working, but anyone relying on `vm` to
// actually contain untrusted code finds out immediately rather than discovering the leak
// the hard way.
let sandboxWarningEmitted = false;
function warnNoIsolation() {
    if (sandboxWarningEmitted) return;
    sandboxWarningEmitted = true;
    try {
        console.error(
            "mycelium: vm module warning — vm.createContext/runInContext/runInNewContext do " +
            "NOT provide real sandboxing here. GraalJS's Context has a single shared " +
            "globalThis; code run via vm executes against that same shared global, with the " +
            "sandbox object's own properties copied onto it (best-effort) rather than used " +
            "as a genuinely separate global object. Globals leak both ways between " +
            "\"sandboxed\" runs and the rest of the process. Do not rely on this for " +
            "untrusted-code isolation.",
        );
    } catch (e) {
        // console may not be available in every host context this runs under — never let
        // the warning itself break the caller.
    }
}

export function createContext(sandbox) {
    warnNoIsolation();
    const ctx = sandbox === undefined ? {} : sandbox;
    if (ctx !== null && typeof ctx === 'object') {
        contextMarker.add(ctx);
        for (const key of Object.keys(ctx)) {
            globalThis[key] = ctx[key];
        }
    }
    return ctx;
}

export function isContext(sandbox) {
    return typeof sandbox === 'object' && sandbox !== null && contextMarker.has(sandbox);
}

export function runInThisContext(code, options) {
    return globalEval(String(code));
}

export function runInContext(code, contextifiedObject, options) {
    warnNoIsolation();
    if (contextifiedObject !== undefined && contextifiedObject !== null && !isContext(contextifiedObject)) {
        createContext(contextifiedObject);
    }
    return globalEval(String(code));
}

export function runInNewContext(code, sandbox, options) {
    warnNoIsolation();
    const ctx = createContext(sandbox);
    const result = globalEval(String(code));
    return result;
}

export class Script {
    constructor(code, options) {
        this.code = String(code);
        this.options = options || {};
    }

    runInThisContext(options) {
        return runInThisContext(this.code, options);
    }

    runInContext(contextifiedObject, options) {
        return runInContext(this.code, contextifiedObject, options);
    }

    runInNewContext(sandbox, options) {
        return runInNewContext(this.code, sandbox, options);
    }

    createCachedData() {
        // No real V8 bytecode cache in GraalJS — return an empty buffer rather than
        // throwing, matching the shape (a Buffer/Uint8Array) callers expect.
        return new Uint8Array(0);
    }
}

export function compileFunction(code, params, options) {
    const args = Array.isArray(params) ? params : [];
    return new Function(...args, String(code));
}

export default {
    createContext,
    isContext,
    runInThisContext,
    runInContext,
    runInNewContext,
    Script,
    compileFunction,
};

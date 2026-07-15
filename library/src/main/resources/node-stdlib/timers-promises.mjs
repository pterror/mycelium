// Minimal Node.js-compatible `timers/promises` module, served by Mycelium as the
// synthetic core module resolved for `import ... from "timers/promises"` /
// `"node:timers/promises"`.
//
// Implements the promise-returning variants of Node's timer APIs on top of the same
// bare `setTimeout`/`setInterval`/`setImmediate` globals `timers.mjs` re-exports (see
// its own comment) — there's no native promise-based timer primitive to delegate to, so
// each function here is a thin `new Promise(...)` wrapper around the callback-based
// global, mirroring Node's own implementation shape.

function timeout(delay, value) {
    return new Promise((resolve) => {
        globalThis.setTimeout(() => resolve(value), delay);
    });
}

function immediate(value) {
    return new Promise((resolve) => {
        if (typeof globalThis.setImmediate !== "undefined") {
            globalThis.setImmediate(() => resolve(value));
        } else {
            globalThis.setTimeout(() => resolve(value), 0);
        }
    });
}

// `setInterval` here is Node's async-iterator flavor (`timers/promises`), not the
// callback-based `timers` one of the same name — each iteration resolves after `delay`
// ms, yielding `value` each time, until the caller stops iterating (e.g. `break`).
async function* interval(delay, value) {
    while (true) {
        await timeout(delay);
        yield value;
    }
}

// `timers/promises`' `setTimeout`/`setImmediate` accept an AbortSignal via `options`
// (`{ signal }`) that rejects the pending promise early — not wired up to a real abort
// mechanism here (Mycelium's timer globals have no cancellation hook to abort into), but
// accepted and ignored so callers that pass one don't throw on an unrecognized option.
export { timeout as setTimeout, immediate as setImmediate, interval as setInterval };

export default {
    setTimeout: timeout,
    setImmediate: immediate,
    setInterval: interval,
};

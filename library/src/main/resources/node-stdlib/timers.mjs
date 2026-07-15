// Minimal Node.js-compatible `timers` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "timers"` / `"node:timers"`.
//
// Node's `timers` module is just a named-export wrapper around the same
// setTimeout/setInterval/setImmediate/clear* functions already exposed as bare globals
// (see PROCESS_INIT_SCRIPT / the timer polyfills in Mycelium.kt) — it doesn't implement
// anything of its own, so this simply re-exports whatever `globalThis` already provides
// rather than re-implementing timer scheduling a second time. `setImmediate`/
// `clearImmediate` are guarded with `typeof` checks (rather than assumed to exist)
// because they're a Node-only, non-standard addition to `globalThis` alongside the
// standard `setTimeout`/`setInterval`, unlike those two which every guest JS environment
// provides.

export const setTimeout = globalThis.setTimeout;
export const clearTimeout = globalThis.clearTimeout;
export const setInterval = globalThis.setInterval;
export const clearInterval = globalThis.clearInterval;
export const setImmediate = typeof globalThis.setImmediate !== "undefined" ? globalThis.setImmediate : undefined;
export const clearImmediate = typeof globalThis.clearImmediate !== "undefined" ? globalThis.clearImmediate : undefined;

export default {
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval,
    setImmediate,
    clearImmediate,
};

// Minimal Node.js-compatible `perf_hooks` module, served by Mycelium as the synthetic
// core module resolved for `import ... from "perf_hooks"` / `"node:perf_hooks"`.
// Backed by Java interop via `Java.type(...)` (GraalJS) since JS itself has no access to
// a monotonic system clock — mirrors os.mjs's approach. Only `performance.now()` (plus a
// handful of stub members packages sometimes destructure without calling) is implemented;
// there's no real timeline/entry buffer or event-loop-delay monitoring behind it.

const JSystem = Java.type("java.lang.System");

// `System.nanoTime()`'s origin is arbitrary (not epoch-relative) but monotonic, which is
// exactly what `performance.now()` needs — capture the starting offset once at module
// load so `now()` returns "milliseconds since this process/module first touched
// perf_hooks", the same shape as real Node's "milliseconds since the process started".
const startNanos = JSystem.nanoTime();

function now() {
    return Number(JSystem.nanoTime() - startNanos) / 1e6;
}

class PerformanceObserver {
    constructor(callback) {
        this._callback = callback;
    }
    observe() {}
    disconnect() {}
}

export const performance = {
    now,
    timeOrigin: Date.now() - now(),
    mark() {},
    measure() {},
    clearMarks() {},
    clearMeasures() {},
    getEntries() { return []; },
    getEntriesByName() { return []; },
    getEntriesByType() { return []; },
};

export { PerformanceObserver };

export function monitorEventLoopDelay() {
    return {
        enable() {},
        disable() {},
        reset() {},
        min: 0,
        max: 0,
        mean: 0,
        stddev: 0,
        percentile() { return 0; },
    };
}

export const constants = {};

export default {
    performance,
    PerformanceObserver,
    monitorEventLoopDelay,
    constants,
};

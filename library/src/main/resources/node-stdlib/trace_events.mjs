// Minimal `trace_events` module stub, served by Mycelium as the synthetic core module
// resolved for `import ... from "trace_events"` / `"node:trace_events"`. Real Node backs
// this with V8's own tracing subsystem writing Chrome-tracing-format JSON to disk; Mycelium
// has no equivalent tracing backend, so `Tracing` objects here just track their own
// enabled/disabled state and categories without ever actually recording or emitting trace
// data.

class Tracing {
    constructor(categories) {
        this._categories = Array.isArray(categories) ? categories.slice() : [categories].filter(Boolean);
        this._enabled = false;
    }

    get categories() {
        return this._categories.join(",");
    }

    get enabled() {
        return this._enabled;
    }

    enable() {
        this._enabled = true;
    }

    disable() {
        this._enabled = false;
    }
}

const enabledTracings = new Set();

export function createTracing(options) {
    const categories = (options && options.categories) || [];
    return new Tracing(categories);
}

export function getEnabledCategories() {
    if (enabledTracings.size === 0) return undefined;
    return Array.from(enabledTracings).join(",");
}

export default {
    createTracing,
    getEnabledCategories,
};

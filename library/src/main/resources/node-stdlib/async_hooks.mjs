// Minimal Node.js-compatible `async_hooks` module, served by Mycelium as the synthetic
// core module resolved for `import ... from "async_hooks"` / `"node:async_hooks"`.
//
// GraalJS has no equivalent of V8's async-resource hooks, so `createHook`'s callbacks are
// never actually invoked here (there's no engine-level init/before/after/destroy signal to
// wire them up to) — `enable`/`disable` are accepted and no-ops. `executionAsyncId`/
// `triggerAsyncId` always return 0 (Node's own "no known async context" sentinel), which is
// what a library polling them outside of any real hook wiring would see anyway.
//
// `AsyncLocalStorage`, on the other hand, is implemented for real: `run`/`getStore`/
// `enterWith` genuinely thread a "current store" through — but only synchronously, via a
// simple push/pop stack, since without true async-context propagation there's no way to
// resume a suspended store across an `await` the way Node's real implementation does. Most
// consumers (e.g. request-scoped logging inside a synchronous call chain) still work; a
// store started via `run()` won't automatically still be "current" after crossing a real
// microtask boundary that wasn't itself wrapped in `run()`.

export function createHook(callbacks) {
    return {
        enable() {
            return this;
        },
        disable() {
            return this;
        },
    };
}

export function executionAsyncId() {
    return 0;
}

export function triggerAsyncId() {
    return 0;
}

export function executionAsyncResource() {
    return {};
}

export class AsyncResource {
    constructor(type, options) {
        this.type = type;
        this._triggerAsyncId =
            options && typeof options === "object" && options.triggerAsyncId !== undefined
                ? options.triggerAsyncId
                : triggerAsyncId();
        this._asyncId = ++AsyncResource._nextId;
    }

    asyncId() {
        return this._asyncId;
    }

    triggerAsyncId() {
        return this._triggerAsyncId;
    }

    runInAsyncScope(fn, thisArg, ...args) {
        return fn.apply(thisArg, args);
    }

    emitDestroy() {
        return this;
    }

    bind(fn, thisArg) {
        const resource = this;
        const bound = function (...args) {
            return resource.runInAsyncScope(fn, thisArg === undefined ? this : thisArg, ...args);
        };
        return bound;
    }

    static bind(fn, type, thisArg) {
        const resource = new AsyncResource(type || fn.name || "bound-anonymous-fn");
        return resource.bind(fn, thisArg);
    }
}
AsyncResource._nextId = 0;

export class AsyncLocalStorage {
    constructor() {
        this._stack = [];
    }

    getStore() {
        return this._stack.length > 0 ? this._stack[this._stack.length - 1] : undefined;
    }

    run(store, callback, ...args) {
        this._stack.push(store);
        try {
            return callback(...args);
        } finally {
            this._stack.pop();
        }
    }

    exit(callback, ...args) {
        const saved = this._stack;
        this._stack = [];
        try {
            return callback(...args);
        } finally {
            this._stack = saved;
        }
    }

    enterWith(store) {
        this._stack.push(store);
    }

    disable() {
        this._stack = [];
    }

    static bind(fn) {
        return fn;
    }

    static snapshot() {
        return (fn, ...args) => fn(...args);
    }
}

export default {
    createHook,
    executionAsyncId,
    triggerAsyncId,
    executionAsyncResource,
    AsyncResource,
    AsyncLocalStorage,
};

// Minimal Node.js-compatible `events` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "events"` / `"node:events"`.
// Ported (simplified) from https://github.com/nodejs/node/blob/main/lib/events.js — pure
// JS, no Java interop needed.

const kMaxListeners = Symbol("maxListeners");

export class EventEmitter {
    constructor() {
        this._events = Object.create(null);
        this[kMaxListeners] = undefined;
    }

    static defaultMaxListeners = 10;

    // Node's real EventEmitter lazily initializes `_events` (via `EventEmitter.init()`,
    // called from every prototype method that touches it) rather than only in the
    // constructor — that's what lets a plain object become "EventEmitter-like" purely by
    // having `EventEmitter.prototype`'s methods copied onto it (e.g. express's `mixin(app,
    // EventEmitter.prototype, false)` in lib/express.js, which merges the prototype's own
    // methods onto a plain function object without ever calling `new EventEmitter()`).
    // Every method below that reads/writes `this._events` calls this first instead of
    // assuming the constructor already ran.
    _ensureEvents() {
        if (this._events === undefined) {
            this._events = Object.create(null);
        }
        return this._events;
    }

    _getMaxListeners() {
        return this[kMaxListeners] === undefined
            ? EventEmitter.defaultMaxListeners
            : this[kMaxListeners];
    }

    setMaxListeners(n) {
        if (typeof n !== "number" || n < 0 || Number.isNaN(n)) {
            throw new TypeError(
                `The "n" argument must be a non-negative number. Received ${n}`
            );
        }
        this[kMaxListeners] = n;
        return this;
    }

    getMaxListeners() {
        return this._getMaxListeners();
    }

    _addListener(event, listener, prepend, once) {
        if (typeof listener !== "function") {
            throw new TypeError(
                `The "listener" argument must be of type Function. Received ${typeof listener}`
            );
        }
        this._ensureEvents();
        this.emit("newListener", event, listener.listener || listener);

        let existing = this._events[event];
        const wrapped = once ? this._onceWrap(event, listener) : listener;
        if (existing === undefined) {
            this._events[event] = wrapped;
        } else if (Array.isArray(existing)) {
            if (prepend) existing.unshift(wrapped);
            else existing.push(wrapped);
        } else {
            this._events[event] = prepend ? [wrapped, existing] : [existing, wrapped];
        }

        const max = this._getMaxListeners();
        const count = this.listenerCount(event);
        if (max > 0 && count > max) {
            console.warn(
                `(mycelium) MaxListenersExceededWarning: Possible EventEmitter memory leak ` +
                    `detected. ${count} ${String(event)} listeners added. Use ` +
                    `emitter.setMaxListeners() to increase limit.`
            );
        }
        return this;
    }

    _onceWrap(event, listener) {
        let fired = false;
        const emitter = this;
        function wrapper(...args) {
            if (!fired) {
                fired = true;
                emitter.removeListener(event, wrapper);
                listener.apply(emitter, args);
            }
        }
        wrapper.listener = listener;
        return wrapper;
    }

    on(event, listener) {
        return this._addListener(event, listener, false, false);
    }

    addListener(event, listener) {
        return this.on(event, listener);
    }

    prependListener(event, listener) {
        return this._addListener(event, listener, true, false);
    }

    once(event, listener) {
        return this._addListener(event, listener, false, true);
    }

    prependOnceListener(event, listener) {
        return this._addListener(event, listener, true, true);
    }

    removeListener(event, listener) {
        const existing = this._ensureEvents()[event];
        if (existing === undefined) return this;

        if (existing === listener || existing.listener === listener) {
            delete this._events[event];
        } else if (Array.isArray(existing)) {
            let index = -1;
            for (let i = existing.length - 1; i >= 0; i--) {
                if (existing[i] === listener || existing[i].listener === listener) {
                    index = i;
                    break;
                }
            }
            if (index === -1) return this;
            if (index === 0) existing.shift();
            else existing.splice(index, 1);
            if (existing.length === 1) this._events[event] = existing[0];
            else if (existing.length === 0) delete this._events[event];
        }
        return this;
    }

    off(event, listener) {
        return this.removeListener(event, listener);
    }

    removeAllListeners(event) {
        this._ensureEvents();
        if (event === undefined) {
            this._events = Object.create(null);
        } else {
            delete this._events[event];
        }
        return this;
    }

    emit(event, ...args) {
        const handler = this._ensureEvents()[event];
        if (handler === undefined) {
            if (event === "error") {
                const err = args[0];
                if (err instanceof Error) throw err;
                throw new Error(`Unhandled error. (${JSON.stringify(err)})`);
            }
            return false;
        }
        if (typeof handler === "function") {
            handler.apply(this, args);
        } else {
            const listeners = handler.slice();
            for (const listener of listeners) {
                listener.apply(this, args);
            }
        }
        return true;
    }

    listenerCount(event) {
        const handler = this._ensureEvents()[event];
        if (handler === undefined) return 0;
        if (typeof handler === "function") return 1;
        return handler.length;
    }

    listeners(event) {
        const handler = this._ensureEvents()[event];
        if (handler === undefined) return [];
        if (typeof handler === "function") return [handler.listener || handler];
        return handler.map((l) => l.listener || l);
    }

    rawListeners(event) {
        const handler = this._ensureEvents()[event];
        if (handler === undefined) return [];
        if (typeof handler === "function") return [handler];
        return handler.slice();
    }

    eventNames() {
        return Object.keys(this._ensureEvents());
    }
}

// Mirrors Node's actual `events` module shape: `module.exports` is the `EventEmitter`
// constructor itself, with `EventEmitter` also attached as a self-referencing static
// property (`require('events').EventEmitter === require('events')`) — packages commonly
// destructure `const { EventEmitter } = require('events')` rather than using the module's
// default export directly (e.g. agent-base: `class Agent extends
// require('events').EventEmitter`), which would otherwise resolve to `undefined` against a
// bare class default export.
EventEmitter.EventEmitter = EventEmitter;

export default EventEmitter;

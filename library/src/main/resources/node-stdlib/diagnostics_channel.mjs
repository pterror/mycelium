// Minimal Node.js-compatible `diagnostics_channel` module, served by Mycelium as the
// synthetic core module resolved for `import ... from "diagnostics_channel"` /
// `"node:diagnostics_channel"`.
//
// This is a functional (not a stubbed-out no-op) implementation of the pub/sub surface —
// `subscribe`/`publish`/`hasSubscribers` genuinely deliver messages to subscribers — since
// that's cheap to provide correctly and some libraries (e.g. pino's diagnostics-channel
// based transport hooks) depend on messages actually being observable, not just on the API
// existing. What's *not* implemented is Node's actual runtime instrumentation (there are no
// built-in channels firing for fs/http/etc. — nothing publishes to them automatically);
// channels only carry whatever a caller explicitly `publish`es.

const channels = new Map();

export class Channel {
    constructor(name) {
        this.name = name;
        this._subscribers = [];
    }

    get hasSubscribers() {
        return this._subscribers.length > 0;
    }

    subscribe(onMessage) {
        this._subscribers.push(onMessage);
    }

    unsubscribe(onMessage) {
        const idx = this._subscribers.indexOf(onMessage);
        if (idx === -1) return false;
        this._subscribers.splice(idx, 1);
        return true;
    }

    publish(message) {
        for (const subscriber of this._subscribers.slice()) {
            try {
                subscriber(message, this.name);
            } catch (e) {
                // Node reports subscriber errors asynchronously (via a nextTick throw)
                // rather than letting them interrupt the publisher; approximate that by
                // scheduling the rethrow as a microtask instead of throwing synchronously
                // here, which would otherwise abort sibling subscribers and the publisher.
                Promise.resolve().then(() => {
                    throw e;
                });
            }
        }
    }

    bindStore(store, transform) {
        this._store = store;
        this._transform = transform;
    }

    unbindStore() {
        this._store = undefined;
        this._transform = undefined;
    }

    runStores(message, fn, thisArg, ...args) {
        return fn.apply(thisArg, args);
    }
}

export function channel(name) {
    let ch = channels.get(name);
    if (!ch) {
        ch = new Channel(name);
        channels.set(name, ch);
    }
    return ch;
}

export function hasSubscribers(name) {
    const ch = channels.get(name);
    return !!ch && ch.hasSubscribers;
}

export function subscribe(name, onMessage) {
    channel(name).subscribe(onMessage);
}

export function unsubscribe(name, onMessage) {
    const ch = channels.get(name);
    return !!ch && ch.unsubscribe(onMessage);
}

class TracingChannel {
    constructor(nameOrChannels) {
        if (typeof nameOrChannels === "string") {
            this.start = channel(`${nameOrChannels}:start`);
            this.end = channel(`${nameOrChannels}:end`);
            this.asyncStart = channel(`${nameOrChannels}:asyncStart`);
            this.asyncEnd = channel(`${nameOrChannels}:asyncEnd`);
            this.error = channel(`${nameOrChannels}:error`);
        } else {
            this.start = nameOrChannels.start;
            this.end = nameOrChannels.end;
            this.asyncStart = nameOrChannels.asyncStart;
            this.asyncEnd = nameOrChannels.asyncEnd;
            this.error = nameOrChannels.error;
        }
    }

    get hasSubscribers() {
        return (
            this.start.hasSubscribers ||
            this.end.hasSubscribers ||
            this.asyncStart.hasSubscribers ||
            this.asyncEnd.hasSubscribers ||
            this.error.hasSubscribers
        );
    }

    subscribe(handlers) {
        if (handlers.start) this.start.subscribe(handlers.start);
        if (handlers.end) this.end.subscribe(handlers.end);
        if (handlers.asyncStart) this.asyncStart.subscribe(handlers.asyncStart);
        if (handlers.asyncEnd) this.asyncEnd.subscribe(handlers.asyncEnd);
        if (handlers.error) this.error.subscribe(handlers.error);
    }

    unsubscribe(handlers) {
        if (handlers.start) this.start.unsubscribe(handlers.start);
        if (handlers.end) this.end.unsubscribe(handlers.end);
        if (handlers.asyncStart) this.asyncStart.unsubscribe(handlers.asyncStart);
        if (handlers.asyncEnd) this.asyncEnd.unsubscribe(handlers.asyncEnd);
        if (handlers.error) this.error.unsubscribe(handlers.error);
    }

    traceSync(fn, context, thisArg, ...args) {
        const ctx = context || {};
        this.start.publish(ctx);
        try {
            const result = fn.apply(thisArg, args);
            ctx.result = result;
            return result;
        } catch (err) {
            ctx.error = err;
            this.error.publish(ctx);
            throw err;
        } finally {
            this.end.publish(ctx);
        }
    }

    tracePromise(fn, context, thisArg, ...args) {
        const ctx = context || {};
        this.start.publish(ctx);
        this.asyncStart.publish(ctx);
        return Promise.resolve()
            .then(() => fn.apply(thisArg, args))
            .then(
                (result) => {
                    ctx.result = result;
                    return result;
                },
                (err) => {
                    ctx.error = err;
                    this.error.publish(ctx);
                    throw err;
                }
            )
            .finally(() => {
                this.asyncEnd.publish(ctx);
                this.end.publish(ctx);
            });
    }

    traceCallback(fn, position, context, thisArg, ...args) {
        const ctx = context || {};
        const pos = position === undefined ? args.length - 1 : position;
        const originalCallback = args[pos];
        this.start.publish(ctx);
        args[pos] = (err, ...results) => {
            if (err) ctx.error = err;
            this.asyncStart.publish(ctx);
            try {
                if (typeof originalCallback === "function") {
                    return originalCallback.apply(this, [err, ...results]);
                }
            } finally {
                this.asyncEnd.publish(ctx);
            }
        };
        try {
            return fn.apply(thisArg, args);
        } catch (err) {
            ctx.error = err;
            this.error.publish(ctx);
            throw err;
        } finally {
            this.end.publish(ctx);
        }
    }
}

export function tracingChannel(nameOrChannels) {
    return new TracingChannel(nameOrChannels);
}

export default {
    channel,
    hasSubscribers,
    subscribe,
    unsubscribe,
    tracingChannel,
    Channel,
};

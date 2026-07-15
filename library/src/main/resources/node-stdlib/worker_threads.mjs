// Minimal Node.js-compatible `worker_threads` module, served by Mycelium as the synthetic
// core module resolved for `import ... from "worker_threads"` / `"node:worker_threads"`.
//
// Mycelium runs guest JS on a single GraalJS context/thread (see Mycelium.kt) — there is no
// real worker-thread pool, so this is a stub: enough surface area for packages that only
// import this module to branch on `isMainThread` (always true here, since there's only
// ever one "thread") to load without throwing. Actually spawning a `Worker` — a second,
// isolated GraalJS context running a different module concurrently — isn't supported, so
// the constructor throws a clear, actionable error rather than silently pretending to
// work.

import { EventEmitter } from "events";

export const isMainThread = true;
export const parentPort = null;
export const workerData = null;
export const threadId = 0;
export const resourceLimits = {};
export const SHARE_ENV = Symbol.for("nodejs.worker_threads.SHARE_ENV");

export class MessagePort extends EventEmitter {
    constructor() {
        super();
        this._peer = null;
        this._closed = false;
    }

    postMessage(value, _transferList) {
        if (this._closed || !this._peer || this._peer._closed) return;
        const peer = this._peer;
        Promise.resolve().then(() => {
            if (peer._closed) return;
            peer.emit("message", value);
        });
    }

    close() {
        if (this._closed) return;
        this._closed = true;
        Promise.resolve().then(() => this.emit("close"));
    }

    start() {
        // No-op: messages are always delivered (paused/resumed reading isn't modeled).
    }

    ref() {
        return this;
    }

    unref() {
        return this;
    }
}

export class MessageChannel {
    constructor() {
        this.port1 = new MessagePort();
        this.port2 = new MessagePort();
        this.port1._peer = this.port2;
        this.port2._peer = this.port1;
    }
}

export class BroadcastChannel extends EventEmitter {
    constructor(name) {
        super();
        this.name = String(name);
        this._closed = false;
    }

    postMessage(_value) {
        // No other threads exist to broadcast to in this runtime, so this is a no-op —
        // matches the semantics (nothing happens) since a lone main thread never has
        // other BroadcastChannel listeners on the same channel to reach.
    }

    close() {
        this._closed = true;
    }

    ref() {
        return this;
    }

    unref() {
        return this;
    }
}

export class Worker extends EventEmitter {
    constructor(_filename, _options) {
        super();
        throw new Error(
            "Worker threads are not yet supported in Mycelium — Mycelium runs guest JS on a " +
                "single execution context, and Worker requires spawning an independent one."
        );
    }
}

export function markAsUntransferable(_object) {
    // No-op: there's no transfer/structured-clone boundary to enforce here.
}

export function moveMessagePortToContext(_port, _context) {
    throw new Error("mycelium: worker_threads.moveMessagePortToContext is not supported");
}

export function receiveMessageOnPort(port) {
    return undefined;
}

export default {
    isMainThread, parentPort, workerData, threadId, resourceLimits, SHARE_ENV,
    MessagePort, MessageChannel, BroadcastChannel, Worker,
    markAsUntransferable, moveMessagePortToContext, receiveMessageOnPort,
};

// Minimal `inspector` module stub, served by Mycelium as the synthetic core module
// resolved for `import ... from "inspector"` / `"node:inspector"`. Real Node's inspector
// wires the V8 Inspector Protocol (Chrome DevTools Protocol) to an actual debugger session;
// GraalJS has its own separate debugging story (Chrome DevTools Protocol support via
// `--inspect` at the GraalVM launcher level, not something a guest script can drive through
// this API), so this only provides the API shape — `Session#post` resolves/rejects with a
// "not supported" error rather than talking to a real protocol backend.

import { EventEmitter } from "events";

export class Session extends EventEmitter {
    connect() {
        // no-op: no real backend to connect to.
    }

    connectToMainThread() {
        // no-op: no real backend to connect to.
    }

    post(method, params, callback) {
        const cb = typeof params === "function" ? params : callback;
        const err = new Error(`mycelium: inspector.Session#post('${method}') is not supported`);
        if (typeof cb === "function") {
            Promise.resolve().then(() => cb(err));
            return undefined;
        }
        return Promise.reject(err);
    }

    disconnect() {
        // no-op
    }
}

export function open(port, host, wait) {
    return `ws://127.0.0.1:9229/mycelium-stub`;
}

export function close() {
    // no-op
}

export function url() {
    return undefined;
}

export function waitForDebugger() {
    // no-op: never actually pauses since there's no real inspector session to wait on.
}

export const console = globalThis.console;

export const Network = {
    requestWillBeSent() {},
    responseReceived() {},
    loadingFailed() {},
    loadingFinished() {},
};

export const NetworkResources = {};

export const DOMStorage = {};

export default {
    open,
    close,
    url,
    waitForDebugger,
    console,
    Session,
    Network,
    NetworkResources,
    DOMStorage,
};

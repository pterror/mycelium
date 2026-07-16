// Minimal `inspector/promises` module stub, served by Mycelium as the synthetic core
// module resolved for `import ... from "inspector/promises"` / `"node:inspector/promises"`.
// Same backend (lack thereof) as inspector.mjs — this only differs from it in that
// `Session#post` always returns a Promise (no callback form), matching real Node's
// promises-flavored inspector API.

import { EventEmitter } from "events";
import * as inspectorSync from "inspector";

export class Session extends EventEmitter {
    connect() {
        // no-op: no real backend to connect to.
    }

    connectToMainThread() {
        // no-op: no real backend to connect to.
    }

    post(method, params) {
        return Promise.reject(new Error(`mycelium: inspector/promises.Session#post('${method}') is not supported`));
    }

    disconnect() {
        // no-op
    }
}

export const open = inspectorSync.open;
export const close = inspectorSync.close;
export const url = inspectorSync.url;
export const waitForDebugger = inspectorSync.waitForDebugger;
export const console = inspectorSync.console;
export const Network = inspectorSync.Network;
export const NetworkResources = inspectorSync.NetworkResources;
export const DOMStorage = inspectorSync.DOMStorage;

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

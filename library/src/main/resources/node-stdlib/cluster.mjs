// Minimal Node.js-compatible `cluster` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "cluster"` / `"node:cluster"`.
//
// Real `cluster` forks the current script as N worker processes (via `child_process.fork()`)
// and load-balances incoming connections across them. Mycelium runs everything as a single
// GraalJS context in a single JVM process — there's no multi-process runtime to fork into,
// and `child_process.mjs`'s `fork()` itself already throws ("requires Node.js IPC, not yet
// supported"). Rather than leave `cluster` unresolvable (breaking any package that merely
// checks `cluster.isPrimary` to decide whether to run its single-process code path), this
// module exports the real API surface configured as if the current process were *always* the
// primary with zero workers: `isPrimary`/`isMaster` are `true`, `isWorker` is `false`,
// `worker` is `undefined`, and `workers` is permanently empty. That's precisely the branch
// most cluster-aware packages fall into for the common "not actually clustered" case, so code
// shaped like:
//
//   if (cluster.isPrimary) { runSingleProcess(); }
//   else { runWorker(); }
//
// keeps working. `fork()` throws — same as `child_process.fork()` — rather than silently
// pretending to spawn a worker that will never send events.

import { EventEmitter } from "events";

class Worker extends EventEmitter {
    constructor(id) {
        super();
        this.id = id;
        this.process = undefined;
        this.state = "none";
        this.isConnected = () => false;
        this.isDead = () => true;
        this.exitedAfterDisconnect = false;
    }

    send() {
        throw new Error("mycelium: cluster worker IPC is not supported (single-process runtime)");
    }

    kill() {
        return this;
    }

    disconnect() {
        return this;
    }
}

class Cluster extends EventEmitter {
    constructor() {
        super();
        this.isPrimary = true;
        this.isMaster = true;
        this.isWorker = false;
        this.worker = undefined;
        this.workers = {};
        this.SCHED_NONE = 1;
        this.SCHED_RR = 2;
        this.schedulingPolicy = this.SCHED_RR;
        this.settings = {
            exec: undefined,
            args: [],
            silent: false,
        };
    }

    setupPrimary(settings) {
        this.settings = Object.assign({}, this.settings, settings || {});
        return this;
    }

    // Node kept `setupMaster` as a deprecated alias of `setupPrimary` — mirrored here since
    // packages predating the rename may still call it.
    setupMaster(settings) {
        return this.setupPrimary(settings);
    }

    fork() {
        throw new Error(
            "mycelium: cluster.fork() requires multi-process IPC, not supported in Mycelium's " +
                "single-process runtime — cluster.isPrimary is always true, so clustered packages " +
                "should already be taking the non-forking code path"
        );
    }

    disconnect(callback) {
        if (typeof callback === "function") Promise.resolve().then(callback);
        return this;
    }
}

const cluster = new Cluster();
// Real Node also exposes the `Worker` constructor on the module namespace.
cluster.Worker = Worker;

export const isPrimary = cluster.isPrimary;
export const isMaster = cluster.isMaster;
export const isWorker = cluster.isWorker;
export const worker = cluster.worker;
export const workers = cluster.workers;
export const SCHED_NONE = cluster.SCHED_NONE;
export const SCHED_RR = cluster.SCHED_RR;
export const schedulingPolicy = cluster.schedulingPolicy;
export const settings = cluster.settings;
export const setupPrimary = cluster.setupPrimary.bind(cluster);
export const setupMaster = cluster.setupMaster.bind(cluster);
export const fork = cluster.fork.bind(cluster);
export const disconnect = cluster.disconnect.bind(cluster);
export const on = cluster.on.bind(cluster);
export const once = cluster.once.bind(cluster);
export const off = cluster.off.bind(cluster);
export const emit = cluster.emit.bind(cluster);
export const addListener = cluster.addListener.bind(cluster);
export const removeListener = cluster.removeListener.bind(cluster);
export { Worker };

export default cluster;

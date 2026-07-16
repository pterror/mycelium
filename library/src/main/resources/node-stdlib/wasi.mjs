// Minimal `wasi` module stub, served by Mycelium as the synthetic core module resolved for
// `import ... from "wasi"` / `"node:wasi"`. Real Node's WASI class wires a WebAssembly
// instance's imports to actual OS syscalls (fd read/write, clock, random, etc.); Mycelium
// doesn't run WebAssembly modules at all yet, so this only provides the constructor/API
// shape — `wasiImport` is populated with no-op stand-ins so a caller that merely feature-
// detects `new WASI(...)` succeeding, or wires `wasiImport` into a WebAssembly.instantiate
// call it never actually runs, doesn't immediately break.

export class WASI {
    constructor(options) {
        const opts = options || {};
        this.args = opts.args || [];
        this.env = opts.env || {};
        this.preopens = opts.preopens || {};
        this.returnOnExit = opts.returnOnExit !== false;
        this.version = opts.version || "preview1";
        this.wasiImport = {};
    }

    start(instance) {
        throw new Error("mycelium: WASI#start() is not supported (WebAssembly execution is not implemented)");
    }

    initialize(instance) {
        throw new Error("mycelium: WASI#initialize() is not supported (WebAssembly execution is not implemented)");
    }

    getImportObject() {
        return { wasi_snapshot_preview1: this.wasiImport };
    }
}

export default { WASI };

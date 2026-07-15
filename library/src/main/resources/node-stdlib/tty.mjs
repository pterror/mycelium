// Minimal Node.js-compatible `tty` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "tty"` / `"node:tty"`.
//
// Mycelium's process is never attached to a real terminal, so `isatty()` always reports
// false and the stream classes are stubs — just enough surface area for packages like
// `debug` and `chalk` (which probe `tty.isTTY`/`process.stdout.isTTY` to decide whether to
// emit ANSI color codes) and their transitive dependents (e.g. `express` via
// `body-parser` -> `debug`) to load and run without throwing, not a real TTY
// implementation.

import { EventEmitter } from "events";

export function isatty(_fd) {
    return false;
}

export class ReadStream extends EventEmitter {
    constructor(fd) {
        super();
        this.fd = fd;
        this.isTTY = false;
        this.isRaw = false;
    }

    setRawMode(mode) {
        this.isRaw = !!mode;
        return this;
    }

    isTTYFn() {
        return false;
    }
}

export class WriteStream extends EventEmitter {
    constructor(fd) {
        super();
        this.fd = fd;
        this.isTTY = false;
        this.columns = undefined;
        this.rows = undefined;
    }

    hasColors(_count, _env) {
        return false;
    }

    getColorDepth(_env) {
        return 1;
    }

    getWindowSize() {
        return [this.columns, this.rows];
    }

    cursorTo(_x, _y, callback) {
        if (typeof callback === "function") callback();
        return true;
    }

    moveCursor(_dx, _dy, callback) {
        if (typeof callback === "function") callback();
        return true;
    }

    clearLine(_dir, callback) {
        if (typeof callback === "function") callback();
        return true;
    }

    clearScreenDown(callback) {
        if (typeof callback === "function") callback();
        return true;
    }
}

export default { isatty, ReadStream, WriteStream };

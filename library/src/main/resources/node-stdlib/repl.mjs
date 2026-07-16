// Minimal `repl` module stub, served by Mycelium as the synthetic core module resolved for
// `import ... from "repl"` / `"node:repl"`. Provides the API shape real Node's REPL exposes
// — `start()` returning an EventEmitter-based `REPLServer` with `context`/`commands` and the
// usual lifecycle events — but doesn't actually implement an interactive read-eval-print
// loop (Mycelium has no interactive terminal session backing an embedded guest context the
// way Node's own REPL wraps `vm`+`readline`); `eval`/`write` are provided as no-op-ish hooks
// callers can override, not a working evaluator.

import { EventEmitter } from "events";

export const REPL_MODE_SLOPPY = "sloppy";
export const REPL_MODE_STRICT = "strict";

export class Recoverable extends SyntaxError {
    constructor(err) {
        super(err && err.message ? err.message : "Recoverable: incomplete input");
        this.err = err;
    }
}

export class REPLServer extends EventEmitter {
    constructor(options) {
        super();
        const opts = typeof options === "string" ? { prompt: options } : options || {};
        this.context = Object.create(globalThis);
        this.commands = {};
        this.lines = [];
        this.useColors = !!opts.useColors;
        this.terminal = !!opts.terminal;
        this._prompt = opts.prompt !== undefined ? opts.prompt : "> ";
        this._eval = typeof opts.eval === "function" ? opts.eval : null;
        this._writer = typeof opts.writer === "function" ? opts.writer : writer;
        this.replMode = opts.replMode || REPL_MODE_SLOPPY;
        this._closed = false;
    }

    defineCommand(keyword, cmd) {
        const action = typeof cmd === "function" ? cmd : cmd.action;
        this.commands[keyword] = { help: cmd.help || "", action };
        return this;
    }

    displayPrompt() {
        this.emit("prompt");
        return this;
    }

    setPrompt(prompt) {
        this._prompt = prompt;
        return this;
    }

    write(data) {
        return this._writer(data);
    }

    clearBufferedCommand() {
        this.lines = [];
    }

    close() {
        if (this._closed) return;
        this._closed = true;
        Promise.resolve().then(() => this.emit("close"));
    }

    pause() {
        return this;
    }

    resume() {
        return this;
    }
}

export function writer(output) {
    return typeof output === "string" ? output : String(output);
}

export function start(options) {
    const server = new REPLServer(options);
    Promise.resolve().then(() => server.emit("exit"));
    return server;
}

export function isValidSyntax(code) {
    // Best-effort: real Node parses via V8 and reports specific SyntaxErrors as
    // "recoverable" (incomplete input, e.g. an unterminated multi-line statement) vs. genuine
    // syntax errors. Mycelium has no cheap standalone JS parser to call here without actually
    // evaluating the code, so this always reports valid — callers that need this for
    // multi-line input buffering should treat every submission as complete.
    return true;
}

export default {
    start,
    writer,
    REPLServer,
    REPL_MODE_SLOPPY,
    REPL_MODE_STRICT,
    Recoverable,
    isValidSyntax,
};

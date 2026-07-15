// Minimal Node.js-compatible `readline` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "readline"` / `"node:readline"` (and the
// `readline/promises` variant below) — needed by CLI tooling (inquirer and friends) that
// prompts on stdin/stdout.
//
// Mycelium's process is never attached to a real terminal (see tty.mjs), so there's no
// raw-mode/keypress-event machinery here — this is a *line*-oriented reader backed by a
// plain `java.io.BufferedReader` wrapping `System.in`, which is a faithful enough model
// for the common case these packages actually rely on: write a prompt, block until a full
// line comes in, hand it back. `input`/`output` options are honored when they expose a
// `.write()` (output) or an underlying Java stream (input via `_javaInputStream`) —
// otherwise this falls back to `System.in`/`System.out` directly.
//
// Threading model: reading is driven by `globalThis.setTimeout`'s own polling loop
// (TIMERS_INIT_SCRIPT in Mycelium.kt), *not* a directly-created `java.lang.Thread` —
// child_process.mjs's `_startIoThread` doc comment explains why: a `Thread` created and
// started directly from a guest callback isn't guaranteed a clean shot at (re-)entering
// the shared GraalJS context, and losing that race throws "Multi threaded access
// requested ... but is not allowed" before a single line of the thread's own body runs,
// silently killing it. `setTimeout`'s machinery (a fresh one-shot daemon thread per
// scheduled callback, each of which sleeps for its full delay *before* ever touching the
// context) doesn't hit this in practice, so line-reading here is a non-blocking poll —
// `BufferedReader#ready()` (true once at least one buffered char is available without
// blocking) gates each `read()` call — ticking on a short `setTimeout` interval, exactly
// like child_process.mjs's `pollStream`/`tick`.

import { EventEmitter } from "events";

const JSystem = Java.type("java.lang.System");
const JBufferedReader = Java.type("java.io.BufferedReader");
const JInputStreamReader = Java.type("java.io.InputStreamReader");
const JCharset = Java.type("java.nio.charset.StandardCharsets");

function nextTick(fn) {
    Promise.resolve().then(fn);
}

function defaultWrite(str) {
    JSystem.out.print(str);
}

class Interface extends EventEmitter {
    constructor(options = {}) {
        super();
        if (typeof options === "object" && options !== null && typeof options.input === "undefined" &&
            typeof options.write === "function") {
            // Some callers pass a stream directly as the first argument (Node's legacy
            // `createInterface(input, output, completer)` form) — normalized by
            // createInterface before construction, so this branch shouldn't normally be
            // hit, but guard anyway rather than silently misbehaving.
            options = { input: options };
        }

        this.terminal = !!options.terminal;
        this._output = options.output;
        this._completer = options.completer;
        this._prompt = options.prompt !== undefined ? options.prompt : "> ";
        this._crlfDelay = options.crlfDelay;
        this._history = Array.isArray(options.history) ? options.history.slice() : [];
        this.historySize = options.historySize !== undefined ? options.historySize : 30;
        this._closed = false;
        this._paused = false;
        this._pendingQuestions = [];
        this._javaReader = null;
        this._lineBuffer = "";
        this._polling = false;

        this._initReader(options.input);
        // Deferred one tick (a same-thread microtask, not a real background thread) so
        // that whatever `.on('line', ...)`/`.question(...)` calls immediately follow
        // `createInterface(...)` in the caller's own synchronous code have already run
        // before the first poll can possibly deliver anything — same rationale as
        // child_process.mjs's `_startIoThread` scheduling its first tick via a zero-delay
        // `setTimeout` rather than ticking synchronously inline.
        globalThis.setTimeout(() => this._poll(), 0);
    }

    _initReader(input) {
        try {
            // `input` may be a Java InputStream-like guest wrapper (e.g. via a Node
            // stream that exposes `_javaInputStream`), or absent entirely — in which
            // case default to the process's real stdin.
            const javaStream = input && input._javaInputStream ? input._javaInputStream : JSystem.in;
            this._javaReader = new JBufferedReader(new JInputStreamReader(javaStream, JCharset.UTF_8));
        } catch (e) {
            this._javaReader = null;
        }
    }

    _write(str) {
        if (this._output && typeof this._output.write === "function") {
            this._output.write(str);
        } else {
            defaultWrite(str);
        }
    }

    // One non-blocking poll tick: drains every char currently buffered/available on the
    // reader (via `ready()`, which never blocks) into `_lineBuffer`, delivering a line
    // each time a `\n` (with an optional preceding `\r` stripped) is seen, then
    // reschedules itself — see the module doc comment above for why this is a poll loop
    // rather than a blocking `readLine()` call on a dedicated thread.
    _poll() {
        if (this._closed || !this._javaReader) return;
        if (this._paused) {
            globalThis.setTimeout(() => this._poll(), 20);
            return;
        }
        try {
            while (this._javaReader.ready()) {
                const code = this._javaReader.read();
                if (code === -1) {
                    this.close();
                    return;
                }
                if (code === 10 /* \n */) {
                    let line = this._lineBuffer;
                    if (line.endsWith("\r")) line = line.slice(0, -1);
                    this._lineBuffer = "";
                    this._deliverLine(line);
                    if (this._closed) return;
                } else {
                    this._lineBuffer += String.fromCharCode(code);
                }
            }
        } catch (e) {
            this.close();
            return;
        }
        globalThis.setTimeout(() => this._poll(), 20);
    }

    _deliverLine(line) {
        if (line.length > 0) {
            this._history.unshift(line);
            if (this._history.length > this.historySize) this._history.length = this.historySize;
            this.emit("history", this._history);
        }
        const waiter = this._pendingQuestions.shift();
        if (waiter) {
            waiter(line);
        } else {
            this.emit("line", line);
        }
    }

    setPrompt(prompt) {
        this._prompt = prompt;
    }

    getPrompt() {
        return this._prompt;
    }

    prompt(preserveCursor) {
        if (this._closed) return;
        this._write(this._prompt);
    }

    question(query, optionsOrCallback, maybeCallback) {
        let callback = optionsOrCallback;
        if (typeof optionsOrCallback !== "function") callback = maybeCallback;
        this._write(query);
        this._pendingQuestions.push((answer) => {
            if (typeof callback === "function") callback(answer);
        });
    }

    write(data, key) {
        if (data !== undefined && data !== null) {
            this._write(data);
        }
        // Keypress simulation (the `key` argument) isn't implemented — there's no raw
        // mode / keypress-event machinery here (see the module doc comment above).
        return true;
    }

    pause() {
        if (this._paused) return this;
        this._paused = true;
        this.emit("pause");
        return this;
    }

    resume() {
        if (!this._paused) return this;
        this._paused = false;
        this.emit("resume");
        return this;
    }

    close() {
        if (this._closed) return;
        this._closed = true;
        for (const waiter of this._pendingQuestions.splice(0)) waiter(null);
        this.emit("close");
    }

    // Push-based under the hood (lines are read and `emit('line', ...)`'d continuously as
    // soon as they're available, whether or not anything is currently awaiting this
    // iterator — see the module doc comment for why reading can't be made to wait on a
    // consumer without breaking the Promise-based `question()` path), so a line emitted
    // between two `next()` calls is simply missed by the iterator, same as it would be by
    // a `.on('line', ...)` listener registered too late. Good enough for the common
    // `for await (const line of rl)` loop that starts consuming immediately.
    [Symbol.asyncIterator]() {
        const self = this;
        return {
            next() {
                if (self._closed) return Promise.resolve({ value: undefined, done: true });
                return new Promise((resolve) => {
                    const onLine = (l) => {
                        self.removeListener("close", onClose);
                        resolve({ value: l, done: false });
                    };
                    const onClose = () => {
                        self.removeListener("line", onLine);
                        resolve({ value: undefined, done: true });
                    };
                    self.once("line", onLine);
                    self.once("close", onClose);
                });
            },
            [Symbol.asyncIterator]() {
                return this;
            },
        };
    }
}

function normalizeCreateInterfaceArgs(args) {
    if (args.length === 1 && typeof args[0] === "object" && args[0] !== null) {
        return args[0];
    }
    // Legacy positional form: createInterface(input, output, completer, terminal)
    const [input, output, completer, terminal] = args;
    return { input, output, completer, terminal };
}

export function createInterface(...args) {
    const options = normalizeCreateInterfaceArgs(args);
    return new Interface(options);
}

export function emitKeypressEvents(_stream, _iface) {
    // No-op: there's no raw-mode keypress machinery here (see module doc comment).
}

export function clearLine(_stream, _dir, callback) {
    if (typeof callback === "function") nextTick(callback);
    return true;
}

export function clearScreenDown(_stream, callback) {
    if (typeof callback === "function") nextTick(callback);
    return true;
}

export function cursorTo(_stream, _x, _y, callback) {
    const cb = typeof _y === "function" ? _y : callback;
    if (typeof cb === "function") nextTick(cb);
    return true;
}

export function moveCursor(_stream, _dx, _dy, callback) {
    if (typeof callback === "function") nextTick(callback);
    return true;
}

export { Interface, normalizeCreateInterfaceArgs as _normalizeCreateInterfaceArgs };

export default {
    createInterface, Interface, emitKeypressEvents, clearLine, clearScreenDown, cursorTo, moveCursor,
};

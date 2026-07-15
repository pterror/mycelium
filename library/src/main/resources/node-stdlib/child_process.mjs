// Minimal Node.js-compatible `child_process` module, served by Mycelium as the synthetic
// core module resolved for `import ... from "child_process"` / `"node:child_process"`.
// Backed by `java.lang.ProcessBuilder` — there's no real OS-level event loop here (same
// caveat as http.mjs/net.mjs), so `spawn()`'s live stdout/stderr streaming is driven by
// dedicated daemon `java.lang.Thread`s that block on `InputStream.read()` and call back
// into the shared GraalJS context to `emit('data', ...)`, exactly like net.mjs's socket
// reader thread. That's safe under the same rule those modules already rely on: GraalJS
// transfers context ownership to whichever thread calls into a guest function, as long as
// no other thread is concurrently *inside* the context at the same moment.
//
// The synchronous APIs (`execSync`/`execFileSync`/`spawnSync`) never spawn extra threads —
// everything happens as plain blocking Java I/O calls made directly from the calling JS
// thread, which needs no context-reentrancy reasoning at all. To avoid the classic
// ProcessBuilder pipe deadlock (reading stdout to completion before touching stderr can
// hang if the child fills the stderr pipe buffer while blocked writing to a full stdout
// pipe) a real implementation would drain both streams concurrently; this one reads stdout
// fully and then stderr fully, same simplification already accepted elsewhere in this
// codebase (see InterceptingFileSystem.kt's computePythonSearchPaths/
// computeRubySearchPaths, which have the same "read one stream, then waitFor" shape) —
// fine for the typical small-output command-running use cases this targets, but a command
// that writes many megabytes to stderr while nothing drains it could still hang.

import { EventEmitter } from "events";

const JProcessBuilder = Java.type("java.lang.ProcessBuilder");
const JRedirect = Java.type("java.lang.ProcessBuilder$Redirect");
const JFile = Java.type("java.io.File");
const JByteArray = Java.type("byte[]");
const JByteArrayOutputStream = Java.type("java.io.ByteArrayOutputStream");
const JCharset = Java.type("java.nio.charset.StandardCharsets");
const JArrayList = Java.type("java.util.ArrayList");
const JTimeUnit = Java.type("java.util.concurrent.TimeUnit");

function nextTick(fn) {
    Promise.resolve().then(fn);
}

// ---- byte <-> JS helpers (same shape as net.mjs/http.mjs) ----

function toJByteArray(data) {
    if (typeof data === "string") {
        const buf = JCharset.UTF_8.encode(data);
        const len = buf.remaining();
        const bytes = new JByteArray(len);
        buf.get(bytes);
        return bytes;
    }
    const bytes = new JByteArray(data.length);
    for (let i = 0; i < data.length; i++) {
        const v = data[i];
        bytes[i] = v > 127 ? v - 256 : v;
    }
    return bytes;
}

function fromJByteArray(jBytes, len) {
    const n = len === undefined ? jBytes.length : len;
    const arr = new Uint8Array(n);
    for (let i = 0; i < n; i++) arr[i] = jBytes[i] & 0xff;
    return arr;
}

function concatU8(chunks) {
    let total = 0;
    for (const c of chunks) total += c.length;
    const out = new Uint8Array(total);
    let offset = 0;
    for (const c of chunks) {
        out.set(c, offset);
        offset += c.length;
    }
    return out;
}

// `encoding === undefined`/`"buffer"` returns a `Buffer`; anything else is decoded to a
// string via `Buffer#toString(encoding)`. `defaultEncoding` lets callback-style APIs
// (exec/execFile, which default to `'utf8'`) differ from the *Sync APIs (which default to
// raw `Buffer`, matching real Node).
function encodeOutput(u8, encoding, defaultEncoding) {
    const enc = encoding || defaultEncoding;
    const buf = globalThis.Buffer.from(u8);
    if (!enc || enc === "buffer") return buf;
    return buf.toString(enc);
}

// Turns a chunk of raw bytes read off a child's stdout/stderr into whatever the
// `ChildReadable` should emit: a `Buffer` by default, or a decoded string once
// `setEncoding()` has been called on it (mirrors real Node's Readable behavior).
function emitChunk(readable, jBuf, n) {
    const chunk = fromJByteArray(jBuf, n);
    const data = readable._encoding
        ? globalThis.Buffer.from(chunk).toString(readable._encoding)
        : globalThis.Buffer.from(chunk);
    readable.emit("data", data);
}

// Non-blocking: reads only what's already buffered (`available()`), so the single IO
// thread driving a ChildProcess (see ChildProcess#_startIoThread) never blocks inside a
// `read()` call and can keep cycling between stdout/stderr/the timeout deadline. Returns
// whether anything was actually read, so the caller can skip its poll-interval sleep when
// there's more to drain right away.
function pollStream(inStream, readable, jBuf) {
    let avail;
    try {
        avail = inStream.available();
    } catch (e) {
        return false;
    }
    if (avail <= 0) return false;
    let n;
    try {
        n = inStream.read(jBuf, 0, Math.min(jBuf.length, avail));
    } catch (e) {
        return false;
    }
    if (n <= 0) return false;
    emitChunk(readable, jBuf, n);
    return true;
}

// Blocking full drain, used only once the child process has already exited — at that
// point the OS pipe holds a fixed, finite amount of remaining data and reading it hits a
// real EOF quickly rather than blocking indefinitely.
function drainStream(inStream, readable, jBuf) {
    let n;
    try {
        while ((n = inStream.read(jBuf)) !== -1) {
            emitChunk(readable, jBuf, n);
        }
    } catch (e) {
        // ignore — best-effort final drain
    }
}

function readAllBytesSync(inputStream) {
    if (inputStream === null || inputStream === undefined) return new JByteArray(0);
    const baos = new JByteArrayOutputStream();
    const chunk = new JByteArray(8192);
    let n;
    while ((n = inputStream.read(chunk, 0, chunk.length)) !== -1) {
        baos.write(chunk, 0, n);
    }
    return baos.toByteArray();
}

// ---- command / shell helpers ----

function defaultShell() {
    const plat = globalThis.process && globalThis.process.platform;
    return plat === "win32" ? "cmd.exe" : "/bin/sh";
}

function shellCommandArgs(shell, command) {
    if (String(shell).toLowerCase().indexOf("cmd.exe") !== -1) {
        return [shell, "/d", "/s", "/c", command];
    }
    return [shell, "-c", command];
}

function toStringArgs(args) {
    if (!args) return [];
    return args.map((a) => String(a));
}

function buildCommandList(file, args) {
    const list = new JArrayList();
    list.add(String(file));
    for (const a of args) list.add(String(a));
    return list;
}

function applyCwd(pb, cwd) {
    if (cwd) pb.directory(new JFile(String(cwd)));
}

function applyEnv(pb, env) {
    const source = env || (globalThis.process && globalThis.process.env);
    if (!source) return;
    const jEnv = pb.environment();
    jEnv.clear();
    for (const key in source) {
        const v = source[key];
        if (v !== undefined) jEnv.put(String(key), String(v));
    }
}

function devNullPath() {
    return (globalThis.process && globalThis.process.platform) === "win32" ? "NUL" : "/dev/null";
}

// Accepts the string form (`'pipe'`/`'ignore'`/`'inherit'`, applied to all three of
// stdin/stdout/stderr) or the array form (`[stdin, stdout, stderr, ...]`) — only the first
// three slots are honored; extra fds (e.g. `'ipc'` for `fork()`) aren't supported.
function normalizeStdio(stdio) {
    if (stdio === undefined || stdio === null) return ["pipe", "pipe", "pipe"];
    if (typeof stdio === "string") return [stdio, stdio, stdio];
    if (Array.isArray(stdio)) {
        const result = [];
        for (let i = 0; i < 3; i++) {
            const v = stdio[i];
            result.push(v === undefined || v === null ? "pipe" : v);
        }
        return result;
    }
    return ["pipe", "pipe", "pipe"];
}

function applyStdio(pb, stdioSpec) {
    const [inSpec, outSpec, errSpec] = stdioSpec;
    if (inSpec === "inherit") pb.redirectInput(JRedirect.INHERIT);
    else if (inSpec === "ignore") pb.redirectInput(JRedirect.from(new JFile(devNullPath())));

    if (outSpec === "inherit") pb.redirectOutput(JRedirect.INHERIT);
    else if (outSpec === "ignore") pb.redirectOutput(JRedirect.to(new JFile(devNullPath())));

    if (errSpec === "inherit") pb.redirectError(JRedirect.INHERIT);
    else if (errSpec === "ignore") pb.redirectError(JRedirect.to(new JFile(devNullPath())));
}

function resolveShellInvocation(command, args, shellOption) {
    const shell = shellOption === true ? defaultShell() : shellOption;
    const full = [command].concat(toStringArgs(args)).join(" ");
    const shellArgs = shellCommandArgs(shell, full);
    return { file: shellArgs[0], args: shellArgs.slice(1) };
}

// ---- minimal stdio stream wrappers ----

class ChildReadable extends EventEmitter {
    constructor() {
        super();
        this._encoding = null;
        this.readable = true;
        this.destroyed = false;
    }

    setEncoding(encoding) {
        this._encoding = encoding || null;
        return this;
    }

    pipe(dest, options) {
        this.on("data", (chunk) => dest.write(chunk));
        const endOption = !options || options.end !== false;
        if (endOption) {
            this.on("end", () => {
                if (typeof dest.end === "function") dest.end();
            });
        }
        return dest;
    }

    // No real backpressure/event-loop here — `pause`/`resume` are accepted for API
    // compatibility but the underlying reader thread always keeps draining.
    pause() {
        return this;
    }

    resume() {
        return this;
    }

    destroy() {
        this.destroyed = true;
        return this;
    }
}

class ChildWritable extends EventEmitter {
    constructor(jOutputStream) {
        super();
        this._out = jOutputStream;
        this.writable = true;
        this.destroyed = false;
    }

    write(chunk, encoding, callback) {
        const cb = typeof encoding === "function" ? encoding : callback;
        if (!this._out || this.destroyed) {
            const err = new Error("mycelium: write after child process stdin was closed");
            if (cb) nextTick(() => cb(err));
            else nextTick(() => this.emit("error", err));
            return false;
        }
        try {
            const bytes = toJByteArray(chunk);
            this._out.write(bytes, 0, bytes.length);
            this._out.flush();
            if (cb) nextTick(cb);
            return true;
        } catch (e) {
            const err = e instanceof Error ? e : new Error(String(e && e.message ? e.message : e));
            if (cb) nextTick(() => cb(err));
            else nextTick(() => this.emit("error", err));
            return false;
        }
    }

    end(chunk, encoding, callback) {
        let cb = callback;
        if (typeof chunk === "function") {
            cb = chunk;
            chunk = undefined;
        } else if (typeof encoding === "function") {
            cb = encoding;
            encoding = undefined;
        }
        const finish = () => {
            try {
                if (this._out) this._out.close();
            } catch (e) {
                // best-effort — process may have already exited and closed the pipe
            }
            this.destroyed = true;
            this.writable = false;
            if (cb) nextTick(cb);
        };
        if (chunk !== undefined) {
            this.write(chunk, encoding, finish);
        } else {
            finish();
        }
        return this;
    }
}

// ---- ChildProcess ----

class ChildProcess extends EventEmitter {
    constructor() {
        super();
        this.pid = undefined;
        this.stdin = null;
        this.stdout = null;
        this.stderr = null;
        this.stdio = [null, null, null];
        this.exitCode = null;
        this.signalCode = null;
        this.killed = false;
        this.connected = false;
        this.spawnfile = undefined;
        this.spawnargs = [];
        this._jProcess = null;
        this._killedSignal = null;
        this._closed = false;
    }

    _start(pb, spawnfile, args, stdioSpec, timeoutMs, killSignal) {
        this.spawnfile = spawnfile;
        this.spawnargs = [spawnfile].concat(args);
        let jProcess;
        try {
            jProcess = pb.start();
        } catch (e) {
            const err = e instanceof Error ? e : new Error(String(e && e.message ? e.message : e));
            nextTick(() => this.emit("error", err));
            return this;
        }
        this._jProcess = jProcess;
        this.pid = Number(jProcess.pid());

        this.stdin = stdioSpec[0] === "pipe" ? new ChildWritable(jProcess.getOutputStream()) : null;
        this.stdout = stdioSpec[1] === "pipe" ? new ChildReadable() : null;
        this.stderr = stdioSpec[2] === "pipe" ? new ChildReadable() : null;
        this.stdio = [this.stdin, this.stdout, this.stderr];

        this._startIoThread(timeoutMs, killSignal);
        return this;
    }

    // Polls stdout/stderr/exit status via `globalThis.setTimeout` (see TIMERS_INIT_SCRIPT
    // in Mycelium.kt) rather than a single dedicated long-lived `java.lang.Thread` that
    // loops internally. An earlier version of this used exactly that — one thread per
    // ChildProcess, blocking in a `while (true)` loop between polls — and it turned out to
    // be unreliable in practice: a background `Thread` newly created and started from a
    // guest callback isn't guaranteed a clean shot at (re-)entering the shared GraalJS
    // context, and losing that race throws "Multi threaded access requested ... but is not
    // allowed" *before a single line of the thread's own body runs* — including before any
    // `try/catch` inside it — silently killing the thread and leaving `exit`/`close` never
    // fired. `setTimeout`'s own machinery (a fresh one-shot daemon thread per scheduled
    // callback, each of which sleeps for its full delay *before* ever touching the context)
    // doesn't hit this in practice, so each poll "tick" here is scheduled as its own
    // `setTimeout` callback instead of a loop iteration — reusing infrastructure that's
    // already exercised (and relied upon) by setTimeout/setInterval elsewhere, rather than
    // re-implementing (and re-debugging) the same thread-safety properties here. The first
    // tick specifically goes through a zero-delay `setTimeout` (a same-thread microtask, see
    // TIMERS_INIT_SCRIPT's `schedule`) rather than a real thread at all, which also
    // guarantees it can never observably run until the *calling* script's current
    // synchronous turn — including whatever `child.on('close', ...)`-style listener
    // registration immediately follows `spawn()` — has finished, so a near-instantly-
    // exiting child (e.g. `echo`) can't have this reach `emit('close', ...)` before that
    // listener exists.
    _startIoThread(timeoutMs, killSignal) {
        const self = this;
        const jProcess = this._jProcess;
        const outStream = this.stdout ? jProcess.getInputStream() : null;
        const errStream = this.stderr ? jProcess.getErrorStream() : null;
        const deadline = timeoutMs && timeoutMs > 0 ? Date.now() + timeoutMs : null;
        const buf = new JByteArray(65536);

        const finish = () => {
            let code;
            try {
                code = jProcess.waitFor();
            } catch (e) {
                code = null;
            }
            if (self._killedSignal) {
                self.exitCode = null;
                self.signalCode = self._killedSignal;
            } else {
                self.exitCode = code;
                self.signalCode = null;
            }
            if (self.stdout) {
                self.stdout.readable = false;
                self.stdout.emit("end");
            }
            if (self.stderr) {
                self.stderr.readable = false;
                self.stderr.emit("end");
            }
            self.emit("exit", self.exitCode, self.signalCode);
            self.emit("close", self.exitCode, self.signalCode);
        };

        const tick = () => {
            let readAny = false;
            if (outStream) readAny = pollStream(outStream, self.stdout, buf) || readAny;
            if (errStream) readAny = pollStream(errStream, self.stderr, buf) || readAny;

            if (!jProcess.isAlive()) {
                // The process is already dead, so any remaining data is fully buffered in
                // the OS pipe — this drains to a real EOF quickly rather than blocking
                // indefinitely.
                if (outStream) drainStream(outStream, self.stdout, buf);
                if (errStream) drainStream(errStream, self.stderr, buf);
                finish();
                return;
            }

            if (deadline !== null && Date.now() >= deadline && !self._killedSignal) {
                self._killedSignal = killSignal || "SIGTERM";
                self.killed = true;
                if (self._killedSignal === "SIGKILL") jProcess.destroyForcibly();
                else jProcess.destroy();
            }

            globalThis.setTimeout(tick, readAny ? 0 : 5);
        };
        globalThis.setTimeout(tick, 0);
    }

    kill(signal) {
        if (!this._jProcess) return false;
        if (this.exitCode !== null || this.signalCode !== null) return false;
        this._killedSignal = signal || "SIGTERM";
        this.killed = true;
        try {
            if (this._killedSignal === "SIGKILL" || this._killedSignal === 9) {
                this._jProcess.destroyForcibly();
            } else {
                this._jProcess.destroy();
            }
        } catch (e) {
            return false;
        }
        return true;
    }

    ref() {
        return this;
    }

    unref() {
        return this;
    }

    disconnect() {
        if (this.connected) {
            this.connected = false;
            nextTick(() => this.emit("disconnect"));
        }
    }

    send() {
        throw new Error(
            "mycelium: ChildProcess#send() requires Node.js IPC, not yet supported in Mycelium"
        );
    }
}

// ---- spawn ----

function spawnRaw(file, args, options) {
    options = options || {};
    const stdioSpec = normalizeStdio(options.stdio);
    const pb = new JProcessBuilder(buildCommandList(file, args));
    applyCwd(pb, options.cwd);
    applyEnv(pb, options.env);
    applyStdio(pb, stdioSpec);

    const child = new ChildProcess();
    child._start(pb, file, args, stdioSpec, options.timeout, options.killSignal);
    return child;
}

export function spawn(command, args, options) {
    if (Array.isArray(args)) {
        // args given as-is
    } else if (args && typeof args === "object") {
        options = args;
        args = [];
    } else {
        args = [];
    }
    options = options || {};
    let file = command;
    let finalArgs = toStringArgs(args);
    if (options.shell) {
        const resolved = resolveShellInvocation(command, args, options.shell);
        file = resolved.file;
        finalArgs = resolved.args;
    }
    return spawnRaw(file, finalArgs, options);
}

// ---- exec / execFile (callback-based, buffered) ----

function collectExec(child, options, displayCommand, callback) {
    const maxBuffer = options.maxBuffer || 1024 * 1024;
    const encoding = options.encoding;
    const outChunks = [];
    const errChunks = [];
    let outLen = 0;
    let errLen = 0;
    let maxBufferError = null;

    const track = (chunksList, chunk, lenRef) => {
        const u8 = chunk instanceof Uint8Array ? chunk : globalThis.Buffer.from(String(chunk));
        lenRef.n += u8.length;
        if (lenRef.n > maxBuffer) {
            if (!maxBufferError) {
                maxBufferError = new Error(`${displayCommand}: ${lenRef.name} maxBuffer length exceeded`);
                child.kill(options.killSignal || "SIGTERM");
            }
            return;
        }
        chunksList.push(u8);
    };

    const outLenRef = { n: 0, name: "stdout" };
    const errLenRef = { n: 0, name: "stderr" };
    if (child.stdout) child.stdout.on("data", (chunk) => track(outChunks, chunk, outLenRef));
    if (child.stderr) child.stderr.on("data", (chunk) => track(errChunks, chunk, errLenRef));

    let settled = false;
    const settle = (spawnError) => {
        if (settled) return;
        settled = true;
        const stdout = encodeOutput(concatU8(outChunks), encoding, "utf8");
        const stderr = encodeOutput(concatU8(errChunks), encoding, "utf8");
        let error = spawnError || maxBufferError;
        if (!error && child.signalCode) {
            error = new Error(`Command failed: ${displayCommand}`);
            error.signal = child.signalCode;
        } else if (!error && child.exitCode !== null && child.exitCode !== 0) {
            error = new Error(`Command failed: ${displayCommand}\n${typeof stderr === "string" ? stderr : ""}`);
            error.code = child.exitCode;
        }
        if (error) {
            error.cmd = displayCommand;
            error.killed = child.killed;
        }
        if (typeof callback === "function") callback(error, stdout, stderr);
    };

    child.on("error", settle);
    child.on("close", () => settle());
}

export function exec(command, options, callback) {
    if (typeof options === "function") {
        callback = options;
        options = {};
    }
    options = options || {};
    const shell = options.shell || defaultShell();
    const shellArgs = shellCommandArgs(shell, command);
    const child = spawnRaw(shellArgs[0], shellArgs.slice(1), options);
    collectExec(child, options, command, callback);
    return child;
}

export function execFile(file, args, options, callback) {
    if (typeof args === "function") {
        callback = args;
        args = [];
        options = {};
    } else if (Array.isArray(args)) {
        if (typeof options === "function") {
            callback = options;
            options = {};
        }
    } else if (args && typeof args === "object") {
        callback = options;
        options = args;
        args = [];
    } else {
        args = [];
    }
    options = options || {};
    args = toStringArgs(args);
    const displayCommand = [file].concat(args).join(" ");

    let spawnFile = file;
    let spawnArgs = args;
    if (options.shell) {
        const resolved = resolveShellInvocation(file, args, options.shell);
        spawnFile = resolved.file;
        spawnArgs = resolved.args;
    }
    const child = spawnRaw(spawnFile, spawnArgs, options);
    collectExec(child, options, displayCommand, callback);
    return child;
}

// ---- execSync / execFileSync / spawnSync (blocking) ----

function spawnSyncRaw(file, args, options) {
    options = options || {};
    const stdioSpec = normalizeStdio(options.stdio);
    const pb = new JProcessBuilder(buildCommandList(file, args));
    applyCwd(pb, options.cwd);
    applyEnv(pb, options.env);
    applyStdio(pb, stdioSpec);

    let jProcess;
    try {
        jProcess = pb.start();
    } catch (e) {
        return {
            pid: undefined,
            output: [null, null, null],
            stdout: null,
            stderr: null,
            status: null,
            signal: null,
            error: e instanceof Error ? e : new Error(String(e && e.message ? e.message : e)),
        };
    }

    const pid = Number(jProcess.pid());

    if (stdioSpec[0] === "pipe") {
        try {
            if (options.input !== undefined && options.input !== null) {
                const bytes = toJByteArray(options.input);
                jProcess.getOutputStream().write(bytes, 0, bytes.length);
            }
            jProcess.getOutputStream().close();
        } catch (e) {
            // best-effort — the child may not read stdin at all
        }
    }

    // See the module doc comment at the top of this file for why this reads stdout fully
    // before starting on stderr, rather than draining both concurrently.
    const outBytes = stdioSpec[1] === "pipe" ? readAllBytesSync(jProcess.getInputStream()) : new JByteArray(0);
    const errBytes = stdioSpec[2] === "pipe" ? readAllBytesSync(jProcess.getErrorStream()) : new JByteArray(0);

    let timedOut = false;
    if (options.timeout && options.timeout > 0) {
        const finished = jProcess.waitFor(options.timeout, JTimeUnit.MILLISECONDS);
        if (!finished) {
            timedOut = true;
            if ((options.killSignal || "SIGTERM") === "SIGKILL") jProcess.destroyForcibly();
            else jProcess.destroy();
            jProcess.waitFor();
        }
    } else {
        jProcess.waitFor();
    }

    const encoding = options.encoding;
    const stdoutOut = stdioSpec[1] === "pipe" ? encodeOutput(fromJByteArray(outBytes), encoding) : null;
    const stderrOut = stdioSpec[2] === "pipe" ? encodeOutput(fromJByteArray(errBytes), encoding) : null;
    const status = timedOut ? null : jProcess.exitValue();
    const signal = timedOut ? options.killSignal || "SIGTERM" : null;

    return {
        pid,
        output: [null, stdoutOut, stderrOut],
        stdout: stdoutOut,
        stderr: stderrOut,
        status,
        signal,
        error: timedOut
            ? new Error(`Command timed out after ${options.timeout}ms`)
            : undefined,
    };
}

export function spawnSync(command, args, options) {
    if (Array.isArray(args)) {
        // args given as-is
    } else if (args && typeof args === "object") {
        options = args;
        args = [];
    } else {
        args = [];
    }
    options = options || {};
    let file = command;
    let finalArgs = toStringArgs(args);
    if (options.shell) {
        const resolved = resolveShellInvocation(command, args, options.shell);
        file = resolved.file;
        finalArgs = resolved.args;
    }
    return spawnSyncRaw(file, finalArgs, options);
}

function throwIfSyncFailed(result, displayCommand) {
    if (result.error) throw result.error;
    if (result.signal) {
        const err = new Error(`Command failed: ${displayCommand}`);
        err.signal = result.signal;
        err.status = result.status;
        throw err;
    }
    if (result.status !== 0) {
        const err = new Error(
            `Command failed: ${displayCommand}\n${typeof result.stderr === "string" ? result.stderr : ""}`
        );
        err.status = result.status;
        err.stdout = result.stdout;
        err.stderr = result.stderr;
        throw err;
    }
}

export function execSync(command, options) {
    options = options || {};
    const shell = options.shell || defaultShell();
    const shellArgs = shellCommandArgs(shell, command);
    const result = spawnSyncRaw(shellArgs[0], shellArgs.slice(1), options);
    throwIfSyncFailed(result, command);
    return result.stdout;
}

export function execFileSync(file, args, options) {
    if (args && !Array.isArray(args)) {
        options = args;
        args = [];
    }
    args = toStringArgs(args || []);
    options = options || {};
    const displayCommand = [file].concat(args).join(" ");

    let spawnFile = file;
    let spawnArgs = args;
    if (options.shell) {
        const resolved = resolveShellInvocation(file, args, options.shell);
        spawnFile = resolved.file;
        spawnArgs = resolved.args;
    }
    const result = spawnSyncRaw(spawnFile, spawnArgs, options);
    throwIfSyncFailed(result, displayCommand);
    return result.stdout;
}

// ---- fork ----

export function fork() {
    throw new Error("fork() requires Node.js IPC, not yet supported in Mycelium");
}

export { ChildProcess };

export default {
    exec,
    execSync,
    execFile,
    execFileSync,
    spawn,
    spawnSync,
    fork,
    ChildProcess,
};

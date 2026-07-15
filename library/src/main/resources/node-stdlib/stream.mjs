// Minimal Node.js-compatible `stream` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "stream"` / `"node:stream"`. Pure JS — no Java
// interop needed. Since core modules are loaded as standalone synthetic ESM resources
// (see InterceptingFileSystem), importing the `events` core module from here via a
// standard `import` isn't reliable, so EventEmitter is duplicated inline (kept minimal —
// just what Stream's subclasses need) rather than shared.

class EventEmitter {
    constructor() {
        this._events = Object.create(null);
    }

    on(event, listener) {
        const existing = this._events[event];
        if (existing === undefined) this._events[event] = [listener];
        else existing.push(listener);
        return this;
    }

    addListener(event, listener) {
        return this.on(event, listener);
    }

    once(event, listener) {
        const wrapper = (...args) => {
            this.removeListener(event, wrapper);
            listener.apply(this, args);
        };
        wrapper.listener = listener;
        return this.on(event, wrapper);
    }

    removeListener(event, listener) {
        const existing = this._events[event];
        if (existing === undefined) return this;
        const idx = existing.findIndex((l) => l === listener || l.listener === listener);
        if (idx !== -1) existing.splice(idx, 1);
        if (existing.length === 0) delete this._events[event];
        return this;
    }

    off(event, listener) {
        return this.removeListener(event, listener);
    }

    removeAllListeners(event) {
        if (event === undefined) this._events = Object.create(null);
        else delete this._events[event];
        return this;
    }

    emit(event, ...args) {
        const handlers = this._events[event];
        if (handlers === undefined) {
            if (event === "error") {
                const err = args[0];
                throw err instanceof Error ? err : new Error(String(err));
            }
            return false;
        }
        for (const listener of handlers.slice()) {
            listener.apply(this, args);
        }
        return true;
    }

    listenerCount(event) {
        const handlers = this._events[event];
        return handlers === undefined ? 0 : handlers.length;
    }

    listeners(event) {
        const handlers = this._events[event];
        return handlers === undefined ? [] : handlers.slice();
    }
}

// Runs `fn` on a microtask/macrotask so callers observing events (e.g. `.on('data', ...)`
// registered right after construction) don't miss synchronously-emitted events — mirrors
// Node's own "streams are asynchronous by default" behavior closely enough for the base
// classes here.
function nextTick(fn) {
    Promise.resolve().then(fn);
}

class Stream extends EventEmitter {
    pipe(destination, options) {
        const onData = (chunk) => {
            const canContinue = destination.write(chunk);
            if (canContinue === false && typeof this.pause === "function") {
                this.pause();
            }
        };
        this.on("data", onData);

        const onDrain = () => {
            if (typeof this.resume === "function") this.resume();
        };
        if (typeof destination.on === "function") destination.on("drain", onDrain);

        const endOption = !options || options.end !== false;
        const onEnd = () => {
            if (endOption && typeof destination.end === "function") destination.end();
        };
        this.on("end", onEnd);

        const onError = (err) => {
            this.emit("error", err);
        };
        if (typeof destination.on === "function") destination.on("error", onError);

        destination.emit && destination.emit("pipe", this);
        return destination;
    }

    unpipe() {
        return this;
    }
}

class Readable extends Stream {
    constructor(options) {
        super();
        options = options || {};
        this._readImpl = options.read;
        this._destroyImpl = options.destroy;
        this._buffer = [];
        this._ended = false;
        this._reading = false;
        this._flowing = false;
        this._encoding = options.encoding || null;
        this._highWaterMark = options.highWaterMark !== undefined ? options.highWaterMark : 16384;
        this._objectMode = !!options.objectMode;
        this._destroyed = false;
        this._closed = false;
        this._errored = null;
        this.readableEnded = false;
        this.readableFlowing = null;
    }

    get destroyed() {
        return !!this._destroyed;
    }

    set destroyed(value) {
        this._destroyed = value;
    }

    get closed() {
        return !!this._closed;
    }

    get errored() {
        return this._errored || null;
    }

    get readable() {
        return !this.destroyed && !this._ended;
    }

    get readableHighWaterMark() {
        return this._highWaterMark;
    }

    get readableLength() {
        let len = 0;
        for (const chunk of this._buffer) {
            len += chunk && chunk.length !== undefined ? chunk.length : 1;
        }
        return len;
    }

    get readableObjectMode() {
        return this._objectMode;
    }

    setEncoding(encoding) {
        this._encoding = encoding;
        return this;
    }

    destroy(err) {
        if (this.destroyed) return this;
        this._destroyed = true;
        const afterDestroy = (destroyErr) => {
            const finalErr = destroyErr || err;
            if (finalErr) {
                this._errored = finalErr;
                this.emit("error", finalErr);
            }
            this._closed = true;
            this.emit("close");
        };
        if (typeof this._destroy === "function") {
            this._destroy(err || null, afterDestroy);
        } else if (typeof this._destroyImpl === "function") {
            this._destroyImpl.call(this, err || null, afterDestroy);
        } else {
            afterDestroy(null);
        }
        return this;
    }

    _read(size) {
        if (this._readImpl) this._readImpl.call(this, size);
    }

    // `push` both feeds the internal buffer and — while in flowing mode — schedules a
    // drain of it via `_flow`, which is also what re-invokes `_read` to pull the next
    // chunk once the buffer runs dry (mirroring Node's own pull-on-demand readable
    // stream protocol, where a stream implementation calls `push` from inside its own
    // `_read`).
    push(chunk, encoding) {
        this._reading = false;
        if (chunk === null || chunk === undefined) {
            this._ended = true;
            if (this._flowing) nextTick(() => this._flow());
            return false;
        }
        if (this._encoding && typeof chunk !== "string" && chunk && typeof chunk.toString === "function") {
            chunk = chunk.toString(this._encoding);
        }
        this._buffer.push(chunk);
        if (this._flowing) nextTick(() => this._flow());
        return true;
    }

    _flow() {
        if (!this._flowing) return;
        while (this._flowing && this._buffer.length > 0) {
            const chunk = this._buffer.shift();
            this.emit("data", chunk);
        }
        if (!this._flowing) return;
        if (this._buffer.length === 0) {
            if (this._ended) {
                if (!this.readableEnded) {
                    this.readableEnded = true;
                    this.emit("end");
                }
            } else if (!this._reading) {
                this._reading = true;
                this._read(16384);
            }
        }
    }

    read(size) {
        if (this._buffer.length === 0 && !this._ended && !this._reading) {
            this._reading = true;
            this._read(size || 0);
        }
        if (this._buffer.length === 0) return null;
        return this._buffer.shift();
    }

    pause() {
        this._flowing = false;
        this.readableFlowing = false;
        return this;
    }

    resume() {
        this._flowing = true;
        this.readableFlowing = true;
        nextTick(() => this._flow());
        return this;
    }

    isPaused() {
        return !this._flowing;
    }

    on(event, listener) {
        super.on(event, listener);
        if (event === "data") {
            this._flowing = true;
            this.readableFlowing = true;
            nextTick(() => this._flow());
        }
        return this;
    }

    [Symbol.asyncIterator]() {
        const self = this;
        return {
            next() {
                return new Promise((resolve, reject) => {
                    const value = self.read();
                    if (value !== null) {
                        resolve({ value, done: false });
                        return;
                    }
                    if (self._ended) {
                        resolve({ value: undefined, done: true });
                        return;
                    }
                    const onData = () => {
                        cleanup();
                        resolve({ value: self.read(), done: false });
                    };
                    const onEnd = () => {
                        cleanup();
                        resolve({ value: undefined, done: true });
                    };
                    const onError = (err) => {
                        cleanup();
                        reject(err);
                    };
                    const cleanup = () => {
                        self.removeListener("data", onData);
                        self.removeListener("end", onEnd);
                        self.removeListener("error", onError);
                    };
                    self.once("data", onData);
                    self.once("end", onEnd);
                    self.once("error", onError);
                });
            },
        };
    }

    static from(iterable, options) {
        const readable = new Readable(options);
        if (iterable && typeof iterable[Symbol.asyncIterator] === "function") {
            const iterator = iterable[Symbol.asyncIterator]();
            readable._read = function () {
                iterator.next().then(
                    (res) => {
                        if (res.done) this.push(null);
                        else this.push(res.value);
                    },
                    (err) => {
                        this.emit("error", err);
                    },
                );
            };
            return readable;
        }
        if (iterable && typeof iterable[Symbol.iterator] === "function" && !Array.isArray(iterable)) {
            const iterator = iterable[Symbol.iterator]();
            readable._read = function () {
                const res = iterator.next();
                if (res.done) this.push(null);
                else this.push(res.value);
            };
            return readable;
        }
        const items = Array.isArray(iterable) ? iterable.slice() : Array.from(iterable);
        readable._read = function () {
            if (items.length === 0) {
                this.push(null);
            } else {
                this.push(items.shift());
            }
        };
        return readable;
    }
}

class Writable extends Stream {
    constructor(options) {
        super();
        options = options || {};
        this._writeImpl = options.write;
        this._finalImpl = options.final;
        this._destroyImpl = options.destroy;
        this.writableEnded = false;
        this.writableFinished = false;
        this._corked = 0;
        this._pending = [];
        this._highWaterMark = options.highWaterMark !== undefined ? options.highWaterMark : 16384;
        this._objectMode = !!options.objectMode;
        this._writableLength = 0;
        this._destroyed = false;
        this._closed = false;
        this._errored = null;
    }

    get destroyed() {
        return !!this._destroyed;
    }

    set destroyed(value) {
        this._destroyed = value;
    }

    get closed() {
        return !!this._closed;
    }

    get errored() {
        return this._errored || null;
    }

    get writable() {
        return !this.destroyed && !this.writableEnded;
    }

    get writableHighWaterMark() {
        return this._highWaterMark;
    }

    get writableLength() {
        return this._writableLength;
    }

    get writableObjectMode() {
        return this._objectMode;
    }

    get writableCorked() {
        return this._corked;
    }

    destroy(err) {
        if (this.destroyed) return this;
        this._destroyed = true;
        const afterDestroy = (destroyErr) => {
            const finalErr = destroyErr || err;
            if (finalErr) {
                this._errored = finalErr;
                this.emit("error", finalErr);
            }
            this._closed = true;
            this.emit("close");
        };
        if (typeof this._destroy === "function") {
            this._destroy(err || null, afterDestroy);
        } else if (typeof this._destroyImpl === "function") {
            this._destroyImpl.call(this, err || null, afterDestroy);
        } else {
            afterDestroy(null);
        }
        return this;
    }

    _write(chunk, encoding, callback) {
        if (this._writeImpl) {
            this._writeImpl.call(this, chunk, encoding, callback);
        } else {
            callback();
        }
    }

    write(chunk, encoding, callback) {
        if (typeof encoding === "function") {
            callback = encoding;
            encoding = "utf8";
        }
        if (this.writableEnded) {
            const err = new Error("write after end");
            this.emit("error", err);
            if (callback) callback(err);
            return false;
        }
        const chunkLength = chunk && chunk.length !== undefined ? chunk.length : 1;
        this._writableLength += chunkLength;
        this._write(chunk, encoding || "utf8", (err) => {
            this._writableLength -= chunkLength;
            if (err) {
                this.emit("error", err);
            }
            if (callback) callback(err);
        });
        return true;
    }

    end(chunk, encoding, callback) {
        if (typeof chunk === "function") {
            callback = chunk;
            chunk = undefined;
        } else if (typeof encoding === "function") {
            callback = encoding;
            encoding = undefined;
        }
        const finish = () => {
            this.writableEnded = true;
            this.writableFinished = true;
            this.emit("finish");
            if (callback) callback();
        };
        if (chunk !== undefined) {
            this.write(chunk, encoding, () => {
                if (this._finalImpl) this._finalImpl.call(this, finish);
                else finish();
            });
        } else if (this._finalImpl) {
            this._finalImpl.call(this, finish);
        } else {
            nextTick(finish);
        }
        return this;
    }

    cork() {
        this._corked++;
    }

    uncork() {
        if (this._corked > 0) this._corked--;
    }
}

class Duplex extends Readable {
    constructor(options) {
        super(options);
        options = options || {};
        this._writeImpl = options.write;
        this._finalImpl = options.final;
        this.writableEnded = false;
        this.writableFinished = false;
        this._corked = 0;
        this._pending = [];
        this._writableLength = 0;
    }
}
// Mix Writable's instance methods onto Duplex's prototype (rather than attempting real
// multiple inheritance, which JS classes don't support). `destroy`/`destroyed`/`closed`/
// `errored` are shared concepts already provided via the Readable prototype chain (both
// classes use the same backing field names), so they're deliberately left out here.
for (const name of ["_write", "write", "end", "cork", "uncork"]) {
    Duplex.prototype[name] = Writable.prototype[name];
}
// Mix in Writable-only getters (readable-side equivalents already come from Readable).
for (const name of ["writable", "writableHighWaterMark", "writableLength", "writableObjectMode", "writableCorked"]) {
    Object.defineProperty(Duplex.prototype, name, Object.getOwnPropertyDescriptor(Writable.prototype, name));
}

class Transform extends Duplex {
    constructor(options) {
        super(options);
        options = options || {};
        this._transformImpl = options.transform;
        this._flushImpl = options.flush;
    }

    _transform(chunk, encoding, callback) {
        if (this._transformImpl) {
            this._transformImpl.call(this, chunk, encoding, callback);
        } else {
            callback(null, chunk);
        }
    }

    _write(chunk, encoding, callback) {
        this._transform(chunk, encoding, (err, transformed) => {
            if (err) {
                callback(err);
                return;
            }
            if (transformed !== undefined && transformed !== null) {
                this.push(transformed);
            }
            callback();
        });
    }

    // Default no-op `_flush`; subclasses following Node's convention override this as
    // an ordinary class method (e.g. `class Gzip extends Transform { _flush(cb) {...} }`).
    // `_final` below dispatches to it via `this._flush(...)`, so an overridden method on
    // the subclass's prototype takes precedence automatically — no separate hook needed
    // for that path. `options.flush` (the "options object" construction style, `new
    // Transform({ flush(cb) {...} })`) is supported too, as a fallback when `_flush`
    // hasn't been overridden.
    _flush(callback) {
        if (this._flushImpl) {
            this._flushImpl.call(this, callback);
        } else {
            callback();
        }
    }

    _final(callback) {
        this._flush((err, transformed) => {
            if (err) {
                callback(err);
                return;
            }
            if (transformed !== undefined && transformed !== null) this.push(transformed);
            this.push(null);
            callback();
        });
    }

    end(chunk, encoding, callback) {
        if (typeof chunk === "function") {
            callback = chunk;
            chunk = undefined;
        } else if (typeof encoding === "function") {
            callback = encoding;
            encoding = undefined;
        }
        const finish = () => {
            this.writableEnded = true;
            this.writableFinished = true;
            this.emit("finish");
            if (callback) callback();
        };
        const afterWrite = () => this._final(finish);
        if (chunk !== undefined) {
            this.write(chunk, encoding, afterWrite);
        } else {
            afterWrite();
        }
        return this;
    }
}

class PassThrough extends Transform {
    _transform(chunk, encoding, callback) {
        callback(null, chunk);
    }
}

// `finished(stream, [options], callback)` resolves once `stream` reaches a terminal
// state — mirrors Node's `stream.finished`, including firing immediately (on a fresh
// tick, to keep the callback always-async) when the stream has already finished/ended/
// errored/closed by the time this is called, since callers frequently attach it after
// the terminal event already fired synchronously elsewhere.
function finished(stream, options, callback) {
    if (typeof options === "function") {
        callback = options;
        options = undefined;
    }
    options = options || {};
    let called = false;
    const done = (err) => {
        if (called) return;
        called = true;
        cleanup();
        callback(err);
    };
    const onEnd = () => done();
    const onFinish = () => done();
    const onError = (err) => done(err);
    const onClose = () => done(stream.errored || undefined);
    const cleanup = () => {
        stream.removeListener("end", onEnd);
        stream.removeListener("finish", onFinish);
        stream.removeListener("error", onError);
        stream.removeListener("close", onClose);
    };
    stream.once("end", onEnd);
    stream.once("finish", onFinish);
    stream.once("error", onError);
    stream.once("close", onClose);

    const alreadyDone =
        (options.readable !== false && stream.readableEnded) ||
        (options.writable !== false && stream.writableFinished) ||
        stream.destroyed ||
        stream.errored;
    if (alreadyDone) {
        nextTick(() => done(stream.errored || undefined));
    }

    return () => {
        cleanup();
    };
}

// `pipeline(source, ...transforms, dest, callback)` chains `.pipe()` calls and, when a
// callback is given, both resolves it once the last stream finishes (via `finished`
// above) and destroys every stream in the chain if any of them errors — mirrors Node's
// automatic cleanup-on-error behavior for `stream.pipeline`.
function pipeline(...args) {
    const callback = typeof args[args.length - 1] === "function" ? args.pop() : undefined;
    const streams = args;
    let current = streams[0];
    for (let i = 1; i < streams.length; i++) {
        current = current.pipe(streams[i]);
    }
    if (callback) {
        const last = streams[streams.length - 1];
        let called = false;
        const onDone = (err) => {
            if (called) return;
            called = true;
            if (err) {
                for (const s of streams) {
                    if (typeof s.destroy === "function") s.destroy();
                }
            }
            callback(err);
        };
        finished(last, onDone);
        for (const s of streams) {
            if (typeof s.on === "function") {
                s.on("error", onDone);
            }
        }
    }
    return current;
}

// Mirrors Node's actual `stream` module shape: `module.exports` is the `Stream`
// constructor itself, with `Readable`/`Writable`/etc. attached as static properties —
// `require('stream')` returns something callers both `new` directly (rare) and read
// `.Readable`/`.Stream` etc. off of (common: e.g. `send`'s `var Stream = require('stream')`
// followed by `util.inherits(SendStream, Stream)`, which needs `Stream` itself, not a
// `{ Stream, Readable, ... }` wrapper object without a `.prototype` of its own).
Stream.Stream = Stream;
Stream.Readable = Readable;
Stream.Writable = Writable;
Stream.Duplex = Duplex;
Stream.Transform = Transform;
Stream.PassThrough = PassThrough;
Stream.finished = finished;
Stream.pipeline = pipeline;

export { Stream, Readable, Writable, Duplex, Transform, PassThrough, finished, pipeline, EventEmitter };

export default Stream;

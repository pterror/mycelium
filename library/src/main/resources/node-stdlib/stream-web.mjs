// Minimal Node.js-compatible `stream/web` module (the WHATWG Streams API — `ReadableStream`
// / `WritableStream` / `TransformStream` and friends), served by Mycelium as the synthetic
// core module resolved for `import ... from "stream/web"` / `"node:stream/web"`.
//
// GraalJS doesn't implement the Streams API itself (it's a web/WHATWG spec, not part of
// ECMAScript) — same situation buffer.mjs/Mycelium.kt already handle for `TextEncoder`/
// `TextDecoder` (see Mycelium.kt's TEXT_ENCODING_INIT_SCRIPT) and Blob (see
// stream-consumers.mjs). This is a from-scratch, deliberately simplified implementation:
// a single-reader/single-writer queue-based model with promise-based backpressure signals
// (`desiredSize`, `ready`), good enough for the common "produce chunks, consume chunks,
// maybe transform in between" use cases npm packages built against these APIs actually
// exercise — not a spec-complete implementation (no BYOB/byte-mode reading, no real
// queuing-strategy `size()` accounting beyond one-chunk-equals-one-unit).
import { gzipSync, gunzipSync, deflateSync, inflateSync, deflateRawSync, inflateRawSync } from "zlib";

async function toUint8Array(chunks) {
    // Concatenates a list of Uint8Array-ish chunks without depending on `Buffer` (unlike
    // stream-consumers.mjs, this module's exports — e.g. CompressionStream — are meant to
    // work on plain bytes without pulling in the `buffer` core module as a hard
    // dependency).
    let total = 0;
    for (const chunk of chunks) total += chunk.length;
    const out = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) {
        out.set(chunk instanceof Uint8Array ? chunk : new Uint8Array(chunk), offset);
        offset += chunk.length;
    }
    return out;
}

// --- ReadableStream ---

class ReadableStreamDefaultController {
    constructor(stream) {
        this._stream = stream;
    }

    get desiredSize() {
        const s = this._stream;
        if (s._state === "closed") return 0;
        if (s._state === "errored") return null;
        return s._highWaterMark - s._queue.length;
    }

    enqueue(chunk) {
        const s = this._stream;
        if (s._state !== "readable") return;
        if (s._pendingReads.length > 0) {
            const { resolve } = s._pendingReads.shift();
            resolve({ value: chunk, done: false });
        } else {
            s._queue.push(chunk);
        }
    }

    close() {
        const s = this._stream;
        if (s._state !== "readable") return;
        s._state = "closed";
        for (const { resolve } of s._pendingReads.splice(0)) resolve({ value: undefined, done: true });
        for (const resolve of s._closedResolvers.splice(0)) resolve();
        s._closedRejecters.length = 0;
    }

    error(e) {
        const s = this._stream;
        if (s._state !== "readable") return;
        s._state = "errored";
        s._storedError = e;
        for (const { reject } of s._pendingReads.splice(0)) reject(e);
        for (const reject of s._closedRejecters.splice(0)) reject(e);
        s._closedResolvers.length = 0;
    }
}

// Byte-mode controller/reader/request are stubbed (not backed by real BYOB semantics) —
// exported for API-surface completeness (some libraries merely check these exist) since
// no consumer in Mycelium's supported package set actually drives byte-mode reading yet.
class ReadableByteStreamController extends ReadableStreamDefaultController {
    get byobRequest() {
        return null;
    }
}

class ReadableStreamBYOBRequest {
    constructor() {
        this.view = null;
    }
    respond() {}
    respondWithNewView() {}
}

class ReadableStreamDefaultReader {
    constructor(stream) {
        this._stream = stream;
        stream._reader = this;
        this.closed = new Promise((resolve, reject) => {
            if (stream._state === "closed") resolve();
            else if (stream._state === "errored") reject(stream._storedError);
            else {
                stream._closedResolvers.push(resolve);
                stream._closedRejecters.push(reject);
            }
        });
        // A `.closed` rejection with no attached handler otherwise reports as an unhandled
        // rejection the moment a stream errors, even for callers who only ever check
        // `read()`'s result — same "attach a no-op catch" pattern used for the internal
        // bookkeeping promise chains elsewhere in this module.
        this.closed.catch(() => {});
    }

    read() {
        const s = this._stream;
        if (s._state === "errored") return Promise.reject(s._storedError);
        if (s._queue.length > 0) {
            const value = s._queue.shift();
            s._maybePull();
            return Promise.resolve({ value, done: false });
        }
        if (s._state === "closed") return Promise.resolve({ value: undefined, done: true });
        return new Promise((resolve, reject) => {
            s._pendingReads.push({ resolve, reject });
            s._maybePull();
        });
    }

    releaseLock() {
        if (this._stream._reader === this) this._stream._reader = null;
    }

    cancel(reason) {
        return this._stream.cancel(reason);
    }
}

class ReadableStreamBYOBReader extends ReadableStreamDefaultReader {
    read(_view) {
        return Promise.reject(new TypeError("BYOB reading is not supported by Mycelium's stream/web shim"));
    }
}

class ReadableStream {
    constructor(underlyingSource = {}, strategy = {}) {
        this._queue = [];
        this._state = "readable";
        this._storedError = undefined;
        this._reader = null;
        this._pendingReads = [];
        this._closedResolvers = [];
        this._closedRejecters = [];
        this._underlyingSource = underlyingSource || {};
        this._started = false;
        this._pulling = false;
        this._highWaterMark = strategy && strategy.highWaterMark !== undefined ? strategy.highWaterMark : 1;
        this._controller =
            underlyingSource && underlyingSource.type === "bytes"
                ? new ReadableByteStreamController(this)
                : new ReadableStreamDefaultController(this);

        const startResult = this._underlyingSource.start
            ? this._underlyingSource.start(this._controller)
            : undefined;
        Promise.resolve(startResult).then(
            () => {
                this._started = true;
                this._maybePull();
            },
            (e) => this._controller.error(e),
        );
    }

    get locked() {
        return this._reader !== null;
    }

    _maybePull() {
        if (!this._started || this._state !== "readable" || this._pulling) return;
        if (this._queue.length >= this._highWaterMark) return;
        if (typeof this._underlyingSource.pull !== "function") return;
        this._pulling = true;
        Promise.resolve(this._underlyingSource.pull(this._controller)).then(
            () => {
                this._pulling = false;
                this._maybePull();
            },
            (e) => {
                this._pulling = false;
                this._controller.error(e);
            },
        );
    }

    getReader(options) {
        if (this._reader) throw new TypeError("ReadableStream is already locked to a reader");
        return options && options.mode === "byob"
            ? new ReadableStreamBYOBReader(this)
            : new ReadableStreamDefaultReader(this);
    }

    cancel(reason) {
        if (typeof this._underlyingSource.cancel === "function") {
            return Promise.resolve(this._underlyingSource.cancel(reason));
        }
        this._state = "closed";
        return Promise.resolve();
    }

    [Symbol.asyncIterator]() {
        const reader = this.getReader();
        return {
            next: () => reader.read(),
            return: (value) => {
                reader.releaseLock();
                return Promise.resolve({ value, done: true });
            },
            [Symbol.asyncIterator]() {
                return this;
            },
        };
    }

    async pipeTo(destination, options) {
        options = options || {};
        const reader = this.getReader();
        const writer = destination.getWriter();
        try {
            while (true) {
                const { value, done } = await reader.read();
                if (done) break;
                await writer.write(value);
            }
            if (!options.preventClose) await writer.close();
        } catch (e) {
            if (!options.preventAbort) await writer.abort(e).catch(() => {});
            throw e;
        } finally {
            reader.releaseLock();
            writer.releaseLock();
        }
    }

    pipeThrough(transform, options) {
        this.pipeTo(transform.writable, options).catch(() => {});
        return transform.readable;
    }

    tee() {
        // Simplified tee: a single background pump owns the shared reader and pushes every
        // chunk into *both* branch controllers as soon as it's read (rather than each
        // branch's own `pull()` independently racing to read from the shared reader, which
        // is prone to interleaving chunks between branches out of order). This means an
        // unconsumed branch's queue grows without the bound a spec-accurate
        // backpressure-aware tee would apply — an accepted memory/backpressure tradeoff for
        // the common case both branches are consumed at a similar pace.
        const reader = this.getReader();
        let controllerA;
        let controllerB;
        const streamA = new ReadableStream({
            start(controller) {
                controllerA = controller;
            },
        });
        const streamB = new ReadableStream({
            start(controller) {
                controllerB = controller;
            },
        });
        (async () => {
            try {
                while (true) {
                    const { value, done } = await reader.read();
                    if (done) {
                        controllerA.close();
                        controllerB.close();
                        return;
                    }
                    controllerA.enqueue(value);
                    controllerB.enqueue(value);
                }
            } catch (e) {
                controllerA.error(e);
                controllerB.error(e);
            }
        })();
        return [streamA, streamB];
    }
}

// --- WritableStream ---

class WritableStreamDefaultController {
    constructor(stream) {
        this._stream = stream;
    }

    error(e) {
        this._stream._error(e);
    }

    get signal() {
        return this._stream._signal;
    }
}

class WritableStreamDefaultWriter {
    constructor(stream) {
        this._stream = stream;
        stream._writer = this;
        this.ready = Promise.resolve();
        this.closed = new Promise((resolve, reject) => {
            if (stream._state === "closed") resolve();
            else if (stream._state === "errored") reject(stream._storedError);
            else {
                stream._closedResolvers.push(resolve);
                stream._closedRejecters.push(reject);
            }
        });
        this.closed.catch(() => {});
    }

    get desiredSize() {
        const s = this._stream;
        if (s._state === "errored") return null;
        if (s._state === "closed") return 0;
        return s._highWaterMark - s._queueSize;
    }

    write(chunk) {
        const s = this._stream;
        if (s._state !== "writable") {
            return Promise.reject(s._storedError || new TypeError("The stream is not writable"));
        }
        s._queueSize++;
        const result = s._writeChain
            .then(() => {
                if (s._state !== "writable") throw s._storedError || new TypeError("The stream is not writable");
                return s._sink.write ? s._sink.write(chunk, s._controller) : undefined;
            })
            .then(
                () => {
                    s._queueSize--;
                },
                (e) => {
                    s._queueSize--;
                    s._error(e);
                    throw e;
                },
            );
        s._writeChain = result.catch(() => {});
        return result;
    }

    close() {
        const s = this._stream;
        const result = s._writeChain
            .then(() => (s._sink.close ? s._sink.close() : undefined))
            .then(
                () => {
                    s._state = "closed";
                    for (const resolve of s._closedResolvers.splice(0)) resolve();
                    s._closedRejecters.length = 0;
                },
                (e) => {
                    s._error(e);
                    throw e;
                },
            );
        s._writeChain = result.catch(() => {});
        return result;
    }

    abort(reason) {
        return this._stream.abort(reason);
    }

    releaseLock() {
        if (this._stream._writer === this) this._stream._writer = null;
    }
}

class WritableStream {
    constructor(underlyingSink = {}, strategy = {}) {
        this._sink = underlyingSink || {};
        this._state = "writable";
        this._storedError = undefined;
        this._writer = null;
        this._queueSize = 0;
        this._closedResolvers = [];
        this._closedRejecters = [];
        this._highWaterMark = strategy && strategy.highWaterMark !== undefined ? strategy.highWaterMark : 1;
        this._controller = new WritableStreamDefaultController(this);
        this._writeChain = Promise.resolve(this._sink.start ? this._sink.start(this._controller) : undefined).catch(
            (e) => this._error(e),
        );
    }

    get locked() {
        return this._writer !== null;
    }

    _error(e) {
        if (this._state === "errored" || this._state === "closed") return;
        this._state = "errored";
        this._storedError = e;
        for (const reject of this._closedRejecters.splice(0)) reject(e);
        this._closedResolvers.length = 0;
    }

    getWriter() {
        if (this._writer) throw new TypeError("WritableStream is already locked to a writer");
        return new WritableStreamDefaultWriter(this);
    }

    abort(reason) {
        const afterAbort = () => {
            this._state = "errored";
            this._storedError = reason;
            for (const reject of this._closedRejecters.splice(0)) reject(reason);
            this._closedResolvers.length = 0;
        };
        if (typeof this._sink.abort === "function") {
            return Promise.resolve(this._sink.abort(reason)).then(afterAbort);
        }
        afterAbort();
        return Promise.resolve();
    }
}

// --- TransformStream ---

class TransformStreamDefaultController {
    constructor(readableController) {
        this._readableController = readableController;
    }

    get desiredSize() {
        return this._readableController.desiredSize;
    }

    enqueue(chunk) {
        this._readableController.enqueue(chunk);
    }

    error(e) {
        this._readableController.error(e);
    }

    terminate() {
        this._readableController.close();
    }
}

class TransformStream {
    constructor(transformer = {}, writableStrategy, readableStrategy) {
        transformer = transformer || {};
        let readableController;
        const readable = new ReadableStream(
            {
                start(controller) {
                    readableController = controller;
                },
            },
            readableStrategy,
        );
        const transformController = new TransformStreamDefaultController(readableController);
        const writable = new WritableStream(
            {
                write(chunk) {
                    if (typeof transformer.transform === "function") {
                        return transformer.transform(chunk, transformController);
                    }
                    transformController.enqueue(chunk);
                    return undefined;
                },
                close() {
                    if (typeof transformer.flush === "function") {
                        return Promise.resolve(transformer.flush(transformController)).then(() =>
                            transformController.terminate(),
                        );
                    }
                    transformController.terminate();
                    return undefined;
                },
                abort(reason) {
                    transformController.error(reason);
                },
            },
            writableStrategy,
        );
        this.readable = readable;
        this.writable = writable;
        if (typeof transformer.start === "function") transformer.start(transformController);
    }
}

// --- Queuing strategies ---

class ByteLengthQueuingStrategy {
    constructor(options) {
        this.highWaterMark = options ? options.highWaterMark : undefined;
    }
    size(chunk) {
        return chunk && chunk.byteLength !== undefined ? chunk.byteLength : 0;
    }
}

class CountQueuingStrategy {
    constructor(options) {
        this.highWaterMark = options ? options.highWaterMark : undefined;
    }
    size() {
        return 1;
    }
}

// --- Text encode/decode transform streams ---

class TextEncoderStream extends TransformStream {
    constructor() {
        const encoder = new globalThis.TextEncoder();
        super({
            transform(chunk, controller) {
                controller.enqueue(encoder.encode(String(chunk)));
            },
        });
        this._encoding = "utf-8";
    }
    get encoding() {
        return this._encoding;
    }
}

class TextDecoderStream extends TransformStream {
    constructor(label, options) {
        const decoder = new globalThis.TextDecoder(label, options);
        super({
            transform(chunk, controller) {
                // Mycelium's TextDecoder polyfill (see Mycelium.kt) doesn't support the
                // `{ stream: true }` incremental-decode option, so a multi-byte character
                // split across chunk boundaries can decode incorrectly here — an accepted
                // limitation shared with that polyfill rather than one specific to this
                // stream wrapper.
                const text = decoder.decode(chunk);
                if (text.length > 0) controller.enqueue(text);
            },
        });
        this._encoding = decoder.encoding;
    }
    get encoding() {
        return this._encoding;
    }
}

// --- Compression/decompression transform streams ---
//
// Built on zlib.mjs's synchronous `*Sync` functions rather than true incremental
// (de)compression: every chunk written to the stream is buffered, and the whole buffered
// input is (de)compressed in one shot when the stream is closed. That means one full
// compressed member is only ever produced once — correct for the common "compress this
// whole body" / "decompress this whole response" use, but not a byte-for-byte streaming
// implementation (no incremental flush per chunk).
class CompressionStream extends TransformStream {
    constructor(format) {
        const chunks = [];
        super({
            transform(chunk) {
                chunks.push(chunk instanceof Uint8Array ? chunk : new Uint8Array(chunk));
            },
            async flush(controller) {
                const input = await toUint8Array(chunks);
                let result;
                if (format === "gzip") result = gzipSync(input);
                else if (format === "deflate") result = deflateSync(input);
                else if (format === "deflate-raw") result = deflateRawSync(input);
                else throw new TypeError(`Unsupported compression format: '${format}'`);
                controller.enqueue(new Uint8Array(result));
            },
        });
        this.format = format;
    }
}

class DecompressionStream extends TransformStream {
    constructor(format) {
        const chunks = [];
        super({
            transform(chunk) {
                chunks.push(chunk instanceof Uint8Array ? chunk : new Uint8Array(chunk));
            },
            async flush(controller) {
                const input = await toUint8Array(chunks);
                let result;
                if (format === "gzip") result = gunzipSync(input);
                else if (format === "deflate") result = inflateSync(input);
                else if (format === "deflate-raw") result = inflateRawSync(input);
                else throw new TypeError(`Unsupported compression format: '${format}'`);
                controller.enqueue(new Uint8Array(result));
            },
        });
        this.format = format;
    }
}

export {
    ReadableStream,
    ReadableStreamDefaultReader,
    ReadableStreamBYOBReader,
    ReadableStreamBYOBRequest,
    ReadableByteStreamController,
    ReadableStreamDefaultController,
    TransformStream,
    TransformStreamDefaultController,
    WritableStream,
    WritableStreamDefaultWriter,
    WritableStreamDefaultController,
    ByteLengthQueuingStrategy,
    CountQueuingStrategy,
    TextEncoderStream,
    TextDecoderStream,
    CompressionStream,
    DecompressionStream,
};

export default {
    ReadableStream,
    ReadableStreamDefaultReader,
    ReadableStreamBYOBReader,
    ReadableStreamBYOBRequest,
    ReadableByteStreamController,
    ReadableStreamDefaultController,
    TransformStream,
    TransformStreamDefaultController,
    WritableStream,
    WritableStreamDefaultWriter,
    WritableStreamDefaultController,
    ByteLengthQueuingStrategy,
    CountQueuingStrategy,
    TextEncoderStream,
    TextDecoderStream,
    CompressionStream,
    DecompressionStream,
};

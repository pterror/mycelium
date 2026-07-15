// Minimal Node.js-compatible `zlib` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "zlib"` / `"node:zlib"`. Backed by
// `java.util.zip` (`GZIPOutputStream`/`GZIPInputStream` for gzip, `Deflater`/`Inflater`
// for raw/zlib deflate). Provides both the `*Sync` buffer functions and a
// Transform-stream based streaming API (`createGzip`/`createGunzip`/etc), for the
// "pipe a big file through it" use case the sync API can't handle.

import { Transform } from "stream";

const JByteArrayOutputStream = Java.type("java.io.ByteArrayOutputStream");
const JByteArrayInputStream = Java.type("java.io.ByteArrayInputStream");
const JGZIPOutputStream = Java.type("java.util.zip.GZIPOutputStream");
const JGZIPInputStream = Java.type("java.util.zip.GZIPInputStream");
const JDeflater = Java.type("java.util.zip.Deflater");
const JInflater = Java.type("java.util.zip.Inflater");
const JByteArray = Java.type("byte[]");
const JCharset = Java.type("java.nio.charset.StandardCharsets");

function toJByteArray(data) {
    if (typeof data === "string") {
        const buf = Java.type("java.nio.charset.StandardCharsets").UTF_8.encode(data);
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

// Real Node's zlib `*Sync` functions (and the streaming API's `data` events) hand back an
// actual `Buffer` — not a plain `Uint8Array` — and callers rely on that: e.g.
// `@mongodb-js/saslprep`'s `dist/code-points-data.js` does
// `zlib.gunzipSync(Buffer.from(...))` and then immediately calls `.readUInt32BE(...)` on
// the result, a `Buffer`-only method a bare `Uint8Array` doesn't have. `globalThis.Buffer`
// is set up during context init (see PROCESS_INIT_SCRIPT/GLOBAL_CRYPTO_INIT_SCRIPT-style
// globals in Mycelium.kt) before any guest code (including this module) runs, so it's
// available here as a bare global rather than imported — a direct `import ... from
// "buffer"` isn't reliable from within another core module (see http.mjs's own comment on
// why core modules are standalone synthetic resources).
function fromJByteArray(jBytes, length) {
    const len = length === undefined ? jBytes.length : length;
    const buf = globalThis.Buffer.alloc(len);
    for (let i = 0; i < len; i++) buf[i] = jBytes[i] & 0xff;
    return buf;
}

// Like toJByteArray, but produces a plain JS array of unsigned byte values (0-255)
// rather than a Java byte[] — used by the streaming gzip header parser, which needs to
// look ahead/behind across chunk boundaries with ordinary JS array operations
// (push/slice) before handing the post-header bytes off to an Inflater.
function toIntArray(data) {
    if (typeof data === "string") {
        const buf = JCharset.UTF_8.encode(data);
        const len = buf.remaining();
        const arr = new Array(len);
        for (let i = 0; i < len; i++) arr[i] = buf.get() & 0xff;
        return arr;
    }
    const arr = new Array(data.length);
    for (let i = 0; i < data.length; i++) arr[i] = data[i] & 0xff;
    return arr;
}

export function gzipSync(buffer, options) {
    const input = toJByteArray(buffer);
    const baos = new JByteArrayOutputStream();
    const gzos = new JGZIPOutputStream(baos);
    gzos.write(input, 0, input.length);
    gzos.finish();
    gzos.close();
    return fromJByteArray(baos.toByteArray());
}

export function gunzipSync(buffer, options) {
    const input = toJByteArray(buffer);
    const bais = new JByteArrayInputStream(input);
    const gzis = new JGZIPInputStream(bais);
    const baos = new JByteArrayOutputStream();
    const chunk = new JByteArray(8192);
    let n;
    while ((n = gzis.read(chunk, 0, chunk.length)) !== -1) {
        baos.write(chunk, 0, n);
    }
    gzis.close();
    return fromJByteArray(baos.toByteArray());
}

function deflateWith(buffer, raw) {
    const input = toJByteArray(buffer);
    const deflater = new JDeflater(JDeflater.DEFAULT_COMPRESSION, !!raw);
    deflater.setInput(input);
    deflater.finish();
    const baos = new JByteArrayOutputStream();
    const chunk = new JByteArray(8192);
    while (!deflater.finished()) {
        const n = deflater.deflate(chunk);
        baos.write(chunk, 0, n);
    }
    deflater.end();
    return fromJByteArray(baos.toByteArray());
}

function inflateWith(buffer, raw) {
    const input = toJByteArray(buffer);
    const inflater = new JInflater(!!raw);
    inflater.setInput(input);
    const baos = new JByteArrayOutputStream();
    const chunk = new JByteArray(8192);
    try {
        while (!inflater.finished()) {
            const n = inflater.inflate(chunk);
            if (n === 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break;
            }
            baos.write(chunk, 0, n);
        }
    } finally {
        inflater.end();
    }
    return fromJByteArray(baos.toByteArray());
}

export function deflateSync(buffer, options) {
    return deflateWith(buffer, false);
}

export function inflateSync(buffer, options) {
    return inflateWith(buffer, false);
}

export function deflateRawSync(buffer, options) {
    return deflateWith(buffer, true);
}

export function inflateRawSync(buffer, options) {
    return inflateWith(buffer, true);
}

export function brotliCompressSync(buffer, options) {
    throw new Error("brotli not available");
}

export function brotliDecompressSync(buffer, options) {
    throw new Error("brotli not available");
}

// ---- Streaming (Transform-stream) API ----
//
// `Deflate`/`Inflate`/`DeflateRaw`/`InflateRaw` are genuinely incremental: `Deflater`
// and `Inflater` are byte-buffer state machines (not blocking `InputStream`-based
// APIs), so each `_transform` call can feed in exactly the bytes it received and drain
// exactly the output produced so far — no buffering of the whole input required.
//
// `Gzip` (compression) is incremental too, via `GZIPOutputStream#flush()` (a sync
// flush of the underlying `Deflater`, which forces pending compressed data out to the
// backing `ByteArrayOutputStream` instead of holding it back until `finish()`).
//
// `Gunzip` (decompression) is the one case `java.util.zip` doesn't offer a direct
// non-blocking incremental API for — `GZIPInputStream` reads from a blocking
// `InputStream`, which doesn't fit a push-based Transform without a background thread.
// Instead, this parses the gzip header itself (handling FEXTRA/FNAME/FCOMMENT/FHCRC,
// buffering across chunk boundaries until the header is fully available) and then
// decompresses the deflate body incrementally via a raw (nowrap) `Inflater`, ignoring
// the trailing CRC32/ISIZE trailer once the inflater reports it's finished.

function levelFromOptions(options) {
    if (options && typeof options.level === "number") return options.level;
    return JDeflater.DEFAULT_COMPRESSION;
}

class DeflateBase extends Transform {
    constructor(options, raw) {
        super(options);
        this._deflater = new JDeflater(levelFromOptions(options), !!raw);
    }

    _transform(chunk, encoding, callback) {
        try {
            const input = toJByteArray(chunk);
            this._deflater.setInput(input);
            const buf = new JByteArray(16384);
            // Force a sync flush per chunk (rather than the default `deflate(buf)`,
            // which lets the deflater buffer internally for better compression and may
            // emit nothing at all until `finish()`), so the stream is genuinely
            // incremental — each `_transform` call produces output for the bytes it
            // was just given rather than deferring everything to `_flush`.
            while (!this._deflater.needsInput()) {
                const n = this._deflater.deflate(buf, 0, buf.length, JDeflater.SYNC_FLUSH);
                if (n > 0) this.push(fromJByteArray(buf, n));
                else break;
            }
            callback();
        } catch (e) {
            callback(e);
        }
    }

    _flush(callback) {
        try {
            this._deflater.finish();
            const buf = new JByteArray(16384);
            while (!this._deflater.finished()) {
                const n = this._deflater.deflate(buf);
                if (n > 0) this.push(fromJByteArray(buf, n));
            }
            this._deflater.end();
            callback();
        } catch (e) {
            callback(e);
        }
    }
}

class InflateBase extends Transform {
    constructor(options, raw) {
        super(options);
        this._inflater = new JInflater(!!raw);
    }

    _transform(chunk, encoding, callback) {
        try {
            const input = toJByteArray(chunk);
            this._inflater.setInput(input);
            const buf = new JByteArray(16384);
            while (!this._inflater.finished() && !this._inflater.needsInput()) {
                const n = this._inflater.inflate(buf);
                if (n > 0) this.push(fromJByteArray(buf, n));
                else if (this._inflater.needsDictionary()) break;
                else break;
            }
            callback();
        } catch (e) {
            callback(e);
        }
    }

    _flush(callback) {
        try {
            const buf = new JByteArray(16384);
            while (!this._inflater.finished()) {
                const n = this._inflater.inflate(buf);
                if (n > 0) this.push(fromJByteArray(buf, n));
                else break;
            }
            this._inflater.end();
            callback();
        } catch (e) {
            callback(e);
        }
    }
}

export class Deflate extends DeflateBase {
    constructor(options) {
        super(options, false);
    }
}

export class Inflate extends InflateBase {
    constructor(options) {
        super(options, false);
    }
}

export class DeflateRaw extends DeflateBase {
    constructor(options) {
        super(options, true);
    }
}

export class InflateRaw extends InflateBase {
    constructor(options) {
        super(options, true);
    }
}

export class Gzip extends Transform {
    constructor(options) {
        super(options);
        this._baos = new JByteArrayOutputStream();
        this._gzos = new JGZIPOutputStream(this._baos);
    }

    _drain() {
        const bytes = this._baos.toByteArray();
        if (bytes.length > 0) {
            this.push(fromJByteArray(bytes));
            this._baos.reset();
        }
    }

    _transform(chunk, encoding, callback) {
        try {
            const input = toJByteArray(chunk);
            this._gzos.write(input, 0, input.length);
            this._gzos.flush();
            this._drain();
            callback();
        } catch (e) {
            callback(e);
        }
    }

    _flush(callback) {
        try {
            this._gzos.finish();
            this._drain();
            this._gzos.close();
            callback();
        } catch (e) {
            callback(e);
        }
    }
}

// Parses a gzip member header (RFC 1952 §2.3) from `bytes` (a plain JS array of
// unsigned byte values, possibly assembled across several chunks). Returns the header
// length in bytes once it can be fully determined, or `null` if `bytes` doesn't yet
// contain enough data to know where the header ends (caller should buffer more and
// retry).
function parseGzipHeaderLength(bytes) {
    if (bytes.length < 10) return null;
    if (bytes[0] !== 0x1f || bytes[1] !== 0x8b) {
        throw new Error("incorrect header check");
    }
    const flg = bytes[3];
    let pos = 10;
    if (flg & 0x04) {
        // FEXTRA
        if (bytes.length < pos + 2) return null;
        const xlen = bytes[pos] | (bytes[pos + 1] << 8);
        pos += 2 + xlen;
        if (bytes.length < pos) return null;
    }
    if (flg & 0x08) {
        // FNAME (null-terminated)
        let end = pos;
        while (bytes[end] !== 0) {
            if (end >= bytes.length) return null;
            end++;
        }
        if (end >= bytes.length) return null;
        pos = end + 1;
    }
    if (flg & 0x10) {
        // FCOMMENT (null-terminated)
        let end = pos;
        while (bytes[end] !== 0) {
            if (end >= bytes.length) return null;
            end++;
        }
        if (end >= bytes.length) return null;
        pos = end + 1;
    }
    if (flg & 0x02) {
        // FHCRC
        if (bytes.length < pos + 2) return null;
        pos += 2;
    }
    return pos;
}

export class Gunzip extends Transform {
    constructor(options) {
        super(options);
        this._headerBuf = [];
        this._headerParsed = false;
        this._inflater = new JInflater(true);
    }

    _feedInflate(intArr) {
        if (intArr.length === 0 || this._inflater.finished()) return;
        const input = toJByteArray(intArr);
        this._inflater.setInput(input);
        const buf = new JByteArray(16384);
        while (!this._inflater.finished() && !this._inflater.needsInput()) {
            const n = this._inflater.inflate(buf);
            if (n > 0) this.push(fromJByteArray(buf, n));
            else if (this._inflater.needsDictionary()) break;
            else break;
        }
    }

    _transform(chunk, encoding, callback) {
        try {
            const bytes = toIntArray(chunk);
            if (!this._headerParsed) {
                for (let i = 0; i < bytes.length; i++) this._headerBuf.push(bytes[i]);
                const headerLen = parseGzipHeaderLength(this._headerBuf);
                if (headerLen === null) {
                    callback();
                    return;
                }
                this._headerParsed = true;
                const body = this._headerBuf.slice(headerLen);
                this._headerBuf = null;
                this._feedInflate(body);
            } else {
                this._feedInflate(bytes);
            }
            callback();
        } catch (e) {
            callback(e);
        }
    }

    _flush(callback) {
        try {
            if (this._headerParsed) {
                const buf = new JByteArray(16384);
                while (!this._inflater.finished()) {
                    const n = this._inflater.inflate(buf);
                    if (n > 0) this.push(fromJByteArray(buf, n));
                    else break;
                }
            }
            this._inflater.end();
            callback();
        } catch (e) {
            callback(e);
        }
    }
}

// Brotli isn't available (no pure-JVM/java.util.zip codec); the streaming classes
// exist for API completeness but emit an 'error' as soon as any data is written to
// them, mirroring the *Sync functions above throwing immediately.
export class BrotliCompress extends Transform {
    _transform(chunk, encoding, callback) {
        callback(new Error("brotli not available"));
    }
}

export class BrotliDecompress extends Transform {
    _transform(chunk, encoding, callback) {
        callback(new Error("brotli not available"));
    }
}

export function createGzip(options) {
    return new Gzip(options);
}

export function createGunzip(options) {
    return new Gunzip(options);
}

export function createDeflate(options) {
    return new Deflate(options);
}

export function createInflate(options) {
    return new Inflate(options);
}

export function createDeflateRaw(options) {
    return new DeflateRaw(options);
}

export function createInflateRaw(options) {
    return new InflateRaw(options);
}

export function createBrotliCompress(options) {
    return new BrotliCompress(options);
}

export function createBrotliDecompress(options) {
    return new BrotliDecompress(options);
}

// Async wrappers: no genuine async implementation (no worker-thread pool here) — each
// simply runs the corresponding *Sync function immediately and reports the result via a
// microtask-deferred callback, so callers using the callback form still work.
function asyncify(syncFn) {
    return function (buffer, optionsOrCallback, maybeCallback) {
        const callback = typeof optionsOrCallback === "function" ? optionsOrCallback : maybeCallback;
        const options = typeof optionsOrCallback === "function" ? undefined : optionsOrCallback;
        Promise.resolve().then(() => {
            try {
                const result = syncFn(buffer, options);
                if (callback) callback(null, result);
            } catch (e) {
                if (callback) callback(e);
            }
        });
    };
}

export const gzip = asyncify(gzipSync);
export const gunzip = asyncify(gunzipSync);
export const deflate = asyncify(deflateSync);
export const inflate = asyncify(inflateSync);
export const deflateRaw = asyncify(deflateRawSync);
export const inflateRaw = asyncify(inflateRawSync);

export const Z_NO_COMPRESSION = 0;
export const Z_BEST_SPEED = 1;
export const Z_BEST_COMPRESSION = 9;
export const Z_DEFAULT_COMPRESSION = -1;

export const Z_NO_FLUSH = 0;
export const Z_PARTIAL_FLUSH = 1;
export const Z_SYNC_FLUSH = 2;
export const Z_FULL_FLUSH = 3;
export const Z_FINISH = 4;
export const Z_BLOCK = 5;
export const Z_TREES = 6;

export const Z_OK = 0;
export const Z_STREAM_END = 1;
export const Z_NEED_DICT = 2;
export const Z_ERRNO = -1;
export const Z_STREAM_ERROR = -2;
export const Z_DATA_ERROR = -3;
export const Z_MEM_ERROR = -4;
export const Z_BUF_ERROR = -5;
export const Z_VERSION_ERROR = -6;

export const Z_FILTERED = 1;
export const Z_HUFFMAN_ONLY = 2;
export const Z_RLE = 3;
export const Z_FIXED = 4;
export const Z_DEFAULT_STRATEGY = 0;

export const Z_DEFAULT_CHUNK = 16384;
export const Z_DEFAULT_MEMLEVEL = 8;
export const Z_MIN_LEVEL = 0;
export const Z_MAX_LEVEL = 9;
export const Z_MIN_MEMLEVEL = 1;
export const Z_MAX_MEMLEVEL = 9;
export const Z_MIN_CHUNK = 64;
export const Z_MAX_CHUNK = Infinity;
export const Z_MIN_WINDOWBITS = 8;
export const Z_MAX_WINDOWBITS = 15;
export const Z_DEFAULT_WINDOWBITS = 15;

export const constants = {
    Z_NO_COMPRESSION,
    Z_BEST_SPEED,
    Z_BEST_COMPRESSION,
    Z_DEFAULT_COMPRESSION,
    Z_NO_FLUSH,
    Z_PARTIAL_FLUSH,
    Z_SYNC_FLUSH,
    Z_FULL_FLUSH,
    Z_FINISH,
    Z_BLOCK,
    Z_TREES,
    Z_OK,
    Z_STREAM_END,
    Z_NEED_DICT,
    Z_ERRNO,
    Z_STREAM_ERROR,
    Z_DATA_ERROR,
    Z_MEM_ERROR,
    Z_BUF_ERROR,
    Z_VERSION_ERROR,
    Z_FILTERED,
    Z_HUFFMAN_ONLY,
    Z_RLE,
    Z_FIXED,
    Z_DEFAULT_STRATEGY,
    Z_DEFAULT_CHUNK,
    Z_DEFAULT_MEMLEVEL,
    Z_MIN_LEVEL,
    Z_MAX_LEVEL,
    Z_MIN_MEMLEVEL,
    Z_MAX_MEMLEVEL,
    Z_MIN_CHUNK,
    Z_MAX_CHUNK,
    Z_MIN_WINDOWBITS,
    Z_MAX_WINDOWBITS,
    Z_DEFAULT_WINDOWBITS,
};

export default {
    gzipSync,
    gunzipSync,
    deflateSync,
    inflateSync,
    deflateRawSync,
    inflateRawSync,
    brotliCompressSync,
    brotliDecompressSync,
    gzip,
    gunzip,
    deflate,
    inflate,
    deflateRaw,
    inflateRaw,
    createGzip,
    createGunzip,
    createDeflate,
    createInflate,
    createDeflateRaw,
    createInflateRaw,
    createBrotliCompress,
    createBrotliDecompress,
    Gzip,
    Gunzip,
    Deflate,
    Inflate,
    DeflateRaw,
    InflateRaw,
    BrotliCompress,
    BrotliDecompress,
    constants,
    Z_NO_COMPRESSION,
    Z_BEST_SPEED,
    Z_BEST_COMPRESSION,
    Z_DEFAULT_COMPRESSION,
};

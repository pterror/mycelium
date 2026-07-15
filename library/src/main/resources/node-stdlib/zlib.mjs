// Minimal Node.js-compatible `zlib` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "zlib"` / `"node:zlib"`. Backed by
// `java.util.zip` (`GZIPOutputStream`/`GZIPInputStream` for gzip, `Deflater`/`Inflater`
// for raw/zlib deflate). Only the `*Sync` functions are implemented — no async/callback
// or streaming (Transform-stream) API — which is enough for the common "compress this
// buffer" use case npm packages tend to reach for.

const JByteArrayOutputStream = Java.type("java.io.ByteArrayOutputStream");
const JByteArrayInputStream = Java.type("java.io.ByteArrayInputStream");
const JGZIPOutputStream = Java.type("java.util.zip.GZIPOutputStream");
const JGZIPInputStream = Java.type("java.util.zip.GZIPInputStream");
const JDeflater = Java.type("java.util.zip.Deflater");
const JInflater = Java.type("java.util.zip.Inflater");
const JByteArray = Java.type("byte[]");

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

function fromJByteArray(jBytes, length) {
    const len = length === undefined ? jBytes.length : length;
    const arr = new Uint8Array(len);
    for (let i = 0; i < len; i++) arr[i] = jBytes[i] & 0xff;
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

export const constants = {
    Z_NO_COMPRESSION,
    Z_BEST_SPEED,
    Z_BEST_COMPRESSION,
    Z_DEFAULT_COMPRESSION,
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
    constants,
    Z_NO_COMPRESSION,
    Z_BEST_SPEED,
    Z_BEST_COMPRESSION,
    Z_DEFAULT_COMPRESSION,
};

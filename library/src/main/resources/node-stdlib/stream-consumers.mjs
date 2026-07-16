// Minimal Node.js-compatible `stream/consumers` module, served by Mycelium as the
// synthetic core module resolved for `import ... from "stream/consumers"` /
// `"node:stream/consumers"`. Each export drains an async-iterable stream (a Node
// `Readable`, a Web `ReadableStream` — both are async-iterable per stream.mjs and
// stream-web.mjs — or any other `for await`-able object) into a single in-memory value.
//
// `Buffer` is a Mycelium global (see Mycelium.kt's Buffer bootstrap script, which wires
// `globalThis.Buffer` to buffer.mjs's `Buffer` the same way Node itself exposes it as a
// global) — referenced here as a bare global rather than imported, mirroring how plain
// Node userland code consuming these APIs would use it too.

async function buffer(stream) {
    const chunks = [];
    for await (const chunk of stream) {
        chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
    }
    return Buffer.concat(chunks);
}

async function arrayBuffer(stream) {
    const buf = await buffer(stream);
    return buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
}

async function bytes(stream) {
    const buf = await buffer(stream);
    return new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength);
}

async function text(stream) {
    const buf = await buffer(stream);
    return buf.toString("utf8");
}

async function json(stream) {
    return JSON.parse(await text(stream));
}

async function blob(stream) {
    const buf = await buffer(stream);
    if (typeof globalThis.Blob === "function") {
        return new globalThis.Blob([buf]);
    }
    // No global `Blob` is wired up in Mycelium's guest environment (see the module doc
    // comment) — fall back to a minimal object shaped like the parts of the real Blob API
    // (`size`/`type`/`arrayBuffer()`/`text()`) that a caller consuming this is most likely
    // to actually use, rather than throwing outright.
    return {
        size: buf.length,
        type: "",
        arrayBuffer: async () => buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength),
        text: async () => buf.toString("utf8"),
    };
}

export { arrayBuffer, blob, buffer, bytes, json, text };

export default { arrayBuffer, blob, buffer, bytes, json, text };

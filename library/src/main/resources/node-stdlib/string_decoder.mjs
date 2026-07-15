// Minimal Node.js-compatible `string_decoder` module, served by Mycelium as the synthetic
// core module resolved for `import ... from "string_decoder"` / `"node:string_decoder"`.
// The tricky part of this module — correctly buffering an incomplete multi-byte sequence
// that's split across two `write()` calls until the rest of it arrives — is exactly what
// `java.nio.charset.CharsetDecoder` already does when fed a `ByteBuffer` and told not to
// flush (`endOfInput = false`): it consumes as many complete characters as the input
// contains and leaves any trailing partial sequence in the buffer's remaining bytes for
// the next call, so it's used here directly rather than re-implementing UTF-8 boundary
// detection in JS.

const JCharset = Java.type("java.nio.charset.Charset");
const JByteBuffer = Java.type("java.nio.ByteBuffer");
const JCharBuffer = Java.type("java.nio.CharBuffer");
const JCodingErrorAction = Java.type("java.nio.charset.CodingErrorAction");
const JByteArray = Java.type("byte[]");
const JSystem = Java.type("java.lang.System");

function normalizeEncoding(encoding) {
    if (!encoding) return "utf8";
    const e = String(encoding).toLowerCase();
    if (e === "utf-8") return "utf8";
    if (e === "binary") return "latin1";
    if (e === "ucs2" || e === "ucs-2") return "utf16le";
    return e;
}

function javaCharsetNameFor(encoding) {
    switch (normalizeEncoding(encoding)) {
        case "utf8":
            return "UTF-8";
        case "ascii":
        case "latin1":
            return "ISO-8859-1";
        case "utf16le":
            return "UTF-16LE";
        case "base64":
        case "hex":
            // Not real text encodings; StringDecoder falls back to treating input as raw
            // bytes decoded as-is for these, matching Node's own limited handling.
            return "ISO-8859-1";
        default:
            return "UTF-8";
    }
}

function toJByteArray(data) {
    if (typeof data === "string") {
        // Already a string — nothing to decode; callers shouldn't normally pass one, but
        // tolerate it by round-tripping through UTF-8 bytes.
        const buf = JCharset.forName("UTF-8").encode(data);
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

export class StringDecoder {
    constructor(encoding) {
        this.encoding = normalizeEncoding(encoding);
        this._decoder = JCharset.forName(javaCharsetNameFor(this.encoding)).newDecoder();
        this._decoder.onMalformedInput(JCodingErrorAction.REPLACE);
        this._decoder.onUnmappableCharacter(JCodingErrorAction.REPLACE);
        // Bytes carried over from a previous write() that didn't yet form a complete
        // character, prepended to the next chunk before decoding.
        this._pending = new JByteArray(0);
    }

    _decode(jBytes, endOfInput) {
        let input = jBytes;
        if (this._pending.length > 0) {
            const combined = new JByteArray(this._pending.length + jBytes.length);
            JSystem.arraycopy(this._pending, 0, combined, 0, this._pending.length);
            JSystem.arraycopy(jBytes, 0, combined, this._pending.length, jBytes.length);
            input = combined;
        }
        const inBuf = JByteBuffer.wrap(input);
        // Char capacity: worst case one char per byte is always enough headroom for every
        // charset used here (UTF-8/UTF-16LE/Latin1 all expand, never shrink, byte->char).
        const outBuf = JCharBuffer.allocate(input.length + 1);
        this._decoder.decode(inBuf, outBuf, endOfInput);
        outBuf.flip();
        const text = String(outBuf.toString());

        const remaining = inBuf.remaining();
        if (remaining > 0) {
            const leftover = new JByteArray(remaining);
            inBuf.get(leftover);
            this._pending = leftover;
        } else {
            this._pending = new JByteArray(0);
        }
        return text;
    }

    write(buffer) {
        return this._decode(toJByteArray(buffer), false);
    }

    end(buffer) {
        let tail = "";
        if (buffer !== undefined) {
            tail = this._decode(toJByteArray(buffer), true);
        } else if (this._pending.length > 0) {
            tail = this._decode(new JByteArray(0), true);
        }
        this._decoder.reset();
        this._pending = new JByteArray(0);
        return tail;
    }

    text(buffer, offset) {
        return this.write(buffer);
    }
}

export default { StringDecoder };

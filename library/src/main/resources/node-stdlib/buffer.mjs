// Minimal Node.js-compatible `buffer` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "buffer"` / `"node:buffer"`. Backed internally by
// a plain `Uint8Array`, with `Java.type(...)` interop used only where the conversion
// genuinely needs it (base64 encode/decode, UTF-8 <-> bytes).

const JBase64 = Java.type("java.util.Base64");
const JString = Java.type("java.lang.String");
const JByteArray = Java.type("byte[]");
const JCharset = Java.type("java.nio.charset.StandardCharsets");

function normalizeEncoding(encoding) {
    if (!encoding) return "utf8";
    const e = String(encoding).toLowerCase();
    if (e === "utf-8") return "utf8";
    if (e === "binary") return "latin1";
    if (e === "ucs2" || e === "ucs-2") return "utf16le";
    return e;
}

function jCharsetFor(encoding) {
    switch (normalizeEncoding(encoding)) {
        case "utf8":
            return JCharset.UTF_8;
        case "ascii":
        case "latin1":
            return JCharset.ISO_8859_1;
        case "utf16le":
            return JCharset.UTF_16LE;
        default:
            return JCharset.UTF_8;
    }
}

function toJByteArray(u8) {
    const bytes = new JByteArray(u8.length);
    for (let i = 0; i < u8.length; i++) {
        const v = u8[i];
        bytes[i] = v > 127 ? v - 256 : v;
    }
    return bytes;
}

function fromJByteArray(jBytes) {
    const arr = new Uint8Array(jBytes.length);
    for (let i = 0; i < jBytes.length; i++) {
        arr[i] = jBytes[i] & 0xff;
    }
    return arr;
}

const HEX_CHARS = "0123456789abcdef";

function bytesToHex(u8, start, end) {
    let out = "";
    for (let i = start; i < end; i++) {
        const b = u8[i];
        out += HEX_CHARS[(b >> 4) & 0xf];
        out += HEX_CHARS[b & 0xf];
    }
    return out;
}

function hexToBytes(str) {
    const clean = str.length % 2 === 0 ? str : str.slice(0, -1);
    const out = new Uint8Array(clean.length / 2);
    for (let i = 0; i < out.length; i++) {
        out[i] = parseInt(clean.substr(i * 2, 2), 16) & 0xff;
    }
    return out;
}

// Encodes a JS string to bytes via Charset#encode, which returns a ByteBuffer (a host
// object, unaffected by GraalJS's auto-unboxing of java.lang.String results) rather than
// going through java.lang.String#getBytes — calling that on a `new java.lang.String(...)`
// result doesn't work, because GraalJS automatically unboxes host String instances
// crossing back into JS to plain JS string primitives, which have no `.getBytes` method
// (mirrors fs.mjs's encodeString for the same reason).
function encodeStringToBytes(str, encoding) {
    const enc = normalizeEncoding(encoding);
    if (enc === "hex") return hexToBytes(str);
    if (enc === "base64" || enc === "base64url") {
        const decoder = enc === "base64url" ? JBase64.getUrlDecoder() : JBase64.getDecoder();
        return fromJByteArray(decoder.decode(str));
    }
    const buf = jCharsetFor(enc).encode(str);
    const len = buf.remaining();
    const jBytes = new JByteArray(len);
    buf.get(jBytes);
    return fromJByteArray(jBytes);
}

function decodeBytesToString(u8, encoding, start, end) {
    const enc = normalizeEncoding(encoding);
    const s = start === undefined ? 0 : Math.max(0, start);
    const e = end === undefined ? u8.length : Math.min(u8.length, end);
    const slice = u8.subarray(s, e);
    if (enc === "hex") return bytesToHex(slice, 0, slice.length);
    if (enc === "base64") {
        return String(JBase64.getEncoder().encodeToString(toJByteArray(slice)));
    }
    if (enc === "base64url") {
        return String(JBase64.getUrlEncoder().withoutPadding().encodeToString(toJByteArray(slice)));
    }
    return String(new JString(toJByteArray(slice), jCharsetFor(enc)));
}

function byteLengthOf(str, encoding) {
    const enc = normalizeEncoding(encoding);
    if (enc === "hex") return Math.floor(str.length / 2);
    if (enc === "base64" || enc === "base64url") {
        return encodeStringToBytes(str, enc).length;
    }
    if (enc === "ascii" || enc === "latin1") return str.length;
    if (enc === "utf16le") return str.length * 2;
    // utf8: count actual encoded bytes
    return JCharset.UTF_8.encode(str).remaining();
}

const ENCODINGS = new Set([
    "utf8", "utf-8", "ascii", "hex", "base64", "base64url", "latin1", "binary", "ucs2", "ucs-2", "utf16le",
]);

class Buffer extends Uint8Array {
    toString(encoding, start, end) {
        return decodeBytesToString(this, encoding, start, end);
    }

    toJSON() {
        return { type: "Buffer", data: Array.from(this) };
    }

    slice(start, end) {
        return Buffer.from(this.subarray(normalizeStart(start, this.length), normalizeEnd(end, this.length)));
    }

    subarray(start, end) {
        const result = Uint8Array.prototype.subarray.call(this, start, end);
        Object.setPrototypeOf(result, Buffer.prototype);
        return result;
    }

    copy(target, targetStart, sourceStart, sourceEnd) {
        const tStart = targetStart || 0;
        const sStart = sourceStart || 0;
        const sEnd = sourceEnd === undefined ? this.length : sourceEnd;
        let count = 0;
        for (let i = sStart; i < sEnd && tStart + count < target.length; i++, count++) {
            target[tStart + count] = this[i];
        }
        return count;
    }

    equals(other) {
        if (this.length !== other.length) return false;
        for (let i = 0; i < this.length; i++) {
            if (this[i] !== other[i]) return false;
        }
        return true;
    }

    compare(other) {
        const len = Math.min(this.length, other.length);
        for (let i = 0; i < len; i++) {
            if (this[i] !== other[i]) return this[i] < other[i] ? -1 : 1;
        }
        if (this.length === other.length) return 0;
        return this.length < other.length ? -1 : 1;
    }

    indexOf(value, byteOffset, encoding) {
        const needle = valueToBytes(value, encoding);
        const start = byteOffset || 0;
        outer: for (let i = start; i <= this.length - needle.length; i++) {
            for (let j = 0; j < needle.length; j++) {
                if (this[i + j] !== needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    includes(value, byteOffset) {
        return this.indexOf(value, byteOffset) !== -1;
    }

    fill(value, start, end) {
        const s = normalizeStart(start, this.length);
        const e = normalizeEnd(end, this.length);
        if (typeof value === "string") {
            const bytes = encodeStringToBytes(value, "utf8");
            for (let i = s, k = 0; i < e; i++, k = (k + 1) % bytes.length) {
                this[i] = bytes[k];
            }
        } else if (typeof value === "number") {
            for (let i = s; i < e; i++) this[i] = value & 0xff;
        } else {
            for (let i = s, k = 0; i < e; i++, k = (k + 1) % value.length) {
                this[i] = value[k];
            }
        }
        return this;
    }

    write(string, offset, length, encoding) {
        let off = offset;
        let len = length;
        let enc = encoding;
        if (typeof off === "string") {
            enc = off;
            off = undefined;
        } else if (typeof len === "string") {
            enc = len;
            len = undefined;
        }
        off = off || 0;
        const bytes = encodeStringToBytes(string, enc);
        const maxLen = len === undefined ? Math.min(bytes.length, this.length - off) : Math.min(len, bytes.length, this.length - off);
        for (let i = 0; i < maxLen; i++) this[off + i] = bytes[i];
        return maxLen;
    }

    readUInt8(offset) {
        return this[offset || 0];
    }
    readInt8(offset) {
        const v = this[offset || 0];
        return v > 127 ? v - 256 : v;
    }
    writeUInt8(value, offset) {
        this[offset || 0] = value & 0xff;
        return (offset || 0) + 1;
    }
    writeInt8(value, offset) {
        this[offset || 0] = value & 0xff;
        return (offset || 0) + 1;
    }

    readUInt16BE(offset) {
        const o = offset || 0;
        return (this[o] << 8) | this[o + 1];
    }
    readUInt16LE(offset) {
        const o = offset || 0;
        return (this[o + 1] << 8) | this[o];
    }
    readInt16BE(offset) {
        const v = this.readUInt16BE(offset);
        return v > 0x7fff ? v - 0x10000 : v;
    }
    readInt16LE(offset) {
        const v = this.readUInt16LE(offset);
        return v > 0x7fff ? v - 0x10000 : v;
    }
    writeUInt16BE(value, offset) {
        const o = offset || 0;
        this[o] = (value >> 8) & 0xff;
        this[o + 1] = value & 0xff;
        return o + 2;
    }
    writeUInt16LE(value, offset) {
        const o = offset || 0;
        this[o] = value & 0xff;
        this[o + 1] = (value >> 8) & 0xff;
        return o + 2;
    }
    writeInt16BE(value, offset) {
        return this.writeUInt16BE(value < 0 ? value + 0x10000 : value, offset);
    }
    writeInt16LE(value, offset) {
        return this.writeUInt16LE(value < 0 ? value + 0x10000 : value, offset);
    }

    readUInt32BE(offset) {
        const o = offset || 0;
        return ((this[o] << 24) | (this[o + 1] << 16) | (this[o + 2] << 8) | this[o + 3]) >>> 0;
    }
    readUInt32LE(offset) {
        const o = offset || 0;
        return ((this[o + 3] << 24) | (this[o + 2] << 16) | (this[o + 1] << 8) | this[o]) >>> 0;
    }
    readInt32BE(offset) {
        const o = offset || 0;
        return (this[o] << 24) | (this[o + 1] << 16) | (this[o + 2] << 8) | this[o + 3];
    }
    readInt32LE(offset) {
        const o = offset || 0;
        return (this[o + 3] << 24) | (this[o + 2] << 16) | (this[o + 1] << 8) | this[o];
    }
    writeUInt32BE(value, offset) {
        const o = offset || 0;
        this[o] = (value >>> 24) & 0xff;
        this[o + 1] = (value >>> 16) & 0xff;
        this[o + 2] = (value >>> 8) & 0xff;
        this[o + 3] = value & 0xff;
        return o + 4;
    }
    writeUInt32LE(value, offset) {
        const o = offset || 0;
        this[o] = value & 0xff;
        this[o + 1] = (value >>> 8) & 0xff;
        this[o + 2] = (value >>> 16) & 0xff;
        this[o + 3] = (value >>> 24) & 0xff;
        return o + 4;
    }
    writeInt32BE(value, offset) {
        return this.writeUInt32BE(value < 0 ? value + 0x100000000 : value, offset);
    }
    writeInt32LE(value, offset) {
        return this.writeUInt32LE(value < 0 ? value + 0x100000000 : value, offset);
    }

    readFloatBE(offset) {
        return readFloatGeneric(this, offset || 0, false, 4);
    }
    readFloatLE(offset) {
        return readFloatGeneric(this, offset || 0, true, 4);
    }
    readDoubleBE(offset) {
        return readFloatGeneric(this, offset || 0, false, 8);
    }
    readDoubleLE(offset) {
        return readFloatGeneric(this, offset || 0, true, 8);
    }
    writeFloatBE(value, offset) {
        return writeFloatGeneric(this, value, offset || 0, false, 4);
    }
    writeFloatLE(value, offset) {
        return writeFloatGeneric(this, value, offset || 0, true, 4);
    }
    writeDoubleBE(value, offset) {
        return writeFloatGeneric(this, value, offset || 0, false, 8);
    }
    writeDoubleLE(value, offset) {
        return writeFloatGeneric(this, value, offset || 0, true, 8);
    }

    readBigInt64BE(offset) {
        return dataViewFor(this, offset || 0, 8).getBigInt64(0, false);
    }
    readBigInt64LE(offset) {
        return dataViewFor(this, offset || 0, 8).getBigInt64(0, true);
    }
    readBigUInt64BE(offset) {
        return dataViewFor(this, offset || 0, 8).getBigUint64(0, false);
    }
    readBigUInt64LE(offset) {
        return dataViewFor(this, offset || 0, 8).getBigUint64(0, true);
    }
    writeBigInt64BE(value, offset) {
        const o = offset || 0;
        dataViewFor(this, o, 8).setBigInt64(0, BigInt(value), false);
        return o + 8;
    }
    writeBigInt64LE(value, offset) {
        const o = offset || 0;
        dataViewFor(this, o, 8).setBigInt64(0, BigInt(value), true);
        return o + 8;
    }
    writeBigUInt64BE(value, offset) {
        const o = offset || 0;
        dataViewFor(this, o, 8).setBigUint64(0, BigInt(value), false);
        return o + 8;
    }
    writeBigUInt64LE(value, offset) {
        const o = offset || 0;
        dataViewFor(this, o, 8).setBigUint64(0, BigInt(value), true);
        return o + 8;
    }

    swap16() {
        if (this.length % 2 !== 0) throw new RangeError("Buffer size must be a multiple of 16-bits");
        for (let i = 0; i < this.length; i += 2) {
            const tmp = this[i];
            this[i] = this[i + 1];
            this[i + 1] = tmp;
        }
        return this;
    }

    swap32() {
        if (this.length % 4 !== 0) throw new RangeError("Buffer size must be a multiple of 32-bits");
        for (let i = 0; i < this.length; i += 4) {
            let tmp = this[i];
            this[i] = this[i + 3];
            this[i + 3] = tmp;
            tmp = this[i + 1];
            this[i + 1] = this[i + 2];
            this[i + 2] = tmp;
        }
        return this;
    }

    swap64() {
        if (this.length % 8 !== 0) throw new RangeError("Buffer size must be a multiple of 64-bits");
        for (let i = 0; i < this.length; i += 8) {
            for (let j = 0; j < 4; j++) {
                const tmp = this[i + j];
                this[i + j] = this[i + 7 - j];
                this[i + 7 - j] = tmp;
            }
        }
        return this;
    }

    lastIndexOf(value, byteOffset, encoding) {
        const needle = valueToBytes(value, encoding);
        if (needle.length === 0) return this.length;
        let start = byteOffset === undefined || byteOffset === null ? this.length - needle.length : byteOffset;
        if (start < 0) start = this.length + start;
        start = Math.min(start, this.length - needle.length);
        outer: for (let i = start; i >= 0; i--) {
            for (let j = 0; j < needle.length; j++) {
                if (this[i + j] !== needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}

function dataViewFor(buf, offset, byteSize) {
    return new DataView(buf.buffer, buf.byteOffset + offset, byteSize);
}

function readFloatGeneric(buf, offset, littleEndian, byteSize) {
    const dv = new DataView(buf.buffer, buf.byteOffset + offset, byteSize);
    return byteSize === 4 ? dv.getFloat32(0, littleEndian) : dv.getFloat64(0, littleEndian);
}

function writeFloatGeneric(buf, value, offset, littleEndian, byteSize) {
    const dv = new DataView(buf.buffer, buf.byteOffset + offset, byteSize);
    if (byteSize === 4) dv.setFloat32(0, value, littleEndian);
    else dv.setFloat64(0, value, littleEndian);
    return offset + byteSize;
}

function normalizeStart(start, len) {
    if (start === undefined) return 0;
    return start < 0 ? Math.max(0, len + start) : Math.min(start, len);
}
function normalizeEnd(end, len) {
    if (end === undefined) return len;
    return end < 0 ? Math.max(0, len + end) : Math.min(end, len);
}

function valueToBytes(value, encoding) {
    if (typeof value === "string") return encodeStringToBytes(value, encoding || "utf8");
    if (typeof value === "number") return new Uint8Array([value & 0xff]);
    return value;
}

Buffer.alloc = function (size, fill, encoding) {
    const buf = Buffer.allocUnsafe(size);
    if (fill !== undefined) {
        buf.fill(fill, 0, size, encoding);
    } else {
        buf.fill(0);
    }
    return buf;
};

Buffer.allocUnsafe = function (size) {
    const buf = new Buffer(size);
    return buf;
};

Buffer.allocUnsafeSlow = Buffer.allocUnsafe;

Buffer.from = function (value, encodingOrOffset, length) {
    if (typeof value === "string") {
        const bytes = encodeStringToBytes(value, encodingOrOffset);
        const buf = new Buffer(bytes.length);
        buf.set(bytes);
        return buf;
    }
    if (Buffer.isBuffer(value) || value instanceof Uint8Array) {
        const buf = new Buffer(value.length);
        buf.set(value);
        return buf;
    }
    if (Array.isArray(value)) {
        const buf = new Buffer(value.length);
        for (let i = 0; i < value.length; i++) buf[i] = value[i] & 0xff;
        return buf;
    }
    if (value instanceof ArrayBuffer) {
        const off = encodingOrOffset || 0;
        const len = length === undefined ? value.byteLength - off : length;
        const buf = new Buffer(len);
        buf.set(new Uint8Array(value, off, len));
        return buf;
    }
    if (value && typeof value === "object" && typeof value.length === "number") {
        const buf = new Buffer(value.length);
        for (let i = 0; i < value.length; i++) buf[i] = value[i] & 0xff;
        return buf;
    }
    throw new TypeError("The first argument must be a string, Buffer, ArrayBuffer, or Array-like object.");
};

Buffer.isBuffer = function (obj) {
    return obj instanceof Buffer;
};

Buffer.of = function (...items) {
    return Buffer.from(items);
};

Buffer.copyBytesFrom = function (source, sourceStart, sourceLength) {
    const bytesPerElement = source.BYTES_PER_ELEMENT || 1;
    const start = sourceStart || 0;
    const length = sourceLength === undefined ? source.length - start : sourceLength;
    const byteOffset = (source.byteOffset || 0) + start * bytesPerElement;
    const byteLen = length * bytesPerElement;
    const bytes = new Uint8Array(source.buffer, byteOffset, byteLen);
    const buf = new Buffer(byteLen);
    buf.set(bytes);
    return buf;
};

Buffer.byteLength = function (string, encoding) {
    if (typeof string !== "string") return string.length;
    return byteLengthOf(string, encoding);
};

Buffer.concat = function (list, totalLength) {
    let total = totalLength;
    if (total === undefined) {
        total = 0;
        for (const b of list) total += b.length;
    }
    const buf = Buffer.allocUnsafe(total);
    let offset = 0;
    for (const b of list) {
        const n = Math.min(b.length, total - offset);
        if (n <= 0) break;
        buf.set(b.subarray(0, n), offset);
        offset += n;
    }
    if (offset < total) buf.fill(0, offset, total);
    return buf;
};

Buffer.isEncoding = function (encoding) {
    return ENCODINGS.has(String(encoding).toLowerCase());
};

Buffer.compare = function (a, b) {
    return Buffer.prototype.compare.call(a, b);
};

export const constants = {
    MAX_LENGTH: 2147483647, // 2^31 - 1 (V8 limit, use same for compat)
    MAX_STRING_LENGTH: 536870888, // V8's max string length
};

Buffer.constants = constants;

export { Buffer };

export default { Buffer, constants };

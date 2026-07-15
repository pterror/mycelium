// Minimal Node.js-compatible `crypto` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "crypto"` / `"node:crypto"`. Backed by
// `java.security` / `javax.crypto` via `Java.type(...)`.

const JMessageDigest = Java.type("java.security.MessageDigest");
const JMac = Java.type("javax.crypto.Mac");
const JSecretKeySpec = Java.type("javax.crypto.spec.SecretKeySpec");
const JSecureRandom = Java.type("java.security.SecureRandom");
const JUUID = Java.type("java.util.UUID");
const JBase64 = Java.type("java.util.Base64");
const JString = Java.type("java.lang.String");
const JCharset = Java.type("java.nio.charset.StandardCharsets");
const JByteArray = Java.type("byte[]");
const JCipher = Java.type("javax.crypto.Cipher");
const JIvParameterSpec = Java.type("javax.crypto.spec.IvParameterSpec");

const secureRandom = new JSecureRandom();

const HASH_ALGORITHMS = {
    md5: "MD5",
    sha1: "SHA-1",
    sha224: "SHA-224",
    sha256: "SHA-256",
    sha384: "SHA-384",
    sha512: "SHA-512",
};

const HMAC_ALGORITHMS = {
    md5: "HmacMD5",
    sha1: "HmacSHA1",
    sha224: "HmacSHA224",
    sha256: "HmacSHA256",
    sha384: "HmacSHA384",
    sha512: "HmacSHA512",
};

const HEX_CHARS = "0123456789abcdef";

function bytesToHex(jBytes) {
    let out = "";
    for (let i = 0; i < jBytes.length; i++) {
        const b = jBytes[i] & 0xff;
        out += HEX_CHARS[(b >> 4) & 0xf];
        out += HEX_CHARS[b & 0xf];
    }
    return out;
}

function bytesToBase64(jBytes) {
    return String(JBase64.getEncoder().encodeToString(jBytes));
}

// See buffer.mjs's encodeStringToBytes comment: `new java.lang.String(...).getBytes(...)`
// doesn't work in GraalJS because host String instances crossing back into JS get
// auto-unboxed to plain JS string primitives (which have no `.getBytes` method) — use
// Charset#encode (which returns a host ByteBuffer, unaffected by that unboxing) instead.
function toJByteArray(data) {
    if (typeof data === "string") {
        const buf = JCharset.UTF_8.encode(data);
        const len = buf.remaining();
        const bytes = new JByteArray(len);
        buf.get(bytes);
        return bytes;
    }
    // Buffer/Uint8Array-like
    const bytes = new JByteArray(data.length);
    for (let i = 0; i < data.length; i++) {
        const v = data[i];
        bytes[i] = v > 127 ? v - 256 : v;
    }
    return bytes;
}

function fromJByteArray(jBytes) {
    const arr = new Uint8Array(jBytes.length);
    for (let i = 0; i < jBytes.length; i++) arr[i] = jBytes[i] & 0xff;
    return arr;
}

function digestOutput(jBytes, encoding) {
    if (!encoding || encoding === "buffer") return fromJByteArray(jBytes);
    if (encoding === "hex") return bytesToHex(jBytes);
    if (encoding === "base64") return bytesToBase64(jBytes);
    if (encoding === "base64url") return String(JBase64.getUrlEncoder().withoutPadding().encodeToString(jBytes));
    if (encoding === "latin1" || encoding === "binary") return String(new JString(jBytes, JCharset.ISO_8859_1));
    return bytesToHex(jBytes);
}

class Hash {
    constructor(algorithm) {
        const javaAlg = HASH_ALGORITHMS[algorithm.toLowerCase()] || algorithm;
        this._digest = JMessageDigest.getInstance(javaAlg);
    }

    update(data, inputEncoding) {
        this._digest.update(toJByteArray(data));
        return this;
    }

    digest(encoding) {
        const jBytes = this._digest.digest();
        return digestOutput(jBytes, encoding);
    }

    copy() {
        const h = Object.create(Hash.prototype);
        h._digest = this._digest.clone();
        return h;
    }
}

class Hmac {
    constructor(algorithm, key) {
        const javaAlg = HMAC_ALGORITHMS[algorithm.toLowerCase()] || `Hmac${algorithm.toUpperCase()}`;
        this._mac = JMac.getInstance(javaAlg);
        this._mac.init(new JSecretKeySpec(toJByteArray(key), javaAlg));
    }

    update(data) {
        this._mac.update(toJByteArray(data));
        return this;
    }

    digest(encoding) {
        const jBytes = this._mac.doFinal();
        return digestOutput(jBytes, encoding);
    }
}

export function createHash(algorithm) {
    return new Hash(algorithm);
}

export function createHmac(algorithm, key) {
    return new Hmac(algorithm, key);
}

export function randomBytes(size, callback) {
    const jBytes = new JByteArray(size);
    secureRandom.nextBytes(jBytes);
    const arr = fromJByteArray(jBytes);
    // Give the resulting Uint8Array Buffer-like `.toString(encoding)` ergonomics without
    // taking a hard dependency on the `buffer` module (crypto and buffer are independent
    // core modules; duplicating this one small helper avoids a cross-module import).
    arr.toString = function (encoding) {
        return digestOutput(jBytes, encoding || "hex");
    };
    if (typeof callback === "function") {
        callback(null, arr);
        return undefined;
    }
    return arr;
}

export function randomUUID() {
    return String(JUUID.randomUUID().toString());
}

export function randomInt(min, max) {
    let lo = min;
    let hi = max;
    if (hi === undefined) {
        hi = lo;
        lo = 0;
    }
    const range = hi - lo;
    return lo + Math.floor(secureRandomDouble() * range);
}

function secureRandomDouble() {
    return secureRandom.nextDouble();
}

export function timingSafeEqual(a, b) {
    if (a.length !== b.length) {
        throw new RangeError("Input buffers must have the same byte length");
    }
    let diff = 0;
    for (let i = 0; i < a.length; i++) {
        diff |= a[i] ^ b[i];
    }
    return diff === 0;
}

export function getHashes() {
    return Object.keys(HASH_ALGORITHMS).concat(Object.values(HASH_ALGORITHMS).map((s) => s.toLowerCase()));
}

const CIPHER_TRANSFORMS = {
    "aes-128-cbc": "AES/CBC/PKCS5Padding",
    "aes-192-cbc": "AES/CBC/PKCS5Padding",
    "aes-256-cbc": "AES/CBC/PKCS5Padding",
    "aes-128-ecb": "AES/ECB/PKCS5Padding",
    "aes-256-ecb": "AES/ECB/PKCS5Padding",
};

class Cipheriv {
    constructor(algorithm, key, iv, mode) {
        const transform = CIPHER_TRANSFORMS[algorithm.toLowerCase()];
        if (!transform) throw new Error(`Unsupported cipher algorithm: ${algorithm}`);
        this._cipher = JCipher.getInstance(transform);
        const keySpec = new JSecretKeySpec(toJByteArray(key), "AES");
        if (transform.includes("CBC")) {
            this._cipher.init(mode, keySpec, new JIvParameterSpec(toJByteArray(iv)));
        } else {
            this._cipher.init(mode, keySpec);
        }
    }

    update(data, inputEncoding, outputEncoding) {
        const out = this._cipher.update(toJByteArray(data));
        if (out === null) return outputEncoding ? "" : new Uint8Array(0);
        return outputEncoding ? digestOutput(out, outputEncoding) : fromJByteArray(out);
    }

    final(outputEncoding) {
        const out = this._cipher.doFinal();
        return outputEncoding ? digestOutput(out, outputEncoding) : fromJByteArray(out);
    }
}

export function createCipheriv(algorithm, key, iv) {
    return new Cipheriv(algorithm, key, iv, JCipher.ENCRYPT_MODE);
}

export function createDecipheriv(algorithm, key, iv) {
    return new Cipheriv(algorithm, key, iv, JCipher.DECRYPT_MODE);
}

export default {
    createHash,
    createHmac,
    randomBytes,
    randomUUID,
    randomInt,
    timingSafeEqual,
    getHashes,
    createCipheriv,
    createDecipheriv,
};

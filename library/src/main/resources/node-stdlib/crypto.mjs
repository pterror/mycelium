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
const JSignature = Java.type("java.security.Signature");
const JKeyFactory = Java.type("java.security.KeyFactory");
const JKeyPairGenerator = Java.type("java.security.KeyPairGenerator");
const JPKCS8EncodedKeySpec = Java.type("java.security.spec.PKCS8EncodedKeySpec");
const JX509EncodedKeySpec = Java.type("java.security.spec.X509EncodedKeySpec");
const JECGenParameterSpec = Java.type("java.security.spec.ECGenParameterSpec");
const JRSAKeyGenParameterSpec = Java.type("java.security.spec.RSAKeyGenParameterSpec");
const JECPublicKey = Java.type("java.security.interfaces.ECPublicKey");
const JRSAKey = Java.type("java.security.interfaces.RSAKey");

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

// --- Random buffer filling ---

export function randomFillSync(buffer, offset, size) {
    const off = offset === undefined ? 0 : offset;
    const len = size === undefined ? buffer.length - off : size;
    const jBytes = new JByteArray(len);
    secureRandom.nextBytes(jBytes);
    for (let i = 0; i < len; i++) buffer[off + i] = jBytes[i] & 0xff;
    return buffer;
}

export function randomFill(buffer, offset, size, callback) {
    let off = offset;
    let len = size;
    let cb = callback;
    if (typeof off === "function") {
        cb = off;
        off = 0;
        len = undefined;
    } else if (typeof len === "function") {
        cb = len;
        len = undefined;
    }
    Promise.resolve().then(() => {
        try {
            randomFillSync(buffer, off, len);
            cb(null, buffer);
        } catch (e) {
            cb(e);
        }
    });
    return undefined;
}

// --- Key derivation functions ---

function toResultBytes(jBytes) {
    const arr = fromJByteArray(jBytes);
    arr.toString = function (encoding) {
        return digestOutput(jBytes, encoding || "utf8");
    };
    return arr;
}

// Implemented directly via HMAC (RFC 2898 §5.2) rather than `javax.crypto.SecretKeyFactory`
// + `PBEKeySpec`: PBEKeySpec (a) rejects empty salt/password arrays outright (Node allows
// both — and `scryptSync` below needs an empty salt to work, per its own RFC 7914 test
// vectors), and (b) converts its `char[]` password to bytes by truncation rather than UTF-8
// encoding, which mishandles non-ASCII passwords. Hand-rolling it on top of the existing HMAC
// plumbing sidesteps both issues and matches Node's raw-bytes treatment of password/salt.
export function pbkdf2Sync(password, salt, iterations, keylen, digest) {
    const digestName = (digest || "sha1").toLowerCase();
    const javaHmacAlg = HMAC_ALGORITHMS[digestName] || `Hmac${digestName.toUpperCase()}`;
    const passwordBytes = toJByteArray(password);
    const saltBytes = toJByteArray(salt);

    const probe = JMac.getInstance(javaHmacAlg);
    const hLen = probe.getMacLength();
    const blockCount = Math.ceil(keylen / hLen);
    const dk = new JByteArray(keylen);
    let produced = 0;

    for (let i = 1; i <= blockCount; i++) {
        const intBlock = new JByteArray(4);
        intBlock[0] = toJByte((i >>> 24) & 0xff);
        intBlock[1] = toJByte((i >>> 16) & 0xff);
        intBlock[2] = toJByte((i >>> 8) & 0xff);
        intBlock[3] = toJByte(i & 0xff);

        let u = hmacDigest(javaHmacAlg, passwordBytes, concatJBytes([saltBytes, intBlock]));
        const t = new JByteArray(hLen);
        for (let k = 0; k < hLen; k++) t[k] = u[k];
        for (let c = 1; c < iterations; c++) {
            u = hmacDigest(javaHmacAlg, passwordBytes, u);
            for (let k = 0; k < hLen; k++) t[k] ^= u[k];
        }

        const take = Math.min(hLen, keylen - produced);
        for (let k = 0; k < take; k++) dk[produced + k] = t[k];
        produced += take;
    }
    return toResultBytes(dk);
}

export function pbkdf2(password, salt, iterations, keylen, digest, callback) {
    let cb = callback;
    let digestArg = digest;
    if (typeof digestArg === "function") {
        cb = digestArg;
        digestArg = "sha1";
    }
    Promise.resolve().then(() => {
        try {
            const result = pbkdf2Sync(password, salt, iterations, keylen, digestArg);
            cb(null, result);
        } catch (e) {
            cb(e);
        }
    });
    return undefined;
}

// HKDF (RFC 5869), implemented on top of the existing HMAC support since the JDK exposes no
// dedicated HKDF primitive.
function hmacDigest(javaHmacAlg, keyBytes, dataBytes) {
    const mac = JMac.getInstance(javaHmacAlg);
    mac.init(new JSecretKeySpec(keyBytes.length === 0 ? new JByteArray(1) : keyBytes, javaHmacAlg));
    mac.update(dataBytes);
    return mac.doFinal();
}

function concatJBytes(parts) {
    let total = 0;
    for (const p of parts) total += p.length;
    const out = new JByteArray(total);
    let offset = 0;
    for (const p of parts) {
        JavaByteArrayCopy(p, out, offset);
        offset += p.length;
    }
    return out;
}

function JavaByteArrayCopy(src, dst, dstPos) {
    for (let i = 0; i < src.length; i++) dst[dstPos + i] = src[i];
}

export function hkdfSync(digest, ikm, salt, info, keylen) {
    const digestName = digest.toLowerCase();
    const javaHmacAlg = HMAC_ALGORITHMS[digestName] || `Hmac${digestName.toUpperCase()}`;
    const hashLen = JMessageDigest.getInstance(HASH_ALGORITHMS[digestName] || digestName).getDigestLength();

    const saltBytes = salt && (salt.length || 0) > 0 ? toJByteArray(salt) : new JByteArray(hashLen);
    const ikmBytes = toJByteArray(ikm);
    const infoBytes = info ? toJByteArray(info) : new JByteArray(0);

    // extract
    const prk = hmacDigest(javaHmacAlg, saltBytes, ikmBytes);

    // expand
    const n = Math.ceil(keylen / hashLen);
    if (n > 255) throw new RangeError("hkdf: keylen too large for chosen digest");
    let previous = new JByteArray(0);
    const okmParts = [];
    let produced = 0;
    for (let i = 1; i <= n && produced < keylen; i++) {
        const counter = new JByteArray(1);
        counter[0] = i > 127 ? i - 256 : i;
        const t = hmacDigest(javaHmacAlg, prk, concatJBytes([previous, infoBytes, counter]));
        okmParts.push(t);
        previous = t;
        produced += t.length;
    }
    const okm = concatJBytes(okmParts);
    const result = new JByteArray(keylen);
    for (let i = 0; i < keylen; i++) result[i] = okm[i];
    return toResultBytes(result);
}

export function hkdf(digest, ikm, salt, info, keylen, callback) {
    Promise.resolve().then(() => {
        try {
            const result = hkdfSync(digest, ikm, salt, info, keylen);
            callback(null, result.buffer ? result : result);
        } catch (e) {
            callback(e);
        }
    });
    return undefined;
}

// --- scrypt (RFC 7914) ---
//
// The JDK (as shipped by GraalVM CE) has no built-in scrypt SecretKeyFactory (that's a
// BouncyCastle/newer-JDK-provider thing not present here), so it's implemented directly in
// JS following the RFC 7914 reference algorithm: PBKDF2-HMAC-SHA256 (delegated to
// `pbkdf2Sync` above, backed by Java) bookending a pure-JS Salsa20/8-based ROMix/BlockMix
// core, which has no JDK equivalent to lean on.

function rotl(a, b) {
    return ((a << b) | (a >>> (32 - b))) >>> 0;
}

// Salsa20/8 core, operating in place on a 16-word (64-byte) block.
function salsa20_8(block) {
    const x = block.slice();
    for (let i = 0; i < 4; i++) {
        x[4] ^= rotl((x[0] + x[12]) >>> 0, 7);
        x[8] ^= rotl((x[4] + x[0]) >>> 0, 9);
        x[12] ^= rotl((x[8] + x[4]) >>> 0, 13);
        x[0] ^= rotl((x[12] + x[8]) >>> 0, 18);

        x[9] ^= rotl((x[5] + x[1]) >>> 0, 7);
        x[13] ^= rotl((x[9] + x[5]) >>> 0, 9);
        x[1] ^= rotl((x[13] + x[9]) >>> 0, 13);
        x[5] ^= rotl((x[1] + x[13]) >>> 0, 18);

        x[14] ^= rotl((x[10] + x[6]) >>> 0, 7);
        x[2] ^= rotl((x[14] + x[10]) >>> 0, 9);
        x[6] ^= rotl((x[2] + x[14]) >>> 0, 13);
        x[10] ^= rotl((x[6] + x[2]) >>> 0, 18);

        x[3] ^= rotl((x[15] + x[11]) >>> 0, 7);
        x[7] ^= rotl((x[3] + x[15]) >>> 0, 9);
        x[11] ^= rotl((x[7] + x[3]) >>> 0, 13);
        x[15] ^= rotl((x[11] + x[7]) >>> 0, 18);

        x[1] ^= rotl((x[0] + x[3]) >>> 0, 7);
        x[2] ^= rotl((x[1] + x[0]) >>> 0, 9);
        x[3] ^= rotl((x[2] + x[1]) >>> 0, 13);
        x[0] ^= rotl((x[3] + x[2]) >>> 0, 18);

        x[6] ^= rotl((x[5] + x[4]) >>> 0, 7);
        x[7] ^= rotl((x[6] + x[5]) >>> 0, 9);
        x[4] ^= rotl((x[7] + x[6]) >>> 0, 13);
        x[5] ^= rotl((x[4] + x[7]) >>> 0, 18);

        x[11] ^= rotl((x[10] + x[9]) >>> 0, 7);
        x[8] ^= rotl((x[11] + x[10]) >>> 0, 9);
        x[9] ^= rotl((x[8] + x[11]) >>> 0, 13);
        x[10] ^= rotl((x[9] + x[8]) >>> 0, 18);

        x[12] ^= rotl((x[15] + x[14]) >>> 0, 7);
        x[13] ^= rotl((x[12] + x[15]) >>> 0, 9);
        x[14] ^= rotl((x[13] + x[12]) >>> 0, 13);
        x[15] ^= rotl((x[14] + x[13]) >>> 0, 18);
    }
    for (let i = 0; i < 16; i++) block[i] = (block[i] + x[i]) >>> 0;
}

// BlockMix_{Salsa20/8, r}(B): B is 2r 16-word (64-byte) blocks, flattened.
// Per RFC 7914 §4: X starts as B[2r-1]; each Y[i] = Salsa20/8(X xor B[i]); the output
// interleaves Y[0], Y[2], ..., Y[2r-2] followed by Y[1], Y[3], ..., Y[2r-1].
function blockMixSalsa8(input, r) {
    const output = new Uint32Array(input.length);
    let x = input.slice(input.length - 16, input.length);
    for (let i = 0; i < 2 * r; i++) {
        const block = input.subarray(i * 16, i * 16 + 16);
        const mixed = new Uint32Array(16);
        for (let j = 0; j < 16; j++) mixed[j] = (x[j] ^ block[j]) >>> 0;
        salsa20_8(mixed);
        x = mixed;
        const destIndex = i % 2 === 0 ? i / 2 : r + (i - 1) / 2;
        output.set(x, destIndex * 16);
    }
    return output;
}

function integerify(block, r) {
    // Interpret the last 64-byte block's first 8 bytes (little-endian) as an integer.
    const base = (2 * r - 1) * 16;
    // Uint32Array words are already little-endian-composed relative to the original byte
    // stream (see bytesToUint32ArrayLE), so word[base] is the low 32 bits.
    return block[base] >>> 0;
}

function romix(block, N, r) {
    const blockLen = 32 * r; // words per block (2r * 16)
    let x = block.slice(0, blockLen);
    const V = new Array(N);
    for (let i = 0; i < N; i++) {
        V[i] = x;
        x = blockMixSalsa8(x, r);
    }
    for (let i = 0; i < N; i++) {
        const j = integerify(x, r) % N;
        const xored = new Uint32Array(blockLen);
        const vj = V[j];
        for (let k = 0; k < blockLen; k++) xored[k] = (x[k] ^ vj[k]) >>> 0;
        x = blockMixSalsa8(xored, r);
    }
    return x;
}

function bytesToUint32ArrayLE(jBytes) {
    const len = jBytes.length;
    const out = new Uint32Array(len / 4);
    for (let i = 0; i < out.length; i++) {
        const o = i * 4;
        out[i] =
            ((jBytes[o] & 0xff) |
                ((jBytes[o + 1] & 0xff) << 8) |
                ((jBytes[o + 2] & 0xff) << 16) |
                ((jBytes[o + 3] & 0xff) << 24)) >>>
            0;
    }
    return out;
}

// Java `byte` is signed (-128..127); values > 127 must be wrapped into that range before
// assignment into a Java byte[] or GraalJS rejects the coercion as lossy (same convention as
// `toJByteArray` above).
function toJByte(unsignedByte) {
    return unsignedByte > 127 ? unsignedByte - 256 : unsignedByte;
}

function uint32ArrayToJBytes(words) {
    const out = new JByteArray(words.length * 4);
    for (let i = 0; i < words.length; i++) {
        const w = words[i];
        const o = i * 4;
        out[o] = toJByte(w & 0xff);
        out[o + 1] = toJByte((w >>> 8) & 0xff);
        out[o + 2] = toJByte((w >>> 16) & 0xff);
        out[o + 3] = toJByte((w >>> 24) & 0xff);
    }
    return out;
}

const SCRYPT_DEFAULTS = { N: 16384, r: 8, p: 1, maxmem: 32 * 1024 * 1024 };

export function scryptSync(password, salt, keylen, options) {
    const opts = Object.assign({}, SCRYPT_DEFAULTS, options || {});
    const N = opts.N || opts.cost || SCRYPT_DEFAULTS.N;
    const r = opts.r || opts.blockSize || SCRYPT_DEFAULTS.r;
    const p = opts.p || opts.parallelization || SCRYPT_DEFAULTS.p;

    const blockBytesLen = 128 * r;
    const bBytes = pbkdf2Sync(password, salt, 1, p * blockBytesLen, "sha256");
    const bJBytes = toJByteArray(bBytes);

    const outJBytes = new JByteArray(p * blockBytesLen);
    for (let i = 0; i < p; i++) {
        const chunk = new JByteArray(blockBytesLen);
        for (let k = 0; k < blockBytesLen; k++) chunk[k] = bJBytes[i * blockBytesLen + k];
        const words = bytesToUint32ArrayLE(chunk);
        const mixed = romix(words, N, r);
        const mixedBytes = uint32ArrayToJBytes(mixed);
        for (let k = 0; k < blockBytesLen; k++) outJBytes[i * blockBytesLen + k] = mixedBytes[k];
    }

    const outBytes = toResultBytes(outJBytes);
    return pbkdf2Sync(password, outBytes, 1, keylen, "sha256");
}

export function scrypt(password, salt, keylen, options, callback) {
    let cb = callback;
    let opts = options;
    if (typeof opts === "function") {
        cb = opts;
        opts = undefined;
    }
    Promise.resolve().then(() => {
        try {
            const result = scryptSync(password, salt, keylen, opts);
            cb(null, result);
        } catch (e) {
            cb(e);
        }
    });
    return undefined;
}

// --- PEM helpers ---

function pemToDer(pem) {
    const b64 = pem
        .replace(/-----BEGIN [^-]+-----/g, "")
        .replace(/-----END [^-]+-----/g, "")
        .replace(/\s+/g, "");
    return JBase64.getDecoder().decode(b64);
}

function derToPem(jBytes, label) {
    const b64 = String(JBase64.getEncoder().encodeToString(jBytes));
    const lines = b64.match(/.{1,64}/g) || [""];
    return `-----BEGIN ${label}-----\n${lines.join("\n")}\n-----END ${label}-----\n`;
}

function materialToDerBytes(input) {
    if (typeof input === "string") {
        if (input.includes("BEGIN")) return pemToDer(input);
        return JBase64.getDecoder().decode(input);
    }
    // Buffer/Uint8Array: sniff for PEM text, else treat as raw DER.
    let text = null;
    try {
        text = String(new JString(toJByteArray(input), JCharset.US_ASCII));
    } catch (e) {
        text = null;
    }
    if (text && text.includes("BEGIN")) return pemToDer(text);
    return toJByteArray(input);
}

// --- KeyObject ---

const PRIVATE_KEY_ALGORITHMS = ["RSA", "EC", "Ed25519", "Ed448"];
const PUBLIC_KEY_ALGORITHMS = ["RSA", "EC", "Ed25519", "Ed448"];

class KeyObject {
    constructor(type, javaKeyOrBytes, details) {
        this.type = type; // 'private' | 'public' | 'secret'
        this._key = javaKeyOrBytes;
        this._details = details || {};
    }

    get asymmetricKeyType() {
        if (this.type === "secret") return undefined;
        return this._details.algorithm;
    }

    get asymmetricKeySize() {
        if (this.type === "secret") return undefined;
        const key = this._key;
        if (JRSAKey.class.isInstance(key)) {
            return Math.ceil(key.getModulus().bitLength() / 8);
        }
        if (JECPublicKey.class.isInstance(key)) {
            return Math.ceil(key.getParams().getCurve().getField().getFieldSize() / 8);
        }
        return undefined;
    }

    get symmetricKeySize() {
        if (this.type !== "secret") return undefined;
        return this._key.length;
    }

    export(options) {
        const opts = options || {};
        if (this.type === "secret") {
            const format = opts.format || "buffer";
            if (format === "buffer") return fromJByteArray(this._key);
            return digestOutput(this._key, format);
        }
        const format = opts.format || "pem";
        const der = this._key.getEncoded();
        if (format === "der") return fromJByteArray(der);
        const label = this.type === "private" ? "PRIVATE KEY" : "PUBLIC KEY";
        return derToPem(der, label);
    }
}

function parsePrivateKeyInput(input) {
    if (input instanceof KeyObject) return input;
    let keySource = input;
    if (typeof input === "object" && input !== null && !(input instanceof Uint8Array) && "key" in input) {
        keySource = input.key;
    }
    if (keySource instanceof KeyObject) return keySource;
    const der = materialToDerBytes(keySource);
    const spec = new JPKCS8EncodedKeySpec(der);
    for (const alg of PRIVATE_KEY_ALGORITHMS) {
        try {
            const kf = JKeyFactory.getInstance(alg);
            const key = kf.generatePrivate(spec);
            return new KeyObject("private", key, { algorithm: alg.toLowerCase() });
        } catch (e) {
            // try next algorithm
        }
    }
    throw new Error("Unable to parse private key: unsupported or malformed key material");
}

function parsePublicKeyInput(input) {
    if (input instanceof KeyObject) return input;
    // Signing/verification callers sometimes pass a private key where a public one is
    // expected only in test/mock code; real Node requires the actual public key, so we
    // don't special-case that here.
    let keySource = input;
    if (typeof input === "object" && input !== null && !(input instanceof Uint8Array) && "key" in input) {
        keySource = input.key;
    }
    if (keySource instanceof KeyObject) return keySource;
    const der = materialToDerBytes(keySource);
    const spec = new JX509EncodedKeySpec(der);
    for (const alg of PUBLIC_KEY_ALGORITHMS) {
        try {
            const kf = JKeyFactory.getInstance(alg);
            const key = kf.generatePublic(spec);
            return new KeyObject("public", key, { algorithm: alg.toLowerCase() });
        } catch (e) {
            // try next algorithm
        }
    }
    throw new Error("Unable to parse public key: unsupported or malformed key material");
}

export function createPrivateKey(key) {
    return parsePrivateKeyInput(key);
}

export function createPublicKey(key) {
    if (key instanceof KeyObject) {
        if (key.type === "public") return key;
        if (key.type === "private") {
            // Node allows deriving a public key from a private one for RSA/EC by
            // re-deriving from the private key's DER isn't generally possible without extra
            // work; not supported for the raw KeyObject case here.
            throw new Error("Deriving a public key from a private KeyObject is not supported");
        }
    }
    return parsePublicKeyInput(key);
}

export function createSecretKey(key) {
    return new KeyObject("secret", toJByteArray(key));
}

// --- Sign / Verify ---

function normalizeDigestName(algorithm) {
    let a = String(algorithm).toUpperCase();
    a = a.replace("WITHRSAENCRYPTION", "");
    a = a.replace(/^RSA-/, "");
    a = a.replace(/^ECDSA-WITH-/, "");
    a = a.replace(/^ECDSA-/, "");
    a = a.replace(/^DSA-/, "");
    a = a.replace(/-/g, "");
    return a;
}

function signatureAlgorithmFor(algorithm, keyObj) {
    const keyType = keyObj.asymmetricKeyType;
    if (keyType === "ed25519") return "Ed25519";
    if (keyType === "ed448") return "Ed448";
    const digest = normalizeDigestName(algorithm);
    if (keyType === "ec") return `${digest}withECDSA`;
    return `${digest}withRSA`;
}

class Sign {
    constructor(algorithm) {
        this._algorithm = algorithm;
        this._chunks = [];
    }

    update(data) {
        this._chunks.push(toJByteArray(data));
        return this;
    }

    sign(privateKey, outputEncoding) {
        const keyObj = parsePrivateKeyInput(privateKey);
        const javaAlg = signatureAlgorithmFor(this._algorithm, keyObj);
        const sig = JSignature.getInstance(javaAlg);
        sig.initSign(keyObj._key);
        for (const c of this._chunks) sig.update(c);
        const out = sig.sign();
        return outputEncoding ? digestOutput(out, outputEncoding) : fromJByteArray(out);
    }
}

class Verify {
    constructor(algorithm) {
        this._algorithm = algorithm;
        this._chunks = [];
    }

    update(data) {
        this._chunks.push(toJByteArray(data));
        return this;
    }

    verify(publicKey, signature, inputEncoding) {
        const keyObj = parsePublicKeyInput(publicKey);
        const javaAlg = signatureAlgorithmFor(this._algorithm, keyObj);
        const sig = JSignature.getInstance(javaAlg);
        sig.initVerify(keyObj._key);
        for (const c of this._chunks) sig.update(c);
        const sigBytes =
            typeof signature === "string" ? decodeStringToJBytes(signature, inputEncoding || "buffer") : toJByteArray(signature);
        return sig.verify(sigBytes);
    }
}

function decodeStringToJBytes(str, encoding) {
    if (encoding === "hex") {
        const bytes = new JByteArray(str.length / 2);
        for (let i = 0; i < bytes.length; i++) {
            bytes[i] = toJByte(parseInt(str.substr(i * 2, 2), 16) & 0xff);
        }
        return bytes;
    }
    if (encoding === "base64" || encoding === "base64url") {
        return JBase64.getDecoder().decode(str.replace(/-/g, "+").replace(/_/g, "/"));
    }
    return toJByteArray(str);
}

export function createSign(algorithm) {
    return new Sign(algorithm);
}

export function createVerify(algorithm) {
    return new Verify(algorithm);
}

export function sign(algorithm, data, key, callback) {
    const doSign = () => {
        const keyObj = parsePrivateKeyInput(key);
        const javaAlg = algorithm ? signatureAlgorithmFor(algorithm, keyObj) : signatureAlgorithmFor("", keyObj);
        const sig = JSignature.getInstance(javaAlg);
        sig.initSign(keyObj._key);
        sig.update(toJByteArray(data));
        return fromJByteArray(sig.sign());
    };
    if (typeof callback === "function") {
        Promise.resolve().then(() => {
            try {
                callback(null, doSign());
            } catch (e) {
                callback(e);
            }
        });
        return undefined;
    }
    return doSign();
}

export function verify(algorithm, data, key, signature, callback) {
    const doVerify = () => {
        const keyObj = parsePublicKeyInput(key);
        const javaAlg = algorithm ? signatureAlgorithmFor(algorithm, keyObj) : signatureAlgorithmFor("", keyObj);
        const sig = JSignature.getInstance(javaAlg);
        sig.initVerify(keyObj._key);
        sig.update(toJByteArray(data));
        return sig.verify(toJByteArray(signature));
    };
    if (typeof callback === "function") {
        Promise.resolve().then(() => {
            try {
                callback(null, doVerify());
            } catch (e) {
                callback(e);
            }
        });
        return undefined;
    }
    return doVerify();
}

// --- Key pair generation ---

// The JDK's built-in SunEC provider only recognizes its own canonical curve names (e.g.
// "secp256r1"), not the OpenSSL-style names Node/most tooling use ("prime256v1", "P-256")
// nor non-NIST curves like secp256k1 (unsupported by SunEC entirely) — translate the common
// aliases so `namedCurve: 'prime256v1'` (what most Node code passes) works as expected.
const EC_CURVE_ALIASES = {
    prime256v1: "secp256r1",
    "p-256": "secp256r1",
    "p-384": "secp384r1",
    "p-521": "secp521r1",
};

function resolveCurveName(curve) {
    return EC_CURVE_ALIASES[String(curve).toLowerCase()] || curve;
}

function keyTypeToJavaAlgorithm(type) {
    switch (String(type).toLowerCase()) {
        case "rsa":
        case "rsa-pss":
            return "RSA";
        case "ec":
            return "EC";
        case "ed25519":
            return "Ed25519";
        case "ed448":
            return "Ed448";
        default:
            throw new Error(`Unsupported key type: ${type}`);
    }
}

function encodeKeyPairResult(privateKeyObj, publicKeyObj, options) {
    const opts = options || {};
    return {
        publicKey: opts.publicKeyEncoding ? publicKeyObj.export(opts.publicKeyEncoding) : publicKeyObj,
        privateKey: opts.privateKeyEncoding ? privateKeyObj.export(opts.privateKeyEncoding) : privateKeyObj,
    };
}

export function generateKeyPairSync(type, options) {
    const opts = options || {};
    const javaAlg = keyTypeToJavaAlgorithm(type);
    const gen = JKeyPairGenerator.getInstance(javaAlg);
    if (javaAlg === "RSA") {
        gen.initialize(opts.modulusLength || 2048);
    } else if (javaAlg === "EC") {
        const curve = resolveCurveName(opts.namedCurve || "secp256r1");
        gen.initialize(new JECGenParameterSpec(curve));
    }
    // Ed25519/Ed448 use the provider's default parameters; no initialize() call needed.
    const pair = gen.generateKeyPair();
    const algLower = String(type).toLowerCase();
    const privObj = new KeyObject("private", pair.getPrivate(), { algorithm: algLower });
    const pubObj = new KeyObject("public", pair.getPublic(), { algorithm: algLower });
    return encodeKeyPairResult(privObj, pubObj, opts);
}

export function generateKeyPair(type, options, callback) {
    let cb = callback;
    let opts = options;
    if (typeof opts === "function") {
        cb = opts;
        opts = undefined;
    }
    Promise.resolve().then(() => {
        try {
            const result = generateKeyPairSync(type, opts);
            cb(null, result.publicKey, result.privateKey);
        } catch (e) {
            cb(e);
        }
    });
    return undefined;
}

// --- Misc ---

export function getCiphers() {
    return Object.keys(CIPHER_TRANSFORMS);
}

export function getCurves() {
    // Limited to curves the JDK's bundled SunEC provider actually supports (see
    // EC_CURVE_ALIASES above) — notably excludes secp256k1, which SunEC rejects outright.
    return ["prime256v1", "secp384r1", "secp521r1"];
}

export const constants = {
    OPENSSL_VERSION_NUMBER: 0x30000000,
    RSA_PKCS1_PADDING: 1,
    RSA_NO_PADDING: 3,
    RSA_PKCS1_OAEP_PADDING: 4,
    RSA_PKCS1_PSS_PADDING: 6,
    RSA_PSS_SALTLEN_DIGEST: -1,
    RSA_PSS_SALTLEN_AUTO: -2,
    RSA_PSS_SALTLEN_MAX_SIGN: -2,
};

export default {
    createHash,
    createHmac,
    randomBytes,
    randomUUID,
    randomInt,
    randomFillSync,
    randomFill,
    timingSafeEqual,
    getHashes,
    getCiphers,
    getCurves,
    createCipheriv,
    createDecipheriv,
    pbkdf2,
    pbkdf2Sync,
    hkdf,
    hkdfSync,
    scrypt,
    scryptSync,
    createSign,
    createVerify,
    sign,
    verify,
    generateKeyPair,
    generateKeyPairSync,
    createPrivateKey,
    createPublicKey,
    createSecretKey,
    constants,
};

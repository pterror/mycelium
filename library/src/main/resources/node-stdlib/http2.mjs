// Minimal Node.js-compatible `http2` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "http2"` / `"node:http2"`. Client requests are
// backed by `java.net.http.HttpClient` with `Version.HTTP_2` (native HTTP/2 support since
// Java 11) — unlike http.mjs/https.mjs (which use the older `HttpURLConnection`), the
// modern `java.net.http` API is what actually negotiates HTTP/2 over TLS (ALPN) or via
// prior-knowledge/upgrade for plaintext, so it's used here instead.
//
// The public shape (`http2.connect` -> `ClientHttp2Session` -> `session.request(headers)`
// -> `ClientHttp2Stream`) mirrors Node's, closely enough for consumers like axios's
// `http2` transport (lib/adapters/http.js's `http2Transport` + `Http2Sessions`), which
// only needs: `http2.connect(authority, options)`, `session.request(headers)` returning a
// stream that emits `'response'` (with a headers object including the `:status`
// pseudo-header) then behaves as a readable stream (`'data'`/`'end'`), plus
// `http2.constants` for the `:method`/`:path`/`:scheme`/`:status` pseudo-header names.
//
// Like http.mjs, there's no real event loop here: a `session.request(...)` call buffers
// any request body written to the returned stream until `.end()`, then performs one
// blocking `HttpClient.send()` call and delivers the whole response as a single
// microtask-scheduled burst of stream events — a "functional but incomplete subset"
// (no server push, no true stream multiplexing/backpressure, no real PING round-trip
// timing) sufficient for typical client usage.
//
// Server-side (`http2.createServer` / `http2.createSecureServer`) is not implemented:
// `com.sun.net.httpserver.HttpServer` (used by http.mjs's server) speaks HTTP/1.1 only,
// and `java.net.http.HttpClient` has no server counterpart at all — there is no
// straightforward Java stdlib API to build an HTTP/2 server on top of here, unlike the
// client side. Both throw a clear, specific error rather than silently behaving like an
// HTTP/1.1 server.

import { EventEmitter } from "events";
import { Buffer } from "buffer";

const JHttpClient = Java.type("java.net.http.HttpClient");
const JHttpRequest = Java.type("java.net.http.HttpRequest");
const JBodyPublishers = Java.type("java.net.http.HttpRequest$BodyPublishers");
const JBodyHandlers = Java.type("java.net.http.HttpResponse$BodyHandlers");
const JHttpVersion = Java.type("java.net.http.HttpClient$Version");
const JURI = Java.type("java.net.URI");
const JDuration = Java.type("java.time.Duration");
const JByteArray = Java.type("byte[]");
const JCharset = Java.type("java.nio.charset.StandardCharsets");

function nextTick(fn) {
    Promise.resolve().then(fn);
}

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

function fromJByteArray(jBytes) {
    const arr = new Uint8Array(jBytes.length);
    for (let i = 0; i < jBytes.length; i++) arr[i] = jBytes[i] & 0xff;
    return arr;
}

// ---- http2.constants ----
//
// Not an exhaustive transcription of Node's internal constants table — covers the
// pseudo-headers, the common request methods/header names, and the full HTTP status code
// range, which is what real-world consumers (axios, etc.) actually destructure.
export const constants = {
    NGHTTP2_SESSION_SERVER: 0,
    NGHTTP2_SESSION_CLIENT: 1,

    NGHTTP2_STREAM_STATE_IDLE: 0,
    NGHTTP2_STREAM_STATE_OPEN: 1,
    NGHTTP2_STREAM_STATE_RESERVED_LOCAL: 2,
    NGHTTP2_STREAM_STATE_RESERVED_REMOTE: 3,
    NGHTTP2_STREAM_STATE_HALF_CLOSED_LOCAL: 4,
    NGHTTP2_STREAM_STATE_HALF_CLOSED_REMOTE: 5,
    NGHTTP2_STREAM_STATE_CLOSED: 7,

    NGHTTP2_NO_ERROR: 0,
    NGHTTP2_PROTOCOL_ERROR: 1,
    NGHTTP2_INTERNAL_ERROR: 2,
    NGHTTP2_FLOW_CONTROL_ERROR: 3,
    NGHTTP2_SETTINGS_TIMEOUT: 4,
    NGHTTP2_STREAM_CLOSED: 5,
    NGHTTP2_FRAME_SIZE_ERROR: 6,
    NGHTTP2_REFUSED_STREAM: 7,
    NGHTTP2_CANCEL: 8,
    NGHTTP2_COMPRESSION_ERROR: 9,
    NGHTTP2_CONNECT_ERROR: 10,
    NGHTTP2_ENHANCE_YOUR_CALM: 11,
    NGHTTP2_INADEQUATE_SECURITY: 12,
    NGHTTP2_HTTP_1_1_REQUIRED: 13,

    NGHTTP2_DEFAULT_WEIGHT: 16,
    NGHTTP2_FLAG_NONE: 0,
    NGHTTP2_FLAG_END_STREAM: 1,
    NGHTTP2_FLAG_END_HEADERS: 4,
    NGHTTP2_FLAG_ACK: 1,
    NGHTTP2_FLAG_PADDED: 8,
    NGHTTP2_FLAG_PRIORITY: 32,

    HTTP2_HEADER_STATUS: ":status",
    HTTP2_HEADER_METHOD: ":method",
    HTTP2_HEADER_AUTHORITY: ":authority",
    HTTP2_HEADER_SCHEME: ":scheme",
    HTTP2_HEADER_PATH: ":path",
    HTTP2_HEADER_PROTOCOL: ":protocol",

    HTTP2_HEADER_ACCEPT_CHARSET: "accept-charset",
    HTTP2_HEADER_ACCEPT_ENCODING: "accept-encoding",
    HTTP2_HEADER_ACCEPT_LANGUAGE: "accept-language",
    HTTP2_HEADER_ACCEPT_RANGES: "accept-ranges",
    HTTP2_HEADER_ACCEPT: "accept",
    HTTP2_HEADER_ACCESS_CONTROL_ALLOW_CREDENTIALS: "access-control-allow-credentials",
    HTTP2_HEADER_ACCESS_CONTROL_ALLOW_HEADERS: "access-control-allow-headers",
    HTTP2_HEADER_ACCESS_CONTROL_ALLOW_METHODS: "access-control-allow-methods",
    HTTP2_HEADER_ACCESS_CONTROL_ALLOW_ORIGIN: "access-control-allow-origin",
    HTTP2_HEADER_ACCESS_CONTROL_EXPOSE_HEADERS: "access-control-expose-headers",
    HTTP2_HEADER_ACCESS_CONTROL_MAX_AGE: "access-control-max-age",
    HTTP2_HEADER_ACCESS_CONTROL_REQUEST_HEADERS: "access-control-request-headers",
    HTTP2_HEADER_ACCESS_CONTROL_REQUEST_METHOD: "access-control-request-method",
    HTTP2_HEADER_AGE: "age",
    HTTP2_HEADER_ALLOW: "allow",
    HTTP2_HEADER_AUTHORIZATION: "authorization",
    HTTP2_HEADER_CACHE_CONTROL: "cache-control",
    HTTP2_HEADER_CONNECTION: "connection",
    HTTP2_HEADER_CONTENT_DISPOSITION: "content-disposition",
    HTTP2_HEADER_CONTENT_ENCODING: "content-encoding",
    HTTP2_HEADER_CONTENT_LANGUAGE: "content-language",
    HTTP2_HEADER_CONTENT_LENGTH: "content-length",
    HTTP2_HEADER_CONTENT_LOCATION: "content-location",
    HTTP2_HEADER_CONTENT_RANGE: "content-range",
    HTTP2_HEADER_CONTENT_TYPE: "content-type",
    HTTP2_HEADER_COOKIE: "cookie",
    HTTP2_HEADER_DATE: "date",
    HTTP2_HEADER_ETAG: "etag",
    HTTP2_HEADER_EXPECT: "expect",
    HTTP2_HEADER_EXPIRES: "expires",
    HTTP2_HEADER_FORWARDED: "forwarded",
    HTTP2_HEADER_FROM: "from",
    HTTP2_HEADER_HOST: "host",
    HTTP2_HEADER_IF_MATCH: "if-match",
    HTTP2_HEADER_IF_MODIFIED_SINCE: "if-modified-since",
    HTTP2_HEADER_IF_NONE_MATCH: "if-none-match",
    HTTP2_HEADER_IF_UNMODIFIED_SINCE: "if-unmodified-since",
    HTTP2_HEADER_LAST_MODIFIED: "last-modified",
    HTTP2_HEADER_LINK: "link",
    HTTP2_HEADER_LOCATION: "location",
    HTTP2_HEADER_MAX_FORWARDS: "max-forwards",
    HTTP2_HEADER_ORIGIN: "origin",
    HTTP2_HEADER_PRAGMA: "pragma",
    HTTP2_HEADER_PROXY_AUTHENTICATE: "proxy-authenticate",
    HTTP2_HEADER_PROXY_AUTHORIZATION: "proxy-authorization",
    HTTP2_HEADER_RANGE: "range",
    HTTP2_HEADER_REFERER: "referer",
    HTTP2_HEADER_RETRY_AFTER: "retry-after",
    HTTP2_HEADER_SERVER: "server",
    HTTP2_HEADER_SET_COOKIE: "set-cookie",
    HTTP2_HEADER_STRICT_TRANSPORT_SECURITY: "strict-transport-security",
    HTTP2_HEADER_TRAILER: "trailer",
    HTTP2_HEADER_TRANSFER_ENCODING: "transfer-encoding",
    HTTP2_HEADER_USER_AGENT: "user-agent",
    HTTP2_HEADER_UPGRADE: "upgrade",
    HTTP2_HEADER_VARY: "vary",
    HTTP2_HEADER_VIA: "via",
    HTTP2_HEADER_WWW_AUTHENTICATE: "www-authenticate",
    HTTP2_HEADER_KEEP_ALIVE: "keep-alive",
    HTTP2_HEADER_PROXY_CONNECTION: "proxy-connection",
    HTTP2_HEADER_X_FRAME_OPTIONS: "x-frame-options",

    HTTP2_METHOD_GET: "GET",
    HTTP2_METHOD_HEAD: "HEAD",
    HTTP2_METHOD_POST: "POST",
    HTTP2_METHOD_PUT: "PUT",
    HTTP2_METHOD_DELETE: "DELETE",
    HTTP2_METHOD_OPTIONS: "OPTIONS",
    HTTP2_METHOD_PATCH: "PATCH",
    HTTP2_METHOD_TRACE: "TRACE",
    HTTP2_METHOD_CONNECT: "CONNECT",

    HTTP_STATUS_CONTINUE: 100,
    HTTP_STATUS_SWITCHING_PROTOCOLS: 101,
    HTTP_STATUS_PROCESSING: 102,
    HTTP_STATUS_EARLY_HINTS: 103,
    HTTP_STATUS_OK: 200,
    HTTP_STATUS_CREATED: 201,
    HTTP_STATUS_ACCEPTED: 202,
    HTTP_STATUS_NON_AUTHORITATIVE_INFORMATION: 203,
    HTTP_STATUS_NO_CONTENT: 204,
    HTTP_STATUS_RESET_CONTENT: 205,
    HTTP_STATUS_PARTIAL_CONTENT: 206,
    HTTP_STATUS_MULTI_STATUS: 207,
    HTTP_STATUS_ALREADY_REPORTED: 208,
    HTTP_STATUS_IM_USED: 226,
    HTTP_STATUS_MULTIPLE_CHOICES: 300,
    HTTP_STATUS_MOVED_PERMANENTLY: 301,
    HTTP_STATUS_FOUND: 302,
    HTTP_STATUS_SEE_OTHER: 303,
    HTTP_STATUS_NOT_MODIFIED: 304,
    HTTP_STATUS_USE_PROXY: 305,
    HTTP_STATUS_TEMPORARY_REDIRECT: 307,
    HTTP_STATUS_PERMANENT_REDIRECT: 308,
    HTTP_STATUS_BAD_REQUEST: 400,
    HTTP_STATUS_UNAUTHORIZED: 401,
    HTTP_STATUS_PAYMENT_REQUIRED: 402,
    HTTP_STATUS_FORBIDDEN: 403,
    HTTP_STATUS_NOT_FOUND: 404,
    HTTP_STATUS_METHOD_NOT_ALLOWED: 405,
    HTTP_STATUS_NOT_ACCEPTABLE: 406,
    HTTP_STATUS_PROXY_AUTHENTICATION_REQUIRED: 407,
    HTTP_STATUS_REQUEST_TIMEOUT: 408,
    HTTP_STATUS_CONFLICT: 409,
    HTTP_STATUS_GONE: 410,
    HTTP_STATUS_LENGTH_REQUIRED: 411,
    HTTP_STATUS_PRECONDITION_FAILED: 412,
    HTTP_STATUS_PAYLOAD_TOO_LARGE: 413,
    HTTP_STATUS_URI_TOO_LONG: 414,
    HTTP_STATUS_UNSUPPORTED_MEDIA_TYPE: 415,
    HTTP_STATUS_RANGE_NOT_SATISFIABLE: 416,
    HTTP_STATUS_EXPECTATION_FAILED: 417,
    HTTP_STATUS_TEAPOT: 418,
    HTTP_STATUS_MISDIRECTED_REQUEST: 421,
    HTTP_STATUS_UNPROCESSABLE_ENTITY: 422,
    HTTP_STATUS_LOCKED: 423,
    HTTP_STATUS_FAILED_DEPENDENCY: 424,
    HTTP_STATUS_TOO_EARLY: 425,
    HTTP_STATUS_UPGRADE_REQUIRED: 426,
    HTTP_STATUS_PRECONDITION_REQUIRED: 428,
    HTTP_STATUS_TOO_MANY_REQUESTS: 429,
    HTTP_STATUS_REQUEST_HEADER_FIELDS_TOO_LARGE: 431,
    HTTP_STATUS_UNAVAILABLE_FOR_LEGAL_REASONS: 451,
    HTTP_STATUS_INTERNAL_SERVER_ERROR: 500,
    HTTP_STATUS_NOT_IMPLEMENTED: 501,
    HTTP_STATUS_BAD_GATEWAY: 502,
    HTTP_STATUS_SERVICE_UNAVAILABLE: 503,
    HTTP_STATUS_GATEWAY_TIMEOUT: 504,
    HTTP_STATUS_HTTP_VERSION_NOT_SUPPORTED: 505,
    HTTP_STATUS_VARIANT_ALSO_NEGOTIATES: 506,
    HTTP_STATUS_INSUFFICIENT_STORAGE: 507,
    HTTP_STATUS_LOOP_DETECTED: 508,
    HTTP_STATUS_BANDWIDTH_LIMIT_EXCEEDED: 509,
    HTTP_STATUS_NOT_EXTENDED: 510,
    HTTP_STATUS_NETWORK_AUTHENTICATION_REQUIRED: 511,
};

function normalizeAuthority(authority) {
    if (typeof authority === "string") return authority;
    if (authority && typeof authority === "object") {
        // A WHATWG URL (or url.parse()-shaped) object.
        if (authority.href) return authority.href;
        const protocol = authority.protocol || "https:";
        const host = authority.host || authority.hostname || "localhost";
        return `${protocol}//${host}`;
    }
    return String(authority);
}

// ---- http2.getDefaultSettings / getPackedSettings ----

export function getDefaultSettings() {
    return {
        headerTableSize: 4096,
        enablePush: true,
        initialWindowSize: 65535,
        maxFrameSize: 16384,
        maxConcurrentStreams: 4294967295,
        maxHeaderListSize: 65535,
        maxHeaderSize: 65535,
        enableConnectProtocol: false,
    };
}

// Maps a settings-object key to its SETTINGS parameter identifier, per RFC 7540 §11.3 —
// `maxHeaderSize` isn't a real wire parameter, so it's aliased onto the same
// SETTINGS_MAX_HEADER_LIST_SIZE identifier as `maxHeaderListSize`, matching Node's own
// getPackedSettings behavior.
const SETTINGS_KEY_TO_ID = {
    headerTableSize: 0x1,
    enablePush: 0x2,
    maxConcurrentStreams: 0x3,
    initialWindowSize: 0x4,
    maxFrameSize: 0x5,
    maxHeaderListSize: 0x6,
    maxHeaderSize: 0x6,
    enableConnectProtocol: 0x8,
};

// Packs a settings object into the binary wire format of an HTTP/2 SETTINGS frame payload
// (a sequence of 6-byte `{ 2-byte identifier, 4-byte value }` entries) — real Node uses
// this to hand a pre-negotiated settings blob to an `Http2-Settings` HTTP/1.1 upgrade
// header. Returns a `Buffer` (from the `buffer` core module), matching Node's return type.
export function getPackedSettings(settings) {
    const s = settings || getDefaultSettings();
    const keys = Object.keys(s).filter(
        (k) => SETTINGS_KEY_TO_ID[k] !== undefined && s[k] !== undefined && s[k] !== null
    );
    const buf = Buffer.alloc(keys.length * 6);
    let offset = 0;
    for (const key of keys) {
        const id = SETTINGS_KEY_TO_ID[key];
        let value = s[key];
        if (typeof value === "boolean") value = value ? 1 : 0;
        buf.writeUInt16BE(id, offset);
        buf.writeUInt32BE(value >>> 0, offset + 2);
        offset += 6;
    }
    return buf;
}

// Unpacks a buffer produced by getPackedSettings back into a settings object — the inverse
// operation, provided for symmetry with Node's API (used far less often than the pack
// direction, e.g. by servers reading an incoming `Http2-Settings` upgrade header).
const ID_TO_SETTINGS_KEY = {
    0x1: "headerTableSize",
    0x2: "enablePush",
    0x3: "maxConcurrentStreams",
    0x4: "initialWindowSize",
    0x5: "maxFrameSize",
    0x6: "maxHeaderListSize",
    0x8: "enableConnectProtocol",
};

export function getUnpackedSettings(buf) {
    const settings = {};
    for (let offset = 0; offset + 6 <= buf.length; offset += 6) {
        const id = buf.readUInt16BE(offset);
        const value = buf.readUInt32BE(offset + 2);
        const key = ID_TO_SETTINGS_KEY[id];
        if (key) settings[key] = key === "enablePush" || key === "enableConnectProtocol" ? !!value : value;
    }
    return settings;
}

// ---- ClientHttp2Stream ----

class ClientHttp2Stream extends EventEmitter {
    constructor(session, headers, options) {
        super();
        this.session = session;
        this._requestHeaders = headers || {};
        this._options = options || {};
        this._chunks = [];
        this._ended = false;
        this.destroyed = false;
        this.closed = false;
        this.aborted = false;
        this.pending = true;
        this.rstCode = constants.NGHTTP2_NO_ERROR;
        this._timeoutMs = null;
        this._timeoutCallback = null;
        session._trackStream(this);
    }

    setEncoding() {
        return this;
    }

    setTimeout(msecs, callback) {
        this._timeoutMs = msecs;
        if (typeof callback === "function") {
            this._timeoutCallback = callback;
            this.once("timeout", callback);
        }
        return this;
    }

    priority() {
        // No wire-level access to stream priority via java.net.http.HttpClient — accepted
        // and ignored, matching how http.mjs/https.mjs already drop options they can't
        // honor (e.g. https.mjs's `rejectUnauthorized: false`) rather than throwing.
        return undefined;
    }

    write(chunk, encoding, callback) {
        if (this._ended) return false;
        this._chunks.push(chunk);
        const cb = typeof encoding === "function" ? encoding : callback;
        if (cb) nextTick(cb);
        return true;
    }

    end(chunk, encoding, callback) {
        if (typeof chunk === "function") {
            callback = chunk;
            chunk = undefined;
        }
        if (chunk !== undefined) this._chunks.push(chunk);
        if (this._ended) return this;
        this._ended = true;
        this.pending = false;
        this._send();
        if (typeof callback === "function") nextTick(callback);
        return this;
    }

    close(code, callback) {
        this.rstCode = code || constants.NGHTTP2_NO_ERROR;
        this._finish(callback);
        return undefined;
    }

    destroy(err) {
        if (this.destroyed) return this;
        this.destroyed = true;
        if (err) this.emit("error", err instanceof Error ? err : new Error(String(err)));
        this._finish();
        return this;
    }

    _finish(callback) {
        if (this.closed) {
            if (typeof callback === "function") nextTick(callback);
            return;
        }
        this.closed = true;
        nextTick(() => {
            this.emit("close");
            if (typeof callback === "function") callback();
            this.session._untrackStream(this);
        });
    }

    _send() {
        nextTick(() => {
            try {
                const headers = this._requestHeaders;
                const method = headers[constants.HTTP2_HEADER_METHOD] || "GET";
                const path = headers[constants.HTTP2_HEADER_PATH] || "/";
                const scheme =
                    headers[constants.HTTP2_HEADER_SCHEME] || (this.session.encrypted ? "https" : "http");
                const authorityHeader = headers[constants.HTTP2_HEADER_AUTHORITY] || this.session._authorityHost;
                const url = `${scheme}://${authorityHeader}${path}`;

                let builder = JHttpRequest.newBuilder(JURI.create(url));
                for (const key of Object.keys(headers)) {
                    if (key.charAt(0) === ":") continue;
                    const value = headers[key];
                    if (value === undefined || value === null) continue;
                    if (Array.isArray(value)) {
                        for (const v of value) builder = builder.header(String(key), String(v));
                    } else {
                        builder = builder.header(String(key), String(value));
                    }
                }
                if (typeof this._timeoutMs === "number" && this._timeoutMs > 0) {
                    builder = builder.timeout(JDuration.ofMillis(this._timeoutMs));
                }

                const hasBody = this._chunks.length > 0 && !["GET", "HEAD"].includes(String(method).toUpperCase());
                if (hasBody) {
                    let totalLength = 0;
                    const parts = this._chunks.map((c) => toJByteArray(c));
                    for (const p of parts) totalLength += p.length;
                    const body = new JByteArray(totalLength);
                    let off = 0;
                    for (const p of parts) {
                        for (let i = 0; i < p.length; i++) body[off + i] = p[i];
                        off += p.length;
                    }
                    builder = builder.method(String(method), JBodyPublishers.ofByteArray(body));
                } else {
                    builder = builder.method(String(method), JBodyPublishers.noBody());
                }

                const request = builder.build();
                const response = this.session._client.send(request, JBodyHandlers.ofByteArray());

                const responseHeaders = { [constants.HTTP2_HEADER_STATUS]: response.statusCode() };
                const jHeaderMap = response.headers().map();
                const it = jHeaderMap.entrySet().iterator();
                while (it.hasNext()) {
                    const entry = it.next();
                    const name = String(entry.getKey()).toLowerCase();
                    const values = entry.getValue();
                    const joined = [];
                    const vit = values.iterator();
                    while (vit.hasNext()) joined.push(String(vit.next()));
                    responseHeaders[name] = joined.length === 1 ? joined[0] : joined.join(", ");
                }

                this.emit("response", responseHeaders, 0);

                const bodyBytes = response.body();
                nextTick(() => {
                    if (bodyBytes.length > 0) this.emit("data", fromJByteArray(bodyBytes));
                    this.emit("end");
                    this._finish();
                });
            } catch (e) {
                const err = e instanceof Error ? e : new Error(String(e && e.message ? e.message : e));
                this.emit("error", err);
                this._finish();
            }
        });
    }
}

// ---- ClientHttp2Session ----

class ClientHttp2Session extends EventEmitter {
    constructor(authority, options) {
        super();
        options = options || {};
        const authorityStr = normalizeAuthority(authority);
        const uri = JURI.create(
            authorityStr.indexOf("://") === -1 ? `https://${authorityStr}` : authorityStr
        );
        this._authorityHost = uri.getPort() === -1 ? uri.getHost() : `${uri.getHost()}:${uri.getPort()}`;
        this.encrypted = uri.getScheme() === "https";
        this.destroyed = false;
        this.closed = false;
        this.connecting = true;
        this.alpnProtocol = undefined;
        this.pendingSettingsAck = false;
        this._pendingStreams = new Set();

        let clientBuilder = JHttpClient.newBuilder().version(JHttpVersion.HTTP_2);
        if (typeof options.connectTimeout === "number") {
            clientBuilder = clientBuilder.connectTimeout(JDuration.ofMillis(options.connectTimeout));
        }
        this._client = clientBuilder.build();

        nextTick(() => {
            if (this.destroyed) return;
            this.connecting = false;
            this.alpnProtocol = this.encrypted ? "h2" : undefined;
            this.emit("connect", this, {});
        });
    }

    request(headers, options) {
        if (this.destroyed || this.closed) {
            throw new Error("mycelium: cannot call request() on a closed/destroyed Http2Session");
        }
        return new ClientHttp2Stream(this, headers || {}, options || {});
    }

    // Real Node PING round-trips an 8-byte opaque payload through the HTTP/2 connection's
    // PING frame and reports the actual wire latency — `java.net.http.HttpClient` exposes
    // no frame-level access to do that, so this reports a nominal ~0ms duration instead of
    // throwing "not supported"; the payload is still echoed back like the real API.
    ping(payload, callback) {
        let cb = callback;
        let data = payload;
        if (typeof payload === "function") {
            cb = payload;
            data = Buffer.alloc(8);
        }
        if (typeof cb !== "function") return false;
        const started = Date.now();
        nextTick(() => {
            const duration = Date.now() - started;
            cb(null, duration, data || Buffer.alloc(8));
        });
        return true;
    }

    settings(settings, callback) {
        this.localSettings = Object.assign({}, this.localSettings || getDefaultSettings(), settings || {});
        if (typeof callback === "function") nextTick(() => callback(null, this.localSettings));
        return undefined;
    }

    setTimeout(msecs, callback) {
        if (typeof callback === "function") this.once("timeout", callback);
        return this;
    }

    _trackStream(stream) {
        this._pendingStreams.add(stream);
    }

    _untrackStream(stream) {
        this._pendingStreams.delete(stream);
        if (this.closed && this._pendingStreams.size === 0) {
            this._emitClosedOnce();
        }
    }

    _emitClosedOnce() {
        if (this._closedEmitted) return;
        this._closedEmitted = true;
        this.emit("close");
    }

    close(callback) {
        if (typeof callback === "function") this.once("close", callback);
        if (this.closed) return;
        this.closed = true;
        if (this._pendingStreams.size === 0) {
            nextTick(() => this._emitClosedOnce());
        }
    }

    destroy(err) {
        if (this.destroyed) return;
        this.destroyed = true;
        this.closed = true;
        if (err) this.emit("error", err instanceof Error ? err : new Error(String(err)));
        nextTick(() => this._emitClosedOnce());
    }
}

// ---- module-level API ----

export function connect(authority, options, listener) {
    if (typeof options === "function") {
        listener = options;
        options = {};
    }
    const session = new ClientHttp2Session(authority, options || {});
    if (typeof listener === "function") session.once("connect", listener);
    return session;
}

export function createServer() {
    throw new Error(
        "mycelium: http2.createServer is not implemented — there is no straightforward " +
            "Java stdlib API for an HTTP/2 server (com.sun.net.httpserver.HttpServer, used by " +
            "http.mjs's createServer, only speaks HTTP/1.1; java.net.http.HttpClient has no " +
            "server-side counterpart)"
    );
}

export function createSecureServer() {
    throw new Error(
        "mycelium: http2.createSecureServer is not implemented — there is no straightforward " +
            "Java stdlib API for an HTTP/2 server; see http2.createServer's error for details"
    );
}

export { ClientHttp2Session, ClientHttp2Stream };

export default {
    connect,
    constants,
    createServer,
    createSecureServer,
    getDefaultSettings,
    getPackedSettings,
    getUnpackedSettings,
    ClientHttp2Session,
    ClientHttp2Stream,
};

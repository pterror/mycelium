// Minimal Node.js-compatible `https` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "https"` / `"node:https"`. Mirrors http.mjs's
// client implementation closely (same EventEmitter/IncomingMessage/ClientRequest shape),
// but opens connections via `javax.net.ssl.HttpsURLConnection` explicitly and defaults the
// scheme/port to `https:`/443. Duplicated rather than imported from the `http` core module
// — see stream.mjs's comment on why core modules don't import each other here: each is
// served as a standalone synthetic ESM resource, and cross-core-module `import` isn't a
// pattern exercised/relied on elsewhere in this codebase.
//
// `createServer` is a stub: a real HTTPS server needs a configured `SSLContext`
// (certificate + private key) that Node's `https.createServer({ key, cert }, ...)` takes
// as JS-supplied PEM strings — wiring that up to `com.sun.net.httpserver.HttpsServer` /
// `javax.net.ssl.SSLContext` is out of scope here, so it throws rather than silently
// behaving like a plain HTTP server.

const JURL = Java.type("java.net.URL");
const JByteArrayOutputStream = Java.type("java.io.ByteArrayOutputStream");
const JByteArray = Java.type("byte[]");
const JCharset = Java.type("java.nio.charset.StandardCharsets");

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

function readAllBytes(inputStream) {
    if (inputStream === null) return new JByteArray(0);
    const baos = new JByteArrayOutputStream();
    const chunk = new JByteArray(8192);
    let n;
    while ((n = inputStream.read(chunk, 0, chunk.length)) !== -1) {
        baos.write(chunk, 0, n);
    }
    inputStream.close();
    return baos.toByteArray();
}

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
        return this;
    }

    off(event, listener) {
        return this.removeListener(event, listener);
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
        for (const listener of handlers.slice()) listener.apply(this, args);
        return true;
    }

    listenerCount(event) {
        const handlers = this._events[event];
        return handlers === undefined ? 0 : handlers.length;
    }
}

function nextTick(fn) {
    Promise.resolve().then(fn);
}

const STATUS_CODES = {
    200: "OK", 201: "Created", 204: "No Content", 301: "Moved Permanently",
    302: "Found", 304: "Not Modified", 400: "Bad Request", 401: "Unauthorized",
    403: "Forbidden", 404: "Not Found", 405: "Method Not Allowed",
    500: "Internal Server Error", 502: "Bad Gateway", 503: "Service Unavailable",
};

function headersToLowerCaseObject(jHeaderMap) {
    const headers = {};
    const it = jHeaderMap.entrySet().iterator();
    while (it.hasNext()) {
        const entry = it.next();
        const key = entry.getKey();
        if (key === null) continue;
        const values = entry.getValue();
        const lowerKey = String(key).toLowerCase();
        const joined = [];
        const vit = values.iterator();
        while (vit.hasNext()) joined.push(String(vit.next()));
        headers[lowerKey] = joined.length === 1 ? joined[0] : joined.join(", ");
    }
    return headers;
}

class IncomingMessage extends EventEmitter {
    constructor(statusCode, statusMessage, headers, bodyBytes) {
        super();
        this.statusCode = statusCode;
        this.statusMessage = statusMessage;
        this.headers = headers;
        this.httpVersion = "1.1";
        this._bodyBytes = bodyBytes;
        this.readable = true;
        this.complete = false;
    }

    setEncoding() {
        return this;
    }

    _deliver() {
        nextTick(() => {
            if (this._bodyBytes.length > 0) {
                this.emit("data", fromJByteArray(this._bodyBytes));
            }
            this.complete = true;
            this.emit("end");
        });
    }

    resume() {
        return this;
    }

    pause() {
        return this;
    }
}

class ClientRequest extends EventEmitter {
    constructor(options) {
        super();
        this._options = options;
        this._chunks = [];
        this._ended = false;
        this._headers = Object.assign({}, options.headers || {});
    }

    setHeader(name, value) {
        this._headers[name] = value;
        return this;
    }

    getHeader(name) {
        return this._headers[name];
    }

    removeHeader(name) {
        delete this._headers[name];
    }

    write(chunk, encoding, callback) {
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
        this._send();
        if (typeof callback === "function") nextTick(callback);
        return this;
    }

    abort() {
        this.emit("abort");
    }

    destroy(err) {
        if (err) this.emit("error", err);
    }

    _send() {
        nextTick(() => {
            try {
                const opts = this._options;
                const hostname = opts.hostname || opts.host || "localhost";
                const port = opts.port || 443;
                const path = opts.path || "/";
                const urlString = `https://${hostname}:${port}${path}`;
                const url = new JURL(urlString);
                // `url.openConnection()` already returns a `javax.net.ssl.HttpsURLConnection`
                // for an `https:` URL — GraalJS's host interop is dynamically typed, so no
                // explicit cast is needed to call HttpsURLConnection-specific methods on it.
                const conn = url.openConnection();
                conn.setRequestMethod(opts.method || "GET");
                conn.setConnectTimeout(opts.timeout || 30000);
                conn.setReadTimeout(opts.timeout || 30000);
                conn.setInstanceFollowRedirects(true);
                // Note: `rejectUnauthorized: false` (Node's "skip cert validation" escape
                // hatch for self-signed/local dev certs) isn't implemented — connections
                // always validate against the JVM's default trust store.

                for (const key of Object.keys(this._headers)) {
                    const value = this._headers[key];
                    if (value === undefined || value === null) continue;
                    conn.setRequestProperty(String(key), String(value));
                }

                const hasBody = this._chunks.length > 0 &&
                    !["GET", "HEAD"].includes((opts.method || "GET").toUpperCase());
                if (hasBody) {
                    conn.setDoOutput(true);
                    const outStream = conn.getOutputStream();
                    for (const chunk of this._chunks) {
                        const bytes = toJByteArray(chunk);
                        outStream.write(bytes, 0, bytes.length);
                    }
                    outStream.close();
                }

                const statusCode = conn.getResponseCode();
                const statusMessage = String(conn.getResponseMessage() || STATUS_CODES[statusCode] || "");
                const headers = headersToLowerCaseObject(conn.getHeaderFields());
                const bodyStream = statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
                const bodyBytes = readAllBytes(bodyStream);

                const res = new IncomingMessage(statusCode, statusMessage, headers, bodyBytes);
                this.emit("response", res);
                res._deliver();
            } catch (e) {
                this.emit("error", e instanceof Error ? e : new Error(String(e)));
            }
        });
    }
}

function parseRequestArgs(urlOrOptions, optionsOrCallback, maybeCallback) {
    let options;
    let callback;
    if (typeof urlOrOptions === "string") {
        const u = new JURL(urlOrOptions);
        options = {
            hostname: String(u.getHost()),
            port: u.getPort() === -1 ? 443 : u.getPort(),
            path: (String(u.getFile()) || "/") || "/",
            method: "GET",
            headers: {},
        };
        if (typeof optionsOrCallback === "object" && optionsOrCallback !== null) {
            options = Object.assign(options, optionsOrCallback);
            callback = maybeCallback;
        } else {
            callback = optionsOrCallback;
        }
    } else {
        options = Object.assign({ method: "GET", path: "/", headers: {} }, urlOrOptions || {});
        callback = optionsOrCallback;
    }
    return { options, callback };
}

export function request(urlOrOptions, optionsOrCallback, maybeCallback) {
    const { options, callback } = parseRequestArgs(urlOrOptions, optionsOrCallback, maybeCallback);
    const req = new ClientRequest(options);
    if (typeof callback === "function") req.on("response", callback);
    return req;
}

export function get(urlOrOptions, optionsOrCallback, maybeCallback) {
    const req = request(urlOrOptions, optionsOrCallback, maybeCallback);
    req.end();
    return req;
}

export function createServer() {
    throw new Error(
        "mycelium: https.createServer is not implemented — HTTPS server support requires " +
            "SSLContext/certificate setup that isn't wired up in this build"
    );
}

// ---- Agent ----
//
// See http.mjs's Agent for the full rationale: our client requests are each backed by
// their own `HttpsURLConnection`, which pools/reuses connections internally at the JVM
// level, so this class doesn't need to do any real pooling — it exists so packages that
// construct `new https.Agent({ ... })` (very common for e.g. passing `rejectUnauthorized:
// false`) don't crash.
class Agent {
    constructor(options = {}) {
        this.keepAlive = options.keepAlive || false;
        this.keepAliveMsecs = options.keepAliveMsecs !== undefined ? options.keepAliveMsecs : 1000;
        this.maxSockets = options.maxSockets !== undefined ? options.maxSockets : Infinity;
        this.maxFreeSockets = options.maxFreeSockets !== undefined ? options.maxFreeSockets : 256;
        this.maxTotalSockets = options.maxTotalSockets !== undefined ? options.maxTotalSockets : Infinity;
        this.timeout = options.timeout;
        this.scheduling = options.scheduling || "lifo";
        this.options = options;
        this.protocol = "https:";
        this.sockets = {};
        this.freeSockets = {};
        this.requests = {};
    }

    createConnection(options, callback) {
        const err = new Error("mycelium: Agent.createConnection is not implemented (connections are managed internally)");
        if (typeof callback === "function") {
            nextTick(() => callback(err));
            return undefined;
        }
        throw err;
    }

    getName(options = {}) {
        const host = options.host || options.hostname || "localhost";
        const port = options.port || 443;
        const localAddress = options.localAddress ? `:${options.localAddress}` : "";
        return `${host}:${port}${localAddress}`;
    }

    destroy() {
        this.sockets = {};
        this.freeSockets = {};
        this.requests = {};
    }
}

const globalAgent = new Agent({ keepAlive: false });

export { STATUS_CODES, IncomingMessage, ClientRequest, Agent, globalAgent };

export default {
    request, get, createServer, STATUS_CODES, IncomingMessage, ClientRequest, Agent, globalAgent,
};

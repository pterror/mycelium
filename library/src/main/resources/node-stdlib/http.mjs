// Minimal Node.js-compatible `http` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "http"` / `"node:http"`. Client requests are
// backed by `java.net.HttpURLConnection` and run synchronously under the hood (there's no
// real event loop / worker-thread pool here — see Mycelium.kt), with results delivered to
// listeners via a microtask so the async-looking `on('response', ...)` API still works.
// The server is backed by `com.sun.net.httpserver.HttpServer`, whose own internal accept
// thread invokes the JS request handler — GraalJS transfers context ownership to whichever
// thread calls into a guest function, so this works as long as no other thread is
// concurrently inside the context at the same moment (true here: `server.listen()`
// returns immediately, and nothing else touches the context while a request is being
// handled).
//
// This is a functional-but-incomplete subset: no keep-alive/agent pooling, no chunked
// request bodies, no HTTP/2 — enough for typical npm packages (e.g. simple REST clients)
// to make/receive basic requests.

const JURL = Java.type("java.net.URL");
const JHttpURLConnection = Java.type("java.net.HttpURLConnection");
const JByteArrayOutputStream = Java.type("java.io.ByteArrayOutputStream");
const JByteArray = Java.type("byte[]");
const JCharset = Java.type("java.nio.charset.StandardCharsets");
const JHttpServer = Java.type("com.sun.net.httpserver.HttpServer");
const JInetSocketAddress = Java.type("java.net.InetSocketAddress");

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

function utf8Decode(u8) {
    const bytes = toJByteArray(u8);
    return String(new (Java.type("java.lang.String"))(bytes, JCharset.UTF_8));
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

// Minimal EventEmitter, self-contained (see stream.mjs's comment on why: core modules are
// standalone synthetic resources, so importing the `events` core module from here isn't
// reliable).
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

// Mirrors Node's `http.METHODS` — the sorted list of HTTP methods the parser supports —
// needed by packages that build their own method-name tables off it rather than
// hardcoding one (e.g. express's lib/utils.js: `METHODS.map((m) => m.toLowerCase())`).
const METHODS = [
    "ACL", "BIND", "CHECKOUT", "CONNECT", "COPY", "DELETE", "GET", "HEAD", "LINK", "LOCK",
    "M-SEARCH", "MERGE", "MKACTIVITY", "MKCALENDAR", "MKCOL", "MOVE", "NOTIFY", "OPTIONS",
    "PATCH", "POST", "PROPFIND", "PROPPATCH", "PURGE", "PUT", "REBIND", "REPORT", "SEARCH",
    "SOURCE", "SUBSCRIBE", "TRACE", "UNBIND", "UNLINK", "UNLOCK", "UNSUBSCRIBE",
];

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
        if (key === null) continue; // HttpURLConnection uses a null key for the status line
        const values = entry.getValue();
        const lowerKey = String(key).toLowerCase();
        const joined = [];
        const vit = values.iterator();
        while (vit.hasNext()) joined.push(String(vit.next()));
        headers[lowerKey] = joined.length === 1 ? joined[0] : joined.join(", ");
    }
    return headers;
}

// ---- Client: IncomingMessage (response) ----

class IncomingMessage extends EventEmitter {
    constructor(statusCode, statusMessage, headers, bodyBytes) {
        super();
        this.statusCode = statusCode;
        this.statusMessage = statusMessage;
        this.headers = headers;
        this.httpVersion = "1.1";
        this.method = undefined;
        this.url = undefined;
        this._bodyBytes = bodyBytes;
        this.readable = true;
        this.complete = false;
    }

    setEncoding() {
        return this;
    }

    // Delivers the whole (already-fully-read) body as a single 'data' event followed by
    // 'end' — a reasonable approximation of Node's real chunked delivery given the
    // underlying HttpURLConnection call already blocked until the whole response arrived.
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

// ---- Client: ClientRequest ----

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
                const protocol = opts.protocol || "http:";
                const hostname = opts.hostname || opts.host || "localhost";
                const port = opts.port || (protocol === "https:" ? 443 : 80);
                const path = opts.path || "/";
                const urlString = `${protocol}//${hostname}:${port}${path}`;
                const url = new JURL(urlString);
                const conn = url.openConnection();
                conn.setRequestMethod(opts.method || "GET");
                conn.setConnectTimeout(opts.timeout || 30000);
                conn.setReadTimeout(opts.timeout || 30000);
                conn.setInstanceFollowRedirects(true);

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
            protocol: `${u.getProtocol()}:`,
            hostname: String(u.getHost()),
            port: u.getPort() === -1 ? undefined : u.getPort(),
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

// ---- Server ----

class ServerIncomingMessage extends EventEmitter {
    constructor(exchange) {
        super();
        this.method = String(exchange.getRequestMethod());
        this.url = String(exchange.getRequestURI().toString());
        this.httpVersion = "1.1";
        this.headers = headersToLowerCaseObject(exchange.getRequestHeaders());
        this._bodyBytes = readAllBytes(exchange.getRequestBody());
        this.readable = true;
        this.complete = false;
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

    setEncoding() {
        return this;
    }
}

class ServerResponse extends EventEmitter {
    constructor(exchange) {
        super();
        this._exchange = exchange;
        this.statusCode = 200;
        this._headers = {};
        this._headersSent = false;
        this._chunks = [];
        this.finished = false;
        this.writableEnded = false;
    }

    setHeader(name, value) {
        this._headers[String(name).toLowerCase()] = value;
        return this;
    }

    getHeader(name) {
        return this._headers[String(name).toLowerCase()];
    }

    removeHeader(name) {
        delete this._headers[String(name).toLowerCase()];
    }

    hasHeader(name) {
        return String(name).toLowerCase() in this._headers;
    }

    writeHead(statusCode, statusMessageOrHeaders, maybeHeaders) {
        this.statusCode = statusCode;
        let headers = maybeHeaders;
        if (typeof statusMessageOrHeaders === "object" && statusMessageOrHeaders !== null) {
            headers = statusMessageOrHeaders;
        }
        if (headers) {
            for (const key of Object.keys(headers)) this.setHeader(key, headers[key]);
        }
        return this;
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
        if (this.finished) return this;

        const bodyParts = this._chunks.map((c) => toJByteArray(c));
        let totalLength = 0;
        for (const part of bodyParts) totalLength += part.length;
        const body = new JByteArray(totalLength);
        let offset = 0;
        for (const part of bodyParts) {
            for (let i = 0; i < part.length; i++) body[offset + i] = part[i];
            offset += part.length;
        }

        const respHeaders = this._exchange.getResponseHeaders();
        for (const key of Object.keys(this._headers)) {
            respHeaders.set(String(key), String(this._headers[key]));
        }
        this._exchange.sendResponseHeaders(this.statusCode, totalLength);
        if (totalLength > 0) {
            const out = this._exchange.getResponseBody();
            out.write(body, 0, body.length);
            out.close();
        } else {
            this._exchange.getResponseBody().close();
        }
        this._exchange.close();
        this.finished = true;
        this.writableEnded = true;
        this.emit("finish");
        if (typeof callback === "function") nextTick(callback);
        return this;
    }
}

class Server extends EventEmitter {
    constructor(requestListener) {
        super();
        if (typeof requestListener === "function") this.on("request", requestListener);
        this._httpServer = null;
    }

    listen(port, hostnameOrCallback, callback) {
        let host = "0.0.0.0";
        let cb = callback;
        if (typeof hostnameOrCallback === "function") {
            cb = hostnameOrCallback;
        } else if (typeof hostnameOrCallback === "string") {
            host = hostnameOrCallback;
        }
        const actualPort = port || 0;
        this._httpServer = JHttpServer.create(new JInetSocketAddress(host, actualPort), 0);
        const self = this;
        this._httpServer.createContext("/", function (exchange) {
            try {
                const req = new ServerIncomingMessage(exchange);
                const res = new ServerResponse(exchange);
                self.emit("request", req, res);
                req._deliver();
            } catch (e) {
                // Surfaced to stderr rather than swallowed silently — a bare 500 with no
                // trace of what threw is nearly impossible to debug from the client side
                // (this mirrors Node itself, which prints an uncaught exception from a
                // request handler to stderr when nothing else handles it).
                console.error("mycelium: http server request handler threw:", e && e.stack ? e.stack : e);
                try {
                    exchange.sendResponseHeaders(500, 0);
                    exchange.close();
                } catch (e2) {
                    // best-effort — nothing more we can do if even the error path fails
                }
            }
        });
        this._httpServer.setExecutor(null);
        this._httpServer.start();
        this.listening = true;
        if (typeof cb === "function") nextTick(cb);
        return this;
    }

    address() {
        if (!this._httpServer) return null;
        const addr = this._httpServer.getAddress();
        return { address: String(addr.getAddress().getHostAddress()), family: "IPv4", port: addr.getPort() };
    }

    close(callback) {
        if (this._httpServer) {
            this._httpServer.stop(0);
            this.listening = false;
        }
        if (typeof callback === "function") nextTick(callback);
        return this;
    }
}

export function createServer(requestListener) {
    return new Server(requestListener);
}

export { STATUS_CODES, METHODS, IncomingMessage, ClientRequest, ServerResponse, Server };

export default {
    request, get, createServer, STATUS_CODES, METHODS, IncomingMessage, ClientRequest,
    ServerResponse, Server,
};

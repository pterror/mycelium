// Minimal Node.js-compatible `net` module, served by Mycelium as the synthetic core module
// resolved for `import ... from "net"` / `"node:net"`. Client/server TCP sockets are backed
// by plain `java.net.Socket`/`java.net.ServerSocket` — there's no real event loop here (see
// http.mjs's comment on the same point), so each socket's inbound-data delivery is driven by
// a dedicated daemon `java.lang.Thread` that blocks on `InputStream.read()` and calls
// straight back into the shared GraalJS context to `emit('data', ...)`. That's safe under
// the same rule http.mjs's server side already relies on: GraalJS transfers context
// ownership to whichever thread calls into a guest function, so this works as long as no
// other thread is concurrently inside the context at the same moment — true here, since
// nothing else touches the context while a socket's reader thread is blocked in `read()`.
//
// UNIX domain sockets (`net.connect({ path: ... })`, `net.createServer().listen(path)`) are
// not implemented — `java.net.Socket`/`ServerSocket` are TCP-only; `java.nio.channels`'s
// `UnixDomainSocketAddress` (Java 16+) would be the real path to that, but isn't wired up
// here. Both throw a clear, specific error instead of silently behaving like a TCP socket.

import { EventEmitter } from "events";

const JSocket = Java.type("java.net.Socket");
const JServerSocket = Java.type("java.net.ServerSocket");
const JInetSocketAddress = Java.type("java.net.InetSocketAddress");
const JByteArray = Java.type("byte[]");
const JCharset = Java.type("java.nio.charset.StandardCharsets");
const JThread = Java.type("java.lang.Thread");

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

// ---- net.isIP / isIPv4 / isIPv6 ----

export function isIPv4(input) {
    if (typeof input !== "string") return false;
    const parts = input.split(".");
    if (parts.length !== 4) return false;
    return parts.every((part) => /^\d{1,3}$/.test(part) && Number(part) >= 0 && Number(part) <= 255);
}

// A reasonably complete (not RFC-exhaustive) IPv6 literal matcher: full 8-group form,
// `::`-compressed form (at most one run), and a trailing embedded IPv4 (e.g.
// `::ffff:192.168.1.1`) — covers what `https-proxy-agent`/`socks-proxy-agent`-style
// consumers of `net.isIP` actually check in practice.
const IPV6_FULL = /^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$/;
const IPV6_COMPRESSED = /^([0-9a-fA-F]{1,4}(:[0-9a-fA-F]{1,4})*)?::([0-9a-fA-F]{1,4}(:[0-9a-fA-F]{1,4})*)?$/;

export function isIPv6(input) {
    if (typeof input !== "string" || input.length === 0) return false;
    let candidate = input;
    const lastColon = input.lastIndexOf(":");
    const trailingV4 = input.slice(lastColon + 1);
    if (trailingV4.indexOf(".") !== -1 && isIPv4(trailingV4)) {
        candidate = input.slice(0, lastColon) + ":0:0";
    }
    if (IPV6_FULL.test(candidate)) return true;
    if (candidate.indexOf("::") !== -1 && (candidate.match(/::/g) || []).length === 1) {
        return IPV6_COMPRESSED.test(candidate);
    }
    return false;
}

export function isIP(input) {
    if (isIPv4(input)) return 4;
    if (isIPv6(input)) return 6;
    return 0;
}

// ---- net.Socket ----

class Socket extends EventEmitter {
    constructor(options) {
        super();
        options = options || {};
        this._jSocket = null;
        this.destroyed = false;
        this.connecting = false;
        this.pending = true;
        this.writable = options.writable !== false;
        this.readable = options.readable !== false;
        this.remoteAddress = undefined;
        this.remotePort = undefined;
        this.remoteFamily = undefined;
        this.localAddress = undefined;
        this.localPort = undefined;
        this.bytesRead = 0;
        this.bytesWritten = 0;
        this._encoding = null;
    }

    setEncoding(encoding) {
        this._encoding = encoding || null;
        return this;
    }

    setNoDelay(enable) {
        if (this._jSocket) {
            try {
                this._jSocket.setTcpNoDelay(enable !== false);
            } catch (e) {
                // best-effort — not every platform/socket state allows changing this
            }
        }
        return this;
    }

    setKeepAlive(enable, _initialDelay) {
        if (this._jSocket) {
            try {
                this._jSocket.setKeepAlive(!!enable);
            } catch (e) {
                // best-effort
            }
        }
        return this;
    }

    setTimeout(msecs, callback) {
        if (this._jSocket) {
            try {
                this._jSocket.setSoTimeout(msecs || 0);
            } catch (e) {
                // best-effort
            }
        }
        this._timeoutMs = msecs;
        if (typeof callback === "function") this.once("timeout", callback);
        return this;
    }

    // Accepts Node's three call shapes: connect(path[, cb]) (UNIX socket — not supported),
    // connect(port[, host][, cb]), connect(options[, cb]).
    connect(...args) {
        let port;
        let host = "localhost";
        let callback;
        const first = args[0];
        if (first && typeof first === "object") {
            if (first.path) {
                throw new Error(
                    "mycelium: net.Socket#connect to a UNIX domain socket path is not implemented — " +
                        "only TCP host:port connections are supported"
                );
            }
            port = first.port;
            host = first.host || host;
            callback = typeof args[1] === "function" ? args[1] : undefined;
        } else if (typeof first === "string" && !/^\d+$/.test(first) && args.length === 1) {
            throw new Error(
                "mycelium: net.Socket#connect to a UNIX domain socket path is not implemented — " +
                    "only TCP host:port connections are supported"
            );
        } else {
            port = Number(first);
            if (typeof args[1] === "string") {
                host = args[1];
                callback = typeof args[2] === "function" ? args[2] : undefined;
            } else {
                callback = typeof args[1] === "function" ? args[1] : undefined;
            }
        }

        if (typeof callback === "function") this.once("connect", callback);
        this.connecting = true;
        this.pending = true;
        nextTick(() => {
            try {
                const jSocket = new JSocket();
                jSocket.connect(new JInetSocketAddress(host, port), this._connectTimeoutMs || 0);
                this._jSocket = jSocket;
                this._afterConnect();
            } catch (e) {
                this.connecting = false;
                this.emit("error", e instanceof Error ? e : new Error(String(e && e.message ? e.message : e)));
            }
        });
        return this;
    }

    _afterConnect() {
        const jSocket = this._jSocket;
        this.connecting = false;
        this.pending = false;
        try {
            this.remoteAddress = String(jSocket.getInetAddress().getHostAddress());
            this.remoteFamily = this.remoteAddress.indexOf(":") !== -1 ? "IPv6" : "IPv4";
            this.remotePort = jSocket.getPort();
            this.localAddress = String(jSocket.getLocalAddress().getHostAddress());
            this.localPort = jSocket.getLocalPort();
        } catch (e) {
            // best-effort — leave address fields undefined if the JVM can't report them
        }
        this.emit("connect");
        this.emit("ready");
        this._startReader();
    }

    _startReader() {
        const self = this;
        const thread = new JThread(function () {
            try {
                const inStream = self._jSocket.getInputStream();
                const buf = new JByteArray(8192);
                let n;
                while ((n = inStream.read(buf)) !== -1) {
                    const chunk = new Uint8Array(n);
                    for (let i = 0; i < n; i++) chunk[i] = buf[i] & 0xff;
                    self.bytesRead += n;
                    self.emit("data", self._encoding ? bytesToString(chunk, self._encoding) : chunk);
                }
                self.emit("end");
            } catch (e) {
                if (!self.destroyed) {
                    self.emit("error", e instanceof Error ? e : new Error(String(e && e.message ? e.message : e)));
                }
            } finally {
                self._finishClose();
            }
        });
        thread.setDaemon(true);
        thread.setName("mycelium-net-socket-reader");
        thread.start();
    }

    write(chunk, encoding, callback) {
        const cb = typeof encoding === "function" ? encoding : callback;
        if (!this._jSocket || this.destroyed) {
            if (cb) nextTick(() => cb(new Error("mycelium: net.Socket#write called before connect")));
            return false;
        }
        try {
            const bytes = toJByteArray(chunk);
            this._jSocket.getOutputStream().write(bytes, 0, bytes.length);
            this.bytesWritten += bytes.length;
            if (cb) nextTick(cb);
            return true;
        } catch (e) {
            const err = e instanceof Error ? e : new Error(String(e && e.message ? e.message : e));
            if (cb) nextTick(() => cb(err));
            else nextTick(() => this.emit("error", err));
            return false;
        }
    }

    end(chunk, encoding, callback) {
        if (typeof chunk === "function") {
            callback = chunk;
            chunk = undefined;
        }
        const finish = () => {
            try {
                if (this._jSocket && !this._jSocket.isOutputShutdown()) {
                    this._jSocket.shutdownOutput();
                }
            } catch (e) {
                // best-effort — some socket states/platforms don't support half-close
            }
            if (typeof callback === "function") nextTick(callback);
        };
        if (chunk !== undefined) {
            this.write(chunk, encoding, finish);
        } else {
            finish();
        }
        return this;
    }

    destroy(err) {
        if (this.destroyed) return this;
        this.destroyed = true;
        if (err) this.emit("error", err instanceof Error ? err : new Error(String(err)));
        try {
            if (this._jSocket && !this._jSocket.isClosed()) this._jSocket.close();
        } catch (e) {
            // best-effort
        }
        this._finishClose();
        return this;
    }

    pause() {
        this._paused = true;
        return this;
    }

    resume() {
        this._paused = false;
        return this;
    }

    ref() {
        return this;
    }

    unref() {
        return this;
    }

    _finishClose() {
        if (this._closed) return;
        this._closed = true;
        nextTick(() => this.emit("close", !!this._hadError));
    }

    // Wraps an already-connected `java.net.Socket` (from `ServerSocket#accept()`) as a
    // Socket instance without going through the client-side `connect()` handshake —
    // mirrors how Node hands a freshly-accepted connection to a server's `'connection'`
    // listener.
    static _fromAccepted(jSocket) {
        const socket = new Socket();
        socket._jSocket = jSocket;
        nextTick(() => socket._afterConnect());
        return socket;
    }
}

function bytesToString(u8, encoding) {
    const bytes = toJByteArray(u8);
    const charset = String(encoding).toLowerCase() === "hex" ? null : JCharset.UTF_8;
    if (charset === null) {
        let hex = "";
        for (let i = 0; i < bytes.length; i++) {
            hex += (bytes[i] & 0xff).toString(16).padStart(2, "0");
        }
        return hex;
    }
    return String(new (Java.type("java.lang.String"))(bytes, charset));
}

// ---- net.Server ----

class Server extends EventEmitter {
    constructor(optionsOrListener, connectionListener) {
        super();
        let listener = connectionListener;
        if (typeof optionsOrListener === "function") {
            listener = optionsOrListener;
        }
        if (typeof listener === "function") this.on("connection", listener);
        this._jServerSocket = null;
        this.listening = false;
        this.maxConnections = Infinity;
    }

    listen(...args) {
        let port = 0;
        let host = "0.0.0.0";
        let callback;
        const first = args[0];
        if (first && typeof first === "object") {
            if (first.path) {
                throw new Error(
                    "mycelium: net.Server#listen on a UNIX domain socket path is not implemented — " +
                        "only TCP host:port listening is supported"
                );
            }
            port = first.port || 0;
            host = first.host || host;
            callback = typeof args[1] === "function" ? args[1] : undefined;
        } else if (typeof first === "string" && !/^\d+$/.test(first)) {
            throw new Error(
                "mycelium: net.Server#listen on a UNIX domain socket path is not implemented — " +
                    "only TCP host:port listening is supported"
            );
        } else {
            port = Number(first) || 0;
            if (typeof args[1] === "string") {
                host = args[1];
                callback = typeof args[2] === "function" ? args[2] : undefined;
            } else {
                callback = typeof args[1] === "function" ? args[1] : undefined;
            }
        }

        this._jServerSocket = new JServerSocket();
        this._jServerSocket.bind(new JInetSocketAddress(host, port));
        this.listening = true;

        const self = this;
        const acceptThread = new JThread(function () {
            while (!self._jServerSocket.isClosed()) {
                let jClientSocket;
                try {
                    jClientSocket = self._jServerSocket.accept();
                } catch (e) {
                    break; // server socket closed — accept loop ends
                }
                const socket = Socket._fromAccepted(jClientSocket);
                self.emit("connection", socket);
            }
        });
        acceptThread.setDaemon(true);
        acceptThread.setName("mycelium-net-server-accept");
        acceptThread.start();

        if (typeof callback === "function") this.once("listening", callback);
        nextTick(() => this.emit("listening"));
        return this;
    }

    address() {
        if (!this._jServerSocket || this._jServerSocket.isClosed()) return null;
        const addr = this._jServerSocket.getInetAddress();
        const family = String(addr.getHostAddress()).indexOf(":") !== -1 ? "IPv6" : "IPv4";
        return { address: String(addr.getHostAddress()), family, port: this._jServerSocket.getLocalPort() };
    }

    close(callback) {
        if (typeof callback === "function") this.once("close", callback);
        if (this._jServerSocket) {
            try {
                this._jServerSocket.close();
            } catch (e) {
                // best-effort
            }
            this.listening = false;
        }
        nextTick(() => this.emit("close"));
        return this;
    }

    getConnections(callback) {
        if (typeof callback === "function") nextTick(() => callback(null, 0));
        return this;
    }
}

// ---- module-level factory functions ----

export function connect(...args) {
    const socket = new Socket();
    return socket.connect(...args);
}

export const createConnection = connect;

export function createServer(optionsOrListener, connectionListener) {
    return new Server(optionsOrListener, connectionListener);
}

export { Socket, Server };

export default {
    isIP, isIPv4, isIPv6, connect, createConnection, createServer, Socket, Server,
};

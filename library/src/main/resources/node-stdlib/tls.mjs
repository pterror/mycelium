// Minimal Node.js-compatible `tls` module, served by Mycelium as the synthetic core module
// resolved for `import ... from "tls"` / `"node:tls"`. `TLSSocket`/`connect` reuse `net`'s
// plain `Socket` (see net.mjs) for the EventEmitter/stream surface and its background
// reader-thread pattern, but source their underlying `java.net.Socket` from
// `javax.net.ssl.SSLSocketFactory` instead — either a fresh TLS connection, or an upgrade of
// an already-connected plain socket (`tls.connect({ socket, ... })`, the pattern
// `https-proxy-agent`/`socks-proxy-agent` use to TLS-wrap a CONNECT-tunnel socket).
//
// Like https.mjs, `rejectUnauthorized: false` (Node's self-signed/local-dev escape hatch)
// isn't implemented — connections always validate against the JVM's default trust store.
// `createServer`/`createSecureContext` are not implemented: a real TLS server needs a
// configured `SSLContext` built from JS-supplied PEM strings, the same gap https.mjs's
// `createServer` documents.

import { Socket } from "net";

const JSSLSocketFactory = Java.type("javax.net.ssl.SSLSocketFactory");
const JInetSocketAddress = Java.type("java.net.InetSocketAddress");

function nextTick(fn) {
    Promise.resolve().then(fn);
}

class TLSSocket extends Socket {
    constructor(options) {
        super(options);
        this._tlsOptions = options || {};
        this.encrypted = true;
        this.authorized = false;
        this.authorizationError = null;
    }

    connect(...args) {
        let options = {};
        let callback;
        if (args[0] && typeof args[0] === "object") {
            options = args[0];
            callback = typeof args[1] === "function" ? args[1] : undefined;
        } else {
            options = Object.assign({}, this._tlsOptions, { port: args[0], host: args[1] });
            callback = typeof args[2] === "function" ? args[2] : typeof args[1] === "function" ? args[1] : undefined;
        }
        options = Object.assign({}, this._tlsOptions, options);
        if (typeof callback === "function") this.once("secureConnect", callback);

        this.connecting = true;
        this.pending = true;
        nextTick(() => {
            try {
                const factory = JSSLSocketFactory.getDefault();
                let jRawSocket;
                let host = options.host || "localhost";
                let port = options.port;
                let jTlsSocket;
                if (options.socket && options.socket._jSocket) {
                    // Upgrade an already-connected plain socket in place (the CONNECT-tunnel
                    // pattern) — `autoClose: true` so closing the TLS layer also closes the
                    // underlying raw socket, matching how Node's single logical socket
                    // object behaves.
                    jRawSocket = options.socket._jSocket;
                    host = options.host || jRawSocket.getInetAddress().getHostName();
                    port = options.port || jRawSocket.getPort();
                    jTlsSocket = factory.createSocket(jRawSocket, host, port, true);
                } else {
                    jTlsSocket = factory.createSocket();
                    jTlsSocket.connect(new JInetSocketAddress(host, port), options.timeout || 0);
                }
                jTlsSocket.startHandshake();
                this._jSocket = jTlsSocket;
                this.authorized = true;
                this._afterConnect();
                this.emit("secureConnect");
            } catch (e) {
                this.connecting = false;
                this.authorized = false;
                this.authorizationError = e instanceof Error ? e.message : String(e);
                this.emit("error", e instanceof Error ? e : new Error(String(e && e.message ? e.message : e)));
            }
        });
        return this;
    }

    getProtocol() {
        try {
            return this._jSocket ? String(this._jSocket.getSession().getProtocol()) : null;
        } catch (e) {
            return null;
        }
    }

    getCipher() {
        try {
            if (!this._jSocket) return null;
            const suite = String(this._jSocket.getSession().getCipherSuite());
            return { name: suite, standardName: suite, version: this.getProtocol() };
        } catch (e) {
            return null;
        }
    }

    getPeerCertificate() {
        // Real Node returns a parsed X.509 certificate object; extracting and reshaping
        // one from `javax.net.ssl.SSLSession#getPeerCertificates()` into Node's exact
        // shape isn't wired up here — callers that only check "is there a peer cert at
        // all" still get a truthy/falsy-consistent empty object rather than a throw.
        return {};
    }
}

export function connect(...args) {
    let options = {};
    let callback;
    if (args[0] && typeof args[0] === "object" && typeof args[0] !== "function") {
        options = args[0];
        callback = typeof args[1] === "function" ? args[1] : undefined;
    } else {
        options = { port: args[0], host: typeof args[1] === "string" ? args[1] : undefined };
        callback = args.find((a) => typeof a === "function");
    }
    const socket = new TLSSocket(options);
    return socket.connect(options, callback);
}

export function createServer() {
    throw new Error(
        "mycelium: tls.createServer is not implemented — a real TLS server needs a configured " +
            "SSLContext (certificate + private key) built from JS-supplied PEM strings, which isn't " +
            "wired up in this build (see https.mjs's createServer for the same gap)"
    );
}

export function createSecureContext() {
    throw new Error(
        "mycelium: tls.createSecureContext is not implemented — see tls.createServer's error for details"
    );
}

export { TLSSocket };

export default { connect, createServer, createSecureContext, TLSSocket };

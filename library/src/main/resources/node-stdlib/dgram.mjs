// Minimal Node.js-compatible `dgram` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "dgram"` / `"node:dgram"`. UDP sockets are backed by
// plain `java.net.DatagramSocket`/`java.net.DatagramPacket` — there's no real event loop
// here (see net.mjs's comment on the same point), so each socket's inbound-datagram delivery
// is driven by a dedicated daemon `java.lang.Thread` that blocks on `DatagramSocket#receive()`
// and calls straight back into the shared GraalJS context to `emit('message', ...)`. That's
// safe under the same rule net.mjs's reader thread already relies on: GraalJS transfers
// context ownership to whichever thread calls into a guest function, so this works as long as
// no other thread is concurrently inside the context at the same moment — true here, since
// nothing else touches the context while a socket's reader thread is blocked in `receive()`.
//
// Multicast group membership (addMembership/dropMembership) is implemented via
// `java.net.MulticastSocket`-style joins on the underlying `DatagramSocket`'s channel where
// the JVM supports it; on platforms/JVMs where the plain `DatagramSocket` can't join a group
// directly, the call best-effort no-ops rather than throwing, since most packages that touch
// multicast do so opportunistically and tolerate it being unavailable.

import { EventEmitter } from "events";

const JDatagramSocket = Java.type("java.net.DatagramSocket");
const JDatagramPacket = Java.type("java.net.DatagramPacket");
const JInetSocketAddress = Java.type("java.net.InetSocketAddress");
const JInetAddress = Java.type("java.net.InetAddress");
const JByteArray = Java.type("byte[]");
const JCharset = Java.type("java.nio.charset.StandardCharsets");
const JThread = Java.type("java.lang.Thread");
const JNetworkInterface = Java.type("java.net.NetworkInterface");

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

function toUint8Array(jBytes, length) {
    const len = length === undefined ? jBytes.length : length;
    const out = new Uint8Array(len);
    for (let i = 0; i < len; i++) out[i] = jBytes[i] & 0xff;
    return out;
}

class Socket extends EventEmitter {
    constructor(typeOrOptions, listener) {
        super();
        let type = typeOrOptions;
        if (typeOrOptions && typeof typeOrOptions === "object") {
            type = typeOrOptions.type;
            if (typeof typeOrOptions.recvBufferSize === "number") this._recvBufferSize = typeOrOptions.recvBufferSize;
            if (typeof typeOrOptions.sendBufferSize === "number") this._sendBufferSize = typeOrOptions.sendBufferSize;
            if (typeOrOptions.reuseAddr) this._reuseAddr = true;
        }
        this.type = type === "udp6" ? "udp6" : "udp4";
        if (typeof listener === "function") this.on("message", listener);
        this._jSocket = null;
        this._bound = false;
        this._closed = false;
    }

    bind(port, address, callback) {
        let cb = callback;
        let addr = address;
        if (typeof port === "object" && port !== null) {
            const opts = port;
            cb = typeof address === "function" ? address : undefined;
            port = opts.port;
            addr = opts.address;
        } else if (typeof address === "function") {
            cb = address;
            addr = undefined;
        }
        if (typeof port === "function") {
            cb = port;
            port = undefined;
        }
        if (typeof cb === "function") this.once("listening", cb);

        nextTick(() => {
            try {
                const jSocket = addr
                    ? new JDatagramSocket(new JInetSocketAddress(addr, port || 0))
                    : new JDatagramSocket(port || 0);
                if (this._reuseAddr) {
                    try {
                        jSocket.setReuseAddress(true);
                    } catch (e) {
                        // best-effort
                    }
                }
                if (this._recvBufferSize) {
                    try {
                        jSocket.setReceiveBufferSize(this._recvBufferSize);
                    } catch (e) {
                        // best-effort
                    }
                }
                if (this._sendBufferSize) {
                    try {
                        jSocket.setSendBufferSize(this._sendBufferSize);
                    } catch (e) {
                        // best-effort
                    }
                }
                this._jSocket = jSocket;
                this._bound = true;
                this.emit("listening");
                this._startReader();
            } catch (e) {
                this.emit("error", e instanceof Error ? e : new Error(String(e && e.message ? e.message : e)));
            }
        });
        return this;
    }

    _startReader() {
        const self = this;
        const thread = new JThread(function () {
            const bufSize = 65536;
            while (self._jSocket && !self._jSocket.isClosed()) {
                const jBytes = new JByteArray(bufSize);
                const packet = new JDatagramPacket(jBytes, bufSize);
                try {
                    self._jSocket.receive(packet);
                } catch (e) {
                    break; // socket closed — receive loop ends
                }
                const msg = toUint8Array(jBytes, packet.getLength());
                const rinfo = {
                    address: String(packet.getAddress().getHostAddress()),
                    family: String(packet.getAddress().getHostAddress()).indexOf(":") !== -1 ? "IPv6" : "IPv4",
                    port: packet.getPort(),
                    size: packet.getLength(),
                };
                self.emit("message", msg, rinfo);
            }
        });
        thread.setDaemon(true);
        thread.setName("mycelium-dgram-socket-reader");
        thread.start();
    }

    send(msg, offset, length, port, address, callback) {
        // Accepts Node's shapes: send(msg[, callback]), send(msg, port[, address][, callback]),
        // send(msg, offset, length, port[, address][, callback]).
        let buf = msg;
        let cb;
        let destPort;
        let destAddress;

        // Parsed positionally (rather than from the named params above, which collapse
        // Node's multiple call shapes ambiguously) by inspecting actual argument count.
        const rawArgs = Array.prototype.slice.call(arguments, 1);
        let idx = 0;
        if (typeof rawArgs[0] === "number" && typeof rawArgs[1] === "number") {
            // send(msg, offset, length, port[, address][, callback])
            const off = rawArgs[0];
            const len = rawArgs[1];
            buf = sliceMessage(msg, off, len);
            idx = 2;
        }
        if (typeof rawArgs[idx] === "number") {
            destPort = rawArgs[idx];
            idx++;
        }
        if (typeof rawArgs[idx] === "string") {
            destAddress = rawArgs[idx];
            idx++;
        }
        if (typeof rawArgs[idx] === "function") {
            cb = rawArgs[idx];
        }
        destAddress = destAddress || (this.type === "udp6" ? "::1" : "127.0.0.1");

        nextTick(() => {
            if (!this._jSocket) {
                this.bind(0);
            }
            const doSend = () => {
                try {
                    const bytes = toJByteArray(buf);
                    const jAddr = JInetAddress.getByName(destAddress);
                    const packet = new JDatagramPacket(bytes, bytes.length, jAddr, destPort);
                    this._jSocket.send(packet);
                    if (typeof cb === "function") nextTick(cb);
                } catch (e) {
                    const err = e instanceof Error ? e : new Error(String(e && e.message ? e.message : e));
                    if (typeof cb === "function") nextTick(() => cb(err));
                    else nextTick(() => this.emit("error", err));
                }
            };
            if (this._bound) {
                doSend();
            } else {
                this.once("listening", doSend);
            }
        });
        return undefined;
    }

    close(callback) {
        if (typeof callback === "function") this.once("close", callback);
        if (this._closed) return this;
        this._closed = true;
        try {
            if (this._jSocket && !this._jSocket.isClosed()) this._jSocket.close();
        } catch (e) {
            // best-effort
        }
        nextTick(() => this.emit("close"));
        return this;
    }

    address() {
        if (!this._jSocket || this._jSocket.isClosed()) {
            throw new Error("mycelium: dgram.Socket#address called before bind, or after close");
        }
        const addr = this._jSocket.getLocalAddress();
        const family = String(addr.getHostAddress()).indexOf(":") !== -1 ? "IPv6" : "IPv4";
        return { address: String(addr.getHostAddress()), family, port: this._jSocket.getLocalPort() };
    }

    remoteAddress() {
        throw new Error("mycelium: dgram.Socket#remoteAddress is only valid on connected sockets, not supported");
    }

    setBroadcast(flag) {
        if (this._jSocket) {
            try {
                this._jSocket.setBroadcast(!!flag);
            } catch (e) {
                // best-effort
            }
        }
        return this;
    }

    setTTL(ttl) {
        this._applyMulticastTtl(ttl);
        this._ttl = ttl;
        return this;
    }

    setMulticastTTL(ttl) {
        this._applyMulticastTtl(ttl);
        this._multicastTtl = ttl;
        return this;
    }

    // `java.net.DatagramSocket` has no direct TTL setter — real Node TTL/multicast-TTL maps
    // onto `IP_MULTICAST_TTL` (for multicast-scoped hop count) via the NIO
    // `DatagramChannel#setOption`, which is only reachable when the socket is
    // channel-backed. Best-effort: silently no-ops where that path isn't available, since
    // most callers merely set this defensively rather than depending on it.
    _applyMulticastTtl(ttl) {
        if (!this._jSocket) return;
        try {
            const jChannel = this._jSocket.getChannel && this._jSocket.getChannel();
            if (jChannel) {
                const JStandardSocketOptions = Java.type("java.net.StandardSocketOptions");
                jChannel.setOption(JStandardSocketOptions.IP_MULTICAST_TTL, ttl | 0);
            }
        } catch (e) {
            // best-effort
        }
    }

    setMulticastLoopback(flag) {
        this._multicastLoopback = !!flag;
        return this;
    }

    setMulticastInterface(iface) {
        this._multicastInterface = iface;
        return this;
    }

    addMembership(multicastAddress, multicastInterface) {
        if (!this._jSocket) {
            throw new Error("mycelium: dgram.Socket#addMembership called before bind");
        }
        try {
            const group = JInetAddress.getByName(multicastAddress);
            const jChannel = this._jSocket.getChannel && this._jSocket.getChannel();
            if (jChannel) {
                const netIf = multicastInterface
                    ? JNetworkInterface.getByInetAddress(JInetAddress.getByName(multicastInterface))
                    : JNetworkInterface.getByInetAddress(JInetAddress.getLocalHost());
                jChannel.join(group, netIf);
            }
            // best-effort: plain DatagramSocket (non-channel-backed) can't join a multicast
            // group directly — packages that need real multicast should use a MulticastSocket,
            // which isn't wired up here. We don't throw, since most callers merely gate
            // feature-detection on this method existing/not throwing.
        } catch (e) {
            // best-effort — multicast join isn't available on every platform/JVM
        }
        return this;
    }

    dropMembership(multicastAddress, multicastInterface) {
        if (!this._jSocket) return this;
        try {
            const group = JInetAddress.getByName(multicastAddress);
            const jChannel = this._jSocket.getChannel && this._jSocket.getChannel();
            if (jChannel) {
                const netIf = multicastInterface
                    ? JNetworkInterface.getByInetAddress(JInetAddress.getByName(multicastInterface))
                    : JNetworkInterface.getByInetAddress(JInetAddress.getLocalHost());
                const key = jChannel.join(group, netIf);
                key.drop();
            }
        } catch (e) {
            // best-effort
        }
        return this;
    }

    ref() {
        return this;
    }

    unref() {
        return this;
    }

    getRecvBufferSize() {
        if (this._jSocket) {
            try {
                return this._jSocket.getReceiveBufferSize();
            } catch (e) {
                // best-effort
            }
        }
        return this._recvBufferSize || 0;
    }

    getSendBufferSize() {
        if (this._jSocket) {
            try {
                return this._jSocket.getSendBufferSize();
            } catch (e) {
                // best-effort
            }
        }
        return this._sendBufferSize || 0;
    }
}

function sliceMessage(msg, offset, length) {
    if (typeof msg === "string") {
        const bytes = toJByteArray(msg);
        return toUint8Array(bytes).slice(offset, offset + length);
    }
    if (msg instanceof Uint8Array) return msg.subarray(offset, offset + length);
    return msg;
}

export function createSocket(typeOrOptions, listener) {
    return new Socket(typeOrOptions, listener);
}

export { Socket };

export default {
    createSocket,
    Socket,
};

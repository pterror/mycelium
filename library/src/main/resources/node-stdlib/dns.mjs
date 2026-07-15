// Minimal Node.js-compatible `dns` module, served by Mycelium as the synthetic core module
// resolved for `import ... from "dns"` / `"node:dns"` / `require("dns")`. Backed by
// `java.net.InetAddress` for forward/reverse lookups. Node's richer record types (MX, TXT,
// SRV, NS, CNAME, SOA, ...) require a real DNS client capable of issuing raw queries for
// those RR types, which the JDK's public API doesn't expose without pulling in
// `com.sun.jndi.dns` (an internal, JPMS-encapsulated package not reliably available across
// JDK/GraalVM builds) — those resolvers are stubbed out below with a clear
// "not supported" error rather than silently returning wrong data.

const JInetAddress = Java.type("java.net.InetAddress");
const JUnknownHostException = Java.type("java.net.UnknownHostException");

export const ADDRCONFIG = 0;
export const V4MAPPED = 1;
export const ALL = 2;

let servers = ["8.8.8.8", "8.8.4.4"];

export function getServers() {
    return servers.slice();
}

export function setServers(newServers) {
    servers = Array.isArray(newServers) ? newServers.slice() : [];
}

function isIPv4(addr) {
    return /^(\d{1,3}\.){3}\d{1,3}$/.test(addr);
}

function isIPv6(addr) {
    return typeof addr === "string" && addr.includes(":");
}

class DnsError extends Error {
    constructor(code, hostname) {
        super(`${code}: ${hostname ? `queryA ${hostname}` : "dns error"}`);
        this.code = code;
        this.errno = code;
        this.hostname = hostname;
        this.name = "Error";
    }
}

function toErrback(fn, ...args) {
    const cb = args[args.length - 1];
    if (typeof cb !== "function") {
        throw new TypeError("Callback must be a function");
    }
    Promise.resolve().then(() => {
        try {
            fn(...args.slice(0, -1), cb);
        } catch (e) {
            cb(e);
        }
    });
}

// --- lookup ---

function normalizeLookupOptions(options) {
    if (typeof options === "number") return { family: options };
    return options || {};
}

export function lookup(hostname, options, callback) {
    let opts = options;
    let cb = callback;
    if (typeof opts === "function") {
        cb = opts;
        opts = {};
    }
    opts = normalizeLookupOptions(opts);
    if (typeof cb !== "function") {
        throw new TypeError("Callback must be a function");
    }
    Promise.resolve().then(() => {
        try {
            const addrs = JInetAddress.getAllByName(hostname);
            const family = opts.family;
            const all = opts.all === true;
            const results = [];
            for (let i = 0; i < addrs.length; i++) {
                const addr = String(addrs[i].getHostAddress());
                const addrFamily = isIPv6(addr) ? 6 : 4;
                if (family === 4 && addrFamily !== 4) continue;
                if (family === 6 && addrFamily !== 6) continue;
                results.push({ address: addr, family: addrFamily });
            }
            if (results.length === 0) {
                cb(new DnsError("ENOTFOUND", hostname));
                return;
            }
            if (all) {
                cb(null, results);
            } else {
                cb(null, results[0].address, results[0].family);
            }
        } catch (e) {
            if (e instanceof JUnknownHostException) {
                cb(new DnsError("ENOTFOUND", hostname));
            } else {
                cb(e);
            }
        }
    });
    return undefined;
}

export function lookupService(address, port, callback) {
    if (typeof callback !== "function") {
        throw new TypeError("Callback must be a function");
    }
    Promise.resolve().then(() => {
        try {
            const addr = JInetAddress.getByName(address);
            const hostname = String(addr.getCanonicalHostName());
            callback(null, hostname, "unknown");
        } catch (e) {
            callback(e);
        }
    });
    return undefined;
}

// --- resolve* ---

function resolveAddresses(hostname, wantFamily, callback) {
    Promise.resolve().then(() => {
        try {
            const addrs = JInetAddress.getAllByName(hostname);
            const results = [];
            for (let i = 0; i < addrs.length; i++) {
                const addr = String(addrs[i].getHostAddress());
                const family = isIPv6(addr) ? 6 : 4;
                if (family === wantFamily) results.push(addr);
            }
            if (results.length === 0) {
                callback(new DnsError("ENOTFOUND", hostname));
                return;
            }
            callback(null, results);
        } catch (e) {
            callback(e instanceof JUnknownHostException ? new DnsError("ENOTFOUND", hostname) : e);
        }
    });
    return undefined;
}

export function resolve4(hostname, options, callback) {
    let cb = callback;
    if (typeof options === "function") cb = options;
    return resolveAddresses(hostname, 4, cb);
}

export function resolve6(hostname, options, callback) {
    let cb = callback;
    if (typeof options === "function") cb = options;
    return resolveAddresses(hostname, 6, cb);
}

const UNSUPPORTED_RRTYPES = new Set(["MX", "TXT", "SRV", "NS", "CNAME", "SOA", "PTR", "NAPTR", "CAA"]);

export function resolve(hostname, rrtype, callback) {
    let type = rrtype;
    let cb = callback;
    if (typeof type === "function") {
        cb = type;
        type = "A";
    }
    type = (type || "A").toUpperCase();
    if (typeof cb !== "function") {
        throw new TypeError("Callback must be a function");
    }
    if (type === "A") return resolveAddresses(hostname, 4, cb);
    if (type === "AAAA") return resolveAddresses(hostname, 6, cb);
    if (type === "CNAME") return reverseCname(hostname, cb);
    if (UNSUPPORTED_RRTYPES.has(type)) {
        Promise.resolve().then(() => {
            cb(new Error(`dns.resolve: record type '${type}' is not supported (requires a full DNS client, unavailable via java.net.InetAddress)`));
        });
        return undefined;
    }
    Promise.resolve().then(() => cb(new Error(`dns.resolve: unknown record type '${type}'`)));
    return undefined;
}

function reverseCname(hostname, callback) {
    Promise.resolve().then(() => {
        try {
            const addr = JInetAddress.getByName(hostname);
            callback(null, [String(addr.getCanonicalHostName())]);
        } catch (e) {
            callback(e instanceof JUnknownHostException ? new DnsError("ENOTFOUND", hostname) : e);
        }
    });
    return undefined;
}

function unsupportedResolver(rrtype) {
    return function (hostname, callback) {
        const cb = typeof callback === "function" ? callback : arguments[arguments.length - 1];
        Promise.resolve().then(() => {
            cb(new Error(`dns.resolve${rrtype}: record type '${rrtype}' is not supported (requires a full DNS client, unavailable via java.net.InetAddress)`));
        });
        return undefined;
    };
}

export const resolveMx = unsupportedResolver("Mx");
export const resolveTxt = unsupportedResolver("Txt");
export const resolveSrv = unsupportedResolver("Srv");
export const resolveNs = unsupportedResolver("Ns");
export const resolveCname = reverseCname;
export const resolveSoa = unsupportedResolver("Soa");
export const resolvePtr = unsupportedResolver("Ptr");
export const resolveNaptr = unsupportedResolver("Naptr");
export const resolveCaa = unsupportedResolver("Caa");
export const resolveAny = unsupportedResolver("Any");

export function reverse(ip, callback) {
    if (typeof callback !== "function") {
        throw new TypeError("Callback must be a function");
    }
    Promise.resolve().then(() => {
        try {
            const addr = JInetAddress.getByName(ip);
            const hostname = String(addr.getCanonicalHostName());
            callback(null, [hostname]);
        } catch (e) {
            callback(e instanceof JUnknownHostException ? new DnsError("ENOTFOUND", ip) : e);
        }
    });
    return undefined;
}

// --- Resolver class ---

export class Resolver {
    constructor(options) {
        this._servers = servers.slice();
    }

    getServers() {
        return this._servers.slice();
    }

    setServers(newServers) {
        this._servers = Array.isArray(newServers) ? newServers.slice() : [];
    }

    resolve(hostname, rrtype, callback) {
        return resolve(hostname, rrtype, callback);
    }

    resolve4(hostname, options, callback) {
        return resolve4(hostname, options, callback);
    }

    resolve6(hostname, options, callback) {
        return resolve6(hostname, options, callback);
    }

    reverse(ip, callback) {
        return reverse(ip, callback);
    }

    resolveMx(hostname, callback) {
        return resolveMx(hostname, callback);
    }

    resolveTxt(hostname, callback) {
        return resolveTxt(hostname, callback);
    }

    resolveSrv(hostname, callback) {
        return resolveSrv(hostname, callback);
    }

    resolveNs(hostname, callback) {
        return resolveNs(hostname, callback);
    }

    resolveCname(hostname, callback) {
        return resolveCname(hostname, callback);
    }

    resolveSoa(hostname, callback) {
        return resolveSoa(hostname, callback);
    }

    resolvePtr(hostname, callback) {
        return resolvePtr(hostname, callback);
    }

    resolveNaptr(hostname, callback) {
        return resolveNaptr(hostname, callback);
    }

    resolveCaa(hostname, callback) {
        return resolveCaa(hostname, callback);
    }

    resolveAny(hostname, callback) {
        return resolveAny(hostname, callback);
    }

    cancel() {
        // No pending-request tracking to cancel — all lookups here are synchronous Java
        // calls wrapped in a resolved microtask, not real in-flight network requests.
    }

    setLocalAddress() {
        // No-op: java.net.InetAddress offers no equivalent to binding the outgoing query
        // socket to a specific local address/port pair.
    }
}

// --- promises namespace ---

function promisify1(fn) {
    return function (...args) {
        return new Promise((resolve, reject) => {
            fn(...args, (err, result) => {
                if (err) reject(err);
                else resolve(result);
            });
        });
    };
}

export const promises = {
    lookup: function (hostname, options) {
        return new Promise((resolve, reject) => {
            lookup(hostname, options || {}, (err, address, family) => {
                if (err) reject(err);
                else if (options && options.all) resolve(address);
                else resolve({ address, family });
            });
        });
    },
    lookupService: promisify1(lookupService),
    resolve: promisify1(resolve),
    resolve4: promisify1(resolve4),
    resolve6: promisify1(resolve6),
    reverse: promisify1(reverse),
    resolveMx: promisify1(resolveMx),
    resolveTxt: promisify1(resolveTxt),
    resolveSrv: promisify1(resolveSrv),
    resolveNs: promisify1(resolveNs),
    resolveCname: promisify1(resolveCname),
    resolveSoa: promisify1(resolveSoa),
    resolvePtr: promisify1(resolvePtr),
    resolveNaptr: promisify1(resolveNaptr),
    resolveCaa: promisify1(resolveCaa),
    resolveAny: promisify1(resolveAny),
    getServers,
    setServers,
    Resolver,
};

export default {
    ADDRCONFIG,
    V4MAPPED,
    ALL,
    getServers,
    setServers,
    lookup,
    lookupService,
    resolve,
    resolve4,
    resolve6,
    resolveMx,
    resolveTxt,
    resolveSrv,
    resolveNs,
    resolveCname,
    resolveSoa,
    resolvePtr,
    resolveNaptr,
    resolveCaa,
    resolveAny,
    reverse,
    Resolver,
    promises,
};

// Minimal Node.js-compatible `url` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "url"` / `"node:url"`. GraalJS's plain "js"
// language (unlike Node.js or a browser) doesn't ship WHATWG `URL`/`URLSearchParams` as
// globals, so both are implemented here in pure JS (a reasonably-compliant subset — not a
// full WHATWG URL Standard parser, but enough for the common `protocol`/`host`/
// `hostname`/`port`/`pathname`/`search`/`hash`/`searchParams` surface); the legacy
// `url.parse`/`url.format`/`url.resolve` API and the `pathToFileURL`/`fileURLToPath`
// helpers are layered on top, with `java.net.URI`/`java.io.File` used for the
// filesystem-path <-> file: URL conversions where Java interop is the natural fit.

const JFile = Java.type("java.io.File");
const JURI = Java.type("java.net.URI");

const URL_RE =
    /^([a-zA-Z][a-zA-Z0-9+.-]*:)(\/\/(?:([^:@/?#]*)(?::([^@/?#]*))?@)?([^:/?#]*)(?::(\d*))?)?([^?#]*)(\?[^#]*)?(#.*)?$/;

const SPECIAL_PORTS = {
    "ftp:": "21", "http:": "80", "https:": "443", "ws:": "80", "wss:": "443", "file:": "",
};

class URLSearchParams {
    constructor(init) {
        this._params = [];
        if (!init) {
            // nothing to seed
        } else if (typeof init === "string") {
            const s = init.startsWith("?") ? init.slice(1) : init;
            if (s.length > 0) {
                for (const pair of s.split("&")) {
                    if (pair === "") continue;
                    const eq = pair.indexOf("=");
                    const key = eq === -1 ? pair : pair.slice(0, eq);
                    const value = eq === -1 ? "" : pair.slice(eq + 1);
                    this._params.push([decodeURIComponent(key.replace(/\+/g, " ")), decodeURIComponent(value.replace(/\+/g, " "))]);
                }
            }
        } else if (init instanceof URLSearchParams) {
            this._params = init._params.map(([k, v]) => [k, v]);
        } else if (Array.isArray(init)) {
            this._params = init.map(([k, v]) => [String(k), String(v)]);
        } else if (typeof init[Symbol.iterator] === "function") {
            for (const [k, v] of init) this._params.push([String(k), String(v)]);
        } else if (typeof init === "object") {
            for (const key of Object.keys(init)) this._params.push([key, String(init[key])]);
        }
    }

    append(name, value) {
        this._params.push([String(name), String(value)]);
    }

    delete(name) {
        this._params = this._params.filter(([k]) => k !== name);
    }

    get(name) {
        const found = this._params.find(([k]) => k === name);
        return found ? found[1] : null;
    }

    getAll(name) {
        return this._params.filter(([k]) => k === name).map(([, v]) => v);
    }

    has(name) {
        return this._params.some(([k]) => k === name);
    }

    set(name, value) {
        let replaced = false;
        this._params = this._params.filter(([k]) => {
            if (k !== name) return true;
            if (!replaced) {
                replaced = true;
                return true;
            }
            return false;
        });
        if (replaced) {
            const idx = this._params.findIndex(([k]) => k === name);
            this._params[idx] = [name, String(value)];
        } else {
            this._params.push([String(name), String(value)]);
        }
    }

    sort() {
        this._params.sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
    }

    forEach(callback, thisArg) {
        for (const [k, v] of this._params) callback.call(thisArg, v, k, this);
    }

    keys() {
        return this._params.map(([k]) => k)[Symbol.iterator]();
    }

    values() {
        return this._params.map(([, v]) => v)[Symbol.iterator]();
    }

    entries() {
        return this._params.map(([k, v]) => [k, v])[Symbol.iterator]();
    }

    [Symbol.iterator]() {
        return this.entries();
    }

    toString() {
        return this._params
            .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
            .join("&");
    }
}

class URL {
    constructor(input, base) {
        let resolved = String(input);
        if (base !== undefined) {
            resolved = resolveUrlString(String(base), resolved);
        } else if (!URL_RE.test(resolved) || !/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(resolved)) {
            throw new TypeError(`Invalid URL: ${input}`);
        }
        const match = URL_RE.exec(resolved);
        if (!match) throw new TypeError(`Invalid URL: ${input}`);
        const [, protocol, authority, username, password, hostname, port, pathname, search, hash] = match;
        this._protocol = protocol;
        this._username = username || "";
        this._password = password || "";
        this._hostname = hostname || "";
        this._port = port || "";
        this._pathname = pathname || (authority ? "/" : "");
        this._hash = hash || "";
        this._searchParams = new URLSearchParams(search || "");
        this._searchParams._url = this;
    }

    get protocol() {
        return this._protocol;
    }
    set protocol(v) {
        this._protocol = v.endsWith(":") ? v : `${v}:`;
    }

    get username() {
        return this._username;
    }
    set username(v) {
        this._username = v;
    }

    get password() {
        return this._password;
    }
    set password(v) {
        this._password = v;
    }

    get hostname() {
        return this._hostname;
    }
    set hostname(v) {
        this._hostname = v;
    }

    get port() {
        return this._port === SPECIAL_PORTS[this._protocol] ? "" : this._port;
    }
    set port(v) {
        this._port = String(v);
    }

    get host() {
        return this._port ? `${this._hostname}:${this._port}` : this._hostname;
    }
    set host(v) {
        const idx = v.lastIndexOf(":");
        if (idx === -1) {
            this._hostname = v;
            this._port = "";
        } else {
            this._hostname = v.slice(0, idx);
            this._port = v.slice(idx + 1);
        }
    }

    get pathname() {
        return this._pathname;
    }
    set pathname(v) {
        this._pathname = v.startsWith("/") ? v : `/${v}`;
    }

    get search() {
        const s = this._searchParams.toString();
        return s ? `?${s}` : "";
    }
    set search(v) {
        this._searchParams = new URLSearchParams(v);
        this._searchParams._url = this;
    }

    get searchParams() {
        return this._searchParams;
    }

    get hash() {
        return this._hash;
    }
    set hash(v) {
        this._hash = v && !v.startsWith("#") ? `#${v}` : v;
    }

    get origin() {
        return this._hostname ? `${this._protocol}//${this.host}` : "null";
    }

    get href() {
        let out = this._protocol;
        // WHATWG "special" schemes (of which `file:` is one) always serialize the "//"
        // authority marker even when the host is empty — e.g. `file:///tmp/x` (empty
        // host, still "//"), not `file:/tmp/x`. This matters in practice for
        // `pathToFileURL`/`Bun.pathToFileURL` below and Node's own `url.pathToFileURL`
        // (same class): a local absolute path always parses to an empty-host `file:`
        // URL, and without this, the "//" was silently dropped (verified as a real
        // divergence from both Node's and real Bun's own `file:///...` output — this
        // class has no `_hasAuthority` flag from parsing to fall back on, so `file:` is
        // special-cased explicitly here rather than generalized to every scheme).
        if (this._hostname || this._username || this._protocol === "file:") {
            out += "//";
            if (this._username) {
                out += this._password ? `${this._username}:${this._password}@` : `${this._username}@`;
            }
            out += this.host;
        }
        out += this._pathname;
        out += this.search;
        out += this._hash;
        return out;
    }
    set href(v) {
        const u = new URL(v);
        this._protocol = u._protocol;
        this._username = u._username;
        this._password = u._password;
        this._hostname = u._hostname;
        this._port = u._port;
        this._pathname = u._pathname;
        this._hash = u._hash;
        this._searchParams = u._searchParams;
        this._searchParams._url = this;
    }

    toString() {
        return this.href;
    }

    toJSON() {
        return this.href;
    }

    static canParse(input, base) {
        try {
            new URL(input, base);
            return true;
        } catch (e) {
            return false;
        }
    }
}

// Resolves `to` against `from` per common relative-URL rules: an absolute `to` (has its
// own scheme) is returned unchanged; otherwise it's spliced onto `from`'s
// scheme/authority, resolving `.`/`..` segments and handling scheme-relative
// ("//host/path"), absolute-path ("/path"), and relative-path forms distinctly, the same
// four cases `URL`'s two-argument constructor and legacy `url.resolve` both need.
function resolveUrlString(from, to) {
    if (/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(to)) return to;
    const fromMatch = URL_RE.exec(from);
    if (!fromMatch) throw new TypeError(`Invalid URL: ${from}`);
    const [, protocol, authority] = fromMatch;
    if (to.startsWith("//")) {
        return `${protocol}${to}`;
    }
    const authorityPart = authority || "";
    if (to.startsWith("/")) {
        return `${protocol}${authorityPart}${normalizePathSegments(to)}`;
    }
    if (to === "" || to.startsWith("?") || to.startsWith("#")) {
        const fromPath = fromMatch[7] || "";
        return `${protocol}${authorityPart}${fromPath}${to}`;
    }
    const fromPath = fromMatch[7] || "/";
    const basePath = fromPath.slice(0, fromPath.lastIndexOf("/") + 1);
    return `${protocol}${authorityPart}${normalizePathSegments(basePath + to)}`;
}

function normalizePathSegments(path) {
    const [pathOnly, rest] = splitOffQueryAndHash(path);
    const segments = pathOnly.split("/");
    const out = [];
    for (const seg of segments) {
        if (seg === ".") continue;
        if (seg === "..") {
            if (out.length > 1) out.pop();
        } else {
            out.push(seg);
        }
    }
    return out.join("/") + rest;
}

function splitOffQueryAndHash(path) {
    const idx = path.search(/[?#]/);
    return idx === -1 ? [path, ""] : [path.slice(0, idx), path.slice(idx)];
}

export { URL, URLSearchParams };

// ---- Legacy API ----

export function parse(urlString, parseQueryString) {
    let u;
    try {
        u = new URL(urlString);
    } catch (e) {
        // Legacy url.parse tolerates relative/incomplete URLs that the WHATWG parser
        // rejects; fall back to a best-effort manual split.
        return legacyParseFallback(urlString, parseQueryString);
    }
    const search = u.search || "";
    return {
        protocol: u.protocol || null,
        slashes: urlString.includes("://") ? true : null,
        auth: u.username ? (u.password ? `${u.username}:${u.password}` : u.username) : null,
        host: u.host || null,
        port: u.port || null,
        hostname: u.hostname || null,
        hash: u.hash || null,
        search: search || null,
        query: parseQueryString ? Object.fromEntries(u.searchParams) : (search ? search.slice(1) : null),
        pathname: u.pathname || null,
        path: (u.pathname || "") + search,
        href: u.href,
    };
}

function legacyParseFallback(urlString, parseQueryString) {
    const result = {
        protocol: null, slashes: null, auth: null, host: null, port: null,
        hostname: null, hash: null, search: null, query: parseQueryString ? {} : null,
        pathname: null, path: null, href: urlString,
    };
    let rest = urlString;

    const hashIdx = rest.indexOf("#");
    if (hashIdx !== -1) {
        result.hash = rest.slice(hashIdx);
        rest = rest.slice(0, hashIdx);
    }

    const protoMatch = /^([a-zA-Z][a-zA-Z0-9+.-]*:)/.exec(rest);
    if (protoMatch) {
        result.protocol = protoMatch[1];
        rest = rest.slice(protoMatch[1].length);
    }

    if (rest.startsWith("//")) {
        result.slashes = true;
        rest = rest.slice(2);
        const pathIdx = rest.search(/[/?]/);
        const authority = pathIdx === -1 ? rest : rest.slice(0, pathIdx);
        rest = pathIdx === -1 ? "" : rest.slice(pathIdx);

        let hostPart = authority;
        const atIdx = authority.lastIndexOf("@");
        if (atIdx !== -1) {
            result.auth = authority.slice(0, atIdx);
            hostPart = authority.slice(atIdx + 1);
        }
        result.host = hostPart || null;
        const colonIdx = hostPart.lastIndexOf(":");
        if (colonIdx !== -1) {
            result.hostname = hostPart.slice(0, colonIdx);
            result.port = hostPart.slice(colonIdx + 1);
        } else {
            result.hostname = hostPart || null;
        }
    }

    const searchIdx = rest.indexOf("?");
    if (searchIdx !== -1) {
        result.search = rest.slice(searchIdx);
        result.pathname = rest.slice(0, searchIdx) || null;
        if (parseQueryString) {
            result.query = Object.fromEntries(new URLSearchParams(result.search));
        } else {
            result.query = result.search.slice(1);
        }
    } else {
        result.pathname = rest || null;
    }
    result.path = (result.pathname || "") + (result.search || "");
    return result;
}

export function format(urlObject) {
    if (urlObject instanceof URL) return urlObject.href;
    if (typeof urlObject === "string") return urlObject;

    let result = "";
    if (urlObject.protocol) {
        result += urlObject.protocol;
        if (!urlObject.protocol.endsWith(":")) result += ":";
    }
    if (urlObject.slashes || urlObject.host || urlObject.hostname) {
        result += "//";
        if (urlObject.auth) result += `${urlObject.auth}@`;
        if (urlObject.host) {
            result += urlObject.host;
        } else if (urlObject.hostname) {
            result += urlObject.hostname;
            if (urlObject.port) result += `:${urlObject.port}`;
        }
    }
    if (urlObject.pathname) result += urlObject.pathname;
    if (urlObject.path && !urlObject.pathname) result += urlObject.path;
    if (urlObject.query) {
        if (typeof urlObject.query === "string") {
            result += `?${urlObject.query}`;
        } else if (typeof urlObject.query === "object") {
            const qs = new URLSearchParams(urlObject.query).toString();
            if (qs) result += `?${qs}`;
        }
    } else if (urlObject.search) {
        result += urlObject.search.startsWith("?") ? urlObject.search : `?${urlObject.search}`;
    }
    if (urlObject.hash) result += urlObject.hash.startsWith("#") ? urlObject.hash : `#${urlObject.hash}`;
    return result;
}

export function resolve(from, to) {
    try {
        return new URL(to, from).href;
    } catch (e) {
        // `from` might itself be relative/incomplete; fall back to java.net.URI resolution.
        try {
            return String(JURI.create(from).resolve(to).toString());
        } catch (e2) {
            return to;
        }
    }
}

export function pathToFileURL(path) {
    const file = new JFile(String(path));
    return new URL(String(file.toURI().toString()));
}

export function fileURLToPath(url) {
    const href = url instanceof URL ? url.href : String(url);
    if (!href.startsWith("file:")) {
        throw new TypeError("The URL must be of scheme file");
    }
    const uri = JURI.create(href);
    return String(new JFile(uri).getPath());
}

const JIDN = Java.type("java.net.IDN");

export function domainToASCII(domain) {
    try {
        return String(JIDN.toASCII(String(domain)));
    } catch (e) {
        // Real Node's domainToASCII returns an empty string for an invalid domain rather
        // than throwing.
        return "";
    }
}

export function domainToUnicode(domain) {
    try {
        return String(JIDN.toUnicode(String(domain)));
    } catch (e) {
        return "";
    }
}

export default {
    URL,
    URLSearchParams,
    parse,
    format,
    resolve,
    pathToFileURL,
    fileURLToPath,
    domainToASCII,
    domainToUnicode,
};

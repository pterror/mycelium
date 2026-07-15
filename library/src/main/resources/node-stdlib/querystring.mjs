// Minimal Node.js-compatible `querystring` module, served by Mycelium as the synthetic
// core module resolved for `import ... from "querystring"` / `"node:querystring"`. Pure
// JS — no Java interop needed. Distinct from (and predates) `url.mjs`'s WHATWG
// `URLSearchParams`: this module preserves duplicate keys as arrays (matching Node's own
// `querystring.parse` behavior) rather than URLSearchParams's flat repeated-entry model,
// and supports custom separator/equals characters.

// Node's `querystring.escape`/`.unescape` differ subtly from `encodeURIComponent`/
// `decodeURIComponent`: escape additionally turns spaces into `%20` (encodeURIComponent
// already does that) but leaves a small set of characters unescaped that
// encodeURIComponent would otherwise leave alone anyway, so — for the common case — they
// coincide closely enough that delegating to encodeURIComponent/decodeURIComponent (with a
// try/catch fallback for unescape, matching Node's own tolerance of malformed sequences)
// is a reasonable, dependency-free approximation.
export function escape(str) {
    return encodeURIComponent(String(str));
}

export function unescape(str) {
    try {
        return decodeURIComponent(String(str));
    } catch (e) {
        return String(str);
    }
}

export function parse(str, sep, eq, options) {
    const separator = sep || "&";
    const equals = eq || "=";
    const result = Object.create(null);
    const input = str === undefined || str === null ? "" : String(str);
    if (input === "") return result;

    const maxKeys = options && typeof options.maxKeys === "number" ? options.maxKeys : 1000;
    const decode = (options && typeof options.decodeURIComponent === "function")
        ? options.decodeURIComponent
        : unescape;

    let pairs = input.split(separator);
    if (maxKeys > 0 && pairs.length > maxKeys) pairs = pairs.slice(0, maxKeys);

    for (const pair of pairs) {
        if (pair === "") continue;
        const eqIdx = pair.indexOf(equals);
        let key;
        let value;
        if (eqIdx === -1) {
            key = pair;
            value = "";
        } else {
            key = pair.slice(0, eqIdx);
            value = pair.slice(eqIdx + equals.length);
        }
        key = decode(key.replace(/\+/g, " "));
        value = decode(value.replace(/\+/g, " "));

        if (Object.prototype.hasOwnProperty.call(result, key)) {
            const existing = result[key];
            if (Array.isArray(existing)) existing.push(value);
            else result[key] = [existing, value];
        } else {
            result[key] = value;
        }
    }
    return result;
}

export function stringify(obj, sep, eq, options) {
    const separator = sep || "&";
    const equals = eq || "=";
    if (obj === undefined || obj === null) return "";

    const encode = (options && typeof options.encodeURIComponent === "function")
        ? options.encodeURIComponent
        : escape;

    const parts = [];
    for (const key of Object.keys(obj)) {
        const value = obj[key];
        const encodedKey = encode(String(key));
        if (Array.isArray(value)) {
            for (const item of value) {
                parts.push(`${encodedKey}${equals}${encode(stringifyValue(item))}`);
            }
        } else {
            parts.push(`${encodedKey}${equals}${encode(stringifyValue(value))}`);
        }
    }
    return parts.join(separator);
}

function stringifyValue(value) {
    if (value === undefined || value === null) return "";
    if (typeof value === "string") return value;
    if (typeof value === "number" || typeof value === "boolean") return String(value);
    return "";
}

export const encode = stringify;
export const decode = parse;

export default { escape, unescape, parse, stringify, encode, decode };

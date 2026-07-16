// Minimal Node.js-compatible `path/posix` module, served by Mycelium as the synthetic
// core module resolved for `import ... from "path/posix"` / `"node:path/posix"`. In real
// Node this is the exact same object as `require("path").posix` — re-exported here from
// path.mjs's top-level (POSIX-semantics) exports rather than duplicated, same precedent as
// fs-promises.mjs importing straight from fs.mjs.
import {
    sep,
    delimiter,
    isAbsolute,
    normalize,
    join,
    resolve,
    relative,
    toNamespacedPath,
    dirname,
    basename,
    extname,
    format,
    parse,
    posix,
    win32,
} from "path";

export const _makeLong = toNamespacedPath;

export {
    sep,
    delimiter,
    isAbsolute,
    normalize,
    join,
    resolve,
    relative,
    toNamespacedPath,
    dirname,
    basename,
    extname,
    format,
    parse,
    posix,
    win32,
};

export default posix;

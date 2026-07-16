// Minimal Node.js-compatible `path/win32` module, served by Mycelium as the synthetic
// core module resolved for `import ... from "path/win32"` / `"node:path/win32"`. In real
// Node this is the exact same object as `require("path").win32` — re-exported here from
// path.mjs's `win32` export (a real, non-stubbed Windows path implementation — see
// path.mjs's "win32" section) rather than duplicated, same precedent as path-posix.mjs.
import { win32 } from "path";

export const sep = win32.sep;
export const delimiter = win32.delimiter;
export const isAbsolute = win32.isAbsolute;
export const normalize = win32.normalize;
export const join = win32.join;
export const resolve = win32.resolve;
export const relative = win32.relative;
export const toNamespacedPath = win32.toNamespacedPath;
export const dirname = win32.dirname;
export const basename = win32.basename;
export const extname = win32.extname;
export const format = win32.format;
export const parse = win32.parse;
export const posix = win32.posix;
export const _makeLong = win32._makeLong;
export { win32 };

export default win32;

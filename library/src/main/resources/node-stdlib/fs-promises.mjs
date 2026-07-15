// Minimal Node.js-compatible `fs/promises` module, served by Mycelium as the synthetic
// core module resolved for `import ... from "fs/promises"` / `"node:fs/promises"`.
//
// Node's `require('fs/promises')` returns the promises-only API directly (not the full
// `fs` module namespace) — this re-exports the same `promises` namespace `fs.mjs` already
// builds (see fs.mjs's `promises` export), both as named exports (for `import { readFile }
// from "fs/promises"`) and as the default export (for `import fsp from "fs/promises"` /
// CJS interop via requireEsmModule, which hands back `default` verbatim when present).
import { constants, promises } from "fs";

export const access = promises.access;
export const copyFile = promises.copyFile;
export const mkdir = promises.mkdir;
export const readdir = promises.readdir;
export const readlink = promises.readlink;
export const symlink = promises.symlink;
export const lstat = promises.lstat;
export const stat = promises.stat;
export const unlink = promises.unlink;
export const chmod = promises.chmod;
export const realpath = promises.realpath;
export const rename = promises.rename;
export const rm = promises.rm;
export const rmdir = promises.rmdir;
export const writeFile = promises.writeFile;
export const appendFile = promises.appendFile;
export const readFile = promises.readFile;

export { constants };

export default {
    constants,
    ...promises,
};

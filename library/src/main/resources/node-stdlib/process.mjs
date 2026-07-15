// Minimal Node.js-compatible `process` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "process"` / `"node:process"`. Just re-exports the
// `process` global that Mycelium.kt injects onto globalThis before any guest code runs
// (see PROCESS_INIT_SCRIPT), so this file has no logic of its own — it exists only so
// `import process from "process"` / `import { env } from "node:process"` work the same
// way they do against the global.

const process = globalThis.process;

export default process;
export const env = process.env;
export const argv = process.argv;
export const cwd = process.cwd;
export const chdir = process.chdir;
export const exit = process.exit;
export const platform = process.platform;
export const arch = process.arch;
export const version = process.version;
export const versions = process.versions;
export const pid = process.pid;
export const ppid = process.ppid;
export const stdout = process.stdout;
export const stderr = process.stderr;
export const stdin = process.stdin;
export const hrtime = process.hrtime;
export const nextTick = process.nextTick;
export const emitWarning = process.emitWarning;
export const on = process.on;
export const once = process.once;
export const removeListener = process.removeListener;

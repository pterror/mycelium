// Minimal Node.js-compatible `console` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "console"` / `"node:console"`. `console` is usually
// consumed as the global GraalJS/Mycelium already provides (see Mycelium.kt's console
// bootstrap), but Node also lets it be `require()`d/imported explicitly — this just
// re-exports that same global object rather than building a second, divergent one, so
// `console.log(...)` and `require('console').log(...)` always share identical state (e.g.
// `console.count()`'s per-label counters).
//
// Node's `console` module additionally exports a `Console` *class* (for constructing
// independent console instances bound to custom output streams via `new
// console.Console(stdout, stderr)`) — not implemented here since Mycelium's global
// `console` has no such constructor to re-export and no consumer needs a second
// instantiable console yet; `globalThis.console.Console` is left `undefined` same as it
// would be if real Node's global console object were missing it.
const globalConsole = globalThis.console;

export const log = globalConsole.log.bind(globalConsole);
export const info = globalConsole.info ? globalConsole.info.bind(globalConsole) : log;
export const debug = globalConsole.debug ? globalConsole.debug.bind(globalConsole) : log;
export const warn = globalConsole.warn ? globalConsole.warn.bind(globalConsole) : log;
export const error = globalConsole.error ? globalConsole.error.bind(globalConsole) : log;
export const trace = globalConsole.trace ? globalConsole.trace.bind(globalConsole) : log;
export const dir = globalConsole.dir ? globalConsole.dir.bind(globalConsole) : log;
export const dirxml = globalConsole.dirxml ? globalConsole.dirxml.bind(globalConsole) : log;
export const table = globalConsole.table ? globalConsole.table.bind(globalConsole) : log;
export const assert = globalConsole.assert ? globalConsole.assert.bind(globalConsole) : function () {};
export const clear = globalConsole.clear ? globalConsole.clear.bind(globalConsole) : function () {};
export const count = globalConsole.count ? globalConsole.count.bind(globalConsole) : function () {};
export const countReset = globalConsole.countReset ? globalConsole.countReset.bind(globalConsole) : function () {};
export const group = globalConsole.group ? globalConsole.group.bind(globalConsole) : function () {};
export const groupCollapsed = globalConsole.groupCollapsed
    ? globalConsole.groupCollapsed.bind(globalConsole)
    : function () {};
export const groupEnd = globalConsole.groupEnd ? globalConsole.groupEnd.bind(globalConsole) : function () {};
export const time = globalConsole.time ? globalConsole.time.bind(globalConsole) : function () {};
export const timeEnd = globalConsole.timeEnd ? globalConsole.timeEnd.bind(globalConsole) : function () {};
export const timeLog = globalConsole.timeLog ? globalConsole.timeLog.bind(globalConsole) : function () {};
export const timeStamp = globalConsole.timeStamp ? globalConsole.timeStamp.bind(globalConsole) : function () {};
export const profile = globalConsole.profile ? globalConsole.profile.bind(globalConsole) : function () {};
export const profileEnd = globalConsole.profileEnd ? globalConsole.profileEnd.bind(globalConsole) : function () {};
export const context = globalConsole.context ? globalConsole.context.bind(globalConsole) : undefined;
export const createTask = globalConsole.createTask ? globalConsole.createTask.bind(globalConsole) : undefined;
export const Console = globalConsole.Console;

export default globalConsole;

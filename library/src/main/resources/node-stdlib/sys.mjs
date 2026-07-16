// Deprecated `sys` module, served by Mycelium as the synthetic core module resolved for
// `import ... from "sys"` / `"node:sys"`. Real Node's `sys` module is just `util` under an
// old name (kept for backwards compatibility, deprecated since Node 0.x) — this does the
// same, re-exporting `util.mjs` wholesale rather than duplicating it.

export * from "util";
export { default } from "util";

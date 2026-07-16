// Minimal `node:sea` (Single Executable Application) module stub, served by Mycelium as
// the synthetic core module resolved for `import ... from "node:sea"` (`node:`-only, same
// as `node:test`/`node:sqlite`). Mycelium doesn't have a single-executable-application
// packaging story of its own (same call other embedded-JS runtimes like Bun/Deno have made
// — see the task notes this module was implemented from), so `isSea()` always reports
// false and the asset accessors always throw, matching real Node's behavior when called
// outside of a SEA.

export function isSea() {
    return false;
}

export function getAsset(key, encoding) {
    throw new Error(`mycelium: node:sea.getAsset('${key}') — not running inside a single executable application`);
}

export function getAssetAsBlob(key, options) {
    throw new Error(`mycelium: node:sea.getAssetAsBlob('${key}') — not running inside a single executable application`);
}

export function getRawAsset(key) {
    throw new Error(`mycelium: node:sea.getRawAsset('${key}') — not running inside a single executable application`);
}

export function getAssetKeys() {
    return [];
}

export default {
    isSea,
    getAsset,
    getAssetAsBlob,
    getRawAsset,
    getAssetKeys,
};

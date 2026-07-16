// Minimal Node.js-compatible `stream/promises` module, served by Mycelium as the
// synthetic core module resolved for `import ... from "stream/promises"` /
// `"node:stream/promises"`. Node's real module surface here is just two names —
// `pipeline` and `finished` — both promise-returning wrappers around the exact same
// callback-based implementations `stream.mjs` already has (imported below rather than
// duplicated, same precedent as fs-promises.mjs importing `fs`'s callback internals).
import { finished as finishedCb, pipeline as pipelineCb } from "stream";

export function finished(stream, options) {
    return new Promise((resolve, reject) => {
        finishedCb(stream, options, (err) => {
            if (err) reject(err);
            else resolve();
        });
    });
}

export function pipeline(...args) {
    // Node's promise-returning `pipeline` takes the same source/...transforms/destination
    // argument shape as the callback form, minus the trailing callback — the last arg here
    // is never a function (callers awaiting the returned Promise don't pass one), so no
    // callback-extraction step is needed before delegating to the callback implementation.
    return new Promise((resolve, reject) => {
        const result = pipelineCb(...args, (err) => {
            if (err) reject(err);
            else resolve(result);
        });
    });
}

export default { finished, pipeline };

// Minimal Node.js-compatible `readline/promises` module, served by Mycelium as the
// synthetic core module resolved for `import ... from "readline/promises"` /
// `"node:readline/promises"`. Same underlying line-reading machinery as `readline.mjs`
// (imported below via the "readline" specifier — see fs-promises.mjs for the precedent of
// one core module importing another this way) — the only difference from the callback API
// is that `question()` returns a Promise instead of taking a callback.

import {
    Interface as CallbackInterface,
    emitKeypressEvents, clearLine, clearScreenDown, cursorTo, moveCursor,
    _normalizeCreateInterfaceArgs as normalizeCreateInterfaceArgs,
} from "readline";

class Interface extends CallbackInterface {
    question(query, options) {
        return new Promise((resolve) => {
            super.question(query, options, resolve);
        });
    }
}

export function createInterface(...args) {
    const options = normalizeCreateInterfaceArgs(args);
    return new Interface(options);
}

export { Interface, emitKeypressEvents, clearLine, clearScreenDown, cursorTo, moveCursor };

export default { createInterface, Interface, emitKeypressEvents, clearLine, clearScreenDown, cursorTo, moveCursor };

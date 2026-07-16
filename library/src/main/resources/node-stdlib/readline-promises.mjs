// Minimal Node.js-compatible `readline/promises` module, served by Mycelium as the
// synthetic core module resolved for `import ... from "readline/promises"` /
// `"node:readline/promises"`. Same underlying line-reading machinery as `readline.mjs` —
// the promise-based `Interface` subclass (whose only difference from the callback API is
// that `question()` returns a Promise instead of taking a callback) is defined *inside*
// readline.mjs itself and just re-exported here under this module's expected names.
//
// This indirection (rather than defining the subclass here, at this module's own top
// level) is deliberate: see readline.mjs's "promises namespace" section for why defining
// it here instead forms an unresolvable ESM import cycle with readline.mjs.
import {
    _createPromiseInterface as createInterface,
    _PromiseInterface as Interface,
    emitKeypressEvents, clearLine, clearScreenDown, cursorTo, moveCursor,
} from "readline";

export { createInterface, Interface, emitKeypressEvents, clearLine, clearScreenDown, cursorTo, moveCursor };

export default { createInterface, Interface, emitKeypressEvents, clearLine, clearScreenDown, cursorTo, moveCursor };

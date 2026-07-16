// Minimal `node:test` module stub, served by Mycelium as the synthetic core module
// resolved for `import ... from "node:test"` (Node only exposes this under the `node:`
// prefix, never bare `test` — see NodeParityTest / CoreModuleRegistry for why only the
// prefixed form is registered here). This is a small but functional in-process test runner
// — `describe`/`it`/`test` genuinely register and (via `run`) execute callbacks, tracking
// pass/fail/skip counts — rather than a pure no-op stub, since that's cheap to provide and
// lets simple test files written against `node:test` actually execute under Mycelium. What's
// missing relative to real Node: TAP-format stdout reporting, `mock`/`snapshot` support
// (shape-only stubs below), parallelism, and CLI test-file discovery — those all revolve
// around Node's process/CLI integration, which Mycelium doesn't reproduce.

const rootSuite = { name: null, parent: null, hooks: { before: [], after: [], beforeEach: [], afterEach: [] }, tests: [] };
let currentSuite = rootSuite;

function makeContext(name) {
    return {
        name,
        skip(reason) {
            throw new SkipSignal(reason);
        },
        todo(reason) {
            // marks as todo; doesn't throw, real Node still runs it.
        },
        diagnostic() {},
        test: test,
    };
}

class SkipSignal {
    constructor(reason) {
        this.reason = reason;
    }
}

function registerTest(name, options, fn, kind) {
    let opts = options;
    let callback = fn;
    if (typeof options === "function") {
        callback = options;
        opts = {};
    }
    opts = opts || {};
    const entry = {
        name: name || "<anonymous>",
        fn: callback,
        skip: !!opts.skip,
        todo: !!opts.todo,
        only: !!opts.only,
        kind,
    };
    currentSuite.tests.push(entry);
    return entry;
}

export function describe(name, options, fn) {
    let opts = options;
    let body = fn;
    if (typeof options === "function") {
        body = options;
        opts = {};
    }
    const suite = {
        name,
        parent: currentSuite,
        hooks: { before: [], after: [], beforeEach: [], afterEach: [] },
        tests: [],
        skip: !!(opts && opts.skip),
    };
    currentSuite.tests.push({ name, suite, kind: "suite" });
    const previous = currentSuite;
    currentSuite = suite;
    try {
        if (typeof body === "function") body();
    } finally {
        currentSuite = previous;
    }
}

export const suite = describe;

export function it(name, options, fn) {
    return registerTest(name, options, fn, "test");
}

export function test(name, options, fn) {
    return registerTest(name, options, fn, "test");
}

test.skip = (name, options, fn) => registerTest(name, options, fn, "test-marked-skip") && undefined;
it.skip = test.skip;
describe.skip = (name, options, fn) => describe(name, options, fn);

export function before(fn) {
    currentSuite.hooks.before.push(fn);
}

export function after(fn) {
    currentSuite.hooks.after.push(fn);
}

export function beforeEach(fn) {
    currentSuite.hooks.beforeEach.push(fn);
}

export function afterEach(fn) {
    currentSuite.hooks.afterEach.push(fn);
}

export function skip(name, options, fn) {
    const entry = registerTest(name, options, fn, "test");
    entry.skip = true;
    return entry;
}

export function todo(name, options, fn) {
    const entry = registerTest(name, options, fn, "test");
    entry.todo = true;
    return entry;
}

export function only(name, options, fn) {
    const entry = registerTest(name, options, fn, "test");
    entry.only = true;
    return entry;
}

// Executes every test registered (via `describe`/`it`/`test`) so far, depth-first,
// returning a summary. Real Node's `run()` takes file/glob options and streams a TAP
// reporter; this simplified version just walks the in-memory suite tree built by whatever
// module-level `describe`/`it` calls already ran.
export async function run(options) {
    const summary = { pass: 0, fail: 0, skip: 0, todo: 0, tests: [] };

    async function runSuite(suite) {
        for (const hook of suite.hooks.before) await hook();
        for (const entry of suite.tests) {
            if (entry.kind === "suite") {
                await runSuite(entry.suite);
                continue;
            }
            for (const hook of suite.hooks.beforeEach) await hook();
            const record = { name: entry.name, suite: suite.name };
            if (entry.skip) {
                summary.skip++;
                record.status = "skip";
            } else if (entry.todo) {
                summary.todo++;
                record.status = "todo";
            } else {
                try {
                    await entry.fn(makeContext(entry.name));
                    summary.pass++;
                    record.status = "pass";
                } catch (e) {
                    if (e instanceof SkipSignal) {
                        summary.skip++;
                        record.status = "skip";
                    } else {
                        summary.fail++;
                        record.status = "fail";
                        record.error = e;
                    }
                }
            }
            summary.tests.push(record);
            for (const hook of suite.hooks.afterEach) await hook();
        }
        for (const hook of suite.hooks.after) await hook();
    }

    await runSuite(rootSuite);
    return summary;
}

export const mock = {
    fn(original) {
        let impl = typeof original === "function" ? original : () => {};
        const mockFn = function (...args) {
            mockFn.mock.calls.push({ arguments: args });
            return impl.apply(this, args);
        };
        mockFn.mock = { calls: [], callCount: () => mockFn.mock.calls.length };
        mockFn.mock.mockImplementation = (fn) => {
            impl = fn;
        };
        return mockFn;
    },
    method(object, methodName, implementation) {
        const original = object[methodName];
        const mockFn = mock.fn(implementation || original);
        object[methodName] = mockFn;
        mockFn.mock.restore = () => {
            object[methodName] = original;
        };
        return mockFn;
    },
    getter() {
        throw new Error("mycelium: mock.getter() is not supported");
    },
    setter() {
        throw new Error("mycelium: mock.setter() is not supported");
    },
    reset() {},
    restoreAll() {},
    timers: {
        enable() {},
        reset() {},
        tick() {},
    },
};

export const snapshot = {
    setDefaultSnapshotSerializers() {},
    setResolveSnapshotPath() {},
};

export const assert = {
    snapshot() {
        throw new Error("mycelium: test.assert.snapshot() is not supported");
    },
    fileSnapshot() {
        throw new Error("mycelium: test.assert.fileSnapshot() is not supported");
    },
};

export const expectFailure = (fn) => {
    // no-op marker; real Node uses this internally.
    return fn;
};

export default test;

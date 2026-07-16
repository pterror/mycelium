// Minimal `node:test/reporters` module stub, served by Mycelium as the synthetic core
// module resolved for `import ... from "node:test/reporters"` (also `node:`-only, same as
// `node:test` itself). Real Node's reporters are transform streams that consume the test
// runner's internal TestEvent stream and produce formatted output (TAP, JUnit XML, etc.);
// Mycelium's own `node:test` doesn't emit such an event stream, so these are async
// generator function stand-ins with the right shape — each accepts a `source` async
// iterable and yields nothing, rather than genuinely formatting test results.

async function* passthroughReporter(source) {
    for await (const _event of source) {
        // no-op — real reporters format `_event` into TAP/JUnit/etc. output here.
    }
}

export const tap = passthroughReporter;
export const spec = passthroughReporter;
export const dot = passthroughReporter;
export const junit = passthroughReporter;
export const lcov = passthroughReporter;

export default {
    tap,
    spec,
    dot,
    junit,
    lcov,
};

# Mycelium — Project State & Open Threads

*Updated 2026-07-15. This file is the project's institutional memory — written to survive context compaction. Treat as reliable background, but verify against code before relying on specifics that could have drifted.*

## Vision

Mycelium's purpose: make every language's package ecosystem available to every other language. npm + PyPI + RubyGems + crates.io, all importable from anywhere, cross-language, in one shared GraalVM polyglot `Context`. Not just "write in the best language" — unlock every package registry at once.

## Architecture decisions (with rationale)

- **CJS support implemented as a JS-side wrapper**, not GraalJS's built-in loader: `(function(exports, require, module, __filename, __dirname) { ... })`, with the module cache populated before execution to support circular `require()`s (mirrors Node's own resolution order).
- **GraalJS's `js.commonjs-require` / `js.commonjs-require-cwd` options tried and rejected.** Enabling them swaps GraalJS's ES module loader for `NpmCompatibleESModuleLoader`, which hard-rejects any import specifier extension outside a hardcoded whitelist (`js`/`mjs`/`cjs`/`json`/`node`) with `TypeError: Unsupported file extension` — before Mycelium's custom `InterceptingFileSystem` ever sees the read. Mycelium's entire cross-language-import mechanism (`.c`/`.rb`/`.rs`/`.ts`/wasm) depends on GraalJS handing "unrecognized" extensions to that FileSystem to shim, so the option can't be turned on globally without redesigning that mechanism (e.g. a second, CJS-only Context for a subtree — conflicts with the single shared Context/polyglot-bindings architecture used everywhere).
- **Forking GraalJS's CJS loader rejected** — too tightly coupled to internal Truffle APIs to maintain.
- **Embedding GraalVM's Node.js distribution rejected** — it's a forked C++ binary, not a Truffle language; can't be embedded in a JVM `Context`.
- **WASM loaded via GraalJS's `WebAssembly` JS API, not the raw Truffle "wasm" language.** `context.eval("wasm", ...)` exposes zero `memberKeys` — no way to read a compiled module's exports. Fix: read the compiled `.wasm` bytes, hand them to the JS context via `context.polyglotBindings` as a plain Java `ByteArray`, and evaluate JS that builds a `Uint8Array` and calls `new WebAssembly.Module(...)` / `new WebAssembly.Instance(...)`. Requires the `js.webassembly` context option (off by default). `instance.exports` is then an ordinary JS object, reusing the same shim-generation path as LLVM exports.
- **esbuild used for TypeScript transpilation only, not for CJS.** Bundling/transpiling CJS would break Node's circular-dependency semantics; esbuild is invoked transform-only (`bundle = false`) purely to strip TS types/syntax, leaving `import`/`export` specifiers untouched so Mycelium's own resolver still handles them.
- **Node stdlib implemented as synthetic core modules backed by Java interop**, cross-referencing Node/Deno/Bun source for exact semantics (must mirror Node 1:1, not just approximate it).
- **Cross-language binding namespacing**: polyglot bindings keyed as `"modulepath:exportname"` to avoid collisions between languages/modules sharing one bindings object.

## Hard constraints / gotchas

- TruffleRuby is not published above 25.0.0 on Maven Central — this is the practical ceiling for GraalVM version bumps (see "GraalVM version" below).
- GraalPython upstream bug blocks Python support entirely — see dedicated section below.
- Maven Central's search API is stale/unreliable for checking whether a version exists — always verify against the raw repo (e.g. `repo1.maven.org`), not the search UI/API.
- GraalJS auto-unboxes host `String`s to JS primitives — use `Charset#encode`, not `String.getBytes`, when producing bytes from JS-originated strings.
- GraalJS ships no `URL`/`URLSearchParams` globals — had to be implemented in pure JS.
- GraalVM `Context` is single-threaded for JS — the httpserver dispatch thread cannot share a `Context` across threads.
- `native-image` peaks at ~17GB RAM during build; capped via `-J-Xmx8g`; produces an ~887MB binary.
- Rust compilation needs `--crate-type=lib` or `rustc` fails with "main function not found".
- Go→LLVM is not viable — stock Go does not emit LLVM bitcode via any single-command pipeline. Only Go→WASM via tinygo works (see Go section).

## Bun/Deno Compatibility Strategy

### Runtime Detection (per-package)
Check lockfiles/config in order of broadest support — short circuit on first match:
1. Node: `package-lock.json`, `pnpm-lock.yaml`, `yarn.lock` → present as Node
2. Bun: `bun.lockb`, `bunfig.toml` → present as Bun
3. Deno: `deno.lock`, `deno.json`/`deno.jsonc`, URL imports → present as Deno

Detection order IS the priority order. Never check Bun/Deno signals before Node signals — a project with both `bunfig.toml` and `pnpm-lock.yaml` is Node, not Bun.

### Runtime Identity
- Present as exactly ONE runtime per package — never expose multiple runtime globals simultaneously
- Cross-runtime packages (code that detects and supports multiple runtimes) always fall through to their Node path, which is the most tested
- Bun/Deno APIs available as importable modules (`import ... from "bun"`, etc.) but runtime globals (`Bun`, `Deno`) only exposed when detected as that runtime
- The Trojan horse is Node compat. The payload is polyglot.

### Why Not "Be All Runtimes"
If all runtime globals are defined simultaneously, a package with inconsistent detection order across files (checks Bun first in one file, Deno first in another) enters a mixed-codepath state that never exists on any real runtime and was never tested by the package author.

## npm compatibility status

**Round 1 (5/5 pass, unpinned versions):** lodash, minimist, chalk, uuid, dotenv.

**Round 2 (2/5 pass):**
- `semver` — WORKS (plain CJS, same `"main"` → CJS-to-ESM path as lodash).
- `ms` — FAILS. `"main": "./index"` has no file extension; `InterceptingFileSystem.resolvePackageEntryPoint` resolves it literally with no extension-probing fallback (real Node tries `./index`, `./index.js`, `./index.json`, `./index.node`, `./index/index.js`).
- `debug` — FAILS. `require("tty")`; `tty` is not among Mycelium's ~16 synthetic core modules. Common transitive dep (pulled in by express via body-parser), so this one gap blocks a lot of the ecosystem.
- `axios` — FAILS. `exports["."]` is a *nested* conditions object where even the `"default"` condition's value is itself another `{require, default}` object. `ENTRY_POINT_RESOLVER` only descends one level before unconditionally `.asString()`-ing the result. Proper Node conditional-exports resolution is recursive; Mycelium's isn't. (The underlying `http`/`https` core-module shims are a real, if minimal, implementation — this is purely a resolution-layer gap.)
- `express` — FAILS, for the same reason as `debug` (transitively pulls it in via body-parser) — not a distinct express-specific gap.

**Chalk regression (pre-existing, confirmed via `git stash` to predate this session):** `chalkResolvesSubpathImports` fails against whatever chalk version npm currently resolves as "latest" (5.6.2 as of last check) — `#ansi-styles` fails with "no matching imports entry found", i.e. the fallback path (`sourceDirectoryStack.get().firstOrNull() ?: process.cwd()`) is hit instead of chalk's real `source/` directory. Suggests the source-directory `ThreadLocal` stack (pushed/popped around `newByteChannel` reads in `InterceptingFileSystem.kt`) is empty by the time GraalJS resolves the import. This is a flake risk for the whole `NpmCompatibilityTest` suite, since no test there pins package versions.

All findings above are cataloged as tests in `NpmCompatibilityTest.kt` (see its class doc comment for full detail) — **cataloged, not yet fixed**, per the project's "find gaps, don't fix them yet" sweep approach.

### Known npm gaps to fix (ordered simplest → hardest)
1. Extensionless `"main"` field resolution — probe `.js`/`.json`/`index.js` etc. like Node does.
2. Add a `tty` core module (a stub is fine — just needs `isatty()` and a `WriteStream`).
3. Recursive/nested conditional-exports resolution in `package.json`'s `"exports"` field.
4. Chalk subpath-import regression (`sourceDirectoryStack` empty for subpath imports).

**Round 3 (ws, commander, yargs, glob, pino, zod, nanoid, inquirer) — quick-win fixes landed this session:**
- `ws`, `zod`, `nanoid` — already WORKED (no gaps).
- `commander` — was FAILING on `util.mjs` missing `stripVTControlCharacters`. Fixed: added to `util.mjs`, now WORKS end-to-end.
- `yargs` — was FAILING on escalade's `exports["./sync"]` being an *array* of fallback candidates (`RESOLVE_CONDITIONS_FN` didn't recurse into arrays). Fixed: `resolveConditions` in `InterceptingFileSystem.kt` now tries each array element in order. Still fails past that point — yargs's platform shim also needs `node:module` (`builtinModules`), not yet registered.
- `glob` — was FAILING on missing `fs/promises`/`node:fs/promises` core module. Fixed: added `fs-promises.mjs`, registered in `coreModules`. Still fails past that point — minimatch/path-scurry construct `new URL(...)` at module scope, and `URL` isn't a global in Mycelium's guest environment (only importable via the `url` module).
- `pino` — was FAILING on missing `diagnostics_channel`/`node:diagnostics_channel`. Fixed: added a real (not just stubbed) pub/sub implementation in `diagnostics_channel.mjs`. Still fails past that point — pino's thread-transport also needs `worker_threads`, not yet registered.
- `inquirer` — was FAILING on missing `async_hooks`/`node:async_hooks`. Fixed: added `async_hooks.mjs` with a genuinely-working (synchronous-only) `AsyncLocalStorage` plus inert `createHook`/`AsyncResource` stubs. Still fails past that point — inquirer also needs `node:readline`, not yet registered.
- Also fixed while in the area (not gating any round-3 package directly, but needed for real-world npm compat): `util.mjs` gained `debuglog`/`debug`, `parseArgs`, `promisify.custom`, `styleText`, `formatWithOptions`, `getSystemErrorName`, `toUSVString`.
- `assert.strict.rejects`/`assert.strict.doesNotReject` — checked, were already correctly implemented (no bug found there).

### Next npm gaps to fix (ordered simplest → hardest)
1. `node:module` core module (at least `builtinModules`) — unblocks yargs.
2. `worker_threads` core module (stub acceptable) — unblocks pino.
3. `readline` core module — unblocks inquirer.
4. Expose `URL`/`URLSearchParams` as globals (not just importable from `url`) — unblocks glob/minimatch/path-scurry.

### Missing Node stdlib modules
`net`, `worker_threads`, `readline`, `module`, `dns`, `dgram`, `cluster`. (`child_process`, `tty`, `diagnostics_channel`, `async_hooks`, `fs/promises` are now implemented.) Also: console output could be improved beyond GraalJS's defaults.

## Language support status

### JavaScript / TypeScript — working
Core language. TypeScript (`.ts`/`.mts`, routed to `js` via `JsSupport.extensions`) transpiles through `TsCompiler` (`library/src/main/kotlin/org/mycelium/library/TsCompiler.kt`) using `io.mvnpm:esbuild-java:1.5.1`. `InterceptingFileSystem.newByteChannel` intercepts `.ts`/`.mts` reads before any other routing and hands GraalJS the transpiled JS. Output cached on disk by source-content hash (`~/.cache/mycelium/ts`, same pattern as `Compiler.compile`'s LLVM cache) since esbuild spawns a subprocess and downloads a platform binary on first use (network required once; ~5s cold, near-instant cached). esbuild hashes output filenames unpredictably, so `TsCompiler` picks the lone `.js` file in the esbuild dist dir (safe since each transpile is a single non-bundled entry point). Covered by `jsImportsTypeScript` in `MyceliumIntegrationTest.kt`.

### Go — working (WASM only)
`TinyGoSupport` (Go → WASM via `tinygo build -target=wasi`, languageId = `"wasm"`) is wired into Mycelium's default language list. `tinygo` is in `flake.nix`'s devShell. Go→LLVM was investigated and ruled out (see doc comment on `TinyGoSupport` in `Languages.kt`). `LanguageSupport` has an `outputExtension` property so the compiled-import branch keys off it instead of hardcoding `"llvm"`; Go overrides it to `"wasm"`.

The first end-to-end test (`jsImportsGoWasm`) initially failed because the raw Truffle "wasm" language exposes zero `memberKeys` for module exports — fixed by routing through GraalJS's `WebAssembly` API instead (see architecture section above). tinygo's `-target=wasi` output imports several WASI preview1 functions even for WASI-oblivious code (runtime init pulls them in); `wasi_snapshot_preview1.*` is stubbed with no-ops in the instantiation snippet.

All `:library:test` tests pass including `jsImportsGoWasm` (tinygo 0.41.1, GraalVM CE 25.0.2 in devShell, Maven artifacts pinned at 25.0.0). Open/unexplored: `gccgo` as an alternative Go→LLVM path was never investigated.

### Rust — working
`--crate-type=lib` fix applied; compiles end-to-end. `rustc` is now in `flake.nix`'s devShell (added since last update), so `jsImportsRust` runs by default rather than skipping.

### Ruby, C — working
Cross-language imports for `.rb`/`.c` function via the same `InterceptingFileSystem` shimming mechanism as the other compiled/foreign languages.

### Python — blocked upstream
GraalPython crashes with `SystemError: Unable to set dict of object of type foreign` when evaluating Python through a named `Source` with a custom `FileSystem` attached. Confirmed on both 24.1.1 and 25.0.0 — a GraalPython bug, not a Mycelium issue (disappears if either the custom FileSystem or the named Source is removed). Separately fixed a Mycelium-side bug where it was hard-failing on GraalPython's internal `<frozen importlib._bootstrap>` synthetic identifiers — that fix is in the tree independent of the blocker. As of the 25.0.0 bump, the underlying crash now manifests as a host `NullPointerException` instead of an opaque `HostException` wrapper, but is otherwise unresolved. Repro tests in `PythonInteropExperimentTest.kt`. Next step: try a newer GraalVM/GraalPython release, or file a minimal repro upstream.

## GraalVM version: pinned at 25.0.0 (bump to 25.1.3 attempted, blocked)

Attempted bumping `library/build.gradle.kts`'s `graalVersion` from 24.2.1 to 25.1.3. `truffle-api` and `polyglot:{polyglot,js,python,java,wasm,llvm-native}` are published at 25.1.3 on Maven Central, but `polyglot:ruby`, `org.graalvm.ruby:ruby`, and the old `polyglot:llvm` coordinate are not — they stop at 25.0.0/25.0.1. Mixing `truffle-api` 25.1.3 with `truffleruby` 25.0.0 fails hard at runtime (`ClassCastException: Invalid inline context node passed to an inlined field`, deep in Truffle DSL-generated inline-cache code) — an ABI break between Truffle DSL versions, not just a version mismatch.

Landed on **25.0.0 uniformly** (up from 24.2.1) — the highest version where every required GraalVM Maven artifact (including Ruby/LLVM) is simultaneously available. Two integration tests needed updates for behavior changes between 24.2.1 and 25.0.0: `MyceliumIntegrationTest.nonexistentBareSpecifierGivesFriendlyError` (host-exception shape reverted to pre-24.2.1 form) and `PythonInteropExperimentTest.mycelium_runFile_jsPolyglotEvalPython_noLongerHardFailsOnFrozenBootstrap` (same underlying GraalPython bug, now a host NPE instead of an opaque HostException).

All 60 `:library:test` tests pass and `:app:nativeCompile` succeeds at 25.0.0. `flake.nix` uses nixpkgs' stock `graalvmPackages.graalvm-ce`, currently 25.0.2 — close enough to Maven's 25.0.0 that native-image works without incident.

**Revisit when:** upstream publishes matching 25.1.x Ruby/LLVM Maven artifacts, or confirms they're dropped for good — `org.graalvm.polyglot:ruby` / `org.graalvm.ruby:ruby` is already marked deprecated upstream ("retained only for Maven compatibility").

## Open threads

### Package manager auto-install
npm bare-specifier resolution works against an existing `node_modules` (walks parent dirs, reads `package.json` exports/main), but there's no auto-install — users must `npm install` packages themselves first. Open design question: eager (install on first import — magical but unpredictable) vs. declarative (manifest file — explicit but adds friction). Neither pip nor gem resolution exists yet at all (see below).

### pip / gem resolution
npm's `node_modules` resolution is implemented; analogous resolution for Python (pip/site-packages) and Ruby (gem) packages is not. Each ecosystem has different conventions for "already installed" package layout — worth investigating those conventions before building resolvers. Blocked in practice for Python by the GraalPython upstream bug above, but the resolver work itself is independent and could proceed for Ruby.

### Distribution form
CLI exists (`mycelium run/version/help`) but is only invoked via `./gradlew :app:run --args="..."`. `native-image` produces a standalone binary but at ~887MB; `installDist` is ~709MB but needs a JRE. User wants a genuinely self-contained binary (not Nix-only) — neither current option is fully satisfying. Open question: is GraalVM's weight simply acceptable for end users, or is a lighter packaging path worth exploring?

## User preferences (for future sessions)

- "Simplest first, sweep everything, note blockers, don't get stuck" — the general working style for compatibility sweeps.
- Node stdlib compatibility is CORE to the project, not a nice-to-have.
- Must mirror Node semantics 1:1, not approximate them.
- End goal is a self-contained binary, not a Nix-only distribution.
- User fact-checks claims — verify before relaying anything as confirmed.
- Prefers architecturally sound solutions over expedient ones (e.g. rejecting `js.commonjs-require` rather than papering over its incompatibility).

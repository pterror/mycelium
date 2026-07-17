# Mycelium — Project State & Open Threads

*Updated 2026-07-17 (session handoff #6: mod authoring polish, integer division mod, null-safe
computed access. This session extended the mod-loader infrastructure built in #5: null-safe mod
now covers bracket/computed property access (`obj[x]` on null/undefined) via ReadElementNode
patching; integer-division mod landed as the fourth working mod (C-style `/` truncation,
patches JSDivideNode); mod authoring documentation (`docs/mod-authoring.md`) was written to guide
future mod authors; and `PatchDsl.recordPatch()` was made public for mods that drop to raw
classNode iteration. Discovered daemon gotcha: mods don't take effect if a stale daemon is
running without the javaagent — kill it first or disable it for mod testing.* Previous handoff
(session handoff #5: GraalVM Insight integration, mod loader, daemon reliability. Pivoted from
Node-stdlib/compat sweeps toward JVM-level extensibility: `--insight=<script>` dynamic
instrumentation landed; a full mod loader (discovery, descriptors, transformers, priority
dispatch, dependency resolution, version checking, a helper DSL) was built and shipped with three
working example mods; the daemon gained a version handshake and a bypass path for JVM-level
features; hybrid cross-language metaprogramming was explored experimentally (findings only, not
committed). Previous handoff #4: stub audit + Truffle-embedding-JAR visibility fix — silent-
wrong-answer stubs replaced with real implementations, `polyglot`/`truffle-api` exposed as `api`
deps for `Engine` visibility, stream/path submodules and Scala.js auto-download confirmed landed.
Previous handoffs (#1-#3): daemon architecture (Unix socket, shared `Engine`, per-request
isolation), inter-core-module import fix, native-image removal, distribution architecture
resolution (JVM mode over native-image), Node stdlib completion (dgram/cluster, 14 niche
modules), compile-to-JS growth to 12 languages. All committed and in git history — condensed
out of this file per the "remove RESOLVED cruft" rule. This file is the project's institutional
memory — written to survive context compaction. Treat as reliable background, but verify against
code before relying on specifics that could have drifted.*

## Native-image build removed — reconfirmed this session (see also below, same item)

Already landed earlier in the 2026-07-17 session (see next section) and reconfirmed still true:
`org.graalvm.buildtools.native`, the `graalvmNative { }` block, and native-image classpath
wiring are gone from `app/build.gradle.kts`. `./gradlew :app:run`/`:app:jvmRun` remain the only
run paths. No further changes needed this pass beyond the doc updates in this file.

## Event loop gated to JS only — RESOLVED this session

`Mycelium.kt`'s `runEventLoop()` (called after evaluating a top-level source at line ~427) now
only drains timers/microtasks when `language == "js"` — previously it ran unconditionally
regardless of which language was actually evaluated, which was harmless for JS but wasteful
(and conceptually wrong) for every other GraalVM-native language (Ruby, Python when unblocked,
C/LLVM, etc.) that has no JS-style event loop of its own. Compile-to-JS languages (TypeScript,
CoffeeScript, Gleam, Dart, etc. — see "Compile-to-JS languages" below) are unaffected: their
compiled output is executed *as* JS through `JsSupport`, so they still drain the event loop via
that delegation path, not by satisfying `language == "js"` directly at the top-level call site.

## Native-image removed (this session, follow-up to the JVM-mode decision below)

The `org.graalvm.buildtools.native` plugin, the `graalvmNative { }` block, and the
native-image-specific classpath wiring in `app/build.gradle.kts` have been deleted outright.
This was dead weight: incompatible with the per-language-JAR/on-demand-loading architecture
(native-image can't dynamically load a Truffle language JAR at runtime — see "Native-image has
no dylib/shared-library support" in ROADMAP.md), cost ~17GB RAM to build, and produced an
~887MB binary. `./gradlew :app:run` and `./gradlew :app:jvmRun` (JVM mode, `-Planguages=...`)
are now the only ways to run Mycelium — see "Architecture decisions" below for the split
language-pack Configurations `jvmRun` consumes. `library/build.gradle.kts` needed no dependency
changes (it never declared native-image-specific artifacts), just a doc-comment update to drop
the now-false claim that a native-image build path still existed.

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
- **`InterceptingFileSystem` split into 8 focused classes** (commit `1abf11e`, end of this
  session): it had grown to 1389 lines doing module resolution, CJS execution, shim
  generation, and compiler dispatch all in one place. GraalVM's `FileSystem` interface is
  confirmed to be the *only* officially-sanctioned interception point (no module-resolver
  hook, no `Source`-level interception exists), so the FileSystem-interception architecture
  itself is correct and was kept — only the monolith was split, pure refactor, no behavior
  change, all 216 tests still pass. New layout, all in
  `library/src/main/kotlin/org/mycelium/library/`:
  - `InterceptingFileSystem.kt` (515 lines) — thin `FileSystem` adapter + orchestration;
    still owns `sourceDirectoryStack` and `lastReadModuleDir` (the stateful bits).
  - `NodeModuleResolver.kt` (378 lines) — `node_modules` walk, `package.json`
    `exports`/`imports`/`main` resolution (`resolvePackageEntryPoint`, `resolveConditions`
    live here now, **not** in `InterceptingFileSystem.kt`).
  - `CjsRuntime.kt` (352 lines) — `require()`, module cache, CJS→ESM shim generation.
  - `Compiler.kt` (117 lines) — LLVM/WASM compilation + loading.
  - `CoreModuleRegistry.kt` (98 lines) — the Node-stdlib core-module map.
  - `PythonModuleResolver.kt` (50 lines) — `sys.path` resolution.
  - `RubyModuleResolver.kt` (46 lines) — `$LOAD_PATH` resolution.
  - `ShimGenerator.kt` (34 lines) — cross-language ESM shim generation.
  - **Any older note (including elsewhere in this file) that says "`InterceptingFileSystem.kt`" for entry-point/conditional-exports resolution logic predates this split.**
- **Content-addressable store (CAS) for package installs**: `PackageCache` now dedups at the
  per-file level (SHA-256 of file contents), not just per-archive — identical files shared
  across different package versions or even different ecosystems are stored once on disk and
  hardlinked out to each consumer, instead of once per extracted archive.
- **`HttpMetadataCache`**: ETag/conditional-GET (`If-None-Match`) caching layered onto every
  registry client (npm, RubyGems, PyPI, crates.io, Go proxy) — a metadata re-fetch (e.g.
  re-resolving the same version range on a later run) returns `304 Not Modified` instead of
  re-downloading unchanged JSON, shared by all five clients rather than implemented per-client.
- **`ToolResolver` gained two more `ToolSource` implementations**: `GitHubRelease` (downloads a
  compiler distributed as a GitHub release binary rather than an npm/registry package — used
  for Gleam) and `DartSdkDownload` (fetches the Dart SDK directly — used for Dart/dart2js).
  Both slot into the same composable chain (`ProjectLocalBin` → `SystemPath` →
  `CachedPackage` → `RegistryDownload` → `GitHubRelease` → `DartSdkDownload`) as the
  pre-existing sources, no special-casing needed in `CompileToJsDispatcher` itself.
- **DIP-compliant composition root**: `Mycelium` (the class, in `Mycelium.kt`) constructs every
  collaborator its object graph needs — one `PackageIndex` per ecosystem (`NpmRegistryClient`,
  `RubyGemsClient`, `PyPIClient`, `CratesIoClient`, `GoProxyClient`), each paired with its own
  `PackageInstaller`, plus `CompilerDownloader`/`CompilerRegistry`/`CompileToJsDispatcher` — in
  its own constructor body, and threads them down into whatever needs them, rather than any
  downstream class reaching for a concrete singleton itself. `CompileToJsDispatcher`/
  `CompilerDownloader`/`PackageInstaller` depend only on the `PackageIndex`/`CompilerRegistry`/
  `ToolResolver` interfaces and constructor params they're handed, never on a concrete
  `*Client` directly — so a consumer embedding Mycelium as a library (or a test) can substitute
  a fake registry/downloader/cache at exactly one point (`Mycelium`'s own constructor) without
  touching any class further down the graph. Same pattern repeats one level down:
  `CompileToJsDispatcher` takes `CompilerRegistry`/`CompilerDownloader`/`CompilerCache`/
  `bundledBackend`/`ProjectBuildRunner`/`ToolResolver` as constructor params, all defaulted for
  real callers but swappable in tests.
- **Languages split into per-language Gradle configurations, loaded on demand** (this
  session): the core runtime JAR set no longer bakes in any Truffle language — it's 22MB
  across 19 JARs with zero languages included. Each language is its own Gradle configuration
  (`js`, `ruby`, `python`, etc.) selected via `-Planguages=js,ruby,python`; GraalJS alone adds
  47MB across 8 JARs. Full polyglot (every language) is 709MB across 71 JARs — the same
  ballpark as before, but now that weight is opt-in per deployment rather than forced on every
  binary. This directly resolves the "887MB binary" distribution problem tracked in previous
  sessions' open threads (see "Distribution architecture" in Open threads, below, for the
  still-open question this doesn't resolve: startup/daemon strategy).
- **`./gradlew :app:jvmRun` runs Mycelium in JVM mode** — confirmed working this session,
  ~0.9s cold startup. This is the vehicle the split-JAR on-demand language loading above is
  built for; native-image can't dynamically load a Truffle language JAR at runtime (see
  "Native-image has no dylib/shared-library support" in ROADMAP.md), so on-demand loading only
  works in JVM mode. The native-image build config has since been removed entirely (see
  "Native-image removed" above) — `jvmRun` is now the only supported way to run Mycelium.
- **setTimeout/setInterval fixed**: previously spawned a raw Java `Thread` per timer, which
  crashed with `IllegalStateException` (GraalVM `Context` is single-threaded for JS — see
  hard constraints below). Replaced with a proper single-threaded event loop in
  `TimerManager.kt`; `Timer` objects now expose `ref`/`unref`/`refresh`/`hasRef`/`close`
  matching the Node/Bun/Deno timer-handle contract.

## Hard constraints / gotchas

- TruffleRuby is not published above 25.0.0 on Maven Central — this is the practical ceiling for GraalVM version bumps (see "GraalVM version" below).
- GraalPython upstream bug blocks Python support entirely — see dedicated section below.
- Maven Central's search API is stale/unreliable for checking whether a version exists — always verify against the raw repo (e.g. `repo1.maven.org`), not the search UI/API.
- GraalJS auto-unboxes host `String`s to JS primitives — use `Charset#encode`, not `String.getBytes`, when producing bytes from JS-originated strings.
- GraalJS ships no `URL`/`URLSearchParams` globals — had to be implemented in pure JS.
- GraalVM `Context` is single-threaded for JS — the httpserver dispatch thread cannot share a `Context` across threads.
- `native-image` used to peak at ~17GB RAM during build and produce an ~887MB binary when every
  language was baked in. Resolved in two steps: first by splitting languages into per-language
  Gradle configurations loaded on demand (core runtime is now 22MB with zero languages baked
  in; see "Architecture decisions" above), then by removing the native-image build config
  outright this session (see "Native-image removed" above) — JVM mode's `jvmRun` is now the
  only build/run path. The 887MB figure now only applies to an intentional full-polyglot build
  (709MB across 71 JARs in JVM-mode `installDist`).
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

**Round 2 — initially 2/5 pass, ended the session at 5/5 (all gaps fixed):**
- `semver` — WORKS (plain CJS, same `"main"` → CJS-to-ESM path as lodash).
- `ms` — initially FAILED, **fixed**: extensionless `"main"` resolution now probes `.js`/`.json`/etc. like Node does. `"main": "./index"` had no file extension; the entry-point resolver (now `NodeModuleResolver.resolvePackageEntryPoint` post-refactor — see "Architecture decisions" above) resolved it literally with no extension-probing fallback.
- `debug` — initially FAILED on `require("tty")` (not yet a core module at the time). **Fixed**: real `tty` module added.
- `axios` — initially FAILED on non-recursive conditional-exports resolution (`exports["."]` nested one level deeper than the resolver descended). **Fixed**: resolver now recurses.
- `express` — initially FAILED transitively via the same `debug`/`tty` gap. **Fixed** once `tty` landed; also required `Error.captureStackTrace` and `http2` later on (see Round 4-adjacent work) before a full live-HTTP-round-trip test passed.

**Chalk regression (pre-existing, confirmed via `git stash` to predate this session) — fixed:** `chalkResolvesSubpathImports` was failing against current "latest" chalk (5.6.2) — `#ansi-styles` failed with "no matching imports entry found". Root cause: GraalJS's ESM loader resolves a module's imports in a second phase, after `sourceDirectoryStack` (pushed/popped around `newByteChannel` reads in `InterceptingFileSystem.kt`) had already popped that module's directory. Fixed with a `lastReadModuleDir` ThreadLocal that tracks the last-read module's directory for subpath/bare-specifier resolution.

All Round 1/2 findings are cataloged as tests in `NpmCompatibilityTest.kt` (see its class doc comment for full detail) — all now passing, not just cataloged.

### Known npm gaps to fix (ordered simplest → hardest) — ALL FOUR FIXED this session
1. Extensionless `"main"` field resolution — **fixed**, now probes `.js`/`.json`/`index.js` etc. like Node does.
2. Add a `tty` core module — **fixed**, real module (not just a stub), in the `NodeParityTest.kt` `MODULES` list.
3. Recursive/nested conditional-exports resolution in `package.json`'s `"exports"` field — **fixed**, also handles array-of-fallback-candidates form (needed for yargs/escalade).
4. Chalk subpath-import regression — **fixed**: root cause was GraalJS's ESM loader resolving a module's imports in a second phase after `sourceDirectoryStack` had already popped that module's directory; fixed with a `lastReadModuleDir` ThreadLocal tracking the last-read module's directory.

**Round 3 (ws, commander, yargs, glob, pino, zod, nanoid, inquirer) — quick-win fixes landed this session:**
- `ws`, `zod`, `nanoid` — already WORKED (no gaps).
- `commander` — was FAILING on `util.mjs` missing `stripVTControlCharacters`. Fixed: added to `util.mjs`, now WORKS end-to-end.
- `yargs` — was FAILING on escalade's `exports["./sync"]` being an *array* of fallback candidates (`RESOLVE_CONDITIONS_FN` didn't recurse into arrays). Fixed: `resolveConditions` (now in `NodeModuleResolver.kt` post-refactor — see "Architecture decisions" below) now tries each array element in order. **Later in the same session**, `node:module` (`builtinModules`) was implemented too — yargs now passes fully.
- `glob` — was FAILING on missing `fs/promises`/`node:fs/promises` core module. Fixed: added `fs-promises.mjs`, registered in `coreModules`. **Later in the same session**, global `URL`/`URLSearchParams` were exposed (glob/minimatch/path-scurry construct `new URL(...)` at module scope) — glob now passes fully.
- `pino` — was FAILING on missing `diagnostics_channel`/`node:diagnostics_channel`. Fixed: added a real (not just stubbed) pub/sub implementation in `diagnostics_channel.mjs`. **Later in the same session**, `worker_threads` was implemented and `buffer.constants` (`MAX_STRING_LENGTH`) was added — pino now passes fully.
- `inquirer` — was FAILING on missing `async_hooks`/`node:async_hooks`. Fixed: added `async_hooks.mjs` with a genuinely-working (synchronous-only) `AsyncLocalStorage` plus inert `createHook`/`AsyncResource` stubs. **Later in the same session**, `readline` was implemented and a CJS→ESM named-export interop gap for `chardet` was fixed (Mycelium executes the CJS module first, then enumerates its real `module.exports` at runtime to generate accurate named ESM re-exports — more correct than Node's static `cjs-module-lexer` since it catches dynamic exports too) — inquirer now passes fully. **Round 3 final: 8/8 pass** (ws, zod, nanoid, commander, yargs, glob, pino, inquirer).
- Also fixed while in the area (not gating any round-3 package directly, but needed for real-world npm compat): `util.mjs` gained `debuglog`/`debug`, `parseArgs`, `promisify.custom`, `styleText`, `formatWithOptions`, `getSystemErrorName`, `toUSVString`.
- `assert.strict.rejects`/`assert.strict.doesNotReject` — checked, were already correctly implemented (no bug found there).

**Round 4 (fastify, jsonwebtoken, bcryptjs, moment, yaml, socket.io, mongoose, pg, sharp, esbuild) — FINAL: 9/10 pass, only `sharp` fails (native addon, expected/permanent):**

Initial pass found 3/10 working and 7 gaps (see prior paragraph in git history for the
original per-package diagnosis); all but one were fixed in the same session:
- **Fixed**: `dns` module implemented (unblocked fastify's initial gap — fastify then hit a
  separate GraalJS parser bug, see below); `crypto.mjs`'s internal `KeyObject` class now
  exported (jsonwebtoken passes); `util/types` registered as a core module subpath (pg
  passes); `os.availableParallelism` added (sharp got past its ES-module-linking failure,
  though it still fails later on the native addon itself — expected); `require.resolve()`
  implemented on the CJS shim (esbuild passes); the "Is a directory" exception blocking both
  socket.io and mongoose was `Files.exists()` returning true for directories somewhere in
  `NodeModuleResolver`'s resolution walk — fixed with `Files.isRegularFile()`, which also
  surfaced and fixed a CJS/ESM condition-priority bug (`require` vs `import` ordering).
- **fastify** — after the `dns` fix, hit a second wall initially misdiagnosed as "GraalJS
  can't parse `}export{` syntax in toad-cache" (an upstream parser bug, thought
  unfixable). Re-investigated and that diagnosis was wrong — the real blockers were a
  missing `perf_hooks` module, `http.Server.setTimeout()`, `Server.listen()` not emitting a
  `"listening"` event, and a missing `queueMicrotask` global. All fixed; fastify passes.
- **sharp** — the only permanent failure. Needs native N-API/`dlopen` support to load
  libvips's compiled addon; GraalJS has no N-API layer (same reason native addons don't work
  in Deno, and only work in Bun because Bun built a compat layer). Now fails with sharp's own
  clean error message rather than crashing, which is the fully-correct behavior for a native
  addon Mycelium can't load — this is a distinct, permanent gap (see "Native addon / FFI
  support" in ROADMAP.md), not a stdlib omission to revisit.

All round 4 findings are cataloged as tests in `NpmCompatibilityTest.kt` (9 passing, 1
`@Disabled` for sharp) — see its class doc comment for full detail.

**Full acid-test tally across all 4 rounds: 27/28 (96%).** Only `sharp` fails, and only for
the fundamental native-addon reason above.

### Known npm/stdlib gaps remaining — dgram/cluster and the parity-list gap both RESOLVED this session
1. `dgram`, `cluster` core modules — **implemented this session**
   (`library/src/main/resources/node-stdlib/dgram.mjs`, `cluster.mjs`); no longer missing.
2. `NodeParityTest.kt`'s `MODULES` list — **extended this session** to include `dns`,
   `worker_threads`, `readline`, `node:module`, `perf_hooks`, `timers`, `util/types`, `dgram`,
   `cluster`, and all 14 niche/deprecated modules below (52 dynamic parity tests total). No
   longer a test-coverage gap.
3. A real `console` module landed (`eb0611a`) — no longer relying on GraalJS's built-in
   console. Deeper formatting fidelity beyond module-surface parity remains open regardless.

### Missing Node stdlib modules — NONE remain unimplemented (this session)
`dgram` and `cluster` — the last two modules with no implementation at all — are now
implemented, closing that gap entirely. Everything previously tracked here — `net`, `dns`,
`util/types`, `child_process`, `tty`, `diagnostics_channel`, `async_hooks`, `fs/promises`,
`worker_threads`, `readline`, `module`, `perf_hooks`, `timers` — was already implemented.
**Also landed this session**: 14 niche/deprecated modules previously not tracked at all —
`constants`, `domain`, `punycode`, `sys`, `repl`, `trace_events`, `v8`, `wasi`, `inspector`,
`inspector/promises`, `node:test`, `node:test/reporters`, `node:sea`, `node:sqlite`. Total:
50+ core modules (up from ~30; see `library/src/main/resources/node-stdlib/*.mjs`).

`stream/promises`, `stream/consumers`, `stream/web`, `path/posix`, `path/win32` submodules and
a real `console` module also landed (`eb0611a`) — see "Open threads" below for the
readline-circular-import fix that shipped alongside them.

## Language support status

### JavaScript / TypeScript — working
Core language. TypeScript (`.ts`/`.mts`, routed to `js` via `JsSupport.extensions`) transpiles through `TsCompiler` (`library/src/main/kotlin/org/mycelium/library/TsCompiler.kt`) using `io.mvnpm:esbuild-java:1.5.1`. `InterceptingFileSystem.newByteChannel` intercepts `.ts`/`.mts` reads before any other routing and hands GraalJS the transpiled JS. Output cached on disk by source-content hash (`~/.cache/mycelium/ts`, same pattern as `Compiler.compile`'s LLVM cache) since esbuild spawns a subprocess and downloads a platform binary on first use (network required once; ~5s cold, near-instant cached). esbuild hashes output filenames unpredictably, so `TsCompiler` picks the lone `.js` file in the esbuild dist dir (safe since each transpile is a single non-bundled entry point). Covered by `jsImportsTypeScript` in `MyceliumIntegrationTest.kt`.

### Compile-to-JS languages (12: TypeScript, CoffeeScript, Civet, LiveScript, Elm, PureScript, ReScript, Imba, Gleam, Dart, Kotlin/JS, Scala.js) — working
Generalized pipeline, not one-off wiring: `CompileToJsDispatcher` resolves a `CompilerSpec`
(`CompilerRegistry`) per extension and routes through one of three `CompileToJsBackend`s —
`EsbuildBackend` (`BUNDLED`, TypeScript only), `NpmJsApiBackend` (`JS_API`, CoffeeScript/
Civet/LiveScript — `require()`'d into the shared GraalJS Context), `SubprocessBackend`
(`SUBPROCESS`, Elm/PureScript/ReScript/Imba/Gleam/Dart/Kotlin-JS/Scala.js — no JS-callable
compile function at all). Elm/PureScript/ReScript additionally get project-aware whole-project
builds (`elm.json`/`spago.yaml`,`spago.dhall`/`rescript.json`,`bsconfig.json` found walking up
from the source file) via `ProjectBuildRunner`, falling back to single-file compilation with
documented limitations when no project file exists. Dart/Kotlin/Scala also get project-aware
resolution against `tsconfig.json`-adjacent project markers where applicable.

Compiler packages are resolved/downloaded via the pure-Kotlin `CompilerDownloader` (backed by
`NpmRegistryClient` + `PackageCache`) — no `npm`/`node` subprocess needed to fetch them.
PureScript's `spago` (a separate npm package from `purescript`/`purs`), Gleam's compiler
binary (via GitHub releases, not npm — Gleam isn't published there at all), and the Dart SDK
(via a dedicated `DartSdkDownload` `ToolSource`, not an npm package) are each resolved
independently via `ToolResolver`/composable `ToolSource`s (project `node_modules/.bin` →
system PATH → Mycelium's own cache → registry download → GitHub release → Dart SDK download).
`ToolResolver` is now version-aware: it validates a resolved candidate binary's real version
against a project's `package.json` constraint (`findPackageJsonConstraint`) before accepting
it, and caches resolution results (stat+mtime-keyed) so repeated compiles skip re-probing the
whole `ToolSource` chain.

`jsImportsCoffeeScript`/`jsImportsCivet` in `MyceliumIntegrationTest.kt` were still gated on
`npm` being on PATH from when this was first built with an actual `npm install` fallback path
— that gate was stale (removed earlier session): `CompilerDownloader` never shells out to `npm`
at all, only to the npm *registry* over HTTP, so both tests now run unconditionally (still
need real network access to the registry the first time, cached thereafter).

**Previously "investigated, not added" — all since landed** (see `CompilerRegistry.kt`'s own
comment/history for the exact unblock per language): Imba (moved off `NpmJsApiBackend`'s
`outputIsObject` contract onto `SubprocessBackend`), Gleam (`GitHubRelease` `ToolSource` instead
of requiring an npm package), Dart/dart2js (`DartSdkDownload` `ToolSource` fetches the SDK
directly), Kotlin/JS and Scala.js (both resolved as subprocess tools via `ToolResolver` rather
than requiring a single JS-callable npm package).

**Fable is now the only remaining "investigated, not added" candidate**:
`@fable-org/fable-compiler-js` needs no .NET, but its npm `bin` is a plain JS file relying on
its own `#!/usr/bin/env node` shebang — the first compile-to-JS language here that would
require an external `node` install. Possible unblock for next session: run Fable's compiler on
GraalJS itself instead of shelling out to a real `node` binary.

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

All 60 `:library:test` tests pass and `:app:nativeCompile` succeeds at 25.0.0. `flake.nix` uses nixpkgs' stock `graalvmPackages.graalvm-ce`, currently 25.0.2 — close enough to Maven's 25.0.0 that native-image works without incident. *(Historical note: this was true when written, before the native-image build config was removed entirely later in the project — see "Native-image removed" above. `:app:nativeCompile` no longer exists as a Gradle task; kept here as-is since this section documents a specific past GraalVM-version-bump investigation, not current build instructions.)*

**Revisit when:** upstream publishes matching 25.1.x Ruby/LLVM Maven artifacts, or confirms they're dropped for good — `org.graalvm.polyglot:ruby` / `org.graalvm.ruby:ruby` is already marked deprecated upstream ("retained only for Maven compatibility").

## Open threads

*Open threads from a previous session. Treat as starting context, not instructions — verify
relevance before acting.*

### GraalVM Insight — integrated, untested in production
`--insight=<script>` flag lands GraalVM's dynamic-instrumentation API (Instrument API) in
Mycelium; tested with source-load tracing. Known limitation, by design: the daemon path does
not forward insight scripts to a running daemon — the daemon bypass added this session (see
"Daemon reliability" below) routes insight (and mod-loader) invocations to an in-process JVM
run instead, since instrumentation needs to attach to the actual `Engine`/`Context` being
built, not a pre-existing shared one. Open question: should Mycelium ship built-in instrument
scripts (e.g. a cross-language call tracer) rather than leaving `--insight` purely
bring-your-own-script?

### Mod loader — functional, room to grow
Infrastructure is complete: mod discovery, descriptors, transformers, priority-ordered
dispatch, dependency resolution, version checking, and a helper DSL (`patchClass`,
`afterCallsTo`, `redirectCallsTo`) for writing mods without touching Truffle internals
directly. Four working example mods ship: cursed-addition (`1 + 1 = 3`), null-safe property
access (`null.foo` returns `undefined` instead of throwing), strict equality (`==` behaves
like `===`), and integer division (`/` truncates toward zero like C, patches JSDivideNode).
This is the concrete instance behind the "mod loader for language runtimes" value
proposition above. Open questions/gaps:
- **Mixin integration** — research done (`docs/research/java-modding-systems.md`): realistic,
  but Mycelium would be the first non-Minecraft consumer of Mixin-style bytecode weaving.
  Unevaluated whether it's worth the dependency.
- **`obj[x]` computed access gap** — RESOLVED this session, now patched via ReadElementNode in
  the null-safe mod.
- **Mod authoring docs** — RESOLVED this session (`docs/mod-authoring.md` exists), documents
  how to find the right Truffle node class and use the helper DSL.
- **GraalJS constant-folds literal arithmetic at parse time** — mod patches on arithmetic nodes
  only affect *non-constant* operands (`x + y` where `x`/`y` are variables), not literal
  expressions like `1 + 1` that the parser folds before any node-level patch point exists. This
  is why cursed-addition works as a demo but wouldn't intercept every `+` in a real program —
  worth documenting explicitly for mod authors so it isn't discovered the hard way.
- **More mod ideas floated, not started**: proper tail calls, operator overloading.

### Hybrid metaprogramming — partial results, not committed
Experimental session exploring cross-language metaprogramming across the shared polyglot
`Context` — findings only, nothing landed in git. Tested: Ruby `method_missing` on JS objects,
Python `__getattr__` on Ruby hashes, JS `Proxy` on Ruby objects, Ruby `Enumerable` duck-typing
(`each`/`map`/`respond_to?`) on JS arrays. What works: `method_missing`, `__getattr__`, `Proxy`
get/set traps, and the duck-typing cases. What's flattened/broken: Ruby singleton classes don't
survive being applied to a foreign (non-Ruby) object; JS `Proxy`'s `has` trap returns the wrong
answer when the target is a Ruby hash's keys; Ruby's `send` to a missing member on a foreign
object throws an uncatchable *internal* error rather than a catchable Ruby exception.
JS-to-Python interop is entirely blocked by the pre-existing GraalPython `FileSystem` bug (see
"Python — blocked upstream" above) — couldn't even get far enough to test hybrid patterns
there. Not a current priority, but the specific working/broken cases above are worth knowing if
this gets picked back up, since none of it is written down anywhere except this note.

### Daemon reliability
Two changes from session #5, both aimed at a specific failure mode where a stale daemon kept
intercepting test runs during development:
- **Version handshake added to the wire protocol** — the client now sends its version on
  connect; the daemon rejects (and auto-shuts-down) on a version mismatch instead of silently
  serving requests with stale code. This is the fix for the recurring "stale daemon intercepts
  every test run" problem, but it was not tested end-to-end against the new code by the end of
  the session — verify it actually triggers the shutdown path before relying on it.
- **Daemon bypass for JVM-level features** — `--insight` and mod-loader invocations skip the
  daemon and run in-process instead, since both need direct access to the `Engine`/`Context`
  under construction (see "GraalVM Insight" above).
- **Daemon gotcha (discovered this session)**: mods don't take effect if a stale daemon is
  running without the javaagent — the bypass path isn't triggered. Kill the daemon first or
  disable it (`--no-daemon`) for mod testing.
- **Still open, undiagnosed**: a "ZipFile invalid LOC header" daemon bug surfaced repeatedly
  during this session's testing. Not root-caused. Worth investigating first if daemon flakiness
  shows up again next session.

### Rust/Go auto-install gap — architecture gap, not a registry-client gap
`CratesIoClient`/`GoProxyClient` (pure-Kotlin `PackageIndex` implementations, same shape as
the npm/RubyGems/PyPI clients) exist and are fully able to resolve/download, but neither is
consulted by any automatic bare-specifier resolution yet. Fetching crates/Go modules is easy;
the hard part is integrating their compiled output into Mycelium's LLVM bitcode/WASM
pipeline: `RustSupport`/`TinyGoSupport` each compile a single source file straight to one
standalone LLVM-bitcode/WASM unit (`Compiler.compile`), with no dependency-graph resolution or
multi-unit linking step at all — there's no compile-time seam an auto-fetched crate/module's
compiled output could plug into today without that linking architecture existing first.

### Fable (F# → JS) — closest remaining compile-to-JS candidate, still blocked
`@fable-org/fable-compiler-js` needs no .NET runtime, but its npm `bin` is a plain JS file that
only runs via its own `#!/usr/bin/env node` shebang — the one thing every other compile-to-JS
language in this project avoids needing. Possible unblock to try next session: run Fable's
compiler on GraalJS itself (Mycelium's own JS engine) instead of shelling out to a real `node`
binary.

### Kotlin/JS as an in-process JVM library call
Currently invoked as a `SUBPROCESS` backend (`kotlinc-js`). Since Mycelium is itself a
Kotlin/JVM application, it may be possible to invoke the Kotlin/JS compiler in-process as a
library call instead of shelling out to a separate `kotlinc-js` process — would save
process-spawn overhead on every compile. Unexplored.

### Memory reclamation — benchmarked this session, informs the daemon design above
G1 GC with tuned flags reclaims aggressively after a load spike: peak 1914MB → 187MB idle
within 30s, using `-XX:+UseG1GC -XX:G1PeriodicGCInterval=1000 -XX:MinHeapFreeRatio=10
-XX:MaxHeapFreeRatio=20`. Default (untuned) G1 holds onto memory the way Gradle's daemon
does — doesn't give it back to the OS promptly. **ZGC was tried and was surprisingly worse**
for this workload (held more memory, not less) — counter to the general expectation that ZGC
is the "give memory back" collector; not re-investigated further this session, just noted as
a negative result. These flags are the starting point for daemon-mode JVM tuning once the
daemon architecture above is actually built.

### Node stdlib `.mjs` shims — confirmed intentional, not tech debt
Revisited this session: the choice to implement Node core modules as JS-side `.mjs` shims
(rather than pure Kotlin/Java) is deliberate, not a shortcut to clean up later. JS is the
right tool for these specifically because (a) they need Java interop for I/O (file handles,
sockets, etc.) and (b) they need to express Node's callback/event semantics (`EventEmitter`,
stream backpressure, etc.), which are naturally JS-shaped. This item is closed — no action
needed, listed here only so a future session doesn't reopen it as if it were an oversight.

### Truffle language embedding JAR gap — unresearched
TruffleRuby and Sulong (LLVM) Maven artifacts stopped being published past 25.0.0. TruffleRuby
itself is alive upstream (release 34.0.1), Oracle just stopped publishing embedding JARs for
it. FastR (R) never had Maven artifacts and is effectively abandoned (see dedicated FastR
thread below). Open question: can Mycelium build TruffleRuby/Sulong from source against
current Truffle APIs and publish its own embedding JARs? If the Truffle SPI is stable across
versions, this could be straightforward packaging; if unstable, it's an ongoing maintenance
burden. This is the practical ceiling on the GraalVM version bump (see "GraalVM version"
above) — matters because it blocks moving off 25.0.0 for as long as it's unresolved.

### Bun/Deno runtime compat — confirmed goal, not started
Confirmed as an actual goal ("you have code? we run code"), not just a nice-to-have. Detection
should be import-based, not just lockfile-based: `bun:`-prefixed imports imply Bun, URL imports
imply Deno, cross-runtime branching code should fall through to the Node path (safest, most-
tested fallback) — see "Bun/Deno Compatibility Strategy" above for the fuller detection-order
rationale already agreed on. The API surface for `Bun.*`/`Deno.*` globals is well-documented
upstream but large. No implementation work started yet.

### Value proposition — advanced this session, not settled
Previously "genuinely unclear beyond 'it's funny.'" This session explored it extensively and
landed on a new candidate: **mod loader for programming language runtimes**. No prior art
exists for this — Minecraft-style mod loading (discovery, descriptors, dependency resolution,
version checking, priority-ordered dispatch) applied to a JVM language runtime instead of a
game. The framing that stuck: *because it's funny / because it's technically possible* — not a
business case, not (yet) a technical necessity, but a genuinely novel angle that the polyglot
story alone didn't have. Older candidates (universal *host* vs WASM's universal *target*,
cross-ecosystem package reuse, "you have code, we run code") are still live but felt less
distinctive once the mod loader angle showed up. Not resolved as THE identity — worth sitting
with further — but significantly advanced from "unclear" to "strong candidate with a name."

### GraalJS vs V8 throughput — settled, not open, noted for context
Structural ~1.5-2x gap, conceded (Octane aggregate: V8 39,145 vs GraalJS 22,350; on non-loop-heavy
workloads the gap can blow out to 80x — no native-image/JVM-mode/JIT-tuning knob changes this,
it's the engine). JS execution speed is not the value proposition; polyglot interop is. Listed
here only so a future session doesn't reopen it as if unresolved.

### Stub/throws inventory — known remainder after this session's fixes
Full-codebase audit found 27 FULL, 20 PARTIAL, 7 STUB, 3 THROWS implementations across Node
core modules. Silent-wrong-answer cases (stubs that returned plausible but incorrect results)
were fixed this session (`253ae14`). Remaining known gaps: STUB-level modules `inspector`,
`repl`, `test-reporters`, `trace_events`, `tty`, `cluster`; THROWS-level modules `sea`,
`sqlite`, `wasi` (these throw explicitly rather than silently misbehaving, which is considered
acceptable for now). Open question: `sqlite` might warrant a real implementation via
`sqlite-jdbc` as an optional JAR dependency rather than staying a throw — unevaluated.

### Kotlin/JS in-process compiler — unchanged, not started
See "Kotlin/JS as an in-process JVM library call" above — still just a subprocess call to
`kotlinc-js`, could be a direct JVM library call to save process-spawn overhead. Not started
this session either.

### Fable (F# → JS) — unchanged, still blocked
See "Fable" entries above — blocked on needing a real `node` binary for its `bin` shebang. Not
attempted this session; the GraalJS-as-node-substitute idea remains the proposed unblock.

### Round 5 npm acid test — not started
Next tier up from the 27/28-pass Round 1-4 acid tests: webpack, prisma, next.js. Significant
difficulty step up from prior rounds (build tooling, ORM/native-binding-adjacent code, and a
full framework, respectively). Not started this session.

### Ruby cross-language import UX — open question, unchanged
Cross-language `.rb` imports work but require explicit `public :method_name` and a trailing
`self` in `.rb` files to expose methods across the language boundary — this could be
surprising to users coming from plain Ruby. Open question: should Mycelium infer/handle this
automatically instead of requiring it explicitly? Not investigated this session.

### FastR (R support) — effectively abandoned upstream, alternatives unevaluated
FastR never had Maven artifacts, was removed from GraalVM's own docs, and its last real
upstream activity was 2023 — effectively abandoned as a path to R support. Alternative paths
discussed but not investigated: Renjin (pure-Java R implementation, published on Maven
Central), R-to-WASM, or a subprocess call to a real `Rscript` binary (same category of
tradeoff as the Fable/`node` situation). None investigated this session.

## User preferences (for future sessions)

- "Simplest first, sweep everything, note blockers, don't get stuck" — the general working style for compatibility sweeps.
- Node stdlib compatibility is CORE to the project, not a nice-to-have.
- Must mirror Node semantics 1:1, not approximate them.
- End goal is a self-contained binary, not a Nix-only distribution.
- User fact-checks claims — verify before relaying anything as confirmed.
- Prefers architecturally sound solutions over expedient ones (e.g. rejecting `js.commonjs-require` rather than papering over its incompatibility).

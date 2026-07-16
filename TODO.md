# Mycelium — Project State & Open Threads

*Updated 2026-07-16 (test-gating fix session: stale npm `assumeTrue` gates removed, compile-to-JS/package-infrastructure docs brought current, Gleam/Dart/Kotlin-JS/Scala.js/Fable investigated and not added). This file is the project's institutional memory — written to survive context compaction. Treat as reliable background, but verify against code before relying on specifics that could have drifted.*

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

### Known npm/stdlib gaps remaining (small, none blocking a cataloged package)
1. `dgram`, `cluster` core modules — not implemented (no cataloged package has hit these yet).
2. `NodeParityTest.kt`'s `MODULES` list hasn't been extended to include `dns`,
   `worker_threads`, `readline`, `node:module`, `perf_hooks`, `timers`, `util/types` — all
   exist and work, just lack parity-harness coverage (test-coverage gap only).
3. Console output could be improved beyond GraalJS's defaults (unchanged from earlier notes).

### Missing Node stdlib modules
Only `dgram` and `cluster` remain unimplemented. Everything else previously tracked here —
`net`, `dns`, `util/types`, `child_process`, `tty`, `diagnostics_channel`, `async_hooks`,
`fs/promises`, `worker_threads`, `readline`, `module`, `perf_hooks`, `timers` — is now
implemented (~30 core modules total; see `library/src/main/resources/node-stdlib/*.mjs`).

## Language support status

### JavaScript / TypeScript — working
Core language. TypeScript (`.ts`/`.mts`, routed to `js` via `JsSupport.extensions`) transpiles through `TsCompiler` (`library/src/main/kotlin/org/mycelium/library/TsCompiler.kt`) using `io.mvnpm:esbuild-java:1.5.1`. `InterceptingFileSystem.newByteChannel` intercepts `.ts`/`.mts` reads before any other routing and hands GraalJS the transpiled JS. Output cached on disk by source-content hash (`~/.cache/mycelium/ts`, same pattern as `Compiler.compile`'s LLVM cache) since esbuild spawns a subprocess and downloads a platform binary on first use (network required once; ~5s cold, near-instant cached). esbuild hashes output filenames unpredictably, so `TsCompiler` picks the lone `.js` file in the esbuild dist dir (safe since each transpile is a single non-bundled entry point). Covered by `jsImportsTypeScript` in `MyceliumIntegrationTest.kt`.

### Compile-to-JS languages (CoffeeScript, Civet, LiveScript, Elm, PureScript, ReScript) — working
Generalized pipeline, not one-off wiring: `CompileToJsDispatcher` resolves a `CompilerSpec`
(`CompilerRegistry`) per extension and routes through one of three `CompileToJsBackend`s —
`EsbuildBackend` (`BUNDLED`, TypeScript only), `NpmJsApiBackend` (`JS_API`, CoffeeScript/
Civet/LiveScript — `require()`'d into the shared GraalJS Context), `SubprocessBackend`
(`SUBPROCESS`, Elm/PureScript/ReScript — no JS-callable compile function at all). Elm/
PureScript/ReScript additionally get project-aware whole-project builds (`elm.json`/
`spago.yaml`,`spago.dhall`/`rescript.json`,`bsconfig.json` found walking up from the source
file) via `ProjectBuildRunner`, falling back to single-file compilation with documented
limitations when no project file exists.

Compiler packages are resolved/downloaded via the pure-Kotlin `CompilerDownloader` (backed by
`NpmRegistryClient` + `PackageCache`) — no `npm`/`node` subprocess needed to fetch them.
PureScript's `spago` (a separate npm package from `purescript`/`purs`) is resolved
independently via `ToolResolver`/composable `ToolSource`s (project `node_modules/.bin` →
system PATH → Mycelium's own cache → auto-download).

`jsImportsCoffeeScript`/`jsImportsCivet` in `MyceliumIntegrationTest.kt` were still gated on
`npm` being on PATH from when this was first built with an actual `npm install` fallback path
— that gate was stale (removed this session): `CompilerDownloader` never shells out to `npm`
at all, only to the npm *registry* over HTTP, so both tests now run unconditionally (still
need real network access to the registry the first time, cached thereafter).

Investigated for addition, not added (see `CompilerRegistry.kt`'s own comment): Gleam (compiler
isn't published to npm — it's a Rust binary via GitHub releases/Hex), Dart/dart2js (no
self-contained npm package, needs the full Dart SDK), Kotlin/JS (needs a full classpath +
stdlib jars), Scala.js (needs scala-cli/sbt + linker + library jars), Fable
(`@fable-org/fable-compiler-js` needs no .NET, but its npm `bin` is a plain JS file relying on
its own `#!/usr/bin/env node` shebang — would be the first compile-to-JS language requiring an
external `node` install, which every other one here avoids).

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

*The three items below were written when auto-install, pip/gem resolution, and the CLI
binary didn't exist yet. All three have since shipped — descriptions updated to reflect
what actually landed and what's still genuinely open (see ROADMAP.md's "Open design
questions" for the longer-term unresolved angle on each).*

### Package manager auto-install — landed (both eager and declarative), now pure-Kotlin-first
Both options from the original open question shipped, not just one: **`--yolo`** does eager
auto-install (installs on first missing bare specifier), and **manifest-based install** runs
when a `package.json` with dependencies is found but `node_modules` isn't (suppressible with
`--no-install`, e.g. for offline use or when the user manages `node_modules` themselves). npm
bare-specifier resolution against an existing `node_modules` was already implemented and
still works as before.

Since then, both paths were rebuilt on top of `PackageInstaller` (pure Kotlin,
`NpmRegistryClient` + `PackageCache`, no subprocess) as the primary path, falling back to
shelling out to `npm install`/`pnpm install` only if that fails. `PackageInstaller` and
`CompilerDownloader` now resolve real semver ranges (`^`/`~`/x-ranges/hyphen ranges/`||`/
comparators) via `SemverResolver` against the registry's actual published versions, rather
than falling back to "latest" — see `SemverResolver.kt`. So
neither `--yolo` nor manifest-based install actually requires npm/pnpm on PATH anymore in the
common case — `MyceliumIntegrationTest.kt`'s `yoloAutoInstallsMissingPackage` and
`manifestInstallRunsNpmInstall` had this session's stale `assumeTrue(isCommandAvailable
("npm"))` gate removed accordingly (both still need real network access to the npm registry,
just not the npm binary itself).

### pip / gem resolution — landed, including fetching (not just $LOAD_PATH/site-packages walk)
Resolution for Python (site-packages) and Ruby (gem) packages is implemented, alongside the
pre-existing npm resolution. Resolution order tries the host language's own ecosystem first,
falling back to others. Python resolution is implemented but can't be exercised end-to-end
in practice because of the GraalPython upstream bug (see "Python support" above) — the
resolver itself is not what's blocked.

Beyond the static walk, `--yolo` auto-install now covers Ruby/Python too, not just npm:
`RubyGemsClient`/`PyPIClient` (pure-Kotlin `PackageIndex` implementations, mirroring
`NpmRegistryClient`) are constructed in `Mycelium`'s composition root, each paired with its own
`PackageInstaller`, and threaded into `InterceptingFileSystem` (`rubyGemsInstaller`/
`pyPIInstaller` constructor params). A bare `require`/`import` that can't be resolved against
`$LOAD_PATH`/`sys.path` (real or previously auto-installed) now fetches the gem/package from
RubyGems/PyPI (`yoloInstallRubyGem`/`yoloInstallPyPIPackage`) into a project-local vendor
directory (`.mycelium-vendor/gems/<name>` / `.mycelium-vendor/pypi/<name>`) and retries —
`RubyModuleResolver`/`PythonModuleResolver` each gained a `vendorSearchPaths` list (consulted
ahead of the real `$LOAD_PATH`/`sys.path`) and a `registerInstalledGem`/`registerInstalledPackage`
method to register a freshly-installed package's actual importable directory (handling both a
flat and a "src layout" sdist for Python; a gem's `lib/` convention for Ruby). No
gem-install/pip-install subprocess fallback exists (unlike npm's yolo, which falls back to
`npm install`/`pnpm install`) — there's no equivalent portable CLI story to shell out to, so a
failed install just surfaces as the pre-existing "cannot find module" error.

`PackageCache` gained real support for the archive shapes these ecosystems actually use beyond
npm's `.tgz` — PyPI sdist `.tar.gz` (top-level-dir-stripped gzip tar, same as npm), a PyPI wheel
`.whl` (flat ZIP, no stripping), a RubyGems `.gem` (an *outer* uncompressed tar wrapping an inner
`data.tar.gz`, unwrapped via a new `extractGem`), and (support built, not yet exercised by an
automatic resolver — see below) a Go module `.zip` (top-level-dir-stripped ZIP).

`CratesIoClient`/`GoProxyClient` are constructed in `Mycelium`'s composition root
(`cratesIoClient`/`cratesIoInstaller`, `goProxyClient`/`goProxyInstaller`) and fully able to
resolve/download, but **not yet consulted by any automatic bare-specifier resolution** —
`RustSupport`/`TinyGoSupport` compile a single source file straight to one standalone
LLVM-bitcode/WASM unit (`Compiler.compile`) with no dependency-graph resolution or multi-unit
linking step at all yet, so there's no compile-time seam an auto-fetched crate/module's
compiled output could wire into today without that architecture existing first. That
architecture gap — not the registry client — is what's actually blocking Rust/Go auto-install.

### Distribution form — CLI binary works, weight still an open question
`native-image` now produces a working standalone `mycelium` binary (~887MB, RAM-capped at
build time via `-J-Xmx8g`) and `installDist` produces a ~709MB JRE-dependent alternative —
both work via `./gradlew :app:installDist` / `:app:nativeCompile`, not just `:app:run`. The
genuinely open question, tracked in ROADMAP.md's "Distribution" and "Open design questions"
sections, is whether GraalVM's ~700-900MB footprint is acceptable long-term or worth
trimming — and a substantial redesign direction was scoped this session (not yet
implemented): don't bundle *any* Truffle language runtime or compiler in the core binary;
fetch and cache them on demand per-language, JVM-mode only (native-image can't dynamically
load Truffle languages post-build — see ROADMAP.md's "Distribution Architecture" section for
the full native-image-vs-JVM tradeoff writeup).

## User preferences (for future sessions)

- "Simplest first, sweep everything, note blockers, don't get stuck" — the general working style for compatibility sweeps.
- Node stdlib compatibility is CORE to the project, not a nice-to-have.
- Must mirror Node semantics 1:1, not approximate them.
- End goal is a self-contained binary, not a Nix-only distribution.
- User fact-checks claims — verify before relaying anything as confirmed.
- Prefers architecturally sound solutions over expedient ones (e.g. rejecting `js.commonjs-require` rather than papering over its incompatibility).

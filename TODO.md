# Mycelium — Project State & Open Threads

*Updated 2026-07-17 (session handoff #2: the daemon architecture scoped as an open thread
earlier in the 2026-07-17 session is now fully implemented — Unix socket server, shared
`Engine` across requests, per-request cwd/env/flags isolation, auto-start with config
opt-out, 1h idle timeout, G1-tuned GC flags, XDG config with project-local override. The
inter-core-module-import architecture gap is also fixed — GraalJS's dual-cache bug
(`node:`-URI vs bare-specifier cache entries for the same module) found and fixed via
specifier normalization in `CoreModuleRegistry`, and `EventEmitter` deduplicated out of
`stream.mjs`/`http.mjs`/`https.mjs` into a single shared `events.mjs` import. Native-image
build config fully removed (see below — was already noted earlier this session, reconfirmed).
The event loop is now gated to JS only (`runEventLoop()` checks `language == "js"`);
compile-to-JS languages still drain it via `JsSupport` delegation since their output runs as
JS under the hood. See "Open threads" at the end for what's still unresolved.* Earlier
2026-07-17 handoff (distribution architecture): GraalJS guest JIT confirmed already-on in
native image, GraalJS-vs-V8 and native-image-vs-JVM-mode gaps measured, the "Oracle GraalVM
1.55x" claim traced to a bad comparison and corrected; core runtime split into per-language
Gradle configurations, cutting the baked-in binary from 887MB to a 22MB core with languages
loaded on demand; G1 GC memory-reclamation flags benchmarked; setTimeout/setInterval event
loop bug fixed. Previous handoff (2026-07-16): compile-to-JS grew from 7 to 12 languages —
Gleam, Imba, Dart, Kotlin/JS, Scala.js all landed, no longer "investigated, not added";
pure-Kotlin registry infrastructure gained a content-addressable store, `HttpMetadataCache`
ETag/conditional-GET, version-aware `ToolResolver` with resolution caching. This file is the
project's institutional memory — written to survive context compaction. Treat as reliable
background, but verify against code before relying on specifics that could have drifted.*

## Daemon architecture — RESOLVED/implemented this session (was "new, unresolved" above)

Fully implemented, not just scoped. Code lives in
`library/src/main/kotlin/org/mycelium/library/daemon/` (`DaemonServer.kt`, `DaemonClient.kt`,
`DaemonPaths.kt`, `Protocol.kt`) plus `library/src/main/kotlin/org/mycelium/library/config/Config.kt`
and `app/src/main/kotlin/org/mycelium/app/App.kt`/`DaemonLauncher.kt`:

- **Unix socket transport**: `DaemonServer` listens on a `UnixDomainSocketAddress`; socket/PID
  paths resolved by `DaemonPaths` (`$XDG_RUNTIME_DIR/mycelium`, falling back to a per-user
  `/tmp/mycelium-$USER` if `XDG_RUNTIME_DIR` isn't set).
- **Shared `Engine`, per-request isolation**: one GraalVM `Engine` is reused across every
  request the daemon serves (this is the whole point — amortizing JVM/Context/Engine startup),
  but each request gets its own `Context` built with `Context.Builder.currentWorkingDirectory(Path)`
  and `.environment(Map)` so concurrent/sequential invocations from different cwds or with
  different env vars never leak state into each other. This is the same
  `currentWorkingDirectory`/`environment` builder API `Mycelium.kt` already used for
  single-shot (non-daemon) runs — the daemon path reuses it per-request rather than inventing a
  separate isolation mechanism.
- **Auto-start with config opt-out**: `mycelium run`/`mycelium <file>` auto-starts a background
  daemon on first use unless disabled; `App.kt`'s `maybeStartDaemon`-equivalent checks
  `daemonConfig.enabled` (from `Config.kt`, defaults `true`) and returns early (no daemon,
  falls back to a cold in-process run) if the user opted out via config.
- **1h idle timeout**: `DaemonServer`'s idle-check thread shuts the daemon down after
  `idleTimeoutSeconds` (default `3600L`, i.e. 1 hour) of inactivity since the last request —
  configurable via the daemon's own launch args.
- **G1-tuned GC flags applied at daemon launch**: `App.kt` launches the daemon subprocess with
  `-XX:+UseG1GC -XX:G1PeriodicGCInterval=1000 -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20`
  — the exact flags benchmarked earlier this session (see "Memory reclamation" below), now
  actually wired into the daemon's own JVM invocation rather than just a benchmarking note.
- **XDG-compliant config with project-local override**: `Config.kt` loads
  `$XDG_CONFIG_HOME/mycelium/config.toml` (default `~/.config/mycelium/config.toml`) first,
  then merges in the nearest `.mycelium/config.toml` found walking up from the working
  directory — project-local settings win over the user-global file, per the constraint scoped
  earlier this session. Config is a hand-rolled parser for the small TOML subset Mycelium
  actually needs (no external TOML dependency).
- All four constraints scoped earlier this session before implementation are satisfied: works
  with or without the daemon (opt-out via config, cold single-shot path unchanged), XDG config
  with project-local override, memory reclamation via the G1 flags, and IPC
  transport/framing/lifecycle are now actually designed and built (Unix socket + a small
  length-prefixed frame protocol in `Protocol.kt` + idle-timeout lifecycle), not just scoped.

## Inter-core-module imports — RESOLVED this session (was "architectural gap, not started")

Root cause found: GraalJS maintains **two separate module-cache entries for the same core
module** depending on how it was imported — once under the `node:`-prefixed URI form and once
under the bare specifier form (e.g. `"node:events"` vs `"events"`) — so `import { EventEmitter }
from "events"` inside one core-module shim and `import ... from "node:events"` inside another
loaded two different module instances instead of sharing one, breaking `instanceof`/shared-state
assumptions between them. Fixed with specifier normalization in `CoreModuleRegistry.kt`
(`normalizeCoreModuleSpecifiers`): every `node:`-prefixed *import/export specifier* in a core
module's source text is rewritten to the bare form before the source is handed to GraalJS, so
both forms resolve to the same cache entry. The normalization is specifier-aware (only rewrites
actual `import`/`export ... from` specifiers, not arbitrary string data that happens to contain
`"node:events"`).

With that fixed, `EventEmitter` — previously duplicated inline in every core module that needed
it — is now genuinely shared: `stream.mjs`, `http.mjs`, and `https.mjs` all `import { EventEmitter }
from "events"` from the single `events.mjs` implementation instead of carrying their own copy.
`child_process.mjs` was already importing it this way. This closes the item tracked below under
"Open threads" in the previous version of this file — see that section's note for full
before/after detail, now resolved rather than open.

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

### Distribution architecture — RESOLVED this session (was "the central unresolved question")
Previous session left four open questions: whether native-image already had (or could get)
runtime JIT for V8-like fast-startup-plus-peak-perf; whether Oracle GraalVM's reported
~1.55x throughput edge over CE reopened native-image as the throughput-competitive option;
how native-image compares to JVM mode; and how to shrink the 887MB binary. All four are now
answered with real numbers, not guesses:

1. **GraalJS already has guest JIT in native image — it's the default, not experimental.**
   The "investigate runtime JIT compilation" thread is resolved: there is nothing left to
   turn on. The Graal compiler is statically linked into the native image and JITs hot JS at
   runtime via partial evaluation, same as JVM mode. This was the mechanism the previous
   session hoped would give "V8-like" behavior in a single binary — it's already there.
2. **GraalJS vs V8 peak throughput does not close, and it's structural, not a packaging
   artifact.** Octane aggregate: V8 39,145 vs GraalJS 22,350 (~1.5-2x slower). On tight
   numeric loops GraalJS can actually win (RayTrace). On non-loop-heavy workloads the gap can
   blow out to 80x. No native-image/JVM-mode/JIT-tuning knob changes this — it's the engine.
   **Conclusion: JS performance is conceded.** The polyglot story (one process, every
   ecosystem, no FFI) is the value proposition, not JS execution speed.
3. **Native image has lower peak throughput than JVM mode, even after guest JIT warmup** —
   confirmed, not just asserted. The gap traces to Substrate VM's simpler GC, lack of
   compressed OOPs, and general HotSpot maturity that Substrate VM hasn't caught up to.
   TruffleRuby's own docs explicitly recommend `--jvm` over native-image for
   throughput-sensitive production use — this matches what Mycelium found independently.
4. **The "Oracle GraalVM ~1.55x faster than CE" claim from the previous session was wrong —
   corrected, not just re-verified.** The 1.55x number was Oracle GraalVM in **JVM mode**
   vs **OpenJDK 8** on the Renaissance benchmark (geomean) — not Oracle **native image** vs
   CE **native image**, which is the comparison that would have actually mattered for the
   native-image-vs-JVM-mode decision. Any doc or note elsewhere citing "Oracle GraalVM 1.55x
   faster than CE at peak performance" is repeating this error; there is no confirmed
   Oracle-vs-CE native-image throughput multiplier as of this session.

**Net direction**: JVM mode is the path forward, not native-image. `./gradlew :app:jvmRun`
already works (~0.9s cold startup) and is the vehicle for on-demand per-language JAR loading
(see "Architecture decisions" above — core runtime is now 22MB with zero languages baked in,
addressing the old 887MB-binary complaint independently of the native-image-vs-JVM question).
The open follow-on from this resolution is *how* to make JVM-mode startup cost disappear for
CLI-style single-shot invocations — see "Daemon architecture" in the new open threads below.

### Rust/Go auto-install gap — architecture gap, not a registry-client gap
`CratesIoClient`/`GoProxyClient` (pure-Kotlin `PackageIndex` implementations, same shape as
the npm/RubyGems/PyPI clients) exist and are fully able to resolve/download, but neither is
consulted by any automatic bare-specifier resolution yet. Fetching crates/Go modules is easy;
the hard part is integrating their compiled output into Mycelium's LLVM bitcode/WASM
pipeline: `RustSupport`/`TinyGoSupport` each compile a single source file straight to one
standalone LLVM-bitcode/WASM unit (`Compiler.compile`), with no dependency-graph resolution or
multi-unit linking step at all — there's no compile-time seam an auto-fetched crate/module's
compiled output could plug into today without that linking architecture existing first.

### Scala.js auto-download — no download `ToolSource` yet
Scala.js currently resolves via `SystemPath` only (must already be on the user's PATH via
scala-cli/sbt). Unlike Gleam (`GitHubRelease`) or Dart (`DartSdkDownload`), there's no
download-based `ToolSource` wired up for it yet. Coursier or GitHub releases might work as the
download source — natural next step, following the same pattern those two used.

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

### Daemon architecture — RESOLVED/implemented this session
See "Daemon architecture — RESOLVED/implemented this session" near the top of this file for
full detail: Unix socket transport, shared `Engine` with per-request `Context`
cwd/env isolation, auto-start with config opt-out, 1h idle timeout, G1-tuned GC flags applied
at launch, XDG config with project-local override. All constraints scoped when this thread was
first opened earlier in the same 2026-07-17 session are now satisfied.

### Memory reclamation — benchmarked this session, informs the daemon design above
G1 GC with tuned flags reclaims aggressively after a load spike: peak 1914MB → 187MB idle
within 30s, using `-XX:+UseG1GC -XX:G1PeriodicGCInterval=1000 -XX:MinHeapFreeRatio=10
-XX:MaxHeapFreeRatio=20`. Default (untuned) G1 holds onto memory the way Gradle's daemon
does — doesn't give it back to the OS promptly. **ZGC was tried and was surprisingly worse**
for this workload (held more memory, not less) — counter to the general expectation that ZGC
is the "give memory back" collector; not re-investigated further this session, just noted as
a negative result. These flags are the starting point for daemon-mode JVM tuning once the
daemon architecture above is actually built.

### Inter-core-module import problem — RESOLVED this session
See "Inter-core-module imports — RESOLVED this session" near the top of this file for full
detail: root cause was GraalJS's dual module-cache-entry bug (`node:`-prefixed URI vs bare
specifier resolving to two separate cached module instances for the same core module), fixed
via specifier normalization in `CoreModuleRegistry.kt`. `EventEmitter` is now genuinely shared
(imported from `events.mjs`) by `stream.mjs`, `http.mjs`, `https.mjs`, and `child_process.mjs`
rather than duplicated inline.

### Node stdlib `.mjs` shims — confirmed intentional, not tech debt
Revisited this session: the choice to implement Node core modules as JS-side `.mjs` shims
(rather than pure Kotlin/Java) is deliberate, not a shortcut to clean up later. JS is the
right tool for these specifically because (a) they need Java interop for I/O (file handles,
sockets, etc.) and (b) they need to express Node's callback/event semantics (`EventEmitter`,
stream backpressure, etc.), which are naturally JS-shaped. This item is closed — no action
needed, listed here only so a future session doesn't reopen it as if it were an oversight.

## User preferences (for future sessions)

- "Simplest first, sweep everything, note blockers, don't get stuck" — the general working style for compatibility sweeps.
- Node stdlib compatibility is CORE to the project, not a nice-to-have.
- Must mirror Node semantics 1:1, not approximate them.
- End goal is a self-contained binary, not a Nix-only distribution.
- User fact-checks claims — verify before relaying anything as confirmed.
- Prefers architecturally sound solutions over expedient ones (e.g. rejecting `js.commonjs-require` rather than papering over its incompatibility).

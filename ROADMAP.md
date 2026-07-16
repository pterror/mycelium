# Mycelium Roadmap

*Last updated 2026-07-16 (compile-to-JS expanded to 12 languages, pure-Kotlin registry
infrastructure, content-addressable store, version-aware tool resolution — session handoff;
see "Open threads" below for what's next). This is the forward-looking companion to
`TODO.md` — `TODO.md`
is the detailed, ground-truth session log (architecture decisions, gotchas, exact test
names); this file is the higher-altitude "what's next and in what order" view. When the
two disagree, trust `TODO.md` and fix this file.*

Mycelium's purpose: make every language's package ecosystem available to every other
language — npm, PyPI, RubyGems, crates.io, all importable from anywhere, cross-language, in
one shared GraalVM polyglot `Context`.

---

## Now (in progress)

### Node stdlib completion
Implemented as synthetic core modules backed by Java interop, cross-referencing Node/Deno/Bun
source for exact semantics (mirror Node 1:1, not approximate). **~30 modules implemented**,
with `NodeParityTest.kt` harness verifying export-by-export parity against a real Node binary.

- **Done / has parity coverage** (`NodeParityTest.kt`'s `MODULES` list, diffed against a real
  `node` binary export-by-export): `assert`, `buffer`, `child_process`, `crypto`, `events`,
  `fs`, `http`, `http2`, `https`, `net`, `os`, `path`, `process`, `querystring`, `stream`,
  `string_decoder`, `tls`, `tty`, `url`, `util`, `zlib`.
- **Implemented but not yet in the parity list**: `fs/promises` (as `fs-promises.mjs`),
  `diagnostics_channel` (real pub/sub, not a stub), `async_hooks` (synchronous-only
  `AsyncLocalStorage`, inert `createHook`/`AsyncResource`), `dns`/`dns/promises`,
  `worker_threads`, `readline`/`readline/promises`, `node:module` (`builtinModules` etc.),
  `perf_hooks`, `timers`/`timers/promises`, `util/types` (subpath of `util`). All landed
  during round-3/round-4 npm acid-test gap-filling (see below) — `NodeParityTest.kt`'s
  `MODULES` list (`library/src/test/kotlin/org/mycelium/library/NodeParityTest.kt`) simply
  hasn't been extended to cover them yet, a follow-up task, not a functionality gap.
- **Missing entirely**: `dgram`, `cluster`.
- Parity testing is deliberately not pass/fail per missing export — it's a gap-finding
  signal (`--info` output), not a gate. Hard failure is reserved for "module doesn't load in
  Mycelium at all but loads in Node" or a genuine behavioral mismatch.

### npm acid testing (27/28 passing — DONE for now)
Cross-ecosystem compatibility test harness, `NpmCompatibilityTest.kt`. Only failure is
`sharp` (native addon requiring N-API; fails gracefully with sharp's own error message
rather than crashing — fundamentally blocked until native-addon FFI support is available,
not a stdlib gap).

- **Round 1** (5/5 pass): lodash, minimist, chalk, uuid, dotenv.
- **Round 2** (5/5 pass): semver, ms, debug, axios, express — all gaps fixed (extensionless
  `main` resolution, `tty` module, recursive conditional exports, `Error.captureStackTrace`,
  `http2` module, `net`/`tls` modules, chalk subpath-import regression fixed via a
  `lastReadModuleDir` ThreadLocal tracking the last-read module's directory).
- **Round 3** (8/8 pass): ws, zod, nanoid, commander, yargs, glob, pino, inquirer. Required:
  `util.stripVTControlCharacters`/`debuglog`/`parseArgs`, array-fallback conditional exports,
  `node:fs/promises` alias, `diagnostics_channel`, `async_hooks`, `node:module`
  (`builtinModules`), `worker_threads`, `readline`, global `URL`/`URLSearchParams`,
  `buffer.constants`, legacy `global` alias for `globalThis`, and runtime CJS-export
  enumeration for inquirer's ESM-interop of `chardet` (inspects the actual `module.exports`
  object after execution rather than static analysis — same pattern as the cross-language
  shims, more correct than Node's `cjs-module-lexer` since it catches dynamic exports too).
- **Round 4** (8/10 pass): bcryptjs, moment, yaml, jsonwebtoken, pg, esbuild, socket.io,
  mongoose. Fixed: `crypto.KeyObject` now exported, `util/types` subpath module,
  `os.availableParallelism`, `require.resolve()` on the CJS shim, `dns` module,
  `perf_hooks` module, `queueMicrotask` global, `http.Server` `"listening"` event +
  `setTimeout()`. The socket.io/mongoose "Is a directory" failure was `Files.exists()`
  returning true for directories in `NodeModuleResolver`; fixed with `Files.isRegularFile()`
  — also surfaced a CJS/ESM condition-priority bug (`require` vs `import` ordering) fixed at
  the same time. Only `sharp` still fails (native addon, see above).
- **Round 5**: not started — would need to go after even heavier packages (webpack, prisma,
  next.js loader-class tooling) to find the next layer of gaps.

### Stream/Buffer/crypto API gaps
Actively being filled as npm acid tests surface concrete gaps (e.g. `util.mjs` gained
`debuglog`, `parseArgs`, `promisify.custom`, `styleText`, `formatWithOptions`,
`getSystemErrorName`, `toUSVString` this session). No known open gap tracked separately from
the acid-test punch list above — new ones surface via Round 4.

### Package infrastructure — DONE
No external package-manager binary (`npm`/`pnpm`/`pip`/`gem`) is required for any of the
following — all pure Kotlin, talking directly to each registry's HTTP API
(`library/src/main/kotlin/org/mycelium/library/registry/`):

- **`NpmRegistryClient`, `RubyGemsClient`, `PyPIClient`, `CratesIoClient`, `GoProxyClient`** —
  one `PackageIndex` implementation per ecosystem (npm, RubyGems, PyPI, crates.io, the Go
  module proxy). `Mycelium`'s constructor builds one instance of each and pairs it with its
  own `PackageInstaller` (`registryClient`/`rubyGemsClient`/`pyPIClient`/`cratesIoClient`/
  `goProxyClient` + their matching `*Installer` fields). npm and RubyGems/PyPI are wired all
  the way into automatic bare-specifier resolution (see below); `CratesIoClient`/
  `GoProxyClient` are constructed and available but not yet consulted automatically —
  `RustSupport`/`TinyGoSupport` compile a single source file straight to one standalone
  LLVM-bitcode/WASM unit with no multi-unit linking step yet, so there's no compile-time seam
  an auto-fetched crate/module's output could plug into today.
- **`PackageCache`** — content-verified (checksum), hardlink-based cache shared by every
  consumer above, keyed `XDG_CACHE_HOME/mycelium/packages/<ecosystem>/<name>/<version>`.
  Handles four archive shapes transparently (`.tgz`/PyPI sdist `.tar.gz`/crates.io `.crate` as
  a top-level-dir-stripped gzip'd tar; a PyPI wheel `.whl` as a flat ZIP; a Go module `.zip` as
  a top-level-dir-stripped ZIP; a RubyGems `.gem` as an outer tar wrapping an inner
  `data.tar.gz`), so `PackageInstaller`/`CompilerDownloader` never need to know which
  ecosystem they're installing for beyond picking the right `PackageIndex`.
- **`--yolo` auto-install for Ruby and Python, not just npm**: a bare `require`/`import` in a
  Ruby or Python guest script that can't be resolved against `$LOAD_PATH`/`sys.path` now
  fetches the gem/package from RubyGems/PyPI (`InterceptingFileSystem.yoloInstallRubyGem`/
  `yoloInstallPyPIPackage`) into a project-local vendor directory
  (`.mycelium-vendor/gems/<name>` / `.mycelium-vendor/pypi/<name>`, picked to avoid colliding
  with a real Bundler `vendor/gems` or virtualenv layout) and retries resolution — the same
  "no subprocess needed" shape npm's `--yolo` already had, extended to two more ecosystems.
  Unlike npm, there's no gem-install/pip-install subprocess fallback (no equivalent portable
  CLI story to shell out to) — a failed pure-Kotlin install just surfaces as the same "cannot
  find module" error resolution already reports when nothing resolves.
- **Self-contained package installation, real semver resolution**: `PackageInstaller`
  (manifest `package.json` deps, `--yolo` single-package installs, and now Ruby/PyPI installs
  too) and `CompilerDownloader` (compile-to-JS compiler packages) both resolve version specs
  via `SemverResolver` — a real npm-compatible range resolver (`^`/`~`/x-ranges/hyphen
  ranges/`||`/comparators, matching `node-semver`'s `maxSatisfying` closely enough for
  real-world dependency trees) against each registry's `fetchVersions`, not just an
  exact-version-or-"latest" shortcut. npm's manifest/`--yolo` paths still fall back to
  shelling out to `npm install`/`pnpm install` only on failure (e.g. a genuine network/registry
  hiccup); neither needs npm/pnpm on PATH in the common case — confirmed by
  `MyceliumIntegrationTest.kt`'s `manifestInstallRunsNpmInstall`/`yoloAutoInstallsMissingPackage`,
  no longer gated on npm being present.
- **`ToolResolver` + composable `ToolSource`** — ecosystem-agnostic "find me a tool binary"
  chain (`ProjectLocalBin` → `SystemPath` → `CachedPackage` → `RegistryDownload` →
  `GitHubRelease` → `DartSdkDownload`), extracted out of what used to be inline logic in
  `CompileToJsDispatcher`. Used for PureScript's separately-versioned `spago`, Gleam's
  GitHub-released binary, and Dart's SDK download; the natural extension point for any future
  non-npm tool resolution. Now version-aware (validates a candidate against a project's
  `package.json` constraint) with stat+mtime-based resolution caching.
- **Content-addressable store (CAS)** — per-file SHA-256 dedup layered under `PackageCache`,
  so identical files shared across different package versions/ecosystems are stored once on
  disk (hardlinked out to each consumer) instead of once per archive extraction.
- **`HttpMetadataCache`** — ETag/conditional-GET (`If-None-Match`) caching wired into every
  registry client (npm, RubyGems, PyPI, crates.io, Go proxy), so repeated metadata fetches
  (version lists, package.json-equivalents) return `304 Not Modified` instead of
  re-downloading unchanged JSON on every resolution.
- **Pure Kotlin npm registry client** — HTTP/2, streaming tarball extraction, hardlink-based
  install, and system-aware tuning (concurrency/buffer sizing based on available
  cores/memory), so npm installs don't require shelling out to `npm`/`pnpm` even for the
  "cold cache, real install" path, not just metadata resolution.

---

## Next (short term)

*Everything that was listed here as of the last update (extensionless `main`, `tty`, nested
conditional exports, chalk regression, `node:module`/`worker_threads`/`readline`/global
`URL`, Round 3 and Round 4 acid tests) has since been fixed — see "npm acid testing" above.
Only genuinely open items remain below.*

- **Remaining Node stdlib modules**: `dgram`, `cluster`. Console output improvements beyond
  GraalJS's defaults.
- **`node:fs/promises` as first-class**, not a re-export shim over `fs`.
- **`http.Agent` / `http.globalAgent`** — implemented (round 4 work); revisit only if a
  future package surfaces a gap in it.
- **`NodeParityTest.kt`'s `MODULES` list is behind the actual implemented-module set** —
  `dns`, `worker_threads`, `readline`, `node:module`, `perf_hooks`, `timers`, `util/types`
  all exist as `.mjs` files but aren't yet in the parity harness's `MODULES` list. Purely a
  test-coverage gap, not a functionality gap.
- **Parity test hardening**: edge cases, behavioral fuzzing (today's parity test mostly
  diffs *export surface*, not deep behavior).
- **Round 5 acid test**: webpack, prisma, next.js loader-class tooling — a deliberate
  difficulty step up from Round 4's already-heavy packages (esbuild passed in round 4).
- **`InterceptingFileSystem` was split into 8 focused classes** this session (see
  `library/src/main/kotlin/org/mycelium/library/`: `NodeModuleResolver.kt`,
  `CjsRuntime.kt`, `Compiler.kt`, `CoreModuleRegistry.kt`, `PythonModuleResolver.kt`,
  `RubyModuleResolver.kt`, `ShimGenerator.kt`, plus the now-515-line
  `InterceptingFileSystem.kt` itself as a thin FileSystem adapter). Pure refactor, no
  behavior change, all 216 tests still pass. **Any doc or comment referencing
  `InterceptingFileSystem.resolvePackageEntryPoint` or `resolveConditions` as living in
  `InterceptingFileSystem.kt` is stale — both now live in `NodeModuleResolver.kt`.**

---

## Medium term

### Bun/Deno compatibility
- Runtime detection is **designed but not implemented**: lockfile/config-based, in priority
  order Node → Bun → Deno (short-circuit on first match; Node signals always checked first
  since a project with mixed signals is treated as Node — the best-tested path).
- Present exactly one runtime's globals per package — never expose `Bun`/`Deno`/Node globals
  simultaneously (a package with inconsistent internal detection order would enter an
  untested mixed-codepath state).
- Bun globals/APIs: `Bun.serve`, `Bun.file`, `Bun.spawn`, etc. — not started.
- Deno globals/APIs: `Deno.readFile`, `Deno.serve`, `Deno.permissions`, etc. — not started.
- JSR registry support for Deno packages — not started.
- Strategic framing: Node compat is the trojan horse; polyglot is the payload.

### Distribution Architecture — THE big open decision (see "Open threads" below for the live question)

**Native-image vs JVM tradeoffs:**
- **Native-image (current)**: fast startup (~10ms), single binary, but cannot dynamically load Truffle languages at runtime. All languages must be compiled in at build time. Currently ~887MB with all languages on GraalVM CE (`native-image` peaks ~17GB RAM during build, capped at `-J-Xmx8g`). CE's peak throughput is slower than Oracle GraalVM's.
- **JVM mode**: slower startup (~200-500ms), requires JRE, but can dynamically load Truffle language JARs on demand. Enables lazy language loading and aligns better with the "every language on demand" vision.
- **Native-image has no dylib/shared-library support** for Truffle languages — it's monolithic AOT.
- **JVM is the more tractable path** for true on-demand language loading.
- **Key licensing insight (this session)**: Oracle GraalVM (not just GraalVM CE) is free to use
  under the GFTC (GraalVM Free Terms and Conditions) license, and is reported ~1.55x faster at
  peak throughput than CE. This reopens native-image as a viable path if the throughput gap
  was the main reason to prefer JVM mode.
- **Experimental idea worth investigating**: GraalVM native-image with *runtime* JIT
  compilation enabled (an experimental native-image feature) — would give a V8-like model in a
  single native binary: fast (~10ms) startup *and* JIT-quality peak performance, rather than
  having to choose one or the other. V8 and LuaJIT both achieve "fast startup + peak perf"
  without this kind of tradeoff at all; GraalVM should in principle be able to match that, and
  this is the concrete mechanism to test.
- **Alternative**: Oracle GraalVM in JDK mode with dynamic Truffle-language loading — smaller
  binary than native-image (languages loaded as JARs on demand, not baked in), best peak
  performance of the options considered, but back to JVM-class startup (~4-5s observed, worse
  than the 200-500ms cited above for a minimal JVM cold start — needs re-measurement).
- **What actually blocks a decision**: binary size, startup time, peak throughput, and GFTC
  license compatibility with Mycelium's own distribution — none benchmarked yet this session,
  all four need real numbers before picking a lane.

**Startup optimization (JVM path):**
- CRaC (Coordinated Restore at Checkpoint) — snapshot running JVM, restore instantly.
- GraalVM `--engine.CacheStore` — persist compiled Truffle code across runs.
- AppCDS (Application Class Data Sharing) — pre-cache class loading.
- **Open question**: how mature are these for polyglot contexts? Need benchmarking.

**Binary size strategy:**
- Do NOT bundle any Truffle language runtimes OR compilers in the core binary.
- Core binary = Mycelium runtime (Kotlin/JVM) + InterceptingFileSystem + module resolution + CLI.
- ALL languages (including JS) fetched and cached on demand when first encountered.
- Target: core binary dramatically smaller than current ~887MB.

### Python support — blocked on upstream
GraalPython crashes (`SystemError: Unable to set dict of object of type foreign`, now a host
`NullPointerException` as of GraalVM 25.0.0) when evaluating Python through a named `Source`
with a custom `FileSystem` attached — confirmed a GraalPython bug on both 24.1.1 and 25.0.0,
not a Mycelium issue. Mycelium-side fix for hard-failing on GraalPython's
`<frozen importlib._bootstrap>` synthetic identifiers already landed, independent of the
blocker. Repro in `PythonInteropExperimentTest.kt`. Next step: try a newer GraalVM/GraalPython
release, or file a minimal repro upstream.

### Cross-language import UX improvements
No specific items queued yet; expect friction points to surface as more languages/packages
get exercised.

---

## Long term / Vision

**The ultimate goal: `import anything from anywhere`.** A Python script imports a Rust
crate. A Ruby gem calls a Go function. A TypeScript file uses a Scala library. All in one
process, no FFI, no serialization, no microservices.

### Compile-to-JS languages — framework DONE, 12 languages supported
Built as a generalized compile-to-JS pipeline, not one-off wiring per language:
`CompileToJsDispatcher` (`library/src/main/kotlin/org/mycelium/library/compiler/`) resolves a
`CompilerSpec` (from `CompilerRegistry`) for the importing file's extension, then routes
through one of three `CompileToJsBackend` implementations depending on `BackendType`:

- **`BUNDLED`** (`EsbuildBackend`) — a compiler shipped with Mycelium itself, not
  resolved/downloaded at all. TypeScript is the only language here (`io.mvnpm:esbuild-java`,
  transform-only so `import`/`export` specifiers stay untouched for Mycelium's own resolver).
- **`JS_API`** (`NpmJsApiBackend`) — the npm package exposes a JS-callable
  `compile(source, options)`, `require()`'d into the shared GraalJS Context and called
  directly. CoffeeScript, Civet, LiveScript.
- **`SUBPROCESS`** (`SubprocessBackend`) — the only interface is a native compiler binary
  (no JS API at all). Elm (`elm make`), PureScript (`purs compile`), ReScript (`bsc`),
  Imba, Gleam, Dart (`dart2js`), Kotlin/JS (`kotlinc-js`), Scala.js.

**Supported languages (12):** TypeScript (bundled); CoffeeScript, Civet, LiveScript
(JS_API); Elm, PureScript, ReScript, Imba, Gleam, Dart, Kotlin/JS, Scala.js (SUBPROCESS,
several with project-aware whole-project builds — see below). All registered in
`CompilerRegistry`, all covered by `MyceliumIntegrationTest.kt`/dedicated project-aware tests.

What changed each of the languages previously listed as "investigated, not added" (see
`CompilerRegistry.kt`'s own history/comments for the exact unblock per language):
- **Imba** — routed through `SubprocessBackend` instead of `NpmJsApiBackend`, sidestepping the
  `outputIsObject` contract mismatch that blocked it originally.
- **Gleam** — compiler binary fetched via a new `GitHubRelease` `ToolSource` (Gleam ships a Rust
  binary via GitHub releases, not npm) instead of requiring an npm package.
- **Dart** — resolved via a new `DartSdkDownload` `ToolSource` that fetches the Dart SDK
  directly (no npm package needed) and runs `dart2js`.
- **Kotlin/JS**, **Scala.js** — both resolved as subprocess tools (`kotlinc-js`, scala-cli/sbt
  + linker) via `ToolResolver` rather than requiring a single JS-callable npm package.

**Fable (F#) remains the one open candidate** — see "Open threads" below.

**Version-aware tool resolution**: `ToolResolver`'s `ToolSource` chain
(`ProjectLocalBin` → `SystemPath` → `CachedPackage` → `RegistryDownload` → `GitHubRelease` →
`DartSdkDownload`) now validates a resolved candidate binary's actual version against a
project's own `package.json` constraint (`findPackageJsonConstraint`) before accepting it,
not just "first thing found on PATH." Resolution results are cached keyed by
stat+mtime, so repeated compiles of the same project don't re-probe every `ToolSource` in
the chain each time.

**Resolution order actually implemented** (`CompileToJsDispatcher.compile`,
`CompilerDownloader`, `ToolResolver`):
1. Pre-built output already exists — handled implicitly upstream (an imported package whose
   `package.json` already points at plain `.js` never reaches the compile-to-JS path at all).
2. A project file found walking up from the source (`elm.json`; `spago.yaml`/`spago.dhall`;
   `rescript.json`/`bsconfig.json`) — routes through `ProjectBuildRunner`'s whole-project
   build instead of single-file compilation (real cross-module imports need this).
3. Already installed in the compiled file's own project's `node_modules`.
4. Already downloaded to Mycelium's own compiler cache (`XDG_CACHE_HOME/mycelium/compilers/
   <lang>/<version>/`) from a previous run.
5. Neither — download now via the pure-Kotlin `NpmRegistryClient` + `PackageCache` (no `npm`
   subprocess), for this and every future compile of that language+version.

**Project-aware builds**: Elm/PureScript/ReScript compile a whole project (not just the
imported file) when a real project config is found above it — `elm make`, `spago build`
(PureScript's spago is a *separate* npm package, `spago`, resolved independently via
`ToolResolver`/composable `ToolSource`s — project `node_modules/.bin` → system PATH →
Mycelium's cache → auto-download), and `rescript build`. Falls back to single-file
compilation (`purs compile`, bare `bsc`) with real, documented limitations when no project
file exists (see each `CompilerSpec` registration's CAVEAT comments).

**CLI**: `mycelium compiler fetch <lang>[@version]` / `compiler list` / `compiler cache`
(`app/src/main/kotlin/org/mycelium/app/App.kt`) operate on the same `CompilerRegistry`/
`CompilerDownloader` a running Mycelium instance uses, for eager pre-fetching and cache
introspection.

**Open question: Is Mycelium a CLI tool or a runtime?**
- CLI tool: startup time matters (native-image wins).
- Runtime/server: throughput matters (JVM is competitive, within 10-20% of C).
- Likely both — JVM with startup optimizations may be the pragmatic answer.

**Still open**: module resolution semantics per language beyond what's already handled,
source-map fidelity for cross-language debugging, error attribution, and richer
per-language build-cache invalidation (today: content-hash of source text + fixed version).

### Compile-to-WASM languages
Any language with a WASM target runs via the existing `WebAssembly` JS-API-based
infrastructure (see Go, below, for the pattern).
- Go (via tinygo) — **done**.
- Rust (currently via LLVM/Sulong) — could also target WASM; not needed today.
- C/C++ (currently via LLVM/Sulong) — could also target WASM via emscripten; not needed
  today.
- Zig — planned.
- AssemblyScript — planned.
- Swift (SwiftWasm) — planned.
- Kotlin/WASM — planned.
- OCaml (via `wasm_of_ocaml`) — planned.
- Brython — planned.

### Compile-to-LLVM languages
Already supported via GraalVM's LLVM runtime (Sulong) and the same `InterceptingFileSystem`
shimming mechanism used for Ruby/C.
- C — **done**.
- Rust — **done** (required `--crate-type=lib`; `rustc` now in the flake devShell).
- C++, Objective-C — planned.
- Fortran (via `flang`) — planned.
- Crystal — planned.
- Haskell (via GHC's LLVM backend) — planned.

### GraalVM native languages
Direct Truffle support, no compile step.
- JavaScript — **done** (the core language).
- Ruby — **done**.
- Python — **blocked upstream** (see Medium term).
- R (FastR) — GraalVM project exists, not yet integrated.
- Espresso (Java-on-Truffle) — run Java as a guest language; not yet integrated.

### Native addon / FFI support
Enables calling native compiled code (C, C++, Rust) from polyglot contexts. Currently blocks
packages like `sharp` (image processing) and `bcrypt` (native binding) from working — 27 of 28
npm acid-test packages would pass if this were available.

- **N-API compatibility layer** — **LOW PRIORITY**. ~400 API functions, massive effort. Both
  Bun and Node are reference implementations, but N-API's sprawling scope (covering errno
  handling, UTF-8 validation, native object wrapping, async job queues, etc.) makes this a
  major undertaking. Before pursuing this, strongly consider WASM alternatives for most
  use-cases (WASM FFI is safer and more portable).

- **Bun FFI** (`bun:ffi`) — **medium priority**, potentially lower effort than N-API. Bun's FFI
  API is more purposeful and smaller in scope — it provides a direct wrapper around dlopen/dlsym
  semantics without the overhead of Node's object-wrapping machinery. Has working reference
  implementation in Bun's source.

- **Deno FFI** (`Deno.dlopen`) — **medium priority**, potentially lower effort than N-API.
  Deno's FFI is similarly focused and well-scoped. Working reference implementation available
  in Deno's source code.

**Strategic framing**: Deferred beyond short-term roadmap because (1) WASM is preferred for
new libraries, (2) most high-value npm packages that truly require native code are rare, and
(3) N-API's surface area suggests tackling either Bun or Deno FFI first as a proof of concept.

### Distribution
- Nix flake — **current**, but flake-only is explicitly considered unacceptable as the sole
  distribution channel.
- Native image — **current**, ~887MB, self-contained but oversized.
- JVM fat jar (`installDist`) — **current fallback**, ~709MB, needs a JRE.
- Homebrew formula — planned.
- Docker image — planned.
- npm global install (`npx mycelium run app.js`) — planned; the irony is intentional.
- Standalone per-platform binary (Linux/macOS/Windows) — the actual goal; neither current
  option (native-image size, fat-jar's JRE dependency) is fully satisfying yet.

---

## Moonshots

- Full Node version compatibility: run Node 12 code and Node 22 code in the same runtime.
- Package-level runtime sandboxing — a permissions model à la Deno, but per-package.
- Cross-language type inference: TypeScript types → Python type hints → Ruby Sorbet.
- Universal package manager: `mycelium install lodash pydantic rails activerecord` — one
  command, any ecosystem.
- Hot-reload across languages.
- LSP/IDE support: jump-to-definition across language boundaries.

---

## Open threads (this session's handoff)

1. **Distribution architecture — THE big decision.** See "Distribution Architecture" above for
   the full writeup. Summary: currently native-image on GraalVM CE (887MB, ~10ms startup,
   slower peak perf than the alternatives). Oracle GraalVM is free (GFTC) and ~1.55x faster
   than CE at peak throughput. Two directions to investigate next session: (a) GraalVM
   native-image with experimental runtime JIT compilation, which could give V8-like
   fast-startup-plus-peak-perf in one native binary; (b) Oracle GraalVM JDK mode with dynamic
   Truffle-language loading — smaller binary, best peak perf, but ~4-5s startup. Need real
   benchmarks on binary size, startup time, peak performance, and to confirm GFTC license
   compatibility before picking a lane.
2. **Rust/Go auto-install** — `CratesIoClient`/`GoProxyClient` registry clients exist and can
   resolve/download, but there's no linking stage for compiled output yet; `RustSupport`/
   `TinyGoSupport` each compile a single source file straight to one standalone unit with no
   multi-unit/dependency linking, so there's no seam an auto-fetched crate/module's compiled
   output could plug into today. Architecture gap, not a registry-client gap.
3. **Scala.js auto-download** — resolved via `SystemPath` only right now; no download
   `ToolSource` (no GitHub-release or SDK-download equivalent wired up) for Scala.js yet, unlike
   Gleam (`GitHubRelease`) or Dart (`DartSdkDownload`).
4. **Fable (F# → JS)** — the closest remaining compile-to-JS candidate, still blocked because
   `@fable-org/fable-compiler-js`'s npm `bin` only runs via its own `#!/usr/bin/env node`
   shebang, requiring a real external Node install (the one thing every other compile-to-JS
   language here avoids). Possible unblock to explore: run Fable's compiler on GraalJS itself
   instead of shelling out to a real `node` binary.
5. **Kotlin/JS as an in-process JVM library call** — currently invoked as a `SUBPROCESS`
   (`kotlinc-js`), but since Mycelium is itself a Kotlin/JVM application, it may be possible to
   invoke the Kotlin/JS compiler as an in-process library call instead of shelling out, saving
   process-spawn overhead per compile.

## Open design questions

These don't have an owner or a target yet — tracked so they aren't lost.

- **GraalVM version ceiling**: pinned at 25.0.0 — the highest version where
  `polyglot:ruby`/`org.graalvm.ruby:ruby` and the old `polyglot:llvm` coordinate are still
  published on Maven Central (25.1.3 drops them). `org.graalvm.polyglot:ruby` is already
  marked deprecated upstream ("retained only for Maven compatibility"), so this ceiling may
  become a forced migration rather than a choice. Revisit when upstream either publishes
  matching 25.1.x Ruby/LLVM artifacts or confirms they're gone for good.
- **Distribution weight**: is GraalVM's ~700–900MB footprint simply the acceptable cost of
  the polyglot architecture, or is a trimming strategy (e.g. a smaller native-image profile,
  module exclusion) worth investing in before pursuing Homebrew/Docker/npm distribution?

---

## Working style (carried over from `TODO.md`)

- Simplest first, sweep everything, note blockers, don't get stuck.
- Node stdlib compatibility is core to the project, not a nice-to-have.
- Must mirror Node semantics 1:1, not approximate them.
- End goal is a self-contained binary, not a Nix-only distribution.
- Prefer architecturally sound solutions over expedient ones (e.g. rejecting
  `js.commonjs-require` outright rather than papering over its incompatibility with
  Mycelium's cross-language import mechanism).

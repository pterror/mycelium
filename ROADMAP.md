# Mycelium Roadmap

*Last updated 2026-07-17 (session #3: Node stdlib now effectively complete — `dgram` and
`cluster` implemented (the only two modules previously missing entirely), 14
niche/deprecated modules added (`constants`, `domain`, `punycode`, `sys`, `repl`,
`trace_events`, `v8`, `wasi`, `inspector`, `inspector/promises`, `node:test`,
`node:test/reporters`, `node:sea`, `node:sqlite`), and `NodeParityTest.kt`'s `MODULES` list
extended to cover every implemented module (52 dynamic parity tests) — closing the
test-coverage gap flagged in the previous session. Module count is now 50+ (up from ~30).
Scala.js auto-download landed via a `GitHubRelease` `ToolSource` for scala-cli, which
required teaching `ToolResolver` to extract a bare `.gz`-compressed binary (scala-cli's own
release-asset convention, unlike Gleam's `.tar.gz`) — closes the "Scala.js auto-download"
open thread from the previous session. Stream/path submodules (`stream/promises`,
`stream/consumers`, `stream/web`, `path/posix`, `path/win32`) plus a real `console` module,
and a fix for the `readline`/`readline-promises` circular-import issue, were in flight as
this update was written — see "Open threads" below for current status. Previous update
(session #2: the daemon architecture — the previous update's "open
follow-on question" — is now DONE, not just scoped: Unix socket server, shared `Engine` with
per-request `Context` cwd/env isolation, auto-start with config opt-out, 1h idle timeout,
G1-tuned GC flags applied at launch, XDG config with project-local override. The
inter-core-module-import gap (`EventEmitter` duplicated across `stream.mjs`/`http.mjs`) is
also fixed — root cause was a GraalJS dual-cache bug between `node:`-prefixed and bare module
specifiers, fixed via specifier normalization. Native-image removal is reconfirmed complete.
The event loop is now gated to JS-language evaluations only. See "Open threads" below — items
2 and 3 from the previous update are now resolved.) Previous update, same day (distribution
architecture — the roadmap's long-standing "big open decision" — resolved with real
benchmarks: GraalJS guest JIT confirmed already-on in native image, JS-vs-V8 and
native-image-vs-JVM-mode throughput gaps measured, the "Oracle GraalVM 1.55x faster than CE"
claim corrected, and the 887MB-binary problem solved by splitting languages into on-demand
Gradle configurations. Net direction: JVM mode, not native-image.) Previous update
(2026-07-16): compile-to-JS expanded to 12 languages, pure-Kotlin registry infrastructure,
content-addressable store, version-aware tool resolution. This is the forward-looking
companion to `TODO.md` — `TODO.md` is the detailed, ground-truth session log (architecture
decisions, gotchas, exact test names); this file is the higher-altitude "what's next and in
what order" view. When the two disagree, trust `TODO.md` and fix this file.*

Mycelium's purpose: make every language's package ecosystem available to every other
language — npm, PyPI, RubyGems, crates.io, all importable from anywhere, cross-language, in
one shared GraalVM polyglot `Context`.

---

## Now (in progress)

### Node stdlib completion — effectively DONE this session
Implemented as synthetic core modules backed by Java interop, cross-referencing Node/Deno/Bun
source for exact semantics (mirror Node 1:1, not approximate). **50+ modules implemented**
(up from ~30), with `NodeParityTest.kt` harness verifying export-by-export parity against a
real Node binary, and its `MODULES` list now covering the full implemented set (52 dynamic
parity tests).

- **Done / has parity coverage** (`NodeParityTest.kt`'s `MODULES` list, diffed against a real
  `node` binary export-by-export): `assert`, `buffer`, `child_process`, `crypto`, `events`,
  `fs`, `http`, `http2`, `https`, `net`, `os`, `path`, `process`, `querystring`, `stream`,
  `string_decoder`, `tls`, `tty`, `url`, `util`, `zlib`, `dns`, `dns/promises`,
  `worker_threads`, `readline`, `readline/promises`, `node:module`, `perf_hooks`, `timers`,
  `timers/promises`, `util/types`, `dgram`, `cluster`, `constants`, `domain`, `punycode`,
  `sys`, `repl`, `trace_events`, `v8`, `wasi`, `inspector`, `inspector/promises`, `node:test`,
  `node:test/reporters`, `node:sea`, `node:sqlite`. Landed this session and the previous one
  (round-3/round-4 npm acid-test gap-filling, then a dedicated stdlib-completion pass) —
  `NodeParityTest.kt`'s `MODULES` list (`library/src/test/kotlin/org/mycelium/library/NodeParityTest.kt`)
  now tracks the implemented set 1:1, closing what was previously a follow-up test-coverage gap.
- **In flight as of this update, not yet landed**: `stream/promises`, `stream/consumers`,
  `stream/web`, `path/posix`, `path/win32`, and a real `console` module, plus a fix for the
  `readline`/`readline-promises` circular-import issue — see "Open threads" below.
- **Nothing left missing entirely** — `dgram` and `cluster` (the last two modules with no
  implementation at all) both landed this session.
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

### Event loop gated to JS only — DONE this session
`Mycelium.kt`'s `runEventLoop()` previously drained timers/microtasks unconditionally after
every top-level evaluation, regardless of language — harmless for JS, but wasteful and
conceptually wrong for GraalVM-native non-JS languages (Ruby, C/LLVM, Python once unblocked)
that have no JS-style event loop. Now gated on `language == "js"`. Compile-to-JS languages
(TypeScript, Gleam, Dart, etc.) are unaffected — their compiled output runs *as* JS through
`JsSupport`, so they still drain the loop via that delegation path.

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
`URL`, Round 3 and Round 4 acid tests, the two missing modules `dgram`/`cluster`, the 14
niche/deprecated modules, and the `NodeParityTest.kt` coverage gap) has since been fixed —
see "npm acid testing" and "Node stdlib completion" above. Only genuinely open items remain
below.*

### Truffle Lua implementation — DONE (this entry was stale, corrected in this update)
This entry previously read "high priority" as if unstarted. **It's done**: a full
from-scratch Lua 5.1 interpreter (own Gradle module, `lua/src/main/java/org/mycelium/lua/`, not
a GraalVM-published language) landed across `dfbd1b0` ("feat: Truffle Lua 5.1 — from-scratch
interpreter with polyglot interop") through `a470176` ("fix: Lua 5.1 hardening — 3 bug fixes +
6 missing stdlib functions"), covering:
- Parser + AST with Truffle specializations
- Table implementation with metatables/metamethods
- Standard libraries: string, math, table, io, os (Lua 5.1 surface, not 5.3+ — 5.1 was the
  deliberate target: smaller spec, the industrial-embedding baseline)
- Coroutines
- 105+ Lua-5.1-spec-compliance tests plus acid tests against 6 real-world pure-Lua packages
  (`inspect`, `middleclass`, `json`, `30log`, `serpent`, `fun`, `lume`, `luaunit` — see
  `lua/src/test/resources/lua-libs/`)
- Wired into `Mycelium.kt`'s default language list (`LuaSupport`) alongside JS/Ruby/Python/C/
  Rust/TinyGo, with `jsImportsLua` in `MyceliumIntegrationTest.kt` proving JS can import a
  `.lua` module.

**What's newly open** (not previously tracked, and not part of the original "high priority"
scope above): Lua as the *host* doing a cross-language import — as opposed to Lua as the
import *target*, which `jsImportsLua` already proves works — is unverified.
`LuaSupport.generateShim` exists but nothing exercises it from the Lua side, because Lua has no
`require`-equivalent wired up to Mycelium's cross-language resolver yet. Worth either
implementing that direction or documenting the asymmetry more prominently (see `TODO.md`'s
"Truffle Lua implementation — DONE" entry for the same finding in more detail).

- **In-flight, not yet landed**: stream/path submodules (`stream/promises`,
  `stream/consumers`, `stream/web`, `path/posix`, `path/win32`), a real `console` module, and
  a fix for the `readline`/`readline-promises` circular-import issue.
- Console output improvements beyond GraalJS's defaults (the `console` module above covers
  the module-surface gap; deeper formatting fidelity is still open).
- **`node:fs/promises` as first-class**, not a re-export shim over `fs`.
- **`http.Agent` / `http.globalAgent`** — implemented (round 4 work); revisit only if a
  future package surfaces a gap in it.
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
- Bun runtime detection: **implemented** (`BunRuntime.kt`) — lockfile/config-based
  (`bun.lockb`/`bunfig.toml`, Node signals `package-lock.json`/`pnpm-lock.yaml`/`yarn.lock`
  checked first and win outright) plus a `bun:`-prefixed import/require specifier anywhere
  in the source as an independent signal. Deno detection is not implemented at all yet — the
  three-way Node → Bun → Deno priority order is still the intended design, but only the Node/
  Bun half of it exists in code today.
- Bun's globals are layered on top of Node's, not exclusive with them — Node-compat globals
  (`process`/`Buffer`/`URL`/...) stay injected unconditionally regardless of Bun detection,
  matching real Bun (which itself implements most of Node's global surface). The "never
  expose more than one runtime's globals at once" principle below is about Bun vs. Deno,
  not about hiding Node compat from a detected-Bun script.
- Present exactly one of {Bun, Deno} globals per package — never expose both `Bun` and
  `Deno` globals simultaneously (a package with inconsistent internal detection order would
  enter an untested mixed-codepath state).
- Bun globals/APIs: **substantially more than "first slice" now** — `Bun.serve` (including
  server-side WebSocket support), `Bun.file`, `Bun.write`, `Bun.spawn`/`spawnSync`, `Bun.$`
  (shell), `Bun.Glob`, `Bun.connect`/`Bun.listen`/`Bun.udpSocket`, `Bun.hash` (most
  sub-algorithms), `Bun.CryptoHasher`, `Bun.password` (PBKDF2, not real Bun's argon2id/bcrypt —
  documented divergence), `Bun.deepEquals`, `Bun.escapeHTML`, `Bun.inspect`, `Bun.sleep`/
  `sleepSync`, `Bun.nanoseconds`, `Bun.which`, `Bun.randomUUIDv7`, gzip/deflate helpers,
  `Bun.stringWidth`, `Bun.resolveSync`/`resolve`, `Bun.pathToFileURL`/`fileURLToPath`, `Bun.env`/
  `argv`/`version`/`revision` are all implemented. Structurally still missing:
  `bun:ffi`/`Bun.dlopen` (needs a JVM FFI mechanism — unrelated to Node's N-API), `bun:sqlite`
  (needs a JVM SQLite binding, e.g. `sqlite-jdbc` — same open question as Node's own
  `node:sqlite`), `Bun.build`, `Bun.Transpiler`, `bun:test`, `Bun.S3`/`Bun.redis`/`Bun.SQL`,
  client-side `new WebSocket()`. See `docs/reference/bun-compat.md`'s "Not supported" section
  for the full, checked-against-upstream list (smaller gaps: TLS/unix sockets, permessage-
  deflate, keep-alive, backpressure/`drain`, a few hash algorithms, `Bun.peek`, `Bun.Glob`
  extglob negation, non-ASCII header mangling, and more — not re-enumerated here).
- Deno globals/APIs: `Deno.readFile`, `Deno.serve`, `Deno.permissions`, etc. — **not started at
  all**, including detection logic (no `deno.lock`/`deno.json(c)`/URL-import sniffing exists
  yet either).
- JSR registry support for Deno packages — not started.
- Strategic framing: Node compat is the trojan horse; polyglot is the payload.

### Distribution Architecture — RESOLVED this session; JVM mode is the direction

This was the roadmap's long-standing "big open decision." All four numbers the previous
session flagged as blocking (binary size, startup time, peak throughput, native-image vs JVM
mode) are now measured, and one claim carried over from the previous session (Oracle GraalVM
"~1.55x faster than CE") has been traced to a bad comparison and corrected. Full detail and
sourcing in `TODO.md`'s "Open threads → Distribution architecture" section; summary here:

- **GraalJS guest JIT is already the default in native image, not an experimental feature to
  investigate.** The Graal compiler is statically linked into the native image and JITs hot JS
  at runtime via partial evaluation — the exact "V8-like fast-startup-plus-peak-perf in one
  binary" mechanism the previous session hoped to unlock by *enabling* runtime JIT. There is
  nothing left to turn on; this closes that investigation.
- **GraalJS is structurally ~1.5-2x slower than V8 in aggregate** (Octane: V8 39,145 vs
  GraalJS 22,350), and the gap can blow out to 80x on non-loop-heavy workloads (tight numeric
  loops are the exception, where GraalJS can win — e.g. RayTrace). This doesn't change with
  native-image vs JVM mode or any JIT-tuning flag — it's the engine, not the packaging.
  **JS performance is conceded**; the polyglot story (every ecosystem, one process, no FFI) is
  the value proposition Mycelium is actually selling.
- **Native-image has lower peak throughput than JVM mode even after guest JIT warmup** —
  confirmed, not assumed. Root cause: Substrate VM's simpler GC, no compressed OOPs, and
  general HotSpot maturity Substrate VM hasn't matched. TruffleRuby's own docs recommend
  `--jvm` over native-image for throughput-sensitive production use, independently confirming
  the same conclusion Mycelium reached by benchmarking.
- **Correction: the "Oracle GraalVM ~1.55x faster than CE" claim from the previous session was
  wrong.** That number was Oracle GraalVM in *JVM mode* vs *OpenJDK 8* on Renaissance
  (geomean) — not Oracle **native image** vs CE **native image**, which is the comparison that
  would actually have mattered here. There is no confirmed Oracle-vs-CE native-image
  throughput multiplier. Any surviving reference elsewhere to "1.55x faster than CE" is this
  same error and should be treated as retracted.
- **The 887MB binary-size problem is solved**, independent of the native-image-vs-JVM question:
  languages are now split into per-language Gradle configurations rather than baked into one
  binary. Core runtime is **22MB** (19 JARs) with **zero** languages included. GraalJS alone
  adds 47MB (8 JARs). Full polyglot (every language) is 709MB across 71 JARs — same ballpark
  as before, but now opt-in per deployment (`-Planguages=js,ruby,python`) rather than forced.
- **`./gradlew :app:jvmRun` already works** — confirmed this session, ~0.9s cold startup. This
  is JVM mode, already functional, and is the vehicle the on-demand per-language JAR loading
  above depends on (native-image cannot dynamically load a Truffle language at runtime — see
  below — so on-demand loading is JVM-mode-only by construction).

**Net direction:** JVM mode, not native-image. The open follow-on is startup-cost amortization
for CLI-style single-shot invocations, now scoped as an optional daemon — see "Open threads"
below.

**Follow-up (this session): native-image build config physically removed.** The
`org.graalvm.buildtools.native` plugin and `graalvmNative { }` block are gone from
`app/build.gradle.kts` — it wasn't just de-emphasized, it no longer exists. `./gradlew
:app:jvmRun` (and plain `:app:run` for the bare core) are now the only run paths; see
`TODO.md`'s "Native-image removed" section for the detail.

**Structural facts that don't change with the above** (native-image vs JVM tradeoffs, for
reference):
- **Native-image**: fast startup (~10ms cited previously, not re-measured against the new
  split-JAR core this session), single binary, but cannot dynamically load Truffle languages
  at runtime — languages must be compiled in at build time. No dylib/shared-library support
  for Truffle languages; it's monolithic AOT.
- **JVM mode**: ~0.9s cold startup (measured this session via `:app:jvmRun`), requires a JRE,
  but can dynamically load Truffle language JARs on demand — the split-JAR
  `-Planguages=...` mechanism above is built on this. This is now the chosen path.

**Startup optimization (JVM path) — daemon mode: DONE.**
- **Daemon mode — implemented this session** (`library/src/main/kotlin/org/mycelium/library/daemon/`:
  `DaemonServer.kt`, `DaemonClient.kt`, `DaemonPaths.kt`, `Protocol.kt`). Unix socket transport,
  one shared GraalVM `Engine` reused across requests with a fresh per-request `Context`
  (`currentWorkingDirectory`/`environment` builder isolation, same API the non-daemon path
  already used), auto-start on `mycelium run`/`mycelium <file>` with a config opt-out
  (`daemon.enabled = false` in `config.toml`), 1h default idle timeout, and the G1 GC flags
  benchmarked below now actually applied at daemon-subprocess launch in `App.kt`. XDG config
  (`~/.config/mycelium/config.toml`) with project-local override (`.mycelium/config.toml`,
  found walking up from cwd) via `Config.kt`. All constraints scoped when this item was first
  opened (with/without daemon, XDG+override config, memory reclamation, IPC/lifecycle design)
  are satisfied — see `TODO.md`'s "Daemon architecture — RESOLVED/implemented this session" for
  full detail.
- CRaC (Coordinated Restore at Checkpoint) — snapshot running JVM, restore instantly. Not
  investigated; would only be worth revisiting if the daemon proves insufficient for some
  use case (e.g. environments where a long-lived background process isn't wanted at all).
- GraalVM `--engine.CacheStore` — persist compiled Truffle code across runs. Not investigated.
- AppCDS (Application Class Data Sharing) — pre-cache class loading. Not investigated.

**Memory reclamation (benchmarked this session, feeds daemon-mode tuning):**
G1 GC with `-XX:+UseG1GC -XX:G1PeriodicGCInterval=1000 -XX:MinHeapFreeRatio=10
-XX:MaxHeapFreeRatio=20` reclaims aggressively after a load spike — peak 1914MB → 187MB idle
within 30s. Default (untuned) G1 holds onto memory, similar to how Gradle's daemon behaves.
ZGC was tried and was surprisingly *worse* for this workload (held more memory) — a negative
result worth remembering before reaching for ZGC as the obvious "give memory back" choice.

**Binary size strategy — DONE (see above):**
- No Truffle language runtimes or compilers in the core binary — implemented via per-language
  Gradle configurations, not just a stated intent.
- Core binary = Mycelium runtime (Kotlin/JVM) + InterceptingFileSystem + module resolution +
  CLI — measured at 22MB.
- Languages (including JS) loaded on demand via `-Planguages=...`, not fetched-and-cached at
  first encounter as originally envisioned — the mechanism landed as a build-time
  configuration choice rather than a runtime fetch; runtime on-demand fetching of a language
  runtime itself (as opposed to a language's *packages*) is not implemented and not currently
  planned to be — worth flagging if that distinction matters for a future session.

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

**Open question: Is Mycelium a CLI tool or a runtime? — settled this session's earlier
distribution-architecture work.**
- CLI tool: startup time matters — native-image would have won on raw cold-start, but its
  peak throughput trails JVM mode even after guest JIT warmup, and it can't dynamically load
  per-language JARs (the mechanism the 887MB-binary fix depends on). The native-image build
  config has since been removed from the project entirely. The daemon (see "Daemon
  architecture" in Open threads, now implemented) is the answer for CLI-style startup cost
  instead: amortize JVM/Context startup across invocations rather than eliminating JVM startup
  via a different build mode.
- Runtime/server: throughput matters (JVM is competitive, within 10-20% of C).
- **Answer: JVM mode for both**, with the daemon covering the CLI-startup-cost concern that
  used to be native-image's main argument.

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
- **Lua — done** (its own from-scratch Truffle language, `lua/` Gradle module, not GraalVM's;
  this row was stale until this update — see "Truffle Lua implementation" under "Next" below,
  which has been corrected from "high priority, not started" to "done").
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
- **Native image — removed this session.** ~887MB with everything baked in, confirmed lower
  peak throughput than JVM mode even after guest JIT warmup, and incompatible with the
  on-demand per-language-JAR loading architecture (see "Distribution Architecture" above). The
  build config (`org.graalvm.buildtools.native` plugin, `graalvmNative { }`) is gone from
  `app/build.gradle.kts`, not just de-prioritized.
- **JVM mode (`installDist` / `:app:jvmRun`) — now the chosen direction.** Split into a 22MB
  core (zero languages) plus per-language Gradle configurations loaded via
  `-Planguages=js,ruby,python,...` (GraalJS alone: 47MB); full polyglot is 709MB, same as the
  old fat-jar figure but now opt-in rather than forced. Needs a JRE. ~0.9s cold startup
  confirmed via `:app:jvmRun`; a daemon mode (see Open threads) is the planned way to amortize
  that further for CLI-style usage.
- Homebrew formula — planned.
- Docker image — planned.
- npm global install (`npx mycelium run app.js`) — planned; the irony is intentional.
- Standalone per-platform binary (Linux/macOS/Windows) — still the long-run goal for a
  single-file artifact; the split-JAR JVM-mode approach above is the near-term answer to "how
  do I not ship 887MB," not a substitute for eventually having one.

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

1. **Distribution architecture — RESOLVED this session.** See "Distribution Architecture"
   above for the full writeup. Net direction: JVM mode, not native-image (native-image's peak
   throughput trails JVM mode even with guest JIT warmed up; GraalJS itself trails V8 by a
   structural, unfixable-by-packaging 1.5-2x in aggregate, so JS performance is conceded and
   the polyglot story is the value prop). The 887MB binary problem is solved independently via
   per-language Gradle configurations (22MB core, languages on demand). The "Oracle GraalVM
   1.55x faster than CE" claim from the prior session was a bad comparison (JVM-mode-vs-OpenJDK-8,
   not native-image-vs-native-image) and is retracted. **What replaces this as the open
   question**: see "Daemon architecture" below — JVM mode's ~0.9s cold start is now the
   remaining cost to amortize for CLI-style usage.
2. **Daemon architecture — RESOLVED/implemented this session (later in the same 2026-07-17
   day as item 1 above).** Unix socket server (`DaemonServer.kt`), one shared `Engine` reused
   across requests with per-request `Context` isolation via
   `Context.Builder.currentWorkingDirectory()`/`.environment()`, auto-start with a config
   opt-out, 1h idle timeout, and the G1 GC tuning flags benchmarked earlier this session now
   actually applied at daemon-subprocess launch (not just benchmarked in isolation). XDG
   config (`~/.config/mycelium/config.toml`) with project-local override
   (`.mycelium/config.toml`) implemented in `Config.kt`. IPC transport (Unix socket + a small
   framed protocol in `Protocol.kt`), request framing, and process lifecycle (start/stop/idle
   shutdown) are all designed and built, not just scoped. See `TODO.md` for full detail.
3. **Inter-core-module import problem — RESOLVED this session.** Root cause: GraalJS caches
   the same core module twice — once under its `node:`-prefixed URI and once under the bare
   specifier — so two shims importing the "same" module via different specifier forms got two
   independent instances. Fixed via specifier normalization in `CoreModuleRegistry.kt`
   (`normalizeCoreModuleSpecifiers`, rewrites `node:`-prefixed import/export specifiers to
   bare form before the source reaches GraalJS). `EventEmitter` is now genuinely shared:
   `stream.mjs`, `http.mjs`, and `https.mjs` all `import { EventEmitter } from "events"`
   instead of carrying inline duplicates.
4. **Rust/Go auto-install** — `CratesIoClient`/`GoProxyClient` registry clients exist and can
   resolve/download, but there's no linking stage for compiled output yet; `RustSupport`/
   `TinyGoSupport` each compile a single source file straight to one standalone unit with no
   multi-unit/dependency linking, so there's no seam an auto-fetched crate/module's compiled
   output could plug into today. Architecture gap, not a registry-client gap.
5. **Scala.js auto-download — RESOLVED this session.** A `GitHubRelease` `ToolSource` for
   scala-cli is now wired into Scala.js's `ToolSource` chain
   (`ProjectLocalBin` → `SystemPath` → `CachedPackage` → `RegistryDownload` → `GitHubRelease`),
   matching the pattern Gleam and Dart already used. Required teaching `ToolResolver`
   (`extractPlainGzip`) to handle a bare `.gz`-compressed binary as a distinct archive shape
   from `.tar.gz`/`.tgz` — scala-cli's own GitHub release assets (e.g.
   `scala-cli-x86_64-pc-linux-static.gz`) are the raw binary gzip'd directly, not wrapped in a
   tarball the way Gleam's release assets are.
6. **Fable (F# → JS)** — the closest remaining compile-to-JS candidate, still blocked because
   `@fable-org/fable-compiler-js`'s npm `bin` only runs via its own `#!/usr/bin/env node`
   shebang, requiring a real external Node install (the one thing every other compile-to-JS
   language here avoids). Possible unblock to explore: run Fable's compiler on GraalJS itself
   instead of shelling out to a real `node` binary.
7. **Kotlin/JS as an in-process JVM library call** — currently invoked as a `SUBPROCESS`
   (`kotlinc-js`), but since Mycelium is itself a Kotlin/JVM application, it may be possible to
   invoke the Kotlin/JS compiler as an in-process library call instead of shelling out, saving
   process-spawn overhead per compile.
9. **HttpClient resource leak causing full-suite `:library:test` OOM — HIGH PRIORITY, newly
   found.** Each of `BunUtilTest`/`BunGlobShellTest`/`BunSocketTest`/`BunCompatTest`/
   `BunFuzzTest`/`BunWriteSpawnTest`/`NodeParityTest`/`MyceliumIntegrationTest` passes alone
   (376 tests, 0 failures), but running them together in one Gradle test JVM triggers a
   non-terminating `OutOfMemoryError` cascade from `HttpClient-NNN-SelectorManager`/
   `HTTP-Dispatcher`/`idle-timeout-task` threads (`NNN` climbing into the hundreds) — the full
   suite currently can't complete in one invocation. Confirmed via git history (`1bd6e41`) that
   `BunFuzzTest.kt`'s `by lazy val httpClient` (switched from `HttpURLConnection` because it
   rejects `PATCH`) is a plausible leak source — jqwik constructs a fresh test instance per
   property iteration, so this may be one un-closed `HttpClient` per iteration. JDK is 21
   (`flake.nix`), where `HttpClient` is `AutoCloseable`, so a fix (`.use { }` or explicit
   close in `@AfterEach`) should be straightforward once confirmed. Not yet checked: whether
   `Bun.serve`/`listen`/`udpSocket` shim servers in other tests are all stopped too. Full
   detail in `TODO.md`'s matching entry.
10. **Test suite housekeeping** — two smaller items found alongside the OOM investigation:
    fixed port 3000 in several `Bun.serve`/`Bun.listen` tests collides across concurrent test
    runs (e.g. parallel worktrees) — one test already `assumeTrue`-skips when 3000 is taken,
    others could use an ephemeral port instead; and the suite's live-`bun`-subprocess parity
    checks run with `--no-daemon` per invocation, making full runs slow — batching probes into
    fewer subprocess calls (as `tooling/generate-bun-mime-table.mjs` already does) or tagging
    live-parity tests separately from fast in-shim tests are both options if this becomes
    painful.
11. **Daemon socket sharing/staleness — needs investigation, not confirmed as a real bug.** One
    observed incident: a `mycelium` invocation from one worktree was served by a stale daemon
    from a different worktree with a different language-pack set, returning a wrong-but-
    plausible result instead of an error. Daemon socket path appears fixed
    (`/run/user/1000/mycelium/daemon.sock`); unclear whether the existing version handshake (see
    "Daemon architecture" item 2 above) also covers config/language-pack identity, or just
    binary version. Possibly an artifact of the concurrent-agent-worktree sandbox this was
    observed in rather than a general-workflow bug — not established either way. Full detail
    and candidate fixes in `TODO.md`'s matching entry.
12. **Lua as cross-language import host — unverified, newly found.** Lua as the import *target*
    is proven (`jsImportsLua`); Lua as the *host* doing an import of another language is not —
    `LuaSupport.generateShim` exists but nothing exercises it, since Lua has no
    `require`-equivalent wired to the cross-language resolver. See the corrected "Truffle Lua
    implementation" entry under "Next" above (this whole item was stale until this update — Lua
    is actually done, not "high priority, not started").
13. **Stream/path submodules + console, and the readline circular-import fix — DONE (this entry
    was stale).** This entry previously said "in flight, not yet landed" — confirmed in-tree
    this session: `stream-promises.mjs`, `stream-consumers.mjs`, `stream-web.mjs`,
    `path-posix.mjs`, `path-win32.mjs`, and `console.mjs` all exist under
    `library/src/main/resources/node-stdlib/` (matches `TODO.md`'s "landed (`eb0611a`)" note).

## Open design questions

These don't have an owner or a target yet — tracked so they aren't lost.

- **GraalVM version ceiling**: pinned at 25.0.0 — the highest version where
  `polyglot:ruby`/`org.graalvm.ruby:ruby` and the old `polyglot:llvm` coordinate are still
  published on Maven Central (25.1.3 drops them). `org.graalvm.polyglot:ruby` is already
  marked deprecated upstream ("retained only for Maven compatibility"), so this ceiling may
  become a forced migration rather than a choice. Revisit when upstream either publishes
  matching 25.1.x Ruby/LLVM artifacts or confirms they're gone for good.
- **Distribution weight — largely resolved this session.** The ~700-900MB footprint is not an
  acceptable-cost-of-polyglot fact of life after all: splitting into a 22MB core plus
  per-language Gradle configurations (`-Planguages=...`) means most deployments never pay the
  full weight — only an intentional full-polyglot build does. What's still open: whether to
  also pursue trimming *within* a single language's JAR set (e.g. GraalJS's own 47MB), and
  whether Homebrew/Docker/npm distribution should ship the slim core with languages fetched
  on first use, or a few pre-bundled "common languages" variants — not decided yet.

---

## Working style (carried over from `TODO.md`)

- Simplest first, sweep everything, note blockers, don't get stuck.
- Node stdlib compatibility is core to the project, not a nice-to-have.
- Must mirror Node semantics 1:1, not approximate them.
- End goal is a self-contained binary, not a Nix-only distribution.
- Prefer architecturally sound solutions over expedient ones (e.g. rejecting
  `js.commonjs-require` outright rather than papering over its incompatibility with
  Mycelium's cross-language import mechanism).

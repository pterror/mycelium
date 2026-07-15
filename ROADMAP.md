# Mycelium Roadmap

*Last updated 2026-07-16. This is the forward-looking companion to `TODO.md` — `TODO.md`
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
source for exact semantics (mirror Node 1:1, not approximate).

- **Done / has parity coverage** (`NodeParityTest.kt`'s `MODULES` list, diffed against a real
  `node` binary export-by-export): `assert`, `buffer`, `child_process`, `crypto`, `events`,
  `fs`, `http`, `http2`, `https`, `net`, `os`, `path`, `process`, `querystring`, `stream`,
  `string_decoder`, `tls`, `tty`, `url`, `util`, `zlib`.
- **Implemented but not yet in the parity list**: `fs/promises` (as `fs-promises.mjs`),
  `diagnostics_channel` (real pub/sub, not a stub), `async_hooks` (synchronous-only
  `AsyncLocalStorage`, inert `createHook`/`AsyncResource`).
- **Missing entirely**: `worker_threads`, `readline`, `node:module` (at least
  `builtinModules`), `dns`, `dgram`, `cluster`.
- Parity testing is deliberately not pass/fail per missing export — it's a gap-finding
  signal (`--info` output), not a gate. Hard failure is reserved for "module doesn't load in
  Mycelium at all but loads in Node" or a genuine behavioral mismatch.

### npm acid testing (rounds 1–3 complete, round 4 next)
- **Round 1** (5/5 pass, unpinned versions): lodash, minimist, chalk, uuid, dotenv.
- **Round 2** (2/5 pass): `semver` works; `ms`, `debug`, `axios`, `express` fail — see gaps
  below. Chalk also has a pre-existing flake risk (`sourceDirectoryStack` empty for subpath
  imports against latest chalk).
- **Round 3** (quick-win fixes landed): `ws`, `zod`, `nanoid` already worked. `commander`,
  `yargs`, `glob`, `pino`, `inquirer` were failing and got partial or full fixes this round —
  each still has one more layer to peel (see "Next" below).
- **Round 4** (not started): harder real-world packages — webpack, esbuild, prisma, next.js
  loader, etc.

### Stream/Buffer/crypto API gaps
Actively being filled as npm acid tests surface concrete gaps (e.g. `util.mjs` gained
`debuglog`, `parseArgs`, `promisify.custom`, `styleText`, `formatWithOptions`,
`getSystemErrorName`, `toUSVString` this session). No known open gap tracked separately from
the acid-test punch list above — new ones surface via Round 4.

---

## Next (short term)

- **Remaining Node stdlib modules**: `worker_threads`, `readline`, `node:module`, `dns`,
  `dgram`, `cluster`. Console output improvements beyond GraalJS's defaults.
- **`node:fs/promises` as first-class**, not a re-export shim over `fs`.
- **`http.Agent` / `http.globalAgent`** — not yet implemented.
- **Parity test hardening**: edge cases, behavioral fuzzing (today's parity test mostly
  diffs *export surface*, not deep behavior).
- **Fix remaining Round 3 acid-test failures**, in the order they were found:
  1. `node:module` core module (at least `builtinModules`) — unblocks `yargs` fully.
  2. `worker_threads` core module (stub acceptable) — unblocks `pino` fully.
  3. `readline` core module — unblocks `inquirer` fully.
  4. Expose `URL`/`URLSearchParams` as JS globals, not just importable from `url` — unblocks
     `glob`/`minimatch`/`path-scurry` (they construct `new URL(...)` at module scope).
- **Older Round 2 gaps still open** (simplest → hardest):
  1. Extensionless `"main"` field resolution — probe `.js`/`.json`/`index.js` like Node does
     (`InterceptingFileSystem.resolvePackageEntryPoint`). Unblocks `ms`.
  2. `tty` core module — **done** (now in the parity `MODULES` list above; TODO.md's older
     "known gaps" note predates this).
  3. Recursive/nested conditional-exports resolution in `package.json`'s `"exports"` field
     (`ENTRY_POINT_RESOLVER` only descends one level today). Unblocks `axios`.
  4. Chalk subpath-import regression — `sourceDirectoryStack` `ThreadLocal` appears empty by
     the time GraalJS resolves a subpath import; flake risk for the whole
     `NpmCompatibilityTest` suite since no test pins versions.
- **Round 4 acid test**: webpack, esbuild, prisma, next.js loader-class tooling — a
  deliberate difficulty step up from Round 3's app-level packages.

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

### Native-image binary size reduction
Currently ~887MB (`native-image` peaks ~17GB RAM during build, capped at `-J-Xmx8g`). No
concrete plan yet — open question whether GraalVM's weight is simply the cost of admission
or a lighter packaging path exists.

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

### Compile-to-JS languages
Any language that compiles to JS runs on Mycelium today via the existing esbuild-based
TS pipeline pattern, and gets full JS-ecosystem access plus every other language Mycelium
supports.
- TypeScript — **done**.
- CoffeeScript, Civet — planned.
- ReScript, ReasonML — planned.
- Elm — planned.
- PureScript — planned.
- ClojureScript — planned.
- Scala.js — planned.
- Kotlin/JS — planned.
- Dart (dart2js) — planned.

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

## Open design questions

These don't have an owner or a target yet — tracked so they aren't lost.

- **Package manager auto-install**: npm bare-specifier resolution works against an existing
  `node_modules`, but nothing auto-installs — users must `npm install` first. Eager
  (install-on-import, magical but unpredictable) vs. declarative (manifest file, explicit but
  more friction) is unresolved.
- **pip / gem resolution**: no analogue to npm's `node_modules` walk exists yet for Python
  (blocked in practice by the GraalPython bug) or Ruby (unblocked, just not built).
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

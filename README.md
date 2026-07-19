# Mycelium

Mycelium is a polyglot runtime built on GraalVM that unifies package ecosystems across
languages in a single, shared JavaScript context. Instead of picking one language to get
one ecosystem, you `import` npm, PyPI, RubyGems, crates.io, and Go modules from the same
program — and call across languages directly, no IPC or serialization involved.

```js
import { add, hello } from "./hello.c";       // C, compiled to LLVM bitcode
import { greet } from "./greet.rb";            // Ruby
import lodash from "lodash";                   // npm, auto-installed on first import
import { multiply } from "./multiply.rs";      // Rust, compiled to LLVM bitcode

hello();
console.log(lodash.chunk([1, 2, 3, 4], 2));
Polyglot.eval("python", "print('hello from python')");
```

Everything runs inside one GraalVM `Context`: guest languages call each other's functions
as regular values, not across a process boundary.

## Status

**Pre-alpha, actively evolving, not published anywhere.** There is no release yet — you run
it from source via Gradle. Expect rough edges: Python-as-host is currently blocked by a
GraalPython crash (guest-to-guest `Polyglot.eval("python", ...)` works; running a `.py` file
directly does not), native-addon npm packages requiring N-API are unsupported (they fail
gracefully rather than crashing), and dependency resolution/mod-loader depend-and-breaks
metadata is parsed but not yet enforced. See `TODO.md` (ground-truth session log) and
`ROADMAP.md` (forward-looking plan) for the current, detailed picture — they're kept
up to date every session and are more reliable than any snapshot in this README.

What *is* solid: Node stdlib coverage (50+ modules with export-parity tests against a real
Node binary), a real npm/CJS/ESM resolver (27/28 packages in the compatibility acid test
suite pass, including express, fastify, mongoose, pino, and yargs), 17 importable
languages, and a background daemon that amortizes JVM/GraalVM startup across runs.

## Key features

- **Cross-language imports.** Import a `.rb`, `.py`, `.c`, `.rs`, `.go`, or compile-to-JS
  source file directly from JavaScript, the same way you'd import a `.mjs` file. A custom
  GraalVM `FileSystem` intercepts reads for unrecognized extensions and hands them to the
  right compiler/shim generator.
- **Package ecosystems, unified.** Registry clients and installers for npm, PyPI,
  RubyGems, and crates.io/Go-proxy (the latter two resolve, not yet auto-wired into
  imports). `--yolo` auto-installs a bare specifier that doesn't resolve; the default mode
  installs whatever a `package.json` already declares, with a pure-Kotlin resolver
  (semver-aware, no subprocess) that falls back to `npm`/`pnpm` only if that fails.
- **Node.js compatibility.** 50+ core modules (`fs`, `http`, `crypto`, `stream`, `net`,
  `worker_threads`, `readline`, ...) implemented as synthetic modules backed by Java
  interop and cross-checked against a real Node binary. `process`, `Buffer`, `URL`,
  `TextEncoder`/`TextDecoder`, `crypto.randomUUID`, `queueMicrotask`, and
  `Error.captureStackTrace` are polyfilled as bare globals the way Node exposes them.
- **17 importable languages**, spanning three execution strategies:
  - **Native Truffle languages** — JavaScript/TypeScript, Ruby, Python (guest-eval only).
  - **Compiled to LLVM bitcode/WASM** and loaded through GraalVM's LLVM/WebAssembly
    runtimes — C (clang), Rust (rustc), Go (via TinyGo → WASM).
  - **Compiled to plain JS** and executed as an ordinary ES module — CoffeeScript, Civet,
    LiveScript, Elm, PureScript, ReScript, Dart (dart2js), Kotlin/JS, Scala.js, Gleam,
    Imba — via npm-API, subprocess, or auto-downloaded-tool compiler backends, with a
    content-addressable cache keyed by source hash.
- **A background daemon** (Unix socket, shared `Engine` with per-request `Context`
  isolation) that auto-starts on first `mycelium run` and amortizes JVM/GraalVM startup
  across invocations — configurable via `~/.config/mycelium/config.toml` or
  `.mycelium/config.toml`, with an idle timeout and G1-tuned GC flags. JVM-level features
  (`--insight`, mods) bypass the daemon automatically since they need process-level control
  the daemon's shared `Context` can't provide.
- **A JVM-level mod loader** (`-javaagent`-based bytecode patching of Truffle/GraalJS
  runtime classes at class-load time) for changing language semantics itself — e.g.
  null-safe property access, strict `==`, C-style integer division, proper tail calls via
  trampolining, and GraalJS's experimental operator-overloading API exposed as a JS global.
  See `docs/mod-authoring.md` for the patch DSL and a full authoring guide.
- **GraalVM Insight** instrumentation script support (`--insight=<script>`) for tracing/
  profiling guest execution.

## Building and running

Mycelium is a Kotlin/Gradle project targeting a GraalVM JDK. The Nix flake (`flake.nix`)
provisions GraalVM CE, a JDK-21-hosted Gradle 8 (needed because Gradle's embedded Kotlin DSL
compiler can't parse newer JDK version strings), clang, rustc, and tinygo — enter the
project directory with direnv/`nix develop` and everything needed is on `PATH`.

```bash
# Run a file (JVM mode, JS language pack only — the default)
./gradlew :app:jvmRun --args="run path/to/file.mjs"

# Add more language packs as needed
./gradlew :app:jvmRun --args="run path/to/file.rb" -Planguages=js,ruby
# available packs: js, ruby, python, java, llvm, wasm

# Run tests
./gradlew test

# Manage the daemon directly
./gradlew :app:jvmRun --args="daemon status"

# Load JVM-level mods (see docs/mod-authoring.md)
./gradlew :app:jvmRun --args="run path/to/file.mjs" -Pmod
```

CLI flags: `--yolo` (auto-install unresolved bare imports), `--no-install`
(skip `package.json`-driven auto-install), `--insight=<script>` (attach an Insight
script), `--operator-overloading` (enable GraalJS's operator-overloading API).

There is no native-image build — it was removed (incompatible with on-demand
per-language-JAR loading, ~17GB to build, produced an ~887MB binary). JVM mode
(`:app:run` / `:app:jvmRun`) is the only supported way to run Mycelium; see
`ROADMAP.md` for the reasoning.

## Architecture

- **`library/`** — the runtime itself: `Mycelium.kt` builds and owns the GraalVM
  `Context`, wires up cross-language `Polyglot.eval`/`evalModule`, and injects the
  Node-compat globals. `InterceptingFileSystem` and friends resolve imports, dispatch
  compile-to-JS languages, generate per-language export shims, and drive package
  installation. `registry/` holds one client + installer pair per package ecosystem.
  `daemon/` implements the background server and its wire protocol. `node-stdlib/` holds
  the JS-source Node core-module polyfills, loaded via Java interop.
- **`app/`** — the CLI entry point (`App.kt`): argument parsing, subcommand dispatch
  (`run`, `compiler`, `daemon`, `version`), and the daemon-vs-in-process routing decision.
- **`mod-loader/`** — the generic Java agent (bytecode-patching DSL over ASM) that loads
  and applies mods described by a `mod.json` manifest.
- **`example-mod/`, `null-safe-mod/`, `strict-equality-mod/`, `integer-division-mod/`,
  `operator-overloading-mod/`, `ptc-mod/`** — working example mods, each a standalone
  Gradle module producing its own jar.
- **`buildSrc/`** — shared Gradle Kotlin-DSL convention plugins.
- **`docs/`** — mod-authoring guide plus research notes (Java modding systems survey,
  Mixin evaluation, Truffle-mod-loader feasibility).

One design point worth calling out: cross-language imports work by intercepting
GraalVM's `FileSystem` — the only officially sanctioned interception point in the
polyglot API (there's no module-resolver or `Source`-level hook) — rather than by
patching GraalJS's module loader or embedding a separate Node.js binary. Everything
Mycelium does with imports flows through that one seam.

## Contributing

`TODO.md` is the ground-truth project log — architecture decisions, gotchas, exact test
names, and open threads, updated every session. `ROADMAP.md` is the higher-altitude
"what's next" view. When they disagree, `TODO.md` wins. `docs/mod-authoring.md` covers the
bytecode-patching mod system in depth; `docs/research/` has write-ups on paths that were
evaluated and rejected (worth reading before re-proposing them).

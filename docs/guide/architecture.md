# Architecture

Mycelium is a Kotlin/Gradle multi-module project. The module split mirrors a fairly hard
architectural boundary: one shared runtime, a thin CLI shell on top of it, one from-scratch
guest-language implementation, and a set of standalone bytecode-patching mods.

## Module map

- **`library/`** — the runtime itself. `Mycelium.kt` builds and owns the GraalVM `Context`,
  wires up cross-language `Polyglot.eval`/`evalModule`, and injects the Node-compat globals
  (`process`, `Buffer`, `URL`, `TextEncoder`/`TextDecoder`, `crypto.randomUUID`,
  `queueMicrotask`, `Error.captureStackTrace`). `InterceptingFileSystem` and its supporting
  classes resolve imports, dispatch compile-to-JS languages, generate per-language export
  shims, and drive package installation. `registry/` holds one client + installer pair per
  package ecosystem (npm, RubyGems, PyPI, crates.io, Go proxy). `daemon/` implements the
  background server and its wire protocol. `node-stdlib/` holds the JS-source Node
  core-module polyfills, loaded via Java interop.
- **`app/`** — the CLI entry point (`App.kt`): argument parsing, subcommand dispatch (`run`,
  `compiler`, `daemon`, `version`), and the daemon-vs-in-process routing decision. Also owns
  the `jvmRun` Gradle task that assembles a classpath from `library`'s per-language-pack
  Gradle configurations (see [Getting Started](./getting-started)).
- **`lua/`** — a from-scratch Lua 5.1 implementation on GraalVM Truffle, written in Java
  (not Kotlin — Truffle's DSL annotation processor targets Java sources): lexer/parser, AST
  nodes with Truffle specializations, `LuaTable` (the unified array/hash structure with
  metatables), coroutines, and the `string`/`math`/`table`/`io`/`os`/`debug`/`package`
  standard libraries. Registered as language ID `lua`, loaded via the `luaLang` Gradle
  configuration exactly like any GraalVM-shipped language pack.
- **`mods/mod-loader/`** — the generic Java agent (bytecode-patching DSL over ASM) that
  loads and applies mods described by a `mod.json` manifest.
- **`mods/example-mod/`, `mods/null-safe-mod/`, `mods/strict-equality-mod/`,
  `mods/integer-division-mod/`, `mods/operator-overloading-mod/`, `mods/ptc-mod/`** — six
  working example mods, each a standalone Gradle module producing its own jar. See
  [Mods](../mods/) for what each one does.
- **`buildSrc/`** — shared Gradle Kotlin-DSL convention plugins.

## The one interception seam: `FileSystem`

Cross-language imports work by intercepting GraalVM's `FileSystem` — the only officially
sanctioned interception point in the polyglot API (there's no module-resolver hook, no
`Source`-level hook). `InterceptingFileSystem` sits in front of every guest-language file
read: for a recognized extension (`.rb`, `.py`, `.c`, `.rs`, `.go`, a compile-to-JS
extension, ...), it hands the read to the right compiler/shim generator instead of letting
GraalJS see the raw file. Everything Mycelium does with imports — resolution, compilation,
package installation, shim generation — flows through that one seam, rather than through
patching GraalJS's own module loader or embedding a separate Node.js binary (both evaluated
and rejected; see `TODO.md`'s "Architecture decisions" section for the reasoning).

As the file grew, it was split (while behavior-preserving — a pure refactor, all tests
still passing) into focused classes, all still under
`library/src/main/kotlin/org/mycelium/library/`:

- `InterceptingFileSystem.kt` — thin `FileSystem` adapter + orchestration; owns the
  stateful bits (`sourceDirectoryStack`, `lastReadModuleDir`).
- `NodeModuleResolver.kt` — `node_modules` walk, `package.json`
  `exports`/`imports`/`main` resolution.
- `CjsRuntime.kt` — `require()`, the module cache, CJS→ESM shim generation.
- `Compiler.kt` — LLVM/WASM compilation and loading.
- `CoreModuleRegistry.kt` — the Node-stdlib core-module map.
- `PythonModuleResolver.kt` / `RubyModuleResolver.kt` — `sys.path`/`$LOAD_PATH` resolution
  for those two guest languages.
- `ShimGenerator.kt` — cross-language ESM shim generation (turns another language's
  exported members into `Polyglot.import(...)`-backed JS bindings).

## Package ecosystems

`library/src/main/kotlin/org/mycelium/library/registry/` holds one `PackageIndex`
implementation per ecosystem — `NpmRegistryClient`, `RubyGemsClient`, `PyPIClient`,
`CratesIoClient`, `GoProxyClient` — each talking directly to that ecosystem's HTTP API, no
external package-manager binary (`npm`/`pip`/`gem`) required. `Mycelium`'s constructor
builds one instance of each and pairs it with its own `PackageInstaller`. npm and
RubyGems/PyPI are wired all the way into automatic bare-specifier resolution (including
`--yolo` auto-install); `CratesIoClient`/`GoProxyClient` resolve but aren't yet consulted
automatically, since `RustSupport`/`TinyGoSupport` compile a single source file straight to
a standalone unit with no multi-unit linking step yet for an auto-fetched dependency to
plug into.

Supporting infrastructure shared across all five clients:

- **`PackageCache`** — content-verified (checksum), hardlink-based, content-addressable at
  the per-file level (SHA-256), keyed `XDG_CACHE_HOME/mycelium/packages/<ecosystem>/<name>/
  <version>`. Handles four archive shapes transparently (npm `.tgz`, PyPI sdist/crates.io
  `.tar.gz`, a PyPI wheel `.whl` as a flat ZIP, a Go module `.zip`, a RubyGems `.gem`'s
  nested tar).
- **`HttpMetadataCache`** — ETag/conditional-GET caching layered onto every registry client,
  so re-resolving an already-seen version range returns `304 Not Modified` instead of
  re-downloading metadata.
- **`HttpImportCache`** (`library/.../HttpImportCache.kt`) — caches `import
  'https://esm.sh/lodash'`-style URL imports to disk under
  `XDG_CACHE_HOME/mycelium/http-imports`, keyed by a SHA-256 of the URL. Deliberately no
  revalidation — a URL import is treated as immutable once fetched.
- **`RemoteImportCache`** (`library/.../RemoteImportCache.kt`) — the same on-disk,
  content-addressed cache shape generalized to any `RemoteFileSystem` read (S3, GCS,
  WebDAV, ...), not just plain HTTP.

## Composition root

`Mycelium` (the class) constructs every collaborator its object graph needs — one
`PackageIndex` per ecosystem, each paired with its own `PackageInstaller`, plus
`CompilerDownloader`/`CompilerRegistry`/`CompileToJsDispatcher` — in its own constructor
body, and threads them down to whatever needs them. Downstream classes depend only on
interfaces (`PackageIndex`, `CompilerRegistry`, `ToolResolver`) and constructor parameters,
never on a concrete `*Client` — so a caller embedding Mycelium as a library, or a test, can
substitute a fake at exactly one point (`Mycelium`'s own constructor).

## Distribution: JVM mode, not native-image

Each Truffle language is its own Gradle configuration, loaded on demand via
`-Planguages=js,ruby,python,...` — the core runtime JAR set bakes in zero languages (22MB
across 19 JARs). A native-image build was prototyped and removed: native-image can't
dynamically load a Truffle language JAR at runtime, which conflicts directly with the
on-demand per-language-JAR loading model above, and it cost ~17GB RAM to build for an
~887MB binary. `./gradlew :app:jvmRun` (JVM mode) is the only supported way to run
Mycelium today.

## The background daemon

A Unix-socket server sharing one GraalVM `Engine` across requests, with per-request
`Context` cwd/env isolation, amortizes JVM/GraalVM startup cost across invocations
(auto-starts on first `run`, ~0.9s cold `jvmRun` startup otherwise). Configurable via
`~/.config/mycelium/config.toml` or a project-local `.mycelium/config.toml`; has an idle
timeout and G1-tuned GC flags. JVM-level features (`--insight`, mods) bypass the daemon
automatically — a `-javaagent` or attached instrument needs process-level control the
daemon's shared `Context` can't provide.

## The mod loader

`mods/mod-loader/` is a `-javaagent`-based Java agent that patches Truffle/GraalJS runtime
classes' bytecode at class-load time (an ASM-backed DSL, not a runtime AOP layer), for
changing guest-language semantics that the polyglot API itself has no hook for — see
[Mods](../mods/) for what ships, and the [mod authoring guide](../mod-authoring) for the
patch DSL itself.

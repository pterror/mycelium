# Getting Started

Mycelium is pre-alpha and not published anywhere — there is no release artifact, no
`npm install -g mycelium`. You build and run it from source, via Gradle, every time.

## Prerequisites

Mycelium targets a GraalVM JDK. The repo ships a Nix flake (`flake.nix`) that provisions
everything needed — GraalVM CE, a JDK-21-hosted Gradle 8 (Gradle's embedded Kotlin DSL
compiler can't parse newer JDK version strings, so the Gradle daemon itself runs on JDK 21
while the project's own Java toolchain still targets GraalVM's JDK), `clang` (for the C
language pack), `rustc` (for Rust), and `tinygo` (for Go). Enter the project directory with
direnv or `nix develop` and all of it is on `PATH`.

If you aren't using the flake, you need a GraalVM JDK installed yourself, and `clang`/
`rustc`/`tinygo` on `PATH` specifically for the C/Rust/Go language packs — every other
language pack (JS, Ruby, Python, Lua, and the compile-to-JS languages) has no external
compiler dependency beyond what Gradle itself resolves.

## Building

```bash
./gradlew build
```

## Running a script

Mycelium runs in JVM mode via the `jvmRun` Gradle task — this is the only supported way to
run it. There is no native-image build; it was tried and removed (incompatible with
on-demand per-language-JAR loading, ~17GB to build, produced an ~887MB binary — see
[Architecture](./architecture)).

```bash
# Run a file (JS language pack only — the default when -Planguages is omitted)
./gradlew :app:jvmRun --args="run path/to/file.mjs"
```

### Enabling language packs

Each Truffle language lives in its own Gradle configuration and is opt-in per invocation via
`-Planguages=<comma-separated list>`. The core runtime carries zero languages by default;
`jvmRun` only pulls in `js` because it defaults to `-Planguages=js` when the property is
omitted entirely.

```bash
# Run a Ruby file (pass both packs explicitly — omitting -Planguages is the only way
# the js-only default kicks in)
./gradlew :app:jvmRun --args="run path/to/file.rb" -Planguages=js,ruby

# Run a Lua file
./gradlew :app:jvmRun --args="run path/to/file.lua" -Planguages=lua
```

Available pack names (`app/build.gradle.kts`'s `languagePackConfigNames`): `js`, `ruby`,
`python`, `java`, `llvm`, `wasm`, `lua`. Passing an unknown name fails fast with a
`GradleException` listing the valid set. `llvm` covers both C and Rust (both compile to
LLVM bitcode); `wasm` covers Go, compiled via TinyGo to WASM rather than LLVM bitcode (see
the `TinyGoSupport` doc comment in `Languages.kt` for why there's no Go-to-LLVM path).

Compile-to-JS languages (CoffeeScript, Civet, LiveScript, Elm, PureScript, ReScript, Dart,
Kotlin/JS, Scala.js, Gleam, Imba) don't need their own Gradle configuration — they compile
down to plain JS and execute through the `js` pack, with the compiler itself fetched/
invoked on demand per language. See [Language support](../reference/languages).

### CLI flags

- `--yolo` — auto-install a bare import specifier that doesn't resolve (npm, RubyGems, and
  PyPI all support this; crates.io/Go-proxy resolve but aren't auto-wired into imports yet).
- `--no-install` — skip `package.json`-driven auto-install.
- `--insight=<script>` — attach a GraalVM Insight instrumentation script for tracing/
  profiling guest execution.
- `--operator-overloading` — enable GraalJS's experimental operator-overloading API.

## Running tests

```bash
./gradlew test
```

## The background daemon

`./gradlew :app:jvmRun --args="run ..."` auto-starts a background daemon (Unix socket, a
shared GraalVM `Engine` with per-request `Context` isolation) on first use, amortizing
JVM/GraalVM startup across subsequent invocations. It's configurable via
`~/.config/mycelium/config.toml` or a project-local `.mycelium/config.toml`, has an idle
timeout, and applies G1-tuned GC flags. Check its status directly:

```bash
./gradlew :app:jvmRun --args="daemon status"
```

JVM-level features — `--insight` and mods (see [Mods](../mods/)) — bypass the daemon
automatically, since they need process-level control (a `-javaagent`, an attached
instrument) that the daemon's shared `Context` can't provide.

## Loading JVM-level mods

```bash
./gradlew :app:jvmRun --args="run path/to/file.mjs" -Pmod
```

See [Mods](../mods/) for what ships today, and [the mod authoring guide](../mod-authoring)
for how to write your own.

## Status and known gaps

Worth reading before you hit these yourself:

- **Python-as-host is blocked.** `Polyglot.eval("python", ...)` from another guest language
  works; running a `.py` file directly crashes GraalPython. See
  [Language support](../reference/languages) for details.
- **Native-addon npm packages (N-API) are unsupported** — they fail gracefully with the
  package's own error rather than crashing Mycelium.
- **Mod dependency/conflict metadata (`depends`/`breaks` in `mod.json`) is parsed but not
  enforced yet.**

`TODO.md` (ground-truth session log) and `ROADMAP.md` (forward-looking plan) in the repo
root are kept up to date every session and are the most reliable source for exactly what
works today.

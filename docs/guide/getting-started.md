# Getting Started

Mycelium is pre-alpha and not published anywhere — there is no release artifact, no
`npm install -g mycelium`. You build and run it from source, via Gradle, every time.

## Prerequisites

Mycelium targets a GraalVM JDK. You'll need to install a few things yourself:

1. **A GraalVM JDK.** `library/build.gradle.kts` pins the GraalVM Maven artifacts
   (`truffle-api`, `polyglot`, and the per-language jars) to **25.0.0** — the highest version
   where all of the language packs Mycelium uses (js/python/java/ruby/wasm/llvm) are
   simultaneously published to Maven Central. Grab a matching GraalVM build (25.0.x) from
   the [GraalVM downloads page](https://www.graalvm.org/downloads/) or a JDK version manager
   like SDKMAN (`sdk install java 25.0.2-graal`, or check `sdk list java` for the current
   identifier).
2. **A plain JDK 21 for Gradle itself.** Gradle 8's embedded Kotlin DSL compiler can't parse
   the version string a JDK-25-based GraalVM reports, so the Gradle daemon needs to run on a
   separate JDK 21 — point `JAVA_HOME` at that JDK 21 before invoking `./gradlew`. The
   project's own Kotlin/Java toolchain still targets language level 21 (see
   `buildlogic.kotlin-common-conventions.gradle.kts`) and resolves against your GraalVM
   install via `GRAALVM_HOME`: set that env var to your GraalVM's install directory —
   `gradle.properties` points Gradle's toolchain resolution
   (`org.gradle.java.installations.fromEnv=GRAALVM_HOME`) at it. Toolchain auto-download is
   disabled (`org.gradle.java.installations.auto-download=false`), so both JDKs need to
   already be installed with `JAVA_HOME`/`GRAALVM_HOME` pointing at them — Gradle won't fetch
   anything on its own.
3. **`clang`, `rustc`, and `tinygo` on `PATH`** — only if you want the C, Rust, or Go
   language packs, respectively. Every other language pack (JS, Ruby, Python, Lua, and the
   compile-to-JS languages) has no external compiler dependency beyond what Gradle itself
   resolves.

::: tip If you use Nix
`flake.nix` provisions all of the above already wired up — GraalVM CE, a JDK-21-hosted
Gradle 8, `clang`, `rustc`, `tinygo`, and `JAVA_HOME`/`GRAALVM_HOME` pointed at the right
places. Enter the project directory with direnv or run `nix develop` and everything above is
on `PATH` with no manual setup. This is a convenience, not a requirement — everything it
provides can be installed by hand as described above.
:::

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

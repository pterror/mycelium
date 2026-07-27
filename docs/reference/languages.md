# Language support

Mycelium ships 18 importable languages across three execution strategies, defined in
`library/src/main/kotlin/org/mycelium/library/Languages.kt` (one `LanguageSupport` object
per language/family) and enabled per invocation via `-Planguages=...` (see
[Getting Started](../guide/getting-started)). This page tracks real capabilities and real
limitations — not aspirations.

## Native Truffle languages

These run directly on a GraalVM Truffle language implementation — no separate compile step.

| Language | Extensions | Gradle pack | Notes |
|---|---|---|---|
| JavaScript / TypeScript | `.js`, `.mjs`, `.ts`, `.mts` | `js` | The host language everything else's cross-language imports ultimately funnel through. TypeScript is transpiled via esbuild (types/syntax stripped only, `bundle=false`, so `import`/`export` specifiers stay untouched for Mycelium's own resolver). |
| Ruby | `.rb` | `ruby` | TruffleRuby. `require 'https://...'`/`require 'http://...'` is monkeypatched onto `Kernel#require` to support HTTP(S) requires the same way JS's `import` does. |
| Python | `.py` | `python` | **Guest-eval only.** `Polyglot.eval("python", ...)` from another guest language works; running a `.py` file directly (Mycelium's normal named-`Source`-plus-custom-`FileSystem` mode) crashes GraalPython 24.1.1 with `SystemError: Unable to set dict of object of type foreign`, after the guest code has already run. This is an upstream GraalPython bug, not something fixable from Mycelium's side. See `PythonInteropExperimentTest` for the investigation and a live repro. |
| Lua 5.1 | `.lua` | `lua` | Implemented completely from scratch in `lua/` (Java, not Kotlin — Truffle's DSL annotation processor targets Java) — lexer/parser, `LuaTable`/`LuaFunction`, coroutines, and the `string`/`math`/`table`/`io`/`os`/`debug`/`package` standard libraries. Acid-tested against real Lua libraries (luaunit, penlight, middleclass, json, inspect, serpent). Guest-eval and standalone-file execution both work. **Cross-language member import doesn't yet** — `import { add } from "./foo.lua"` — because `LuaTable`/`LuaFunction` don't export `InteropLibrary` yet, so a polyglot `Value` wrapping either exposes no members/executability to another guest language. A plain `Polyglot.eval("lua", ...)` that returns a primitive (number/string/boolean/nil) still crosses the boundary fine, since those are ordinary boxed values every guest language's interop already understands. |

## Compiled to LLVM bitcode / WASM

Loaded through GraalVM's LLVM or WebAssembly runtimes after an external compiler produces
the binary artifact; results are cached content-hashed under
`$XDG_CACHE_HOME/mycelium/<hash>.<ext>`.

| Language | Extension | Gradle pack | Toolchain | Notes |
|---|---|---|---|---|
| C | `.c` | `llvm` | `clang -emit-llvm -c -o` | |
| Rust | `.rs` | `llvm` | `rustc --crate-type=lib --emit=llvm-bc -o` | |
| Go | `.go` | `wasm` | `tinygo build -target=wasi -o` | Compiles to WASM, not LLVM bitcode — there's no single-command Go→LLVM-bitcode pipeline analogous to `clang -emit-llvm` or `rustc --emit=llvm-bc`; `gollvm` (Go's experimental LLVM backend) is unmaintained/hard to obtain. WASM exports are read via GraalJS's `WebAssembly` JS API (`new WebAssembly.Module/.Instance`, gated behind the `js.webassembly` context option) rather than the raw Truffle `wasm` language directly — `context.eval("wasm", ...)` exposes zero `memberKeys`, so there's no way to read a compiled module's exports that way. Requires `tinygo` specifically on `PATH`, not stock `go` (stock Go has no small, freestanding wasi/wasm target). |

## Compiled to plain JS

Each compiles down to an ordinary ES module and then executes through the `js` pack — no
separate Gradle configuration needed for any of these. Compiler backends are one of:
an npm-installed JS API (`NpmJsApiBackend`), a subprocess invocation of a native/downloaded
binary (`SubprocessBackend`), each resolved via a composable `ToolResolver` chain
(project-local `node_modules/.bin` → system `PATH` → cached package → registry download →
GitHub release → SDK-specific download). Output is cached content-addressed by source hash.

| Language | Extension(s) | Compiler backend | Notes |
|---|---|---|---|
| CoffeeScript | `.coffee` | npm `coffeescript` | |
| Civet | `.civet` | npm `@danielx/civet` | |
| LiveScript | `.ls` | npm `livescript` | |
| Elm | `.elm` | subprocess, `elm` npm package's native binary | Single-file compilation only (with an `elm.json`-scaffolding workaround). |
| PureScript | `.purs` | subprocess, `purescript` npm package's native `purs` | Single file only — no cross-module imports. |
| ReScript | `.res`, `.resi` | subprocess, `rescript` npm package's native `bsc` | `.resi` (interface files) route through the same compiler for consistency, though they have no runtime exports of their own. |
| Dart | `.dart` | subprocess, dart2js via downloaded Dart SDK | |
| Kotlin/JS | `.kt`, `.kts` | subprocess, `kotlin-compiler` npm package's bundled `kotlinc-js` | |
| Scala.js | `.scala` | subprocess, `scala-cli` (auto-downloaded via GitHub release) | |
| Gleam | `.gleam` | subprocess, `gleam` binary (auto-downloaded via GitHub release) | No single-file compile mode — importing a `.gleam` file with no `gleam.toml` anywhere above it fails outright. |
| Imba | `.imba` | npm `imba`'s `require('imba/compiler').compile` | **Experimental** — Imba 2.x has never shipped a stable release (npm's own `latest` dist-tag is an alpha, e.g. `2.0.0-alpha.252` at time of writing); breaking changes between alpha builds are possible. |

## Cross-cutting gaps

- **Native-addon npm packages (N-API)** fail gracefully with the package's own error rather
  than crashing, regardless of which guest language triggered the import — no FFI story for
  compiled Node addons exists yet.
- **Lua and Python are the two languages with an asymmetric interop story** — see their rows
  above. Every other native Truffle language (JS, Ruby) and every compile-to-JS language
  (which executes as JS) has full, symmetric cross-language member import.
- **crates.io and Go-proxy clients are constructed but not auto-wired into imports** — a
  bare `use`/import that would resolve against a Rust crate or Go module isn't
  auto-installed the way an npm/RubyGems/PyPI one is, since `RustSupport`/`TinyGoSupport`
  have no multi-unit linking step yet for a fetched dependency to plug into.

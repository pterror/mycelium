# Mycelium — Open Threads

*Updated 2026-07-15. Treat as starting context, not instructions — verify relevance before acting.*

## Vision

Mycelium's purpose: make every language's package ecosystem available to every other language. npm + PyPI + RubyGems + crates.io, all importable from anywhere. Not just "write in the best language" — unlock every package registry at once.

## Open threads

### Package manager auto-install
npm bare specifier resolution works against existing `node_modules` (walks parent dirs, reads package.json exports/main). But there's no auto-install — users must `npm install` packages themselves first. Open question: eager (install on first import, magical but unpredictable) vs declarative (manifest file, explicit but adds friction). Neither pip nor gem resolution is implemented yet.

### Python support (blocked upstream)
GraalPython 24.1.1 crashes with `SystemError: Unable to set dict of object of type foreign` when evaluating Python through a named Source with a custom FileSystem attached. This is a GraalPython bug, not a Mycelium issue — disappears if either the custom FileSystem or the named Source is removed. Fixed a separate bug where Mycelium was hard-failing on GraalPython's internal `<frozen importlib._bootstrap>` synthetic identifiers. See `PythonInteropExperimentTest.kt` for repro tests. Next step: try newer GraalVM/GraalPython release, or file minimal repro upstream.

### Go support (unverified)
`TinyGoSupport` stubbed in `Languages.kt` (Go → WASM via `tinygo build -target=wasi`, languageId = "wasm"), deliberately not in the default language list. Open question: WASM's sandboxed linear memory vs LLVM's shared address space — unclear if GraalVM polyglot bindings can bridge wasm exports as transparently as LLVM symbols (numeric args likely fine, strings/structs would need marshaling). Neither tinygo nor go is in the dev environment; nothing tested.

### Distribution form
CLI exists (`mycelium run/version/help`) but only via `./gradlew :app:run --args="..."`. Open question: use GraalVM native-image for a standalone binary? Is GraalVM's weight acceptable for end users, or explore lighter paths?

### Add rustc to flake.nix
Rust compilation works end-to-end (`--crate-type=lib` fix applied), but the integration test (`jsImportsRust`) skips because `rustc` isn't in `flake.nix`. Add it to make the test run by default.

### pip / gem resolution
npm node_modules resolution is implemented. Analogous resolution for Python (pip/site-packages) and Ruby (gem) packages is not. Design question: each ecosystem has different conventions for installed package layout — worth investigating what "already installed" looks like for pip and gem before building resolvers.

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

### GraalVM bump to 25.1.3 (blocked on Ruby/LLVM Maven publishing)
Attempted bumping `library/build.gradle.kts`'s `graalVersion` from 24.2.1 to 25.1.3. `org.graalvm.truffle:truffle-api`, `org.graalvm.polyglot:{polyglot,js,python,java,wasm,llvm-native}` are published at 25.1.3 on Maven Central, but `org.graalvm.polyglot:ruby`, `org.graalvm.ruby:ruby`, and the old `org.graalvm.polyglot:llvm` coordinate are not — they stop at 25.0.0/25.0.1. Mixing truffle-api 25.1.3 with truffleruby 25.0.0 was tried and fails hard at runtime: `ClassCastException: Invalid inline context node passed to an inlined field` deep in Truffle's DSL-generated inline-cache code (`InlineSupport$UnsafeField`), i.e. an ABI break between Truffle DSL versions, not just a version-number mismatch. Landed on **25.0.0 uniformly** instead (up from 24.2.1) — the highest version where every required GraalVM Maven artifact is simultaneously available. Two integration tests had to be updated for behavior that changed again between 24.2.1 and 25.0.0 (`MyceliumIntegrationTest.nonexistentBareSpecifierGivesFriendlyError` — host-exception shape reverted to the pre-24.2.1 form; `PythonInteropExperimentTest.mycelium_runFile_jsPolyglotEvalPython_noLongerHardFailsOnFrozenBootstrap` — same underlying GraalPython bug now manifests as a host NullPointerException instead of an opaque HostException wrapper). All 60 `:library:test` tests pass and `:app:nativeCompile` succeeds at 25.0.0. Nix's `flake.nix` uses nixpkgs' stock `graalvmPackages.graalvm-ce`, currently 25.0.2 — close enough to Maven's 25.0.0 that native-image worked without incident. Revisit once upstream publishes matching 25.1.x Ruby/LLVM Maven artifacts (or confirms they're being dropped for good — the `org.graalvm.polyglot:ruby`/`org.graalvm.ruby:ruby` distribution is marked deprecated upstream, "retained only for Maven compatibility").

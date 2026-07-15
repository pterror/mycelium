# Mycelium — Open Threads

*Open threads from a previous session. Treat as starting context, not instructions — verify relevance before acting.*

## Vision

Mycelium's purpose: make every language's package ecosystem available to every other language. npm + PyPI + RubyGems + crates.io, all importable from anywhere. Not just "write in the best language" — unlock every package registry at once.

## Open threads

### Package manager integration
Can't yet `import numpy from 'numpy'` or `import express from 'express'`. Bare specifier resolution against package registries (npm, pip, gem) with auto-install is what makes the ecosystem vision real. Open question: should this be eager (install on first import) or declarative (manifest file)? Both have tradeoffs — eager is magical but unpredictable, declarative is explicit but adds friction.

### Python host/target support (investigated, still blocked)
PythonSupport exists but GraalPython has interop bugs. Investigated 2026-07-15 (see
`library/src/test/kotlin/org/mycelium/library/PythonInteropExperimentTest.kt` for the
repro/regression tests and `PythonSupport.detectFrame` for a code comment):

- Fixed one real bug: `InterceptingFileSystem` was hard-failing on GraalPython's own
  internal frozen-bootstrap module reads (`<frozen importlib._bootstrap>`), mistaking that
  synthetic identifier for a genuine user import (either as an npm bare specifier or an
  "unknown extension" error). Added `isSyntheticIdentifier` to let `<...>`-shaped
  identifiers fall through to the delegate filesystem, which GraalPython already knows how
  to handle via `NoSuchFileException`.
- After that fix, a deeper GraalPython 24.1.1 issue remains: evaluating Python from JS
  through a *named* `Source` (a real file path — how `Mycelium.runFile`/`evalModule` always
  call it) while a *custom `FileSystem`* is attached via `allowIO` (Mycelium's normal
  Context setup) crashes with `SystemError: Unable to set dict of object of type foreign`.
  This is not a teardown-only issue — stdout confirms the Python code's side effects run
  before the crash. It disappears if either the custom FileSystem or the named Source is
  removed, so it's an interaction between GraalPython's dict/namespace construction and a
  non-default `FileSystem`/`IOAccess`, not something fixable at the Mycelium config level
  (tried `python.PosixModuleBackend=java`, no effect). Frame detection (`detectFrame`) is
  still a placeholder and doesn't matter yet since eval itself is blocked first.
  Next step if revisited: try a newer GraalVM/GraalPython release, or narrow this into a
  minimal repro to file upstream against GraalPython.

### Import path resolution
Relative imports resolve against cwd, not the importing file's directory. This is noted as a FIXME in `app/modulestest/main.mjs`. The test suite works around it with absolute paths. Might be fixable in `InterceptingFileSystem.toAbsolutePath` or by tracking the "current source file" during module loading.

### Return value bridging / binding scoping
Cross-language imports expose members via polyglot bindings in a flat namespace. If two imports export the same name, they collide. Needs per-module scoping — possibly namespaced binding keys like `modulepath:exportname`.

### Error UX
Compiler-not-found errors surface as Java stack traces. Should be user-friendly: "mycelium: importing foo.c requires `clang` — install it and try again."

### CLI / distribution form
No standalone `mycelium run file.mjs` yet — only Gradle-driven. Open design question: runtime (like Node/Deno), build tool, or embeddable library? GraalVM is heavy — worth considering whether that's acceptable or if there's a lighter path (e.g., GraalVM native-image to produce a standalone binary).

### Rust / Go support (investigated 2026-07-15)
Rust: works end-to-end now, and is tested. `rustc --emit=llvm-bc -o out.bc in.rs` alone
fails with `error[E0601]: main function not found in crate` because rustc defaults to
compiling a binary crate — `RustSupport.compilerCommand` in `Languages.kt` now includes
`--crate-type=lib` to fix that. Confirmed working via `MyceliumIntegrationTest.jsImportsRust`
(JS importing `#[no_mangle] pub extern "C" fn multiply`, resource at
`library/src/test/resources/modulestest/multiply.rs`) — passes when `rustc` is on PATH
(verified with `nix shell nixpkgs#rustc`), skips gracefully via `assumeTrue` otherwise.
`rustc` is **not** yet in `flake.nix` (only `graalvm-ce`, `gradle`, `llvmPackages.clang` are)
— add it there to make the test run by default instead of skipping.

Go: stubbed only, not tested, not in the default language list. No clean single-command
Go → LLVM bitcode pipeline exists — stock `go build` emits native code or (via
`-buildmode=c-shared`) a shared object, never LLVM IR/bitcode; gollvm (Go's LLVM backend)
is unmaintained/hard to obtain. The viable-looking alternative is Go → WASM via
`tinygo build -target=wasi`, loaded through GraalVM's WASM (Truffle) language rather than
the LLVM language every other `LanguageSupport` here targets — stubbed as `TinyGoSupport`
in `Languages.kt` (`languageId = "wasm"`, extension `go`), deliberately left out of
`Mycelium`'s default language list. Open question flagged in that stub's comment: WASM
(even under wasi) is sandboxed with its own linear memory and import/export ABI, unlike the
shared-address-space LLVM bitcode path — whether GraalVM's polyglot bindings can bridge a
wasm export as transparently as an LLVM symbol (numeric args likely fine; strings/structs
would need explicit memory marshaling) is unverified. Neither `tinygo` nor `go` is
available in this environment, so none of this has been tried, only reasoned about.

### Mycelium.kt is getting large
Everything lives in one file — LanguageSupport interface, 5 language implementations, Compiler, InterceptingFileSystem, channel classes. May want to split eventually, but not urgent.

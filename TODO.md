# Mycelium — Open Threads

*Open threads from a previous session. Treat as starting context, not instructions — verify relevance before acting.*

## Vision

Mycelium's purpose: make every language's package ecosystem available to every other language. npm + PyPI + RubyGems + crates.io, all importable from anywhere. Not just "write in the best language" — unlock every package registry at once.

## Open threads

### Package manager integration
Can't yet `import numpy from 'numpy'` or `import express from 'express'`. Bare specifier resolution against package registries (npm, pip, gem) with auto-install is what makes the ecosystem vision real. Open question: should this be eager (install on first import) or declarative (manifest file)? Both have tradeoffs — eager is magical but unpredictable, declarative is explicit but adds friction.

### Python host/target support
PythonSupport exists but GraalPython has interop bugs — `Polyglot.eval("python", ...)` from JS crashes with "Unable to set dict of object of type foreign" on teardown. Frame detection (`detectFrame`) is a placeholder. Python's ecosystem is arguably the most valuable to unlock. Needs investigation: is this a GraalPython version issue, a context configuration issue, or fundamental?

### Import path resolution
Relative imports resolve against cwd, not the importing file's directory. This is noted as a FIXME in `app/modulestest/main.mjs`. The test suite works around it with absolute paths. Might be fixable in `InterceptingFileSystem.toAbsolutePath` or by tracking the "current source file" during module loading.

### Return value bridging / binding scoping
Cross-language imports expose members via polyglot bindings in a flat namespace. If two imports export the same name, they collide. Needs per-module scoping — possibly namespaced binding keys like `modulepath:exportname`.

### Error UX
Compiler-not-found errors surface as Java stack traces. Should be user-friendly: "mycelium: importing foo.c requires `clang` — install it and try again."

### CLI / distribution form
No standalone `mycelium run file.mjs` yet — only Gradle-driven. Open design question: runtime (like Node/Deno), build tool, or embeddable library? GraalVM is heavy — worth considering whether that's acceptable or if there's a lighter path (e.g., GraalVM native-image to produce a standalone binary).

### Rust / Go support
Rust: extension mapping and `rustc --emit=llvm-bc` wired up but untested. Go: stubbed — no clean single-command Go → LLVM bitcode pipeline (might need tinygo → WASM instead).

### Mycelium.kt is getting large
Everything lives in one file — LanguageSupport interface, 5 language implementations, Compiler, InterceptingFileSystem, channel classes. May want to split eventually, but not urgent.

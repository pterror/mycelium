package org.mycelium.library.compiler

// Describes a single known compile-to-JS language: what to call it, which npm package (if
// any) provides its compiler, and how to invoke that compiler's `compile`-equivalent
// function. `npmPackageName == null` marks a language whose compiler is bundled with
// Mycelium itself rather than resolved/installed from npm — TypeScript is the only such
// entry today, compiled via the bundled esbuild backend (EsbuildBackend) rather than an
// npm-installed `typescript` package.
data class CompilerSpec(
    // e.g. "coffeescript", "civet", "typescript" — also the key CompilerRegistry indexes
    // specs by, and the cache-key component CompilerCache/CompileToJsDispatcher use to keep
    // different languages' cached output from colliding.
    val languageId: String,
    // e.g. "coffeescript", "@danielx/civet"; null for a bundled (non-npm) compiler, e.g.
    // esbuild for TypeScript.
    val npmPackageName: String?,
    // Version resolved/installed when `npmPackageName` is non-null, or otherwise the
    // version identifier folded into cache keys for a bundled backend so a Mycelium
    // upgrade that changes the bundled compiler's behavior invalidates old cache entries.
    val defaultVersion: String,
    // JS expression that evaluates to the compiler's `compile` function, e.g.
    // "require('coffeescript').compile" — meant to be called with the source text (and,
    // typically, an options object) by an npm-package-backed CompileToJsBackend. Unused by
    // bundled backends like EsbuildBackend, which invoke their own tooling directly rather
    // than evaluating a JS expression through GraalJS.
    val compileFunction: String,
    // Default options merged into (and overridable by) whatever options a caller passes to
    // CompileToJsBackend.compile.
    val compileOptions: Map<String, Any> = emptyMap(),
    // true if `compileFunction`'s result is an object with a `code` (or similarly-named)
    // string property rather than being the compiled JS string itself — e.g. some
    // compilers return `{code, sourceMap}` rather than a bare string.
    val outputIsObject: Boolean = false,
)

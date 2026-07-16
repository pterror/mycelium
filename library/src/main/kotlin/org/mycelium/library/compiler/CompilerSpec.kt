package org.mycelium.library.compiler

import java.nio.file.Path

// Which CompileToJsDispatcher/CompileToJsBackend a CompilerSpec is compiled through.
enum class BackendType {
    // require()'d into the shared GraalJS Context and called as a JS function
    // (NpmJsApiBackend) — CoffeeScript, Civet, LiveScript.
    JS_API,

    // Invoked as a native-binary subprocess (SubprocessBackend) — Elm, PureScript, ReScript,
    // none of which expose a JS-callable compile function at all (see CompilerRegistry's
    // registration comments for each).
    SUBPROCESS,

    // Compiled via a backend bundled with Mycelium itself rather than resolved from npm
    // (EsbuildBackend) — TypeScript is the only entry today.
    BUNDLED,
}

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
    // typically, an options object) by an npm-package-backed CompileToJsBackend. Only used
    // when backendType == JS_API; unused by BUNDLED backends like EsbuildBackend (which
    // invoke their own tooling directly rather than evaluating a JS expression through
    // GraalJS) and by SUBPROCESS backends (which have no JS-callable function at all).
    val compileFunction: String = "",
    // Default options merged into (and overridable by) whatever options a caller passes to
    // CompileToJsBackend.compile. Only meaningful for JS_API today (SubprocessBackend
    // ignores `options` entirely — its equivalent of "compile options" is baked into
    // `compileArgs` instead, since subprocess CLIs take flags, not a JS options object).
    val compileOptions: Map<String, Any> = emptyMap(),
    // true if `compileFunction`'s result is an object with a `code` (or similarly-named)
    // string property rather than being the compiled JS string itself — e.g. some
    // compilers return `{code, sourceMap}` rather than a bare string. Only meaningful for
    // backendType == JS_API.
    val outputIsObject: Boolean = false,

    // Which CompileToJsBackend CompileToJsDispatcher should route this language through.
    // Defaults to BUNDLED when there's no npm package at all (matches TypeScript's existing
    // "npmPackageName == null" convention) and to JS_API otherwise, so every pre-existing
    // registration (CoffeeScript/Civet/LiveScript/TypeScript) keeps working unchanged
    // without having to set this explicitly.
    val backendType: BackendType = if (npmPackageName == null) BackendType.BUNDLED else BackendType.JS_API,

    // --- SUBPROCESS-only fields below; ignored for JS_API/BUNDLED specs. ---

    // Locates the actual compiler executable inside the resolved npm package's directory
    // (i.e. `<moduleResolutionDir>/node_modules/<npmPackageName>`) — e.g. Elm:
    // `{ dir -> dir.resolve("bin/elm") }`, ReScript: a platform-specific
    // `<platform>/bsc.exe`. There is no single convention across these packages' `bin`
    // fields/layouts worth generalizing (see CompilerRegistry's registration comments for
    // each), so this is just a plain function rather than a declarative "binary name" the
    // dispatcher would otherwise have to reverse-engineer per package layout.
    val subprocessBinaryPath: ((packageDir: Path) -> Path)? = null,
    // Builds the compiler's CLI arguments given the source file, a caller-chosen output
    // file path (meaningful only when subprocessOutputMode == OUTPUT_FILE), and the
    // subprocess's working directory — see SubprocessBackend's own constructor doc comment.
    val compileArgs: ((sourcePath: Path, outputPath: Path, workDir: Path) -> List<String>)? = null,
    // See SubprocessOutputMode; defaults to STDOUT since that's the simpler of the two
    // (ReScript's `bsc` writes to stdout directly; Elm/PureScript need OUTPUT_FILE).
    val subprocessOutputMode: SubprocessOutputMode = SubprocessOutputMode.STDOUT,
    // Only consulted when subprocessOutputMode == OUTPUT_FILE; see SubprocessBackend's own
    // constructor doc comment for `locateOutput`.
    val locateOutput: ((workDir: Path, sourcePath: Path, outputPath: Path) -> Path)? = null,
)

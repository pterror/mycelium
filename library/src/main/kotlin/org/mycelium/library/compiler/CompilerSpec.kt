package org.mycelium.library.compiler

import org.graalvm.polyglot.Context
import org.mycelium.library.MyceliumException
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

    // --- Project-aware fields below; only meaningful for Elm/PureScript/ReScript, whose
    // real compilation unit is a whole project (elm.json / spago.yaml,spago.dhall /
    // rescript.json,bsconfig.json) rather than a single loose file. Empty/null for every
    // other language (CoffeeScript/Civet/LiveScript/TypeScript never had, and still don't
    // have, any notion of a multi-file project build) — see ProjectBuildRunner and
    // CompileToJsDispatcher.compileProjectAware for how these are consulted. ---

    // File names that mark a project root when walking up from a compiled source file's own
    // directory (e.g. listOf("elm.json"), or listOf("spago.yaml", "spago.dhall") — PureScript
    // supports either config format). Empty (the default) means "this language has no
    // project-build concept" — CompileToJsDispatcher then always uses the single-file
    // SubprocessBackend path instead of ever consulting the fields below.
    val projectFileNames: List<String> = emptyList(),

    // Resolves the binary that drives the *whole-project* build command, given the same
    // `packageDir` `subprocessBinaryPath` would receive (`<moduleResolutionDir>/node_modules/
    // <npmPackageName>`). Defaults to `subprocessBinaryPath` itself when null, since for
    // Elm/ReScript the project build is driven by the exact same binary as single-file
    // compilation (`elm`, `rescript.exe`). PureScript is the one exception — spago (the
    // actual project/dependency-manager tool) is a wholly separate CLI from `purs`
    // (single-file/module-graph compiler) and isn't provided by the `purescript` npm package
    // at all, so PureScriptSupport's registration overrides this to resolve `spago` via PATH
    // instead of `packageDir` (see CompilerRegistry's own comment on that override for why).
    val projectBuildBinaryPath: ((packageDir: Path) -> Path)? = null,

    // Builds the CLI arguments (everything after the resolved binary) for a whole-project
    // build, given the project root, the resolved build binary, the specific source file
    // that triggered this compile (needed by Elm, whose `elm make` takes an explicit entry
    // module argument rather than discovering one on its own), and a scratch output
    // directory ProjectBuildRunner created for this project+language. Run with the project
    // root as the subprocess's working directory. Only consulted when projectFileNames is
    // non-empty and a project root was actually found above the compiled file.
    val projectBuildArgs:
        ((projectRoot: Path, binaryPath: Path, sourcePath: Path, outputDir: Path) -> List<String>)? = null,

    // Locates the specific compiled JS file for `sourcePath` once `projectRoot` has been (or
    // is assumed to already have been) built — e.g. PureScript: `<projectRoot>/output/
    // <ModuleName>/index.js`; ReScript: the `.bs.js` file adjacent to `sourcePath` itself;
    // Elm: the single project-wide bundle ProjectBuildRunner asked `elm make` to write under
    // `outputDir`. Also used, before running any build at all, to check whether fresh
    // pre-built output already exists on disk (ProjectBuildRunner's "step (a)" in the
    // phase-3 design) — so this must be a pure function of paths, safe to call whether or
    // not a build has actually happened yet.
    val resolveProjectOutput: ((projectRoot: Path, outputDir: Path, sourcePath: Path) -> Path)? = null,

    // Constructs the CompileToJsBackend that handles this language's non-bundled
    // compilation, given the shared GraalJS Context and the directory
    // `__mycelium_require__`'s node_modules walk should start from (already resolved by
    // CompileToJsDispatcher — project node_modules vs. its own compiler cache; see
    // CompileToJsDispatcher.resolveCompilerLocation). This is what keeps
    // CompileToJsDispatcher from needing a when(backendType) switch over concrete backend
    // classes (NpmJsApiBackend, SubprocessBackend, ...) itself: the dispatcher just calls
    // `spec.createBackend(context, moduleResolutionDir)` and gets back a
    // CompileToJsBackend, without knowing or caring which concrete class it is.
    //
    // Never invoked for BackendType.BUNDLED specs — a bundled backend (EsbuildBackend for
    // TypeScript) needs no per-compile npm resolution at all, so CompileToJsDispatcher
    // uses its own shared instance for those instead of calling this.
    //
    // Defaults to building the one stock backend matching `backendType` from this spec's
    // own fields, so every existing registration (CoffeeScript/Civet/LiveScript/Elm/
    // PureScript/ReScript) keeps working without setting this explicitly — only a
    // language needing some wholly custom backend would ever override it.
    val createBackend: (context: Context, moduleResolutionDir: Path) -> CompileToJsBackend = { context, moduleResolutionDir ->
        when (backendType) {
            BackendType.JS_API ->
                NpmJsApiBackend(context, compileFunction, moduleResolutionDir, outputIsObject)
            BackendType.SUBPROCESS -> {
                val npmPkg = npmPackageName
                    ?: throw MyceliumException(
                        "mycelium: compiler spec for '$languageId' is SUBPROCESS but has no npmPackageName"
                    )
                val resolveBinaryPath = subprocessBinaryPath
                    ?: throw MyceliumException(
                        "mycelium: compiler spec for '$languageId' is SUBPROCESS but has no subprocessBinaryPath"
                    )
                val args = compileArgs
                    ?: throw MyceliumException(
                        "mycelium: compiler spec for '$languageId' is SUBPROCESS but has no compileArgs"
                    )
                val packageDir = moduleResolutionDir.resolve("node_modules").resolve(npmPkg)
                val binaryPath = resolveBinaryPath(packageDir)
                // The npm/PackageCache download pipeline hardlinks a package's files as-is
                // rather than re-running npm's own install-time chmod-the-bin-files step,
                // so a downloaded compiler binary can land without its executable bit set
                // — make sure it's set before ProcessBuilder ever tries to run it.
                binaryPath.toFile().setExecutable(true)
                SubprocessBackend(
                    binaryPath = binaryPath,
                    compileArgs = args,
                    outputMode = subprocessOutputMode,
                    locateOutput = locateOutput,
                )
            }
            BackendType.BUNDLED -> throw MyceliumException(
                "mycelium: compiler spec for '$languageId' is BUNDLED — createBackend should never be " +
                    "invoked for it (CompileToJsDispatcher uses its own shared bundled backend instead)"
            )
        }
    },
)

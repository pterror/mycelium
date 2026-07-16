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
    // backendType == JS_API. Superseded by `outputProperty` below when a language's output
    // property isn't literally named "code" (e.g. Imba's "js") — kept as its own boolean
    // (rather than folded away entirely) so every pre-existing registration
    // (CoffeeScript/Civet/LiveScript, none of which actually set it true) keeps reading the
    // same way it always has.
    val outputIsObject: Boolean = false,
    // The property name to read off `compileFunction`'s result when it returns an object
    // rather than a bare string — e.g. Imba's `require('imba/compiler').compile(code, o)`
    // returns a whole Compilation/Document instance whose compiled output lives on `.js`
    // (confirmed against the published 2.0.0-alpha.252 tarball's
    // src/compiler/compilation.mjs, which does `this.js = ""` then populates it during
    // `compile()`), not `.code`. Null (the default) means "derive the effective property
    // from `outputIsObject`" — `"code"` when `outputIsObject` is true, or "the result IS the
    // string" (no property read at all) when it's false — so this field only needs setting
    // at all for a compiler whose object output uses some other property name than "code".
    // Only meaningful for backendType == JS_API; see NpmJsApiBackend's own constructor doc
    // comment for how the two fields combine into the single property name it actually uses.
    val outputProperty: String? = null,

    // Version-aware resolution (see ToolResolver.kt's VersionCheck): the CLI command to run
    // against a *candidate* binary (project-local node_modules/.bin entry, a system-PATH
    // install, or an already-downloaded cache entry) to self-report its version, and how to
    // pull a bare version string out of whatever it prints — every tool formats `--version`
    // differently ("0.19.1" vs. "CoffeeScript version 2.7.0" vs. "Dart SDK version: 3.4.0
    // (stable) ..."). Both null (the default) for every pre-existing JS_API registration
    // (CoffeeScript/Civet/LiveScript/Imba) means "accept whatever's installed" — those are
    // `require()`'d straight into the shared GraalJS Context rather than invoked as a
    // subprocess, so there's no separate `--version` binary to shell out to at all; their own
    // version-constraint checking instead happens by reading the resolved npm package's own
    // package.json "version" field directly (see CompilerDownloader.installedPackageVersion /
    // CompileToJsDispatcher.resolveCompilerLocation), which needs neither field. `versionCommand`/
    // `versionExtractor` are for SUBPROCESS-backed (and non-npm, tool-resolved) languages
    // instead — Elm/PureScript/ReScript/Dart/Kotlin-JS/Scala.js — whose only interface to probe
    // is the binary itself.
    val versionCommand: List<String>? = null,
    val versionExtractor: ((String) -> String?)? = null,

    // Tool-based resolution for a SUBPROCESS spec whose compiler isn't an npm package at all —
    // Dart's `dart2js`/`dart` (distributed as a platform SDK zip, see ToolResolver.kt's
    // DartSdkDownload) is the case this exists for. Resolved via the shared ToolResolver/
    // ToolSource chain (project-local -> system PATH -> Mycelium's own download cache ->
    // download) exactly like CompilerSpec.projectBuildToolSources does for a *project*-build
    // binary — this is the single-file-compile counterpart, consulted by
    // CompileToJsDispatcher.compile whenever `npmPackageName` is null, `backendType` is
    // SUBPROCESS, and no project file was found above the compiled source (or the language
    // has no `projectFileNames` at all). `toolBinaryName` names the executable to resolve
    // (e.g. "dart2js"); `toolSources` builds the ToolSource list given the compiled file's own
    // path (so ProjectLocalBin/version-check constraints can be anchored there) — both null
    // (the default) preserves every npm-backed SUBPROCESS language's (Elm/PureScript/ReScript)
    // existing resolution completely unchanged.
    val toolBinaryName: String? = null,
    val toolSources: ((sourcePath: Path) -> List<ToolSource>)? = null,

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

    // Resolves the binary that drives the *whole-project* build command, given the
    // `packageDir` that whichever npm package actually provides it resolved to
    // (`<moduleResolutionDir>/node_modules/<pkg>`) — `<npmPackageName>`'s own packageDir when
    // `projectBuildNpmPackage` is null (Elm/ReScript: the project build is driven by the exact
    // same binary as single-file compilation, `elm`/`rescript.exe`), or
    // `projectBuildNpmPackage`'s packageDir otherwise (PureScript: `spago`, a wholly separate
    // CLI/npm package from `purescript`/`purs` — see `projectBuildNpmPackage`'s own doc
    // comment below).
    val projectBuildBinaryPath: ((packageDir: Path) -> Path)? = null,

    // The npm package that provides the *project-build* tool `projectBuildBinaryPath`
    // locates, when that tool is a wholly separate package from `npmPackageName` itself —
    // PureScript's spago is the one case today (see the extended comment on
    // `projectBuildBinaryPath` above for why: spago is a completely different CLI/project
    // from `purescript`/`purs`). Null (the default) means the project-build tool is provided
    // by `npmPackageName` itself (Elm's `elm`, ReScript's `rescript`), so
    // CompileToJsDispatcher.compileProjectAware resolves it exactly like single-file
    // compilation always has. When non-null, it's resolved independently, through the same
    // "user's own install wins over Mycelium's auto-download" chain SubprocessBackend already
    // gets via resolveCompilerLocation: project node_modules -> system PATH -> Mycelium's own
    // download cache -> auto-download from npm — see
    // CompileToJsDispatcher.resolveProjectBuildBinary.
    val projectBuildNpmPackage: String? = null,

    // Version to resolve/download `projectBuildNpmPackage` at; ignored when
    // `projectBuildNpmPackage` is null. Deliberately independent of `defaultVersion` — when
    // set, the project-build tool and the single-file compiler are entirely separate projects
    // with their own release cadences/version numbers (spago 1.0.4 vs. purs 0.15.15).
    val projectBuildToolVersion: String = defaultVersion,

    // The bare executable name to look for on the system PATH when `projectBuildNpmPackage`
    // is set (e.g. "spago") — a global install (`npm install -g spago`, the official
    // installer, a system package manager, ...) is checked before ever falling back to
    // Mycelium's own auto-download. Null (the default, and the only sensible value when
    // `projectBuildNpmPackage` is also null) skips the PATH-search step entirely.
    val projectBuildPathBinaryName: String? = null,

    // Overrides the entire project-build-binary resolution chain with a caller-supplied
    // ToolSource list, for a project-build tool that isn't hosted on npm at all — Gleam's
    // `gleam` binary (a native Rust binary distributed only via GitHub Releases; the "gleam"
    // package that does exist on npm is an unrelated JS library) is the one case today.
    // `projectBuildNpmPackage`/`projectBuildBinaryPath`/`projectBuildPathBinaryName`
    // above are all built around an npm-shaped `<dir>/node_modules/<pkg>` layout
    // (CachedPackage/RegistryDownload in ToolResolver.kt), which simply doesn't exist for a
    // GitHub-release-only tool — so rather than contorting those fields to fit, this lets a
    // language hand CompileToJsDispatcher.resolveProjectBuildBinary a fully custom source
    // list instead (typically `listOf(ProjectLocalBin(...), SystemPath(), GitHubRelease(...))`,
    // mirroring the same "project-local -> system PATH -> Mycelium's own download cache/
    // download" precedence every other language gets, just sourced from ToolResolver.kt's
    // GitHubRelease rather than RegistryDownload for the last step). Given `sourcePath` so
    // ProjectLocalBin can be anchored at the compiled file's own project. Null (the default)
    // preserves every existing language's (Elm/PureScript/ReScript) npm-based resolution
    // completely unchanged — this is only ever consulted when non-null, taking priority over
    // `projectBuildNpmPackage`'s own resolution entirely.
    val projectBuildToolSources: ((sourcePath: Path) -> List<ToolSource>)? = null,

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
                NpmJsApiBackend(
                    context,
                    compileFunction,
                    moduleResolutionDir,
                    outputProperty ?: if (outputIsObject) "code" else null,
                )
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

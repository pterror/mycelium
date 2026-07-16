package org.mycelium.library.compiler

import org.graalvm.polyglot.Context
import org.mycelium.library.MyceliumException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

// Ties together CompilerRegistry (which compile-to-JS languages Mycelium knows about),
// CompilerCache (content-hash-based disk caching of their compiled output), and whichever
// CompileToJsBackend actually does the compiling — the single entry point
// InterceptingFileSystem calls once it's decided (via LanguageSupport.jsCompilerId) that a
// file needs compiling to JS before GraalJS can evaluate it.
//
// A class (constructed by Mycelium, the composition root) rather than a global `object`:
// all of its collaborators — registry, downloader, cache, bundledBackend — are received via
// constructor rather than reached for as concrete singletons, so this dispatcher never
// needs to know about NpmJsApiBackend/SubprocessBackend directly (CompilerSpec.createBackend
// builds those instead — see its own doc comment) and can be constructed with fakes in
// tests.
//
// Note on "pre-built output already exists" (the very first entry in the compiler
// resolution order described in ROADMAP/the phase-3 design): that case never reaches this
// class at all. It's handled implicitly by InterceptingFileSystem only ever routing a file
// through CompileToJsDispatcher.compile when *that specific file's own extension* is a
// compile-to-JS language's (e.g. .coffee/.civet/.ts) — an imported package whose
// package.json "main"/"exports" already points at plain prebuilt .js is resolved and read as
// an ordinary JS file by NodeModuleResolver and never has a compile-to-JS jsCompilerId
// extension to trigger this path in the first place.
class CompileToJsDispatcher(
    private val registry: CompilerRegistry,
    private val downloader: CompilerDownloader,
    private val cache: CompilerCache,
    // The bundled backend, used only for TypeScript (npmPackageName == null in its
    // CompilerSpec) — never resolved/downloaded from npm, so it needs none of the
    // node_modules/cache/download resolution chain the npm-package-backed languages
    // (CoffeeScript, Civet, ...) go through below. Shared across every compile (unlike
    // NpmJsApiBackend/SubprocessBackend, which are per-dispatch since their
    // moduleResolutionDir/binaryPath vary), since EsbuildBackend needs neither.
    private val bundledBackend: CompileToJsBackend,
    // Handles Elm/PureScript/ReScript's project-aware compilation (elm.json / spago.yaml,
    // spago.dhall / rescript.json,bsconfig.json) — see compileProjectAware. A constructor
    // param (rather than a private `= ProjectBuildRunner()` default) purely so tests can
    // substitute a fake the same way they can for `downloader`/`cache`; defaults to a real
    // one so existing callers (Mycelium.kt) don't have to change.
    private val projectBuildRunner: ProjectBuildRunner = ProjectBuildRunner(),
) {
    fun compile(languageId: String, sourcePath: Path, context: Context): String {
        val spec = registry.resolve(languageId)
            ?: throw MyceliumException("mycelium: no compile-to-JS compiler registered for '$languageId'")
        val sourceText = sourcePath.readText()
        val sourceHash = cache.contentHash(sourceText)

        cache.get(languageId, spec.defaultVersion, sourceHash)?.let { return it }

        // Resolution order (see the phase-3 design / ROADMAP): a project file
        // (elm.json/spago.yaml,spago.dhall/rescript.json,bsconfig.json) found by walking up
        // from the compiled file wins over the single-file SubprocessBackend fallback below —
        // real projects have cross-module imports/dependencies a single loose file can't
        // resolve at all (see each CompilerSpec registration's own CAVEAT comments in
        // CompilerRegistry for what breaks without this). Languages with no
        // projectFileNames at all (CoffeeScript/Civet/LiveScript/TypeScript) always skip
        // straight to the existing behavior below, unchanged.
        val projectRoot = if (spec.projectFileNames.isNotEmpty()) {
            projectBuildRunner.findProjectRoot(sourcePath.parent, spec.projectFileNames)
        } else {
            null
        }

        val compiled = if (spec.backendType == BackendType.BUNDLED) {
            bundledBackend.compile(sourceText, sourcePath, spec.compileOptions)
        } else if (projectRoot != null) {
            compileProjectAware(spec, languageId, sourcePath, projectRoot.first, projectRoot.second)
        } else {
            val npmPackageName = spec.npmPackageName
                ?: throw MyceliumException(
                    "mycelium: compiler spec for '$languageId' is not BUNDLED but has no npmPackageName"
                )
            val moduleResolutionDir =
                resolveCompilerLocation(languageId, npmPackageName, spec.defaultVersion, sourcePath)
            val backend = spec.createBackend(context, moduleResolutionDir)
            backend.compile(sourceText, sourcePath, spec.compileOptions)
        }
        cache.put(languageId, spec.defaultVersion, sourceHash, compiled)
        return compiled
    }

    // Drives a whole-project build (via ProjectBuildRunner) for a language whose
    // CompilerSpec declares `projectFileNames` and a project file was actually found above
    // `sourcePath`. Resolves the project-build binary through the exact same
    // node_modules-vs-cache-vs-download chain resolveCompilerLocation already provides for
    // single-file compilation (steps 2-4 of the phase-3 design) — `projectBuildBinaryPath`
    // only changes *which* binary that resolution hands back (e.g. `spago` on PATH for
    // PureScript, ignoring the resolved packageDir entirely — see its own doc comment on
    // CompilerSpec), not whether Mycelium still tries the project's own node_modules /
    // compiler cache / download first.
    private fun compileProjectAware(
        spec: CompilerSpec,
        languageId: String,
        sourcePath: Path,
        projectRoot: Path,
        projectFile: Path,
    ): String {
        val npmPackageName = spec.npmPackageName
            ?: throw MyceliumException(
                "mycelium: compiler spec for '$languageId' has projectFileNames but no npmPackageName"
            )
        val moduleResolutionDir =
            resolveCompilerLocation(languageId, npmPackageName, spec.defaultVersion, sourcePath)
        val packageDir = moduleResolutionDir.resolve("node_modules").resolve(npmPackageName)
        val resolveBinary = spec.projectBuildBinaryPath ?: spec.subprocessBinaryPath
            ?: throw MyceliumException(
                "mycelium: compiler spec for '$languageId' has projectFileNames but no " +
                    "projectBuildBinaryPath/subprocessBinaryPath"
            )
        val binaryPath = resolveBinary(packageDir)
        // Same rationale as CompilerSpec.createBackend's SUBPROCESS branch: a downloaded
        // compiler binary can land without its executable bit set. Only meaningful when the
        // resolved path actually lives under packageDir (e.g. PureScript's projectBuildBinaryPath
        // resolves to a bare "spago" meant to be PATH-searched instead, which this is a no-op
        // for since it isn't a regular file at all).
        if (Files.isRegularFile(binaryPath)) {
            binaryPath.toFile().setExecutable(true)
        }
        val buildArgs = spec.projectBuildArgs
            ?: throw MyceliumException(
                "mycelium: compiler spec for '$languageId' has projectFileNames but no projectBuildArgs"
            )
        val resolveOutput = spec.resolveProjectOutput
            ?: throw MyceliumException(
                "mycelium: compiler spec for '$languageId' has projectFileNames but no resolveProjectOutput"
            )
        return projectBuildRunner.compile(
            languageId, projectRoot, projectFile, sourcePath, binaryPath, buildArgs, resolveOutput
        )
    }

    // Implements the resolution chain described in the phase-3 design (steps 2-4; step 1 —
    // pre-built output — never reaches this class at all, see the class doc comment above;
    // step 5 — bundled esbuild — only applies to TypeScript, handled separately in compile()
    // above):
    //   2. Already installed in the compiled file's own project's node_modules -> use it,
    //      zero download.
    //   3. Already downloaded to Mycelium's own compiler cache (a previous run, possibly for
    //      a different project) -> use it, zero download.
    //   4. Neither -> download it into the cache now (CompilerDownloader), for this and every
    //      future compile of this language+version.
    // Returns the directory `__mycelium_require__` should resolve `require(npmPackageName)`
    // from (see NpmJsApiBackend) — i.e. whichever directory directly contains a
    // `node_modules/<npmPackageName>` — not the package directory itself.
    private fun resolveCompilerLocation(
        languageId: String,
        npmPackageName: String,
        version: String,
        sourcePath: Path
    ): Path {
        findProjectNodeModulesDir(sourcePath.parent, npmPackageName)?.let { return it }

        val cacheDir = downloader.cacheDirFor(languageId, version)
        if (downloader.isInstalledAt(cacheDir, npmPackageName)) return cacheDir

        return downloader.ensureDownloaded(languageId, npmPackageName, version)
    }

    // Walks `startDir` and its ancestors looking for a `node_modules/<npmPackageName>`
    // directory — the same "walk up, checking every ancestor's node_modules" shape
    // NodeModuleResolver.resolveNodeModule uses for ordinary imports, reimplemented here
    // (rather than reusing NodeModuleResolver directly) since that class is built around
    // resolving *import specifiers* to file paths inside a Context-driven package.json
    // resolver, whereas this only needs a much simpler "does this directory exist" probe with
    // no Context/JS involved.
    private fun findProjectNodeModulesDir(startDir: Path?, npmPackageName: String): Path? {
        var dir: Path? = startDir
        while (dir != null) {
            if (downloader.isInstalledAt(dir, npmPackageName)) return dir
            dir = dir.parent
        }
        return null
    }
}

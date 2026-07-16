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
    // Drives the generic "project-local -> system PATH -> Mycelium's own download cache ->
    // download" chain used by resolveProjectBuildBinary below — see ToolResolver.kt's own doc
    // comments. A constructor param (defaulting to a fresh, stateless instance) for the same
    // reason downloader/cache/projectBuildRunner are: so tests can substitute a fake without
    // this dispatcher ever reaching for a concrete ToolResolver itself.
    private val toolResolver: ToolResolver = ToolResolver(),
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
            // TypeScript's one and only BUNDLED registration additionally supports an
            // explicit opt-in to the user's own installed `tsc` (project devDependency or
            // system PATH) instead of the bundled EsbuildBackend — see
            // resolveTscOverride's own doc comment for what "explicitly opt in" means and
            // why this is checked before ever falling back to the bundled backend.
            resolveTscOverride(languageId, sourcePath)?.let { tscBinary ->
                compileWithTsc(tscBinary, sourcePath)
            } ?: bundledBackend.compile(sourceText, sourcePath, spec.compileOptions)
        } else if (projectRoot != null) {
            compileProjectAware(spec, languageId, sourcePath, projectRoot.first, projectRoot.second)
        } else if (spec.npmPackageName == null && spec.toolBinaryName != null && spec.toolSources != null) {
            // Dart (and any future non-npm, tool-resolved SUBPROCESS language): resolved via
            // ToolResolver/ToolSource instead of the npm node_modules/cache/download chain —
            // see CompilerSpec.toolBinaryName's own doc comment.
            compileViaToolResolver(spec, languageId, sourceText, sourcePath)
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

    // Resolves + invokes a SUBPROCESS-backed compiler whose binary isn't an npm package at all
    // (Dart's `dart2js`) — mirrors CompilerSpec.createBackend's own SUBPROCESS branch (same
    // executable-bit fixup, same SubprocessBackend construction) but sources the binary from
    // `spec.toolSources` via the shared ToolResolver instead of an npm `packageDir`.
    private fun compileViaToolResolver(
        spec: CompilerSpec,
        languageId: String,
        sourceText: String,
        sourcePath: Path,
    ): String {
        val toolName = spec.toolBinaryName
            ?: throw MyceliumException("mycelium: compiler spec for '$languageId' has no toolBinaryName")
        val buildSources = spec.toolSources
            ?: throw MyceliumException("mycelium: compiler spec for '$languageId' has no toolSources")
        val args = spec.compileArgs
            ?: throw MyceliumException("mycelium: compiler spec for '$languageId' is SUBPROCESS but has no compileArgs")
        val binaryPath = toolResolver.resolve(toolName, buildSources(sourcePath))
        if (Files.isRegularFile(binaryPath)) {
            binaryPath.toFile().setExecutable(true)
        }
        val backend = SubprocessBackend(
            binaryPath = binaryPath,
            compileArgs = args,
            outputMode = spec.subprocessOutputMode,
            locateOutput = spec.locateOutput,
        )
        return backend.compile(sourceText, sourcePath, spec.compileOptions)
    }

    // TypeScript's registered CompilerSpec is BackendType.BUNDLED (compiled via the bundled
    // EsbuildBackend, no npm resolution at all) — but a project can also opt into the *real*
    // `tsc` compiler instead, when it's actually available and the project has visibly opted
    // in. "Explicitly opts in" is interpreted here as: a `tsconfig.json` exists somewhere above
    // the compiled file (the project's own signal that it's TypeScript-toolchain-aware, as
    // opposed to a loose .ts file with no project config at all, which stays on the bundled
    // esbuild path exactly as before) AND a `tsc` binary can actually be resolved (project
    // node_modules/.bin, or system PATH) whose own reported version satisfies whatever version
    // constraint the project's package.json declares for "typescript" (if any — no constraint
    // means "accept whatever's found"). Returns null (falls back to bundled esbuild) if either
    // condition doesn't hold, so every project without a tsconfig.json keeps behaving exactly
    // as before this method existed.
    private fun resolveTscOverride(languageId: String, sourcePath: Path): Path? {
        if (languageId != "typescript") return null
        val projectRoot = findAncestorWith(sourcePath.parent, "tsconfig.json") ?: return null
        val constraint = findPackageJsonConstraint(sourcePath.parent, "typescript")
        val versionCheck = constraint?.let {
            VersionCheck(listOf("--version"), ::extractTscVersion, it.versionSpec)
        }
        val sources = buildList {
            add(ProjectLocalBin(projectRoot, versionCheck))
            add(SystemPath(versionCheck))
        }
        return try {
            toolResolver.resolve("tsc", sources, constraint?.versionSpec, constraint?.packageJsonPath)
        } catch (e: MyceliumException) {
            null
        }
    }

    // `tsc --version` prints e.g. "Version 5.4.5" — pull the bare semver out of it.
    private fun extractTscVersion(output: String): String? =
        Regex("""(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)""").find(output)?.groupValues?.get(1)

    // Runs the user's own `tsc` against a single file, mirroring SubprocessBackend's own
    // "temp workdir, read the emitted file back" shape but using tsc's own transpile-only
    // single-file invocation rather than a project-wide `tsc -p tsconfig.json` build (which
    // would compile every file in the project's `include`, not just the one Mycelium was
    // actually asked to import) — `--outDir` redirects the emitted .js into a scratch
    // directory this method reads back and cleans up itself.
    private fun compileWithTsc(tscBinary: Path, sourcePath: Path): String {
        val workDir = Files.createTempDirectory("mycelium-tsc-")
        try {
            val outputFile = workDir.resolve(sourcePath.fileName.toString().removeSuffix(".ts") + ".js")
            val command = listOf(
                tscBinary.toString(),
                sourcePath.toString(),
                "--outDir", workDir.toString(),
                "--module", "esnext",
                "--target", "esnext",
                "--moduleResolution", "bundler",
            )
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0 || !Files.exists(outputFile)) {
                throw MyceliumException(
                    "mycelium: '$tscBinary' failed to compile '${sourcePath.fileName}' (exit $exitCode):\n$output"
                )
            }
            return outputFile.toFile().readText()
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }

    // Walks `startDir` and its ancestors looking for `fileName` — used by resolveTscOverride
    // to find a tsconfig.json above the compiled file, mirroring ProjectBuildRunner's own
    // ancestor walk for elm.json/spago.yaml/rescript.json but without needing a whole
    // (projectRoot, projectFile) pair back.
    private fun findAncestorWith(startDir: Path?, fileName: String): Path? {
        var dir: Path? = startDir
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve(fileName))) return dir
            dir = dir.parent
        }
        return null
    }

    // Drives a whole-project build (via ProjectBuildRunner) for a language whose
    // CompilerSpec declares `projectFileNames` and a project file was actually found above
    // `sourcePath`. Resolves the project-build binary via resolveProjectBuildBinary below —
    // either the same npm package as single-file compilation (Elm/ReScript) or, when
    // `projectBuildNpmPackage` is set (PureScript's spago), a separately-resolved package
    // going through node_modules -> PATH -> Mycelium's own download cache -> auto-download.
    private fun compileProjectAware(
        spec: CompilerSpec,
        languageId: String,
        sourcePath: Path,
        projectRoot: Path,
        projectFile: Path,
    ): String {
        val binaryPath = resolveProjectBuildBinary(spec, languageId, sourcePath)
        // Same rationale as CompilerSpec.createBackend's SUBPROCESS branch: a downloaded
        // compiler binary can land without its executable bit set. Only meaningful when the
        // resolved path is a regular file at all (a PATH-resolved binary already has whatever
        // permissions its own install gave it).
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

    // Resolves the binary that drives a whole-project build (see compileProjectAware above).
    //
    // Two shapes, matching CompilerSpec.projectBuildNpmPackage's own doc comment:
    //   - null (Elm/ReScript): the project-build tool is provided by the exact same npm
    //     package as single-file compilation, so this is exactly resolveCompilerLocation
    //     (steps 2-4 of the phase-3 design) followed by `projectBuildBinaryPath`/
    //     `subprocessBinaryPath` locating the binary inside that package's directory —
    //     unchanged from this dispatcher's pre-existing behavior.
    //   - non-null (PureScript's spago): a wholly separate npm package from `npmPackageName`,
    //     resolved via the shared ToolResolver/ToolSource chain (see ToolResolver.kt) — the
    //     same "local install wins over Mycelium's own auto-download" precedence, with a
    //     global system-PATH install (e.g. `npm install -g spago`) also winning over
    //     auto-downloading, since it's just as much "the user's own install" as a local
    //     node_modules one is:
    //       1. the compiled file's own project's node_modules/.bin (walking up ancestors)
    //       2. system PATH (only when projectBuildPathBinaryName is set)
    //       3. Mycelium's own download cache (a previous run, this or a different project)
    //       4. neither -> download it into the cache now, for this and every future compile
    private fun resolveProjectBuildBinary(spec: CompilerSpec, languageId: String, sourcePath: Path): Path {
        // A third shape, alongside the null/non-null `projectBuildNpmPackage` split
        // documented below: a project-build tool that isn't hosted on npm at all (Gleam's
        // `gleam`, distributed only via GitHub Releases — see CompilerSpec.
        // projectBuildToolSources's own doc comment). Takes priority over
        // `projectBuildNpmPackage` entirely when set.
        spec.projectBuildToolSources?.let { buildToolSources ->
            val toolName = spec.projectBuildPathBinaryName ?: languageId
            return toolResolver.resolve(toolName, buildToolSources(sourcePath))
        }

        val resolveBinaryFromPackageDir = spec.projectBuildBinaryPath ?: spec.subprocessBinaryPath
            ?: throw MyceliumException(
                "mycelium: compiler spec for '$languageId' has projectFileNames but no " +
                    "projectBuildBinaryPath/subprocessBinaryPath"
            )
        val projectBuildNpmPackage = spec.projectBuildNpmPackage
        if (projectBuildNpmPackage == null) {
            val npmPackageName = spec.npmPackageName
                ?: throw MyceliumException(
                    "mycelium: compiler spec for '$languageId' has projectFileNames but no npmPackageName"
                )
            val moduleResolutionDir =
                resolveCompilerLocation(languageId, npmPackageName, spec.defaultVersion, sourcePath)
            val packageDir = moduleResolutionDir.resolve("node_modules").resolve(npmPackageName)
            return resolveBinaryFromPackageDir(packageDir)
        }

        // Cache-keyed by "$languageId-projectbuild" (rather than plain $languageId) so this
        // never collides with $languageId's own single-file-compiler cache entry, which is
        // keyed by a wholly different npm package/version (e.g. purescript's `purs`
        // 0.15.15 vs. spago 1.0.4 here).
        val cacheKey = "$languageId-projectbuild"
        val sources = buildList {
            sourcePath.parent?.let { add(ProjectLocalBin(it)) }
            // projectBuildPathBinaryName and projectBuildNpmPackage always name the same tool
            // for every language registered today (PureScript's spago is both), so a single
            // toolName can drive every source in this list.
            if (spec.projectBuildPathBinaryName != null) add(SystemPath())
            add(
                CachedPackage(
                    downloader.cacheDirFor(cacheKey, spec.projectBuildToolVersion),
                    resolveBinaryFromPackageDir,
                )
            )
            add(
                RegistryDownload(
                    downloader,
                    projectBuildNpmPackage,
                    spec.projectBuildToolVersion,
                    resolveBinaryFromPackageDir,
                    cacheKey,
                )
            )
        }
        return toolResolver.resolve(projectBuildNpmPackage, sources)
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
    // Version-aware since the coordinator's own additional requirement: a project's own
    // package.json can declare a version constraint for any npm-package-backed compile-to-JS
    // compiler in its dependencies/devDependencies (e.g. `"coffeescript": "~2.7.0"`,
    // `"typescript": "^5.0.0"` — see findPackageJsonConstraint in ToolResolver.kt), which takes
    // precedence over CompilerSpec.defaultVersion. When present, an already-installed project
    // node_modules copy is only trusted if its own package.json "version" actually satisfies
    // the constraint (CompilerDownloader.installedPackageVersion + SemverResolver) — a
    // mismatch falls through to Mycelium's own cache/download at the constraint-resolved
    // version instead of silently using whatever happens to be installed.
    private fun resolveCompilerLocation(
        languageId: String,
        npmPackageName: String,
        version: String,
        sourcePath: Path
    ): Path {
        val constraint = findPackageJsonConstraint(sourcePath.parent, npmPackageName)

        findProjectNodeModulesDir(sourcePath.parent, npmPackageName)?.let { dir ->
            if (constraint == null) return dir
            val installedVersion = downloader.installedPackageVersion(dir, npmPackageName)
            val range = org.mycelium.library.registry.SemverResolver.parseRange(constraint.versionSpec)
            val satisfies = installedVersion != null && (range == null || range.satisfies(installedVersion))
            if (satisfies) return dir
            // Mismatched (or unverifiable) project install — fall through to Mycelium's own
            // cache/download at the constraint-resolved version below instead of trusting it.
        }

        val effectiveVersion = if (constraint != null) {
            downloader.resolveVersion(npmPackageName, constraint.versionSpec) ?: constraint.versionSpec
        } else {
            version
        }

        val cacheDir = downloader.cacheDirFor(languageId, effectiveVersion)
        if (downloader.isInstalledAt(cacheDir, npmPackageName)) return cacheDir

        return downloader.ensureDownloaded(languageId, npmPackageName, effectiveVersion)
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

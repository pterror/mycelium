package org.mycelium.library.compiler

import org.graalvm.polyglot.Context
import org.mycelium.library.MyceliumException
import java.nio.file.Path
import kotlin.io.path.readText

// Ties together CompilerRegistry (which compile-to-JS languages Mycelium knows about),
// CompilerCache (content-hash-based disk caching of their compiled output), and whichever
// CompileToJsBackend actually does the compiling — the single entry point
// InterceptingFileSystem calls once it's decided (via LanguageSupport.jsCompilerId) that a
// file needs compiling to JS before GraalJS can evaluate it.
//
// Note on "pre-built output already exists" (the very first entry in the compiler
// resolution order described in ROADMAP/the phase-3 design): that case never reaches this
// object at all. It's handled implicitly by InterceptingFileSystem only ever routing a file
// through CompileToJsDispatcher.compile when *that specific file's own extension* is a
// compile-to-JS language's (e.g. .coffee/.civet/.ts) — an imported package whose
// package.json "main"/"exports" already points at plain prebuilt .js is resolved and read as
// an ordinary JS file by NodeModuleResolver and never has a compile-to-JS jsCompilerId
// extension to trigger this path in the first place.
object CompileToJsDispatcher {
    // The bundled backend, used only for TypeScript (npmPackageName == null in its
    // CompilerSpec) — never resolved/downloaded from npm, so it needs none of the
    // node_modules/cache/download resolution chain the npm-package-backed languages
    // (CoffeeScript, Civet, ...) go through below.
    private val esbuildBackend = EsbuildBackend()

    fun compile(languageId: String, sourcePath: Path, context: Context): String {
        val spec = CompilerRegistry.resolve(languageId)
            ?: throw MyceliumException("mycelium: no compile-to-JS compiler registered for '$languageId'")
        val sourceText = sourcePath.readText()
        val sourceHash = CompilerCache.contentHash(sourceText)

        CompilerCache.get(languageId, spec.defaultVersion, sourceHash)?.let { return it }

        val compiled = if (spec.npmPackageName == null) {
            esbuildBackend.compile(sourceText, sourcePath, spec.compileOptions)
        } else {
            val moduleResolutionDir =
                resolveCompilerLocation(languageId, spec.npmPackageName, spec.defaultVersion, sourcePath)
            val backend = NpmJsApiBackend(context, spec.compileFunction, moduleResolutionDir, spec.outputIsObject)
            backend.compile(sourceText, sourcePath, spec.compileOptions)
        }
        CompilerCache.put(languageId, spec.defaultVersion, sourceHash, compiled)
        return compiled
    }

    // Implements the resolution chain described in the phase-3 design (steps 2-4; step 1 —
    // pre-built output — never reaches this object at all, see the class doc comment above;
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

        val cacheDir = CompilerDownloader.cacheDirFor(languageId, version)
        if (CompilerDownloader.isInstalledAt(cacheDir, npmPackageName)) return cacheDir

        return CompilerDownloader.ensureDownloaded(languageId, npmPackageName, version)
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
            if (CompilerDownloader.isInstalledAt(dir, npmPackageName)) return dir
            dir = dir.parent
        }
        return null
    }
}

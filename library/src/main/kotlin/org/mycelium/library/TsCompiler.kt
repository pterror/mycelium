package org.mycelium.library

import io.mvnpm.esbuild.Bundler
import io.mvnpm.esbuild.model.BundleOptions
import io.mvnpm.esbuild.model.EsBuildConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readText

// Transpiles TypeScript (.ts/.mts) source to plain JS via esbuild (io.mvnpm:esbuild-java),
// so GraalJS — which has no TypeScript support of its own — receives syntax it understands.
// This is transform-only (`bundle = false`): esbuild strips types/syntax from exactly the
// one file handed to it and leaves every `import`/`export` specifier untouched, so relative
// and cross-language imports inside the transpiled output continue to be resolved by
// InterceptingFileSystem itself rather than by esbuild's own (bundling) resolver.
object TsCompiler {
    // esbuild invocations spawn a subprocess and (on first use) may download an esbuild
    // binary, so results are cached on disk by content hash — mirroring Compiler.compile's
    // cache for the LLVM compilation path.
    private val cacheDir = File(
        System.getenv("XDG_CACHE_HOME") ?: File(System.getProperty("user.home"), ".cache").path,
        "mycelium/ts"
    ).also { it.mkdirs() }

    fun transpile(sourcePath: Path): String {
        val sourceText = sourcePath.readText()
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(sourceText.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val cacheFile = File(cacheDir, "$hash.js")
        if (cacheFile.exists()) return cacheFile.readText()

        val workDir = Files.createTempDirectory("mycelium-esbuild-")
        try {
            val options = BundleOptions.builder()
                .addEntryPoint(sourcePath.parent, sourcePath.fileName.toString())
                .withWorkDir(workDir)
                .withEsConfig(
                    EsBuildConfig.builder()
                        .withDefault()
                        .bundle(false)
                        .format(EsBuildConfig.Format.ESM)
                        .build()
                )
                .build()
            val result = Bundler.bundle(options, false)
            // esbuild's default `withDefault()` config hashes output filenames (e.g.
            // "typed-C4ASCX62.js"), so the exact name can't be predicted — but with a
            // single (non-bundled) entry point, dist/ contains exactly one .js output
            // (plus its .map), so picking the lone .js file is unambiguous.
            val outputFile = result.dist().toFile()
                .listFiles { f -> f.extension == "js" }
                ?.singleOrNull()
                ?: throw MyceliumException(
                    "mycelium: esbuild produced no single .js output for '${sourcePath.fileName}' in ${result.dist()}"
                )
            val js = outputFile.readText()
            cacheFile.writeText(js)
            return js
        } catch (e: Exception) {
            throw MyceliumException(
                "mycelium: failed to transpile TypeScript '${sourcePath.fileName}' via esbuild — ${e.message}",
                e
            )
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }
}

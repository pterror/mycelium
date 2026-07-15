package org.mycelium.library

import io.mvnpm.esbuild.Bundler
import io.mvnpm.esbuild.model.BundleOptions
import io.mvnpm.esbuild.model.EsBuildConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readText

// Transpiles CommonJS (require()/module.exports) source to ESM via esbuild — mirrors
// TsCompiler's approach (transform-only, `bundle = false`) for the same reasons: esbuild
// must leave every `require()` call and relative/bare specifier untouched so InterceptingFileSystem
// keeps resolving them itself rather than esbuild's own (bundling) resolver.
//
// With `bundle = false`, esbuild's `format = esm` output for a CJS file is a lazily-invoked
// wrapper ending in `export default <wrapper>();` — the module's `module.exports` value
// becomes the module's default export, and any `require(...)` calls inside are left as
// plain (free-variable) calls rather than rewritten to `import` (esbuild only does that
// rewrite while bundling, since it needs to statically resolve targets to hoist them).
// InterceptingFileSystem therefore prepends a small `require` shim (closing over the
// requiring file's directory) ahead of this output — see
// InterceptingFileSystem.newByteChannel's CJS branch — so the free `require` identifier
// resolves to Mycelium's own module loader instead of being undefined at runtime.
object CjsCompiler {
    private val cacheDir = File(
        System.getenv("XDG_CACHE_HOME") ?: File(System.getProperty("user.home"), ".cache").path,
        "mycelium/cjs"
    ).also { it.mkdirs() }

    fun transpile(sourcePath: Path): String {
        val sourceText = sourcePath.readText()
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(sourceText.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val cacheFile = File(cacheDir, "$hash.js")
        if (cacheFile.exists()) return cacheFile.readText()

        val workDir = Files.createTempDirectory("mycelium-esbuild-cjs-")
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
            // Same reasoning as TsCompiler: hashed output filenames mean the exact name
            // can't be predicted, but a single non-bundled entry point produces exactly
            // one .js output in dist/.
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
                "mycelium: failed to transpile CommonJS '${sourcePath.fileName}' via esbuild — ${e.message}",
                e
            )
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }
}

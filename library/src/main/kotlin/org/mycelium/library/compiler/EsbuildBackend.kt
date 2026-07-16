package org.mycelium.library.compiler

import io.mvnpm.esbuild.Bundler
import io.mvnpm.esbuild.model.BundleOptions
import io.mvnpm.esbuild.model.EsBuildConfig
import org.mycelium.library.MyceliumException
import java.nio.file.Files
import java.nio.file.Path

// The bundled compile-to-JS backend, used for TypeScript today: transpiles a single file to
// plain JS via esbuild (io.mvnpm:esbuild-java), so GraalJS — which has no TypeScript support
// of its own — receives syntax it understands. This is transform-only (`bundle = false`):
// esbuild strips types/syntax from exactly the one file handed to it and leaves every
// `import`/`export` specifier untouched, so relative and cross-language imports inside the
// transpiled output continue to be resolved by InterceptingFileSystem itself rather than by
// esbuild's own (bundling) resolver.
//
// Lifted from the former TsCompiler.kt's transpile logic — the disk-caching that class used
// to do itself now lives in the generalized CompilerCache, driven by CompileToJsDispatcher.
class EsbuildBackend : CompileToJsBackend {
    override fun compile(source: String, sourcePath: Path, options: Map<String, Any>): String {
        val workDir = Files.createTempDirectory("mycelium-esbuild-")
        try {
            val bundleOptions = BundleOptions.builder()
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
            val result = Bundler.bundle(bundleOptions, false)
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
            return outputFile.readText()
        } catch (e: Exception) {
            throw MyceliumException(
                "mycelium: failed to transpile '${sourcePath.fileName}' via esbuild — ${e.message}",
                e
            )
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }
}

package org.mycelium.library.compiler

import java.io.File
import java.security.MessageDigest

// Content-hash-based disk cache for compile-to-JS output, generalizing the pattern
// TsCompiler.kt used to implement just for TypeScript/esbuild. Cache entries are keyed by
// (languageId, compilerVersion, sourceContentHash) so that:
//   - different languages never collide with each other (languageId),
//   - upgrading a compile-to-JS backend's bundled/npm-resolved version invalidates
//     previously-cached output rather than silently reusing stale JS (compilerVersion),
//   - identical source text is compiled exactly once regardless of which file/path it lives
//     at (sourceContentHash) — matching Compiler.compile's existing LLVM/WASM cache.
object CompilerCache {
    private val baseCacheDir = File(
        System.getenv("XDG_CACHE_HOME") ?: File(System.getProperty("user.home"), ".cache").path,
        "mycelium/compiled-js"
    )

    private fun cacheDirFor(languageId: String): File =
        File(baseCacheDir, languageId).also { it.mkdirs() }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun contentHash(sourceText: String): String = sha256(sourceText)

    private fun cacheKey(compilerVersion: String, sourceContentHash: String): String =
        sha256("$compilerVersion:$sourceContentHash")

    fun get(languageId: String, compilerVersion: String, sourceContentHash: String): String? {
        val file = File(cacheDirFor(languageId), "${cacheKey(compilerVersion, sourceContentHash)}.js")
        return if (file.exists()) file.readText() else null
    }

    fun put(languageId: String, compilerVersion: String, sourceContentHash: String, compiled: String) {
        val file = File(cacheDirFor(languageId), "${cacheKey(compilerVersion, sourceContentHash)}.js")
        file.writeText(compiled)
    }
}

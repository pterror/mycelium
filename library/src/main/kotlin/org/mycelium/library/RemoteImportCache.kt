package org.mycelium.library

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

// Disk cache for RemoteFileSystem reads (S3, GCS, WebDAV, ...) — same on-disk-cache-under-
// XDG_CACHE_HOME shape, content-addressed by URI, immutable-once-fetched semantics as
// HttpImportCache, just generalized over "however the caller obtains the bytes" (a
// RemoteFileSystem.read call) rather than being wired to java.net.http directly.
object RemoteImportCache {
    private val cacheDir: File = File(
        System.getenv("XDG_CACHE_HOME") ?: File(System.getProperty("user.home"), ".cache").path,
        "mycelium/remote-imports"
    )

    // Returns a local on-disk path to `uri`'s cached content, calling `read` to obtain the
    // bytes (and caching them) only the first time `uri` is seen.
    fun fetch(uri: URI, read: (URI) -> ByteArray): Path {
        val hash = sha256Hex(uri.toString())
        val name = uri.path?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: "download"
        val cached = File(cacheDir, "$hash-$name")
        if (cached.isFile) return cached.toPath()

        cacheDir.mkdirs()
        val bytes = read(uri)
        val tempFile = Files.createTempFile(cacheDir.toPath(), "download-", ".tmp")
        Files.write(tempFile, bytes)
        Files.move(tempFile, cached.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return cached.toPath()
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

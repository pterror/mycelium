package org.mycelium.library

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

// Caches http(s) import/require targets (`import 'https://esm.sh/lodash'`,
// `require 'https://.../foo.rb'`) to disk under XDG_CACHE_HOME/mycelium/http-imports, keyed
// by a SHA-256 of the URL — same on-disk-cache-under-XDG_CACHE_HOME shape as
// PackageCache/HttpMetadataCache/ToolResolver's GitHubRelease, but content-addressed by URL
// rather than by (ecosystem, name, version): a URL import has no separate version identifier
// of its own the way a registry package does. Deliberately no revalidation (no ETag/
// If-Modified-Since, unlike HttpMetadataCache) — once fetched, a URL import is treated as
// immutable for the cache's lifetime, matching how Deno/browser-style URL imports are
// normally published (a versioned or content-hashed CDN URL) rather than something expected
// to change in place under the same URL.
object HttpImportCache {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val cacheDir: File = File(
        System.getenv("XDG_CACHE_HOME") ?: File(System.getProperty("user.home"), ".cache").path,
        "mycelium/http-imports"
    )

    // Fetches `uri`, returning a local on-disk path to its cached content — downloading it
    // only the first time `uri` is seen. The file name keeps the URL's own basename (a
    // hash-only name would still be correct, but Path.extension-driven language/compiler
    // detection elsewhere reads the file's own extension) prefixed with a hash of the full
    // URL, so the cache key is still the whole URL rather than just its basename.
    fun fetch(uri: URI): Path {
        val hash = sha256Hex(uri.toString())
        val name = uri.path?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: "download"
        val cached = File(cacheDir, "$hash-$name")
        if (cached.isFile) return cached.toPath()

        cacheDir.mkdirs()
        val request = HttpRequest.newBuilder(uri)
            // Some websites send special text-only output to `curl` rather than their usual
            // browser-targeted HTML wrapper.
            .header("User-Agent", "curl/8.9.1")
            .GET()
            .build()
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: InterruptedException) {
            throw IOException("interrupted fetching $uri", e)
        }
        if (response.statusCode() !in 200..299) {
            throw IOException("HTTP ${response.statusCode()} fetching $uri")
        }
        val tempFile = Files.createTempFile(cacheDir.toPath(), "download-", ".tmp")
        Files.write(tempFile, response.body())
        Files.move(tempFile, cached.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return cached.toPath()
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

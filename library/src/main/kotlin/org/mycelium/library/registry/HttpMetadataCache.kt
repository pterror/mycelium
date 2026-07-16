package org.mycelium.library.registry

import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

// Local disk cache for package-index HTTP metadata responses (registry JSON documents),
// mirroring rhizone's normalize-package-index/src/cache.rs — conditional requests
// (If-None-Match/If-Modified-Since) plus a short freshness window so repeatedly resolving
// the same package within one build/session doesn't re-fetch (or even re-validate) its
// metadata every time. Deliberately independent of PackageIndex/PackageMeta: this is HTTP
// plumbing any ecosystem's registry client can reuse (parameterized by `ecosystem` and a
// cache key), not a place for npm-specific parsing.
object HttpMetadataCache {
    private val baseCacheDir = File(
        System.getenv("XDG_CACHE_HOME") ?: File(System.getProperty("user.home"), ".cache").path,
        "mycelium/packages"
    )

    private fun safeKey(key: String): String =
        key.map { c -> if (c.isLetterOrDigit() || c in "-_.@") c else '_' }.joinToString("")

    private fun dataFile(ecosystem: String, cacheKey: String): File =
        File(baseCacheDir, "$ecosystem/.metadata/${safeKey(cacheKey)}.json")

    private fun metaFile(ecosystem: String, cacheKey: String): File =
        File(baseCacheDir, "$ecosystem/.metadata/${safeKey(cacheKey)}.meta.json")

    private data class CacheMeta(val etag: String?, val lastModified: String?, val cachedAt: Long)

    private fun readMeta(ecosystem: String, cacheKey: String): CacheMeta? {
        val file = metaFile(ecosystem, cacheKey)
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText())
            CacheMeta(
                etag = json.optString("etag").takeIf { it.isNotEmpty() },
                lastModified = json.optString("lastModified").takeIf { it.isNotEmpty() },
                cachedAt = json.optLong("cachedAt", 0L),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun writeMeta(ecosystem: String, cacheKey: String, meta: CacheMeta) {
        val file = metaFile(ecosystem, cacheKey)
        file.parentFile?.mkdirs()
        val json = JSONObject()
        meta.etag?.let { json.put("etag", it) }
        meta.lastModified?.let { json.put("lastModified", it) }
        json.put("cachedAt", meta.cachedAt)
        file.writeText(json.toString())
    }

    // `statusCode` is the *logical* status the caller should act on: a real cache hit (fresh
    // within `maxAge`, or revalidated via a 304) is reported as 200 even though no live
    // response (or a bodiless 304) actually came back, so callers can branch on
    // `result.statusCode == 404` exactly as they would on a raw HttpResponse without having
    // to know whether the cache served the answer.
    data class Result(val body: String, val fromCache: Boolean, val statusCode: Int)

    // Fetches `url`, using a locally cached copy when it's fresh (`maxAge`), or validating
    // it with a conditional request (304 -> reuse cached body) otherwise. Falls back to an
    // unconditional GET (and caches the new response) when there's no usable cache entry.
    fun fetch(
        httpClient: HttpClient,
        ecosystem: String,
        cacheKey: String,
        url: String,
        maxAge: Duration,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Result {
        val meta = readMeta(ecosystem, cacheKey)
        val dataFile = dataFile(ecosystem, cacheKey)

        if (meta != null && dataFile.isFile) {
            val age = Instant.now().epochSecond - meta.cachedAt
            if (age in 0..maxAge.seconds) {
                return Result(dataFile.readText(), fromCache = true, statusCode = 200)
            }
        }

        val requestBuilder = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .header("User-Agent", "mycelium (pure-kotlin package index client)")
            .timeout(Duration.ofSeconds(30))
        // setHeader (not header) so an ecosystem-specific override — e.g. crates.io's
        // required descriptive User-Agent — replaces this method's default value instead of
        // being sent as a second, additional header line of the same name.
        extraHeaders.forEach { (k, v) -> requestBuilder.setHeader(k, v) }
        if (meta?.etag != null) requestBuilder.header("If-None-Match", meta.etag)
        if (meta?.lastModified != null) requestBuilder.header("If-Modified-Since", meta.lastModified)

        val response = httpClient.send(requestBuilder.GET().build(), HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() == 304 && dataFile.isFile) {
            writeMeta(ecosystem, cacheKey, meta!!.copy(cachedAt = Instant.now().epochSecond))
            return Result(dataFile.readText(), fromCache = true, statusCode = 200)
        }

        val body = response.body()
        if (response.statusCode() in 200..299) {
            dataFile.parentFile?.mkdirs()
            Files.writeString(dataFile.toPath(), body)
            writeMeta(
                ecosystem,
                cacheKey,
                CacheMeta(
                    etag = response.headers().firstValue("ETag").orElse(null),
                    lastModified = response.headers().firstValue("Last-Modified").orElse(null),
                    cachedAt = Instant.now().epochSecond,
                ),
            )
        }
        return Result(body, fromCache = false, statusCode = response.statusCode())
    }
}

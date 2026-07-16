package org.mycelium.library.registry

import org.json.JSONObject
import java.net.http.HttpClient
import java.time.Duration

// Pure-Kotlin npm registry client — no `npm`/`pnpm`/`node` subprocess required. Mirrors
// rhizone's NpmIndex (normalize-package-index/src/index/npm.rs): GET
// registry.npmjs.org/{name} for full metadata (all versions + dist-tags), or GET
// registry.npmjs.org/{name}/{version} for a single version's metadata (cheaper — the
// registry returns just that one version's JSON instead of the whole document, which for
// popular packages with hundreds of published versions can be megabytes).
class NpmRegistryClient(
    private val registryBase: String = "https://registry.npmjs.org",
    // One shared HttpClient instance for the client's lifetime, per the java.net.http docs:
    // HttpClient pools and reuses its underlying connections (and, for servers that support
    // it, negotiates HTTP/2 with multiplexed streams over a single TCP connection) — but only
    // across requests made through the *same* HttpClient instance. A fresh HttpClient per
    // request would pay a new TCP+TLS(+ALPN) handshake every time, which is exactly the
    // per-request overhead this class exists to avoid.
    private val httpClient: HttpClient = defaultHttpClient(),
) : PackageIndex {
    override val ecosystem: String = "npm"

    // How long a cached metadata document is trusted without even a conditional
    // (If-None-Match/If-Modified-Since) revalidation request — short enough that a build
    // resolving the same package repeatedly (e.g. across a monorepo's workspaces) within one
    // sitting sees fresh-enough data, long enough to avoid hammering the registry.
    private val metadataMaxAge: Duration = Duration.ofMinutes(5)

    companion object {
        fun defaultHttpClient(): HttpClient =
            HttpClient.newBuilder()
                // Prefer HTTP/2 (registry.npmjs.org supports it): a single TCP connection is
                // enough to multiplex metadata + tarball requests for many packages at once,
                // rather than opening one TCP connection per concurrent request the way
                // HTTP/1.1 connection pooling would.
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
    }

    // Scoped package names (@scope/name) must have their "/" percent-encoded when used as a
    // single path segment — the registry otherwise parses "@scope" and "name" as two
    // separate path segments (which happens to also be a valid-looking URL, just the wrong
    // one: GET /@scope/name works today because npm's registry special-cases it, but GET
    // /@scope/name/version does NOT, since the version becomes path segment 3 instead of 2).
    private fun encodedName(name: String): String =
        if (name.startsWith("@")) {
            val slash = name.indexOf('/')
            if (slash < 0) name else name.substring(0, slash) + "%2f" + name.substring(slash + 1)
        } else {
            name
        }

    // Metadata documents (package-info/version-list JSON) are cached to disk via
    // HttpMetadataCache, keyed by ecosystem ("npm") + request path, with conditional
    // revalidation (ETag/Last-Modified) on cache misses/expiry. Tarball downloads are a
    // separate concern already handled by PackageCache and never go through this cache.
    private fun getMetadata(path: String): HttpMetadataCache.Result =
        HttpMetadataCache.fetch(
            httpClient = httpClient,
            ecosystem = ecosystem,
            cacheKey = path,
            url = "$registryBase$path",
            maxAge = metadataMaxAge,
        )

    override fun fetchMeta(name: String, version: String?): PackageMeta {
        if (version == null || version == "latest") {
            val response = getMetadata("/${encodedName(name)}")
            if (response.statusCode == 404) throw PackageNotFoundException(name)
            val doc = JSONObject(response.body)
            val latestVersion = doc.optJSONObject("dist-tags")?.optString("latest")
                ?: throw PackageNotFoundException(name)
            val versionDoc = doc.optJSONObject("versions")?.optJSONObject(latestVersion)
                ?: throw PackageNotFoundException(name, latestVersion)
            return parseVersion(name, latestVersion, versionDoc)
        }

        // The single-version endpoint (GET /{name}/{version}) returns just that version's
        // package.json-shaped document directly — avoids downloading every other published
        // version's metadata just to pick out one.
        val response = getMetadata("/${encodedName(name)}/$version")
        if (response.statusCode == 404) throw PackageNotFoundException(name, version)
        val versionDoc = JSONObject(response.body)
        return parseVersion(name, version, versionDoc)
    }

    override fun fetchVersions(name: String): List<VersionMeta> {
        val response = getMetadata("/${encodedName(name)}")
        if (response.statusCode == 404) throw PackageNotFoundException(name)
        val doc = JSONObject(response.body)
        val versions = doc.optJSONObject("versions") ?: throw PackageNotFoundException(name)
        val time = doc.optJSONObject("time")
        return versions.keySet().map { v ->
            val versionDoc = versions.optJSONObject(v)
            VersionMeta(
                version = v,
                released = time?.optString(v)?.takeIf { it.isNotEmpty() },
                yanked = versionDoc?.opt("deprecated") is String,
            )
        }
    }

    private fun parseVersion(name: String, version: String, doc: JSONObject): PackageMeta {
        val dist = doc.optJSONObject("dist")
        val tarballUrl = dist?.optString("tarball")?.takeIf { it.isNotEmpty() }
            ?: throw PackageNotFoundException(name, version)
        // Prefer the strong `dist.integrity` SRI string (e.g. "sha512-...") when the
        // registry provides one; fall back to the legacy `dist.shasum` (always sha1) that
        // every package has had since before SRI existed.
        val checksum = dist.optString("integrity").takeIf { it.isNotEmpty() }?.let { sri ->
            val dash = sri.indexOf('-')
            if (dash < 0) null else "${sri.substring(0, dash)}:${sri.substring(dash + 1)}"
        } ?: dist.optString("shasum").takeIf { it.isNotEmpty() }?.let { "sha1:$it" }

        return PackageMeta(
            name = doc.optString("name").takeIf { it.isNotEmpty() } ?: name,
            version = doc.optString("version").takeIf { it.isNotEmpty() } ?: version,
            description = doc.optString("description").takeIf { it.isNotEmpty() },
            tarballUrl = tarballUrl,
            checksum = checksum,
            dependencies = toStringMap(doc.optJSONObject("dependencies")),
            peerDependencies = toStringMap(doc.optJSONObject("peerDependencies")),
            devDependencies = toStringMap(doc.optJSONObject("devDependencies")),
            bin = parseBin(doc, name),
        )
    }

    private fun toStringMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        return obj.keySet().associateWith { obj.optString(it) }
    }

    // npm's package.json "bin" field is either a single string (implicitly named after the
    // package) or an object mapping command name -> script path; normalize both to a map.
    private fun parseBin(doc: JSONObject, name: String): Map<String, String> {
        val bin = doc.opt("bin") ?: return emptyMap()
        return when (bin) {
            is String -> {
                val commandName = name.substringAfterLast('/')
                mapOf(commandName to bin)
            }
            is JSONObject -> bin.keySet().associateWith { bin.optString(it) }
            else -> emptyMap()
        }
    }
}

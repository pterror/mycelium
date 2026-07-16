package org.mycelium.library.registry

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// RubyGems client — mirrors rhizone's Gem index (normalize-package-index/src/index/gem.rs):
// GET rubygems.org/api/v1/gems/{name}.json for the current/latest version's full metadata
// (deps, executables, sha256 checksum), GET rubygems.org/api/v1/versions/{name}.json for the
// full version history. Gem tarballs (".gem" files, themselves a tar containing a nested
// data.tar.gz plus metadata.gz — PackageCache only needs the outer download+checksum, not the
// nested unpacking) live at a predictable rubygems.org/gems/{name}-{version}.gem URL, so no
// extra request is needed to find the download location once a version number is known.
class RubyGemsClient(
    private val apiBase: String = "https://rubygems.org/api/v1",
    private val downloadBase: String = "https://rubygems.org/gems",
    private val httpClient: HttpClient = NpmRegistryClient.defaultHttpClient(),
) : PackageIndex {
    override val ecosystem: String = "rubygems"

    private fun get(path: String, base: String = apiBase): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("$base$path"))
            .header("Accept", "application/json")
            .header("User-Agent", "mycelium (pure-kotlin rubygems client)")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    override fun fetchMeta(name: String, version: String?): PackageMeta {
        // /api/v1/gems/{name}.json always describes the current (latest non-prerelease)
        // version — it's the only endpoint with dependencies + executables, so fetch it
        // regardless of which version was asked for (to recover description/bin/deps), then
        // override the version-specific fields (tarball URL, checksum) when an older/other
        // version was explicitly requested.
        val response = get("/gems/$name.json")
        if (response.statusCode() == 404) throw PackageNotFoundException(name)
        val doc = JSONObject(response.body())
        val latestVersion = doc.optString("version").takeIf { it.isNotEmpty() }
            ?: throw PackageNotFoundException(name)

        val resolvedVersion = version?.takeIf { it != "latest" } ?: latestVersion
        val runtimeDeps = doc.optJSONObject("dependencies")?.optJSONArray("runtime")
        val devDeps = doc.optJSONObject("dependencies")?.optJSONArray("development")

        var tarballUrl = "$downloadBase/$name-$resolvedVersion.gem"
        var checksum = doc.optString("sha").takeIf { it.isNotEmpty() }?.let { "sha256:$it" }

        if (resolvedVersion != latestVersion) {
            // gems.json only describes the latest version; look up the requested version's
            // checksum (and confirm it actually exists) in the version-history endpoint.
            val versionsResponse = get("/versions/$name.json")
            if (versionsResponse.statusCode() == 404) throw PackageNotFoundException(name, resolvedVersion)
            val versions = JSONArray(versionsResponse.body())
            val match = (0 until versions.length())
                .map { versions.getJSONObject(it) }
                .firstOrNull { it.optString("number") == resolvedVersion }
                ?: throw PackageNotFoundException(name, resolvedVersion)
            checksum = match.optString("sha256").takeIf { it.isNotEmpty() }?.let { "sha256:$it" }
            tarballUrl = "$downloadBase/$name-$resolvedVersion.gem"
        }

        return PackageMeta(
            name = doc.optString("name").takeIf { it.isNotEmpty() } ?: name,
            version = resolvedVersion,
            description = doc.optString("info").takeIf { it.isNotEmpty() },
            tarballUrl = tarballUrl,
            checksum = checksum,
            dependencies = toDependencyMap(runtimeDeps),
            devDependencies = toDependencyMap(devDeps),
            bin = doc.optJSONArray("executables")?.let { executables ->
                (0 until executables.length()).associate { i ->
                    val cmd = executables.getString(i)
                    cmd to cmd
                }
            } ?: emptyMap(),
        )
    }

    override fun fetchVersions(name: String): List<VersionMeta> {
        val response = get("/versions/$name.json")
        if (response.statusCode() == 404) throw PackageNotFoundException(name)
        val versions = JSONArray(response.body())
        return (0 until versions.length()).map { i ->
            val v = versions.getJSONObject(i)
            VersionMeta(
                version = v.optString("number"),
                released = v.optString("created_at").takeIf { it.isNotEmpty() },
                yanked = v.optBoolean("yanked", false),
            )
        }
    }

    // RubyGems' dependency entries are `{"name": "...", "requirements": ">= 1.0, < 2.0"}`,
    // unlike npm's flat name->range object — flatten to the same Map<String, String> shape
    // PackageMeta expects.
    private fun toDependencyMap(deps: JSONArray?): Map<String, String> {
        if (deps == null) return emptyMap()
        return (0 until deps.length()).associate { i ->
            val dep = deps.getJSONObject(i)
            dep.optString("name") to dep.optString("requirements")
        }
    }
}

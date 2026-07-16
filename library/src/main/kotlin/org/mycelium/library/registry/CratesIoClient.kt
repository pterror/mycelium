package org.mycelium.library.registry

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// crates.io client — mirrors rhizone's CargoIndex (normalize-package-index/src/index/cargo.rs):
// GET crates.io/api/v1/crates/{name} returns both the crate's summary ("crate" object,
// including `max_stable_version`/`max_version`) and every published version's metadata (the
// "versions" array, newest first) in one response — no separate versions endpoint needed
// the way npm/RubyGems/PyPI each require. crates.io's own API docs require a descriptive
// User-Agent identifying the client (they rate-limit/block generic or missing ones), which
// none of the other three registries this codebase talks to enforce.
class CratesIoClient(
    private val apiBase: String = "https://crates.io/api/v1",
    private val httpClient: HttpClient = NpmRegistryClient.defaultHttpClient(),
) : PackageIndex {
    override val ecosystem: String = "crates"

    private fun get(path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("$apiBase$path"))
            .header("Accept", "application/json")
            .header("User-Agent", "mycelium (pure-kotlin crates.io client; https://github.com/mycelium)")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    override fun fetchMeta(name: String, version: String?): PackageMeta {
        val response = get("/crates/$name")
        if (response.statusCode() == 404) throw PackageNotFoundException(name)
        val doc = JSONObject(response.body())
        val crate = doc.optJSONObject("crate") ?: throw PackageNotFoundException(name)
        val versions = doc.optJSONArray("versions") ?: JSONArray()

        val resolvedVersion = version?.takeIf { it != "latest" }
            ?: crate.optString("max_stable_version").takeIf { it.isNotEmpty() }
            ?: crate.optString("max_version").takeIf { it.isNotEmpty() }
            ?: throw PackageNotFoundException(name)

        val versionDoc = (0 until versions.length())
            .map { versions.getJSONObject(it) }
            .firstOrNull { it.optString("num") == resolvedVersion }
            ?: throw PackageNotFoundException(name, resolvedVersion)

        val crateName = crate.optString("id").takeIf { it.isNotEmpty() } ?: name
        val checksum = versionDoc.optString("checksum").takeIf { it.isNotEmpty() }?.let { "sha256:$it" }
        val tarballUrl = versionDoc.optString("dl_path").takeIf { it.isNotEmpty() }
            ?.let { "https://crates.io$it" }
            ?: "$apiBase/crates/$crateName/$resolvedVersion/download"

        return PackageMeta(
            name = crateName,
            version = resolvedVersion,
            description = crate.optString("description").takeIf { it.isNotEmpty() },
            tarballUrl = tarballUrl,
            checksum = checksum,
            dependencies = fetchDependencies(crateName, resolvedVersion),
            bin = versionDoc.optJSONArray("bin_names")?.let { bins ->
                (0 until bins.length()).associate { i -> bins.getString(i) to bins.getString(i) }
            } ?: emptyMap(),
        )
    }

    override fun fetchVersions(name: String): List<VersionMeta> {
        val response = get("/crates/$name")
        if (response.statusCode() == 404) throw PackageNotFoundException(name)
        val doc = JSONObject(response.body())
        val versions = doc.optJSONArray("versions") ?: throw PackageNotFoundException(name)
        return (0 until versions.length()).map { i ->
            val v = versions.getJSONObject(i)
            VersionMeta(
                version = v.optString("num"),
                released = v.optString("created_at").takeIf { it.isNotEmpty() },
                yanked = v.optBoolean("yanked", false),
            )
        }
    }

    // Dependencies aren't included in the /crates/{name} summary response — a separate
    // per-version request is needed (Cargo.toml-shaped: kind "normal"/"dev"/"build", plus a
    // semver "req" range like npm's package.json dependency ranges).
    private fun fetchDependencies(name: String, version: String): Map<String, String> {
        val response = get("/crates/$name/$version/dependencies")
        if (response.statusCode() != 200) return emptyMap()
        val deps = JSONObject(response.body()).optJSONArray("dependencies") ?: return emptyMap()
        return (0 until deps.length())
            .map { deps.getJSONObject(it) }
            .filter { it.optString("kind") == "normal" }
            .associate { it.optString("crate_id") to it.optString("req") }
    }
}

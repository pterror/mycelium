package org.mycelium.library.registry

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// PyPI client — mirrors rhizone's PipIndex (normalize-package-index/src/index/pip.rs): GET
// pypi.org/pypi/{name}/json for the current release's metadata plus every version's file
// list under "releases", or GET pypi.org/pypi/{name}/{version}/json for one specific
// version's document (same shape, "urls" holds just that version's files instead of the
// latest's). Every response — regardless of which endpoint — exposes an "urls" array of
// distribution files (wheels and/or an sdist) for whichever version the document describes;
// prefer the sdist since a source distribution is buildable on any platform, whereas a wheel
// may be platform/ABI-specific.
class PyPIClient(
    private val apiBase: String = "https://pypi.org/pypi",
    private val httpClient: HttpClient = NpmRegistryClient.defaultHttpClient(),
) : PackageIndex {
    override val ecosystem: String = "pypi"

    private fun get(path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("$apiBase$path"))
            .header("Accept", "application/json")
            .header("User-Agent", "mycelium (pure-kotlin pypi client)")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    override fun fetchMeta(name: String, version: String?): PackageMeta {
        val path = if (version == null || version == "latest") "/$name/json" else "/$name/$version/json"
        val response = get(path)
        if (response.statusCode() == 404) throw PackageNotFoundException(name, version)
        val doc = JSONObject(response.body())
        val info = doc.optJSONObject("info") ?: throw PackageNotFoundException(name, version)
        val resolvedVersion = info.optString("version").takeIf { it.isNotEmpty() }
            ?: version ?: throw PackageNotFoundException(name, version)

        val files = doc.optJSONArray("urls") ?: JSONArray()
        val file = (0 until files.length())
            .map { files.getJSONObject(it) }
            .firstOrNull { it.optString("packagetype") == "sdist" }
            ?: (0 until files.length()).map { files.getJSONObject(it) }.firstOrNull()
            ?: throw PackageNotFoundException(name, resolvedVersion)

        val digests = file.optJSONObject("digests")
        val checksum = digests?.optString("sha256")?.takeIf { it.isNotEmpty() }?.let { "sha256:$it" }

        return PackageMeta(
            name = info.optString("name").takeIf { it.isNotEmpty() } ?: name,
            version = resolvedVersion,
            description = info.optString("summary").takeIf { it.isNotEmpty() },
            tarballUrl = file.optString("url"),
            checksum = checksum,
            // PyPI's JSON API only exposes unresolved `requires_dist` strings (e.g.
            // "requests (>=2.0) ; extra == 'foo'"), not a resolved name->range map the way
            // npm's package.json or a gem's dependencies array do — parse just the leading
            // package name and keep the raw requirement string as its "range".
            dependencies = parseRequiresDist(info.optJSONArray("requires_dist")),
        )
    }

    override fun fetchVersions(name: String): List<VersionMeta> {
        val response = get("/$name/json")
        if (response.statusCode() == 404) throw PackageNotFoundException(name)
        val doc = JSONObject(response.body())
        val releases = doc.optJSONObject("releases") ?: throw PackageNotFoundException(name)
        return releases.keySet().map { v ->
            val files = releases.optJSONArray(v)
            val first = if (files != null && files.length() > 0) files.getJSONObject(0) else null
            VersionMeta(
                version = v,
                released = first?.optString("upload_time_iso_8601")?.takeIf { it.isNotEmpty() },
                yanked = first?.optBoolean("yanked", false) ?: false,
            )
        }
    }

    private fun parseRequiresDist(requires: JSONArray?): Map<String, String> {
        if (requires == null) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (i in 0 until requires.length()) {
            val entry = requires.getString(i)
            // Skip conditional (extras-gated) requirements — those apply only to optional
            // extras, not the package's own unconditional dependency set.
            if (entry.contains(";")) continue
            val nameEnd = entry.indexOfFirst { it == ' ' || it == '(' || it == '[' || it == '=' || it == '<' || it == '>' || it == '~' || it == '!' }
            val depName = (if (nameEnd < 0) entry else entry.substring(0, nameEnd)).trim()
            if (depName.isEmpty()) continue
            result[depName] = if (nameEnd < 0) "" else entry.substring(nameEnd).trim()
        }
        return result
    }
}

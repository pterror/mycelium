package org.mycelium.library.registry

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// Go module proxy client — mirrors rhizone's Go index (normalize-package-index/src/index/go.rs):
// GET proxy.golang.org/{module}/@latest for the module's current version, GET
// .../@v/{version}.info for a specific one (both return `{"Version": "...", "Time": "..."}`),
// GET .../@v/list for the full (unordered, one-per-line) version list, and GET
// .../@v/{version}.zip for the module's source archive. Unlike npm/RubyGems/PyPI/crates.io,
// the proxy is a dumb content-addressed mirror with no dependency metadata endpoint at all —
// a module's dependencies only exist in its go.mod, which the proxy does expose per-version
// (`.../@v/{version}.mod`) as a plain-text file in Go's own syntax, not JSON.
class GoProxyClient(
    private val proxyBase: String = "https://proxy.golang.org",
    private val httpClient: HttpClient = NpmRegistryClient.defaultHttpClient(),
) : PackageIndex {
    override val ecosystem: String = "go"

    // The module proxy protocol (golang.org/ref/mod#goproxy-protocol) requires escaping
    // uppercase letters as "!" + the lowercase letter, since module paths are matched
    // case-sensitively on filesystems/URLs that often aren't (e.g. "github.com/BurntSushi/toml"
    // must be requested as "github.com/!burnt!sushi/toml").
    private fun encodeModulePath(module: String): String =
        module.map { c -> if (c.isUpperCase()) "!${c.lowercaseChar()}" else c.toString() }.joinToString("")

    private fun get(path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("$proxyBase$path"))
            .header("Accept", "application/json")
            .header("User-Agent", "mycelium (pure-kotlin go proxy client)")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    override fun fetchMeta(name: String, version: String?): PackageMeta {
        val encoded = encodeModulePath(name)
        val path = if (version == null || version == "latest") "/$encoded/@latest" else "/$encoded/@v/$version.info"
        val response = get(path)
        if (response.statusCode() == 404 || response.statusCode() == 410) throw PackageNotFoundException(name, version)
        val doc = JSONObject(response.body())
        val resolvedVersion = doc.optString("Version").takeIf { it.isNotEmpty() }
            ?: throw PackageNotFoundException(name, version)

        return PackageMeta(
            name = name,
            version = resolvedVersion,
            tarballUrl = "$proxyBase/$encoded/@v/$resolvedVersion.zip",
            // The proxy has no per-module checksum endpoint of its own; go.sum-style hashes
            // come from sum.golang.org, a separate service this client doesn't talk to, so
            // leave verification to PackageCache's normal download-then-hash flow.
            checksum = null,
            dependencies = fetchDependencies(encoded, resolvedVersion),
        )
    }

    override fun fetchVersions(name: String): List<VersionMeta> {
        val encoded = encodeModulePath(name)
        val response = get("/$encoded/@v/list")
        if (response.statusCode() == 404) throw PackageNotFoundException(name)
        val versions = response.body().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (versions.isEmpty()) throw PackageNotFoundException(name)
        return versions.map { VersionMeta(version = it) }
    }

    // go.mod's "require" directives look like either a single-line
    // `require example.com/foo v1.2.3` or a `require (\n\texample.com/foo v1.2.3\n)` block;
    // parse both forms just enough to recover name -> version (Go modules pin exact versions,
    // not ranges, so the "range" half of PackageMeta.dependencies is just that version string).
    private fun fetchDependencies(encodedModule: String, version: String): Map<String, String> {
        val response = get("/$encodedModule/@v/$version.mod")
        if (response.statusCode() != 200) return emptyMap()
        val deps = LinkedHashMap<String, String>()
        var inBlock = false
        for (rawLine in response.body().lines()) {
            val line = rawLine.substringBefore("//").trim()
            if (line.isEmpty()) continue
            if (line == "require (") {
                inBlock = true
                continue
            }
            if (inBlock && line == ")") {
                inBlock = false
                continue
            }
            val requireLine = when {
                inBlock -> line
                line.startsWith("require ") -> line.removePrefix("require ").trim()
                else -> continue
            }
            val parts = requireLine.split(Regex("\\s+"))
            if (parts.size >= 2) deps[parts[0]] = parts[1]
        }
        return deps
    }
}

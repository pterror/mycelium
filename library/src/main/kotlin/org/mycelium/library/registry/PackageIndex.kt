package org.mycelium.library.registry

// Ecosystem-agnostic package-index client, mirroring rhizone's `PackageIndex` trait
// (normalize-package-index/src/index/types.rs) — only trimmed down to what
// CompilerDownloader/PackageCache actually need. NpmRegistryClient is the only
// implementation today, but Mycelium's other LanguageSupport backends (Languages.kt) each
// have their own package ecosystem waiting for the same treatment: RubySupport ->
// rubygems.org, PythonSupport -> pypi.org, C/RustSupport -> crates.io (Rust only; C has no
// registry-driven package manager) once compiled-language dependency fetching moves past
// "shell out to a pre-installed clang/rustc", and TinyGoSupport -> the Go module proxy
// (proxy.golang.org). None of those need a new interface shape, just a new class
// implementing this one and doing that registry's own HTTP dance (PyPI's simple index /
// JSON API, RubyGems' compact index or v1 API, the Go proxy's @v/list + @v/<version>.info
// + .zip endpoints) — PackageCache's tarball-download-verify-extract-hardlink pipeline is
// already generic over "any URL + checksum", so a PyPI wheel/sdist, a .gem, or a Go
// module zip all flow through the exact same PackageCache.ensurePackage/installInto calls
// npm packages do today, just keyed under a different `ecosystem` string.
//
// bun and deno aren't separate *ecosystems* in this sense — both ultimately resolve
// dependencies from this same npm registry (bun entirely; deno for anything not imported
// via a URL or `jsr:` specifier), so NpmRegistryClient/PackageCache already cover them:
// PackageCache.installInto's hardlink-into-node_modules layout is exactly what both of
// them expect to find, so a project using either would resolve against the very same
// on-disk cache a plain npm/node project does. What *would* need new work is (a) reading
// bun.lock/bun.lockb or deno.lock to know which versions to fetch instead of resolving
// fresh, and (b) a JSR client (JSR is its own registry/API, not npm-compatible) for deno's
// `jsr:` specifiers — both are new parsers/clients on top of this same PackageIndex +
// PackageCache foundation, not a redesign of it.
interface PackageIndex {
    // Short ecosystem identifier (e.g. "npm", "pypi", "crates", "rubygems", "maven") —
    // mirrors rhizone's `PackageIndex::ecosystem()`, which is implemented by 60+ registries
    // (normalize-package-index/src/index/mod.rs) sharing this exact trait shape. Used by
    // PackageCache to namespace the on-disk cache (XDG_CACHE_HOME/mycelium/packages/<ecosystem>/...)
    // so two ecosystems' packages of the same name never collide. NpmRegistryClient is the
    // only implementation today, but nothing about this interface, PackageMeta, or
    // PackageCache is npm-specific.
    val ecosystem: String

    // Metadata for the version PackageCache/CompilerDownloader should install: either the
    // exact version asked for, or (when `version` is null/"latest") whatever the ecosystem's
    // notion of "current default version" resolves to (npm: `dist-tags.latest`).
    fun fetchMeta(name: String, version: String? = null): PackageMeta

    // Every published version's minimal metadata (version string + release date + whether
    // it's been deprecated/yanked) — used for version-resolution logic (e.g. semver range
    // matching) without paying for each version's full metadata.
    fun fetchVersions(name: String): List<VersionMeta>
}

// A single resolved package version's metadata — just enough to download, verify, and
// unpack the tarball plus (transitively) resolve its own dependencies. Deliberately not a
// full mirror of package.json; only the fields CompilerDownloader/PackageCache use.
data class PackageMeta(
    val name: String,
    val version: String,
    val description: String? = null,
    // The tarball URL to download (npm registry's `dist.tarball`).
    val tarballUrl: String,
    // "algo:hex" (e.g. "sha1:abcd...", matching rhizone's PackageMeta.checksum format).
    // npm's registry always provides at least a sha1 `dist.shasum`; a stronger
    // `dist.integrity` (SRI, sha512) is preferred when present.
    val checksum: String?,
    // name -> semver range, straight from package.json's "dependencies".
    val dependencies: Map<String, String> = emptyMap(),
    val peerDependencies: Map<String, String> = emptyMap(),
    val devDependencies: Map<String, String> = emptyMap(),
    // command name -> script path, from package.json's "bin" (object form; a string "bin"
    // is normalized to a single entry keyed by the package name, same as npm itself does).
    // Other ecosystems' equivalent (e.g. a gem's executables, a crate's [[bin]] targets)
    // would populate this the same way; ecosystem-specific metadata that doesn't fit any
    // field here belongs in `extra` instead (mirrors rhizone's PackageMeta.extra).
    val bin: Map<String, String> = emptyMap(),
    // Escape hatch for ecosystem-specific metadata that doesn't map onto the fields above
    // (mirrors rhizone's `PackageMeta.extra: HashMap<String, serde_json::Value>`) — kept as
    // raw strings here rather than pulling in a JSON tree type dependency for a field
    // nothing in this codebase reads yet.
    val extra: Map<String, String> = emptyMap(),
)

// Minimal per-version metadata, used for version listing/resolution without fetching each
// version's full metadata (mirrors rhizone's VersionMeta).
data class VersionMeta(
    val version: String,
    val released: String? = null,
    val yanked: Boolean = false,
)

class PackageNotFoundException(name: String, version: String? = null) :
    Exception(if (version == null) "package not found: $name" else "package not found: $name@$version")

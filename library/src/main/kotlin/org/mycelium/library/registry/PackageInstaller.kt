package org.mycelium.library.registry

import java.nio.file.Files
import java.nio.file.Path

// Shared pure-Kotlin package installer used by both InterceptingFileSystem's `--yolo`
// auto-install and Mycelium's manifest-based (package.json) auto-install. Talks directly
// to a PackageIndex (NpmRegistryClient in practice, injected by the caller — Mycelium.kt
// is the composition root that constructs one — rather than hardcoded here) and
// PackageCache's global, content-verified tarball cache — no `npm`/`pnpm`/`node`
// subprocess involved — mirroring CompilerDownloader's approach for downloading
// compile-to-JS compiler packages, just installing into a *project's own* node_modules
// directory instead of a private per-language cache directory.
//
// Version specs are resolved via SemverResolver: an exact version ("1.2.3") is fetched
// as-is; a range ("^1.2.3", "~2.3.0", ">=1.0.0 <2.0.0", "1.x", ...) is resolved against
// every version the registry has actually published (fetchVersions) and the highest
// satisfying one is fetched; anything that isn't a parseable semver range at all (a
// dist-tag like "next"/"beta", or no version specified) is passed straight through to the
// registry, which resolves dist-tags (and "latest" specifically when no version is given)
// itself.
//
// TODO(dedup): node_modules is kept flat (npm v3+ style) unconditionally: the first time
// a package name is encountered anywhere in the tree, its version is installed once at
// the top level and every other occurrence of that name is assumed compatible ("visited"
// dedup — same trick CompilerDownloader uses). No nested node_modules are ever created
// for conflicting versions: if two dependencies genuinely require incompatible versions
// of the same package, whichever is encountered first silently wins. A correct
// implementation would nest the loser under the requiring package's own node_modules,
// the way real npm/pnpm do.
class PackageInstaller(private val registry: PackageIndex) {
    private val exactVersionRegex = Regex("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$")

    // Resolves `versionSpec` to the version string that should be handed to
    // registry.fetchMeta, or null to mean "let the registry pick" (its own "latest"
    // dist-tag, when no version was specified at all).
    //
    // - null (no version specified) -> null (registry's "latest").
    // - a plain "1.2.3"-shaped version -> itself, unchanged.
    // - a semver range ("^1.2.3", "~2.3", ">=1.0.0 <2.0.0", "1.x", "1.0.0 - 2.0.0",
    //   "1.x || >=2.5.0", ...) -> the highest published version (per fetchVersions) that
    //   satisfies it.
    // - anything else (doesn't parse as a semver range at all) -> assumed to be an npm
    //   dist-tag (e.g. "next", "beta") and passed straight through; "latest" itself maps to
    //   null since that's already the registry's default when no version is given.
    private fun resolveVersion(name: String, versionSpec: String?): String? {
        if (versionSpec == null) return null
        if (exactVersionRegex.matches(versionSpec)) return versionSpec
        val range = SemverResolver.parseRange(versionSpec)
        if (range != null) {
            val versions = registry.fetchVersions(name).map { it.version }
            return range.maxSatisfying(versions)
                ?: throw PackageNotFoundException(name, versionSpec)
        }
        return if (versionSpec == "latest") null else versionSpec
    }

    // Installs `name`@`versionSpec` (or whatever the registry resolves as "latest" when
    // versionSpec is null, a range, a dist-tag, etc.) plus, recursively, its runtime
    // dependencies, into `nodeModulesDir` — hardlinking each package's files out of
    // PackageCache's global cache (downloading into that cache first if it isn't already
    // there). Used by `--yolo`'s single-package auto-install.
    //
    // Throws (wrapping the underlying cause) on any failure — network error, package not
    // found, checksum mismatch, etc. — so callers can catch it and fall back to an
    // npm/pnpm subprocess rather than this half-populating node_modules.
    fun installPackageTree(name: String, versionSpec: String?, nodeModulesDir: Path) {
        Files.createDirectories(nodeModulesDir)
        installPackageTreeInto(name, versionSpec, nodeModulesDir, mutableSetOf())
    }

    // Installs every entry of `dependencies` (name -> version spec, e.g. merged from a
    // package.json's "dependencies"/"devDependencies" objects) plus, recursively, each of
    // their own runtime dependencies, into `nodeModulesDir`. Shares one `visited` set
    // across all top-level entries so a package required by two different top-level
    // dependencies is still only installed once. Used by the manifest-based
    // (maybeInstallDependencies) auto-install.
    fun installDependencies(dependencies: Map<String, String>, nodeModulesDir: Path) {
        Files.createDirectories(nodeModulesDir)
        val visited = mutableSetOf<String>()
        for ((name, versionSpec) in dependencies) {
            installPackageTreeInto(name, versionSpec, nodeModulesDir, visited)
        }
    }

    private fun installPackageTreeInto(
        name: String,
        versionSpec: String?,
        nodeModulesDir: Path,
        visited: MutableSet<String>,
    ) {
        if (!visited.add(name)) return
        val meta = registry.fetchMeta(name, resolveVersion(name, versionSpec))
        val cacheDir = PackageCache.ensurePackage(registry.ecosystem, meta)
        PackageCache.installInto(cacheDir, nodeModulesDir, name)
        for ((depName, depRange) in meta.dependencies) {
            installPackageTreeInto(depName, depRange, nodeModulesDir, visited)
        }
    }
}

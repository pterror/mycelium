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
// TODO(semver): like CompilerDownloader, this has no real semver-range resolver yet.
// Exact versions ("1.2.3") are fetched as-is; anything else (a range, "^"/"~" prefix, a
// dist-tag, or no version at all) falls back to whatever the registry's "latest"
// dist-tag currently resolves to. A real range resolver/solver — matching multiple
// candidate versions against a semver range and picking a maximally-shared set across
// the whole dependency graph — is future work if this ever proves insufficient.
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
    // See CompilerDownloader's own exactVersionOrNull for the rationale: only a plain
    // "1.2.3"-shaped version is safe to hand to the registry's single-version endpoint;
    // anything else (including null — "no version specified") falls back to "latest".
    private val exactVersionRegex = Regex("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$")
    private fun exactVersionOrNull(versionSpec: String?): String? =
        versionSpec?.takeIf { exactVersionRegex.matches(it) }

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
        val meta = registry.fetchMeta(name, exactVersionOrNull(versionSpec))
        val cacheDir = PackageCache.ensurePackage(registry.ecosystem, meta)
        PackageCache.installInto(cacheDir, nodeModulesDir, name)
        for ((depName, depRange) in meta.dependencies) {
            installPackageTreeInto(depName, depRange, nodeModulesDir, visited)
        }
    }
}

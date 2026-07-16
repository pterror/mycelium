package org.mycelium.library.compiler

import org.mycelium.library.MyceliumException
import org.mycelium.library.registry.PackageCache
import org.mycelium.library.registry.PackageIndex
import org.mycelium.library.registry.PackageNotFoundException
import org.mycelium.library.registry.SemverResolver
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

// Downloads (and caches on disk) the npm package backing an npm-package-based compile-to-JS
// language (CoffeeScript, Civet, ...) when it isn't already available anywhere else — the
// last resort in CompileToJsDispatcher's resolution chain, after "already in the project's
// own node_modules" and "already downloaded to this cache before" both come up empty.
//
// Talks directly to a PackageIndex (NpmRegistryClient in practice, injected by the caller —
// Mycelium.kt is the composition root that constructs one — rather than hardcoded here) and
// PackageCache's global, content-verified tarball cache — no `npm`/`pnpm`/`node` subprocess
// involved, matching the rest of Mycelium's "no external tools needed" design.
class CompilerDownloader(private val registry: PackageIndex) {
    private val baseCacheDir = File(
        System.getenv("XDG_CACHE_HOME") ?: File(System.getProperty("user.home"), ".cache").path,
        "mycelium/compilers"
    )

    // Where a given language+version's compiler package would live once downloaded — the
    // directory to hand to `require()` (via `__mycelium_require__`) as its resolution root,
    // i.e. the directory that itself directly contains `node_modules/<npmPackageName>`, not
    // the package directory itself.
    fun cacheDirFor(languageId: String, version: String): Path =
        File(baseCacheDir, "$languageId/$version").toPath()

    // True if `npmPackageName` is already installed under `dir/node_modules` — used both to
    // check the cache (has this version already been downloaded?) and, by
    // CompileToJsDispatcher, to check a project's own node_modules tree first.
    fun isInstalledAt(dir: Path, npmPackageName: String): Boolean =
        Files.isDirectory(dir.resolve("node_modules").resolve(npmPackageName))

    // A version string counts as "exact" (safe to hand straight to the registry's
    // single-version endpoint) only when it has no semver-range operators — plain
    // "1.2.3"/"1.2.3-beta.1". Anything else is resolved via SemverResolver against
    // registry.fetchVersions(name)'s published versions (a range, "^"/"~" prefix, x-range,
    // hyphen range, "||"-combined ranges, ...); a version spec that isn't a parseable
    // semver range at all is assumed to be an npm dist-tag (e.g. "next", "beta") and passed
    // straight through to the registry, which resolves dist-tags on its single-version
    // endpoint the same way it resolves exact versions.
    private val exactVersionRegex = Regex("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$")
    private fun exactVersionOrNull(versionSpec: String): String? =
        if (exactVersionRegex.matches(versionSpec)) versionSpec else null

    // See PackageInstaller's own resolveVersion for the full rationale; this mirrors it
    // exactly (both classes independently talk to a PackageIndex, so neither depends on the
    // other, but the resolution policy is identical).
    //
    // Internal (not private) rather than a CompilerDownloader-only implementation detail: a
    // project's own package.json can declare a version *constraint* (e.g.
    // `"coffeescript": "^2.7.0"` in devDependencies) for a compile-to-JS compiler, taking
    // precedence over CompilerSpec.defaultVersion — CompileToJsDispatcher.resolveCompilerLocation
    // needs to resolve that constraint against the real registry the exact same way this class
    // already does for `ensureDownloaded`'s own `version` parameter, so it's exposed here rather
    // than duplicated.
    internal fun resolveVersion(name: String, versionSpec: String): String? {
        exactVersionOrNull(versionSpec)?.let { return it }
        val range = SemverResolver.parseRange(versionSpec)
        if (range != null) {
            val versions = registry.fetchVersions(name).map { it.version }
            return range.maxSatisfying(versions)
                ?: throw PackageNotFoundException(name, versionSpec)
        }
        return if (versionSpec == "latest") null else versionSpec
    }

    // Reads the installed "version" field out of `dir/node_modules/npmPackageName/package.json`
    // — used by CompileToJsDispatcher.resolveCompilerLocation to check whether an
    // already-installed compiler (a project's own node_modules, or Mycelium's own download
    // cache) actually satisfies a version constraint the project's own package.json declares,
    // rather than blindly trusting whatever happens to be on disk. Returns null if the
    // directory/manifest doesn't exist or doesn't parse — treated by the caller as "can't
    // verify, so don't trust this install" instead of throwing.
    fun installedPackageVersion(dir: Path, npmPackageName: String): String? {
        val pkgJson = dir.resolve("node_modules").resolve(npmPackageName).resolve("package.json")
        if (!Files.isRegularFile(pkgJson)) return null
        return try {
            org.json.JSONObject(Files.readString(pkgJson)).optString("version", null)
        } catch (e: Exception) {
            null
        }
    }

    // Downloads `npmPackageName@version` (plus, transitively, its own runtime dependencies)
    // into cacheDirFor(languageId, version)/node_modules, returning that cache directory
    // once it's populated. Idempotent: if the package is already present (e.g. a previous
    // call, or a previous Mycelium process run), the existing directory is reused rather
    // than re-downloading.
    //
    // Downloads to a sibling temp directory first and renames it into place atomically
    // (Files.move — the temp dir is created as a sibling under the same languageId directory,
    // so the move is same-filesystem and therefore atomic on POSIX) so a process crashing or
    // being killed mid-download can never leave a half-populated cache entry that a later
    // run would mistake for a complete one.
    fun ensureDownloaded(languageId: String, npmPackageName: String, version: String): Path {
        val targetDir = cacheDirFor(languageId, version)
        if (isInstalledAt(targetDir, npmPackageName)) return targetDir

        val languageDir = File(baseCacheDir, languageId).also { it.mkdirs() }
        val tempDir = Files.createTempDirectory(languageDir.toPath(), "download-$version-")
        try {
            val nodeModulesDir = tempDir.resolve("node_modules")
            Files.createDirectories(nodeModulesDir)
            try {
                installPackageTree(npmPackageName, version, nodeModulesDir, mutableSetOf())
            } catch (e: Exception) {
                throw MyceliumException(
                    "mycelium: failed to download compile-to-JS compiler '$npmPackageName@$version': " +
                        (e.message ?: e.toString()),
                    e
                )
            }
            if (!isInstalledAt(tempDir, npmPackageName)) {
                throw MyceliumException(
                    "mycelium: download of '$npmPackageName@$version' completed but '$npmPackageName' is not " +
                        "present in $tempDir/node_modules"
                )
            }
            targetDir.parent?.let { Files.createDirectories(it) }
            // If another process/thread won the race and already populated targetDir between
            // the isInstalledAt check above and here, prefer its result over ours rather than
            // erroring — both installs would have produced an equivalent node_modules anyway.
            if (!isInstalledAt(targetDir, npmPackageName)) {
                Files.move(tempDir, targetDir, StandardCopyOption.ATOMIC_MOVE)
            }
            return targetDir
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    // Installs `name`@`versionSpec` and (recursively) its runtime `dependencies` into
    // `nodeModulesDir`, hardlinking each package's files out of PackageCache's global cache
    // (downloading into that cache first if it isn't already there). `visited` both
    // prevents infinite recursion on dependency cycles and avoids redundant work when
    // multiple packages in the tree depend on the same package.
    private fun installPackageTree(
        name: String,
        versionSpec: String,
        nodeModulesDir: java.nio.file.Path,
        visited: MutableSet<String>,
    ) {
        if (!visited.add(name)) return
        val meta = registry.fetchMeta(name, resolveVersion(name, versionSpec))
        val cacheDir = PackageCache.ensurePackage(registry.ecosystem, meta)
        PackageCache.installInto(cacheDir, nodeModulesDir, name)
        for ((depName, depRange) in meta.dependencies) {
            installPackageTree(depName, depRange, nodeModulesDir, visited)
        }
    }
}

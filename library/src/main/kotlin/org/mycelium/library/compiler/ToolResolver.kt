package org.mycelium.library.compiler

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject
import org.mycelium.library.MyceliumException
import org.mycelium.library.registry.NpmRegistryClient
import org.mycelium.library.registry.SemverResolver
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

// Optional version-compatibility gate a ToolSource checks a resolved candidate binary against
// before accepting it — e.g. "run `elm --version`, pull '0.19.1' out of the raw output, and
// require it to satisfy '^0.19.0'" (the constraint typically coming from a project's own
// package.json devDependency spec — see findPackageJsonConstraint below). `command` is
// everything after the binary itself (typically `listOf("--version")`); `extractor` turns
// whatever that command printed (stdout+stderr combined — different tools pick different
// streams, e.g. `dart --version` prints to stderr) into a bare version string, since every
// tool formats its `--version` output differently ("0.19.1" vs. "CoffeeScript version 2.7.0"
// vs. "Dart SDK version: 3.4.0 ..."). `constraint` is an npm-style semver range (or exact
// version); one that isn't a parseable semver range at all (SemverResolver.parseRange returns
// null — e.g. a dist-tag) is treated as "accept anything", matching CompilerDownloader's own
// dist-tag fallback convention.
data class VersionCheck(
    val command: List<String>,
    val extractor: (String) -> String?,
    val constraint: String,
) {
    // Runs `binary <command>` and reports whether its self-reported version satisfies
    // `constraint`. Best-effort: any failure to launch/parse the version (binary doesn't
    // support --version, output doesn't match `extractor`'s expectations, ...) is treated as
    // "does not satisfy" rather than propagating an exception, so a ToolSource can just skip
    // to its next candidate (or the next ToolSource in ToolResolver's list) exactly like an
    // ordinary "not found here" miss.
    fun accepts(binary: Path): Boolean =
        try {
            val process = ProcessBuilder(listOf(binary.toString()) + command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            val versionString = extractor(output) ?: return false
            val range = SemverResolver.parseRange(constraint) ?: return true
            val version = SemverResolver.parseVersion(versionString) ?: return false
            range.satisfies(version)
        } catch (e: Exception) {
            false
        }
}

// Ecosystem-agnostic "find me a tool's binary" abstraction, extracted out of what used to be
// inline logic in CompileToJsDispatcher (resolveProjectBuildBinary's own "project-local ->
// system PATH -> Mycelium's own download cache -> download" chain). Nothing in this file
// knows about npm/compile-to-JS specifically — a caller (CompileToJsDispatcher today; in
// principle any future non-JS ecosystem support too, see PackageIndex's own doc comment on
// RubySupport/PythonSupport/etc.) composes whichever ToolSource list, in whichever
// precedence order, makes sense for its own tool, and hands it to ToolResolver. This is what
// keeps ToolResolver itself free of any node_modules/PATH/PackageCache knowledge: it only
// knows how to try a list of ToolSource in order (DIP — dependencies/specifics live in the
// ToolSource implementations and in whatever composes the list, not in ToolResolver).

// A single place a tool binary might be found, given just the tool's name (which doubles, for
// the npm-backed sources below, as the npm package name — the two happen to always coincide
// for every tool Mycelium resolves this way today, e.g. PureScript's "spago"). Implementations
// return null for "not found here, try the next source" — they should only ever throw for a
// genuine I/O-level failure, never for an ordinary miss, so ToolResolver can freely fall
// through the whole list.
interface ToolSource {
    fun resolve(toolName: String): Path?
}

// Step 1 of the "the user's own install always wins" precedence every ToolResolver caller in
// this codebase follows: an already-installed local dependency, found via each ecosystem's own
// idiomatic "here's where locally-installed CLI tools land" convention — npm/pnpm/yarn's
// `node_modules/.bin` symlinks (created by the package manager's own install-time postinstall
// step for any dependency declaring a `bin` field), or a Python virtualenv's
// `(.venv|venv)/bin` console-script entry points. Walks `projectRoot` and its ancestors
// (mirroring how node_modules resolution itself walks up looking for the nearest match),
// since the project file that decided a tool is needed (elm.json, spago.yaml, ...) may live
// below the directory a dependency's own node_modules root was installed into.
// `versionCheck` (defaulting to null, "accept whatever's found") lets a caller reject an
// installed local dependency that doesn't satisfy a project-declared version constraint —
// see VersionCheck's own doc comment — so ToolResolver transparently falls through to the
// next source (system PATH, Mycelium's own cache, ...) instead of using a stale/mismatched
// local install.
class ProjectLocalBin(
    private val projectRoot: Path,
    private val versionCheck: VersionCheck? = null,
) : ToolSource {
    private val localBinDirs = listOf("node_modules/.bin", ".venv/bin", "venv/bin")

    override fun resolve(toolName: String): Path? {
        var dir: Path? = projectRoot
        while (dir != null) {
            for (relBinDir in localBinDirs) {
                val candidate = dir.resolve(relBinDir).resolve(toolName)
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate) &&
                    (versionCheck == null || versionCheck.accepts(candidate))
                ) {
                    return candidate
                }
            }
            dir = dir.parent
        }
        return null
    }
}

// Step 2: a global install on the system PATH (`npm install -g <pkg>`, a system package
// manager, the tool's own official installer, ...) — just as much "the user's own install" as
// a local project dependency is, so it too wins over Mycelium auto-downloading its own copy.
// A manual PATH walk (rather than the bare-command-name-handed-to-ProcessBuilder approach used
// elsewhere in Mycelium, e.g. InterceptingFileSystem.yoloInstall's npm/pnpm fallback, which
// just lets the OS resolve the name and catches IOException if it can't) because callers need
// to know *whether* the binary exists on PATH before deciding whether to fall back to
// Mycelium's own auto-download, rather than just invoking it and handling failure.
//
// A class (rather than the plain `object` this used to be) purely so a per-call
// `versionCheck` can be threaded through its constructor — every existing call site that
// doesn't care about version compatibility just becomes `SystemPath()`, so this stays
// source-compatible in spirit with the old singleton, just no longer literally one.
class SystemPath(private val versionCheck: VersionCheck? = null) : ToolSource {
    override fun resolve(toolName: String): Path? {
        val pathEnv = System.getenv("PATH") ?: return null
        for (dir in pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) continue
            val candidate = Path.of(dir, toolName)
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate) &&
                (versionCheck == null || versionCheck.accepts(candidate))
            ) {
                return candidate
            }
        }
        return null
    }
}

// Step 3: already present in Mycelium's own download cache from a previous run (this or a
// different project). `cacheDir` is the version-specific root a previous download (see
// RegistryDownload below) populated or will populate — typically
// CompilerDownloader.cacheDirFor(cacheKey, version). Unlike ProjectLocalBin, Mycelium's own
// cache is populated by hardlinking a package's files as-is (PackageCache), never running an
// ecosystem's own postinstall "symlink bin entries into .bin" step, so the binary's location
// inside the installed package has to be computed explicitly via `binaryRelPath` rather than
// assumed to live at some `.bin`-style convention path.
class CachedPackage(
    private val cacheDir: Path,
    private val binaryRelPath: (packageDir: Path) -> Path,
    private val versionCheck: VersionCheck? = null,
) : ToolSource {
    override fun resolve(toolName: String): Path? {
        val packageDir = cacheDir.resolve("node_modules").resolve(toolName)
        if (!Files.isDirectory(packageDir)) return null
        val binaryPath = binaryRelPath(packageDir)
        if (!Files.exists(binaryPath)) return null
        if (versionCheck != null && !versionCheck.accepts(binaryPath)) return null
        return binaryPath
    }
}

// Step 4, the last resort: not found anywhere else, so download it into Mycelium's own cache
// now (via CompilerDownloader, which talks to a PackageIndex/PackageCache — see their own doc
// comments) for this and every future resolve of this tool+version. Never returns null: unlike
// the other three sources, this one is meant to always succeed or throw (via
// CompilerDownloader's own MyceliumException on download failure).
//
// `cacheKey` defaults to `npmPackage` itself (distinct npm package names never collide) but
// can be overridden when a caller needs a different on-disk cache key — e.g. keeping a
// project-build tool's cache entry (PureScript's spago) from colliding with the
// differently-versioned single-file compiler cache entry for the same languageId (purs).
class RegistryDownload(
    private val downloader: CompilerDownloader,
    private val npmPackage: String,
    private val version: String,
    private val binaryRelPath: (packageDir: Path) -> Path,
    private val cacheKey: String = npmPackage,
) : ToolSource {
    override fun resolve(toolName: String): Path? {
        val cacheDir = downloader.cacheDirFor(cacheKey, version)
        val moduleResolutionDir = if (downloader.isInstalledAt(cacheDir, npmPackage)) {
            cacheDir
        } else {
            downloader.ensureDownloaded(cacheKey, npmPackage, version)
        }
        val packageDir = moduleResolutionDir.resolve("node_modules").resolve(npmPackage)
        val binaryPath = binaryRelPath(packageDir)
        // The npm/PackageCache download pipeline hardlinks a package's files as-is rather
        // than re-running npm's own install-time chmod-the-bin-files step, so a downloaded
        // compiler binary can land without its executable bit set — make sure it's set
        // before ProcessBuilder ever tries to run it.
        if (Files.isRegularFile(binaryPath)) {
            binaryPath.toFile().setExecutable(true)
        }
        return binaryPath
    }
}

// Step 4 variant for tools that are never published to any package registry at all, only
// distributed as standalone platform binaries via GitHub Releases — Gleam's real compiler
// (a Rust binary) is the first example: https://github.com/gleam-lang/gleam/releases ships
// per-platform .tar.gz/.zip assets directly, and the "gleam" package on the npm registry is
// an unrelated isomorphic-entities JS library, so RegistryDownload's npm-shaped
// `<dir>/node_modules/<pkg>` layout can't resolve it at all — this is a parallel last-resort
// source, not a replacement for RegistryDownload (which every npm-backed language still uses
// unchanged).
//
// Downloads `https://github.com/{owner}/{repo}/releases/download/{tag}/{assetName}` (once)
// into `XDG_CACHE_HOME/mycelium/tools/<toolName>/<tag>/`, extracting whichever of .tar.gz/
// .tgz/.zip/.gz `assetName` names — the three asset shapes GitHub release assets in practice
// use (.tar.gz/.tgz and .zip being real archives with their own entry names; a bare .gz,
// scala-cli's own convention, being nothing more than the single binary itself
// gzip-compressed with no archive structure at all — see extractPlainGzip) — and returns the
// path to `toolName` inside that directory. Unlike npm tarballs
// (always rooted at one shared top-level directory, e.g. "package/"), a GitHub release
// asset's own layout varies per project; Gleam's ships its binary unwrapped at the archive's
// own root (a single entry literally named "gleam"), so entries are extracted preserving
// their own relative paths with no directory-stripping assumption baked in here.
//
// Reuses NpmRegistryClient's shared HttpClient (HTTP/2, redirect-following) purely for its
// already-tuned connection settings — GitHub release asset URLs redirect through
// objects.githubusercontent.com, so following redirects is required — even though this class
// has nothing else to do with npm.
class GitHubRelease(
    private val owner: String,
    private val repo: String,
    private val tag: String,
    private val assetName: String,
) : ToolSource {
    override fun resolve(toolName: String): Path? {
        val cacheDir = cacheDirFor(toolName)
        val binaryPath = cacheDir.resolve(toolName)
        if (Files.isRegularFile(binaryPath)) {
            binaryPath.toFile().setExecutable(true)
            return binaryPath
        }

        download(cacheDir, toolName)

        // A caller-supplied `assetName` whose extracted contents don't actually contain a
        // file named `toolName` is treated as an ordinary miss (null), matching every other
        // ToolSource's "null means try the next source" contract for genuine misses — as
        // opposed to throwing, which download() itself still does for actual I/O/HTTP
        // failures (a malformed/unreachable URL is not "try the next source", it's a real
        // error the caller should see).
        if (!Files.isRegularFile(binaryPath)) return null
        binaryPath.toFile().setExecutable(true)
        return binaryPath
    }

    private fun cacheDirFor(toolName: String): Path {
        val xdgCacheHome = System.getenv("XDG_CACHE_HOME") ?: File(System.getProperty("user.home"), ".cache").path
        return File(xdgCacheHome, "mycelium/tools/$toolName/$tag").toPath()
    }

    private fun download(cacheDir: Path, toolName: String) {
        val parentDir = cacheDir.parent
        Files.createDirectories(parentDir)
        val tempDir = Files.createTempDirectory(parentDir, "download-$tag-")
        try {
            val url = "https://github.com/$owner/$repo/releases/download/$tag/$assetName"
            val request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "mycelium (pure-kotlin package registry client)")
                .GET()
                .build()
            val response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                throw MyceliumException(
                    "mycelium: failed to download $owner/$repo release asset '$assetName' " +
                        "(HTTP ${response.statusCode()} from $url)"
                )
            }
            response.body().use { body ->
                when {
                    assetName.endsWith(".tar.gz") || assetName.endsWith(".tgz") -> extractTarGz(body, tempDir)
                    assetName.endsWith(".zip") -> extractZip(body, tempDir)
                    // scala-cli's own release assets (e.g.
                    // "scala-cli-x86_64-pc-linux-static.gz") are the bare gzip-compressed
                    // binary itself, unlike Gleam's .tar.gz — no tar/zip archive structure
                    // (no entry name) at all, so extraction here is just "gunzip straight to
                    // a file named `toolName`" rather than walking archive entries the way
                    // extractTarGz/extractZip do.
                    assetName.endsWith(".gz") -> extractPlainGzip(body, tempDir, toolName)
                    else -> throw MyceliumException(
                        "mycelium: don't know how to extract GitHub release asset '$assetName' " +
                            "(only .tar.gz/.tgz/.zip/.gz are supported)"
                    )
                }
            }
            // If another thread/process already populated cacheDir while we were downloading,
            // prefer its result over ours rather than erroring — both are equally valid,
            // identical downloads of the exact same tagged release asset.
            if (!Files.isDirectory(cacheDir)) {
                Files.move(tempDir, cacheDir, StandardCopyOption.ATOMIC_MOVE)
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun extractTarGz(rawStream: InputStream, destDir: Path) {
        GzipCompressorInputStream(rawStream).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                var entry = tar.nextEntry as TarArchiveEntry?
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val dest = destDir.resolve(entry.name).normalize()
                        if (!dest.startsWith(destDir)) {
                            throw MyceliumException("mycelium: tar entry escapes extraction dir: ${entry.name}")
                        }
                        Files.createDirectories(dest.parent)
                        Files.copy(tar, dest, StandardCopyOption.REPLACE_EXISTING)
                        if (entry.mode and 0b001_000_000 != 0) dest.toFile().setExecutable(true)
                    }
                    entry = tar.nextEntry as TarArchiveEntry?
                }
            }
        }
    }

    private fun extractPlainGzip(rawStream: InputStream, destDir: Path, toolName: String) {
        val dest = destDir.resolve(toolName)
        GzipCompressorInputStream(rawStream).use { gzip ->
            Files.copy(gzip, dest, StandardCopyOption.REPLACE_EXISTING)
        }
        dest.toFile().setExecutable(true)
    }

    private fun extractZip(rawStream: InputStream, destDir: Path) {
        ZipArchiveInputStream(rawStream).use { zip ->
            var entry = zip.nextEntry as ZipArchiveEntry?
            while (entry != null) {
                if (!entry.isDirectory) {
                    val dest = destDir.resolve(entry.name).normalize()
                    if (!dest.startsWith(destDir)) {
                        throw MyceliumException("mycelium: zip entry escapes extraction dir: ${entry.name}")
                    }
                    Files.createDirectories(dest.parent)
                    Files.copy(zip, dest, StandardCopyOption.REPLACE_EXISTING)
                    if (entry.unixMode and 0b001_000_000 != 0) dest.toFile().setExecutable(true)
                } else {
                    Files.createDirectories(destDir.resolve(entry.name).normalize())
                }
                entry = zip.nextEntry as ZipArchiveEntry?
            }
        }
    }

    companion object {
        // Shared rather than one-per-instance for the same reason NpmRegistryClient/
        // PackageCache share theirs — HttpClient pools/reuses connections across requests
        // made through the same instance.
        private val HTTP_CLIENT = NpmRegistryClient.defaultHttpClient()
    }
}

// Downloads and extracts the Dart SDK (https://dart.dev, distributed as a per-platform zip
// rather than via npm or GitHub Releases the way every other ToolSource-resolved tool in this
// file is) — the last-resort source for Dart's `dart2js`/`dart` binaries, used by
// CompilerRegistry's "dart" CompilerSpec. Not npm-shaped at all (no package.json/node_modules
// involved), so this doesn't fit CachedPackage/RegistryDownload's `node_modules/<pkg>`
// convention, and not GitHub-Releases-shaped either (so it doesn't reuse GitHubRelease) — it
// extracts straight into `cacheDir/dart-sdk/...`, mirroring the SDK zip's own top-level
// `dart-sdk/` directory, fetched from
// `https://storage.googleapis.com/dart-archive/channels/stable/release/<version>/sdk/
// dartsdk-<platform>-<arch>-release.zip`.
class DartSdkDownload(
    private val cacheDir: Path,
    private val version: String,
) : ToolSource {
    override fun resolve(toolName: String): Path? {
        val binaryPath = cacheDir.resolve("dart-sdk").resolve("bin").resolve(toolName)
        if (!Files.isRegularFile(binaryPath)) {
            downloadAndExtract()
        }
        if (!Files.isRegularFile(binaryPath)) return null
        binaryPath.toFile().setExecutable(true)
        return binaryPath
    }

    private fun downloadAndExtract() {
        val url =
            "https://storage.googleapis.com/dart-archive/channels/stable/release/$version/sdk/" +
                "dartsdk-${platformName()}-${archName()}-release.zip"
        Files.createDirectories(cacheDir)
        val tempZip = Files.createTempFile(cacheDir, "dart-sdk-", ".zip")
        try {
            URI(url).toURL().openStream().use { input ->
                Files.copy(input, tempZip, StandardCopyOption.REPLACE_EXISTING)
            }
            ZipInputStream(Files.newInputStream(tempZip)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outPath = cacheDir.resolve(entry.name).normalize()
                    if (!outPath.startsWith(cacheDir)) {
                        // Zip-slip guard: refuse to write outside cacheDir.
                        entry = zis.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        Files.createDirectories(outPath)
                    } else {
                        outPath.parent?.let { Files.createDirectories(it) }
                        Files.newOutputStream(outPath).use { out -> zis.copyTo(out) }
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            throw MyceliumException("mycelium: failed to download Dart SDK $version from $url: ${e.message}", e)
        } finally {
            Files.deleteIfExists(tempZip)
        }
    }

    private fun platformName(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") -> "macos"
            os.contains("win") -> "windows"
            else -> "linux"
        }
    }

    private fun archName(): String {
        val arch = System.getProperty("os.arch").lowercase()
        return if (arch.contains("aarch64") || arch.contains("arm")) "arm64" else "x64"
    }
}

// Best-effort resolution cache so repeated `ToolResolver.resolve` calls for the same
// (tool, version constraint) — the common case across many files imported from the same
// project, or across separate `mycelium` invocations — skip the whole PATH-scan/
// node_modules-walk/`--version`-subprocess chain entirely and go straight to a single stat()
// on a previously-resolved path. Persisted as a small JSON file under
// $XDG_CACHE_HOME/mycelium (or ~/.cache/mycelium if unset) — deliberately not the same file
// CompilerDownloader's own package cache uses, since this caches *resolutions* (which path
// won, for which constraint), not package contents.
//
// Invalidation is intentionally conservative and cheap:
//   - the cached path must still exist (a stat(), not a re-run of the whole chain);
//   - if a `packageJsonPath` was supplied (the project manifest whose version constraint led
//     to this resolution), its mtime must not be newer than it was when the entry was cached
//     — a project bumping/changing a devDependency's version spec invalidates the old
//     resolution without Mycelium having to diff the manifest's actual contents.
// Neither check re-invokes `--version` on the candidate binary — that's the whole point: the
// expensive part (subprocess spawn + output parsing) only ever happens once per distinct
// (tool, constraint, manifest-mtime) tuple.
class ToolResolutionCache(private val cacheFile: Path = defaultCacheFile()) {
    private val json: JSONObject = loadJson()

    private fun loadJson(): JSONObject =
        try {
            JSONObject(Files.readString(cacheFile))
        } catch (e: Exception) {
            JSONObject()
        }

    private fun cacheKeyFor(toolName: String, versionConstraint: String?): String =
        "$toolName@${versionConstraint ?: "*"}"

    // Returns the cached resolution for (toolName, versionConstraint) if it's still valid, or
    // null if there's no entry or it failed a cheap validity check (see class doc comment) —
    // either way, the caller should fall through to the full resolution chain exactly as if
    // this cache didn't exist.
    @Synchronized
    fun get(toolName: String, versionConstraint: String?, packageJsonPath: Path?): Path? {
        val entry = json.optJSONObject(cacheKeyFor(toolName, versionConstraint)) ?: return null
        val pathStr = entry.optString("path", "")
        if (pathStr.isEmpty()) return null
        val path = Path.of(pathStr)
        if (!Files.exists(path)) return null
        if (packageJsonPath != null && Files.exists(packageJsonPath)) {
            val cachedMtime = entry.optLong("packageJsonMtime", -1L)
            val actualMtime = Files.getLastModifiedTime(packageJsonPath).toMillis()
            if (cachedMtime < 0 || actualMtime > cachedMtime) return null
        }
        return path
    }

    @Synchronized
    fun put(toolName: String, versionConstraint: String?, packageJsonPath: Path?, resolved: Path) {
        val entry = JSONObject()
        entry.put("path", resolved.toString())
        entry.put("timestamp", System.currentTimeMillis())
        if (packageJsonPath != null && Files.exists(packageJsonPath)) {
            entry.put("packageJsonMtime", Files.getLastModifiedTime(packageJsonPath).toMillis())
        }
        json.put(cacheKeyFor(toolName, versionConstraint), entry)
        persist()
    }

    private fun persist() {
        try {
            cacheFile.parent?.let { Files.createDirectories(it) }
            Files.writeString(cacheFile, json.toString())
        } catch (e: Exception) {
            // Best-effort: a failure to persist the cache (read-only filesystem, disk full,
            // ...) should never fail the compile itself — just means the next run pays the
            // full resolution cost again.
        }
    }

    companion object {
        fun defaultCacheFile(): Path {
            val base = System.getenv("XDG_CACHE_HOME") ?: File(System.getProperty("user.home"), ".cache").path
            return File(base, "mycelium/tool-resolution.json").toPath()
        }
    }
}

// Tries each source in `sources`, in order, returning the first hit; throws if none of them
// resolve `toolName`. The one piece of policy every ToolSource-composing caller shares — as
// opposed to each ToolSource's own npm/PATH/cache-specific lookup mechanics — kept in its own
// tiny class that takes no constructor dependencies of its own, so composing/ordering the
// source list stays entirely the caller's job.
//
// `cache` (a ToolResolutionCache, defaulting to the shared on-disk one) short-circuits the
// whole `sources` walk when a previous resolution for this exact (toolName, versionConstraint)
// is still valid — see ToolResolutionCache's own doc comment. `versionConstraint`/
// `packageJsonPath` are optional purely so this cache lookup/store can happen at all; a caller
// that omits them (every pre-existing call site, from before version-aware resolution existed)
// still gets caching keyed on `(toolName, null)`, just without the manifest-mtime
// invalidation.
class ToolResolver(private val cache: ToolResolutionCache = ToolResolutionCache()) {
    fun resolve(
        toolName: String,
        sources: List<ToolSource>,
        versionConstraint: String? = null,
        packageJsonPath: Path? = null,
    ): Path {
        cache.get(toolName, versionConstraint, packageJsonPath)?.let { return it }
        for (source in sources) {
            val resolved = source.resolve(toolName)
            if (resolved != null) {
                cache.put(toolName, versionConstraint, packageJsonPath, resolved)
                return resolved
            }
        }
        throw MyceliumException(
            "mycelium: could not resolve tool '$toolName' via any of ${sources.size} configured source(s)"
        )
    }
}

// Walks `startDir` and its ancestors looking for the nearest `package.json` that declares a
// version spec for `packageName` in its `dependencies` or `devDependencies` (devDependencies
// checked first, since a compile-to-JS compiler like TypeScript/CoffeeScript is almost always
// a devDependency rather than a runtime one — but either is honored, matching how npm itself
// doesn't distinguish them for resolution purposes). Returns the version spec string as
// written (e.g. "^5.4.0", "~2.7.0", "latest") and the package.json path it came from, or null
// if no ancestor manifest declares this package at all — the caller's cue to fall back to
// CompilerSpec.defaultVersion with no constraint.
data class PackageJsonConstraint(val versionSpec: String, val packageJsonPath: Path)

fun findPackageJsonConstraint(startDir: Path?, packageName: String): PackageJsonConstraint? {
    var dir: Path? = startDir
    while (dir != null) {
        val candidate = dir.resolve("package.json")
        if (Files.isRegularFile(candidate)) {
            try {
                val obj = JSONObject(Files.readString(candidate))
                val devDeps = obj.optJSONObject("devDependencies")
                val deps = obj.optJSONObject("dependencies")
                val spec = devDeps?.optString(packageName, null) ?: deps?.optString(packageName, null)
                if (spec != null) return PackageJsonConstraint(spec, candidate)
            } catch (e: Exception) {
                // Malformed package.json — treat as "no constraint declared here" and keep
                // walking up, matching Mycelium.packageJsonDeclaresDependencies' own
                // "malformed JSON -> nothing to do" convention elsewhere in this codebase.
            }
        }
        dir = dir.parent
    }
    return null
}

package org.mycelium.library.compiler

import org.mycelium.library.MyceliumException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

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
class ProjectLocalBin(private val projectRoot: Path) : ToolSource {
    private val localBinDirs = listOf("node_modules/.bin", ".venv/bin", "venv/bin")

    override fun resolve(toolName: String): Path? {
        var dir: Path? = projectRoot
        while (dir != null) {
            for (relBinDir in localBinDirs) {
                val candidate = dir.resolve(relBinDir).resolve(toolName)
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate
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
object SystemPath : ToolSource {
    override fun resolve(toolName: String): Path? {
        val pathEnv = System.getenv("PATH") ?: return null
        for (dir in pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) continue
            val candidate = Path.of(dir, toolName)
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate
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
) : ToolSource {
    override fun resolve(toolName: String): Path? {
        val packageDir = cacheDir.resolve("node_modules").resolve(toolName)
        if (!Files.isDirectory(packageDir)) return null
        val binaryPath = binaryRelPath(packageDir)
        return if (Files.exists(binaryPath)) binaryPath else null
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

// Tries each source in `sources`, in order, returning the first hit; throws if none of them
// resolve `toolName`. The one piece of policy every ToolSource-composing caller shares — as
// opposed to each ToolSource's own npm/PATH/cache-specific lookup mechanics — kept in its own
// tiny class that takes no constructor dependencies of its own, so composing/ordering the
// source list stays entirely the caller's job.
class ToolResolver {
    fun resolve(toolName: String, sources: List<ToolSource>): Path {
        for (source in sources) {
            source.resolve(toolName)?.let { return it }
        }
        throw MyceliumException(
            "mycelium: could not resolve tool '$toolName' via any of ${sources.size} configured source(s)"
        )
    }
}

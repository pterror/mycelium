package org.mycelium.library

import java.nio.file.Files
import java.nio.file.Path

// Resolves bare specifiers against Python's own package ecosystem (site-packages plus the
// stdlib's own library dirs) — used when a bare import is encountered while the guest call
// stack is inside Python (or as a cross-language fallback from JS/Ruby).
class PythonModuleResolver {
    // Python's `sys.path`, discovered once by shelling out to `python3` and cached for the
    // process's lifetime — mirrors the "run once at startup" approach the task called for,
    // just done lazily (on first bare-specifier resolution that needs it) rather than
    // eagerly at construction, so paying the subprocess cost is skipped entirely for
    // JS-only or Ruby-only projects that never touch Python. Falls back to an empty list
    // (rather than throwing) when python3 isn't on PATH, so Python package resolution just
    // always misses in that case instead of breaking every other import.
    private val pythonSitePackages: List<Path> by lazy { computePythonSearchPaths() }

    // Directories for packages InterceptingFileSystem's own auto-install (backed by
    // PyPIClient + PackageCache/PackageInstaller — see
    // InterceptingFileSystem.yoloInstallPyPIPackage) has fetched into a project-local vendor
    // directory, most-recently-installed first. Consulted ahead of the real `python3`
    // subprocess's own sys.path so a freshly auto-installed package is found immediately.
    private val vendorSearchPaths = java.util.concurrent.CopyOnWriteArrayList<Path>()

    // Registers a freshly-installed PyPI package (`pkgRoot`, e.g.
    // ".mycelium-vendor/pypi/<name>", containing whatever a source distribution's own
    // top-level layout happens to be — there's no single standard the way a wheel's
    // dist-info-driven layout guarantees) so subsequent resolvePythonPackage calls can find
    // it. An sdist's actual importable package directory isn't always `pkgRoot` itself —
    // common shapes are a flat `pkgRoot/<name>/__init__.py` or a "src layout"
    // `pkgRoot/src/<name>/__init__.py` — so this searches both locations (matching `name`
    // case-insensitively with '-'/'_' normalized, the same normalization PyPI itself treats
    // package names as equivalent under) for the directory that should be added to the
    // search path, rather than assuming one specific layout.
    //
    // TODO: only handles the common cases above; an sdist with a nonstandard layout (e.g.
    // the package directory nested more than one level deep, or named differently than the
    // distribution) won't be found by this heuristic and will silently fail to resolve.
    fun registerInstalledPackage(pkgRoot: Path, name: String) {
        val normalized = name.lowercase().replace('-', '_')
        for (candidateSearchDir in listOf(pkgRoot, pkgRoot.resolve("src"))) {
            if (!Files.isDirectory(candidateSearchDir)) continue
            if (Files.isRegularFile(candidateSearchDir.resolve("$normalized.py")) ||
                Files.isRegularFile(candidateSearchDir.resolve(normalized).resolve("__init__.py"))
            ) {
                vendorSearchPaths.add(0, candidateSearchDir)
                return
            }
            val matchByNormalizedName = try {
                Files.list(candidateSearchDir).use { entries ->
                    entries.filter { Files.isDirectory(it) }
                        .filter { it.fileName.toString().lowercase().replace('-', '_') == normalized }
                        .filter { Files.isRegularFile(it.resolve("__init__.py")) }
                        .findFirst()
                        .orElse(null)
                }
            } catch (e: java.io.IOException) {
                null
            }
            if (matchByNormalizedName != null) {
                vendorSearchPaths.add(0, candidateSearchDir)
                return
            }
        }
        // Nothing recognizable found — register the root itself as a last resort, in case
        // the package happens to be a plain top-level module file matching some other name
        // convention resolvePythonPackage's own matching (exact `specifier`) still catches.
        vendorSearchPaths.add(0, pkgRoot)
    }

    fun computePythonSearchPaths(): List<Path> =
        try {
            val process = ProcessBuilder(
                "python3", "-c", "import sys; print('\\n'.join(sys.path))"
            ).redirectErrorStream(false).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { Path.of(it) }
                .filter { Files.isDirectory(it) }
                .toList()
        } catch (e: java.io.IOException) {
            emptyList()
        }

    // Resolves a bare specifier (e.g. "numpy" or "pkg/submodule") against Python's search
    // path: a package directory with an `__init__.py`, or a plain `<specifier>.py` module
    // file. `searchPaths` defaults to the real, subprocess-derived `sys.path` but can be
    // overridden by tests to point at a fake site-packages layout without needing a real
    // Python installation.
    fun resolvePythonPackage(specifier: String, searchPaths: List<Path> = vendorSearchPaths + pythonSitePackages): Path? {
        for (dir in searchPaths) {
            val initPy = dir.resolve(specifier).resolve("__init__.py")
            if (Files.isRegularFile(initPy)) return initPy
            val moduleFile = dir.resolve("$specifier.py")
            if (Files.isRegularFile(moduleFile)) return moduleFile
        }
        return null
    }
}

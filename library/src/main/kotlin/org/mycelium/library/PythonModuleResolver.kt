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
    fun resolvePythonPackage(specifier: String, searchPaths: List<Path> = pythonSitePackages): Path? {
        for (dir in searchPaths) {
            val initPy = dir.resolve(specifier).resolve("__init__.py")
            if (Files.isRegularFile(initPy)) return initPy
            val moduleFile = dir.resolve("$specifier.py")
            if (Files.isRegularFile(moduleFile)) return moduleFile
        }
        return null
    }
}

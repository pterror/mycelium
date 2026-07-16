package org.mycelium.library

import java.nio.file.Files
import java.nio.file.Path

// Resolves bare specifiers against Ruby's own package ecosystem ($LOAD_PATH, covering both
// installed gems' `lib/` directories and the interpreter's own stdlib) — used when a bare
// import is encountered while the guest call stack is inside Ruby (or as a cross-language
// fallback from JS/Python).
class RubyModuleResolver {
    // Ruby's `$LOAD_PATH`, discovered once by shelling out to `ruby` and cached — same
    // lazy-on-first-use, empty-list-on-missing-binary approach as
    // PythonModuleResolver.pythonSitePackages/computePythonSearchPaths.
    private val rubyLoadPaths: List<Path> by lazy { computeRubySearchPaths() }

    // Directories for gems InterceptingFileSystem's own auto-install (backed by
    // RubyGemsClient + PackageCache/PackageInstaller — see
    // InterceptingFileSystem.yoloInstallRubyGem) has fetched into a project-local vendor
    // directory, most-recently-installed first. Consulted ahead of the real `ruby`
    // subprocess's own $LOAD_PATH so a freshly auto-installed gem is found immediately
    // without needing to re-run gem discovery.
    private val vendorSearchPaths = java.util.concurrent.CopyOnWriteArrayList<Path>()

    // Registers `gemRoot` (a freshly-extracted gem's install directory, e.g.
    // ".mycelium-vendor/gems/<name>", laid out exactly like PackageCache extracted its
    // data.tar.gz payload — "lib/foo.rb", "exe/foo", ... with no wrapping directory) so
    // subsequent resolveRubyGem calls can find it. Real RubyGems itself adds a gem's own
    // `lib/` directory (not the gem's root) to $LOAD_PATH, so this mirrors that convention
    // when a `lib/` subdirectory exists, falling back to the root itself for the rare gem
    // that keeps its loadable files at its top level instead.
    fun registerInstalledGem(gemRoot: Path) {
        val libDir = gemRoot.resolve("lib")
        vendorSearchPaths.add(0, if (Files.isDirectory(libDir)) libDir else gemRoot)
    }

    fun computeRubySearchPaths(): List<Path> =
        try {
            val process = ProcessBuilder("ruby", "-e", "puts \$LOAD_PATH")
                .redirectErrorStream(false).start()
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

    // Resolves a bare specifier against Ruby's load path: either `<specifier>.rb` directly
    // in a load-path directory, or the conventional `<specifier>/<specifier>.rb` layout
    // gems use for their main entry file. `searchPaths` defaults to the real,
    // subprocess-derived `$LOAD_PATH` but can be overridden by tests to point at a fake
    // gem directory layout without needing a real Ruby installation.
    fun resolveRubyGem(specifier: String, searchPaths: List<Path> = vendorSearchPaths + rubyLoadPaths): Path? {
        for (dir in searchPaths) {
            val direct = dir.resolve("$specifier.rb")
            if (Files.isRegularFile(direct)) return direct
            val nested = dir.resolve(specifier).resolve("$specifier.rb")
            if (Files.isRegularFile(nested)) return nested
        }
        return null
    }
}

package org.mycelium.app

import org.mycelium.library.Mycelium
import org.mycelium.library.MyceliumException
import org.mycelium.library.compiler.CompilerDownloader
import org.mycelium.library.compiler.CompilerRegistry
import org.mycelium.library.registry.NpmRegistryClient
import java.io.File

// The CLI's own small composition root for the `mycelium compiler ...` subcommands, which
// operate on the compiler registry/downloader directly rather than through a running
// Mycelium instance (unlike `mycelium run`, which constructs its own Mycelium — see
// runFile — with its own equivalent instances). Built once per process rather than per
// subcommand invocation so `compiler list`/`compiler cache` see a consistent registry.
private val registryClient = NpmRegistryClient()
private val compilerDownloader = CompilerDownloader(registryClient)
private val compilerRegistry = CompilerRegistry()

// Parsed command line: the `--yolo`/`--no-install` flags can appear anywhere
// (`mycelium --yolo file.mjs` or `mycelium run --yolo file.mjs`), so they're stripped out
// of `rest` before the remaining positional arguments are dispatched on below. Exposed
// (internal, not private) so it can be exercised directly by a parse-only test without
// spinning up a Mycelium/GraalVM Context.
internal data class ParsedArgs(val yolo: Boolean, val noInstall: Boolean, val rest: List<String>)

internal fun parseArgs(args: Array<String>): ParsedArgs {
    val yolo = args.contains("--yolo")
    val noInstall = args.contains("--no-install")
    val rest = args.filterNot { it == "--yolo" || it == "--no-install" }
    return ParsedArgs(yolo, noInstall, rest)
}

fun main(args: Array<String>) {
    val (yolo, noInstall, rest) = parseArgs(args)

    if (rest.isEmpty()) {
        printUsage()
        return
    }

    when (rest[0]) {
        "run" -> {
            if (rest.size < 2) {
                System.err.println("mycelium: missing file argument")
                System.err.println("usage: mycelium run <file>")
                System.exit(1)
                return
            }
            runFile(rest[1], yolo, noInstall)
        }
        "version" -> println("mycelium 0.1.0-dev")
        "compiler" -> runCompilerCommand(rest.drop(1))
        "help", "--help", "-h" -> printUsage()
        else -> {
            // If it looks like a file path, treat it as implicit `run`
            if (rest[0].contains('.')) {
                runFile(rest[0], yolo, noInstall)
            } else {
                System.err.println("mycelium: unknown command '${rest[0]}'")
                printUsage()
                System.exit(1)
            }
        }
    }
}

private fun runFile(path: String, yolo: Boolean, noInstall: Boolean) {
    try {
        val vm = Mycelium(yolo = yolo, autoInstall = !noInstall)
        vm.runFile(path)
    } catch (e: MyceliumException) {
        System.err.println(e.message)
        System.exit(1)
    }
}

private fun printUsage() {
    println("""
        mycelium — run any file, import any language

        usage:
          mycelium run <file>            Run a file (js, mjs, rb, c, rs, py)
          mycelium <file>                Shorthand for 'mycelium run <file>'
          mycelium compiler fetch <lang>[@<version>]
                                          Prefetch a compile-to-JS compiler
          mycelium compiler list         List known compile-to-JS languages
          mycelium compiler cache        Show cached compiler/package downloads
          mycelium version               Show version
          mycelium help                  Show this help

        flags:
          --yolo                 Auto-install missing npm packages on import
          --no-install           Skip auto-installing dependencies declared in package.json
    """.trimIndent())
}

private fun printCompilerUsage() {
    println("""
        usage:
          mycelium compiler fetch <lang>[@<version>]   Prefetch a compiler
          mycelium compiler list                       List registered languages
          mycelium compiler cache                      Show cached downloads
    """.trimIndent())
}

private fun runCompilerCommand(args: List<String>) {
    if (args.isEmpty()) {
        printCompilerUsage()
        System.exit(1)
        return
    }
    when (args[0]) {
        "fetch" -> {
            if (args.size < 2) {
                System.err.println("mycelium: missing language argument")
                System.err.println("usage: mycelium compiler fetch <lang>[@<version>]")
                System.exit(1)
                return
            }
            compilerFetch(args[1])
        }
        "list" -> compilerList()
        "cache" -> compilerCache()
        else -> {
            System.err.println("mycelium: unknown compiler subcommand '${args[0]}'")
            printCompilerUsage()
            System.exit(1)
        }
    }
}

// Splits "coffeescript@2.7.0" into ("coffeescript", "2.7.0") or "coffeescript" into
// ("coffeescript", null). Scoped npm-style language IDs aren't a thing here (languageId is
// always the CompilerRegistry key, not the npm package name), so a plain lastIndexOf('@') is
// sufficient — no need to special-case a leading '@' the way npm package name parsing would.
private fun parseLangSpec(spec: String): Pair<String, String?> {
    val at = spec.lastIndexOf('@')
    return if (at > 0) spec.substring(0, at) to spec.substring(at + 1) else spec to null
}

private fun compilerFetch(spec: String) {
    val (languageId, versionOverride) = parseLangSpec(spec)
    val compilerSpec = compilerRegistry.resolve(languageId)
    if (compilerSpec == null) {
        System.err.println("mycelium: unknown language '$languageId'")
        System.err.println("run 'mycelium compiler list' to see known languages")
        System.exit(1)
        return
    }
    val npmPackageName = compilerSpec.npmPackageName
    if (npmPackageName == null) {
        println("$languageId is bundled with mycelium — nothing to fetch")
        return
    }
    val version = versionOverride ?: compilerSpec.defaultVersion
    println("Fetching $languageId@$version...")
    try {
        val cacheDir = compilerDownloader.ensureDownloaded(languageId, npmPackageName, version)
        println("Cached at $cacheDir")
    } catch (e: MyceliumException) {
        System.err.println(e.message)
        System.exit(1)
    }
}

private fun compilerList() {
    val languages = compilerRegistry.languageIds().sorted()
    if (languages.isEmpty()) {
        println("(no languages registered)")
        return
    }
    for (languageId in languages) {
        val spec = compilerRegistry.resolve(languageId) ?: continue
        val npmPackageName = spec.npmPackageName
        val cached = when {
            npmPackageName == null -> true // bundled, always "available"
            else -> compilerDownloader.isInstalledAt(
                compilerDownloader.cacheDirFor(languageId, spec.defaultVersion),
                npmPackageName,
            )
        }
        val marker = if (cached) "[cached]" else "[not cached]"
        val pkgDescription = npmPackageName ?: "bundled"
        println("$languageId ($pkgDescription@${spec.defaultVersion}) $marker")
    }
}

private fun compilerCache() {
    val cacheHome = System.getenv("XDG_CACHE_HOME") ?: File(System.getProperty("user.home"), ".cache").path
    val packagesDir = File(cacheHome, "mycelium/packages")
    if (!packagesDir.isDirectory) {
        println("(no cached packages — $packagesDir does not exist)")
        return
    }
    // Cached packages live at packages/<ecosystem>/<name>/<version> (scoped npm names nest one
    // level deeper as packages/<ecosystem>/@scope/pkg/<version>) — walk down to the version
    // directories (leaves that directly contain package files, no further subdirectories that
    // are themselves version-shaped) and report each one's on-disk size.
    val versionDirs = mutableListOf<File>()
    fun walk(dir: File) {
        val children = dir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        if (children.isEmpty()) return
        // A directory is a "version dir" (a leaf to report) if none of its children look like
        // further package-name/version nesting containing a package.json — simplest reliable
        // signal: it directly contains a package.json.
        if (File(dir, "package.json").isFile) {
            versionDirs.add(dir)
            return
        }
        children.forEach(::walk)
    }
    walk(packagesDir)

    if (versionDirs.isEmpty()) {
        println("(no cached packages under $packagesDir)")
        return
    }

    versionDirs.sortBy { it.path }
    for (dir in versionDirs) {
        val relative = dir.relativeTo(packagesDir).path
        val size = dirSize(dir)
        println("$relative  ${formatSize(size)}")
    }
}

private fun dirSize(dir: File): Long =
    dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

private fun formatSize(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) "${bytes}B" else "%.1f%s".format(size, units[unitIndex])
}

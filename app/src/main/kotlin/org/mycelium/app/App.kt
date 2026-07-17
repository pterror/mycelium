package org.mycelium.app

import org.mycelium.library.Mycelium
import org.mycelium.library.MyceliumException
import org.mycelium.library.compiler.CompilerDownloader
import org.mycelium.library.compiler.CompilerRegistry
import org.mycelium.library.config.Config
import org.mycelium.library.config.DaemonConfig
import org.mycelium.library.daemon.DaemonClient
import org.mycelium.library.daemon.DaemonPaths
import org.mycelium.library.registry.NpmRegistryClient
import java.io.File
import java.io.IOException

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
internal data class ParsedArgs(
    val yolo: Boolean,
    val noInstall: Boolean,
    val insightScript: String?,
    val operatorOverloading: Boolean,
    val rest: List<String>,
)

internal fun parseArgs(args: Array<String>): ParsedArgs {
    val yolo = args.contains("--yolo")
    val noInstall = args.contains("--no-install")
    val insightScript = args.firstOrNull { it.startsWith("--insight=") }?.removePrefix("--insight=")
    val operatorOverloading = args.contains("--operator-overloading")
    val rest = args.filterNot { it == "--yolo" || it == "--no-install" || it.startsWith("--insight=") || it == "--operator-overloading" }
    return ParsedArgs(yolo, noInstall, insightScript, operatorOverloading, rest)
}

fun main(args: Array<String>) {
    val parsed = parseArgs(args)
    val yolo = parsed.yolo
    val noInstall = parsed.noInstall
    val insightScript = parsed.insightScript
    val operatorOverloading = parsed.operatorOverloading
    val rest = parsed.rest

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
            runFile(rest[1], yolo, noInstall, insightScript, operatorOverloading)
        }
        "version" -> println("mycelium 0.1.0-dev")
        "compiler" -> runCompilerCommand(rest.drop(1))
        "daemon" -> runDaemonCommand(rest.drop(1))
        "help", "--help", "-h" -> printUsage()
        else -> {
            // If it looks like a file path, treat it as implicit `run`
            if (rest[0].contains('.')) {
                runFile(rest[0], yolo, noInstall, insightScript, operatorOverloading)
            } else {
                System.err.println("mycelium: unknown command '${rest[0]}'")
                printUsage()
                System.exit(1)
            }
        }
    }
}

private fun areModsActive(): Boolean {
    val modsDir = System.getProperty("mycelium.mods.dir")
        ?: System.getenv("MYCELIUM_MODS_DIR")
        ?: "mods"
    val dir = java.io.File(modsDir)
    if (!dir.isDirectory) return false
    val jarFiles = dir.listFiles { f -> f.isFile && f.extension == "jar" }
    return jarFiles != null && jarFiles.isNotEmpty()
}

private fun runFile(path: String, yolo: Boolean, noInstall: Boolean, insightScript: String?, operatorOverloading: Boolean) {
    // JVM-level features (GraalVM Insight instrumentation scripts and the mod loader's
    // -javaagent bytecode patches) can't work through the daemon — the daemon's shared
    // Engine/Context wasn't started with them, so routing through it would silently drop
    // the feature. Skip straight to in-process.
    if (insightScript != null) {
        runFileInProcess(path, yolo, noInstall, insightScript, operatorOverloading)
        return
    }

    if (areModsActive()) {
        runFileInProcess(path, yolo, noInstall, insightScript, operatorOverloading)
        return
    }

    val config = Config.load()
    val absolutePath = File(path).absolutePath
    val exitCode = tryRunViaDaemon(config.daemon, absolutePath, yolo, noInstall, operatorOverloading)
    if (exitCode != null) {
        if (exitCode != 0) System.exit(exitCode)
        return
    }
    runFileInProcess(path, yolo, noInstall, insightScript, operatorOverloading)
}

private fun runFileInProcess(path: String, yolo: Boolean, noInstall: Boolean, insightScript: String?, operatorOverloading: Boolean) {
    try {
        val vm = Mycelium(yolo = yolo, autoInstall = !noInstall, insightScript = insightScript, operatorOverloading = operatorOverloading)
        vm.runFile(path)
    } catch (e: MyceliumException) {
        System.err.println(e.message)
        System.exit(1)
    }
}

// Tries the daemon fast path for `path` (already made absolute by the caller — the entry
// file's own path must be resolved by the client since the daemon's cwd generally isn't
// the caller's, but DaemonClient.runFile separately forwards the caller's actual cwd/env
// on the wire so node_modules resolution, `process.cwd()`, and `process.env` inside the
// script still see the invoking shell's own directory/environment, not the daemon's).
// Returns the exit code to propagate on success, or null if the daemon path wasn't usable
// at all (disabled in config, or couldn't be reached even after an auto-start attempt) —
// callers fall back to `runFileInProcess` in that case, so this never leaves the user
// without a way to run their file.
private fun tryRunViaDaemon(daemonConfig: DaemonConfig, absolutePath: String, yolo: Boolean, noInstall: Boolean, operatorOverloading: Boolean): Int? {
    if (!daemonConfig.enabled) return null
    val socketPath = DaemonPaths.socketFile(daemonConfig.socketDir)
    if (!DaemonClient.isRunning(socketPath)) {
        if (!DaemonLauncher.startAndWait(daemonConfig, socketPath)) return null
    }
    return try {
        DaemonClient.runFile(socketPath, absolutePath, yolo = yolo, noInstall = noInstall, operatorOverloading = operatorOverloading)
    } catch (e: IOException) {
        // Daemon vanished between the isRunning check and the actual request (e.g. hit
        // its idle timeout in that window) — fall back rather than erroring out.
        null
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
          mycelium daemon start          Start the daemon in the background
          mycelium daemon stop           Stop the running daemon
          mycelium daemon status         Show daemon uptime/memory/loaded languages
          mycelium version               Show version
          mycelium help                  Show this help

        flags:
          --yolo                       Auto-install missing npm packages on import
          --no-install                 Skip auto-installing dependencies declared in package.json
          --insight=<script>           Attach a GraalVM Insight instrumentation script
          --operator-overloading       Enable GraalJS's experimental operator overloading

        `mycelium run`/`mycelium <file>` auto-start a background daemon on first use
        (amortizes JVM/GraalVM startup across runs) unless disabled via
        ~/.config/mycelium/config.toml or .mycelium/config.toml — see daemon subcommands
        above to manage it directly.
    """.trimIndent())
}

private fun printDaemonUsage() {
    println("""
        usage:
          mycelium daemon start    Start the daemon in the background (no-op if already running)
          mycelium daemon stop     Stop the running daemon
          mycelium daemon status   Show daemon uptime/memory/loaded languages
    """.trimIndent())
}

private fun runDaemonCommand(args: List<String>) {
    val config = Config.load().daemon
    val socketPath = DaemonPaths.socketFile(config.socketDir)
    if (args.isEmpty()) {
        printDaemonUsage()
        System.exit(1)
        return
    }
    when (args[0]) {
        "start" -> {
            if (DaemonClient.isRunning(socketPath)) {
                println("mycelium: daemon already running (socket: $socketPath)")
                return
            }
            if (DaemonLauncher.startAndWait(config, socketPath)) {
                println("mycelium: daemon started (socket: $socketPath)")
            } else {
                System.err.println("mycelium: daemon failed to start")
                System.exit(1)
            }
        }
        "stop" -> {
            if (!DaemonClient.isRunning(socketPath)) {
                println("mycelium: daemon not running")
                return
            }
            if (DaemonClient.stop(socketPath)) {
                println("mycelium: daemon stopped")
            } else {
                System.err.println("mycelium: failed to stop daemon")
                System.exit(1)
            }
        }
        "status" -> {
            val status = DaemonClient.status(socketPath)
            if (status == null) {
                println("mycelium: daemon not running")
                return
            }
            val usedMb = status.usedMemoryBytes / (1024 * 1024)
            val maxMb = status.maxMemoryBytes / (1024 * 1024)
            val languages = status.loadedLanguages
            println("mycelium daemon — pid ${status.pid}, socket $socketPath")
            println("  uptime: ${status.uptimeSeconds}s, requests served: ${status.requestCount}")
            println("  memory: ${usedMb}MB / ${maxMb}MB")
            println("  languages loaded: ${if (languages.isEmpty()) "(none yet)" else languages.joinToString(", ")}")
            println("  idle timeout: ${status.idleTimeoutSeconds}s")
        }
        else -> {
            System.err.println("mycelium: unknown daemon subcommand '${args[0]}'")
            printDaemonUsage()
            System.exit(1)
        }
    }
}

// Forks a daemon process against the launcher's own classpath/`java` binary and waits
// (polling) for its socket to become connectable, so a caller that just auto-started the
// daemon can immediately hand it a request. Returns false (without leaving a half-started
// process running forever, though the forked JVM is left alive to finish booting even on
// a timeout — a slow-to-start daemon that isn't hung will still come up and be picked up
// by the *next* invocation's `isRunning` check) if the socket never appears in time.
private object DaemonLauncher {
    private const val STARTUP_TIMEOUT_MILLIS = 15_000L
    private const val POLL_INTERVAL_MILLIS = 100L

    fun startAndWait(config: DaemonConfig, socketPath: File): Boolean {
        val javaBin = File(File(System.getProperty("java.home"), "bin"), "java").path
        val classpath = System.getProperty("java.class.path")
        val pidPath = DaemonPaths.pidFile(config.socketDir)

        val command = mutableListOf(
            javaBin,
            "-XX:+UseG1GC",
            "-XX:G1PeriodicGCInterval=1000",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=20",
            "-Xmx${config.jvm.maxHeap}",
        )
        command.addAll(config.jvm.extraArgs)
        command += listOf(
            "-cp", classpath,
            "org.mycelium.library.daemon.DaemonServerKt",
            socketPath.absolutePath,
            pidPath.absolutePath,
            config.idleTimeout.toString(),
        )

        try {
            // `Redirect.DISCARD` is only valid for an output stream (it redirects to
            // /dev/null for writing) — `ProcessBuilder.redirectInput` throws
            // IllegalArgumentException if handed a write-mode Redirect, so stdin gets an
            // explicit read-mode redirect from /dev/null instead.
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .start()
        } catch (e: IOException) {
            System.err.println("mycelium: failed to launch daemon — ${e.message}")
            return false
        }

        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (DaemonClient.isRunning(socketPath)) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return false
    }
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

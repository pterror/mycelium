package org.mycelium.library.compiler

import org.mycelium.library.MyceliumException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

// Project-aware counterpart to SubprocessBackend for the compile-to-JS languages whose real
// compilation unit is a whole project (Elm's elm.json, PureScript's spago.yaml/spago.dhall,
// ReScript's rescript.json/bsconfig.json) rather than a single loose file. See
// CompilerSpec's projectFileNames/projectBuildArgs/resolveProjectOutput fields (what each
// language plugs in here) and CompileToJsDispatcher.compileProjectAware (the caller, which
// consults this before ever falling back to SubprocessBackend's single-file compilation).
//
// A class (constructed once by Mycelium's composition root and shared by every compile)
// rather than a set of top-level functions purely so it can be swapped for a fake in tests
// the same way CompileToJsDispatcher's other collaborators (CompilerDownloader,
// CompilerCache) can — it holds no other state of its own; the "don't rebuild on every
// import" cache this class provides is filesystem-mtime-based (see compile's own doc
// comment), not in-memory, so a fresh instance behaves identically to a long-lived one.
class ProjectBuildRunner {
    // Walks `startDir` and its ancestors looking for any of `projectFileNames` (in the order
    // given, so e.g. PureScript's ["spago.yaml", "spago.dhall"] prefers the new YAML config
    // over the legacy Dhall one when a project happens to have both) — returns the directory
    // containing the first match (the project root) paired with the matched file itself, or
    // null if none of them is found anywhere above `startDir`. Mirrors
    // CompileToJsDispatcher.findProjectNodeModulesDir's own ancestor-walk shape.
    fun findProjectRoot(startDir: Path?, projectFileNames: List<String>): Pair<Path, Path>? {
        var dir: Path? = startDir
        while (dir != null) {
            for (name in projectFileNames) {
                val candidate = dir.resolve(name)
                if (Files.isRegularFile(candidate)) return dir to candidate
            }
            dir = dir.parent
        }
        return null
    }

    // Ensures `projectRoot` has been built for `sourcePath` (running `binaryPath` + whatever
    // `buildArgs` returns, unless fresh-enough output already exists — see below), then reads
    // back and returns the compiled JS `resolveOutput` locates for `sourcePath` specifically.
    //
    // Caching/"don't rebuild on every import": rather than an in-memory "already built this
    // project" flag, freshness is judged purely by comparing `resolveOutput`'s result's own
    // mtime against the project file's (elm.json/spago.yaml/rescript.json) mtime — if the
    // output already exists and is at least as new as the project file, the build is skipped
    // entirely. This is deliberately the same "mtime of the project file" heuristic
    // ROADMAP/the task's own design doc calls out as an acceptable simplification (a real
    // incremental-build cache would need to hash or stat every source file the project
    // actually contains, which is exactly what each build tool's own incremental-build
    // machinery already does internally — elm/spago/rescript all cache their own
    // intermediate artifacts, so a from-scratch invocation here is rarely a from-scratch
    // *compile* on their end either). A filesystem check (rather than a JVM-lifetime
    // in-memory map) also means this naturally survives across separate Mycelium process
    // runs and multiple entry points imported from the same project within one run, with no
    // extra bookkeeping.
    fun compile(
        languageId: String,
        projectRoot: Path,
        projectFile: Path,
        sourcePath: Path,
        binaryPath: Path,
        buildArgs: (projectRoot: Path, binaryPath: Path, sourcePath: Path, outputDir: Path) -> List<String>,
        resolveOutput: (projectRoot: Path, outputDir: Path, sourcePath: Path) -> Path,
    ): String {
        // Scratch space for build artifacts this class itself controls the location of
        // (Elm's bundle) — separate per language so e.g. an Elm project and a PureScript
        // project accidentally sharing a directory (unusual, but not impossible) never
        // collide. Languages whose own build tool already picks its own output location
        // (PureScript's `output/`, ReScript's adjacent `.bs.js` files) simply ignore this
        // directory in their `resolveOutput`/`buildArgs` implementations.
        val outputDir = projectRoot.resolve(".mycelium-build").resolve(languageId)
        val projectFileMtime = Files.getLastModifiedTime(projectFile).toMillis()

        val existingOutput = runCatching { resolveOutput(projectRoot, outputDir, sourcePath) }.getOrNull()
        val prebuiltIsFresh = existingOutput != null && Files.exists(existingOutput) &&
            Files.getLastModifiedTime(existingOutput).toMillis() >= projectFileMtime
        if (!prebuiltIsFresh) {
            Files.createDirectories(outputDir)
            runBuild(languageId, projectRoot, binaryPath, sourcePath, buildArgs, outputDir)
        }

        val outputPath = resolveOutput(projectRoot, outputDir, sourcePath)
        if (!Files.exists(outputPath)) {
            throw MyceliumException(
                "mycelium: '$languageId' project build at '$projectRoot' succeeded but produced no output at " +
                    "'$outputPath' for '${sourcePath.fileName}'"
            )
        }
        return outputPath.readText()
    }

    private fun runBuild(
        languageId: String,
        projectRoot: Path,
        binaryPath: Path,
        sourcePath: Path,
        buildArgs: (projectRoot: Path, binaryPath: Path, sourcePath: Path, outputDir: Path) -> List<String>,
        outputDir: Path,
    ) {
        val args = buildArgs(projectRoot, binaryPath, sourcePath, outputDir)
        val fullCommand = listOf(binaryPath.toString()) + args

        // stderr redirected to a sibling file rather than piped — see SubprocessBackend's
        // own comment on the same choice (avoids the two-live-pipes deadlock hazard of
        // reading stdout and stderr sequentially on one thread).
        val stderrFile = Files.createTempFile(outputDir, "stderr", ".txt")
        val process = try {
            ProcessBuilder(fullCommand)
                .directory(projectRoot.toFile())
                .redirectError(stderrFile.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (e: IOException) {
            throw MyceliumException(
                "mycelium: building '$languageId' project at '$projectRoot' requires '$binaryPath' — " +
                    "install it and try again",
                e
            )
        }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val diagnostic = stderrFile.readText().trim()
            throw MyceliumException(
                "mycelium: '$binaryPath' failed to build '$languageId' project at '$projectRoot' " +
                    "(exit $exitCode):\n$diagnostic"
            )
        }
    }
}

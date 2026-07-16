package org.mycelium.library.compiler

import org.mycelium.library.MyceliumException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

// How a SubprocessBackend should recover the compiled JS once the subprocess exits
// successfully.
enum class SubprocessOutputMode {
    // The compiler writes compiled JS straight to its own stdout (e.g. ReScript's `bsc
    // <file>`) — captured directly, no output file involved.
    STDOUT,

    // The compiler writes compiled JS to a file on disk, whose path is either dictated by
    // a CLI flag this backend controls (Elm's `--output=<path>`) or has to be located
    // after the fact by the caller's `locateOutput` callback because the compiler picks
    // the path itself (PureScript's `output/<Module>/index.js`).
    OUTPUT_FILE,
}

// A CompileToJsBackend for compile-to-JS languages whose only interface is a native binary
// (Elm's `elm`, PureScript's `purs`, ReScript's `bsc`) rather than a JS-callable function —
// as opposed to NpmJsApiBackend (require()s a JS API into the shared GraalJS Context) or
// EsbuildBackend (the one bundled/embedded backend). Mirrors org.mycelium.library.Compiler
// (the C/Rust/TinyGo LLVM/WASM subprocess path) in spirit — ProcessBuilder, no PATH
// searching, IOException -> MyceliumException — but targets "produces JS text" rather than
// "produces an LLVM/WASM binary Value", and is per-language configurable (via the
// constructor params below, themselves driven by CompilerSpec) rather than hardcoded to a
// fixed `cmd + [output, input]` argument shape, since Elm/PureScript/ReScript's actual CLIs
// disagree wildly on argument order, flag spelling, and where output ends up.
//
// One instance is created per CompileToJsDispatcher.compile call (see NpmJsApiBackend's own
// doc comment for why) — `binaryPath` is resolved per-dispatch depending on where the
// language's npm package was found (project node_modules vs. Mycelium's own compiler cache).
class SubprocessBackend(
    // Absolute path to the native compiler binary itself (already resolved by the caller —
    // e.g. CompileToJsDispatcher, which knows where the npm package landed on disk — from
    // CompilerSpec.subprocessBinaryPath). Not searched on PATH: exactly one candidate binary
    // is ever tried, matching org.mycelium.library.Compiler's own no-PATH-searching approach.
    private val binaryPath: Path,
    // Builds the compiler's CLI arguments (everything after the binary name itself) given
    // the source file, a caller-chosen output file path (only meaningful for
    // SubprocessOutputMode.OUTPUT_FILE; ignored by compilers that write to stdout), and the
    // working directory the subprocess should run in — some compilers (Elm) need
    // language-specific scaffolding written into that directory (an elm.json) before the
    // subprocess starts, which this lambda is free to do as a side effect before returning
    // its argument list.
    private val compileArgs: (sourcePath: Path, outputPath: Path, workDir: Path) -> List<String>,
    private val outputMode: SubprocessOutputMode,
    // Only consulted when outputMode == OUTPUT_FILE. When null, the compiled JS is read
    // straight from the `outputPath` handed to `compileArgs` (Elm: `--output=<outputPath>`
    // names the file directly). When non-null, the compiler decided the output path itself
    // (PureScript: `purs compile` always writes to `output/<ModuleName>/index.js` under the
    // working directory, ignoring any path this backend might otherwise have suggested) and
    // this locates the actual file to read once the process has exited successfully.
    private val locateOutput: ((workDir: Path, sourcePath: Path, outputPath: Path) -> Path)? = null,
) : CompileToJsBackend {
    override fun compile(source: String, sourcePath: Path, options: Map<String, Any>): String {
        val workDir = Files.createTempDirectory("mycelium-subprocess-")
        try {
            val outputPath = workDir.resolve("${sourcePath.fileName}.out.js")
            val args = compileArgs(sourcePath, outputPath, workDir)
            val fullCommand = listOf(binaryPath.toString()) + args

            // stderr is redirected to a sibling file rather than piped and read on the main
            // thread alongside stdout — reading two live pipes sequentially on one thread
            // risks deadlock if the subprocess fills the *other* pipe's OS buffer while this
            // thread is still blocked reading the first one to EOF. Since Mycelium's stdout
            // handling (STDOUT mode) needs the whole stream read to completion anyway, this
            // sidesteps that hazard entirely rather than introducing a second reader thread.
            val stderrFile = Files.createTempFile(workDir, "stderr", ".txt")
            val process = try {
                ProcessBuilder(fullCommand)
                    .directory(workDir.toFile())
                    .redirectError(stderrFile.toFile())
                    .start()
            } catch (e: IOException) {
                throw MyceliumException(
                    "mycelium: importing ${sourcePath.fileName} requires '$binaryPath' — install it and try again",
                    e
                )
            }

            val stdoutText = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val stderrText = stderrFile.readText()
                val diagnostic = stderrText.ifBlank { stdoutText }.trim()
                throw MyceliumException(
                    "mycelium: '$binaryPath' failed to compile '${sourcePath.fileName}' (exit $exitCode):\n$diagnostic"
                )
            }

            return when (outputMode) {
                SubprocessOutputMode.STDOUT -> stdoutText
                SubprocessOutputMode.OUTPUT_FILE -> {
                    val actualOutputPath = locateOutput?.invoke(workDir, sourcePath, outputPath) ?: outputPath
                    if (!Files.exists(actualOutputPath)) {
                        throw MyceliumException(
                            "mycelium: '$binaryPath' exited successfully but produced no output at " +
                                "'$actualOutputPath' for '${sourcePath.fileName}'"
                        )
                    }
                    actualOutputPath.readText()
                }
            }
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }
}

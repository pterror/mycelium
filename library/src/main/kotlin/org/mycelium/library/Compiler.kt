package org.mycelium.library

import org.graalvm.polyglot.Context
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.Path

// LLVM/WASM compilation routing: shells out to a LanguageSupport's own compiler command
// (compile) and, for WASM outputs specifically, loads the resulting binary into a usable
// polyglot Value (loadWasmExports). InterceptingFileSystem's compiled-import branch of
// newByteChannel calls both in turn, then hands the resulting Value to ShimGenerator to
// turn into an importable ESM shim.
object Compiler {
    private val cacheDir = File(
        System.getenv("XDG_CACHE_HOME") ?: File(System.getProperty("user.home"), ".cache").path,
        "mycelium"
    ).also { it.mkdirs() }

    fun compile(sourcePath: Path, lang: LanguageSupport): Path {
        val compilerCmd = lang.compilerCommand
            ?: throw MyceliumException("mycelium: no compiler configured for ${lang.languageId} files")
        val sourceFile = sourcePath.toFile()
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(sourceFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        val outputFile = File(cacheDir, "$hash.${lang.outputExtension}")
        if (outputFile.exists()) return outputFile.toPath()
        val cmd = compilerCmd + listOf(outputFile.absolutePath, sourceFile.absolutePath)
        val process =
            try {
                ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: java.io.IOException) {
                val compilerName = compilerCmd.firstOrNull() ?: "the compiler"
                throw MyceliumException(
                    "mycelium: importing ${sourcePath.fileName} requires '$compilerName' — install it and try again",
                    e
                )
            }
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw MyceliumException(
                "mycelium: compiling ${sourcePath.fileName} failed (exit $exitCode):\n${output.trim()}"
            )
        }
        return outputFile.toPath()
    }

    // GraalVM's own "wasm" Truffle language (context.eval("wasm", file)) hands back a
    // Value with zero memberKeys — there is no supported way to reach a WASM module's
    // exports through the raw Truffle wasm language directly, which makes it useless for
    // shim generation (see the CRITICAL BLOCKER note on TinyGoSupport in Languages.kt).
    // Instead, this loads the module through GraalJS's built-in `WebAssembly` global (the
    // same JS API browsers/Node expose): compile a `WebAssembly.Module` from the compiled
    // binary's bytes and instantiate it, then return `instance.exports` — an ordinary JS
    // object whose memberKeys ShimGenerator can already enumerate exactly like it does for
    // LLVM-backed exports.
    //
    // The compiled bytes are handed to JS via a polyglot binding (a plain Java byte array)
    // rather than splicing them into JS source text, so this doesn't choke on/slow to a
    // crawl for larger WASM binaries. tinygo's `-target=wasi` output imports a handful of
    // WASI preview1 functions even for WASI-oblivious code like `add.go` (its runtime init
    // pulls them in); those are stubbed out below with harmless no-ops since none of them
    // are expected to be exercised by cross-language-imported pure functions.
    fun loadWasmExports(context: Context, compiled: Path): org.graalvm.polyglot.Value {
        val wasmBytes = Files.readAllBytes(compiled)
        val bindingsKey = "__mycelium_wasm_bytes__${compiled.fileName}__${System.nanoTime()}__"
        context.polyglotBindings.putMember(bindingsKey, wasmBytes)
        try {
            return context.eval(
                "js",
                """
                (function () {
                    const bytes = Polyglot.import(${jsonStringLiteral(bindingsKey)});
                    const u8 = new Uint8Array(bytes.length);
                    for (let i = 0; i < bytes.length; i++) u8[i] = bytes[i] & 0xFF;
                    const mod = new WebAssembly.Module(u8);
                    const memory = new WebAssembly.Memory({ initial: 256 });
                    const wasi = {
                        fd_write: () => 0,
                        fd_close: () => 0,
                        fd_seek: () => 0,
                        fd_read: () => 0,
                        fd_fdstat_get: () => 0,
                        fd_prestat_get: () => 8,
                        fd_prestat_dir_name: () => 0,
                        path_open: () => 0,
                        proc_exit: () => {},
                        args_sizes_get: () => 0,
                        args_get: () => 0,
                        environ_sizes_get: () => 0,
                        environ_get: () => 0,
                        clock_time_get: () => 0,
                        random_get: () => 0,
                        sched_yield: () => 0,
                    };
                    const inst = new WebAssembly.Instance(mod, {
                        env: { memory: memory },
                        wasi_snapshot_preview1: wasi,
                    });
                    return inst.exports;
                })()
                """.trimIndent()
            )
        } finally {
            context.polyglotBindings.removeMember(bindingsKey)
        }
    }

    // Escapes `s` as a JS double-quoted string literal, for splicing an arbitrary
    // filesystem path into generated JS source text.
    private fun jsonStringLiteral(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

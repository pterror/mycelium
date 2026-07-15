package org.mycelium.library

import java.io.File
import java.security.MessageDigest
import java.nio.file.Path

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
}

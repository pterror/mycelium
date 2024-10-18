package org.mycelium.library

import java.io.File
import java.net.URI
import java.nio.file.AccessMode
import java.nio.file.DirectoryStream.Filter
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.FileSystem

class Mycelium {
    val context = Context.create()

    fun runString(code: String, language: String) {
        context.eval(language, code)
    }

    fun runFile(path: String) {
        val file = File(path)
        val language = languageFromExtension(file.extension)!!
        val code = file.readText()
        runString(language, code)
    }

    fun languageFromExtension(extension: String) =
            when (extension) {
                "js", "mjs", "ts", "mts" -> "js"
                "py" -> "python"
                "rb" -> "ruby"
                "wasm" -> "wasm"
                else -> null
            }
}

class InterceptingFileSystem : FileSystem {
    private final val delegate = FileSystem.newDefaultFileSystem()

    override fun parsePath(uri: URI) = Path.of(uri)

    override fun parsePath(path: String) = Path.of(path)

    override fun checkAccess(path: Path, modes: Set<AccessMode>, vararg linkOptions: LinkOption) =
            delegate.checkAccess(path, modes, *linkOptions)

    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) =
            delegate.createDirectory(dir, *attrs)

    override fun delete(path: Path) {}

    override fun newByteChannel(
            path: Path,
            options: Set<OpenOption>,
            vararg attrs: FileAttribute<*>
    ) = delegate.newByteChannel(path, options, *attrs)

    override fun newDirectoryStream(dir: Path, filter: Filter<in Path>) =
            delegate.newDirectoryStream(dir, filter)

    override fun toAbsolutePath(path: Path) = path.toAbsolutePath()

    override fun toRealPath(path: Path, vararg linkOptions: LinkOption) =
            path.toRealPath(*linkOptions)

    override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption) =
            delegate.readAttributes(path, attributes, *options)
}

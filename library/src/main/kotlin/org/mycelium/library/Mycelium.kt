package org.mycelium.library

import java.io.File
import java.net.URI
import java.net.URL
import java.net.HttpURLConnection
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessMode
import java.nio.file.DirectoryStream.Filter
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.nio.ByteBuffer
import kotlin.text.startsWith
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.FileSystem
import org.graalvm.polyglot.io.IOAccess

class Mycelium {
    // `allowIO` is required for imports to work
    val context =
            Context.newBuilder()
                    // Enable `Polyglot` and `Java` globals
                    .allowAllAccess(true)
                    // Intercept file system calls (used to support HTTP imports)
                    .allowIO(IOAccess.newBuilder().fileSystem(InterceptingFileSystem()).build())
                    .build()

    fun runString(language: String, code: String) {
        context.eval(language, code)
    }

    fun runFile(path: String) {
        val file = File(path)
        val language = languageFromExtension(file.extension)!!
        val code = file.readText()
        val source = Source.newBuilder(language, code, path).build()
        context.eval(source)
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

class HttpChannel(val uri: URI): SeekableByteChannel {
    private final val connection = uri.toURL().openConnection() as HttpURLConnection
    private final val buffer = ByteBuffer.allocate(0x10000)

    init {
        // Some websites send special text-only output to `curl`.
        connection.setRequestProperty("User-Agent", "curl/8.9.1")
        connection.connect()
    }

    override fun close() {
        connection.inputStream.close()
    }

    override fun isOpen(): Boolean = false

    override fun read(p0: ByteBuffer): Int = connection.inputStream.read(p0.array())

    override fun write(p0: ByteBuffer): Int {
        connection.outputStream.write(p0.array())
        return p0.limit()
    }

    override fun position(): Long = buffer.position().toLong()

    override fun position(p0: Long): SeekableByteChannel {
        buffer.position(p0.toInt())
        return this
    }

    override fun size(): Long = buffer.limit().toLong()

    override fun truncate(p0: Long): SeekableByteChannel { 
        buffer.limit(p0.toInt())
        return this
    }
}

class InterceptingFileSystem : FileSystem {
    private final val delegate = FileSystem.newDefaultFileSystem()

    override fun parsePath(uri: URI) =
            if (uri.toString().startsWith('/')) Path.of(uri) else Path.of("/.mycelium/" + uri)

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
    ): SeekableByteChannel {
        if (path.startsWith("/.mycelium/")) {
            val uri = URI(path.toString().substring("/.mycelium/".length).replace(":/", "://"))
            return when (uri.scheme) {
                "http", "https" -> HttpChannel(uri)
                else -> {
                    throw Error("Unknown scheme ${uri.scheme}")
                }
            }
        } else {
            return delegate.newByteChannel(path, options, *attrs)
        }
    }

    override fun newDirectoryStream(dir: Path, filter: Filter<in Path>) =
            delegate.newDirectoryStream(dir, filter)

    override fun toAbsolutePath(path: Path) = path.toAbsolutePath()

    override fun toRealPath(path: Path, vararg linkOptions: LinkOption) =
            path.toRealPath(*linkOptions)

    override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption) =
            delegate.readAttributes(path, attributes, *options)
}

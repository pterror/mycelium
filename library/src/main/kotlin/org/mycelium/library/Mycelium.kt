package org.mycelium.library

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessMode
import java.nio.file.DirectoryStream.Filter
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import kotlin.math.max
import kotlin.math.min
import kotlin.text.startsWith
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.FileSystem
import org.graalvm.polyglot.io.IOAccess

class Mycelium {
    val filesystem = InterceptingFileSystem()
    // `allowIO` is required for imports to work
    val context =
            Context.newBuilder()
                    // Enable `Polyglot` and `Java` globals
                    .allowAllAccess(true)
                    // Intercept file system calls (used to support HTTP imports)
                    .allowIO(IOAccess.newBuilder().fileSystem(filesystem).build())
                    .build()

    init {
        filesystem.init(context)
    }

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

class HttpChannel(val uri: URI) : SeekableByteChannel {
    private final val connection = uri.toURL().openConnection() as HttpURLConnection
    private var buffer = ByteBuffer.allocate(8192)
    private var length = 0

    init {
        // Some websites send special text-only output to `curl`.
        connection.setRequestProperty("User-Agent", "curl/8.9.1")
        connection.connect()
    }

    override fun close() {
        connection.inputStream.close()
    }

    override fun isOpen(): Boolean = false

    private fun ensure(capacity: Int) {
        while (capacity > buffer.capacity()) {
            val oldBuffer = buffer
            buffer = ByteBuffer.allocate(buffer.capacity() * 2)
            buffer.put(oldBuffer.array(), 0, length)
            buffer.position(oldBuffer.position())
        }
    }

    private fun fetchMoreData() {
        if (connection.inputStream.available() == 0) return
        val end = length + connection.inputStream.available()
        if (end > length) {
            ensure(end)
            val position = buffer.position()
            buffer.position(length)
            val bytesRead =
                    connection.inputStream.read(
                            buffer.array(),
                            buffer.arrayOffset(),
                            buffer.capacity() - buffer.arrayOffset()
                    )
            length += max(0, bytesRead)
            buffer.position(position)
        }
    }

    override fun read(p0: ByteBuffer): Int {
        val end = buffer.position() + p0.limit()
        if (end > length) fetchMoreData()
        val bytesRead = min(p0.limit() - p0.arrayOffset(), length - buffer.position())
        println("bytesRead=$bytesRead pos=${buffer.position()} end=$end length=$length ${buffer.position()}")
        buffer.get(p0.array(), p0.arrayOffset(), bytesRead)
        println(p0.array().toString(Charsets.UTF_8))
        return if (bytesRead == 0) -1 else bytesRead
    }

    override fun write(p0: ByteBuffer): Int {
        val end = buffer.position() + p0.limit()
        ensure(end)
        val position = buffer.position()
        buffer.position(length)
        val bytesRead = p0.limit() - p0.arrayOffset()
        buffer.put(p0.array(), p0.arrayOffset(), bytesRead)
        length += bytesRead
        buffer.position(position)
        return p0.array().size
    }

    override fun position(): Long = buffer.position().toLong()

    override fun position(p0: Long): SeekableByteChannel {
        buffer.position(p0.toInt())
        return this
    }

    override fun size(): Long {
        // TODO: Module execution fails when split into two reads.
        // Remove `fetchMoreData()` below to see the error.
        if (buffer.position() == length) fetchMoreData()
        return length.toLong()
    }

    override fun truncate(p0: Long): SeekableByteChannel {
        println("a")
        length = p0.toInt()
        buffer.limit(length)
        buffer.compact()
        return this
    }
}

class InterceptingFileSystem : FileSystem {
    private final var context: Context? = null
    private final val delegate = FileSystem.newDefaultFileSystem()

    fun init(context: Context) {
        this.context = context
    }

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

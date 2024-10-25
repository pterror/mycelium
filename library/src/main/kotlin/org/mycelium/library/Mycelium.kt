package org.mycelium.library

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.FrameInstance
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryStream.Filter
import java.nio.file.attribute.FileAttribute
import kotlin.math.max
import kotlin.math.min
import kotlin.text.startsWith
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.FileSystem
import org.graalvm.polyglot.io.IOAccess
import java.nio.file.*
import kotlin.io.path.extension
import kotlin.io.path.readText

class Mycelium {
    private val filesystem = InterceptingFileSystem()

    // `allowIO` is required for imports to work
    private val context: Context =
        Context.newBuilder()
            // Enable `Polyglot` and `Java` globals
            .allowAllAccess(true)
            // Intercept file system calls (used to support HTTP imports)
            .allowIO(IOAccess.newBuilder().fileSystem(filesystem).build())
            .build()

    init {
        filesystem.init(context)
    }

    fun runFile(path: String) {
        val file = File(path)
        val language = languageFromExtension(file.extension)!!
        val code = file.readText()
        val source = Source.newBuilder(language, code, path).build()
        context.eval(source)
    }

    companion object {
        fun languageFromExtension(extension: String) =
            when (extension) {
                "js", "mjs", "ts", "mts" -> "js"
                "py" -> "python"
                "rb" -> "ruby"
                "wasm" -> "wasm"
                else -> null
            }
    }
}

class HttpChannel(uri: URI) : SeekableByteChannel {
    private val connection = uri.toURL().openConnection() as HttpURLConnection
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
        buffer.get(p0.array(), p0.arrayOffset(), bytesRead)
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
        length = p0.toInt()
        buffer.limit(length)
        buffer.compact()
        return this
    }
}

class InterceptingFileSystem : FileSystem {
    private var context: Context? = null
    private val delegate = FileSystem.newDefaultFileSystem()

    fun init(context: Context) {
        this.context = context
    }

    override fun parsePath(uri: URI): Path =
        if (uri.toString().startsWith('/')) Path.of(uri) else Path.of("/.mycelium/$uri")

    override fun parsePath(path: String): Path = Path.of(path)

    override fun checkAccess(path: Path, modes: Set<AccessMode>, vararg linkOptions: LinkOption) =
        delegate.checkAccess(path, modes, *linkOptions)

    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) =
        delegate.createDirectory(dir, *attrs)

    override fun delete(path: Path) {
        delegate.delete(path)
    }

    override fun newByteChannel(
        path: Path,
        options: Set<OpenOption>,
        vararg attrs: FileAttribute<*>
    ): SeekableByteChannel {
        val callTarget = Truffle.getRuntime().iterateFrames(fun(frame) = "${frame.callTarget}")
        val hostLanguage = if (callTarget.startsWith("GraalJSEvaluator.ModuleScriptRoot@")) {
            "js"
        } else {
            println("Host language module class is $callTarget")
            "TODO"
        }
        val targetLanguage = Mycelium.languageFromExtension(path.extension)
        if (hostLanguage == targetLanguage || targetLanguage == null) {
            return if (path.startsWith("/.mycelium/")) {
                val uri = URI(path.toString().substring("/.mycelium/".length).replace(":/", "://"))
                when (uri.scheme) {
                    "http", "https" -> HttpChannel(uri)
                    else -> throw Error("Unknown scheme ${uri.scheme}")
                }
            } else {
                delegate.newByteChannel(path, options, *attrs)
            }
        } else {
            // TODO
            val code = if (path.startsWith("/.mycelium/")) {
                val uri = URI(path.toString().substring("/.mycelium/".length).replace(":/", "://"))
                when (uri.scheme) {
                    "http", "https" -> uri.toURL().readText()
                    else -> throw Error("Unknown scheme ${uri.scheme}")
                }
            } else {
                path.readText()
            }
            val value = context!!.eval(targetLanguage, code)
            when (hostLanguage) {
                "js" -> {
                    val sb = StringBuilder()
                    for (k in value.memberKeys) {
                        // TODO: How to bind and inject a reference to this binding?
                        val v = value.getMember(k)
                        sb.append("export const $k = Polyglot.getBinding(\"v\")")
                    }
                    BufferChannel("")
                }
                else -> throw Error("We do not know how to serialize to host language $hostLanguage")
            }
        }
    }

    override fun newDirectoryStream(dir: Path, filter: Filter<in Path>): DirectoryStream<Path> =
        delegate.newDirectoryStream(dir, filter)

    override fun toAbsolutePath(path: Path): Path = path.toAbsolutePath()

    override fun toRealPath(path: Path, vararg linkOptions: LinkOption): Path =
        path.toRealPath(*linkOptions)

    override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): MutableMap<String, Any> =
        delegate.readAttributes(path, attributes, *options)
}

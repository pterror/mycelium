package org.mycelium.library

import com.oracle.truffle.api.Truffle
import java.io.File
import java.security.MessageDigest
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
import java.nio.charset.StandardCharsets
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
                "c", "h" -> "llvm"
                "rs" -> "llvm"
                "go" -> "llvm"
                else -> null
            }

        fun compilerForExtension(extension: String): List<String>? =
            when (extension) {
                "c" -> listOf("clang", "-emit-llvm", "-c", "-o")
                "rs" -> listOf("rustc", "--emit=llvm-bc", "-o")
                "go" -> null // TODO: Go -> LLVM bitcode pipeline
                else -> null
            }

        fun needsCompilation(extension: String) =
            extension in listOf("c", "rs", "go")

        private val jsIdentifierRegex = Regex("^[a-zA-Z_$][a-zA-Z0-9_$]*$")
        private val jsReservedWords = setOf(
            "break", "case", "catch", "continue", "debugger", "default", "delete",
            "do", "else", "finally", "for", "function", "if", "in", "instanceof",
            "new", "return", "switch", "this", "throw", "try", "typeof", "var",
            "void", "while", "with", "class", "const", "enum", "export", "extends",
            "import", "super", "implements", "interface", "let", "package", "private",
            "protected", "public", "static", "yield"
        )

        fun isValidJsExport(name: String) =
            jsIdentifierRegex.matches(name) && name !in jsReservedWords
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

class ReadOnlyBufferChannel(val buffer: ByteBuffer) : SeekableByteChannel {
    override fun close() {}

    override fun isOpen(): Boolean = false

    override fun read(p0: ByteBuffer): Int {
        val bytesRead = min(p0.limit() - p0.arrayOffset(), buffer.limit() - buffer.position())
        buffer.get(p0.array(), p0.arrayOffset(), bytesRead)
        return if (bytesRead == 0) -1 else bytesRead
    }

    override fun write(p0: ByteBuffer): Int {
        val position = buffer.position()
        buffer.position(buffer.limit())
        val bytesRead = p0.limit() - p0.arrayOffset()
        buffer.put(p0.array(), p0.arrayOffset(), bytesRead)
        buffer.position(position)
        return p0.array().size
    }

    override fun position(): Long = buffer.position().toLong()

    override fun position(p0: Long): SeekableByteChannel {
        buffer.position(p0.toInt())
        return this
    }

    override fun size(): Long {
        return buffer.limit().toLong()
    }

    override fun truncate(p0: Long): SeekableByteChannel {
        buffer.limit(p0.toInt())
        buffer.compact()
        return this
    }
}

object Compiler {
    private val cacheDir = File(
        System.getenv("XDG_CACHE_HOME") ?: File(System.getProperty("user.home"), ".cache").path,
        "mycelium"
    ).also { it.mkdirs() }

    fun compile(sourcePath: Path): Path {
        val extension = sourcePath.extension
        val compilerCmd = Mycelium.compilerForExtension(extension)
            ?: throw Error("No compiler configured for .$extension files")
        val sourceFile = sourcePath.toFile()
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(sourceFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        val outputFile = File(cacheDir, "$hash.bc")
        if (outputFile.exists()) return outputFile.toPath()
        val cmd = compilerCmd + listOf(outputFile.absolutePath, sourceFile.absolutePath)
        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw Error("Compilation of ${sourcePath.fileName} failed (exit $exitCode):\n$output")
        }
        return outputFile.toPath()
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
            null
        }
        if (hostLanguage == null) {
            return delegate.newByteChannel(path, options, *attrs)
        }
        val targetLanguage = Mycelium.languageFromExtension(path.extension)
        if (Mycelium.needsCompilation(path.extension) && !path.startsWith("/.mycelium/")) {
            val compiled = Compiler.compile(path)
            val source = Source.newBuilder("llvm", compiled.toFile()).build()
            val value = context!!.eval(source)
            return when (hostLanguage) {
                "js" -> {
                    val sb = StringBuilder()
                    val bindings = context!!.polyglotBindings
                    for (k in value.memberKeys) {
                        if (k.contains('.') || k.startsWith("llvm.") || k.startsWith("__")) continue
                        bindings.putMember(k, value.getMember(k))
                        sb.append("export const $k = Polyglot.import(\"$k\");\n")
                    }
                    ReadOnlyBufferChannel(StandardCharsets.UTF_8.encode(sb.toString()))
                }
                else -> throw Error("We do not know how to serialize to host language $hostLanguage")
            }
        }
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
            return when (hostLanguage) {
                "js" -> {
                    val sb = StringBuilder()
                    val bindings = context!!.polyglotBindings
                    for (k in value.memberKeys) {
                        if (!Mycelium.isValidJsExport(k)) continue
                        bindings.putMember(k, value.getMember(k))
                        sb.append("export const $k = Polyglot.import(\"$k\");\n")
                    }
                    ReadOnlyBufferChannel(StandardCharsets.UTF_8.encode(sb.toString()))
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

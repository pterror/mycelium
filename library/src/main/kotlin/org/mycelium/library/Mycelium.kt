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

interface LanguageSupport {
    val languageId: String
    val extensions: List<String>
    val compilerCommand: List<String>?
    val needsCompilation: Boolean get() = compilerCommand != null

    fun detectFrame(frame: String): Boolean
    fun isValidExportName(name: String): Boolean
    fun generateShim(exports: Map<String, String>): String
}

object JsSupport : LanguageSupport {
    override val languageId = "js"
    override val extensions = listOf("js", "mjs", "ts", "mts")
    override val compilerCommand = null

    override fun detectFrame(frame: String) =
        frame.startsWith("GraalJSEvaluator.ModuleScriptRoot@")

    private val identifierRegex = Regex("^[a-zA-Z_$][a-zA-Z0-9_$]*$")
    private val reservedWords = setOf(
        "break", "case", "catch", "continue", "debugger", "default", "delete",
        "do", "else", "finally", "for", "function", "if", "in", "instanceof",
        "new", "return", "switch", "this", "throw", "try", "typeof", "var",
        "void", "while", "with", "class", "const", "enum", "export", "extends",
        "import", "super", "implements", "interface", "let", "package", "private",
        "protected", "public", "static", "yield"
    )

    override fun isValidExportName(name: String) =
        identifierRegex.matches(name) && name !in reservedWords

    override fun generateShim(exports: Map<String, String>): String =
        exports.entries.joinToString("\n") { (name, key) ->
            "export const $name = Polyglot.import(\"$key\");"
        }
}

object RubySupport : LanguageSupport {
    override val languageId = "ruby"
    override val extensions = listOf("rb")
    override val compilerCommand = null

    override fun detectFrame(frame: String) =
        frame.startsWith("Kernel#require") ||
        frame.startsWith("Kernel#gem_original_require") ||
        frame.startsWith("Truffle::Boot.")

    private val identifierRegex = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")

    override fun isValidExportName(name: String) =
        identifierRegex.matches(name)

    override fun generateShim(exports: Map<String, String>): String {
        val sb = StringBuilder()
        for ((name, key) in exports) {
            sb.append("$name = Polyglot.import(\"$key\")\n")
        }
        return sb.toString()
    }
}

object PythonSupport : LanguageSupport {
    override val languageId = "python"
    override val extensions = listOf("py")
    override val compilerCommand = null

    override fun detectFrame(frame: String) =
        frame.contains("import") && frame.contains("python")

    private val identifierRegex = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")
    private val reservedWords = setOf(
        "False", "None", "True", "and", "as", "assert", "async", "await",
        "break", "class", "continue", "def", "del", "elif", "else", "except",
        "finally", "for", "from", "global", "if", "import", "in", "is",
        "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try",
        "while", "with", "yield"
    )

    override fun isValidExportName(name: String) =
        identifierRegex.matches(name) && name !in reservedWords

    override fun generateShim(exports: Map<String, String>): String =
        exports.entries.joinToString("\n") { (name, key) ->
            "$name = polyglot.import_value(\"$key\")"
        }
}

object CSupport : LanguageSupport {
    override val languageId = "llvm"
    override val extensions = listOf("c")
    override val compilerCommand = listOf("clang", "-emit-llvm", "-c", "-o")

    override fun detectFrame(frame: String) = false
    override fun isValidExportName(name: String) =
        !name.contains('.') && !name.startsWith("__")
    override fun generateShim(exports: Map<String, String>) = ""
}

object RustSupport : LanguageSupport {
    override val languageId = "llvm"
    override val extensions = listOf("rs")
    override val compilerCommand = listOf("rustc", "--emit=llvm-bc", "-o")

    override fun detectFrame(frame: String) = false
    override fun isValidExportName(name: String) =
        !name.contains('.') && !name.startsWith("__")
    override fun generateShim(exports: Map<String, String>) = ""
}

class Mycelium(
    val languages: List<LanguageSupport> = listOf(
        JsSupport, RubySupport, PythonSupport, CSupport, RustSupport
    )
) {
    private val filesystem = InterceptingFileSystem()

    private val context: Context =
        Context.newBuilder()
            .allowAllAccess(true)
            .allowIO(IOAccess.newBuilder().fileSystem(filesystem).build())
            .build()

    init {
        filesystem.init(context, this)
    }

    private val extensionMap: Map<String, LanguageSupport> =
        languages.flatMap { lang -> lang.extensions.map { it to lang } }.toMap()

    fun supportForExtension(extension: String): LanguageSupport? = extensionMap[extension]

    fun languageFromExtension(extension: String): String? = extensionMap[extension]?.languageId

    fun detectHostLanguage(frames: List<String>): LanguageSupport? {
        for (frame in frames) {
            for (lang in languages) {
                if (lang.detectFrame(frame)) return lang
            }
        }
        return null
    }

    fun runFile(path: String) {
        val file = File(path)
        val language = languageFromExtension(file.extension)!!
        val code = file.readText()
        val source = Source.newBuilder(language, code, path).build()
        context.eval(source)
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

    fun compile(sourcePath: Path, lang: LanguageSupport): Path {
        val compilerCmd = lang.compilerCommand
            ?: throw Error("No compiler configured for ${lang.languageId} files")
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
    private var mycelium: Mycelium? = null
    private val delegate = FileSystem.newDefaultFileSystem()

    fun init(context: Context, mycelium: Mycelium) {
        this.context = context
        this.mycelium = mycelium
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
        val mc = mycelium!!
        val frames = mutableListOf<String>()
        Truffle.getRuntime().iterateFrames { frame ->
            frames.add("${frame.callTarget}")
            null
        }
        val hostLang = mc.detectHostLanguage(frames)
            ?: return delegate.newByteChannel(path, options, *attrs)
        val targetLang = mc.supportForExtension(path.extension)
        if (targetLang != null && targetLang.needsCompilation && !path.startsWith("/.mycelium/")) {
            val compiled = Compiler.compile(path, targetLang)
            val source = Source.newBuilder("llvm", compiled.toFile()).build()
            val value = context!!.eval(source)
            return generateShim(hostLang, value)
        }
        if (hostLang.languageId == targetLang?.languageId || targetLang == null) {
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
            val value = context!!.eval(targetLang.languageId, code)
            return generateShim(hostLang, value)
        }
    }

    private fun generateShim(hostLang: LanguageSupport, value: org.graalvm.polyglot.Value): SeekableByteChannel {
        val bindings = context!!.polyglotBindings
        val exports = mutableMapOf<String, String>()
        for (k in value.memberKeys) {
            if (!hostLang.isValidExportName(k)) continue
            bindings.putMember(k, value.getMember(k))
            exports[k] = k
        }
        val shim = hostLang.generateShim(exports)
        return ReadOnlyBufferChannel(StandardCharsets.UTF_8.encode(shim))
    }

    override fun newDirectoryStream(dir: Path, filter: Filter<in Path>): DirectoryStream<Path> =
        delegate.newDirectoryStream(dir, filter)

    override fun toAbsolutePath(path: Path): Path = path.toAbsolutePath()

    override fun toRealPath(path: Path, vararg linkOptions: LinkOption): Path =
        path.toRealPath(*linkOptions)

    override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): MutableMap<String, Any> =
        delegate.readAttributes(path, attributes, *options)
}

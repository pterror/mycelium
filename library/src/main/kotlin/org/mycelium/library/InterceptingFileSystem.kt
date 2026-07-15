package org.mycelium.library

import com.oracle.truffle.api.Truffle
import java.net.URI
import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryStream.Filter
import java.nio.file.attribute.FileAttribute
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.FileSystem
import java.nio.charset.StandardCharsets
import java.nio.file.*
import kotlin.io.path.extension
import kotlin.io.path.readText

class InterceptingFileSystem : FileSystem {
    private var context: Context? = null
    private var mycelium: Mycelium? = null
    private val delegate = FileSystem.newDefaultFileSystem()
    private val sourceDirectoryStack = ThreadLocal.withInitial { ArrayDeque<Path>() }

    fun init(context: Context, mycelium: Mycelium) {
        this.context = context
        this.mycelium = mycelium
    }

    // Seeds the source-directory stack with `dir` for the duration of `block`, so that
    // relative imports occurring while evaluating an entry point (which is not itself
    // read through this FileSystem) still resolve against that entry point's directory
    // rather than the process's working directory.
    fun <T> withSourceDirectory(dir: Path, block: () -> T): T {
        sourceDirectoryStack.get().addFirst(dir)
        try {
            return block()
        } finally {
            sourceDirectoryStack.get().removeFirst()
        }
    }

    override fun parsePath(uri: URI): Path =
        if (uri.toString().startsWith('/')) Path.of(uri) else Path.of("/.mycelium/$uri")

    override fun parsePath(path: String): Path {
        // Guest languages' own runtimes (e.g. LLVM loading its internal bitcode libraries)
        // also route path-like identifiers through this method, and those must not be
        // reinterpreted as npm package names. There's no reliable way to tell "this bare
        // identifier is a genuine ES import specifier" from "this is some other guest
        // runtime's internal path" by call-stack inspection alone (both can occur nested
        // inside the same outer JS frame). Instead we key off whether a `node_modules`
        // tree exists at all above `fromDir`: if none is found anywhere in the walk, this
        // isn't an npm-style project and we fall back to the pre-existing behavior of
        // treating the string as a plain (probably-invalid) path, so unrelated runtimes
        // asking about their own internal identifiers are unaffected. Only once we know
        // we're in an npm-style project (some ancestor has a `node_modules` dir) do we
        // treat a missing package as an error worth surfacing.
        if (isBareSpecifier(path)) {
            val fromDir = sourceDirectoryStack.get().firstOrNull() ?: Path.of("").toAbsolutePath()
            when (val resolution = resolveNodeModule(path, fromDir)) {
                is NodeModuleResolution.Found -> return resolution.path
                is NodeModuleResolution.PackageNotFound -> throw MyceliumException(
                    "mycelium: cannot find module '$path' — no node_modules/$path found from " +
                        "'$fromDir' or any parent directory"
                )
                NodeModuleResolution.NoNodeModulesTree -> {}
            }
        }
        return Path.of(path)
    }

    // A "bare specifier" is a package import like `express` or `@scope/pkg`, as opposed to
    // a relative/absolute file path or a URL. Per Node.js convention, anything that doesn't
    // start with `.`, `/`, or a recognized URL scheme is treated as a package name to be
    // resolved against `node_modules`.
    // Guest languages sometimes hand the FileSystem synthetic, non-file identifiers for
    // their own internal bookkeeping rather than a genuine import path — e.g. GraalPython's
    // module bootstrap resolves `<frozen importlib._bootstrap>` (and similar `<...>` names)
    // through this same FileSystem while starting up the interpreter, long before any user
    // code runs. These aren't real paths and must never be treated as npm bare specifiers or
    // reported as "don't know how to import" — they should fall straight through to the
    // delegate filesystem, which throws a plain NoSuchFileException that the guest language
    // itself already knows how to handle (e.g. by falling back to its embedded frozen code).
    private fun isSyntheticIdentifier(path: String): Boolean =
        path.startsWith("<") && path.endsWith(">")

    private fun isBareSpecifier(path: String): Boolean =
        path.isNotEmpty() &&
            !isSyntheticIdentifier(path) &&
            !path.startsWith(".") &&
            !path.startsWith("/") &&
            !path.startsWith("http:") &&
            !path.startsWith("https:")

    private sealed class NodeModuleResolution {
        data class Found(val path: Path) : NodeModuleResolution()
        object PackageNotFound : NodeModuleResolution()
        object NoNodeModulesTree : NodeModuleResolution()
    }

    // Implements (a subset of) Node.js's node_modules resolution algorithm: starting from
    // `fromDir`, check `node_modules/<packageName>` in that directory and each of its
    // ancestors in turn (not just the nearest `node_modules` directory — a package may
    // live in a higher-level `node_modules` even if a closer one exists without it),
    // stopping at the first match.
    private fun resolveNodeModule(specifier: String, fromDir: Path): NodeModuleResolution {
        val (packageName, subPath) = splitSpecifier(specifier)
        var dir: Path? = fromDir.toAbsolutePath().normalize()
        var sawNodeModulesTree = false
        while (dir != null) {
            val nodeModules = dir.resolve("node_modules")
            if (Files.isDirectory(nodeModules)) {
                sawNodeModulesTree = true
                val packageDir = nodeModules.resolve(packageName)
                if (Files.isDirectory(packageDir)) {
                    val resolved = if (subPath != null) {
                        val candidate = packageDir.resolve(subPath)
                        if (!Files.exists(candidate)) return NodeModuleResolution.PackageNotFound
                        candidate
                    } else {
                        resolvePackageEntryPoint(packageDir)
                    }
                    return NodeModuleResolution.Found(resolved)
                }
            }
            dir = dir.parent
        }
        return if (sawNodeModulesTree) NodeModuleResolution.PackageNotFound else NodeModuleResolution.NoNodeModulesTree
    }

    // Splits a bare specifier into its package name and an optional subpath, honoring
    // scoped packages (`@scope/name[/subpath]`) as well as plain ones (`name[/subpath]`).
    private fun splitSpecifier(specifier: String): Pair<String, String?> {
        val segments = specifier.split("/")
        return if (specifier.startsWith("@") && segments.size >= 2) {
            val packageName = "${segments[0]}/${segments[1]}"
            val rest = segments.drop(2).joinToString("/")
            packageName to rest.ifEmpty { null }
        } else {
            val packageName = segments[0]
            val rest = segments.drop(1).joinToString("/")
            packageName to rest.ifEmpty { null }
        }
    }

    // Reads `package.json` and resolves it to a concrete entry-point file, preferring the
    // ESM-oriented `exports` field (`exports["."].import` or `.default`) and falling back to
    // `main`, then finally to the conventional `index.js`.
    private fun resolvePackageEntryPoint(packageDir: Path): Path {
        val packageJson = packageDir.resolve("package.json")
        val entry = if (Files.exists(packageJson)) {
            val json = packageJson.readText()
            try {
                context!!.eval("js", ENTRY_POINT_RESOLVER).execute(json).asString()
            } catch (e: Exception) {
                throw MyceliumException(
                    "mycelium: failed to parse '$packageJson' — ${e.message}", e
                )
            }
        } else {
            "index.js"
        }
        return packageDir.resolve(entry).normalize()
    }

    companion object {
        private val ENTRY_POINT_RESOLVER = """
            (function (json) {
                var pkg = JSON.parse(json);
                var exp = pkg.exports;
                if (exp) {
                    var dot = typeof exp === "string" ? exp : (exp["."] || exp);
                    if (typeof dot === "string") return dot;
                    if (dot && typeof dot === "object") {
                        if (dot.import) return dot.import;
                        if (dot.default) return dot.default;
                    }
                }
                return pkg.main || "index.js";
            })
        """.trimIndent()
    }

    override fun checkAccess(path: Path, modes: Set<AccessMode>, vararg linkOptions: LinkOption) =
        delegate.checkAccess(toAbsolutePath(path), modes, *linkOptions)

    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) =
        delegate.createDirectory(dir, *attrs)

    override fun delete(path: Path) {
        delegate.delete(path)
    }

    override fun newByteChannel(
        rawPath: Path,
        options: Set<OpenOption>,
        vararg attrs: FileAttribute<*>
    ): SeekableByteChannel {
        // Resolve relative paths against the currently-loading file's directory (if known)
        // rather than the process's working directory, before doing anything else.
        val path = if (rawPath.isAbsolute) rawPath else toAbsolutePath(rawPath)
        val pushed = path.isAbsolute && !path.startsWith("/.mycelium/")
        if (pushed) {
            val parent = path.parent ?: path
            sourceDirectoryStack.get().addFirst(parent)
        }
        try {
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
                return generateShim(hostLang, value, path.toString())
            }
            if (hostLang.languageId == targetLang?.languageId || targetLang == null) {
                return if (path.startsWith("/.mycelium/")) {
                    val uri = URI(path.toString().substring("/.mycelium/".length).replace(":/", "://"))
                    when (uri.scheme) {
                        "http", "https" -> try {
                            HttpChannel(uri)
                        } catch (e: java.io.IOException) {
                            throw MyceliumException("mycelium: failed to fetch '$uri' — ${e.message}", e)
                        }
                        else -> throw MyceliumException("mycelium: unknown URL scheme '${uri.scheme}' in '$uri'")
                    }
                } else {
                    try {
                        delegate.newByteChannel(path, options, *attrs)
                    } catch (e: NoSuchFileException) {
                        if (targetLang == null && path.extension.isNotEmpty() &&
                            !isSyntheticIdentifier(path.fileName.toString())) {
                            throw MyceliumException(
                                "mycelium: don't know how to import '${path.fileName}' — supported extensions: " +
                                    mc.supportedExtensions.joinToString(", "),
                                e
                            )
                        }
                        throw e
                    }
                }
            } else {
                val code = if (path.startsWith("/.mycelium/")) {
                    val uri = URI(path.toString().substring("/.mycelium/".length).replace(":/", "://"))
                    when (uri.scheme) {
                        "http", "https" -> try {
                            uri.toURL().readText()
                        } catch (e: java.io.IOException) {
                            throw MyceliumException("mycelium: failed to fetch '$uri' — ${e.message}", e)
                        }
                        else -> throw MyceliumException("mycelium: unknown URL scheme '${uri.scheme}' in '$uri'")
                    }
                } else {
                    path.readText()
                }
                val value = context!!.eval(targetLang.languageId, code)
                return generateShim(hostLang, value, path.toString())
            }
        } finally {
            if (pushed) {
                sourceDirectoryStack.get().removeFirst()
            }
        }
    }

    private fun generateShim(
        hostLang: LanguageSupport,
        value: org.graalvm.polyglot.Value,
        modulePath: String
    ): SeekableByteChannel {
        val bindings = context!!.polyglotBindings
        val exports = mutableMapOf<String, String>()
        for (k in value.memberKeys) {
            if (!hostLang.isValidExportName(k)) continue
            val namespacedKey = "$modulePath:$k"
            bindings.putMember(namespacedKey, value.getMember(k))
            exports[k] = namespacedKey
        }
        val shim = hostLang.generateShim(exports)
        return ReadOnlyBufferChannel(StandardCharsets.UTF_8.encode(shim))
    }

    override fun newDirectoryStream(dir: Path, filter: Filter<in Path>): DirectoryStream<Path> =
        delegate.newDirectoryStream(dir, filter)

    override fun toAbsolutePath(path: Path): Path {
        if (!path.isAbsolute) {
            val stack = sourceDirectoryStack.get()
            if (stack.isNotEmpty()) {
                return stack.first().resolve(path).normalize()
            }
        }
        return path.toAbsolutePath()
    }

    override fun toRealPath(path: Path, vararg linkOptions: LinkOption): Path =
        path.toRealPath(*linkOptions)

    override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): MutableMap<String, Any> =
        delegate.readAttributes(path, attributes, *options)
}

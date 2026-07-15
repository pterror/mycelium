package org.mycelium.library

import com.oracle.truffle.api.Truffle
import java.io.File
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
    private var yolo: Boolean = false
    private val delegate = FileSystem.newDefaultFileSystem()
    private val sourceDirectoryStack = ThreadLocal.withInitial { ArrayDeque<Path>() }

    // `require()`d CommonJS modules, keyed by resolved absolute path (as a string). Each
    // cached Value is the module's Node-style module object — `{ exports, id, filename,
    // loaded }` — not just its exports, and is inserted into the cache *before* the
    // module body runs (see requireCjsModule). That's what makes a circular require (A
    // requires B while B is still in the middle of requiring A) return the same,
    // possibly-still-partial `module.exports` object reference instead of recursively
    // re-evaluating A and stack-overflowing — and what makes `require(x) === require(x)`
    // true and top-level module side effects run exactly once.
    private val cjsModuleCache = mutableMapOf<String, org.graalvm.polyglot.Value>()

    fun init(context: Context, mycelium: Mycelium, yolo: Boolean = false) {
        this.context = context
        this.mycelium = mycelium
        this.yolo = yolo
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

    // `uri.toString()` for a real filesystem URI is always of the form "file:/..." (or
    // "file:///...") — it never starts with '/' itself, scheme included. The previous
    // `uri.toString().startsWith('/')` check was therefore never true for genuine file
    // URIs, so every file: URI fell through to the "wrap as synthetic /.mycelium/ URL"
    // branch below. That was invisible as long as nothing exercised this overload with
    // plain file URIs — but GraalJS's CommonJS/ESM package-scope lookup (enabled via
    // js.commonjs-require) walks a file's ancestor directories by repeatedly calling
    // Env.getPublicTruffleFile(URI) with file: URIs, which routes through this method.
    // The old buggy branch wrapped each of those in another "/.mycelium/" layer rather
    // than returning a real, shrinking parent path, so each "walk up to the parent
    // directory" step actually produced a *longer* synthetic path instead — an
    // unbounded loop that alternated between two DEBUG-observed shapes:
    //   file:/.mycelium/file:/.mycelium/.../home/me/package.json
    //   file:///.mycelium/file:/.mycelium/.../home/me
    // growing without bound until the process was killed. Only the scheme, not the
    // string's first character, distinguishes a real filesystem path (file:, or no
    // scheme at all) from our synthetic http(s)-as-import-URL convention.
    override fun parsePath(uri: URI): Path =
        when {
            uri.scheme == null || uri.scheme == "file" -> Path.of(uri)
            // GraalJS treats any import specifier containing a colon (e.g. "node:path") as
            // an absolute URI rather than a bare specifier, routing it here instead of
            // parsePath(String) — so the "node:"-prefixed core module form has to be
            // handled in this overload too.
            uri.scheme == "node" -> coreModules["node:${uri.schemeSpecificPart}"]
                ?.let { syntheticCoreModulePath(it) }
                ?: throw MyceliumException("mycelium: unknown core module 'node:${uri.schemeSpecificPart}'")
            else -> Path.of("/.mycelium/$uri")
        }

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
        if (path.startsWith("#")) {
            val fromDir = sourceDirectoryStack.get().firstOrNull() ?: Path.of("").toAbsolutePath()
            resolveSubpathImport(path, fromDir)?.let { return it }
            throw MyceliumException(
                "mycelium: cannot find module '$path' — no matching \"imports\" entry found in any " +
                    "ancestor package.json from '$fromDir'"
            )
        }
        if (isBareSpecifier(path)) {
            coreModules[path]?.let { return syntheticCoreModulePath(it) }
            val fromDir = sourceDirectoryStack.get().firstOrNull() ?: Path.of("").toAbsolutePath()
            val hostLang = detectHostLanguageFromCallStack()

            // "Try all, ordered by host language": a bare specifier encountered while the
            // guest call stack is inside Ruby or Python is checked against that language's
            // own package ecosystem (gems / site-packages) first, since that's by far the
            // most likely match and doesn't require walking a node_modules tree that will
            // never exist for it. Node's own resolution (below) is still attempted
            // afterwards for every host language — a Ruby file might, e.g., import a
            // cross-language JS package — and whichever of gem/site-packages resolution
            // wasn't already tried up front is attempted last, as a final fallback.
            if (hostLang?.languageId == "ruby") {
                resolveRubyGem(path)?.let { return it }
            } else if (hostLang?.languageId == "python") {
                resolvePythonPackage(path)?.let { return it }
            }

            when (val resolution = resolveNodeModule(path, fromDir)) {
                is NodeModuleResolution.Found -> return resolution.path
                is NodeModuleResolution.PackageNotFound -> {
                    if (yolo) {
                        val (packageName, _) = splitSpecifier(path)
                        yoloInstall(packageName)
                        when (val retry = resolveNodeModule(path, fromDir)) {
                            is NodeModuleResolution.Found -> return retry.path
                            else -> {}
                        }
                    }
                    throw MyceliumException(
                        "mycelium: cannot find module '$path' — no node_modules/$path found from " +
                            "'$fromDir' or any parent directory"
                    )
                }
                NodeModuleResolution.NoNodeModulesTree -> {}
            }

            if (hostLang?.languageId != "ruby") {
                resolveRubyGem(path)?.let { return it }
            }
            if (hostLang?.languageId != "python") {
                resolvePythonPackage(path)?.let { return it }
            }
        }
        return Path.of(path)
    }

    // Inspects the current Truffle call stack the same way newByteChannel does for
    // cross-language shim generation, to figure out which guest language is asking for a
    // bare specifier to be resolved. Returns null (rather than defaulting to JS) when no
    // language can be identified from the stack, e.g. resolution triggered outside of any
    // guest call (there is then nothing more specific to prefer over plain npm lookup).
    private fun detectHostLanguageFromCallStack(): LanguageSupport? {
        val mc = mycelium ?: return null
        val frames = mutableListOf<String>()
        Truffle.getRuntime().iterateFrames { frame ->
            frames.add("${frame.callTarget}")
            null
        }
        return mc.detectHostLanguage(frames)
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

    // Python's `sys.path` (site-packages directories plus the stdlib's own library dirs),
    // discovered once by shelling out to `python3` and cached for the process's lifetime —
    // mirrors the "run once at startup" approach the task called for, just done lazily
    // (on first bare-specifier resolution that needs it) rather than eagerly at
    // InterceptingFileSystem construction, so paying the subprocess cost is skipped
    // entirely for JS-only or Ruby-only projects that never touch Python. Falls back to
    // an empty list (rather than throwing) when python3 isn't on PATH, so Python package
    // resolution just always misses in that case instead of breaking every other import.
    private val pythonSitePackages: List<Path> by lazy { computePythonSearchPaths() }

    internal fun computePythonSearchPaths(): List<Path> =
        try {
            val process = ProcessBuilder(
                "python3", "-c", "import sys; print('\\n'.join(sys.path))"
            ).redirectErrorStream(false).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { Path.of(it) }
                .filter { Files.isDirectory(it) }
                .toList()
        } catch (e: java.io.IOException) {
            emptyList()
        }

    // Resolves a bare specifier (e.g. "numpy" or "pkg/submodule") against Python's search
    // path: a package directory with an `__init__.py`, or a plain `<specifier>.py` module
    // file. `searchPaths` defaults to the real, subprocess-derived `sys.path` but can be
    // overridden by tests to point at a fake site-packages layout without needing a real
    // Python installation.
    internal fun resolvePythonPackage(specifier: String, searchPaths: List<Path> = pythonSitePackages): Path? {
        for (dir in searchPaths) {
            val initPy = dir.resolve(specifier).resolve("__init__.py")
            if (Files.isRegularFile(initPy)) return initPy
            val moduleFile = dir.resolve("$specifier.py")
            if (Files.isRegularFile(moduleFile)) return moduleFile
        }
        return null
    }

    // Ruby's `$LOAD_PATH` (covers both installed gems' `lib/` directories and the
    // interpreter's own stdlib), discovered once by shelling out to `ruby` and cached —
    // same lazy-on-first-use, empty-list-on-missing-binary approach as
    // pythonSitePackages/computePythonSearchPaths above.
    private val rubyLoadPaths: List<Path> by lazy { computeRubySearchPaths() }

    internal fun computeRubySearchPaths(): List<Path> =
        try {
            val process = ProcessBuilder("ruby", "-e", "puts \$LOAD_PATH")
                .redirectErrorStream(false).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { Path.of(it) }
                .filter { Files.isDirectory(it) }
                .toList()
        } catch (e: java.io.IOException) {
            emptyList()
        }

    // Resolves a bare specifier against Ruby's load path: either `<specifier>.rb` directly
    // in a load-path directory, or the conventional `<specifier>/<specifier>.rb` layout
    // gems use for their main entry file. `searchPaths` defaults to the real,
    // subprocess-derived `$LOAD_PATH` but can be overridden by tests to point at a fake
    // gem directory layout without needing a real Ruby installation.
    internal fun resolveRubyGem(specifier: String, searchPaths: List<Path> = rubyLoadPaths): Path? {
        for (dir in searchPaths) {
            val direct = dir.resolve("$specifier.rb")
            if (Files.isRegularFile(direct)) return direct
            val nested = dir.resolve(specifier).resolve("$specifier.rb")
            if (Files.isRegularFile(nested)) return nested
        }
        return null
    }

    // `--yolo` support: when a bare specifier can't be found in any node_modules tree,
    // shell out to `npm install <packageName>` (falling back to `pnpm install` if npm
    // isn't on PATH) in the entry file's own directory, then let the caller retry
    // resolution. The install directory is the *topmost* entry on the source-directory
    // stack (i.e. the running file's own directory, not whichever nested file happens to
    // be importing the missing package) so packages always land in the project root's
    // node_modules rather than scattered across subdirectories, mirroring how `npm
    // install` is normally invoked from a project's root. Falls back to the process's
    // cwd if the stack is empty (e.g. installing while evaluating inline code rather than
    // a file via runFile).
    private fun yoloInstall(packageName: String) {
        val installDir = (sourceDirectoryStack.get().lastOrNull() ?: Path.of("").toAbsolutePath()).toFile()
        System.err.println("mycelium: installing $packageName...")
        val exitCode = runInstallCommand(listOf("npm", "install", packageName), installDir)
            ?: runInstallCommand(listOf("pnpm", "install", packageName), installDir)
            ?: throw MyceliumException("mycelium: --yolo requires npm or pnpm to be installed")
        if (exitCode != 0) {
            throw MyceliumException(
                "mycelium: --yolo install of '$packageName' failed (exit code $exitCode)"
            )
        }
    }

    // Runs an install command, returning its exit code, or `null` if the command itself
    // couldn't be launched (e.g. the binary isn't on PATH) so the caller can fall back to
    // a different package manager. IO is inherited so the user sees npm/pnpm's own
    // install progress rather than it being silently swallowed.
    private fun runInstallCommand(command: List<String>, dir: File): Int? =
        try {
            ProcessBuilder(command).directory(dir).inheritIO().start().waitFor()
        } catch (e: java.io.IOException) {
            null
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

    // Resolves a `#`-prefixed "subpath import" specifier (e.g. `#ansi-styles`) against the
    // nearest ancestor `package.json`'s `"imports"` field, per Node's subpath-imports
    // resolution — a private-to-the-package specifier-remapping mechanism distinct from
    // `"exports"`. Walks up from `fromDir` looking for the first `package.json` that both
    // exists and declares an `"imports"` entry for `specifier`; returns null (letting the
    // caller report a "cannot find module" error) if none is found. The resolved value is
    // returned as an absolute path relative to that package.json's own directory.
    private fun resolveSubpathImport(specifier: String, fromDir: Path): Path? {
        var dir: Path? = fromDir.toAbsolutePath().normalize()
        while (dir != null) {
            val packageJson = dir.resolve("package.json")
            if (Files.exists(packageJson)) {
                val json = packageJson.readText()
                val resolved = try {
                    context!!.eval("js", IMPORTS_RESOLVER).execute(json, specifier).let {
                        if (it.isString) it.asString() else null
                    }
                } catch (e: Exception) {
                    null
                }
                if (resolved != null) return dir.resolve(resolved).normalize()
                // A package.json with no matching (or no) "imports" entry stops the walk —
                // subpath imports are private to the *nearest* package boundary, mirroring
                // Node's own behavior of not searching past the first package.json found.
                return null
            }
            dir = dir.parent
        }
        return null
    }

    // Node stdlib modules Mycelium ships as synthetic ESM resources rather than resolving
    // through node_modules — see library/src/main/resources/node-stdlib/. Both the bare
    // ("path") and "node:"-prefixed forms are accepted, per Node's own convention.
    private val coreModules = mapOf(
        "path" to "path.mjs",
        "node:path" to "path.mjs",
        "events" to "events.mjs",
        "node:events" to "events.mjs",
        "util" to "util.mjs",
        "node:util" to "util.mjs",
        "os" to "os.mjs",
        "node:os" to "os.mjs",
        "fs" to "fs.mjs",
        "node:fs" to "fs.mjs",
        "buffer" to "buffer.mjs",
        "node:buffer" to "buffer.mjs",
        "url" to "url.mjs",
        "node:url" to "url.mjs",
        "stream" to "stream.mjs",
        "node:stream" to "stream.mjs",
        "crypto" to "crypto.mjs",
        "node:crypto" to "crypto.mjs",
        "process" to "process.mjs",
        "node:process" to "process.mjs",
    )

    private fun syntheticCoreModulePath(resourceFile: String): Path =
        Path.of("$CORE_MODULE_PREFIX$resourceFile")

    private fun readCoreModule(path: Path): SeekableByteChannel {
        val resourceName = path.toString().removePrefix(CORE_MODULE_PREFIX)
        val stream = javaClass.classLoader.getResourceAsStream("node-stdlib/$resourceName")
            ?: throw MyceliumException("mycelium: unknown core module resource '$resourceName'")
        val content = stream.use { it.readBytes() }.toString(StandardCharsets.UTF_8)
        return ReadOnlyBufferChannel(StandardCharsets.UTF_8.encode(content))
    }

    // Decides whether a `.js`/`.cjs` file should be treated as CommonJS (and therefore
    // transpiled to ESM before GraalJS sees it). `.mjs` is always ESM, `.cjs` is always
    // CommonJS; for plain `.js`, Node's own rule applies — walk up to the nearest ancestor
    // `package.json` and check its `"type"` field, defaulting to CommonJS when absent
    // (either because there's no package.json at all, or it doesn't say `"type": "module"`).
    private fun isCjsFile(path: Path): Boolean {
        when (path.extension) {
            "mjs" -> return false
            "cjs" -> return true
            "js" -> {}
            else -> return false
        }
        var dir = path.parent
        while (dir != null) {
            val packageJson = dir.resolve("package.json")
            if (Files.exists(packageJson)) {
                return try {
                    val json = packageJson.readText()
                    context!!.eval("js", PACKAGE_TYPE_RESOLVER).execute(json).asString() != "module"
                } catch (e: Exception) {
                    true
                }
            }
            dir = dir.parent
        }
        return true
    }

    // Backs the `require` shim InterceptingFileSystem prepends to transpiled CJS output
    // (see the CJS branch of newByteChannel) — wired up as the JS global
    // `__mycelium_require__` in Mycelium.init. `fromDirStr` is the requiring file's own
    // directory, captured at transpile time (not the source-directory stack, which has
    // already been popped by the time the transpiled module's top-level code — including
    // its `require(...)` calls — actually runs).
    fun requireModule(specifier: String, fromDirStr: String): org.graalvm.polyglot.Value {
        val fromDir = Path.of(fromDirStr)
        val resolved = resolveRequireTarget(specifier, fromDir)
        val cacheKey = resolved.toString()

        cjsModuleCache[cacheKey]?.let { return it.getMember("exports") }

        return if (resolved.startsWith(CORE_MODULE_PREFIX)) {
            requireCoreModule(resolved, cacheKey)
        } else {
            requireCjsModule(resolved, cacheKey)
        }
    }

    // Node's stdlib shims (path, events, ...) are shipped as genuine ESM sources rather
    // than CommonJS (see node-stdlib/ resources), so they're evaluated as an ES module
    // directly instead of being run through the Node-style CJS wrapper requireCjsModule
    // uses. Still cached under the same `{ exports, loaded }` shape as CJS modules so the
    // cache-lookup fast path in requireModule works uniformly regardless of which kind of
    // module was loaded.
    private fun requireCoreModule(resolved: Path, cacheKey: String): org.graalvm.polyglot.Value {
        val content = readChannelToString(readCoreModule(resolved))
        // Core modules live at a synthetic path with no real parent directory on disk (and
        // have no relative imports of their own to resolve), so their temp file goes in
        // the system temp dir rather than alongside a nonexistent "resolved.parent".
        return requireEsmModule(content, cacheKey, tmpFileDir = null)
    }

    // Evaluates `content` as a genuine ES module (rather than through the Node-style CJS
    // wrapper requireCjsModule uses) and caches the result the same way, for callers that
    // determined the target isn't actually CommonJS — core stdlib modules, and `.js` files
    // that are nominally CommonJS by Node's package.json rule but contain genuine ESM
    // syntax anyway (see looksLikeEsm).
    private fun requireEsmModule(content: String, cacheKey: String, tmpFileDir: File?): org.graalvm.polyglot.Value {
        // Mirrors Mycelium.evalModule: writing to a real, `.mjs`-suffixed temp file
        // (rather than an in-memory CharSequence Source) is what actually makes GraalJS
        // both parse it as a module *and* hand context.eval() back the module's namespace
        // object instead of `undefined` — a bare `.mimeType(...)` hint on a CharSequence
        // Source was tried and evaluated fine but returned an empty namespace. The temp
        // file is created alongside the source's own directory (when known) so any
        // relative imports inside `content` keep resolving against the right base
        // directory.
        val tmpFile = File.createTempFile("mycelium-require-esm-", ".mjs", tmpFileDir)
        val moduleValue = try {
            tmpFile.writeText(content)
            context!!.eval(Source.newBuilder("js", tmpFile).build())
        } finally {
            tmpFile.delete()
        }
        // Mirrors Node's CJS/ESM interop: a module with a default export hands that back
        // verbatim; a module with only named exports hands back its whole namespace
        // object instead.
        val exports = if (moduleValue.hasMember("default")) moduleValue.getMember("default") else moduleValue
        val moduleObj = context!!.eval("js", "({ exports: undefined, loaded: true })")
        moduleObj.putMember("exports", exports)
        cjsModuleCache[cacheKey] = moduleObj
        return exports
    }

    // Runs `resolved`'s source through Node's own CJS module-wrapping convention —
    // wrapping it in `(function(exports, require, module, __filename, __dirname) { ... })`
    // and invoking that function with a fresh `module`/`exports` object — rather than
    // transpiling to ESM (the previous CjsCompiler/esbuild approach). This is what makes
    // `module`, `exports`, `require`, `__filename` and `__dirname` all behave exactly as
    // they do in Node without any static rewriting, and (combined with inserting the
    // module object into the cache *before* calling the wrapper below) is what makes
    // circular requires return partial exports instead of stack-overflowing.
    private fun requireCjsModule(resolved: Path, cacheKey: String): org.graalvm.polyglot.Value {
        val filename = resolved.toString()
        val dir = (resolved.parent ?: resolved).toString()
        val content = resolved.readText()

        // See looksLikeEsm: a file can be *treated* as CommonJS by Node's own
        // package.json-driven rule while still containing genuine ESM syntax — evaluate
        // such content as a real ES module instead of wrapping it as CJS (which would be
        // a syntax error, since `export`/top-level `import` aren't legal inside a
        // function body).
        if (looksLikeEsm(content)) {
            return requireEsmModule(content, cacheKey, tmpFileDir = (resolved.parent ?: resolved).toFile())
        }

        val moduleObj = context!!.eval(
            "js",
            "({ exports: {}, id: ${jsonStringLiteral(filename)}, " +
                "filename: ${jsonStringLiteral(filename)}, loaded: false })"
        )
        // Inserted into the cache *before* the module body runs below — this is what
        // makes a circular require (A requires B while B is still in the middle of
        // requiring A) return the same, possibly-still-partial `module.exports` object
        // reference instead of recursively re-evaluating A and stack-overflowing.
        cjsModuleCache[cacheKey] = moduleObj

        // A `require("./data.json")` should hand back the parsed JSON value itself (as in
        // Node), not run JSON source text through the function wrapper below (which would
        // parse as a syntactically-invalid function body for anything but a bare object
        // literal statement).
        if (resolved.extension == "json") {
            val parsed = context!!.eval("js", "(function(json) { return JSON.parse(json); })").execute(content)
            moduleObj.putMember("exports", parsed)
            moduleObj.putMember("loaded", true)
            return parsed
        }

        // Each module gets its own `require` function, closing over *that* module's own
        // directory — not the entry file's — so a relative `require("./x")` inside a
        // nested module resolves against the directory it actually lives in.
        val requireForModule = context!!.eval(
            "js",
            "(function(id) { return globalThis.__mycelium_require__(id, ${jsonStringLiteral(dir)}); })"
        )

        val wrapperSource = "(function(exports, require, module, __filename, __dirname) {\n" +
            content +
            "\n})"
        val wrapperFn = context!!.eval(Source.newBuilder("js", wrapperSource, filename).build())

        wrapperFn.execute(moduleObj.getMember("exports"), requireForModule, moduleObj, filename, dir)
        moduleObj.putMember("loaded", true)
        return moduleObj.getMember("exports")
    }

    // Escapes `s` as a JS double-quoted string literal, for splicing an arbitrary
    // filesystem path into generated JS source text.
    private fun jsonStringLiteral(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun readChannelToString(channel: SeekableByteChannel): String {
        // Both SeekableByteChannel implementations InterceptingFileSystem hands out
        // (ReadOnlyBufferChannel, HttpChannel) fill the destination buffer's backing
        // array directly without advancing its position — relying on `hasRemaining()`
        // to drive the read loop (as ordinary SeekableByteChannel consumers do) would
        // spin the buffer's position at 0 forever. Advance it manually from each call's
        // return value instead.
        val size = channel.size().toInt()
        val buffer = java.nio.ByteBuffer.allocate(size)
        channel.use {
            var total = 0
            while (total < size) {
                val n = it.read(buffer)
                if (n < 0) break
                total += n
                buffer.position(total)
            }
        }
        buffer.flip()
        return StandardCharsets.UTF_8.decode(buffer).toString()
    }

    // Resolves a `require()` specifier the same way Node would: relative/absolute paths
    // resolve against the requiring file's directory (trying a few conventional
    // extensions if the exact path doesn't exist), core modules resolve to their
    // synthetic resource path, and everything else resolves via the same node_modules
    // walk bare ESM imports use.
    private fun resolveRequireTarget(specifier: String, fromDir: Path): Path {
        if (specifier.startsWith(".") || specifier.startsWith("/")) {
            val candidate = fromDir.resolve(specifier).normalize()
            if (Files.exists(candidate) || candidate.extension.isNotEmpty()) return candidate
            for (ext in listOf("js", "cjs", "mjs", "json")) {
                val withExt = Path.of("$candidate.$ext")
                if (Files.exists(withExt)) return withExt
            }
            val indexJs = candidate.resolve("index.js")
            if (Files.exists(indexJs)) return indexJs
            return candidate
        }
        coreModules[specifier]?.let { return syntheticCoreModulePath(it) }
        return when (val resolution = resolveNodeModule(specifier, fromDir)) {
            is NodeModuleResolution.Found -> resolution.path
            is NodeModuleResolution.PackageNotFound, NodeModuleResolution.NoNodeModulesTree ->
                throw MyceliumException(
                    "mycelium: cannot find module '$specifier' required from '$fromDir'"
                )
        }
    }

    // Whether `content`'s top level actually uses ESM `import`/`export` syntax, regardless
    // of what isCjsFile concluded from the file's extension/package.json — a `.js` file
    // under a package.json lacking `"type": "module"` is *treated* as CommonJS by Node's
    // own rule, but some fixtures/packages in the wild (and in this codebase's own tests,
    // e.g. writeFakePackage) ship genuine ESM source under that configuration anyway.
    // Wrapping such a file in the CJS `(function(exports, require, module, ...) { ... })`
    // wrapper would be a syntax error (`export`/top-level `import` aren't legal inside a
    // function body), so both requireCjsModule and the ESM-import branch of newByteChannel
    // check this first and fall back to evaluating the content as a genuine ES module.
    private val esmSyntaxPattern = Regex("(?m)^\\s*(import\\s|import\\{|export\\s|export\\{|export\\*)")

    private fun looksLikeEsm(content: String): Boolean = esmSyntaxPattern.containsMatchIn(content)

    companion object {
        private const val CORE_MODULE_PREFIX = "/.mycelium-core/"

        private val PACKAGE_TYPE_RESOLVER = """
            (function (json) {
                try { return JSON.parse(json).type || "commonjs"; }
                catch (e) { return "commonjs"; }
            })
        """.trimIndent()

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

        // Resolves a `#`-prefixed subpath-imports specifier against a package.json's
        // "imports" field. The mapped value can be a plain string, or a conditions object
        // keyed by environment (e.g. "node", "import", "require", "default") — per Node's
        // own resolution order, "node" is preferred, then "import", then "default".
        // Returns null (not a string) when there's no "imports" field or no matching entry,
        // which the Kotlin caller distinguishes from a real string result via `isString`.
        private val IMPORTS_RESOLVER = """
            (function (json, specifier) {
                try {
                    var pkg = JSON.parse(json);
                    var imports = pkg.imports;
                    if (!imports || typeof imports !== "object") return null;
                    var entry = imports[specifier];
                    if (entry === undefined) return null;
                    if (typeof entry === "string") return entry;
                    if (entry && typeof entry === "object") {
                        if (typeof entry.node === "string") return entry.node;
                        if (typeof entry.import === "string") return entry.import;
                        if (typeof entry.require === "string") return entry.require;
                        if (typeof entry.default === "string") return entry.default;
                    }
                    return null;
                } catch (e) {
                    return null;
                }
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
        val pushed = path.isAbsolute && !path.startsWith("/.mycelium/") && !path.startsWith(CORE_MODULE_PREFIX)
        if (pushed) {
            val parent = path.parent ?: path
            sourceDirectoryStack.get().addFirst(parent)
        }
        try {
            // Node stdlib modules (`path`, ...) are served from bundled resources rather
            // than the real filesystem — see readCoreModule / the coreModules map above.
            if (path.startsWith(CORE_MODULE_PREFIX)) {
                return readCoreModule(path)
            }
            // TypeScript is transpiled to plain JS via esbuild before GraalJS ever sees it —
            // GraalJS itself has no TypeScript support. This only strips types/syntax (no
            // bundling), so import specifiers inside the output are left for
            // InterceptingFileSystem's normal resolution to handle, same as any other JS file.
            if (!path.startsWith("/.mycelium/") &&
                (path.extension == "ts" || path.extension == "mts") &&
                !options.contains(StandardOpenOption.WRITE) &&
                !options.contains(StandardOpenOption.CREATE)
            ) {
                val js = TsCompiler.transpile(path)
                return ReadOnlyBufferChannel(StandardCharsets.UTF_8.encode(js))
            }
            // CommonJS (require()/module.exports) source is never transpiled or evaluated
            // through GraalJS's ordinary ESM pipeline at all — instead, whenever GraalJS's
            // `import` machinery asks for the byte content of a CJS file (e.g. a plain
            // `import x from "./foo.js"` where foo.js is CommonJS), a one-line ESM shim
            // is handed back that defers to the exact same Node-style module loader
            // `require()` itself uses — InterceptingFileSystem.requireModule, via the
            // `__mycelium_require__` global Mycelium.init wires up. Routing both `import`
            // and `require()` of a CJS file through requireModule means both share its
            // module cache: `import`ing the same CJS file twice, or `require`ing it from
            // two different modules, evaluates its top-level code exactly once and hands
            // back the same `module.exports` object each time (see cjsModuleCache).
            if (!path.startsWith("/.mycelium/") &&
                isCjsFile(path) &&
                !options.contains(StandardOpenOption.WRITE) &&
                !options.contains(StandardOpenOption.CREATE)
            ) {
                val content = path.readText()
                // See looksLikeEsm: a `.js` file can be *treated* as CommonJS by Node's
                // own package.json-driven rule while still containing genuine ESM syntax
                // — pass such content through unchanged rather than wrapping it as CJS.
                if (looksLikeEsm(content)) {
                    return ReadOnlyBufferChannel(StandardCharsets.UTF_8.encode(content))
                }
                val dir = (path.parent ?: path).toString()
                val esmShim = "export default globalThis.__mycelium_require__(" +
                    "${jsonStringLiteral(path.toString())}, ${jsonStringLiteral(dir)});\n"
                return ReadOnlyBufferChannel(StandardCharsets.UTF_8.encode(esmShim))
            }
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
                // Every compiled LanguageSupport so far (C, Rust) emits LLVM bitcode, which
                // GraalVM loads under the "llvm" language ID regardless of the source
                // language. TinyGoSupport is the first exception — tinygo emits a
                // WebAssembly binary, which must be loaded under "wasm" instead. Keying off
                // outputExtension (rather than targetLang.languageId, which is itself
                // "wasm" for Go) keeps this in sync with whatever Compiler.compile actually
                // wrote to disk.
                val value = if (targetLang.outputExtension == "wasm") {
                    loadWasmExports(compiled)
                } else {
                    val source = Source.newBuilder("llvm", compiled.toFile()).build()
                    context!!.eval(source)
                }
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

    // GraalVM's own "wasm" Truffle language (context.eval("wasm", file)) hands back a
    // Value with zero memberKeys — there is no supported way to reach a WASM module's
    // exports through the raw Truffle wasm language directly, which makes it useless for
    // shim generation (see the CRITICAL BLOCKER note on TinyGoSupport in Languages.kt).
    // Instead, this loads the module through GraalJS's built-in `WebAssembly` global (the
    // same JS API browsers/Node expose): compile a `WebAssembly.Module` from the compiled
    // binary's bytes and instantiate it, then return `instance.exports` — an ordinary JS
    // object whose memberKeys generateShim can already enumerate exactly like it does for
    // LLVM-backed exports.
    //
    // The compiled bytes are handed to JS via a polyglot binding (a plain Java byte array)
    // rather than splicing them into JS source text, so this doesn't choke on/slow to a
    // crawl for larger WASM binaries. tinygo's `-target=wasi` output imports a handful of
    // WASI preview1 functions even for WASI-oblivious code like `add.go` (its runtime init
    // pulls them in); those are stubbed out below with harmless no-ops since none of them
    // are expected to be exercised by cross-language-imported pure functions.
    private fun loadWasmExports(compiled: Path): org.graalvm.polyglot.Value {
        val wasmBytes = Files.readAllBytes(compiled)
        val bindingsKey = "__mycelium_wasm_bytes__${compiled.fileName}__${System.nanoTime()}__"
        context!!.polyglotBindings.putMember(bindingsKey, wasmBytes)
        try {
            return context!!.eval(
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
            context!!.polyglotBindings.removeMember(bindingsKey)
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

package org.mycelium.library

import com.oracle.truffle.api.Truffle
import org.mycelium.library.vfs.VfsDelegate
import org.mycelium.library.vfs.VfsPathResolver
import org.mycelium.library.vfs.VfsProviderRegistry
import org.mycelium.library.vfs.VfsResolution
import org.mycelium.library.vfs.PathStat
import org.mycelium.library.remote.RemoteFileSystem
import org.mycelium.library.remote.RemoteFileSystemRegistry
import org.mycelium.library.compiler.CompileToJsDispatcher
import org.mycelium.library.registry.PackageInstallerRegistry
import java.io.File
import java.net.URI
import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryStream.Filter
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileTime
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.FileSystem
import java.nio.charset.StandardCharsets
import java.nio.file.*
import kotlin.io.path.extension
import kotlin.io.path.readText

// GraalVM FileSystem implementation that's the seam for all of Mycelium's cross-language
// and cross-ecosystem import support. This class itself is a thin orchestrator — it holds
// the state a GraalVM FileSystem needs to expose (the source-directory stack, the shared
// Context/Mycelium references) and coordinates between the focused pieces that do the
// actual resolution/loading work:
//   - NodeModuleResolver: node_modules walk, package.json exports/main resolution, CJS/ESM
//     file-kind detection.
//   - PythonModuleResolver / RubyModuleResolver: sys.path / $LOAD_PATH bare-specifier
//     resolution for cross-language Python/Ruby imports.
//   - CjsRuntime: `require()`, the CJS module cache, and the CJS->ESM interop shim.
//   - ShimGenerator: the cross-language (LLVM/WASM/other-guest-language) ESM shim GraalJS
//     is handed when a module's own language differs from its importer's.
//   - CoreModuleRegistry: Node stdlib modules shipped as bundled synthetic resources.
//   - Compiler: LLVM/WASM compilation and WASM export loading.
class InterceptingFileSystem : FileSystem {
    private var context: Context? = null
    private var mycelium: Mycelium? = null
    private var yolo: Boolean = false
    private val delegate = FileSystem.newDefaultFileSystem()

    // Extension point for presenting a file as a traversable filesystem tree (zip today; see
    // vfs/) as if it were an ordinary directory — e.g. reading
    // "/proj/assets/bundle.zip/icons/logo.png" transparently extracts icons/logo.png from
    // bundle.zip.
    private val vfsProviderRegistry = VfsProviderRegistry()
    private val vfsPathResolver = VfsPathResolver(vfsProviderRegistry)

    // Extension point for protocol-based remote access (S3, GCS, WebDAV, ...) — see
    // remote/RemoteFileSystem's own doc comment for why this is separate from
    // vfsProviderRegistry above. Empty by default; a consumer registers providers onto
    // this instance (e.g. via Mycelium's composition root) the same way
    // vfsProviderRegistry's own extras would be. http(s) imports are handled by the
    // pre-existing HttpImportCache special case below rather than through this registry.
    val remoteFileSystemRegistry = RemoteFileSystemRegistry()

    // Set by init() — provided by Mycelium (the composition root) rather than reached for
    // as a global singleton, so this class depends only on the CompileToJsDispatcher/
    // PackageInstaller abstractions and can be constructed/tested with fakes of either.
    private var compileToJsDispatcher: CompileToJsDispatcher? = null
    // One PackageInstaller per PackageEcosystem — see Mycelium.kt's composition root,
    // which constructs one per ecosystem's PackageIndex (NpmRegistryClient/
    // RubyGemsClient/PyPIClient) and registers it here. Nullable (rather than required)
    // so existing callers/tests that construct an InterceptingFileSystem without needing
    // cross-ecosystem auto-install (e.g. PythonInteropExperimentTest, which only
    // exercises the npm/JS path) don't need to be updated to pass one.
    private var packageInstallerRegistry: PackageInstallerRegistry? = null

    // The directory relative paths fall back to resolving against when nothing more
    // specific applies (see currentResolutionDir/toAbsolutePath/yoloInstall*'s own uses
    // below) — defaults to the real JVM process's own cwd, but overridden per-request in
    // daemon mode (see init's `cwd` param and Mycelium's own `cwd` constructor param) so a
    // request's relative paths resolve against the *calling* process's directory rather
    // than the long-running daemon's.
    private var cwd: Path = Path.of("").toAbsolutePath()

    private val nodeModuleResolver = NodeModuleResolver { context!! }
    private val pythonModuleResolver = PythonModuleResolver()
    private val rubyModuleResolver = RubyModuleResolver()
    private val cjsRuntime = CjsRuntime({ context!! }, nodeModuleResolver)
    private val shimGenerator = ShimGenerator { context!! }

    private val sourceDirectoryStack = ThreadLocal.withInitial { ArrayDeque<Path>() }

    // The directory of the most recently read real project file, kept around *after*
    // newByteChannel's own push/pop of sourceDirectoryStack has already unwound.
    //
    // GraalJS's ESM loader (DefaultESModuleLoader, at least as of the GraalVM 24.2/25.0
    // series) does not fetch a module's bytes and resolve that module's own import
    // specifiers as one nested operation — it fully fetches the module (a self-contained
    // newByteChannel call whose push/pop of sourceDirectoryStack completes before
    // returning) and only *afterwards*, in a separate top-level call, asks this FileSystem
    // to resolve each of that module's import specifiers (parsePath). By the time that
    // second call happens, sourceDirectoryStack has already been popped back to whatever
    // was active before the module was fetched (typically the entry file's directory, via
    // withSourceDirectory) — not the module's own directory. A `#`-prefixed subpath import
    // (Node's package-private "imports" field) resolves against the *nearest* ancestor
    // package.json and stops there, so being handed the wrong (too-shallow) starting
    // directory makes it stop at the wrong package.json entirely — e.g. resolving chalk's
    // `#ansi-styles` against the *importing project's* package.json instead of chalk's own,
    // which has no matching "imports" entry.
    //
    // This field plugs that gap: it's updated (never popped) on every newByteChannel read
    // of a real project file, so parsePath can prefer "the directory of whatever was read
    // most recently" over the (by-then-stale) sourceDirectoryStack when resolving that
    // file's own specifiers. It's only updated while sourceDirectoryStack is already
    // non-empty (i.e. while a runFile/withSourceDirectory context is active) so that
    // unrelated engine bootstrap reads — e.g. GraalVM loading the Ruby/Python language
    // home's own files before any user code runs, which happen with an empty stack — don't
    // clobber it.
    private val lastReadModuleDir = ThreadLocal<Path?>()

    // The directory to resolve a module's own specifiers (bare, subpath-import, or
    // otherwise) against: the most recently read real file's directory if we're in the
    // middle of resolving that file's own imports (the common case once any file has been
    // read at all), falling back to the outermost entry point's directory — and finally the
    // process's cwd — when nothing has been read yet (resolving the entry file's own
    // top-level imports). See lastReadModuleDir's own comment for why the latter two,
    // sourceDirectoryStack-derived, options are not always accurate on their own.
    private fun currentResolutionDir(): Path =
        lastReadModuleDir.get()
            ?: sourceDirectoryStack.get().firstOrNull()
            ?: cwd

    fun init(
        context: Context,
        mycelium: Mycelium,
        compileToJsDispatcher: CompileToJsDispatcher,
        packageInstallerRegistry: PackageInstallerRegistry,
        yolo: Boolean = false,
        cwd: Path? = null,
    ) {
        this.context = context
        this.mycelium = mycelium
        this.compileToJsDispatcher = compileToJsDispatcher
        this.packageInstallerRegistry = packageInstallerRegistry
        this.yolo = yolo
        if (cwd != null) this.cwd = cwd
    }

    // Seeds the source-directory stack with `dir` for the duration of `block`, so that
    // relative imports occurring while evaluating an entry point (which is not itself
    // read through this FileSystem) still resolve against that entry point's directory
    // rather than the process's working directory.
    fun <T> withSourceDirectory(dir: Path, block: () -> T): T {
        sourceDirectoryStack.get().addFirst(dir)
        // Only meaningful within a single runFile's module graph — reset on entry/exit so a
        // stale value can't leak from a previous, unrelated runFile call on a reused thread
        // (e.g. across tests sharing a JVM), and so it doesn't survive past this call for
        // the *next* runFile to (incorrectly) inherit as its own "most recently read" file.
        val previousLastReadModuleDir = lastReadModuleDir.get()
        lastReadModuleDir.set(null)
        try {
            return block()
        } finally {
            sourceDirectoryStack.get().removeFirst()
            lastReadModuleDir.set(previousLastReadModuleDir)
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
            uri.scheme == "node" -> CoreModuleRegistry.coreModules["node:${uri.schemeSpecificPart}"]
                ?.let { CoreModuleRegistry.syntheticCoreModulePath(it) }
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
            val fromDir = currentResolutionDir()
            nodeModuleResolver.resolveSubpathImport(path, fromDir)?.let { return it }
            throw MyceliumException(
                "mycelium: cannot find module '$path' — no matching \"imports\" entry found in any " +
                    "ancestor package.json from '$fromDir'"
            )
        }
        // GraalJS's own ESM loader routes any colon-containing specifier through
        // parsePath(URI) instead (see that overload's own "node"/else branches) — but other
        // guest languages' own `require`-equivalents (Ruby's `require`, in particular) hand
        // a URL specifier to this String overload directly. Wrapping it in the same
        // synthetic "/.mycelium/<uri>" convention here keeps both routes converging on the
        // same newByteChannel handling below.
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return Path.of("/.mycelium/$path")
        }
        if (remoteScheme(path) != null) {
            return Path.of("/.mycelium/$path")
        }
        if (isBareSpecifier(path)) {
            CoreModuleRegistry.coreModules[path]?.let { return CoreModuleRegistry.syntheticCoreModulePath(it) }
            val fromDir = currentResolutionDir()
            val hostLang = detectHostLanguageFromCallStack()

            // Ordered by host language (see LanguageSupport.packageEcosystemOrder): a bare
            // specifier encountered while the guest call stack is inside Ruby or Python is
            // checked against that language's own package ecosystem (gems / site-packages)
            // first, since that's by far the most likely match and doesn't require walking a
            // node_modules tree that will never exist for it. Node's own resolution is still
            // attempted for every host language — a Ruby file might, e.g., import a
            // cross-language JS package — and whichever of gem/site-packages resolution
            // wasn't already tried up front is attempted last, as a final fallback.
            val ecosystemOrder = hostLang?.packageEcosystemOrder ?: LanguageSupport.DEFAULT_PACKAGE_ECOSYSTEM_ORDER
            for (ecosystem in ecosystemOrder) {
                when (ecosystem) {
                    PackageEcosystem.NPM ->
                        when (val resolution = nodeModuleResolver.resolveNodeModule(path, fromDir)) {
                            is NodeModuleResolver.Resolution.Found -> return resolution.path
                            is NodeModuleResolver.Resolution.PackageNotFound -> {
                                if (yolo) {
                                    val (packageName, _) = nodeModuleResolver.splitSpecifier(path)
                                    yoloInstall(packageName)
                                    when (val retry = nodeModuleResolver.resolveNodeModule(path, fromDir)) {
                                        is NodeModuleResolver.Resolution.Found -> return retry.path
                                        else -> {}
                                    }
                                }
                                throw MyceliumException(
                                    "mycelium: cannot find module '$path' — no node_modules/$path found from " +
                                        "'$fromDir' or any parent directory"
                                )
                            }
                            NodeModuleResolver.Resolution.NoNodeModulesTree -> {}
                        }
                    PackageEcosystem.RUBYGEMS -> rubyModuleResolver.resolveRubyGem(path)?.let { return it }
                    PackageEcosystem.PYPI -> pythonModuleResolver.resolvePythonPackage(path)?.let { return it }
                }
            }

            // `--yolo` support for Ruby/Python, mirroring npm's own yoloInstall above: a
            // bare specifier that couldn't be resolved against $LOAD_PATH/sys.path (real or
            // previously auto-installed) is fetched from RubyGems/PyPI and vendored into the
            // project directory, then resolution is retried once. Gated on `hostLang` (the
            // guest call stack actually being inside that language) rather than trying both
            // unconditionally, matching the ordering rationale in the comment above: a
            // missing specifier while the stack is inside Ruby should try installing a gem,
            // not guess it might be a PyPI package instead.
            if (yolo && (hostLang?.languageId == "ruby" || hostLang?.languageId == "python") &&
                packageInstallerRegistry?.get(PackageEcosystem.RUBYGEMS) != null &&
                packageInstallerRegistry?.get(PackageEcosystem.PYPI) != null
            ) {
                val (packageName, _) = nodeModuleResolver.splitSpecifier(path)
                if (hostLang.languageId == "ruby") {
                    yoloInstallRubyGem(packageName)
                    rubyModuleResolver.resolveRubyGem(path)?.let { return it }
                } else {
                    yoloInstallPyPIPackage(packageName)
                    pythonModuleResolver.resolvePythonPackage(path)?.let { return it }
                }
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

    // Matches `path` against every scheme currently registered with
    // remoteFileSystemRegistry (e.g. "s3://bucket/key" against a registered "s3" provider)
    // — mirrors the hardcoded "http://"/"https://" prefix check just above, generalized to
    // whatever schemes a consumer has registered.
    private fun remoteScheme(path: String): String? =
        path.substringBefore("://", missingDelimiterValue = "")
            .takeIf { it.isNotEmpty() && remoteFileSystemRegistry.forScheme(it) != null }

    private fun isBareSpecifier(path: String): Boolean =
        path.isNotEmpty() &&
            !isSyntheticIdentifier(path) &&
            !path.startsWith(".") &&
            !path.startsWith("/") &&
            !path.startsWith("http:") &&
            !path.startsWith("https:")

    // Resolves a bare specifier against Python's search path — exposed here (rather than
    // requiring callers to reach into PythonModuleResolver themselves) purely so tests can
    // exercise it against a fake site-packages layout via an explicit `searchPaths`
    // override, without needing a real Python installation. Two overloads (rather than a
    // default parameter) so an explicitly-empty `searchPaths` list — "search nothing" — is
    // distinguishable from "use the real subprocess-derived default".
    internal fun resolvePythonPackage(specifier: String): Path? =
        pythonModuleResolver.resolvePythonPackage(specifier)

    internal fun resolvePythonPackage(specifier: String, searchPaths: List<Path>): Path? =
        pythonModuleResolver.resolvePythonPackage(specifier, searchPaths)

    internal fun computePythonSearchPaths(): List<Path> = pythonModuleResolver.computePythonSearchPaths()

    // Resolves a bare specifier against Ruby's load path — see resolvePythonPackage's own
    // comment for why this is exposed as a thin pass-through rather than requiring tests to
    // construct a RubyModuleResolver directly.
    internal fun resolveRubyGem(specifier: String): Path? =
        rubyModuleResolver.resolveRubyGem(specifier)

    internal fun resolveRubyGem(specifier: String, searchPaths: List<Path>): Path? =
        rubyModuleResolver.resolveRubyGem(specifier, searchPaths)

    internal fun computeRubySearchPaths(): List<Path> = rubyModuleResolver.computeRubySearchPaths()

    // `--yolo` support: when a bare specifier can't be found in any node_modules tree,
    // install `packageName` in the entry file's own directory, then let the caller retry
    // resolution. The install directory is the *topmost* entry on the source-directory
    // stack (i.e. the running file's own directory, not whichever nested file happens to
    // be importing the missing package) so packages always land in the project root's
    // node_modules rather than scattered across subdirectories, mirroring how `npm
    // install` is normally invoked from a project's root. Falls back to the process's
    // cwd if the stack is empty (e.g. installing while evaluating inline code rather than
    // a file via runFile).
    //
    // Tries the pure-Kotlin path first (PackageInstaller, backed by NpmRegistryClient +
    // PackageCache — no subprocess, resolving version specs via SemverResolver — see
    // registry/SemverResolver.kt) and only falls back to shelling out to `npm
    // install`/`pnpm install` if that fails (e.g. a network/registry hiccup), so
    // installing a single missing package no longer requires npm/pnpm to be present at
    // all in the common case.
    private fun yoloInstall(packageName: String) {
        val installDir = (sourceDirectoryStack.get().lastOrNull() ?: cwd).toFile()
        System.err.println("mycelium: installing $packageName...")
        try {
            packageInstallerRegistry!![PackageEcosystem.NPM]!!.installPackageTree(
                packageName, null, installDir.toPath().resolve("node_modules")
            )
            return
        } catch (e: Exception) {
            System.err.println(
                "mycelium: pure-Kotlin install of '$packageName' failed (${e.message ?: e}), " +
                    "falling back to npm/pnpm..."
            )
        }
        val exitCode = runInstallCommand(listOf("npm", "install", packageName), installDir)
            ?: runInstallCommand(listOf("pnpm", "install", packageName), installDir)
            ?: throw MyceliumException("mycelium: --yolo requires npm or pnpm to be installed")
        if (exitCode != 0) {
            throw MyceliumException(
                "mycelium: --yolo install of '$packageName' failed (exit code $exitCode)"
            )
        }
    }

    // `--yolo` support for Ruby: fetches `gemName` from rubygems.org (via the
    // RubyGemsClient-backed installer registered under PackageEcosystem.RUBYGEMS) and
    // PackageCache's on-disk archive cache —
    // no `gem`/`bundle` subprocess involved, mirroring yoloInstall's own pure-Kotlin-first
    // approach for npm — into a project-local vendor directory
    // (".mycelium-vendor/gems/<name>", picked to avoid colliding with any real
    // "vendor/gems"-style directory a Bundler-managed project might already have), then
    // registers the extracted gem's own lib/ directory with RubyModuleResolver so the
    // caller's retried resolveRubyGem call can find it. Unlike npm's yoloInstall, there is
    // no subprocess (`gem install`) fallback: RubyGems has no equivalent of npm's
    // registry-agnostic CLI story that's reasonable to shell out to portably here, and a
    // missing/failed pure-Kotlin install should simply surface as the same "cannot find
    // module" the caller already reports when nothing resolves.
    private fun yoloInstallRubyGem(gemName: String) {
        val installDir = sourceDirectoryStack.get().lastOrNull() ?: cwd
        val vendorDir = installDir.resolve(".mycelium-vendor").resolve("gems")
        System.err.println("mycelium: installing gem '$gemName'...")
        try {
            packageInstallerRegistry!![PackageEcosystem.RUBYGEMS]!!.installPackageTree(gemName, null, vendorDir)
            rubyModuleResolver.registerInstalledGem(vendorDir.resolve(gemName))
        } catch (e: Exception) {
            System.err.println("mycelium: installing gem '$gemName' failed (${e.message ?: e})")
        }
    }

    // `--yolo` support for Python — see yoloInstallRubyGem's own comment for the overall
    // shape (PyPIClient + PackageCache, project-local ".mycelium-vendor/pypi/<name>" vendor
    // directory to avoid colliding with a real virtualenv's own "pypi" or similarly-named
    // directory, no pip subprocess fallback).
    private fun yoloInstallPyPIPackage(packageName: String) {
        val installDir = sourceDirectoryStack.get().lastOrNull() ?: cwd
        val vendorDir = installDir.resolve(".mycelium-vendor").resolve("pypi")
        System.err.println("mycelium: installing PyPI package '$packageName'...")
        try {
            packageInstallerRegistry!![PackageEcosystem.PYPI]!!.installPackageTree(packageName, null, vendorDir)
            pythonModuleResolver.registerInstalledPackage(vendorDir.resolve(packageName), packageName)
        } catch (e: Exception) {
            System.err.println("mycelium: installing PyPI package '$packageName' failed (${e.message ?: e})")
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

    // Backs the `require` shim InterceptingFileSystem prepends to every transpiled CJS
    // module — see CjsRuntime.requireModule / newByteChannel's CJS branch.
    fun requireModule(specifier: String, fromDirStr: String): org.graalvm.polyglot.Value =
        cjsRuntime.requireModule(specifier, fromDirStr)

    // Backs `require.resolve(specifier)` / `require.resolve.paths(specifier)` — see
    // CjsRuntime.resolveRequireSpecifier(Paths).
    fun resolveRequireSpecifier(specifier: String, fromDirStr: String): String =
        cjsRuntime.resolveRequireSpecifier(specifier, fromDirStr)

    fun resolveRequireSpecifierPaths(specifier: String, fromDirStr: String): Array<String> =
        cjsRuntime.resolveRequireSpecifierPaths(specifier, fromDirStr)

    // Finds the vfs boundary in `path`, if any — see VfsPathResolver's own doc comment for
    // the algorithm. Never called for synthetic ".mycelium"/core-module paths, which never
    // touch the real filesystem at all.
    private fun resolveVfsBoundary(path: Path): VfsResolution? {
        if (path.startsWith("/.mycelium/") || path.startsWith(CoreModuleRegistry.CORE_MODULE_PREFIX)) return null
        val probeDelegate = object : VfsDelegate {
            override fun stat(p: String): PathStat {
                val np = Path.of(p)
                return if (Files.exists(np)) PathStat(exists = true, isDirectory = Files.isDirectory(np))
                else PathStat(exists = false, isDirectory = false)
            }

            override fun openChannel(p: String): SeekableByteChannel =
                delegate.newByteChannel(Path.of(p), setOf(StandardOpenOption.READ))
        }
        return vfsPathResolver.resolve(path.toString(), probeDelegate)
    }

    // If `path` is a synthetic "/.mycelium/<uri>" path (see parsePath's own comment) whose
    // scheme is a registered RemoteFileSystem's, returns that provider and the decoded URI
    // — used by checkAccess/newDirectoryStream/readAttributes to route directory-listing/
    // attribute lookups to the provider, the same way newByteChannel already routes reads.
    // http(s) paths are excluded here since those keep going through HttpImportCache/
    // delegate instead (see newByteChannel's own "http", "https" branches).
    private fun resolveRemoteBoundary(path: Path): Pair<RemoteFileSystem, URI>? {
        if (!path.startsWith("/.mycelium/")) return null
        val uri = URI(path.toString().substring("/.mycelium/".length).replace(":/", "://"))
        if (uri.scheme == "http" || uri.scheme == "https") return null
        val provider = remoteFileSystemRegistry.forScheme(uri.scheme) ?: return null
        return provider to uri
    }

    override fun checkAccess(path: Path, modes: Set<AccessMode>, vararg linkOptions: LinkOption) {
        val absolute = toAbsolutePath(path)
        val probed = if (!absolute.startsWith("/.mycelium/") && !absolute.startsWith(CoreModuleRegistry.CORE_MODULE_PREFIX)) {
            nodeModuleResolver.resolveExtensionlessPath(absolute)
        } else {
            absolute
        }
        resolveRemoteBoundary(probed)?.let { (provider, uri) ->
            provider.attributes(uri) ?: throw NoSuchFileException(probed.toString())
            return
        }
        resolveVfsBoundary(probed)?.let { resolution ->
            resolution.provider.attributes(resolution.channel, resolution.internalPath)
                ?: throw NoSuchFileException(probed.toString())
            return
        }
        delegate.checkAccess(probed, modes, *linkOptions)
    }

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
        val resolvedPath = if (rawPath.isAbsolute) rawPath else toAbsolutePath(rawPath)
        // GraalJS's own ESM import resolution (unlike Mycelium's require()/node_modules
        // paths, which already probe extensions themselves — see resolveExtensionlessPath)
        // hands relative/absolute specifiers straight through as-is, so an extensionless
        // import (e.g. package "main" fields resolved by a *different* package's own ESM
        // loader, or hand-written extensionless relative imports) would otherwise 404 here.
        // Only probe for real filesystem reads — never for synthetic core-module/URL paths,
        // and never when the caller is writing/creating a file.
        val path = if (!options.contains(StandardOpenOption.WRITE) &&
            !options.contains(StandardOpenOption.CREATE) &&
            !resolvedPath.startsWith("/.mycelium/") &&
            !resolvedPath.startsWith(CoreModuleRegistry.CORE_MODULE_PREFIX)
        ) {
            nodeModuleResolver.resolveExtensionlessPath(resolvedPath)
        } else {
            resolvedPath
        }
        if (!options.contains(StandardOpenOption.WRITE) && !options.contains(StandardOpenOption.CREATE)) {
            resolveVfsBoundary(path)?.let { resolution ->
                return ReadOnlyBufferChannel(
                    java.nio.ByteBuffer.wrap(resolution.provider.read(resolution.channel, resolution.internalPath))
                )
            }
        }
        val pushed = path.isAbsolute && !path.startsWith("/.mycelium/") && !path.startsWith(CoreModuleRegistry.CORE_MODULE_PREFIX)
        if (pushed) {
            val parent = path.parent ?: path
            sourceDirectoryStack.get().addFirst(parent)
            // Record this as the "most recently read module's directory" — see
            // lastReadModuleDir's own comment — but only once a runFile/withSourceDirectory
            // context is actually active (i.e. sourceDirectoryStack already had something
            // on it before the push above), so engine-bootstrap reads that happen before any
            // user code runs (e.g. GraalVM loading a guest language's own home files, which
            // do so with an empty stack) don't get mistaken for project-file reads.
            if (sourceDirectoryStack.get().size > 1) {
                lastReadModuleDir.set(parent)
            }
        }
        try {
            // Node stdlib modules (`path`, ...) are served from bundled resources rather
            // than the real filesystem — see CoreModuleRegistry.readCoreModule.
            if (path.startsWith(CoreModuleRegistry.CORE_MODULE_PREFIX)) {
                return CoreModuleRegistry.readCoreModule(path)
            }
            // Compile-to-JS languages (TypeScript, CoffeeScript, Civet, ... — anything
            // registered in CompilerRegistry) are compiled to plain JS before GraalJS ever
            // sees them — GraalJS itself only understands plain JS. This only strips types/
            // syntax (no bundling), so import specifiers inside the output are left for
            // InterceptingFileSystem's normal resolution to handle, same as any other JS file.
            // JsSupport (which also owns plain .js/.mjs) reports a non-null jsCompilerId too
            // ("typescript"), since the same LanguageSupport object covers both TypeScript
            // and plain JS extensions — so "js"/"mjs" are excluded here explicitly rather
            // than relying on jsCompilerId alone, which would otherwise route ordinary JS
            // source through the compile-to-JS pipeline as well.
            if (!path.startsWith("/.mycelium/") &&
                path.extension != "js" && path.extension != "mjs" &&
                !options.contains(StandardOpenOption.WRITE) &&
                !options.contains(StandardOpenOption.CREATE)
            ) {
                val compilerId = mycelium?.supportForExtension(path.extension)?.jsCompilerId
                if (compilerId != null) {
                    val js = compileToJsDispatcher!!.compile(compilerId, path, context!!)
                    return ReadOnlyBufferChannel(StandardCharsets.UTF_8.encode(js))
                }
            }
            // CommonJS (require()/module.exports) source is never transpiled or evaluated
            // through GraalJS's ordinary ESM pipeline at all — instead, whenever GraalJS's
            // `import` machinery asks for the byte content of a CJS file (e.g. a plain
            // `import x from "./foo.js"` where foo.js is CommonJS), a one-line ESM shim
            // is handed back that defers to the exact same Node-style module loader
            // `require()` itself uses — CjsRuntime.requireModule, via the
            // `__mycelium_require__` global Mycelium.init wires up. Routing both `import`
            // and `require()` of a CJS file through requireModule means both share its
            // module cache: `import`ing the same CJS file twice, or `require`ing it from
            // two different modules, evaluates its top-level code exactly once and hands
            // back the same `module.exports` object each time (see CjsRuntime.cjsModuleCache).
            if (!path.startsWith("/.mycelium/") &&
                nodeModuleResolver.isCjsFile(path) &&
                !options.contains(StandardOpenOption.WRITE) &&
                !options.contains(StandardOpenOption.CREATE)
            ) {
                val content = path.readText()
                // See NodeModuleResolver.looksLikeEsm: a `.js` file can be *treated* as
                // CommonJS by Node's own package.json-driven rule while still containing
                // genuine ESM syntax — pass such content through unchanged rather than
                // wrapping it as CJS.
                if (nodeModuleResolver.looksLikeEsm(content)) {
                    return ReadOnlyBufferChannel(StandardCharsets.UTF_8.encode(content))
                }
                val dir = (path.parent ?: path).toString()
                val esmShim = cjsRuntime.buildCjsEsmShim(path.toString(), dir)
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
                    Compiler.loadWasmExports(context!!, compiled)
                } else {
                    val source = Source.newBuilder("llvm", compiled.toFile()).build()
                    context!!.eval(source)
                }
                return shimGenerator.generateCrossLanguageShim(hostLang, value, path.toString())
            }
            if (hostLang.languageId == targetLang?.languageId || targetLang == null) {
                return if (path.startsWith("/.mycelium/")) {
                    val uri = URI(path.toString().substring("/.mycelium/".length).replace(":/", "://"))
                    when (uri.scheme) {
                        "http", "https" -> try {
                            delegate.newByteChannel(HttpImportCache.fetch(uri), setOf(StandardOpenOption.READ))
                        } catch (e: java.io.IOException) {
                            throw MyceliumException("mycelium: failed to fetch '$uri' — ${e.message}", e)
                        }
                        else -> remoteFileSystemRegistry.forScheme(uri.scheme)?.let { provider ->
                            try {
                                delegate.newByteChannel(
                                    RemoteImportCache.fetch(uri, provider::read), setOf(StandardOpenOption.READ)
                                )
                            } catch (e: Exception) {
                                throw MyceliumException("mycelium: failed to fetch '$uri' — ${e.message}", e)
                            }
                        } ?: throw MyceliumException("mycelium: unknown URL scheme '${uri.scheme}' in '$uri'")
                    }
                } else {
                    try {
                        // A project/npm file importing a core module via the "node:" prefix
                        // (e.g. `import { EventEmitter } from "node:events"`) would otherwise
                        // be resolved by GraalJS's ESM loader through a different module-cache
                        // key than the bare form ("events") — see
                        // CoreModuleRegistry.normalizeCoreModuleSpecifiers's own comment for
                        // why — so such a project ends up with two separately-evaluated copies
                        // of that core module (one for its own "node:events" import, another
                        // shared by whatever core modules it transitively imports, which
                        // always resolve via the bare form). Normalizing real .js/.mjs files'
                        // own "node:"-prefixed core-module imports here, the same as is already
                        // done for our bundled node-stdlib resources, keeps both sides on the
                        // same bare-specifier route so they share one instance.
                        if ((path.extension == "js" || path.extension == "mjs") &&
                            !options.contains(StandardOpenOption.WRITE) &&
                            !options.contains(StandardOpenOption.CREATE)
                        ) {
                            val content = path.readText()
                            if (content.contains("node:")) {
                                ReadOnlyBufferChannel(
                                    StandardCharsets.UTF_8.encode(
                                        CoreModuleRegistry.normalizeCoreModuleSpecifiers(content)
                                    )
                                )
                            } else {
                                delegate.newByteChannel(path, options, *attrs)
                            }
                        } else {
                            delegate.newByteChannel(path, options, *attrs)
                        }
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
                            HttpImportCache.fetch(uri).readText()
                        } catch (e: java.io.IOException) {
                            throw MyceliumException("mycelium: failed to fetch '$uri' — ${e.message}", e)
                        }
                        else -> remoteFileSystemRegistry.forScheme(uri.scheme)?.let { provider ->
                            try {
                                RemoteImportCache.fetch(uri, provider::read).readText()
                            } catch (e: Exception) {
                                throw MyceliumException("mycelium: failed to fetch '$uri' — ${e.message}", e)
                            }
                        } ?: throw MyceliumException("mycelium: unknown URL scheme '${uri.scheme}' in '$uri'")
                    }
                } else {
                    path.readText()
                }
                val value = context!!.eval(targetLang.languageId, code)
                return shimGenerator.generateCrossLanguageShim(hostLang, value, path.toString())
            }
        } finally {
            if (pushed) {
                sourceDirectoryStack.get().removeFirst()
            }
        }
    }

    override fun newDirectoryStream(dir: Path, filter: Filter<in Path>): DirectoryStream<Path> {
        resolveRemoteBoundary(dir)?.let { (provider, uri) ->
            val entries = provider.list(uri)
                .map { entry -> dir.resolve(entry.name) }
                .filter { filter.accept(it) }
            return object : DirectoryStream<Path> {
                override fun iterator(): MutableIterator<Path> = entries.toMutableList().iterator()
                override fun close() {}
            }
        }
        resolveVfsBoundary(dir)?.let { resolution ->
            val entries = resolution.provider.list(resolution.channel, resolution.internalPath)
                .map { entry -> dir.resolve(entry.name) }
                .filter { filter.accept(it) }
            return object : DirectoryStream<Path> {
                override fun iterator(): MutableIterator<Path> = entries.toMutableList().iterator()
                override fun close() {}
            }
        }
        return delegate.newDirectoryStream(dir, filter)
    }

    override fun toAbsolutePath(path: Path): Path {
        if (!path.isAbsolute) {
            val stack = sourceDirectoryStack.get()
            if (stack.isNotEmpty()) {
                return stack.first().resolve(path).normalize()
            }
            // No active runFile/withSourceDirectory scope (e.g. resolving a path outside
            // any file read) — fall back to this instance's own cwd (the caller's cwd in
            // daemon mode, see `cwd`'s own doc comment) rather than `path.toAbsolutePath()`,
            // which would silently resolve against the real JVM process's cwd instead.
            return cwd.resolve(path).normalize()
        }
        return path.toAbsolutePath()
    }

    override fun toRealPath(path: Path, vararg linkOptions: LinkOption): Path =
        path.toRealPath(*linkOptions)

    // GraalVM's FileSystem interface throws UnsupportedOperationException from this
    // method by default — Context.Builder.currentWorkingDirectory(Path) (see Mycelium's
    // `cwd` constructor param) calls straight through to this on whatever FileSystem the
    // Context is configured with, so leaving the default in place would make setting a
    // per-request cwd blow up the moment daemon mode tried to use it. Overriding it to
    // update our own `cwd` field keeps a single source of truth: this is also the field
    // `currentResolutionDir`/`toAbsolutePath`/`yoloInstall*` already fall back to, and
    // `init`'s own `cwd` param (called before the Context/builder exist) sets the same
    // field, so a request's cwd ends up consistent regardless of which of the two paths
    // (constructor-time `init`, or GraalVM calling this during `Context.build()`) sets it
    // last.
    override fun setCurrentWorkingDirectory(currentWorkingDirectory: Path) {
        cwd = currentWorkingDirectory
    }

    override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): MutableMap<String, Any> {
        resolveRemoteBoundary(path)?.let { (provider, uri) ->
            val attrs = provider.attributes(uri) ?: throw NoSuchFileException(path.toString())
            val modified = FileTime.fromMillis(attrs.lastModified)
            return mutableMapOf(
                "isDirectory" to attrs.isDirectory,
                "isRegularFile" to !attrs.isDirectory,
                "isSymbolicLink" to false,
                "isOther" to false,
                "size" to attrs.size,
                "lastModifiedTime" to modified,
                "creationTime" to modified,
                "lastAccessTime" to modified,
            )
        }
        resolveVfsBoundary(path)?.let { resolution ->
            val attrs = resolution.provider.attributes(resolution.channel, resolution.internalPath)
                ?: throw NoSuchFileException(path.toString())
            val modified = FileTime.fromMillis(attrs.lastModified)
            return mutableMapOf(
                "isDirectory" to attrs.isDirectory,
                "isRegularFile" to !attrs.isDirectory,
                "isSymbolicLink" to false,
                "isOther" to false,
                "size" to attrs.size,
                "lastModifiedTime" to modified,
                "creationTime" to modified,
                "lastAccessTime" to modified,
            )
        }
        return delegate.readAttributes(path, attributes, *options)
    }
}

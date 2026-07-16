package org.mycelium.library

import java.io.File
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.mycelium.library.compiler.CompileToJsDispatcher
import org.mycelium.library.compiler.CompilerCache
import org.mycelium.library.compiler.CompilerDownloader
import org.mycelium.library.compiler.CompilerRegistry
import org.mycelium.library.compiler.EsbuildBackend
import org.mycelium.library.registry.CratesIoClient
import org.mycelium.library.registry.GoProxyClient
import org.mycelium.library.registry.NpmRegistryClient
import org.mycelium.library.registry.PackageInstaller
import org.mycelium.library.registry.PyPIClient
import org.mycelium.library.registry.RubyGemsClient

class Mycelium(
    requestedLanguages: List<LanguageSupport> = listOf(
        JsSupport, CoffeeSupport, CivetSupport, LiveScriptSupport, ElmSupport, PureScriptSupport, ReScriptSupport,
        GleamSupport, ImbaSupport,
        DartSupport, KotlinJsSupport, ScalaJsSupport,
        RubySupport, PythonSupport, CSupport, RustSupport, TinyGoSupport
    ),
    // When true, a bare specifier that can't be resolved against any existing
    // node_modules tree triggers an automatic `npm install`/`pnpm install` of the
    // missing package (see InterceptingFileSystem.yoloInstall) rather than immediately
    // failing with "cannot find module".
    val yolo: Boolean = false,
    // When true (the default), `runFile` checks for a `package.json` with declared
    // dependencies alongside (or above) the entry file and, if `node_modules` is missing
    // or empty, runs `npm install`/`pnpm install` there before executing — see
    // maybeInstallDependencies. This is the declarative counterpart to `--yolo`: it runs
    // unconditionally (not gated on yolo) since installing exactly what a manifest already
    // declares is a much safer default than yolo's "install whatever a bare import
    // resolves to". Set to false (the app's `--no-install` flag) to skip it entirely.
    val autoInstall: Boolean = true
) {
    // JVM-mode support: library/build.gradle.kts splits the GraalVM language runtimes
    // (ruby/python/java/llvm/wasm) into separate, non-transitive Gradle Configurations
    // ("language packs") that a JVM-mode launch (app/build.gradle.kts's `jvmRun` task) can
    // add to the classpath selectively — see that file's own comments. Native-image builds
    // are unaffected (every pack is still added to the native-image classpath explicitly),
    // but in JVM mode a given process may genuinely be missing e.g. TruffleRuby's jars
    // entirely. `Engine#getLanguages` reports exactly what's actually resolvable on this
    // classpath/module-path, queried via a throwaway probe Engine below.
    //
    // This is deliberately kept separate from the real Context's own (implicit) Engine
    // rather than sharing one Engine instance between the two via `.engine(...)` —
    // attaching an explicit, potentially-multi-Context-shared Engine to a Context changes
    // GraalVM's language-sharing rules: languages that don't support context sharing (Ruby
    // is one — see the PolyglotException this used to throw, "lazily initialized language
    // 'ruby' does not support sharing... must be known ahead of time when the context is
    // created") then refuse to lazily initialize at all, breaking exactly the on-demand
    // "only touch a language when a script actually needs it" behavior this class relies
    // on. A second, throwaway Engine here costs one extra (and, since GraalVM CE has no
    // libgraal on a stock JDK, textually near-identical) startup warning printed to
    // stderr — not worth the sharing restriction to avoid.
    private val availableLanguageIds: Set<String> = Engine.create().languages.keys

    // `requestedLanguages` filtered down to what's actually usable in this process: a
    // LanguageSupport whose `languageId` isn't in `availableLanguageIds` (e.g. RubySupport
    // when no `rubyLang`-pack jar was added to a JVM-mode classpath) is dropped here rather
    // than left to fail lazily and confusingly the first time some script tries to use it —
    // every other consumer of `languages` (extensionMap, supportedExtensions,
    // detectHostLanguage, runFile/evalModule's "don't know how to run/import" checks) reads
    // this filtered list, so an unavailable language is reported the same clean way an
    // unrecognized extension already is, and the injectEvalModule loop in `init` below never
    // touches a language that isn't actually there (see RubySupport.injectEvalModule, which
    // unconditionally calls `context.eval("ruby", ...)` and would otherwise blow up
    // Mycelium's own constructor before any guest script ever ran).
    val languages: List<LanguageSupport> = requestedLanguages.filter { it.languageId in availableLanguageIds }

    private val filesystem = InterceptingFileSystem()

    // Composition root for the compiler/registry object graph: everything below is
    // constructed here (rather than reached for as a global singleton) and threaded down
    // into whatever actually needs it — CompileToJsDispatcher/InterceptingFileSystem — so
    // each of those depends only on the interfaces/classes it's handed, not on how they're
    // built. Each PackageIndex implementation (one per package ecosystem: npm, RubyGems,
    // PyPI, crates.io, the Go module proxy) is constructed exactly once here and paired with
    // its own PackageInstaller; a consumer wanting a different/fake registry for a given
    // ecosystem (e.g. a test) would swap it out right here rather than anywhere downstream.
    private val registryClient = NpmRegistryClient()
    private val rubyGemsClient = RubyGemsClient()
    private val pyPIClient = PyPIClient()
    // Constructed and available for callers that want to resolve/install a crate or Go
    // module directly (e.g. a future `mycelium add`-style CLI command, or compiled-language
    // dependency fetching once Compiler gains a linking step that can combine more than one
    // compiled unit — see CratesIoClient/GoProxyClient's own doc comments), but not yet
    // consulted by any *automatic* bare-specifier resolution the way rubyGemsClient/
    // pyPIClient are (via InterceptingFileSystem's yoloInstallRubyGem/yoloInstallPyPIPackage
    // below): RustSupport/TinyGoSupport compile a single source file straight to one
    // standalone LLVM-bitcode/WASM unit (Compiler.compile) with no dependency-graph
    // resolution or multi-unit linking step at all yet, so there's no compile-time seam an
    // auto-fetched crate/module's own compiled output could be wired into today without
    // that architecture existing first.
    private val cratesIoClient = CratesIoClient()
    private val goProxyClient = GoProxyClient()
    private val compilerDownloader = CompilerDownloader(registryClient)
    private val compilerRegistry = CompilerRegistry()
    private val compileToJsDispatcher =
        CompileToJsDispatcher(compilerRegistry, compilerDownloader, CompilerCache, EsbuildBackend())
    private val packageInstaller = PackageInstaller(registryClient)
    private val rubyGemsInstaller = PackageInstaller(rubyGemsClient)
    private val pyPIInstaller = PackageInstaller(pyPIClient)
    private val cratesIoInstaller = PackageInstaller(cratesIoClient)
    private val goProxyInstaller = PackageInstaller(goProxyClient)

    // js.commonjs-require was prototyped here and reverted — see TODO.md. Enabling it
    // swaps GraalJS's ES module loader for NpmCompatibleESModuleLoader, which rejects any
    // import specifier whose extension isn't on its own hardcoded whitelist (js/mjs/cjs/
    // json/node) with "Unsupported file extension" *before* InterceptingFileSystem.
    // newByteChannel ever runs — so it broke every cross-language import (.c/.rb/.rs) and
    // the new TypeScript-via-esbuild support (.ts) in one shot: 16 of 62 tests failed.
    // Mycelium's whole cross-language-import mechanism depends on GraalJS accepting
    // "unrecognized" extensions and handing them to our FileSystem to shim, so this
    // option cannot be enabled globally on the shared Context without redesigning that
    // mechanism (e.g. a separate Context per CJS-only subtree, which conflicts with the
    // single shared Context/polyglot-bindings architecture used everywhere else here).
    private val jsAvailable = "js" in availableLanguageIds

    private val context: Context = run {
        val builder = Context.newBuilder()
            .allowAllAccess(true)
            .allowIO(IOAccess.newBuilder().fileSystem(filesystem).build())
        // JS-specific Context options — only applied when GraalJS is actually on the
        // classpath. These are `js.*`-namespaced options that GraalVM will reject outright
        // ("Unknown option: js.esm-eval-returns-exports") if no language with ID "js" is
        // installed, so they must be gated rather than unconditionally set.
        if (jsAvailable) {
            builder
                // Makes Context#eval return the module's namespace object for ES modules
                // (so host code can read named/default exports off the returned Value)
                // instead of `undefined`. Needed by InterceptingFileSystem.requireModule.
                .option("js.esm-eval-returns-exports", "true")
                // Enables GraalJS's built-in `WebAssembly` JS API global. Additionally
                // gated on "wasm" being available — GraalJS's WebAssembly API requires the
                // "wasm" Truffle language to exist so it can delegate to it internally, and
                // throws IllegalStateException out of JSRealm's constructor if "wasm" is
                // absent.
                .option("js.webassembly", ("wasm" in availableLanguageIds).toString())
        }
        builder.build()
    }

    private val extensionMap: Map<String, LanguageSupport> =
        languages.flatMap { lang -> lang.extensions.map { it to lang } }.toMap()

    private val evalModuleFn = org.graalvm.polyglot.proxy.ProxyExecutable { args ->
        val lang = args[0].asString()
        val code = args[1].asString()
        evalModule(lang, code)
    }

    // Backs the `require` shim InterceptingFileSystem prepends to every transpiled CJS
    // module — see InterceptingFileSystem.requireModule / newByteChannel's CJS branch.
    private val requireFn = org.graalvm.polyglot.proxy.ProxyExecutable { args ->
        filesystem.requireModule(args[0].asString(), args[1].asString())
    }

    // Backs `require.resolve(specifier)` / `require.resolve.paths(specifier)` — see the
    // `requireForModule` shim InterceptingFileSystem builds for each CJS module.
    private val requireResolveFn = org.graalvm.polyglot.proxy.ProxyExecutable { args ->
        filesystem.resolveRequireSpecifier(args[0].asString(), args[1].asString())
    }

    private val requireResolvePathsFn = org.graalvm.polyglot.proxy.ProxyExecutable { args ->
        filesystem.resolveRequireSpecifierPaths(args[0].asString(), args[1].asString())
    }

    // Host-side single-threaded event loop backing `setTimeout`/`setInterval`/
    // `setImmediate` — see TimerManager's own doc comment for why this replaced an earlier
    // one-thread-per-timer implementation. `runFile` (below) drives this loop to
    // completion after evaluating the entry file, the same way Node keeps its process
    // alive only as long as ref'd timers/handles remain; `runEventLoop` is also exposed
    // publicly so tests (or any other caller not going through `runFile`) can pump it
    // directly after scheduling timers via a bare `eval`.
    private val timerManager = TimerManager()

    private fun millisFromArg(v: Value): Long = if (v.isNumber) v.asDouble().toLong() else 0L

    // Accepts either a `Timer` handle (as returned by our own `setTimeout`/`setInterval`/
    // `setImmediate` — see TimerManager.handleFor) or a raw numeric id, matching every
    // shape real code passes to `clearTimeout`/`clearInterval`/`clearImmediate`.
    private fun clearTimerArg(v: Value?) {
        if (v == null || v.isNull) return
        if (v.hasMember("close")) {
            v.invokeMember("close")
        } else if (v.isNumber) {
            timerManager.clear(v.asLong())
        }
    }

    private val setTimeoutFn = ProxyExecutable { args ->
        val callback = args[0]
        val delay = if (args.size > 1) millisFromArg(args[1]) else 0L
        val extraArgs = if (args.size > 2) args.drop(2) else emptyList()
        timerManager.handleFor(timerManager.schedule(callback, delay, false, extraArgs))
    }

    private val setIntervalFn = ProxyExecutable { args ->
        val callback = args[0]
        val delay = if (args.size > 1) millisFromArg(args[1]) else 0L
        val extraArgs = if (args.size > 2) args.drop(2) else emptyList()
        timerManager.handleFor(timerManager.schedule(callback, delay, true, extraArgs))
    }

    private val setImmediateFn = ProxyExecutable { args ->
        val callback = args[0]
        val extraArgs = if (args.size > 1) args.drop(1) else emptyList()
        timerManager.handleFor(timerManager.schedule(callback, 0L, false, extraArgs))
    }

    private val clearTimerFn = ProxyExecutable { args ->
        clearTimerArg(args.getOrNull(0))
        null
    }

    init {
        filesystem.init(context, this, compileToJsDispatcher, packageInstaller, yolo, rubyGemsInstaller, pyPIInstaller)
        for (lang in languages) {
            lang.injectEvalModule(context, evalModuleFn)
        }
        // Everything below is JS-specific: the `__mycelium_require__` bindings live on
        // `context.getBindings("js")`, and the init scripts are all evaluated as JS. When
        // GraalJS isn't on the classpath these calls would throw — skip them entirely.
        // Mycelium still constructs and can run non-JS languages (Ruby, Python, etc.) in
        // that case, just without the JS-hosted cross-language bridge, CJS require shim,
        // or Node-compat globals (process, Buffer, URL, timers, TextEncoder, etc.) that
        // only exist in the JS guest.
        if (jsAvailable) {
            context.getBindings("js").putMember("__mycelium_require__", requireFn)
            context.getBindings("js").putMember("__mycelium_require_resolve__", requireResolveFn)
            context.getBindings("js").putMember("__mycelium_require_resolve_paths__", requireResolvePathsFn)
            // setTimeout/setInterval/setImmediate and their clear* counterparts are
            // implemented entirely host-side (TimerManager) rather than in
            // TIMERS_INIT_SCRIPT's JS — see TimerManager's doc comment.
            context.getBindings("js").putMember("setTimeout", setTimeoutFn)
            context.getBindings("js").putMember("clearTimeout", clearTimerFn)
            context.getBindings("js").putMember("setInterval", setIntervalFn)
            context.getBindings("js").putMember("clearInterval", clearTimerFn)
            context.getBindings("js").putMember("setImmediate", setImmediateFn)
            context.getBindings("js").putMember("clearImmediate", clearTimerFn)
            context.eval("js", GLOBAL_ALIAS_INIT_SCRIPT)
            context.eval("js", PROCESS_INIT_SCRIPT)
            context.eval("js", GLOBAL_CRYPTO_INIT_SCRIPT)
            context.eval("js", ERROR_CAPTURE_STACK_TRACE_INIT_SCRIPT)
            context.eval("js", TEXT_ENCODING_INIT_SCRIPT)
            context.eval("js", QUEUE_MICROTASK_INIT_SCRIPT)
            context.eval("js", GLOBAL_BUFFER_INIT_SCRIPT)
            context.eval("js", GLOBAL_URL_INIT_SCRIPT)
        }
    }

    fun supportForExtension(extension: String): LanguageSupport? = extensionMap[extension]

    fun languageFromExtension(extension: String): String? = extensionMap[extension]?.languageId

    val supportedExtensions: List<String> get() = extensionMap.keys.sorted()

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
        if (!file.exists()) {
            throw MyceliumException("mycelium: file not found — '$path'")
        }
        val language = languageFromExtension(file.extension)
            ?: throw MyceliumException(
                if (supportedExtensions.isEmpty())
                    "mycelium: no languages available — add language JARs to the classpath " +
                        "(e.g. -Planguages=js or -Planguages=js,ruby)"
                else
                    "mycelium: don't know how to run '${file.name}' — supported extensions: " +
                        supportedExtensions.joinToString(", ")
            )
        if (autoInstall) {
            maybeInstallDependencies(file.absoluteFile.parentFile)
        }
        val code = file.readText()
        val source = Source.newBuilder(language, code, path).build()
        val dir = file.absoluteFile.parentFile.toPath()
        filesystem.withSourceDirectory(dir) { context.eval(source) }
        // Mirrors Node: the process (here, this call) stays alive only as long as a ref'd
        // timer/handle remains pending — evaluating the entry file above may have
        // registered `setTimeout`/`setInterval` callbacks via the Kotlin-backed globals,
        // which this drives to completion on the same (calling) thread that owns the
        // Context. A script that registered no timers (or only unref'd ones) returns
        // immediately, same as before this loop existed.
        if (jsAvailable) {
            runEventLoop()
        }
    }

    // Drives the host-side timer event loop (TimerManager) on the calling thread until no
    // ref'd `setTimeout`/`setInterval`/`setImmediate` timer remains pending. `runFile`
    // calls this automatically after evaluating the entry file; exposed publicly as well
    // so a caller driving Mycelium directly via `eval`/`evalModule` (e.g. a test, or an
    // embedder with its own entry point) can pump the same loop explicitly.
    fun runEventLoop() {
        timerManager.runUntilIdle()
    }

    fun eval(languageId: String, code: String): org.graalvm.polyglot.Value =
        context.eval(languageId, code)

    fun evalModule(languageId: String, code: String): org.graalvm.polyglot.Value {
        if (languages.none { it.languageId == languageId }) {
            throw MyceliumException(
                "mycelium: don't know how to import for language '$languageId' — supported extensions: " +
                    supportedExtensions.joinToString(", ")
            )
        }
        val tmpFile = File.createTempFile("mycelium-module-", moduleExtensionForLanguage(languageId))
        tmpFile.deleteOnExit()
        tmpFile.writeText(code)
        val source = Source.newBuilder(languageId, tmpFile).build()
        return context.eval(source)
    }

    fun getBindings(languageId: String): org.graalvm.polyglot.Value =
        context.getBindings(languageId)

    private fun moduleExtensionForLanguage(languageId: String): String =
        when (languageId) {
            "js" -> ".mjs"
            else ->
                languages.firstOrNull { it.languageId == languageId }
                    ?.extensions?.firstOrNull()
                    ?.let { ".$it" }
                    ?: ".tmp"
        }

    // Walks up from `startDir` (inclusive) looking for the nearest ancestor directory
    // containing a `package.json` — mirrors Node's own package-scope lookup, and matches
    // isCjsFile's ancestor walk in InterceptingFileSystem. Returns the directory (not the
    // file itself), since that's also where `node_modules` and the install command belong.
    private fun findPackageJsonDir(startDir: File): File? {
        var dir: File? = startDir.absoluteFile
        while (dir != null) {
            if (File(dir, "package.json").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    // The declarative counterpart to `--yolo`: before running a file, check whether the
    // nearest ancestor `package.json` declares any dependencies and, if so, whether
    // `node_modules` is missing or empty there. If both hold, install the declared
    // dependencies in that directory so they're present before the entry file's own
    // imports get a chance to fail. Runs unconditionally (irrespective of `--yolo`)
    // unless `autoInstall` is false (the app's `--no-install` flag) — installing exactly
    // what a manifest already declares doesn't carry the same "install whatever a bare
    // import happens to resolve to" risk `--yolo` does.
    //
    // Tries the pure-Kotlin path first (PackageInstaller, backed by NpmRegistryClient +
    // PackageCache — no subprocess), resolving `dependencies` and `devDependencies`
    // (merged, "dependencies" taking priority on name collisions) and their own
    // transitive dependencies recursively, hardlinked into `node_modules`. Version specs
    // (exact, ranges, x-ranges, dist-tags, ...) are resolved via SemverResolver — see
    // registry/SemverResolver.kt. Falls back to shelling out to `npm install`/`pnpm
    // install` only if that fails (e.g. a network/registry hiccup), so a plain
    // `package.json`-driven install no longer requires npm/pnpm to be present at all in
    // the common case.
    private fun maybeInstallDependencies(startDir: File) {
        val pkgDir = findPackageJsonDir(startDir) ?: return
        val pkgJsonFile = File(pkgDir, "package.json")
        val pkgJsonText = try {
            pkgJsonFile.readText()
        } catch (e: java.io.IOException) {
            return
        }
        if (!packageJsonDeclaresDependencies(pkgJsonText)) return

        val nodeModules = File(pkgDir, "node_modules")
        val nodeModulesIsEmpty = !nodeModules.exists() ||
            (nodeModules.isDirectory && (nodeModules.list()?.isEmpty() ?: true))
        if (!nodeModulesIsEmpty) return

        System.err.println("mycelium: installing dependencies from package.json...")
        try {
            val dependencies = packageJsonDependencies(pkgJsonText)
            packageInstaller.installDependencies(dependencies, nodeModules.toPath())
            return
        } catch (e: Exception) {
            System.err.println(
                "mycelium: pure-Kotlin dependency install failed (${e.message ?: e}), " +
                    "falling back to npm/pnpm..."
            )
        }

        val exitCode = runInstallCommand(listOf("npm", "install"), pkgDir)
            ?: runInstallCommand(listOf("pnpm", "install"), pkgDir)
            ?: throw MyceliumException(
                "mycelium: manifest-based install requires npm or pnpm to be installed"
            )
        if (exitCode != 0) {
            throw MyceliumException(
                "mycelium: manifest-based install failed (exit code $exitCode)"
            )
        }
    }

    // Runs an install command, returning its exit code, or `null` if the command itself
    // couldn't be launched (e.g. the binary isn't on PATH) so the caller can fall back to
    // a different package manager — same approach as InterceptingFileSystem's
    // runInstallCommand (used by `--yolo`), duplicated here since this class has no
    // dependency on InterceptingFileSystem's internals. IO is inherited so the user sees
    // npm/pnpm's own install progress rather than it being silently swallowed.
    private fun runInstallCommand(command: List<String>, dir: File): Int? =
        try {
            ProcessBuilder(command).directory(dir).inheritIO().start().waitFor()
        } catch (e: java.io.IOException) {
            null
        }

    // Parses `package.json` text and reports whether it declares any non-empty
    // `dependencies` or `devDependencies`. Delegates to the shared JS Context (rather than
    // a JVM JSON library) purely to reuse GraalJS's JSON.parse, mirroring
    // InterceptingFileSystem's ENTRY_POINT_RESOLVER/PACKAGE_TYPE_RESOLVER pattern.
    // Malformed JSON is treated as "nothing to install" rather than propagating a parse
    // error out of what's otherwise a best-effort pre-flight check.
    private fun packageJsonDeclaresDependencies(json: String): Boolean =
        context.eval("js", PACKAGE_DEPS_CHECK).execute(json).asBoolean()

    // Parses `package.json` text and returns its `dependencies` merged with its
    // `devDependencies` (name -> version spec), with "dependencies" taking priority over
    // "devDependencies" on name collisions — this is what actually drives the
    // pure-Kotlin (PackageInstaller) manifest-based install. Same GraalJS-JSON.parse
    // approach as packageJsonDeclaresDependencies above (this codebase reads
    // package.json fields through the shared JS Context rather than a JVM JSON library);
    // the merged map itself is round-tripped out as a JSON string and decoded with
    // org.json.JSONObject, matching how NpmRegistryClient already decodes registry JSON.
    // Malformed JSON yields an empty map, matching packageJsonDeclaresDependencies'
    // "nothing to install" treatment of the same case.
    private fun packageJsonDependencies(json: String): Map<String, String> {
        val mergedJson = context.eval("js", PACKAGE_DEPS_MERGE).execute(json).asString()
        val obj = org.json.JSONObject(mergedJson)
        return obj.keySet().associateWith { obj.optString(it) }
    }

    companion object {
        private val PACKAGE_DEPS_CHECK = """
            (function (json) {
                try {
                    var pkg = JSON.parse(json);
                    var deps = pkg.dependencies;
                    var devDeps = pkg.devDependencies;
                    var hasDeps = !!deps && Object.keys(deps).length > 0;
                    var hasDevDeps = !!devDeps && Object.keys(devDeps).length > 0;
                    return hasDeps || hasDevDeps;
                } catch (e) {
                    return false;
                }
            })
        """.trimIndent()

        private val PACKAGE_DEPS_MERGE = """
            (function (json) {
                try {
                    var pkg = JSON.parse(json);
                    var merged = {};
                    var devDeps = pkg.devDependencies || {};
                    var deps = pkg.dependencies || {};
                    for (var k in devDeps) merged[k] = devDeps[k];
                    for (var k in deps) merged[k] = deps[k];
                    return JSON.stringify(merged);
                } catch (e) {
                    return "{}";
                }
            })
        """.trimIndent()

        // Aliases the legacy Node.js `global` object to `globalThis` — many older npm
        // packages (e.g. thread-stream, pino's transport helper) reference the bare `global`
        // identifier directly rather than the standard `globalThis`, and throw
        // `ReferenceError: global is not defined` in Mycelium's guest environment without
        // this. In real Node, `global` and `globalThis` are literally the same object, so a
        // plain alias is enough — no separate object graph to keep in sync.
        private val GLOBAL_ALIAS_INIT_SCRIPT = """
            globalThis.global = globalThis;
        """.trimIndent()

        // Injects a Node-compatible `process` global onto `globalThis` before any guest
        // code runs (see the `init` block above) — many npm packages assume `process`
        // exists unconditionally, even outside actual Node. `Java.type(...)` (GraalJS's
        // Java interop) is used throughout rather than any Mycelium-side plumbing, since
        // this only needs to read a handful of JVM system properties/env vars.
        private val PROCESS_INIT_SCRIPT = """
            (function () {
                var JSystem = Java.type('java.lang.System');
                var JProcessHandle = Java.type('java.lang.ProcessHandle');

                function getenvMap() {
                    return JSystem.getenv();
                }

                // Mutations (process.env.FOO = 'bar') never reach the real OS environment
                // (the JVM has no supported way to do that) — instead they're tracked here
                // and take priority over the live `java.lang.System.getenv()` snapshot on
                // every trap, so a write becomes observable to any later read/has/keys
                // enumeration/delete, matching what real Node's mutable process.env does.
                var envOverrides = {};

                function envGet(key) {
                    if (Object.prototype.hasOwnProperty.call(envOverrides, key)) return envOverrides[key];
                    var v = getenvMap().get(key);
                    return v === null || v === undefined ? undefined : String(v);
                }

                var envProxy = new Proxy({}, {
                    get: function (target, key) {
                        if (typeof key !== 'string') return undefined;
                        return envGet(key);
                    },
                    set: function (target, key, value) {
                        envOverrides[String(key)] = String(value);
                        return true;
                    },
                    has: function (target, key) {
                        if (typeof key !== 'string') return false;
                        return Object.prototype.hasOwnProperty.call(envOverrides, key) || !!getenvMap().containsKey(key);
                    },
                    deleteProperty: function (target, key) {
                        delete envOverrides[String(key)];
                        return true;
                    },
                    ownKeys: function () {
                        var keys = {};
                        var it = getenvMap().keySet().iterator();
                        while (it.hasNext()) keys[String(it.next())] = true;
                        for (var k in envOverrides) keys[k] = true;
                        return Object.keys(keys);
                    },
                    getOwnPropertyDescriptor: function (target, key) {
                        var v = envGet(String(key));
                        if (v === undefined) return undefined;
                        return { enumerable: true, configurable: true, writable: true, value: v };
                    }
                });

                var current = JProcessHandle.current();
                var parentPid = current.parent().isPresent() ? Number(current.parent().get().pid()) : 0;

                globalThis.process = {
                    env: envProxy,
                    argv: [],
                    cwd: function () { return String(JSystem.getProperty('user.dir')); },
                    chdir: function (dir) { /* not really possible in JVM */ },
                    exit: function (code) { JSystem.exit(code || 0); },
                    platform: (function () {
                        var os = String(JSystem.getProperty('os.name')).toLowerCase();
                        if (os.includes('linux')) return 'linux';
                        if (os.includes('mac') || os.includes('darwin')) return 'darwin';
                        if (os.includes('win')) return 'win32';
                        return os;
                    })(),
                    arch: (function () {
                        var arch = String(JSystem.getProperty('os.arch'));
                        if (arch === 'amd64' || arch === 'x86_64') return 'x64';
                        if (arch === 'aarch64') return 'arm64';
                        return arch;
                    })(),
                    version: 'v20.0.0',
                    versions: { node: '20.0.0', mycelium: '0.1.0' },
                    pid: Number(current.pid()),
                    ppid: parentPid,
                    stdout: {
                        write: function (s) { JSystem.out.print(s); return true; },
                        isTTY: false,
                    },
                    stderr: {
                        write: function (s) { JSystem.err.print(s); return true; },
                        isTTY: false,
                    },
                    stdin: { isTTY: false },
                    hrtime: {
                        bigint: function () { return BigInt(JSystem.nanoTime()); },
                    },
                    nextTick: function (fn) {
                        var args = Array.prototype.slice.call(arguments, 1);
                        Promise.resolve().then(function () { fn.apply(null, args); });
                    },
                    emitWarning: function (msg) { JSystem.err.println('Warning: ' + msg); },
                    on: function () { return globalThis.process; },
                    once: function () { return globalThis.process; },
                    removeListener: function () { return globalThis.process; },
                };
            })();
        """.trimIndent()

        // Exposes a minimal Web Crypto API as `globalThis.crypto` — distinct from (and in
        // addition to) the importable Node `crypto` module (coreModules / crypto.mjs):
        // packages like `uuid` reference the bare global `crypto.randomUUID`/
        // `crypto.getRandomValues` directly, the way real Node.js has exposed since Node
        // 19, rather than importing the `crypto` module.
        private val GLOBAL_CRYPTO_INIT_SCRIPT = """
            (function () {
                globalThis.crypto = {
                    randomUUID: function () { return Java.type('java.util.UUID').randomUUID().toString(); },
                    getRandomValues: function (array) {
                        var sr = new (Java.type('java.security.SecureRandom'))();
                        var bytes = Java.type('java.lang.reflect.Array').newInstance(Java.type('java.lang.Byte').TYPE, array.length);
                        sr.nextBytes(bytes);
                        for (var i = 0; i < array.length; i++) array[i] = bytes[i] & 0xFF;
                        return array;
                    },
                    subtle: {},
                };
            })();
        """.trimIndent()

        // Polyfills V8's `Error.captureStackTrace(targetObject, constructorOpt)` /
        // `Error.stackTraceLimit` / `Error.prepareStackTrace`, none of which GraalJS exposes
        // (confirmed by probing `typeof Error.captureStackTrace` under this build — it's
        // `undefined`, not gated behind some other option flag). This is needed by express
        // (via http-errors/depd, both of which call `Error.captureStackTrace(this, <ctor>)`
        // unconditionally in their custom-Error constructors and would otherwise throw
        // "Error.captureStackTrace is not a function").
        //
        // Approach: `new Error()` inside `captureStackTrace` itself already gives GraalJS's
        // own (V8-formatted) `.stack` string for the current call site — this just trims
        // that string to imitate V8's semantics around it rather than reimplementing stack
        // capture from scratch:
        //   1. The frame for `captureStackTrace`'s own `new Error()` call is always dropped
        //      first — probing confirms it's reliably the top frame of the resulting stack,
        //      regardless of how deeply nested the real call site is.
        //   2. If `constructorOpt` is a named function, frames are dropped up to and
        //      including the first one that mentions its name (covers both a plain function
        //      frame "at Ctor (...)" and a `new`-call frame "at new Ctor (...)") — matching
        //      V8's "the stack no longer shows the constructor frame or anything above it"
        //      behavior, without an exact CallSite/function-identity comparison (which
        //      GraalJS's plain string-formatted `.stack` doesn't expose).
        //   3. The remaining frames are capped at `Error.stackTraceLimit` (default 10).
        // `.stack` is installed as a getter/setter pair (not a plain value) so that
        // `Error.prepareStackTrace`, if a guest later assigns one (some frameworks/test
        // runners do, e.g. to hook in source-map remapping), is consulted lazily on first
        // read — matching V8's own lazy-stack-formatting behavior — while a plain assignment
        // (`err.stack = "..."`, which some libraries do) still works by replacing the
        // accessor with an ordinary data property.
        private val ERROR_CAPTURE_STACK_TRACE_INIT_SCRIPT = """
            (function () {
                if (typeof Error.captureStackTrace === 'function') return;

                Error.stackTraceLimit = 10;

                function parseFrames(rawStack) {
                    var lines = String(rawStack || '').split('\n');
                    // lines[0] is the "Name: message" (or bare "Name") header line GraalJS
                    // itself produced for the throwaway Error — everything after is a
                    // "    at ..." frame line.
                    return lines.slice(1);
                }

                function makeCallSite(line) {
                    var trimmed = String(line).replace(/^\s*at\s+/, '');
                    var functionName = null;
                    var fileName = null;
                    var lineNumber = null;
                    var columnNumber = null;
                    var m = /^(.*?)\s+\((.*):(\d+):(\d+)\)$/.exec(trimmed);
                    if (m) {
                        functionName = m[1];
                        fileName = m[2];
                        lineNumber = Number(m[3]);
                        columnNumber = Number(m[4]);
                    } else {
                        var m2 = /^(.*):(\d+):(\d+)$/.exec(trimmed);
                        if (m2) {
                            fileName = m2[1];
                            lineNumber = Number(m2[2]);
                            columnNumber = Number(m2[3]);
                        } else {
                            functionName = trimmed || null;
                        }
                    }
                    var isNew = false;
                    if (functionName && functionName.indexOf('new ') === 0) {
                        isNew = true;
                        functionName = functionName.slice(4);
                    }
                    return {
                        getThis: function () { return undefined; },
                        getTypeName: function () { return null; },
                        getFunction: function () { return undefined; },
                        getFunctionName: function () { return functionName; },
                        getMethodName: function () { return null; },
                        getFileName: function () { return fileName; },
                        getLineNumber: function () { return lineNumber; },
                        getColumnNumber: function () { return columnNumber; },
                        getEvalOrigin: function () { return undefined; },
                        isToplevel: function () { return !functionName; },
                        isEval: function () { return false; },
                        isNative: function () { return false; },
                        isConstructor: function () { return isNew; },
                        isAsync: function () { return false; },
                        isPromiseAll: function () { return false; },
                        getPromiseIndex: function () { return null; },
                        toString: function () {
                            return functionName
                                ? (isNew ? 'new ' : '') + functionName + ' (' + fileName + ':' + lineNumber + ':' + columnNumber + ')'
                                : fileName + ':' + lineNumber + ':' + columnNumber;
                        },
                    };
                }

                Error.captureStackTrace = function captureStackTrace(targetObject, constructorOpt) {
                    var probe = new Error();
                    var frames = parseFrames(probe.stack);
                    // Drop the frame for this captureStackTrace call's own `new Error()`.
                    frames = frames.slice(1);
                    if (typeof constructorOpt === 'function' && constructorOpt.name) {
                        var idx = -1;
                        for (var i = 0; i < frames.length; i++) {
                            if (frames[i].indexOf(constructorOpt.name) !== -1) { idx = i; break; }
                        }
                        if (idx !== -1) frames = frames.slice(idx + 1);
                    }
                    var limit = typeof Error.stackTraceLimit === 'number' ? Error.stackTraceLimit : 10;
                    if (limit >= 0) frames = frames.slice(0, limit);

                    Object.defineProperty(targetObject, 'stack', {
                        configurable: true,
                        enumerable: false,
                        get: function () {
                            if (typeof Error.prepareStackTrace === 'function') {
                                var callSites = frames.map(makeCallSite);
                                return Error.prepareStackTrace(targetObject, callSites);
                            }
                            var name = (targetObject && targetObject.name) || 'Error';
                            var message = targetObject && targetObject.message;
                            var header = message ? name + ': ' + message : name;
                            return [header].concat(frames).join('\n');
                        },
                        set: function (value) {
                            Object.defineProperty(targetObject, 'stack', {
                                configurable: true,
                                enumerable: false,
                                writable: true,
                                value: value,
                            });
                        },
                    });
                };
            })();
        """.trimIndent()

        // Exposes the standard `TextEncoder`/`TextDecoder` globals (WHATWG Encoding API) —
        // GraalJS doesn't provide these itself, but plenty of npm packages (e.g. express's
        // dependency `content-disposition`, which RFC-5987-encodes filenames) reference them
        // as bare globals the way browsers/Node have exposed them for years, rather than
        // importing anything. Backed by `java.nio.charset` for the actual byte<->string
        // conversion, mirroring buffer.mjs's approach.
        private val TEXT_ENCODING_INIT_SCRIPT = """
            (function () {
                var JCharset = Java.type('java.nio.charset.StandardCharsets');
                var JString = Java.type('java.lang.String');
                var JByteArray = Java.type('byte[]');

                if (typeof globalThis.TextEncoder === 'undefined') {
                    function TextEncoder() {
                        this.encoding = 'utf-8';
                    }
                    TextEncoder.prototype.encode = function (input) {
                        input = input === undefined ? '' : String(input);
                        var buf = JCharset.UTF_8.encode(input);
                        var len = buf.remaining();
                        var arr = new Uint8Array(len);
                        for (var i = 0; i < len; i++) arr[i] = buf.get() & 0xFF;
                        return arr;
                    };
                    TextEncoder.prototype.encodeInto = function (input, dest) {
                        var encoded = this.encode(input);
                        var written = Math.min(encoded.length, dest.length);
                        for (var i = 0; i < written; i++) dest[i] = encoded[i];
                        return { read: input.length, written: written };
                    };
                    globalThis.TextEncoder = TextEncoder;
                }

                if (typeof globalThis.TextDecoder === 'undefined') {
                    function resolveCharset(label) {
                        var l = String(label || 'utf-8').toLowerCase();
                        if (l === 'utf-8' || l === 'utf8' || l === 'unicode-1-1-utf-8') return JCharset.UTF_8;
                        if (l === 'utf-16le' || l === 'utf16le') return JCharset.UTF_16LE;
                        if (l === 'utf-16be' || l === 'utf16be') return JCharset.UTF_16BE;
                        if (l === 'latin1' || l === 'iso-8859-1' || l === 'windows-1252') return JCharset.ISO_8859_1;
                        if (l === 'ascii' || l === 'us-ascii') return JCharset.US_ASCII;
                        return JCharset.UTF_8;
                    }
                    function TextDecoder(label, options) {
                        this.encoding = String(label || 'utf-8').toLowerCase();
                        this._charset = resolveCharset(label);
                        this.fatal = !!(options && options.fatal);
                        this.ignoreBOM = !!(options && options.ignoreBOM);
                    }
                    TextDecoder.prototype.decode = function (input) {
                        if (input === undefined) return '';
                        var len = input.length;
                        var bytes = new JByteArray(len);
                        for (var i = 0; i < len; i++) {
                            var v = input[i];
                            bytes[i] = v > 127 ? v - 256 : v;
                        }
                        return String(new JString(bytes, this._charset));
                    };
                    globalThis.TextDecoder = TextDecoder;
                }
            })();
        """.trimIndent()

        // `setTimeout`/`setInterval`/`setImmediate` and their `clear*` counterparts are
        // registered directly as host (Kotlin) `ProxyExecutable` globals in `init` above
        // (see `setTimeoutFn`/`setIntervalFn`/`setImmediateFn`/`clearTimerFn` and
        // `TimerManager`) rather than in a JS init script — GraalJS provides none of these
        // itself (they're a browser/Node runtime feature, not part of the ECMAScript
        // spec), and a prior JS-side implementation that spawned a dedicated
        // `java.lang.Thread` per timer to sleep for real wall-clock time before calling
        // back into the Context crashed with `IllegalStateException: Multi threaded
        // access` the moment two such threads' brief Context entries overlapped — GraalJS
        // only permits one thread inside a Context at a time. TimerManager instead runs a
        // conventional single-threaded event loop (find the next due timer, run it,
        // repeat) entirely on whichever thread already owns the Context.
        //
        // Only `queueMicrotask` remains a JS-side polyfill here: it's just sugar over
        // `Promise.resolve().then(...)`, unrelated to the timer/event-loop machinery.
        // avvio (fastify's plugin-loading/boot library) calls the bare global
        // `queueMicrotask(...)` unconditionally — with no feature-detection guard — from
        // several points in its plugin-queue-draining logic (see avvio/lib/plugin.js's
        // `check()`). Previously undefined here, so that call threw a bare `TypeError:
        // queueMicrotask is not a function` from deep inside a queue callback with no
        // surrounding `try`/`catch` anywhere on the call stack to report it — silently
        // aborting `fastify().listen()`'s plugin boot sequence partway through, which left
        // its returned promise forever unresolved instead of ever rejecting — see
        // NpmCompatibilityTest.fastifyListensAndCloses.
        private val QUEUE_MICROTASK_INIT_SCRIPT = """
            (function () {
                if (typeof globalThis.queueMicrotask === 'undefined') {
                    globalThis.queueMicrotask = function (fn) {
                        Promise.resolve().then(fn);
                    };
                }
            })();
        """.trimIndent()

        // Exposes `Buffer` as a bare global — distinct from (and in addition to) the
        // importable `buffer` core module (see coreModules / buffer.mjs), matching real
        // Node.js, which has exposed `Buffer` as a global since forever (no import needed).
        // Plenty of npm packages assume this unconditionally: e.g. express's dependency
        // `etag` references the bare global `Buffer.isBuffer`/`Buffer.byteLength` directly
        // rather than importing the `buffer` module, and throws `ReferenceError: Buffer is
        // not defined` on every request without this. Implemented by requiring the same
        // `buffer` core module through the existing `__mycelium_require__` bridge (wired up
        // just above) rather than duplicating buffer.mjs's implementation here, so there's
        // exactly one `Buffer` class either way `require`/`import` reaches it.
        private val GLOBAL_BUFFER_INIT_SCRIPT = """
            (function () {
                if (typeof globalThis.Buffer === 'undefined') {
                    globalThis.Buffer = globalThis.__mycelium_require__('buffer', '/').Buffer;
                }
            })();
        """.trimIndent()

        // Exposes `URL`/`URLSearchParams` as bare globals — GraalJS ships no such globals
        // itself (only reachable via the `url` core module), unlike Node.js and browsers,
        // which have exposed both as globals for years. Plenty of npm packages assume this
        // unconditionally: e.g. minimatch/path-scurry (glob's dependencies) construct `new
        // URL(...)` at module scope and throw `ReferenceError: URL is not defined` without
        // this. Implemented by requiring the same `url` core module through the existing
        // `__mycelium_require__` bridge (wired up just above) rather than duplicating
        // url.mjs's implementation here, so there's exactly one `URL`/`URLSearchParams`
        // class either way `require`/`import` reaches it.
        private val GLOBAL_URL_INIT_SCRIPT = """
            (function () {
                if (typeof globalThis.URL === 'undefined' || typeof globalThis.URLSearchParams === 'undefined') {
                    var urlModule = globalThis.__mycelium_require__('url', '/');
                    if (typeof globalThis.URL === 'undefined') {
                        globalThis.URL = urlModule.URL;
                    }
                    if (typeof globalThis.URLSearchParams === 'undefined') {
                        globalThis.URLSearchParams = urlModule.URLSearchParams;
                    }
                }
            })();
        """.trimIndent()
    }
}

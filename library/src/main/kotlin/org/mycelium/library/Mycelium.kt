package org.mycelium.library

import com.oracle.truffle.api.Truffle
import java.io.File
import java.nio.charset.StandardCharsets
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
import org.mycelium.library.registry.PackageInstallerRegistry
import org.mycelium.library.registry.PyPIClient
import org.mycelium.library.registry.RubyGemsClient
import kotlin.io.path.extension
import kotlin.io.path.readText

class Mycelium(
    requestedLanguages: List<LanguageSupport> = listOf(
        JsSupport, CoffeeSupport, CivetSupport, LiveScriptSupport, ElmSupport, PureScriptSupport, ReScriptSupport,
        GleamSupport, ImbaSupport,
        DartSupport, KotlinJsSupport, ScalaJsSupport,
        RubySupport, PythonSupport, CSupport, RustSupport, TinyGoSupport, LuaSupport
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
    val autoInstall: Boolean = true,
    // Daemon support: when non-null, the Context built below attaches to this shared
    // Engine (`.engine(engine)`) instead of getting its own implicit one — see
    // DaemonServer, which builds one Engine per process and passes it to every
    // per-request Mycelium instance so compiled code / warm-up cost is amortized across
    // requests while each request still gets an isolated Context (see `context` below).
    // Left null for the normal CLI "run and exit" path, where a single implicit Engine
    // tied to the one Context is exactly what's wanted.
    private val engine: Engine? = null,
    // Backs `process.exit(code)` (see PROCESS_INIT_SCRIPT) — defaults to actually
    // terminating the JVM, matching the CLI's existing behavior. The daemon overrides
    // this to throw ProcessExitException instead, so a guest script's `process.exit()`
    // ends only that one request rather than killing the whole long-running daemon
    // process (and every other client's in-flight/future request with it).
    private val exitHandler: (Int) -> Unit = { code -> System.exit(code) },
    // Per-request working directory / environment, threaded straight from the caller's own
    // process — see DaemonServer.handleRun, which resolves these from the wire protocol's
    // `run` request (App.kt/DaemonClient populate them from the *client* process's
    // `user.dir`/`System.getenv()` before sending, not the daemon's own). Left null for the
    // normal CLI "run and exit" path, where the JVM's own real cwd/env are already correct
    // and don't need overriding. When non-null, `cwd` is applied to the Context (via
    // `currentWorkingDirectory` below) *and* to InterceptingFileSystem's own resolution
    // fallbacks (see `filesystem.init`'s `cwd` param) — the FileSystem does its own
    // relative-path resolution rather than delegating to GraalVM's Context-level cwd, so
    // both need to agree. `env`, when non-null, replaces (not merges with) the Context's
    // visible environment (`allowEnvironmentAccess(NONE)` + `environment(env)`) and backs
    // `process.env` (see PROCESS_INIT_SCRIPT's `__mycelium_env__` binding below).
    private val cwd: String? = null,
    private val env: Map<String, String>? = null,
    // Path to a GraalVM Insight instrumentation script (`--insight=<path>` on the CLI) to
    // attach to the Context below. Left null for the normal path, where no instrumentation
    // is attached. A no-op if the insight-tool jar isn't on the classpath — see
    // `insightAvailable`.
    private val insightScript: String? = null,
    // When true (the CLI's `--operator-overloading` flag), enables GraalJS's experimental
    // operator overloading via the Context option `js.operator-overloading`. This is a
    // Context-level option and works fine with the daemon (doesn't need to bypass it).
    val operatorOverloading: Boolean = false,
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
    // The same throwaway probe also reports which *instruments* (as opposed to languages)
    // are on the classpath — used below to gate Insight activation the same way
    // `availableLanguageIds` gates language-specific Context options.
    private val probeEngine = Engine.create()
    private val availableLanguageIds: Set<String> = probeEngine.languages.keys
    private val insightAvailable = "insight" in probeEngine.instruments.keys

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
    // Not registered in packageInstallerRegistry: crates.io/the Go module proxy aren't
    // PackageEcosystem entries (see that enum's own doc comment) and aren't consulted by
    // any automatic bare-specifier resolution yet — see the class comment above.
    private val cratesIoInstaller = PackageInstaller(cratesIoClient)
    private val goProxyInstaller = PackageInstaller(goProxyClient)
    private val packageInstallerRegistry = PackageInstallerRegistry().apply {
        register(PackageEcosystem.NPM, packageInstaller)
        register(PackageEcosystem.RUBYGEMS, PackageInstaller(rubyGemsClient))
        register(PackageEcosystem.PYPI, PackageInstaller(pyPIClient))
    }

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
        // When attached to an explicit, potentially multi-Context Engine (daemon mode —
        // see `engine` above), GraalVM requires every permitted language to be declared
        // up front on the Context itself rather than allowed lazily (`Context.newBuilder()`
        // with no arguments): languages that don't support context-independent sharing
        // (Ruby is the one that actually hits this — see the class-level comment on
        // `availableLanguageIds`) refuse to lazily initialize against a shared Engine and
        // throw a PolyglotException instead. Passing the permitted IDs explicitly is a
        // no-op for the normal (unshared, implicit-Engine) CLI path, so it's done
        // unconditionally rather than only when `engine` is non-null.
        val permittedLanguageIds = languages.map { it.languageId }.distinct().toTypedArray()
        val builder = Context.newBuilder(*permittedLanguageIds)
            .allowAllAccess(true)
            .allowIO(IOAccess.newBuilder().fileSystem(filesystem).build())
            // Bind the Context's out/err explicitly to whatever `System.out`/`System.err`
            // currently are, evaluated at Context-construction time (i.e. per-`Mycelium`
            // instance, per-request in daemon mode) rather than left on GraalVM's own
            // default. GraalJS's built-in `console.log`/`console.error` write through the
            // Context's configured out()/err() streams, not through a live lookup of
            // `System.out`/`System.err` at print time — so DaemonServer's per-request
            // `System.setOut`/`setErr` swap (see its own doc comment) would otherwise be
            // invisible to `console.log` even though it *does* reach
            // PROCESS_INIT_SCRIPT's `process.stdout.write`, which calls `JSystem.out`
            // directly on every write. Explicit here removes that asymmetry, and is a
            // no-op for the CLI path (System.out/err there are the real process streams
            // for the whole run anyway).
            .out(System.out)
            .err(System.err)
        if (engine != null) {
            builder.engine(engine)
        }
        // Per-Context cwd — see the `cwd` constructor param's own doc comment. A no-op for
        // the normal CLI path (cwd left null there), where the Context's default (the
        // JVM's own real working directory) is already exactly right.
        if (cwd != null) {
            builder.currentWorkingDirectory(File(cwd).toPath())
        }
        // Per-Context environment — replaces (rather than merges with) whatever the
        // Context would otherwise see: EnvironmentAccess.NONE means *no* environment
        // variable is visible except the ones explicitly listed in `environment(env)`
        // below, so a daemon request's caller-supplied env is the complete picture rather
        // than a set of overrides layered on the daemon process's own env (which would
        // leak the daemon's environment into the guest script otherwise). A no-op for the
        // normal CLI path (env left null there), where EnvironmentAccess defaults to
        // INHERIT and the guest already sees this process's real environment.
        if (env != null) {
            builder.allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
            builder.environment(env)
        }
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
                // gated on "wasm" being permitted on *this* Context (`permittedLanguageIds`,
                // derived from the caller's requested `languages` — see above), not merely
                // available on the classpath (`availableLanguageIds`): now that the Context
                // is built with an explicit permitted-language list (needed for engine
                // sharing — see the comment above `permittedLanguageIds`), turning this on
                // when "wasm" wasn't actually requested (e.g. a composable subset built
                // without TinyGoSupport, whose languageId is "wasm") throws
                // IllegalStateException out of JSRealm's constructor the moment the JS
                // bindings are first touched, since GraalJS's WebAssembly API requires the
                // "wasm" language to be both available *and* permitted so it can delegate to
                // it internally.
                .option("js.webassembly", ("wasm" in permittedLanguageIds).toString())
                // Enables GraalJS's experimental operator overloading, allowing custom
                // operator implementations via the Operators API (`--operator-overloading`
                // CLI flag, or Mycelium's `operatorOverloading` constructor param).
                .option("js.operator-overloading", operatorOverloading.toString())
        }
        builder.build()
    }

    // GraalVM Insight — only activated when the insight-tool jar is on the classpath
    // (`insightAvailable`) and the user actually passed `--insight=<script>`
    // (`insightScript`). Unlike ordinary `js.*`/engine options, Insight cannot be turned on
    // via `Context.Builder#option("insight", path)` in newer GraalVM versions — that form
    // silently fails to attach the instrumentation. Instead the Instrument must be looked
    // up on the already-built Context's Engine and activated via its `Function<Source,
    // AutoCloseable>` lookup API, which is why this happens *after* `context` above rather
    // than inside the builder block. The returned AutoCloseable (kept alive for the
    // lifetime of this Mycelium instance) detaches the instrumentation when closed; nothing
    // currently closes it explicitly, matching the rest of this class's lifecycle (the
    // Context itself is likewise never explicitly closed on the normal CLI path).
    private val insightHandle: AutoCloseable? = if (insightScript != null && insightAvailable) {
        val instrument = context.engine.instruments["insight"]
        @Suppress("UNCHECKED_CAST")
        val access = instrument?.lookup(java.util.function.Function::class.java)
            as? java.util.function.Function<Source, AutoCloseable>
        access?.apply(Source.newBuilder("js", File(insightScript)).build())
    } else null

    private val extensionMap: Map<String, LanguageSupport> =
        languages.flatMap { lang -> lang.extensions.map { it to lang } }.toMap()

    private val evalModuleFn = org.graalvm.polyglot.proxy.ProxyExecutable { args ->
        val lang = args[0].asString()
        val code = args[1].asString()
        evalModule(lang, code)
    }

    // Requiring the same URL twice must only execute it once (Kernel#require's own
    // contract — the second call returns false and does nothing) — separate from
    // HttpImportCache's own disk cache, which only avoids re-fetching, not re-evaluating.
    private val httpRequiredUrls = mutableSetOf<String>()

    // Generates the ESM-style shim InterceptingFileSystem.newByteChannel hands back to
    // GraalJS for a cross-language import — reused here for the same purpose, except
    // httpRequireFn needs the shim actually *evaluated* into the host language's top-level
    // scope (Kernel#require has no return-a-module-object protocol to hand it back through),
    // rather than returned as file bytes for the engine's own import machinery to consume.
    private val shimGenerator = ShimGenerator { context }

    private fun evalCrossLanguageShim(hostLang: LanguageSupport, value: Value, path: String) {
        val channel = shimGenerator.generateCrossLanguageShim(hostLang, value, path) as ReadOnlyBufferChannel
        val shimSource = StandardCharsets.UTF_8.decode(channel.buffer.duplicate()).toString()
        context.eval(Source.newBuilder(hostLang.languageId, shimSource, path).build())
    }

    // Backs RubySupport.injectHttpRequire's `Kernel#require` monkeypatch — see that
    // method's own doc comment for why Ruby needs this rather than routing http(s)
    // `require` targets through FileSystem.parsePath the way JS's `import` does.
    //
    // Mirrors the cross-language logic InterceptingFileSystem.newByteChannel applies to
    // ordinary (filesystem) cross-language imports: detect the target language from the
    // URL's extension, and if it differs from the requiring (host) language, eval it in its
    // own language and splice the result into the host language via evalCrossLanguageShim
    // instead of naively eval'ing foreign-language source as the host language.
    private val httpRequireFn = org.graalvm.polyglot.proxy.ProxyExecutable { args ->
        val url = args[0].asString()
        if (!httpRequiredUrls.add(url)) {
            false
        } else {
            val cachedPath = try {
                HttpImportCache.fetch(java.net.URI(url))
            } catch (e: java.io.IOException) {
                throw MyceliumException("mycelium: failed to fetch '$url' — ${e.message}", e)
            }
            // Only Ruby wires injectHttpRequire up today (see its own doc comment above), so
            // this should always resolve via detectFrame's "Kernel#require" match — the
            // RubySupport fallback below is a defensive default for any future language that
            // adopts the same hook without a distinctive require-frame of its own.
            val frames = mutableListOf<String>()
            Truffle.getRuntime().iterateFrames { frame ->
                frames.add("${frame.callTarget}")
                null
            }
            val hostLang = detectHostLanguage(frames) ?: RubySupport
            val targetLang = supportForExtension(cachedPath.extension)
            when {
                targetLang != null && targetLang.needsCompilation -> {
                    val compiled = Compiler.compile(cachedPath, targetLang)
                    val value = if (targetLang.outputExtension == "wasm") {
                        Compiler.loadWasmExports(context, compiled)
                    } else {
                        context.eval(Source.newBuilder("llvm", compiled.toFile()).build())
                    }
                    evalCrossLanguageShim(hostLang, value, url)
                }
                targetLang != null && targetLang.jsCompilerId != null -> {
                    val js = compileToJsDispatcher.compile(targetLang.jsCompilerId!!, cachedPath, context)
                    val value = context.eval(Source.newBuilder("js", js, url).build())
                    evalCrossLanguageShim(hostLang, value, url)
                }
                targetLang == null || targetLang.languageId == hostLang.languageId -> {
                    context.eval(Source.newBuilder(hostLang.languageId, cachedPath.readText(), url).build())
                }
                else -> {
                    val source = Source.newBuilder(targetLang.languageId, cachedPath.readText(), url).build()
                    evalCrossLanguageShim(hostLang, context.eval(source), url)
                }
            }
            true
        }
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

    // Backs `process.exit(code)` in PROCESS_INIT_SCRIPT — routed through the
    // constructor-supplied `exitHandler` rather than calling `System.exit` directly here,
    // so daemon mode can intercept it (see `exitHandler`'s own doc comment above).
    private val processExitFn = org.graalvm.polyglot.proxy.ProxyExecutable { args ->
        val code = if (args.isNotEmpty() && args[0].isNumber) args[0].asInt() else 0
        exitHandler(code)
        null
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
        filesystem.init(
            context, this, compileToJsDispatcher, packageInstallerRegistry, yolo,
            cwd = cwd?.let { File(it).toPath() },
        )
        for (lang in languages) {
            lang.injectEvalModule(context, evalModuleFn)
            lang.injectHttpRequire(context, httpRequireFn)
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
            context.getBindings("js").putMember("__mycelium_process_exit__", processExitFn)
            // setTimeout/setInterval/setImmediate and their clear* counterparts are
            // implemented entirely host-side (TimerManager) rather than in
            // TIMERS_INIT_SCRIPT's JS — see TimerManager's doc comment.
            context.getBindings("js").putMember("setTimeout", setTimeoutFn)
            context.getBindings("js").putMember("clearTimeout", clearTimerFn)
            context.getBindings("js").putMember("setInterval", setIntervalFn)
            context.getBindings("js").putMember("clearInterval", clearTimerFn)
            context.getBindings("js").putMember("setImmediate", setImmediateFn)
            context.getBindings("js").putMember("clearImmediate", clearTimerFn)
            // Backs PROCESS_INIT_SCRIPT's `process.cwd()`/`process.env` — only set when
            // this Mycelium instance was actually given an explicit cwd/env (daemon mode;
            // see the constructor params' own doc comment). PROCESS_INIT_SCRIPT falls back
            // to the real `java.lang.System` cwd/env when these bindings are absent, so the
            // normal CLI path (cwd/env left null) is unaffected.
            if (cwd != null) {
                context.getBindings("js").putMember("__mycelium_cwd__", cwd)
            }
            if (env != null) {
                context.getBindings("js").putMember("__mycelium_env__", env)
            }
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
        // Normalize "node:"-prefixed core-module imports the same way InterceptingFileSystem
        // does for every other .js/.mjs file it reads (see
        // CoreModuleRegistry.normalizeCoreModuleSpecifiers) — the entry file's own source is
        // read directly here rather than through the FileSystem (GraalJS is handed a Source
        // built from this string, not a file path it resolves itself), so it would otherwise
        // be the one file in the whole program whose own "node:events"-style imports aren't
        // normalized, defeating the point when the entry file itself does exactly that.
        val rawCode = file.readText()
        val code = if (language == "js") CoreModuleRegistry.normalizeCoreModuleSpecifiers(rawCode) else rawCode
        val source = Source.newBuilder(language, code, path).build()
        val dir = file.absoluteFile.parentFile.toPath()
        // Bun-compat globals (the `Bun` object — see BUN_INIT_SCRIPT) are injected only
        // when this specific entry file/project looks like Bun code, layered on top of the
        // Node-compat globals injected unconditionally in `init` above — see
        // BunRuntime.detect's own doc comment for why Node-compat itself stays
        // unconditional rather than being made part of the same conditional.
        if (language == "js") {
            maybeInjectBunGlobals(dir.toFile(), code, path)
        }
        filesystem.withSourceDirectory(dir) { context.eval(source) }
        // Mirrors Node: the process (here, this call) stays alive only as long as a ref'd
        // timer/handle remains pending — evaluating the entry file above may have
        // registered `setTimeout`/`setInterval` callbacks via the Kotlin-backed globals,
        // which this drives to completion on the same (calling) thread that owns the
        // Context. A script that registered no timers (or only unref'd ones) returns
        // immediately, same as before this loop existed.
        //
        // Gated on the *entry file's* resolved language being "js" — not merely on
        // `jsAvailable` — because setTimeout/setInterval/setImmediate are a JS/Node
        // runtime concept, not a universal one: `language` here is "js" for both plain
        // JS/TS and every compile-to-JS language (Coffee/Civet/LiveScript/Elm/PureScript/
        // ReScript/Dart/Kotlin-JS/Scala.js/Gleam/Imba all delegate their `languageId` to
        // JsSupport — see Languages.kt), since their compiled output ultimately runs as an
        // ordinary JS module on this same Context and may itself have scheduled timers.
        // Ruby/Python/C/Rust (`llvm`)/Go (`wasm`) run to completion on their own and have
        // no timer/event-loop concept at all — draining TimerManager for one of those
        // would be pumping a queue nothing could ever have populated, so it's skipped
        // entirely rather than running (harmlessly, but pointlessly) as a no-op.
        if (language == "js") {
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
        // Same "node:"-prefixed core-module import normalization as runFile — see that
        // method's own comment for why the entry source needs it applied explicitly rather
        // than relying on InterceptingFileSystem (this Source is also built directly from a
        // string, not resolved as a file through the FileSystem).
        val normalizedCode = if (languageId == "js") CoreModuleRegistry.normalizeCoreModuleSpecifiers(code) else code
        tmpFile.writeText(normalizedCode)
        // Same conditional Bun-globals injection as runFile — see that method's own
        // comment and BunRuntime.detect's doc comment. No entry-file path is available
        // here (the source is an in-memory string, not a file the caller named), so the
        // project-directory signal check falls back to this Mycelium instance's own `cwd`
        // (or the JVM's real working directory when `cwd` is null, matching every other
        // cwd fallback in this class).
        if (languageId == "js") {
            maybeInjectBunGlobals(cwd?.let { File(it) }, normalizedCode, null)
        }
        val source = Source.newBuilder(languageId, tmpFile).build()
        return context.eval(source)
    }

    // Shared by runFile/evalModule — see their own comments. Evaluates BUN_INIT_SCRIPT
    // (idempotent: it no-ops if `globalThis.Bun` already exists) only when BunRuntime.detect
    // says this particular script/project looks like Bun code. `scriptPath`, when known
    // (runFile has one; evalModule's in-memory module body doesn't), backs Bun.argv's
    // second element the same way real Bun's argv[1] is the script path — see
    // BUN_INIT_SCRIPT's own comment for why argv can't do much better than this without
    // Mycelium threading real CLI args through to guest code at all (it currently doesn't;
    // process.argv is `[]` unconditionally for the same reason, see PROCESS_INIT_SCRIPT).
    private fun maybeInjectBunGlobals(projectDir: File?, source: String, scriptPath: String?) {
        if (!jsAvailable) return
        if (!BunRuntime.detect(projectDir, source)) return
        context.getBindings("js").putMember("__mycelium_bun_script_path__", scriptPath ?: "")
        context.eval("js", BUN_INIT_SCRIPT)
    }

    fun getBindings(languageId: String): org.graalvm.polyglot.Value =
        context.getBindings(languageId)

    private fun moduleExtensionForLanguage(languageId: String): String =
        languages.firstOrNull { it.languageId == languageId }?.moduleExtension ?: ".tmp"

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

                // Prefers the per-request `__mycelium_env__` binding (daemon mode — see
                // Mycelium's `env` constructor param) over the real JVM process
                // environment, so a daemon-served request sees the *caller's* environment
                // rather than the long-running daemon process's own. `__mycelium_env__` is
                // itself a plain `java.util.Map<String,String>` (Kotlin's `Map` interface),
                // so it supports the same `.get`/`.containsKey`/`.keySet` calls below as
                // `JSystem.getenv()` already does.
                function getenvMap() {
                    return typeof globalThis.__mycelium_env__ !== 'undefined'
                        ? globalThis.__mycelium_env__
                        : JSystem.getenv();
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
                    // Prefers the per-request `__mycelium_cwd__` binding (daemon mode —
                    // see Mycelium's `cwd` constructor param) over the JVM process's own
                    // `user.dir` system property, so a daemon-served request reports the
                    // *caller's* working directory rather than the daemon's.
                    cwd: function () {
                        return typeof globalThis.__mycelium_cwd__ !== 'undefined'
                            ? String(globalThis.__mycelium_cwd__)
                            : String(JSystem.getProperty('user.dir'));
                    },
                    chdir: function (dir) { /* not really possible in JVM */ },
                    exit: function (code) { globalThis.__mycelium_process_exit__(code || 0); },
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

        // Bun-compat globals — the `Bun` object real Bun scripts assume exists ambiently,
        // matching the same "assume npm/Bun packages reach for a bare global" rationale as
        // PROCESS_INIT_SCRIPT/GLOBAL_BUFFER_INIT_SCRIPT/GLOBAL_URL_INIT_SCRIPT above, except
        // this one is injected *conditionally* — only when Mycelium.maybeInjectBunGlobals
        // decides (via BunRuntime.detect) that the script being run/imported looks like Bun
        // code, never unconditionally like the Node-compat globals. Idempotent (bails out
        // immediately if `globalThis.Bun` already exists) so it's safe to eval on every
        // Bun-detected runFile/evalModule call without re-defining classes each time.
        //
        // Deliberately a partial slice of Bun's real API surface — scope was fixed by the
        // task this was implemented under, and explicitly excludes (not started, not
        // stubbed, just absent): `Bun.dlopen`/`bun:ffi` (would need a JVM-side foreign-
        // function mechanism — Java's Foreign Function & Memory API or JNI — unrelated to
        // Node-addon N-API, which is a different thing entirely and does not block this),
        // `Bun.sqlite`/`bun:sqlite` (would need a JVM SQLite binding, e.g. sqlite-jdbc, as a
        // new dependency — same open question TODO.md already has for Node's own
        // `node:sqlite`), `Bun.write`, `Bun.spawn`/`Bun.spawnSync`, `Bun.$` (shell), `Bun.hash`,
        // `Bun.password`, `Bun.build`, `Bun.Transpiler`, `bun:test`, and WebSocket upgrade
        // support in `Bun.serve` — see docs/reference/bun-compat.md for the authoritative,
        // checked-against-upstream list of what is/isn't covered.
        //
        // Golden values below (MIME-type guesses, the non-Response-return placeholder body,
        // the default-error-response body, ENOENT error shape, Bun.argv's shape) were
        // obtained by running a real `bun` 1.3.13 binary (via `nix develop -c bun run
        // <script>`, this repo's flake.nix devShell) against small probe scripts during
        // development — not assumed from memory. See BunCompatTest.kt for the equivalent
        // assertions and a note on which behaviors could/couldn't be cross-checked this way.
        private val BUN_INIT_SCRIPT = """
            (function () {
                if (typeof globalThis.Bun !== 'undefined') return;

                var JFiles = Java.type('java.nio.file.Files');
                var JPaths = Java.type('java.nio.file.Paths');
                var JString = Java.type('java.lang.String');
                var JCharset = Java.type('java.nio.charset.StandardCharsets');
                var JByteArray = Java.type('byte[]');
                var JHttpServer = Java.type('com.sun.net.httpserver.HttpServer');
                var JInetSocketAddress = Java.type('java.net.InetSocketAddress');
                var JProcessHandle = Java.type('java.lang.ProcessHandle');

                function toJByteArray(str) {
                    var buf = JCharset.UTF_8.encode(String(str));
                    var len = buf.remaining();
                    var bytes = new JByteArray(len);
                    buf.get(bytes);
                    return bytes;
                }

                function readAllBytes(inputStream) {
                    if (inputStream === null) return new JByteArray(0);
                    var JByteArrayOutputStream = Java.type('java.io.ByteArrayOutputStream');
                    var baos = new JByteArrayOutputStream();
                    var chunk = new JByteArray(8192);
                    var n;
                    while ((n = inputStream.read(chunk, 0, chunk.length)) !== -1) {
                        baos.write(chunk, 0, n);
                    }
                    inputStream.close();
                    return baos.toByteArray();
                }

                // Generated by tooling/generate-bun-mime-table.mjs against real `bun` 1.3.13
                // (this repo's flake.nix-pinned version, generated 2026-07-27), 454 entries.
                // That script probes a real bun process in a SINGLE invocation (no stat is
                // needed for `.type`, so no files actually have to exist — see the "no stat is
                // performed" comment on BunFile.type below) via three passes: (1) EXHAUSTIVE
                // 1-2 letter extensions (all 702 lowercase combinations — this is what closes
                // the exact gap that motivated expanding this table: short registered
                // extensions like ".c"/".h"/".t"/".s"/".f" that a hand-picked list is liable
                // to miss one-by-one); (2) EXHAUSTIVE 3-letter extensions (17,576
                // combinations) MINUS anything real bun maps to a vendor-specific
                // ("vnd."-tagged) MIME type — ~338 obscure proprietary formats excluded, a
                // mechanical rather than hand-picked cutoff; (3) a CURATED corpus of 4+
                // character extensions across text/image/audio/video/application/font
                // categories plus — since Mycelium is a polyglot runtime — a deliberately
                // broad source-code list (.js/.ts/.py/.rb/.go/.rs/.java/.kt/.swift/.lua/...).
                // Pass 3 is NOT exhaustive (bun's full mime-db runs into the thousands of
                // entries across all lengths and would bloat this init script far past what's
                // practical to inline) — every 1-3 character extension here is authoritative
                // against bun 1.3.13, and 4+ character coverage is "every extension a real
                // user would plausibly hit," not the full mime-db. Do NOT hand-edit entries —
                // regenerate via `bun run tooling/generate-bun-mime-table.mjs` (adding new
                // extensions to that script's CURATED_EXTENSIONS first if needed) and paste
                // its output here wholesale. See that script's own header comment for the full
                // per-pass rationale, and BunFuzzTest.kt's bunFileMimeTypeGuessLiveDiffFuzz for
                // how the (small, documented) residual gap beyond this table's scope is
                // exercised against live bun rather than hidden.
                var MIME_TYPES = {
                    '3g2': 'video/3gpp2',
                    '3gp': 'video/3gpp',
                    '7z': 'application/x-7z-compressed',
                    'aab': 'application/x-authorware-bin',
                    'aac': 'audio/x-aac',
                    'aam': 'application/x-authorware-map',
                    'aas': 'application/x-authorware-seg',
                    'abw': 'application/x-abiword',
                    'ac': 'application/vnd.nokia.n-gage.ac+xml',
                    'ace': 'application/x-ace-compressed',
                    'adp': 'audio/adpcm',
                    'afm': 'application/x-font-type1',
                    'ai': 'application/postscript',
                    'aif': 'audio/x-aiff',
                    'aiff': 'audio/x-aiff',
                    'amr': 'audio/amr',
                    'apk': 'application/vnd.android.package-archive',
                    'arc': 'application/x-freearc',
                    'arj': 'application/x-arj',
                    'asc': 'application/pgp-signature',
                    'asf': 'video/x-ms-asf',
                    'asm': 'text/x-asm',
                    'asx': 'video/x-ms-asf',
                    'atom': 'application/atom+xml',
                    'au': 'audio/basic',
                    'avi': 'video/x-msvideo',
                    'avif': 'image/avif',
                    'aw': 'application/applixware',
                    'bat': 'application/x-msdownload',
                    'bdf': 'application/x-font-bdf',
                    'blb': 'application/x-blorb',
                    'bmp': 'image/x-ms-bmp',
                    'boz': 'application/x-bzip2',
                    'bz': 'application/x-bzip',
                    'bz2': 'application/x-bzip2',
                    'c': 'text/x-c',
                    'caf': 'audio/x-caf',
                    'cba': 'application/x-cbr',
                    'cbr': 'application/x-cbr',
                    'cbt': 'application/x-cbr',
                    'cbz': 'application/x-cbr',
                    'cc': 'text/x-c',
                    'cco': 'application/x-cocoa',
                    'cct': 'application/x-director',
                    'cdf': 'application/x-netcdf',
                    'cdx': 'chemical/x-cdx',
                    'cer': 'application/pkix-cert',
                    'cfs': 'application/x-cfs-compressed',
                    'cgm': 'image/cgm',
                    'cif': 'chemical/x-cif',
                    'cjs': 'text/javascript;charset=utf-8',
                    'class': 'application/java-vm',
                    'clp': 'application/x-msclip',
                    'cml': 'chemical/x-cml',
                    'cmx': 'image/x-cmx',
                    'coffee': 'text/coffeescript',
                    'com': 'application/x-msdownload',
                    'conf': 'text/plain;charset=utf-8',
                    'cpl': 'application/cpl+xml',
                    'cpp': 'text/x-c',
                    'cpt': 'application/mac-compactpro',
                    'crd': 'application/x-mscardfile',
                    'crl': 'application/pkix-crl',
                    'crt': 'application/x-x509-ca-cert',
                    'crx': 'application/x-chrome-extension',
                    'csh': 'application/x-csh',
                    'css': 'text/css;charset=utf-8',
                    'cst': 'application/x-director',
                    'csv': 'text/csv',
                    'cts': 'text/javascript;charset=utf-8',
                    'cu': 'application/cu-seeme',
                    'cww': 'application/prs.cww',
                    'cxt': 'application/x-director',
                    'cxx': 'text/x-c',
                    'dart': 'application/vnd.dart',
                    'dbk': 'application/docbook+xml',
                    'dcr': 'application/x-director',
                    'deb': 'application/x-debian-package',
                    'def': 'text/plain;charset=utf-8',
                    'der': 'application/x-x509-ca-cert',
                    'dgc': 'application/x-dgc-compressed',
                    'dic': 'text/x-c',
                    'dir': 'application/x-director',
                    'dll': 'application/x-msdownload',
                    'dmg': 'application/x-apple-diskimage',
                    'doc': 'application/msword',
                    'docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
                    'dot': 'application/msword',
                    'dp': 'application/vnd.osgi.dp',
                    'dsc': 'text/prs.lines.tag',
                    'dtb': 'application/x-dtbook+xml',
                    'dtd': 'application/xml-dtd',
                    'dvi': 'application/x-dvi',
                    'dwd': 'application/atsc-dwd+xml',
                    'dxr': 'application/x-director',
                    'ear': 'application/java-archive',
                    'emf': 'image/emf',
                    'eml': 'message/rfc822',
                    'emz': 'application/x-msmetafile',
                    'eot': 'application/vnd.ms-fontobject',
                    'eps': 'application/postscript',
                    'epub': 'application/epub+zip',
                    'es': 'application/ecmascript',
                    'etx': 'text/x-setext',
                    'eva': 'application/x-eva',
                    'evy': 'application/x-envoy',
                    'exe': 'application/x-msdownload',
                    'exi': 'application/exi',
                    'exp': 'application/express',
                    'exr': 'image/aces',
                    'ez': 'application/andrew-inset',
                    'f': 'text/x-fortran',
                    'f90': 'text/x-fortran',
                    'fdt': 'application/fdt+xml',
                    'fgd': 'application/x-director',
                    'fh': 'image/x-freehand',
                    'fhc': 'image/x-freehand',
                    'fig': 'application/x-xfig',
                    'flac': 'audio/x-flac',
                    'fli': 'video/x-fli',
                    'flv': 'video/x-flv',
                    'fm': 'application/vnd.framemaker',
                    'fo': 'application/vnd.software602.filler.form+xml',
                    'for': 'text/x-fortran',
                    'gam': 'application/x-tads',
                    'gbr': 'application/rpki-ghostbusters',
                    'gca': 'application/x-gca-compressed',
                    'geojson': 'application/geo+json',
                    'gif': 'image/gif',
                    'glb': 'model/gltf-binary',
                    'gml': 'application/gml+xml',
                    'gpx': 'application/gpx+xml',
                    'gsf': 'application/x-font-ghostscript',
                    'gv': 'text/vnd.graphviz',
                    'gxf': 'application/gxf',
                    'gz': 'application/gzip',
                    'h': 'text/x-c',
                    'hbs': 'text/x-handlebars-template',
                    'hdd': 'application/x-virtualbox-hdd',
                    'hdf': 'application/x-hdf',
                    'heic': 'image/heic',
                    'heif': 'image/heif',
                    'hh': 'text/x-c',
                    'hlp': 'application/winhlp',
                    'hqx': 'application/mac-binhex40',
                    'htc': 'text/x-component',
                    'htm': 'text/html;charset=utf-8',
                    'html': 'text/html;charset=utf-8',
                    'ice': 'x-conference/x-cooltalk',
                    'ico': 'image/x-icon',
                    'ics': 'text/calendar',
                    'ief': 'image/ief',
                    'ifb': 'text/calendar',
                    'igs': 'model/iges',
                    'in': 'text/plain;charset=utf-8',
                    'ini': 'text/plain;charset=utf-8',
                    'ink': 'application/inkml+xml',
                    'iso': 'application/x-iso9660-image',
                    'its': 'application/its+xml',
                    'jar': 'application/java-archive',
                    'java': 'text/x-java-source',
                    'jhc': 'image/jphc',
                    'jls': 'image/jls',
                    'jng': 'image/x-jng',
                    'jpe': 'image/jpeg',
                    'jpeg': 'image/jpeg',
                    'jpf': 'image/jpx',
                    'jpg': 'image/jpeg',
                    'jph': 'image/jph',
                    'jpm': 'video/jpm',
                    'jpx': 'image/jpx',
                    'js': 'text/javascript;charset=utf-8',
                    'json': 'application/json;charset=utf-8',
                    'json5': 'application/json5',
                    'jsx': 'text/javascript;charset=utf-8',
                    'jxr': 'image/jxr',
                    'jxs': 'image/jxs',
                    'kar': 'audio/midi',
                    'key': 'application/x-iwork-keynote-sffkey',
                    'ktx': 'image/ktx',
                    'lgr': 'application/lgr+xml',
                    'lha': 'application/x-lzh-compressed',
                    'litcoffee': 'text/coffeescript',
                    'lnk': 'application/x-ms-shortcut',
                    'log': 'text/plain;charset=utf-8',
                    'lua': 'text/x-lua',
                    'lzh': 'application/x-lzh-compressed',
                    'm3u8': 'application/vnd.apple.mpegurl',
                    'm4a': 'audio/x-m4a',
                    'm4v': 'video/x-m4v',
                    'ma': 'application/mathematica',
                    'man': 'text/troff',
                    'map': 'application/json;charset=utf-8',
                    'markdown': 'text/markdown',
                    'mb': 'application/mathematica',
                    'md': 'text/markdown',
                    'mdb': 'application/x-msaccess',
                    'mdx': 'text/mdx',
                    'me': 'text/troff',
                    'mft': 'application/rpki-manifest',
                    'mid': 'audio/midi',
                    'midi': 'audio/midi',
                    'mie': 'application/x-mie',
                    'mjs': 'text/javascript;charset=utf-8',
                    'mka': 'audio/x-matroska',
                    'mkd': 'text/x-markdown',
                    'mks': 'video/x-matroska',
                    'mkv': 'video/x-matroska',
                    'mml': 'text/mathml',
                    'mng': 'video/x-mng',
                    'mny': 'application/x-msmoney',
                    'mobi': 'application/x-mobipocket-ebook',
                    'mov': 'video/quicktime',
                    'mp3': 'audio/mpeg',
                    'mp4': 'video/mp4',
                    'mpd': 'application/dash+xml',
                    'mpe': 'video/mpeg',
                    'mpeg': 'video/mpeg',
                    'mpf': 'application/media-policy-dataset+xml',
                    'mpg': 'video/mpeg',
                    'mrc': 'application/marc',
                    'ms': 'text/troff',
                    'msh': 'model/mesh',
                    'msi': 'application/x-msdownload',
                    'mtl': 'model/mtl',
                    'mts': 'text/javascript;charset=utf-8',
                    'mvb': 'application/x-msmediaview',
                    'mxf': 'application/mxf',
                    'n3': 'text/n3',
                    'nb': 'application/mathematica',
                    'nc': 'application/x-netcdf',
                    'ncx': 'application/x-dtbncx+xml',
                    'nfo': 'text/x-nfo',
                    'nq': 'application/n-quads',
                    'nsc': 'application/x-conference',
                    'nt': 'application/n-triples',
                    'nzb': 'application/x-nzb',
                    'obd': 'application/x-msbinder',
                    'obj': 'model/obj',
                    'oda': 'application/oda',
                    'odp': 'application/vnd.oasis.opendocument.presentation',
                    'ods': 'application/vnd.oasis.opendocument.spreadsheet',
                    'odt': 'application/vnd.oasis.opendocument.text',
                    'oga': 'audio/ogg',
                    'ogg': 'audio/ogg',
                    'ogv': 'video/ogg',
                    'ogx': 'application/ogg',
                    'opf': 'application/oebps-package+xml',
                    'opus': 'audio/ogg',
                    'org': 'text/x-org',
                    'otf': 'font/otf',
                    'ova': 'application/x-virtualbox-ova',
                    'ovf': 'application/x-virtualbox-ovf',
                    'owl': 'application/rdf+xml',
                    'p': 'text/x-pascal',
                    'pac': 'application/x-ns-proxy-autoconfig',
                    'pas': 'text/x-pascal',
                    'pbm': 'image/x-portable-bitmap',
                    'pcf': 'application/x-font-pcf',
                    'pct': 'image/x-pict',
                    'pcx': 'image/x-pcx',
                    'pdb': 'application/x-pilot',
                    'pde': 'text/x-processing',
                    'pdf': 'application/pdf',
                    'pem': 'application/x-x509-ca-cert',
                    'pfa': 'application/x-font-type1',
                    'pfb': 'application/x-font-type1',
                    'pfm': 'application/x-font-type1',
                    'pfr': 'application/font-tdpfr',
                    'pfx': 'application/x-pkcs12',
                    'pgm': 'image/x-portable-graymap',
                    'pgn': 'application/x-chess-pgn',
                    'pgp': 'application/pgp-encrypted',
                    'php': 'application/x-httpd-php',
                    'pic': 'image/x-pict',
                    'pki': 'application/pkixcmp',
                    'pl': 'application/x-perl',
                    'pls': 'application/pls+xml',
                    'pm': 'application/x-perl',
                    'png': 'image/png',
                    'pnm': 'image/x-portable-anymap',
                    'pot': 'application/vnd.ms-powerpoint',
                    'ppm': 'image/x-portable-pixmap',
                    'ppt': 'application/vnd.ms-powerpoint',
                    'pptx': 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
                    'prc': 'model/prc',
                    'prf': 'application/pics-rules',
                    'ps': 'application/postscript',
                    'psd': 'image/vnd.adobe.photoshop',
                    'psf': 'application/x-font-linux-psf',
                    'pti': 'image/prs.pti',
                    'pub': 'application/x-mspublisher',
                    'qt': 'video/quicktime',
                    'ra': 'audio/x-realaudio',
                    'ram': 'audio/x-pn-realaudio',
                    'rar': 'application/x-rar-compressed',
                    'ras': 'image/x-cmu-raster',
                    'rdf': 'application/rdf+xml',
                    'res': 'application/x-dtbresource+xml',
                    'rgb': 'image/x-rgb',
                    'rif': 'application/reginfo+xml',
                    'ris': 'application/x-research-info-systems',
                    'rl': 'application/resource-lists+xml',
                    'rld': 'application/resource-lists-diff+xml',
                    'rm': 'application/vnd.rn-realmedia',
                    'rmi': 'audio/midi',
                    'rmp': 'audio/x-pn-realaudio-plugin',
                    'rnc': 'application/relax-ng-compact-syntax',
                    'rng': 'application/xml',
                    'roa': 'application/rpki-roa',
                    'rpm': 'application/x-redhat-package-manager',
                    'rq': 'application/sparql-query',
                    'rs': 'application/rls-services+xml',
                    'rsd': 'application/rsd+xml',
                    'rss': 'application/rss+xml',
                    'rtf': 'text/rtf',
                    'rtx': 'text/richtext',
                    'run': 'application/x-makeself',
                    's': 'text/x-asm',
                    'sc': 'application/vnd.ibm.secure-container',
                    'scd': 'application/x-msschedule',
                    'scm': 'application/vnd.lotus-screencam',
                    'scq': 'application/scvp-cv-request',
                    'scs': 'application/scvp-cv-response',
                    'sdp': 'application/sdp',
                    'sea': 'application/x-sea',
                    'ser': 'application/java-serialized-object',
                    'sfv': 'text/x-sfv',
                    'sgi': 'image/sgi',
                    'sgm': 'text/sgml',
                    'sh': 'application/x-sh',
                    'shf': 'application/shf+xml',
                    'sid': 'image/x-mrsid-image',
                    'sig': 'application/pgp-signature',
                    'sil': 'audio/silk',
                    'sit': 'application/x-stuffit',
                    'siv': 'application/sieve',
                    'slm': 'text/slim',
                    'sls': 'application/route-s-tsid+xml',
                    'sm': 'application/vnd.stepmania.stepchart',
                    'smi': 'application/smil+xml',
                    'smv': 'video/x-smv',
                    'snd': 'audio/basic',
                    'snf': 'application/x-font-snf',
                    'spc': 'application/x-pkcs7-certificates',
                    'spl': 'application/x-futuresplash',
                    'spp': 'application/scvp-vp-response',
                    'spq': 'application/scvp-vp-request',
                    'spx': 'audio/ogg',
                    'sql': 'application/x-sql',
                    'src': 'application/x-wais-source',
                    'srt': 'application/x-subrip',
                    'sru': 'application/sru+xml',
                    'srx': 'application/sparql-results+xml',
                    'st': 'application/vnd.sailingtracker.track',
                    'stk': 'application/hyperstudio',
                    'stl': 'model/stl',
                    'svg': 'image/svg+xml',
                    'svgz': 'image/svg+xml',
                    'swa': 'application/x-director',
                    'swf': 'application/x-shockwave-flash',
                    't': 'text/troff',
                    'tar': 'application/x-tar',
                    'tcl': 'application/x-tcl',
                    'td': 'application/urc-targetdesc+xml',
                    'tei': 'application/tei+xml',
                    'tex': 'application/x-tex',
                    'text': 'text/plain;charset=utf-8',
                    'tfi': 'application/thraud+xml',
                    'tfm': 'application/x-tex-tfm',
                    'tfx': 'image/tiff-fx',
                    'tga': 'image/x-tga',
                    'tif': 'image/tiff',
                    'tiff': 'image/tiff',
                    'tk': 'application/x-tcl',
                    'toml': 'application/toml',
                    'torrent': 'application/x-bittorrent',
                    'tr': 'text/troff',
                    'trm': 'application/x-msterminal',
                    'ts': 'text/javascript;charset=utf-8',
                    'tsd': 'application/timestamped-data',
                    'tsv': 'text/tab-separated-values',
                    'tsx': 'text/javascript;charset=utf-8',
                    'ttc': 'font/collection',
                    'ttf': 'font/ttf',
                    'ttl': 'text/turtle',
                    'txt': 'text/plain;charset=utf-8',
                    'ubj': 'application/ubjson',
                    'ulx': 'application/x-glulx',
                    'uri': 'text/uri-list',
                    'uu': 'text/x-uuencode',
                    'vcard': 'text/vcard',
                    'vcd': 'application/x-cdlink',
                    'vcf': 'text/x-vcard',
                    'vcs': 'text/x-vcalendar',
                    'vdi': 'application/x-virtualbox-vdi',
                    'vhd': 'application/x-virtualbox-vhd',
                    'vob': 'video/x-ms-vob',
                    'vox': 'application/x-authorware-bin',
                    'vtt': 'text/vtt',
                    'wad': 'application/x-doom',
                    'war': 'application/java-archive',
                    'wasm': 'application/wasm',
                    'wav': 'audio/x-wav',
                    'wax': 'audio/x-ms-wax',
                    'webm': 'video/webm',
                    'webmanifest': 'application/manifest+json',
                    'webp': 'image/webp',
                    'wg': 'application/vnd.pmi.widget',
                    'wgt': 'application/widget',
                    'wif': 'application/watcherinfo+xml',
                    'wm': 'video/x-ms-wm',
                    'wma': 'audio/x-ms-wma',
                    'wmd': 'application/x-ms-wmd',
                    'wmf': 'image/wmf',
                    'wmv': 'video/x-ms-wmv',
                    'wmx': 'video/x-ms-wmx',
                    'wmz': 'application/x-msmetafile',
                    'woff': 'font/woff',
                    'woff2': 'font/woff2',
                    'wri': 'application/x-mswrite',
                    'wrl': 'model/vrml',
                    'wvx': 'video/x-ms-wvx',
                    'xap': 'application/x-silverlight-app',
                    'xav': 'application/xcap-att+xml',
                    'xbm': 'image/x-xbitmap',
                    'xca': 'application/xcap-caps+xml',
                    'xcs': 'application/calendar+xml',
                    'xdf': 'application/xcap-diff+xml',
                    'xel': 'application/xcap-el+xml',
                    'xer': 'application/patch-ops-error+xml',
                    'xht': 'application/xhtml+xml',
                    'xlf': 'application/xliff+xml',
                    'xls': 'application/vnd.ms-excel',
                    'xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
                    'xm': 'audio/xm',
                    'xml': 'application/xml',
                    'xns': 'application/xcap-ns+xml',
                    'xo': 'application/vnd.olpc-sugar',
                    'xop': 'application/xop+xml',
                    'xpi': 'application/x-xpinstall',
                    'xpl': 'application/xproc+xml',
                    'xpm': 'image/x-xpixmap',
                    'xsd': 'application/xml',
                    'xsl': 'application/xslt+xml',
                    'xvm': 'application/xv+xml',
                    'xwd': 'image/x-xwindowdump',
                    'xyz': 'chemical/x-xyz',
                    'xz': 'application/x-xz',
                    'yaml': 'text/yaml',
                    'yin': 'application/yin+xml',
                    'yml': 'text/yaml',
                    'ymp': 'text/x-suse-ymp',
                    'zip': 'application/zip',
                };

                function guessMimeType(path) {
                    var idx = path.lastIndexOf('.');
                    if (idx === -1 || idx === path.length - 1) return 'application/octet-stream';
                    var ext = path.slice(idx + 1).toLowerCase();
                    return MIME_TYPES[ext] || 'application/octet-stream';
                }

                // Matches real Bun's ENOENT shape for a missing-file read (verified: `name`
                // 'Error', `code` 'ENOENT', message "ENOENT: no such file or directory, open
                // '<path>'") — same convention Node's own `fs` shims elsewhere in this
                // codebase already use for missing-file errors.
                function notFoundError(path) {
                    var err = new Error("ENOENT: no such file or directory, open '" + path + "'");
                    err.code = 'ENOENT';
                    err.errno = -2;
                    err.path = path;
                    err.syscall = 'open';
                    return err;
                }

                // --- Bun.file() ---

                function BunFile(path) {
                    this._path = String(path);
                }
                BunFile.prototype = {
                    get size() {
                        try {
                            return Number(JFiles.size(JPaths.get(this._path)));
                        } catch (e) {
                            return 0;
                        }
                    },
                    get type() {
                        // Real Bun infers `.type` from the path alone, even for a file that
                        // doesn't exist (verified: Bun.file('does-not-exist.json').type is
                        // still 'application/json;charset=utf-8') — no stat is performed here.
                        return guessMimeType(this._path);
                    },
                    exists: function () {
                        var p = this._path;
                        return Promise.resolve().then(function () {
                            return JFiles.exists(JPaths.get(p));
                        });
                    },
                    text: function () {
                        var p = this._path;
                        return Promise.resolve().then(function () {
                            var jp = JPaths.get(p);
                            if (!JFiles.exists(jp)) throw notFoundError(p);
                            var bytes = JFiles.readAllBytes(jp);
                            return String(new JString(bytes, JCharset.UTF_8));
                        });
                    },
                    arrayBuffer: function () {
                        var p = this._path;
                        return Promise.resolve().then(function () {
                            var jp = JPaths.get(p);
                            if (!JFiles.exists(jp)) throw notFoundError(p);
                            var bytes = JFiles.readAllBytes(jp);
                            var ab = new ArrayBuffer(bytes.length);
                            var view = new Uint8Array(ab);
                            for (var i = 0; i < bytes.length; i++) view[i] = bytes[i] & 0xFF;
                            return ab;
                        });
                    },
                    json: function () {
                        return this.text().then(function (t) { return JSON.parse(t); });
                    },
                };

                // --- Minimal Fetch-API-shaped Request/Response/Headers, just enough for
                // Bun.serve's fetch(req) => Response contract. Not exposed unconditionally
                // (only within this Bun-detected script), unlike a real browser/Bun/Deno
                // runtime, which exposes these as always-on globals — see BunRuntime.kt's
                // doc comment for why Bun's surface here stays scoped to detected-Bun
                // scripts rather than leaking into every JS Context.

                function MyceliumHeaders(init) {
                    this._map = new Map();
                    if (init) {
                        if (init instanceof MyceliumHeaders) {
                            init._map.forEach((function (v, k) { this._map.set(k, v); }).bind(this));
                        } else if (Array.isArray(init)) {
                            for (var i = 0; i < init.length; i++) this.set(init[i][0], init[i][1]);
                        } else {
                            for (var k in init) if (Object.prototype.hasOwnProperty.call(init, k)) this.set(k, init[k]);
                        }
                    }
                }
                MyceliumHeaders.prototype.set = function (name, value) {
                    this._map.set(String(name).toLowerCase(), String(value));
                };
                MyceliumHeaders.prototype.get = function (name) {
                    var v = this._map.get(String(name).toLowerCase());
                    return v === undefined ? null : v;
                };
                MyceliumHeaders.prototype.has = function (name) {
                    return this._map.has(String(name).toLowerCase());
                };
                MyceliumHeaders.prototype.delete = function (name) {
                    this._map.delete(String(name).toLowerCase());
                };
                MyceliumHeaders.prototype.append = function (name, value) {
                    var key = String(name).toLowerCase();
                    if (this._map.has(key)) {
                        this._map.set(key, this._map.get(key) + ', ' + value);
                    } else {
                        this._map.set(key, String(value));
                    }
                };
                MyceliumHeaders.prototype.forEach = function (cb) {
                    this._map.forEach(function (v, k) { cb(v, k); });
                };
                MyceliumHeaders.prototype.entries = function () { return this._map.entries(); };
                MyceliumHeaders.prototype[Symbol.iterator] = function () { return this._map.entries(); };

                function MyceliumRequest(url, init) {
                    init = init || {};
                    this.url = String(url);
                    this.method = String(init.method || 'GET').toUpperCase();
                    this.headers = init.headers instanceof MyceliumHeaders ? init.headers : new MyceliumHeaders(init.headers);
                    this._bodyText = init._bodyText !== undefined
                        ? init._bodyText
                        : (init.body === undefined || init.body === null ? '' : String(init.body));
                }
                MyceliumRequest.prototype.text = function () {
                    var self = this;
                    return Promise.resolve().then(function () { return self._bodyText; });
                };
                MyceliumRequest.prototype.json = function () {
                    return this.text().then(function (t) { return JSON.parse(t); });
                };
                MyceliumRequest.prototype.arrayBuffer = function () {
                    var self = this;
                    return Promise.resolve().then(function () {
                        var bytes = toJByteArray(self._bodyText);
                        var ab = new ArrayBuffer(bytes.length);
                        var view = new Uint8Array(ab);
                        for (var i = 0; i < bytes.length; i++) view[i] = bytes[i] & 0xFF;
                        return ab;
                    });
                };
                MyceliumRequest.prototype.clone = function () {
                    return new MyceliumRequest(this.url, {
                        method: this.method, headers: this.headers, _bodyText: this._bodyText,
                    });
                };

                function MyceliumResponse(body, init) {
                    init = init || {};
                    this.status = init.status !== undefined ? init.status : 200;
                    this.statusText = init.statusText !== undefined ? String(init.statusText) : '';
                    this.headers = init.headers instanceof MyceliumHeaders ? init.headers : new MyceliumHeaders(init.headers);
                    if (body === undefined || body === null) {
                        this._bodyText = '';
                    } else if (typeof body === 'string') {
                        this._bodyText = body;
                        // Matches real Bun/browser fetch default: a bare string body gets a
                        // text/plain content-type unless the caller already set one
                        // (verified: `new Response("...")`'s default content-type is
                        // 'text/plain;charset=utf-8').
                        if (!this.headers.has('content-type')) {
                            this.headers.set('content-type', 'text/plain;charset=utf-8');
                        }
                    } else {
                        this._bodyText = String(body);
                    }
                    this.ok = this.status >= 200 && this.status < 300;
                }
                MyceliumResponse.prototype.text = function () {
                    var self = this;
                    return Promise.resolve().then(function () { return self._bodyText; });
                };
                MyceliumResponse.prototype.json = function () {
                    return this.text().then(function (t) { return JSON.parse(t); });
                };

                if (typeof globalThis.Headers === 'undefined') globalThis.Headers = MyceliumHeaders;
                if (typeof globalThis.Request === 'undefined') globalThis.Request = MyceliumRequest;
                if (typeof globalThis.Response === 'undefined') globalThis.Response = MyceliumResponse;

                // --- Bun.serve() ---
                //
                // Backed by the same com.sun.net.httpserver.HttpServer primitive as the
                // node-stdlib http.mjs shim's `Server` class (see that file's own class-level
                // comment for the single-threaded-Context caveat this inherits: the JDK
                // server's own accept thread invokes the JS `fetch` handler, which works only
                // because GraalJS transfers Context ownership to whichever thread calls into
                // it, and no other thread may concurrently be inside the Context at the same
                // moment — true here the same way it's true for http.mjs).
                function bunServe(options) {
                    options = options || {};
                    var fetchHandler = options.fetch;
                    if (typeof fetchHandler !== 'function') {
                        throw new TypeError('Bun.serve() requires a "fetch" function');
                    }
                    var errorHandler = typeof options.error === 'function' ? options.error : null;
                    var host = options.hostname || '0.0.0.0';
                    var requestedPort = options.port !== undefined ? Number(options.port) : 3000;

                    var httpServer = JHttpServer.create(new JInetSocketAddress(host, requestedPort), 0);
                    var displayHost = host === '0.0.0.0' ? 'localhost' : host;

                    httpServer.createContext('/', function (exchange) {
                        function sendBytes(status, bytes, headers) {
                            try {
                                var respHeaders = exchange.getResponseHeaders();
                                if (headers) headers.forEach(function (v, k) { respHeaders.set(k, v); });
                                exchange.sendResponseHeaders(status, bytes.length);
                                if (bytes.length > 0) {
                                    var out = exchange.getResponseBody();
                                    out.write(bytes, 0, bytes.length);
                                    out.close();
                                } else {
                                    exchange.getResponseBody().close();
                                }
                                exchange.close();
                            } catch (sendErr) {
                                // best-effort — nothing more to do if even the error path fails
                            }
                        }

                        // Real Bun's default (non-development) fetch-handler-threw response,
                        // verified against real `bun` 1.3.13 with `development: false`: 500
                        // status, plain-text body "Something went wrong!", content-type
                        // text/plain. (Bun's *development-mode* default is a much richer HTML
                        // error overlay — deliberately not replicated here; this shim always
                        // behaves like `development: false`.)
                        function sendDefault500() {
                            var msg = 'Something went wrong!';
                            sendBytes(500, toJByteArray(msg), (function () {
                                var h = new MyceliumHeaders();
                                h.set('content-type', 'text/plain');
                                return h;
                            })());
                        }

                        function sendResponse(responseLike) {
                            var response = responseLike instanceof MyceliumResponse
                                ? responseLike
                                // Real Bun: a fetch handler returning anything other than a
                                // Response is treated as *success*, not an error — verified
                                // with `fetch(){ return "not a response" }` against real bun
                                // 1.3.13: status 200, this exact placeholder body.
                                : new MyceliumResponse('Welcome to Bun! To get started, return a Response object.');
                            sendBytes(response.status, toJByteArray(response._bodyText || ''), response.headers);
                        }

                        function handleThrown(e) {
                            console.error('mycelium: Bun.serve fetch handler threw:', e && e.stack ? e.stack : e);
                            if (errorHandler) {
                                try {
                                    var errResult = errorHandler(e);
                                    if (errResult && typeof errResult.then === 'function') {
                                        errResult.then(function (r) {
                                            if (r) sendResponse(r); else sendDefault500();
                                        }, function () { sendDefault500(); });
                                        return;
                                    }
                                    if (errResult) {
                                        sendResponse(errResult);
                                        return;
                                    }
                                } catch (e2) {
                                    // fall through to the default 500 below
                                }
                            }
                            sendDefault500();
                        }

                        try {
                            var method = String(exchange.getRequestMethod());
                            var fullUrl = 'http://' + displayHost + ':' + httpServer.getAddress().getPort()
                                + String(exchange.getRequestURI().toString());
                            var bodyBytes = readAllBytes(exchange.getRequestBody());
                            var bodyText = bodyBytes.length > 0 ? String(new JString(bodyBytes, JCharset.UTF_8)) : '';
                            var headersInit = {};
                            var hdrs = exchange.getRequestHeaders();
                            var it = hdrs.entrySet().iterator();
                            while (it.hasNext()) {
                                var entry = it.next();
                                if (entry.getKey() === null) continue;
                                var values = entry.getValue();
                                var joined = [];
                                var vit = values.iterator();
                                while (vit.hasNext()) joined.push(String(vit.next()));
                                headersInit[String(entry.getKey())] = joined.join(', ');
                            }
                            var req = new MyceliumRequest(fullUrl, { method: method, headers: headersInit, _bodyText: bodyText });

                            var result;
                            try {
                                result = fetchHandler(req, serverHandle);
                            } catch (syncErr) {
                                handleThrown(syncErr);
                                return;
                            }
                            if (result && typeof result.then === 'function') {
                                result.then(sendResponse, handleThrown);
                            } else {
                                sendResponse(result);
                            }
                        } catch (outerErr) {
                            try {
                                exchange.sendResponseHeaders(500, 0);
                                exchange.close();
                            } catch (e4) {
                                // best-effort
                            }
                        }
                    });
                    httpServer.setExecutor(null);
                    httpServer.start();

                    var actualPort = httpServer.getAddress().getPort();
                    var serverHandle = {
                        port: actualPort,
                        hostname: host,
                        development: !!options.development,
                        fetch: fetchHandler,
                        get url() {
                            return new globalThis.URL('http://' + displayHost + ':' + actualPort + '/');
                        },
                        stop: function () {
                            httpServer.stop(0);
                        },
                        reload: function (newOptions) {
                            if (newOptions && typeof newOptions.fetch === 'function') fetchHandler = newOptions.fetch;
                        },
                    };
                    return serverHandle;
                }

                // --- Bun.argv / Bun.version / Bun.revision / Bun.env ---
                //
                // Bun.argv's shape matches Node's process.argv convention: [execPath,
                // scriptPath, ...args] — verified against real `bun` 1.3.13 (`bun run
                // script.js foo bar` reports Bun.argv as
                // [<absolute bun binary path>, <absolute script path>, "foo", "bar"]). This
                // shim can only reconstruct the first two elements (no extra CLI args are
                // threaded through Mycelium to guest code at all today — process.argv is `[]`
                // unconditionally for the same reason, see PROCESS_INIT_SCRIPT above), so
                // trailing args are simply absent rather than fabricated.
                var argv = [];
                try {
                    var cmdOpt = JProcessHandle.current().info().command();
                    argv.push(cmdOpt.isPresent() ? String(cmdOpt.get()) : 'bun');
                } catch (e) {
                    argv.push('bun');
                }
                if (typeof globalThis.__mycelium_bun_script_path__ !== 'undefined' && globalThis.__mycelium_bun_script_path__) {
                    argv.push(String(globalThis.__mycelium_bun_script_path__));
                }

                globalThis.Bun = {
                    // Shares the exact same env source `process.env` already uses (see
                    // PROCESS_INIT_SCRIPT above, which always runs before this script) rather
                    // than duplicating the env-reading/mutation-tracking logic.
                    env: globalThis.process.env,
                    argv: argv,
                    // Non-load-bearing per the task scope ("doesn't need to match Bun's actual
                    // version, just be a non-empty semver-looking string") — set to the
                    // version of the real `bun` binary this shim was developed/verified
                    // against (1.3.13, via this repo's flake.nix devShell), not a claim that
                    // Mycelium actually replicates that exact release's behavior everywhere.
                    version: '1.3.13',
                    revision: 'mycelium-bun-compat-shim',
                    file: function (path) { return new BunFile(path); },
                    serve: bunServe,
                };
            })();
        """.trimIndent()
    }
}

package org.mycelium.library

import java.io.File
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.IOAccess

class Mycelium(
    val languages: List<LanguageSupport> = listOf(
        JsSupport, RubySupport, PythonSupport, CSupport, RustSupport, TinyGoSupport
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
    private val filesystem = InterceptingFileSystem()

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
    private val context: Context =
        Context.newBuilder()
            .allowAllAccess(true)
            .allowIO(IOAccess.newBuilder().fileSystem(filesystem).build())
            // Unrelated to js.commonjs-require above (which swaps the module *loader* and
            // breaks cross-language imports). This only changes what Context#eval hands
            // back for a Source that turns out to be an ES module: the module's namespace
            // object (so host code can read named/default exports off the returned Value)
            // instead of `undefined` (an ES module's top level has no completion value).
            // Needed by InterceptingFileSystem.requireModule, which evaluates a resolved
            // module directly from Kotlin (unlike ordinary `import`, which GraalJS's own
            // module linker resolves internally without ever handing Java-side code a
            // Value to inspect).
            .option("js.esm-eval-returns-exports", "true")
            // Enables GraalJS's built-in `WebAssembly` JS API global (WebAssembly.Module /
            // .Instance / ...), which InterceptingFileSystem.loadWasmExports uses to load
            // tinygo-compiled Go WASM modules and read their exports off
            // `instance.exports`. GraalVM's raw "wasm" Truffle language
            // (context.eval("wasm", ...)) exposes no exports as Value members at all — see
            // TinyGoSupport in Languages.kt — so the JS WebAssembly API is used instead of
            // evaluating compiled WASM directly as a "wasm"-language Source.
            .option("js.webassembly", "true")
            .build()

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

    init {
        filesystem.init(context, this, yolo)
        for (lang in languages) {
            lang.injectEvalModule(context, evalModuleFn)
        }
        context.getBindings("js").putMember("__mycelium_require__", requireFn)
        context.eval("js", PROCESS_INIT_SCRIPT)
        context.eval("js", GLOBAL_CRYPTO_INIT_SCRIPT)
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
    // `node_modules` is missing or empty there. If both hold, run `npm install` (falling
    // back to `pnpm install`) in that directory so the declared dependencies are present
    // before the entry file's own imports get a chance to fail. Runs unconditionally
    // (irrespective of `--yolo`) unless `autoInstall` is false (the app's `--no-install`
    // flag) — installing exactly what a manifest already declares doesn't carry the same
    // "install whatever a bare import happens to resolve to" risk `--yolo` does.
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
    }
}

package org.mycelium.library.compiler

import java.nio.file.Files
import java.nio.file.Path

// Maps a language ID (e.g. "coffeescript", "typescript") to the CompilerSpec describing how
// to compile it to JS. Ships with entries for every compile-to-JS language Mycelium knows
// about out of the box; `register` lets a consumer add support for further languages without
// modifying this file.
object CompilerRegistry {
    private val specs = mutableMapOf<String, CompilerSpec>()

    init {
        register(
            CompilerSpec(
                languageId = "typescript",
                // Bundled: compiled via EsbuildBackend (io.mvnpm:esbuild-java), not an
                // npm-resolved "typescript" package.
                npmPackageName = null,
                defaultVersion = "0.24.0",
                compileFunction = "",
                compileOptions = emptyMap(),
                outputIsObject = false,
            )
        )
        register(
            CompilerSpec(
                languageId = "coffeescript",
                npmPackageName = "coffeescript",
                defaultVersion = "2.7.0",
                compileFunction = "require('coffeescript').compile",
                compileOptions = mapOf("bare" to true),
                outputIsObject = false,
            )
        )
        register(
            CompilerSpec(
                languageId = "civet",
                npmPackageName = "@danielx/civet",
                defaultVersion = "0.9.24",
                compileFunction = "require('@danielx/civet').compile",
                // `sync: true` is required to get a plain string back synchronously —
                // without it, civet.compile returns a Promise (it supports async plugins/
                // macros in general), which NpmJsApiBackend has no way to await from
                // Kotlin-side host code. `js: true` selects plain-JS output (as opposed to
                // leaving in TypeScript-flavored syntax).
                compileOptions = mapOf("js" to true, "sync" to true),
                outputIsObject = false,
            )
        )
        register(
            CompilerSpec(
                languageId = "livescript",
                npmPackageName = "livescript",
                defaultVersion = "1.6.0",
                // `require('livescript').compile(code, options)` returns a plain JS string
                // by default (verified against the published lib/index.js source) — it only
                // returns a `{code, map, ...}` object when `options.map` is set, which we
                // never set here, so `outputIsObject: false` is correct.
                compileFunction = "require('livescript').compile",
                // `bare: true` mirrors CoffeeScript's own entry above: without it, LiveScript
                // (like CoffeeScript) wraps the whole compiled module body in an IIFE, which
                // would hide top-level declarations from the rest of Mycelium's ESM-shim
                // machinery (InterceptingFileSystem's export-detection expects top-level
                // `var`/`function` declarations it can rewrite into `export`s).
                compileOptions = mapOf("bare" to true),
                outputIsObject = false,
            )
        )
        // Elm, PureScript, and ReScript: none of them expose a JS-callable
        // `compile(source, options)` function the way CoffeeScript/Civet/LiveScript do, so
        // each is routed through SubprocessBackend (BackendType.SUBPROCESS) instead of
        // NpmJsApiBackend:
        //   - `elm` on npm (https://registry.npmjs.org/elm) is just an installer — its
        //     description is literally "Installer for Elm: just downloads the binary into
        //     node_modules" (optionalDependencies per-platform binaries, `bin: {elm: "bin/elm"}`).
        //     There is no `require('elm')`-able JS API; the only interface is the native
        //     `elm` binary, invoked as a subprocess.
        //   - `purescript` on npm (https://registry.npmjs.org/purescript) is likewise a
        //     wrapper that installs a native `purs` binary (`bin: {purs: "purs.bin"}`,
        //     description "PureScript wrapper that makes it available as a local dependency")
        //     — no JS compile function, subprocess-only.
        //   - `rescript` on npm (https://registry.npmjs.org/rescript) ships the OCaml-based
        //     native toolchain (`bsc`/`rescript`/`bstracing` binaries) plus a compiled JS
        //     *runtime* (`./lib/js/*`, `./lib/es6/*` — the stdlib ReScript programs import,
        //     not a compiler entry point). Its `exports` map has no `./compiler` or similar
        //     JS-callable entry; compilation is subprocess-only via the native `bsc` binary,
        //     which lives at a platform-specific path (e.g. `linux/bsc.exe`,
        //     `darwinarm64/bsc.exe`) rather than anywhere `package.json`'s own `bin` field
        //     names — the `.exe` suffix is a historical OCaml-native-toolchain naming
        //     convention carried over onto every platform, not actually Windows-specific.
        //
        // See each registration's own comment below for compile-workflow specifics
        // (elm.json scaffolding for Elm, `output/<Module>/index.js` for PureScript, stdout
        // for ReScript) and their caveats.
        register(
            CompilerSpec(
                languageId = "elm",
                npmPackageName = "elm",
                // Latest npm "elm" dist-tag as of this writing; wraps compiler release 0.19.1.
                defaultVersion = "0.19.1-6",
                backendType = BackendType.SUBPROCESS,
                subprocessBinaryPath = { packageDir -> packageDir.resolve("bin/elm") },
                subprocessOutputMode = SubprocessOutputMode.OUTPUT_FILE,
                // CAVEAT: `elm make` refuses to run without a discoverable `elm.json` in (or
                // above) its working directory — with none present, it interactively prompts
                // "Would you like me to create an elm.json file for you? [Y/n]:" on stdin,
                // which would hang forever against a subprocess with no attached terminal.
                // Worked around by writing a minimal `elm.json` into the (freshly created,
                // per-compile) working directory before every invocation, with
                // "source-directories" pointing at the real source file's own parent
                // directory (so the file is compiled in place — no copying needed) and a
                // single explicit dependency on elm/core (the only package every Elm module
                // implicitly needs; anything beyond that a given source file imports will
                // fail to resolve, since there is no dependency-declaration mechanism for a
                // single loose .elm file the way `elm.json` normally expects one).
                // `elm make` additionally resolves/downloads elm/core (and its own transitive
                // dependencies) from https://package.elm-lang.org into `$ELM_HOME` on first
                // use if not already cached there — unlike Mycelium's own npm-package
                // downloading (CompilerDownloader), this is the *Elm* compiler's own
                // dependency-fetching, entirely outside Mycelium's control, and requires
                // network access + a writable $ELM_HOME the first time any Elm file is
                // compiled on a given machine.
                compileArgs = { sourcePath, outputPath, workDir ->
                    writeMinimalElmJson(workDir, sourcePath.parent)
                    listOf("make", sourcePath.toString(), "--output=${outputPath}")
                },
            )
        )
        register(
            CompilerSpec(
                languageId = "purescript",
                npmPackageName = "purescript",
                // Latest npm "purescript" dist-tag as of this writing; wraps compiler
                // release 0.15.15 (post-0.15, `purs` emits ES modules rather than CommonJS —
                // GraalJS evaluates the result as an ES module the same way it does
                // TypeScript/CoffeeScript output, via JsSupport, so this is compatible).
                defaultVersion = "0.15.15",
                backendType = BackendType.SUBPROCESS,
                subprocessBinaryPath = { packageDir -> packageDir.resolve("purs.bin") },
                subprocessOutputMode = SubprocessOutputMode.OUTPUT_FILE,
                // CAVEAT: `purs bundle` (which used to produce a single-file JS bundle) was
                // removed in 0.15 — the documented modern workflow is "compile with `purs
                // compile`, then hand the resulting ES modules under `output/` to a real
                // bundler (esbuild/webpack/Parcel)". Bundling a second time here would be
                // redundant with (and architecturally opposed to) EsbuildBackend's own
                // transform-only, import-preserving approach for TypeScript, so this instead
                // just runs `purs compile <file> --output <workDir>/output` and reads back
                // the single JS file `purs` wrote for the compiled module directly
                // (`output/<ModuleName>/index.js`, located via `locateOutput` below) — the
                // module's own `import`/`export` statements toward *other* PureScript modules
                // are left completely unresolved, since there are none: single-file
                // compilation only works at all for source that imports nothing beyond
                // implicitly-available `Prim`. Any real cross-module PureScript program needs
                // its full module graph compiled together (a Spago/pulp project), which is
                // out of scope for Mycelium's per-file compile-to-JS model.
                compileArgs = { sourcePath, _, workDir ->
                    listOf("compile", sourcePath.toString(), "--output", workDir.resolve("output").toString())
                },
                locateOutput = { workDir, sourcePath, _ ->
                    val moduleName = extractPureScriptModuleName(sourcePath) ?: "Main"
                    workDir.resolve("output").resolve(moduleName).resolve("index.js")
                },
            )
        )
        register(
            CompilerSpec(
                languageId = "rescript",
                npmPackageName = "rescript",
                // Latest npm "rescript" dist-tag as of this writing.
                defaultVersion = "11.1.4",
                backendType = BackendType.SUBPROCESS,
                subprocessBinaryPath = { packageDir -> packageDir.resolve(rescriptPlatformDir()).resolve("bsc.exe") },
                // Of the three, ReScript's `bsc` is the simplest to drive: as a standalone
                // ("barebone") single-file compiler (as opposed to `rescript`/`bsb`, the
                // build-system entry point that expects a whole `bsconfig.json`-described
                // project), `bsc <file>.res` compiles exactly that one file and writes the
                // resulting JS straight to stdout with no project/config file needed at all
                // — no elm.json- or output-directory-locating workaround required.
                subprocessOutputMode = SubprocessOutputMode.STDOUT,
                compileArgs = { sourcePath, _, _ -> listOf(sourcePath.toString()) },
            )
        )
        // Imba (`imba` on npm) was also investigated and NOT added: its
        // `require('imba/compiler').compile(source, options)` function (confirmed present at
        // ./dist/compiler.cjs, exports.compile) does not return a plain JS string or a
        // `{code}`-shaped object the way NpmJsApiBackend's `outputIsObject` contract expects.
        // Deobfuscating the (minified) 2.0.0-alpha compiler shows `compile()` returns a whole
        // `Document` instance — compiled output lives on a `.js` field that is itself
        // populated by further internal machinery, not a simple top-level `code` string —
        // so NpmJsApiBackend.compile's single `result.getMember("code")` (or bare
        // `result.asString()`) can't extract it without backend changes. Imba is also still
        // shipping only alpha prereleases (no stable dist-tag) as of this writing, which
        // would make `defaultVersion` unstable regardless. Left for future work alongside a
        // NpmJsApiBackend enhancement (or a bespoke Imba backend) rather than guessed at here.
    }

    fun resolve(languageId: String): CompilerSpec? = specs[languageId]

    fun register(spec: CompilerSpec) {
        specs[spec.languageId] = spec
    }

    // All registered language IDs (built-ins plus anything a consumer has `register`ed) —
    // used by e.g. `mycelium compiler list` to enumerate what's available without needing to
    // know the specific set of built-in languages ahead of time.
    fun languageIds(): Set<String> = specs.keys

    // Writes a minimal `elm.json` into `workDir` so `elm make` (invoked with `workDir` as its
    // working directory — see SubprocessBackend) finds a project file instead of
    // interactively prompting to create one. `sourceDir` (the real source file's own parent
    // directory) is used as the sole "source-directories" entry so the file is compiled
    // in place, with no copying — see the "elm" CompilerSpec registration's own CAVEAT
    // comment above for why this only supports source needing nothing beyond elm/core.
    private fun writeMinimalElmJson(workDir: Path, sourceDir: Path) {
        val json = """
            {
                "type": "application",
                "source-directories": [
                    ${jsonStringLiteral(sourceDir.toString())}
                ],
                "elm-version": "0.19.1",
                "dependencies": {
                    "direct": {
                        "elm/core": "1.0.5"
                    },
                    "indirect": {}
                },
                "test-dependencies": {
                    "direct": {},
                    "indirect": {}
                }
            }
        """.trimIndent()
        Files.writeString(workDir.resolve("elm.json"), json)
    }

    private fun jsonStringLiteral(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // Best-effort extraction of a PureScript source file's own module name (e.g. "Main" from
    // "module Main where" or "module Data.Foo.Bar (baz) where") — used by the "purescript"
    // CompilerSpec's `locateOutput` to find the one JS file `purs compile` wrote for this
    // module under `output/<ModuleName>/index.js`. Falls back to null (the caller then
    // defaults to "Main") if no `module ... where` header is found at all, e.g. malformed
    // source — `purs compile` itself will already have failed with a clearer diagnostic in
    // that case, since PureScript requires every file to declare a module.
    private val moduleNameRegex = Regex("""(?m)^\s*module\s+([A-Za-z_][A-Za-z0-9_.]*)""")
    private fun extractPureScriptModuleName(sourcePath: Path): String? =
        moduleNameRegex.find(Files.readString(sourcePath))?.groupValues?.get(1)

    // Maps this JVM's OS/architecture to the platform-specific subdirectory name the
    // `rescript` npm package ships its native `bsc.exe`/`rescript.exe`/`bstracing.exe`
    // binaries under (mirrors the package's own `install.js`, which does this same
    // switch to decide which optionalDependency sub-package to require at runtime).
    private fun rescriptPlatformDir(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val isArm = arch.contains("aarch64") || arch.contains("arm")
        return when {
            os.contains("win") -> "win32"
            os.contains("mac") || os.contains("darwin") -> if (isArm) "darwinarm64" else "darwin"
            else -> if (isArm) "linuxarm64" else "linux"
        }
    }
}

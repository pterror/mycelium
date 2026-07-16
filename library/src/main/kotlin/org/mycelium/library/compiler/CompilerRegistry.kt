package org.mycelium.library.compiler

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
        // Elm, PureScript, and ReScript are deliberately NOT registered here — none of them
        // expose a JS-callable `compile(source, options)` function the way CoffeeScript/
        // Civet/LiveScript do:
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
        //     JS-callable entry; compilation is subprocess-only via the native `rescript`/
        //     `bsc` binaries.
        // Adding any of these would require Compiler.compile's subprocess/native-binary path
        // (like C/Rust/TinyGo in Languages.kt) rather than CompilerRegistry/NpmJsApiBackend's
        // require()-and-call-a-JS-function model — out of scope here.
        //
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
}

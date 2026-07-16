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

package org.mycelium.library

import org.graalvm.polyglot.Context

object JsSupport : LanguageSupport {
    override val languageId = "js"
    override val extensions = listOf("js", "mjs", "ts", "mts")
    override val compilerCommand = null
    override val moduleExtension = ".mjs"

    // JsSupport is shared by both plain JS (.js/.mjs) and TypeScript (.ts/.mts) — this only
    // ever applies to the latter. InterceptingFileSystem's dispatch logic additionally
    // checks the file's own extension before consulting this, so plain .js/.mjs files are
    // never routed through the compile-to-JS pipeline despite jsCompilerId being non-null
    // here.
    override val jsCompilerId = "typescript"

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

    override fun injectEvalModule(context: Context, fn: org.graalvm.polyglot.proxy.ProxyExecutable) {
        context.getBindings("js").getMember("Polyglot").putMember("evalModule", fn)
    }
}

// CoffeeScript, compiled to plain JS before GraalJS ever evaluates it (see
// org.mycelium.library.compiler.CompileToJsDispatcher / NpmJsApiBackend, which `require()`s
// the real `coffeescript` npm package into the shared Context). Delegates everything except
// `extensions`/`jsCompilerId` to JsSupport (interface delegation, `by JsSupport`) since a
// CoffeeScript file, once compiled, is executed as an ordinary ES module the exact same way
// a .ts/.mts file is — same languageId ("js", for eval/frame-detection/generateShim
// purposes), same Polyglot.evalModule wiring, same export-name validation rules. Only which
// extension(s) this LanguageSupport owns, and which compile-to-JS compiler its source is
// routed through, actually differ.
object CoffeeSupport : LanguageSupport by JsSupport {
    override val extensions = listOf("coffee")
    override val jsCompilerId = "coffeescript"
}

// Civet, likewise compiled to plain JS via NpmJsApiBackend (backed by the real
// `@danielx/civet` npm package) — see CoffeeSupport's own doc comment for why delegation
// (`by JsSupport`) is enough here.
object CivetSupport : LanguageSupport by JsSupport {
    override val extensions = listOf("civet")
    override val jsCompilerId = "civet"
}

// LiveScript, likewise compiled to plain JS via NpmJsApiBackend (backed by the real
// `livescript` npm package) — see CoffeeSupport's own doc comment for why delegation
// (`by JsSupport`) is enough here.
object LiveScriptSupport : LanguageSupport by JsSupport {
    override val extensions = listOf("ls")
    override val jsCompilerId = "livescript"
}

// Elm, compiled to plain JS via SubprocessBackend (backed by the real `elm` npm package's
// installed native binary, not a JS API — see CompilerRegistry's "elm" registration for the
// elm.json-scaffolding workaround and its single-file-compilation caveats). See
// CoffeeSupport's own doc comment for why delegation (`by JsSupport`) is enough here despite
// Elm being subprocess-backed rather than npm-JS-API-backed: from GraalJS's perspective, the
// compiled output is just another ES module either way.
object ElmSupport : LanguageSupport by JsSupport {
    override val extensions = listOf("elm")
    override val jsCompilerId = "elm"
}

// PureScript, likewise compiled via SubprocessBackend (backed by the real `purescript` npm
// package's installed native `purs` binary) — see CoffeeSupport's own doc comment for why
// delegation (`by JsSupport`) is enough here, and CompilerRegistry's "purescript"
// registration for the `purs compile`/`output/<Module>/index.js` workflow and its
// single-file (no cross-module imports) caveat.
object PureScriptSupport : LanguageSupport by JsSupport {
    override val extensions = listOf("purs")
    override val jsCompilerId = "purescript"
}

// ReScript, likewise compiled via SubprocessBackend (backed by the real `rescript` npm
// package's installed native, platform-specific `bsc` binary) — see CoffeeSupport's own doc
// comment for why delegation (`by JsSupport`) is enough here, and CompilerRegistry's
// "rescript" registration for the standalone `bsc <file>` stdout-based workflow. `.resi`
// (ReScript's interface-file extension, mirroring OCaml's `.mli`) is routed through the same
// compiler: `bsc` accepts either extension, and an interface file alone has no runtime
// exports of its own to speak of, but there's no harm in Mycelium being willing to try.
object ReScriptSupport : LanguageSupport by JsSupport {
    override val extensions = listOf("res", "resi")
    override val jsCompilerId = "rescript"
}

// Dart, compiled to plain JS via dart2js (SubprocessBackend, resolved via ToolResolver/
// ToolSource rather than an npm package — see CompilerRegistry's "dart" registration and
// ToolResolver.kt's DartSdkDownload) — see CoffeeSupport's own doc comment for why delegation
// (`by JsSupport`) is enough here.
object DartSupport : LanguageSupport by JsSupport {
    override val extensions = listOf("dart")
    override val jsCompilerId = "dart"
}

// Kotlin/JS, compiled via SubprocessBackend (backed by the `kotlin-compiler` npm package's
// bundled `kotlinc-js` binary — see CompilerRegistry's "kotlinjs" registration for why this
// falls back to a subprocess rather than an in-process Kotlin/JS compiler-API call despite
// Kotlin already being Mycelium's own host language). `.kt`/`.kts` both route through the same
// compiler — see CoffeeSupport's own doc comment for why delegation (`by JsSupport`) is enough
// here.
object KotlinJsSupport : LanguageSupport by JsSupport {
    override val extensions = listOf("kt", "kts")
    override val jsCompilerId = "kotlinjs"
}

// Scala.js, compiled via SubprocessBackend (backed by a `scala-cli` binary already on PATH or
// in node_modules/.bin — see CompilerRegistry's "scalajs" registration) — see CoffeeSupport's
// own doc comment for why delegation (`by JsSupport`) is enough here.
object ScalaJsSupport : LanguageSupport by JsSupport {
    override val extensions = listOf("scala")
    override val jsCompilerId = "scalajs"
}

// Gleam, compiled via SubprocessBackend (backed by a native `gleam` binary downloaded from
// GitHub Releases — see CompilerRegistry's "gleam" registration and ToolResolver.kt's
// GitHubRelease for the download workflow) — see CoffeeSupport's own doc comment for why
// delegation (`by JsSupport`) is enough here. Unlike Elm/PureScript/ReScript, Gleam has no
// single-file compile mode at all: importing a .gleam file with no gleam.toml anywhere above
// it fails outright (see CompilerRegistry's "gleam" registration for the exact diagnostic).
object GleamSupport : LanguageSupport by JsSupport {
    override val extensions = listOf("gleam")
    override val jsCompilerId = "gleam"
}

// Imba, compiled to plain JS via NpmJsApiBackend (backed by the real `imba` npm package's
// `require('imba/compiler').compile`, whose Document-shaped result is unwrapped via
// CompilerSpec.outputProperty = "js" — see CompilerRegistry's "imba" registration) — see
// CoffeeSupport's own doc comment for why delegation (`by JsSupport`) is enough here.
//
// CAVEAT: Imba 2.x has never shipped a stable release — only alpha prereleases exist as of
// this writing (the npm registry's own "latest" dist-tag is "2.0.0-alpha.252") — so this
// support should be treated as experimental: breaking changes between alpha builds are
// possible, and `defaultVersion` may need bumping more often than any other language here.
object ImbaSupport : LanguageSupport by JsSupport {
    override val extensions = listOf("imba")
    override val jsCompilerId = "imba"
}

object RubySupport : LanguageSupport {
    override val languageId = "ruby"
    override val extensions = listOf("rb")
    override val compilerCommand = null
    override val packageEcosystemOrder =
        listOf(PackageEcosystem.RUBYGEMS, PackageEcosystem.NPM, PackageEcosystem.PYPI)

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

    override fun injectEvalModule(context: Context, fn: org.graalvm.polyglot.proxy.ProxyExecutable) {
        context.eval("ruby", """
            module Polyglot
                def self.eval_module(lang, code)
                    Polyglot.import("__mycelium_eval_module__").call(lang, code)
                end
            end
        """.trimIndent())
        context.polyglotBindings.putMember("__mycelium_eval_module__", fn)
    }

    // Monkeypatches Kernel#require so `require 'https://.../foo.rb'` (and `http://`) is
    // special-cased to `fn` before falling through to Ruby's own require — see
    // LanguageSupport.injectHttpRequire's own doc comment for why this can't just be
    // handled by FileSystem.parsePath the way JS's `import` is.
    override fun injectHttpRequire(context: Context, fn: org.graalvm.polyglot.proxy.ProxyExecutable) {
        context.polyglotBindings.putMember("__mycelium_require_http__", fn)
        context.eval("ruby", """
            module Kernel
                alias_method :__mycelium_original_require__, :require
                def require(path)
                    if path.is_a?(String) && (path.start_with?("http://") || path.start_with?("https://"))
                        Polyglot.import("__mycelium_require_http__").call(path)
                    else
                        __mycelium_original_require__(path)
                    end
                end
                private :require
            end
        """.trimIndent())
    }
}

object PythonSupport : LanguageSupport {
    override val languageId = "python"
    override val extensions = listOf("py")
    override val compilerCommand = null
    override val packageEcosystemOrder =
        listOf(PackageEcosystem.PYPI, PackageEcosystem.NPM, PackageEcosystem.RUBYGEMS)

    // Placeholder — never validated against a real GraalPython call stack. Python-as-host
    // support is blocked before frame detection even matters: evaluating Python from JS
    // through a named Source with a custom FileSystem attached (Mycelium's normal
    // operating mode) crashes GraalPython 24.1.1 with "SystemError: Unable to set dict of
    // object of type foreign" after the guest code has already run. See
    // PythonInteropExperimentTest for the investigation and a live repro.
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
    override val compilerCommand = listOf("rustc", "--crate-type=lib", "--emit=llvm-bc", "-o")

    override fun detectFrame(frame: String) = false
    override fun isValidExportName(name: String) =
        !name.contains('.') && !name.startsWith("__")
    override fun generateShim(exports: Map<String, String>) = ""
}

// Go support, via tinygo -> WASM. Wired into Mycelium's default language list (see
// Mycelium.kt) despite tinygo not being guaranteed present in the environment: the
// existing Compiler/InterceptingFileSystem plumbing already turns a missing compiler
// binary into a clean MyceliumException at the point a .go file is actually imported
// (ProcessBuilder's IOException is caught in Compiler.compile), rather than at startup —
// so listing TinyGoSupport by default costs nothing for consumers who never import a Go
// file, exactly like the other compiled languages (C/Rust) already work.
//
// Why WASM and not LLVM: there's no single-command Go -> LLVM bitcode pipeline the way
// there is for C (clang -emit-llvm) or Rust (rustc --emit=llvm-bc). Stock `go build` only
// emits native machine code or, via `-buildmode=c-shared`, a platform shared object —
// never LLVM IR/bitcode GraalVM's LLVM runtime can load. gollvm (Go's experimental LLVM
// backend) could theoretically emit bitcode but is unmaintained/hard to obtain as of this
// writing, and bouncing through a .a archive + clang to recover bitcode from it is fragile
// enough (and undocumented enough) not to be worth building on. So Go -> LLVM is not
// offered here at all — Go's only compiled path is Go -> WASM via tinygo
// (`tinygo build -target=wasi`), loaded through GraalVM's WASM language (Truffle wasm)
// rather than the LLVM language every other compiled LanguageSupport here targets. That
// makes `languageId = "wasm"`, not "llvm", and `outputExtension = "wasm"` (see
// LanguageSupport.outputExtension / InterceptingFileSystem's compiled-import branch,
// which uses outputExtension rather than languageId to decide "llvm" vs "wasm" so
// Compiler's cache file extension and the guest language used to load it never drift
// apart).
//
// Requirements: `tinygo` must be on PATH (not stock `go` — stock Go has no wasi/wasm
// target that emits a small, freestanding-friendly binary the way tinygo does). Compiled
// output is cached the same way as C/Rust (content-hashed under
// $XDG_CACHE_HOME/mycelium/<hash>.wasm).
//
// RESOLVED BLOCKER (originally discovered 2026-07-15 during first end-to-end test, fixed
// same day): GraalVM's raw "wasm" Truffle language does NOT expose module exports via
// Value.memberKeys the way the LLVM language does — context.eval("wasm", file) hands back
// a Value with zero memberKeys and no way to instantiate/access exports. The fix was to
// stop using the "wasm" language directly and instead load compiled WASM through GraalJS's
// built-in `WebAssembly` JS API (WebAssembly.Module/.Instance, enabled via the
// "js.webassembly" context option set in Mycelium.kt) — see
// InterceptingFileSystem.loadWasmExports, which returns `instance.exports`, an ordinary JS
// object generateShim can enumerate exactly like an LLVM Value's members.
// Lua 5.1, via Mycelium's own from-scratch Truffle language implementation
// (project(":lua"), package org.mycelium.lua — see lua/build.gradle.kts and
// LuaLanguage.java's own Truffle registration, language ID "lua"). Unlike every other
// LanguageSupport here, this isn't a GraalVM-shipped or npm-installed runtime: Mycelium
// owns the lexer/parser/AST/stdlib itself. `packageEcosystemOrder` falls back to NPM
// (rather than defining a new PackageEcosystem.LUAROCKS entry) since LuaRocks isn't wired
// into PackageEcosystem/PackageInstallerRegistry yet — a bare specifier encountered while
// the guest stack is inside Lua is simply resolved the same way any other unrecognized
// host language's would be (npm-first, the interface default), rather than against any
// Lua-specific package index.
//
// KNOWN LIMITATION (discovered integrating this LanguageSupport, 2026-07-20): the Lua
// runtime's own value types (LuaTable, LuaFunction — see
// lua/src/main/java/org/mycelium/lua/runtime/) carry no
// `@ExportLibrary(InteropLibrary.class)` annotations at all, so a polyglot Value wrapping
// either one exposes no members/executability to another guest language — `Value.memberKeys`
// is always empty and `Value.canExecute()` is always false for a Lua table/function. That
// blocks the two things Mycelium's own cross-language import machinery
// (ShimGenerator/InterceptingFileSystem) needs from a *target* language's return value:
// enumerating a returned table's fields to generate a shim (so a `.lua` module written as
// `return { add = function(a, b) ... end }` can't have `add` extracted the way a Ruby/JS
// module's own exports already are — see ShimGenerator.generateCrossLanguageShim, which
// walks `value.memberKeys`) and directly invoking a Lua closure from another guest language.
// A plain `context.eval("lua", ...)` that returns a primitive (number/string/boolean/nil)
// still crosses the polyglot boundary fine — Lua numbers/strings are ordinary boxed
// Long/Double/String values, which every guest language's interop already understands — and
// running a standalone `.lua` *file* via `Mycelium.runFile` works too, since neither of
// those depends on interop on the returned value. Only cross-language *member*-based
// import of/into Lua is affected; fixing it would require adding InteropLibrary exports to
// LuaTable/LuaFunction in the lua/ subproject itself, which is out of scope here (this
// LanguageSupport only wires up what the existing Lua runtime already exposes).
object LuaSupport : LanguageSupport {
    override val languageId = "lua"
    override val extensions = listOf("lua")
    override val compilerCommand = null
    override val packageEcosystemOrder = listOf(PackageEcosystem.NPM)

    // Placeholder, mirroring PythonSupport's own detectFrame doc comment — this Lua
    // implementation has no `require`-equivalent wired up yet (LuaContext installs the
    // stdlib but no module-loading builtin), so there's no real cross-language-import call
    // stack shape to validate this against yet. LuaRootNode (see
    // lua/src/main/java/org/mycelium/lua/nodes/LuaRootNode.java) doesn't override
    // toString()/getName(), so a Truffle call target's default frame representation while
    // executing Lua code includes that class's simple name.
    override fun detectFrame(frame: String) = frame.contains("LuaRootNode")

    private val identifierRegex = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")
    private val reservedWords = setOf(
        "and", "break", "do", "else", "elseif", "end", "false", "for", "function",
        "if", "in", "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while"
    )

    override fun isValidExportName(name: String) =
        identifierRegex.matches(name) && name !in reservedWords

    // Unverified against a real cross-language import — see this object's own KNOWN
    // LIMITATION doc comment above: Value.memberKeys is always empty for a Lua table/
    // function today, so ShimGenerator never actually has any exports to hand this
    // method in practice. Modeled on RubySupport's own generateShim (a plain top-level
    // assignment per export) for whenever Lua gains a "Polyglot" global of its own to
    // resolve `Polyglot.import` against.
    override fun generateShim(exports: Map<String, String>): String =
        exports.entries.joinToString("\n") { (name, key) ->
            "$name = Polyglot.import(\"$key\")"
        }
}

object TinyGoSupport : LanguageSupport {
    override val languageId = "wasm"
    override val extensions = listOf("go")
    override val compilerCommand = listOf("tinygo", "build", "-target=wasi", "-o")
    override val outputExtension = "wasm"

    override fun detectFrame(frame: String) = false
    override fun isValidExportName(name: String) =
        !name.contains('.') && !name.startsWith("__")
    override fun generateShim(exports: Map<String, String>) = ""
}

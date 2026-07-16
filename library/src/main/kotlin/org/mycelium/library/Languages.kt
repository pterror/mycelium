package org.mycelium.library

import org.graalvm.polyglot.Context

object JsSupport : LanguageSupport {
    override val languageId = "js"
    override val extensions = listOf("js", "mjs", "ts", "mts")
    override val compilerCommand = null

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

object RubySupport : LanguageSupport {
    override val languageId = "ruby"
    override val extensions = listOf("rb")
    override val compilerCommand = null

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
}

object PythonSupport : LanguageSupport {
    override val languageId = "python"
    override val extensions = listOf("py")
    override val compilerCommand = null

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

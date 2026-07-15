package org.mycelium.library

import org.graalvm.polyglot.Context

object JsSupport : LanguageSupport {
    override val languageId = "js"
    override val extensions = listOf("js", "mjs", "ts", "mts")
    override val compilerCommand = null

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
// Known limitations:
//  - Untested without a real tinygo binary in this environment (none is installed here as
//    of this writing — see flake.nix). generateShim/isValidExportName below mirror
//    CSupport/RustSupport's LLVM conventions on the assumption that GraalVM's WASM engine
//    exposes a module's exports as Value.memberKeys the same way the LLVM engine exposes a
//    bitcode module's global symbols (both are meant to follow the same polyglot Value
//    convention), but this has not been confirmed against a real compiled .wasm file.
//  - WASM (even under wasi) is sandboxed with its own linear memory and import/export ABI,
//    unlike the LLVM bitcode path where C/Rust functions share the same native address
//    space and can be called with plain scalar args/returns. Numeric args/returns
//    (int32/int64/float32/float64) should bridge transparently through polyglot Value the
//    same way LLVM's do; strings and structs will not — Go's ABI for those (e.g. Go
//    strings as a pointer+length pair into wasm linear memory) needs explicit marshaling
//    that this shim layer does not attempt. Keep exported functions to scalar
//    parameters/returns (see the //export comment convention tinygo requires) until that's
//    built out.
//  - main()'s WASI startup semantics (tinygo emits a `_start` export by default when
//    targeting wasi) aren't accounted for here; only //export-annotated functions are
//    expected to be usable as importable members.
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

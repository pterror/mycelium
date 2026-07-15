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

// EXPERIMENTAL — not wired into Mycelium's default language list.
//
// Investigated 2026-07-15: there's no single-command Go -> LLVM bitcode pipeline the way
// there is for C (clang -emit-llvm) or Rust (rustc --emit=llvm-bc). Stock `go build` only
// emits native machine code or, via `-buildmode=c-shared`, a platform shared object — never
// LLVM IR/bitcode GraalVM's LLVM runtime can load. gollvm (Go's experimental LLVM backend)
// could theoretically emit bitcode but is unmaintained/hard to obtain as of this writing.
//
// The viable path instead looks like Go -> WASM via tinygo (`tinygo build -target=wasi`),
// loaded through GraalVM's WASM language (Truffle wasm) rather than the LLVM language that
// every other LanguageSupport here targets. That means `languageId = "wasm"`, not "llvm" —
// this is a genuinely different runtime, not just another compilerCommand.
//
// Open question this raises for Mycelium: WASM (even under wasi) is sandboxed with its own
// linear memory and import/export ABI, unlike the LLVM bitcode path where C/Rust functions
// share the same native address space and can be called with plain scalar args/returns.
// Whether GraalVM's polyglot bindings can bridge a wasm export as transparently as an LLVM
// symbol (numeric args are probably fine; strings/structs would need explicit
// memory-marshaling through the wasm instance's linear memory) is unverified — this object
// is a stub for that investigation, not a tested integration.
object TinyGoSupport : LanguageSupport {
    override val languageId = "wasm"
    override val extensions = listOf("go")
    override val compilerCommand = listOf("tinygo", "build", "-target=wasi", "-o")

    override fun detectFrame(frame: String) = false
    override fun isValidExportName(name: String) =
        !name.contains('.') && !name.startsWith("__")
    override fun generateShim(exports: Map<String, String>) = ""
}

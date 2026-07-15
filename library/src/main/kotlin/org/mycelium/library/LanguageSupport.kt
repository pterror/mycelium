package org.mycelium.library

import org.graalvm.polyglot.Context

interface LanguageSupport {
    val languageId: String
    val extensions: List<String>
    val compilerCommand: List<String>?
    val needsCompilation: Boolean get() = compilerCommand != null

    // Extension (without the dot) Compiler.compile should give the cached compiled
    // artifact. Defaults to "bc" (LLVM bitcode) since that's what every existing compiled
    // LanguageSupport (C, Rust) produces; TinyGoSupport overrides this to "wasm" since
    // tinygo emits a WebAssembly binary, not LLVM bitcode. InterceptingFileSystem's
    // compiled-import branch also uses this to pick the guest language ("llvm" vs "wasm")
    // the artifact gets loaded with.
    val outputExtension: String get() = "bc"

    fun detectFrame(frame: String): Boolean
    fun isValidExportName(name: String): Boolean
    fun generateShim(exports: Map<String, String>): String
    fun injectEvalModule(context: Context, fn: org.graalvm.polyglot.proxy.ProxyExecutable) {}
}

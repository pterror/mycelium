package org.mycelium.library

import org.graalvm.polyglot.Context

interface LanguageSupport {
    val languageId: String
    val extensions: List<String>
    val compilerCommand: List<String>?
    val needsCompilation: Boolean get() = compilerCommand != null

    fun detectFrame(frame: String): Boolean
    fun isValidExportName(name: String): Boolean
    fun generateShim(exports: Map<String, String>): String
    fun injectEvalModule(context: Context, fn: org.graalvm.polyglot.proxy.ProxyExecutable) {}
}

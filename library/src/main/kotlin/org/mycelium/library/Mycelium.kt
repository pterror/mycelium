package org.mycelium.library

import java.io.File
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.IOAccess

class Mycelium(
    val languages: List<LanguageSupport> = listOf(
        JsSupport, RubySupport, PythonSupport, CSupport, RustSupport
    )
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
            .build()

    private val extensionMap: Map<String, LanguageSupport> =
        languages.flatMap { lang -> lang.extensions.map { it to lang } }.toMap()

    private val evalModuleFn = org.graalvm.polyglot.proxy.ProxyExecutable { args ->
        val lang = args[0].asString()
        val code = args[1].asString()
        evalModule(lang, code)
    }

    init {
        filesystem.init(context, this)
        for (lang in languages) {
            lang.injectEvalModule(context, evalModuleFn)
        }
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
}

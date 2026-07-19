package org.mycelium.library

import org.graalvm.polyglot.Context

// The cross-ecosystem package registries InterceptingFileSystem can resolve a bare import
// specifier against — see LanguageSupport.packageEcosystemOrder.
enum class PackageEcosystem { NPM, RUBYGEMS, PYPI }

interface LanguageSupport {
    val languageId: String
    val extensions: List<String>
    val compilerCommand: List<String>?
    val needsCompilation: Boolean get() = compilerCommand != null

    // The order in which InterceptingFileSystem.parsePath tries package ecosystems when
    // resolving a bare specifier while this language is the guest call stack's own host
    // language. Defaults to npm-first since that's the only ecosystem most LanguageSupports
    // (JS itself, plus every compile-to-JS language) actually import from; RubySupport/
    // PythonSupport override this to put their own ecosystem first instead, since a bare
    // specifier encountered while the stack is inside Ruby/Python is far more likely to be
    // a gem/PyPI package than an npm one, and checking it first avoids walking a
    // node_modules tree that will never exist for it.
    val packageEcosystemOrder: List<PackageEcosystem>
        get() = DEFAULT_PACKAGE_ECOSYSTEM_ORDER

    // The extension (with leading dot) Mycelium.evalModule writes its temp file with, so
    // GraalVM's own source-sniffing (which keys off file extension for some languages) picks
    // the right parser. Defaults to this language's first declared `extensions` entry;
    // JsSupport overrides this to ".mjs" since a bare ".js" temp file can be ambiguously
    // treated as CommonJS depending on the nearest ancestor package.json's "type" field,
    // while ".mjs" is unambiguously ESM. Every JsSupport-delegating LanguageSupport (Coffee,
    // Civet, ...) inherits ".mjs" too via `by JsSupport`, since none of them override this
    // property themselves.
    val moduleExtension: String get() = extensions.firstOrNull()?.let { ".$it" } ?: ".tmp"

    // Extension (without the dot) Compiler.compile should give the cached compiled
    // artifact. Defaults to "bc" (LLVM bitcode) since that's what every existing compiled
    // LanguageSupport (C, Rust) produces; TinyGoSupport overrides this to "wasm" since
    // tinygo emits a WebAssembly binary, not LLVM bitcode. InterceptingFileSystem's
    // compiled-import branch also uses this to pick the guest language ("llvm" vs "wasm")
    // the artifact gets loaded with.
    val outputExtension: String get() = "bc"

    // The compile-to-JS language ID (see org.mycelium.library.compiler.CompilerRegistry)
    // this LanguageSupport's source should be compiled through before GraalJS evaluates it
    // — e.g. "typescript" for TypeScript. null (the default) for languages that need no
    // compile-to-JS step, including plain JS/CJS/ESM and every non-JS-hosted language.
    val jsCompilerId: String? get() = null

    fun detectFrame(frame: String): Boolean
    fun isValidExportName(name: String): Boolean
    fun generateShim(exports: Map<String, String>): String
    fun injectEvalModule(context: Context, fn: org.graalvm.polyglot.proxy.ProxyExecutable) {}

    // Optional hook for languages whose native `require`-equivalent never hands a
    // URL-shaped specifier to Mycelium's FileSystem at all — Ruby's Kernel#require checks
    // whether its argument looks like an absolute filesystem path or a $LOAD_PATH-relative
    // feature name and raises LoadError itself otherwise, without ever calling
    // FileSystem.parsePath. GraalJS's own ESM `import` needs no such hook: it hands any
    // colon-containing specifier straight to FileSystem.parsePath(URI) itself (see
    // InterceptingFileSystem.parsePath), which already routes http(s) URLs through the
    // ordinary newByteChannel path. `fn` fetches+caches a URL (via HttpImportCache) and
    // evaluates it in this language, returning whether it actually ran (vs.
    // already-required) — the same true/false contract as Kernel#require's own return value.
    fun injectHttpRequire(context: Context, fn: org.graalvm.polyglot.proxy.ProxyExecutable) {}

    companion object {
        val DEFAULT_PACKAGE_ECOSYSTEM_ORDER =
            listOf(PackageEcosystem.NPM, PackageEcosystem.RUBYGEMS, PackageEcosystem.PYPI)
    }
}

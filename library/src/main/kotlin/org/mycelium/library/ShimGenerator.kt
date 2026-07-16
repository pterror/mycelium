package org.mycelium.library

import org.graalvm.polyglot.Context
import java.nio.channels.SeekableByteChannel
import java.nio.charset.StandardCharsets

// Generates the ESM shim source InterceptingFileSystem hands back to GraalJS when a JS
// (or other host-language) module imports a cross-language module — a compiled LLVM/WASM
// module (C/Rust/Go) or a directly-interpreted guest-language module (Ruby/Python) whose
// language differs from the importing file's own. The compiled/evaluated module's exports
// are stashed under namespaced keys in the shared Context's polyglot bindings, and the
// shim (built by the target LanguageSupport itself — see LanguageSupport.generateShim)
// reads them back out via `Polyglot.import(...)`.
//
// `context` is a lambda for the same reason as on NodeModuleResolver/CjsRuntime: this
// generator is constructed before InterceptingFileSystem has a real Context to hand it.
class ShimGenerator(private val context: () -> Context) {
    fun generateCrossLanguageShim(
        hostLang: LanguageSupport,
        value: org.graalvm.polyglot.Value,
        modulePath: String
    ): SeekableByteChannel {
        val bindings = context().polyglotBindings
        val exports = mutableMapOf<String, String>()
        for (k in value.memberKeys) {
            if (!hostLang.isValidExportName(k)) continue
            val namespacedKey = "$modulePath:$k"
            bindings.putMember(namespacedKey, value.getMember(k))
            exports[k] = namespacedKey
        }
        val shim = hostLang.generateShim(exports)
        return ReadOnlyBufferChannel(StandardCharsets.UTF_8.encode(shim))
    }
}

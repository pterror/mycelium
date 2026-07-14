package org.mycelium.library

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class LanguageSupportTest {

    @Test fun jsValidExportNames() {
        assertTrue(JsSupport.isValidExportName("foo"))
        assertTrue(JsSupport.isValidExportName("_private"))
        assertTrue(JsSupport.isValidExportName("\$jquery"))
        assertFalse(JsSupport.isValidExportName("class"))
        assertFalse(JsSupport.isValidExportName("import"))
        assertFalse(JsSupport.isValidExportName("respond_to?"))
        assertFalse(JsSupport.isValidExportName("=="))
        assertFalse(JsSupport.isValidExportName("llvm.lifetime.start"))
        assertFalse(JsSupport.isValidExportName(""))
    }

    @Test fun rubyValidExportNames() {
        assertTrue(RubySupport.isValidExportName("foo"))
        assertTrue(RubySupport.isValidExportName("_private"))
        assertTrue(RubySupport.isValidExportName("greet"))
        assertFalse(RubySupport.isValidExportName("respond_to?"))
        assertFalse(RubySupport.isValidExportName("=="))
        assertFalse(RubySupport.isValidExportName(""))
    }

    @Test fun pythonValidExportNames() {
        assertTrue(PythonSupport.isValidExportName("foo"))
        assertTrue(PythonSupport.isValidExportName("_private"))
        assertFalse(PythonSupport.isValidExportName("class"))
        assertFalse(PythonSupport.isValidExportName("import"))
        assertFalse(PythonSupport.isValidExportName("None"))
        assertFalse(PythonSupport.isValidExportName(""))
    }

    @Test fun cValidExportNames() {
        assertTrue(CSupport.isValidExportName("add"))
        assertTrue(CSupport.isValidExportName("hello"))
        assertFalse(CSupport.isValidExportName("__builtin_something"))
        assertFalse(CSupport.isValidExportName("llvm.lifetime.start"))
    }

    @Test fun jsDetectFrame() {
        assertTrue(JsSupport.detectFrame("GraalJSEvaluator.ModuleScriptRoot@66908383"))
        assertFalse(JsSupport.detectFrame("Kernel#require"))
        assertFalse(JsSupport.detectFrame(""))
    }

    @Test fun rubyDetectFrame() {
        assertTrue(RubySupport.detectFrame("Kernel#require"))
        assertTrue(RubySupport.detectFrame("Kernel#require_relative"))
        assertTrue(RubySupport.detectFrame("Kernel#gem_original_require"))
        assertTrue(RubySupport.detectFrame("Truffle::Boot.read_abi_version"))
        assertFalse(RubySupport.detectFrame("GraalJSEvaluator.ModuleScriptRoot@123"))
        assertFalse(RubySupport.detectFrame(""))
    }

    @Test fun jsGenerateShim() {
        val shim = JsSupport.generateShim(mapOf("add" to "add", "hello" to "hello"))
        assertTrue(shim.contains("export const add = Polyglot.import(\"add\");"))
        assertTrue(shim.contains("export const hello = Polyglot.import(\"hello\");"))
    }

    @Test fun rubyGenerateShim() {
        val shim = RubySupport.generateShim(mapOf("greet" to "greet"))
        assertTrue(shim.contains("greet = Polyglot.import(\"greet\")"))
    }

    @Test fun pythonGenerateShim() {
        val shim = PythonSupport.generateShim(mapOf("greet" to "greet"))
        assertTrue(shim.contains("greet = polyglot.import_value(\"greet\")"))
    }

    @Test fun extensionMapping() {
        val mc = Mycelium()
        assertEquals("js", mc.languageFromExtension("js"))
        assertEquals("js", mc.languageFromExtension("mjs"))
        assertEquals("js", mc.languageFromExtension("ts"))
        assertEquals("ruby", mc.languageFromExtension("rb"))
        assertEquals("python", mc.languageFromExtension("py"))
        assertEquals("llvm", mc.languageFromExtension("c"))
        assertEquals("llvm", mc.languageFromExtension("rs"))
        assertNull(mc.languageFromExtension("unknown"))
    }

    @Test fun composableLanguageSubset() {
        val jsOnly = Mycelium(listOf(JsSupport))
        assertNotNull(jsOnly.languageFromExtension("js"))
        assertNull(jsOnly.languageFromExtension("rb"))
        assertNull(jsOnly.languageFromExtension("c"))

        val jsAndC = Mycelium(listOf(JsSupport, CSupport))
        assertNotNull(jsAndC.languageFromExtension("js"))
        assertNotNull(jsAndC.languageFromExtension("c"))
        assertNull(jsAndC.languageFromExtension("rb"))
    }

    @Test fun hostLanguageDetection() {
        val mc = Mycelium()
        assertEquals(
            JsSupport,
            mc.detectHostLanguage(listOf("GraalJSEvaluator.ModuleScriptRoot@123"))
        )
        assertEquals(
            RubySupport,
            mc.detectHostLanguage(listOf("Kernel#require"))
        )
        assertNull(mc.detectHostLanguage(listOf("SomeUnknownFrame")))
    }

    @Test fun hostLanguageDetectionScansAllFrames() {
        val mc = Mycelium()
        assertEquals(
            RubySupport,
            mc.detectHostLanguage(listOf(
                "Truffle::Boot.read_abi_version",
                "<module:GemUtil>",
                "GraalJSEvaluator.ModuleScriptRoot@123"
            ))
        )
    }

    @Test fun needsCompilation() {
        assertTrue(CSupport.needsCompilation)
        assertTrue(RustSupport.needsCompilation)
        assertFalse(JsSupport.needsCompilation)
        assertFalse(RubySupport.needsCompilation)
        assertFalse(PythonSupport.needsCompilation)
    }
}

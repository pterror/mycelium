package org.mycelium.library

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class MyceliumIntegrationTest {
    private fun testResourcePath(name: String): String {
        val url = javaClass.classLoader.getResource("modulestest/$name")
            ?: throw IllegalArgumentException("Test resource not found: modulestest/$name")
        return File(url.toURI()).absolutePath
    }

    private fun runJsWithImports(mc: Mycelium, script: String) {
        val tmpFile = File.createTempFile("mycelium-test-", ".mjs")
        tmpFile.deleteOnExit()
        tmpFile.writeText(script)
        mc.runFile(tmpFile.absolutePath)
    }

    // --- Polyglot eval basics ---

    @Test fun polyglotEvalJs() {
        val mc = Mycelium()
        val result = mc.eval("js", "2 + 3")
        assertEquals(5, result.asInt())
    }

    @Test fun polyglotEvalRuby() {
        val mc = Mycelium()
        val result = mc.eval("ruby", "2 + 3")
        assertEquals(5, result.asInt())
    }

    // --- JS as host: importing each target ---

    @Test fun runJsFile() {
        val mc = Mycelium()
        mc.runFile(testResourcePath("constant.mjs"))
    }

    @Test fun jsImportsJs() {
        val mc = Mycelium()
        val path = testResourcePath("constant.mjs")
        runJsWithImports(mc, """
            import { value } from "$path";
            globalThis.__testResult = value;
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__testResult")
        assertEquals(42, result.asInt())
    }

    @Test fun jsImportsRuby() {
        val mc = Mycelium()
        val path = testResourcePath("hello.rb")
        runJsWithImports(mc, """
            import "$path";
            globalThis.__testResult = "rb_imported";
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__testResult")
        assertEquals("rb_imported", result.asString())
    }

    @Test fun jsImportsC() {
        val mc = Mycelium()
        val path = testResourcePath("add.c")
        runJsWithImports(mc, """
            import { add } from "$path";
            globalThis.__testResult = add(2, 3);
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__testResult")
        assertEquals(5, result.asInt())
    }

    @Test fun jsImportsAllAtOnce() {
        val mc = Mycelium()
        val jsPath = testResourcePath("constant.mjs")
        val rbPath = testResourcePath("hello.rb")
        val cPath = testResourcePath("add.c")
        runJsWithImports(mc, """
            import { value } from "$jsPath";
            import "$rbPath";
            import { add } from "$cPath";
            globalThis.__results = {
                jsValue: value,
                cAdd: add(10, 20),
                rbImported: true
            };
        """.trimIndent())
        val results = mc.eval("js", "globalThis.__results")
        assertEquals(42, results.getMember("jsValue").asInt())
        assertEquals(30, results.getMember("cAdd").asInt())
        assertTrue(results.getMember("rbImported").asBoolean())
    }

    @Test fun jsImportsMultipleC() {
        val mc = Mycelium()
        val addPath = testResourcePath("add.c")
        val subPath = testResourcePath("subtract.c")
        runJsWithImports(mc, """
            import { add } from "$addPath";
            import { subtract } from "$subPath";
            globalThis.__results = {
                sum: add(10, 3),
                diff: subtract(10, 3)
            };
        """.trimIndent())
        val results = mc.eval("js", "globalThis.__results")
        assertEquals(13, results.getMember("sum").asInt())
        assertEquals(7, results.getMember("diff").asInt())
    }

    // --- Ruby as host: eval into each target ---

    @Test fun runRubyFile() {
        val mc = Mycelium()
        mc.runFile(testResourcePath("hello.rb"))
    }

    @Test fun rubyEvalsJs() {
        val mc = Mycelium()
        mc.eval("ruby", """
            ${'$'}__rb_result = Polyglot.eval("js", "2 + 3")
        """.trimIndent())
        val result = mc.eval("ruby", "${'$'}__rb_result")
        assertEquals(5, result.asInt())
    }

    @Test fun rubyEvalsRuby() {
        val mc = Mycelium()
        mc.eval("ruby", """
            ${'$'}__rb_result = Polyglot.eval("ruby", "7 * 6")
        """.trimIndent())
        val result = mc.eval("ruby", "${'$'}__rb_result")
        assertEquals(42, result.asInt())
    }

    @Test fun rubyEvalsCViaModule() {
        val mc = Mycelium()
        val cPath = testResourcePath("add.c")
        mc.eval("ruby", """
            Polyglot.eval_module("js", "import { add } from '$cPath'; globalThis.__rbCResult = add(11, 22);")
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__rbCResult")
        assertEquals(33, result.asInt())
    }

    // --- JS as host: eval into each target ---

    @Test fun jsEvalsRuby() {
        val mc = Mycelium()
        mc.eval("js", """
            globalThis.__jsRbResult = Polyglot.eval("ruby", "7 * 6");
        """)
        val result = mc.eval("js", "globalThis.__jsRbResult")
        assertEquals(42, result.asInt())
    }

    @Test fun jsEvalsJs() {
        val mc = Mycelium()
        mc.eval("js", """
            globalThis.__jsJsResult = Polyglot.eval("js", "3 * 3");
        """)
        val result = mc.eval("js", "globalThis.__jsJsResult")
        assertEquals(9, result.asInt())
    }

    // --- evalModule ---

    @Test fun evalModuleJs() {
        val mc = Mycelium()
        val cPath = testResourcePath("add.c")
        mc.evalModule("js", """
            import { add } from "$cPath";
            globalThis.__evalModuleResult = add(7, 8);
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__evalModuleResult")
        assertEquals(15, result.asInt())
    }

    @Test fun evalModuleFromRuby() {
        val mc = Mycelium()
        val cPath = testResourcePath("add.c")
        mc.eval("ruby", """
            Polyglot.eval_module("js", "import { add } from '$cPath'; globalThis.__rbModResult = add(3, 4);")
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__rbModResult")
        assertEquals(7, result.asInt())
    }

    @Test fun evalModuleFromJs() {
        val mc = Mycelium()
        val cPath = testResourcePath("add.c")
        mc.eval("js", """
            Polyglot.evalModule("js", "import { add } from '$cPath'; globalThis.__jsModResult = add(5, 6);");
        """)
        val result = mc.eval("js", "globalThis.__jsModResult")
        assertEquals(11, result.asInt())
    }

    // --- Polyglot chains ---

    @Test fun chainJsToRubyToJs() {
        val mc = Mycelium()
        mc.eval("js", """
            const rbResult = Polyglot.eval("ruby", 'Polyglot.eval("js", "10 + 20")');
            globalThis.__chainResult = rbResult;
        """)
        val result = mc.eval("js", "globalThis.__chainResult")
        assertEquals(30, result.asInt())
    }

    @Test fun chainRubyToJsToRuby() {
        val mc = Mycelium()
        mc.eval("ruby", """
            ${'$'}__chain_result = Polyglot.eval("js", "Polyglot.eval('ruby', '5 * 5')")
        """.trimIndent())
        val result = mc.eval("ruby", "${'$'}__chain_result")
        assertEquals(25, result.asInt())
    }

    @Test fun chainThreeDeepNested() {
        // JS -> Ruby -> JS, single expression
        val mc = Mycelium()
        val result = mc.eval("js",
            """Polyglot.eval("ruby", 'Polyglot.eval("js", "100")')"""
        )
        assertEquals(100, result.asInt())
    }

    @Test fun chainFourDeepNested() {
        // Ruby -> JS -> Ruby -> JS, single expression
        val mc = Mycelium()
        val result = mc.eval("ruby",
            """Polyglot.eval("js", "Polyglot.eval('ruby', 'Polyglot.eval(\"js\", \"42\")')")"""
        )
        assertEquals(42, result.asInt())
    }

    @Test fun chainDeepNested() {
        // Programmatically build nested Polyglot.eval chains
        // to test arbitrary recursion depth without escaping hell
        val mc = Mycelium()
        for (depth in 3..8) {
            var code = "1"
            val outerLang = if (depth % 2 == 0) "js" else "ruby"
            var lang = if (outerLang == "js") "ruby" else "js"
            for (i in 1 until depth) {
                val escaped = code.replace("\\", "\\\\").replace("\"", "\\\"")
                code = """Polyglot.eval("$lang", "$escaped")"""
                lang = if (lang == "js") "ruby" else "js"
            }
            val result = mc.eval(outerLang, code)
            assertEquals(1, result.asInt(), "Failed at depth $depth")
        }
    }

    @Test fun chainJsToRubyToJsToC() {
        val mc = Mycelium()
        val cPath = testResourcePath("add.c")
        mc.eval("js", """
            const rbVal = Polyglot.eval("ruby", 'Polyglot.eval("js", "100")');
            globalThis.__chainPreC = rbVal;
        """)
        mc.evalModule("js", """
            import { add } from "$cPath";
            globalThis.__chainWithC = add(globalThis.__chainPreC, 50);
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__chainWithC")
        assertEquals(150, result.asInt())
    }

    @Test fun chainFourDeepAlternating() {
        val mc = Mycelium()
        // Ruby -> JS -> Ruby -> JS
        mc.eval("ruby", """
            result = Polyglot.eval("js", "Polyglot.eval('ruby', 'Polyglot.eval(\"js\", \"42\")')")
            ${'$'}__chain4 = result
        """.trimIndent())
        val result = mc.eval("ruby", "${'$'}__chain4")
        assertEquals(42, result.asInt())
    }

    @Test fun chainWithDataTransform() {
        val mc = Mycelium()
        // JS multiplies -> Ruby adds -> JS subtracts
        mc.eval("js", """
            const step1 = 6 * 7;
            const step2 = Polyglot.eval("ruby", 'Polyglot.eval("js", "' + step1 + '") + 8');
            globalThis.__chainTransform = step2 - 10;
        """)
        val result = mc.eval("js", "globalThis.__chainTransform")
        assertEquals(40, result.asInt()) // (6*7) + 8 - 10 = 40
    }

    // --- Composability ---

    @Test fun composableSubsetRejectsUnknownExtension() {
        val mc = Mycelium(listOf(JsSupport))
        assertThrows(NullPointerException::class.java) {
            mc.runFile(testResourcePath("hello.rb"))
        }
    }

    @Test fun composableJsAndCOnly() {
        val mc = Mycelium(listOf(JsSupport, CSupport))
        val cPath = testResourcePath("add.c")
        runJsWithImports(mc, """
            import { add } from "$cPath";
            globalThis.__testResult = add(100, 200);
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__testResult")
        assertEquals(300, result.asInt())
    }

    @Test fun composableJsAndRubyOnly() {
        val mc = Mycelium(listOf(JsSupport, RubySupport))
        val rbPath = testResourcePath("hello.rb")
        runJsWithImports(mc, """
            import "$rbPath";
            globalThis.__testResult = "works";
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__testResult")
        assertEquals("works", result.asString())
    }

    @Test fun composableSubsetCNotAvailable() {
        val mc = Mycelium(listOf(JsSupport, RubySupport))
        assertNull(mc.supportForExtension("c"))
        assertNull(mc.languageFromExtension("c"))
    }
}

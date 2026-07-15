package org.mycelium.library

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.graalvm.polyglot.PolyglotException
import java.io.File
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.URI

class MyceliumIntegrationTest {
    companion object {
        // GraalVM Context instances are very memory-hungry, so most tests below share a
        // single Mycelium instance (and therefore a single Context) rather than each
        // creating their own. Tests that need a different set of LanguageSupports (for
        // the "composable subset" cases) still create their own, since that's the thing
        // under test. Tests that only exercise error paths that fire before any guest
        // code runs also reuse `vm` rather than needing an isolated instance.
        @JvmStatic
        @BeforeAll
        fun setUpVm() {
            vm = Mycelium()
        }

        lateinit var vm: Mycelium
    }

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
        val mc = vm
        val result = mc.eval("js", "2 + 3")
        assertEquals(5, result.asInt())
    }

    @Test fun polyglotEvalRuby() {
        val mc = vm
        val result = mc.eval("ruby", "2 + 3")
        assertEquals(5, result.asInt())
    }

    // --- JS as host: importing each target ---

    @Test fun runJsFile() {
        val mc = vm
        mc.runFile(testResourcePath("constant.mjs"))
    }

    @Test fun jsImportsJs() {
        val mc = vm
        val path = testResourcePath("constant.mjs")
        runJsWithImports(mc, """
            import { value } from "$path";
            globalThis.__testResult = value;
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__testResult")
        assertEquals(42, result.asInt())
    }

    @Test fun jsImportsRuby() {
        val mc = vm
        val path = testResourcePath("hello.rb")
        runJsWithImports(mc, """
            import "$path";
            globalThis.__testResult = "rb_imported";
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__testResult")
        assertEquals("rb_imported", result.asString())
    }

    @Test fun jsImportsTwoRubyModulesWithSameExportName() {
        val mc = vm
        val path1 = testResourcePath("greet1.rb")
        val path2 = testResourcePath("greet2.rb")
        runJsWithImports(mc, """
            import { greet as greet1 } from "$path1";
            import { greet as greet2 } from "$path2";
            globalThis.__results = {
                greet1: greet1("world"),
                greet2: greet2("world")
            };
        """.trimIndent())
        val results = mc.eval("js", "globalThis.__results")
        assertEquals("hello, world!", results.getMember("greet1").asString())
        assertEquals("greetings, world.", results.getMember("greet2").asString())
        assertNotEquals(
            results.getMember("greet1").asString(),
            results.getMember("greet2").asString()
        )
    }

    @Test fun jsImportsC() {
        val mc = vm
        val path = testResourcePath("add.c")
        runJsWithImports(mc, """
            import { add } from "$path";
            globalThis.__testResult = add(2, 3);
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__testResult")
        assertEquals(5, result.asInt())
    }

    // rustc is not (yet) provisioned by flake.nix — only clang and GraalVM are. Skip
    // gracefully rather than failing the whole suite on machines/CI where it's absent.
    // See TODO.md ("Rust / Go support") for the follow-up to add rustc to the flake.
    private fun isCommandAvailable(cmd: String): Boolean =
        try {
            ProcessBuilder(cmd, "--version")
                .redirectErrorStream(true)
                .start()
                .also { it.inputStream.readAllBytes(); it.waitFor() }
            true
        } catch (e: java.io.IOException) {
            false
        }

    @Test fun jsImportsRust() {
        assumeTrue(isCommandAvailable("rustc"), "rustc not available in PATH — skipping Rust interop test")
        val mc = vm
        val path = testResourcePath("multiply.rs")
        runJsWithImports(mc, """
            import { multiply } from "$path";
            globalThis.__testResult = multiply(6, 7);
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__testResult")
        assertEquals(42, result.asInt())
    }

    // tinygo (not stock `go`) is required for the Go -> WASM path — see TinyGoSupport's
    // doc comment in Languages.kt for why. Not (yet) guaranteed present in every
    // environment this test suite runs in, so skip gracefully like jsImportsRust does for
    // rustc above, rather than failing the whole suite where tinygo is absent.
    @Test fun jsImportsGoWasm() {
        assumeTrue(isCommandAvailable("tinygo"), "tinygo not available in PATH — skipping Go/WASM interop test")
        val mc = vm
        val path = testResourcePath("add.go")
        runJsWithImports(mc, """
            import { add } from "$path";
            globalThis.__testResult = add(2, 3);
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__testResult")
        assertEquals(5, result.asInt())
    }

    @Test fun jsImportsAllAtOnce() {
        val mc = vm
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
        val mc = vm
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
        val mc = vm
        mc.runFile(testResourcePath("hello.rb"))
    }

    @Test fun rubyEvalsJs() {
        val mc = vm
        mc.eval("ruby", """
            ${'$'}__rb_result = Polyglot.eval("js", "2 + 3")
        """.trimIndent())
        val result = mc.eval("ruby", "${'$'}__rb_result")
        assertEquals(5, result.asInt())
    }

    @Test fun rubyEvalsRuby() {
        val mc = vm
        mc.eval("ruby", """
            ${'$'}__rb_result = Polyglot.eval("ruby", "7 * 6")
        """.trimIndent())
        val result = mc.eval("ruby", "${'$'}__rb_result")
        assertEquals(42, result.asInt())
    }

    @Test fun rubyEvalsCViaModule() {
        val mc = vm
        val cPath = testResourcePath("add.c")
        mc.eval("ruby", """
            Polyglot.eval_module("js", "import { add } from '$cPath'; globalThis.__rbCResult = add(11, 22);")
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__rbCResult")
        assertEquals(33, result.asInt())
    }

    // --- JS as host: eval into each target ---

    @Test fun jsEvalsRuby() {
        val mc = vm
        mc.eval("js", """
            globalThis.__jsRbResult = Polyglot.eval("ruby", "7 * 6");
        """)
        val result = mc.eval("js", "globalThis.__jsRbResult")
        assertEquals(42, result.asInt())
    }

    @Test fun jsEvalsJs() {
        val mc = vm
        mc.eval("js", """
            globalThis.__jsJsResult = Polyglot.eval("js", "3 * 3");
        """)
        val result = mc.eval("js", "globalThis.__jsJsResult")
        assertEquals(9, result.asInt())
    }

    // --- evalModule ---

    @Test fun evalModuleJs() {
        val mc = vm
        val cPath = testResourcePath("add.c")
        mc.evalModule("js", """
            import { add } from "$cPath";
            globalThis.__evalModuleResult = add(7, 8);
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__evalModuleResult")
        assertEquals(15, result.asInt())
    }

    @Test fun evalModuleFromRuby() {
        val mc = vm
        val cPath = testResourcePath("add.c")
        mc.eval("ruby", """
            Polyglot.eval_module("js", "import { add } from '$cPath'; globalThis.__rbModResult = add(3, 4);")
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__rbModResult")
        assertEquals(7, result.asInt())
    }

    @Test fun evalModuleFromJs() {
        val mc = vm
        val cPath = testResourcePath("add.c")
        mc.eval("js", """
            Polyglot.evalModule("js", "import { add } from '$cPath'; globalThis.__jsModResult = add(5, 6);");
        """)
        val result = mc.eval("js", "globalThis.__jsModResult")
        assertEquals(11, result.asInt())
    }

    // --- Polyglot chains ---

    @Test fun chainJsToRubyToJs() {
        val mc = vm
        mc.eval("js", """
            const rbResult = Polyglot.eval("ruby", 'Polyglot.eval("js", "10 + 20")');
            globalThis.__chainResult = rbResult;
        """)
        val result = mc.eval("js", "globalThis.__chainResult")
        assertEquals(30, result.asInt())
    }

    @Test fun chainRubyToJsToRuby() {
        val mc = vm
        mc.eval("ruby", """
            ${'$'}__chain_result = Polyglot.eval("js", "Polyglot.eval('ruby', '5 * 5')")
        """.trimIndent())
        val result = mc.eval("ruby", "${'$'}__chain_result")
        assertEquals(25, result.asInt())
    }

    @Test fun chainThreeDeepNested() {
        // JS -> Ruby -> JS, single expression
        val mc = vm
        val result = mc.eval("js",
            """Polyglot.eval("ruby", 'Polyglot.eval("js", "100")')"""
        )
        assertEquals(100, result.asInt())
    }

    @Test fun chainFourDeepNested() {
        // Ruby -> JS -> Ruby -> JS, single expression
        val mc = vm
        val result = mc.eval("ruby",
            """Polyglot.eval("js", "Polyglot.eval('ruby', 'Polyglot.eval(\"js\", \"42\")')")"""
        )
        assertEquals(42, result.asInt())
    }

    @Test fun chainDeepNested() {
        // Programmatically build nested Polyglot.eval chains
        // to test arbitrary recursion depth without escaping hell
        val mc = vm
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
        val mc = vm
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
        val mc = vm
        // Ruby -> JS -> Ruby -> JS
        mc.eval("ruby", """
            result = Polyglot.eval("js", "Polyglot.eval('ruby', 'Polyglot.eval(\"js\", \"42\")')")
            ${'$'}__chain4 = result
        """.trimIndent())
        val result = mc.eval("ruby", "${'$'}__chain4")
        assertEquals(42, result.asInt())
    }

    @Test fun chainWithDataTransform() {
        val mc = vm
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
        val ex = assertThrows(MyceliumException::class.java) {
            mc.runFile(testResourcePath("hello.rb"))
        }
        assertTrue(ex.message!!.contains("mycelium:"))
        assertTrue(ex.message!!.contains("hello.rb"))
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

    // --- Friendly error UX ---

    @Test fun runFileWithUnsupportedExtensionGivesFriendlyError() {
        // No guest code runs before this error fires, so the shared `vm` is fine here —
        // no need for an isolated Mycelium/Context just to hit this early-exit path.
        val mc = vm
        val tmpFile = File.createTempFile("mycelium-test-", ".xyz")
        tmpFile.deleteOnExit()
        tmpFile.writeText("irrelevant contents")
        val ex = assertThrows(MyceliumException::class.java) {
            mc.runFile(tmpFile.absolutePath)
        }
        assertTrue(ex.message!!.startsWith("mycelium:"))
        assertTrue(ex.message!!.contains("don't know how to run"))
        assertTrue(ex.message!!.contains(tmpFile.name))
    }

    @Test fun runNonexistentFileGivesFriendlyError() {
        // Same rationale as above: the "file not found" check happens before any
        // Context interaction, so reusing `vm` is safe and avoids another Context.
        val mc = vm
        val missingPath = File(File.createTempFile("mycelium-test-", ".mjs").also { it.delete() }.absolutePath)
        val ex = assertThrows(MyceliumException::class.java) {
            mc.runFile(missingPath.absolutePath)
        }
        assertTrue(ex.message!!.startsWith("mycelium:"))
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun missingCompilerGivesFriendlyError() {
        // Exercise Compiler.compile directly with a bogus compiler command instead of
        // spinning up a whole Mycelium/Context: the friendly-error behavior under test
        // lives entirely in Compiler.compile, so there's no need to round-trip through
        // a guest language import to observe it.
        val bogusCSupport = object : LanguageSupport {
            override val languageId = "llvm"
            override val extensions = listOf("c")
            override val compilerCommand = listOf("nonexistent-compiler-xyz")
            override fun detectFrame(frame: String) = false
            override fun isValidExportName(name: String) =
                !name.contains('.') && !name.startsWith("__")
            override fun generateShim(exports: Map<String, String>) = ""
        }
        val cFile = File.createTempFile("mycelium-nocompiler-", ".c")
        cFile.deleteOnExit()
        cFile.writeText("""
            int add_${System.nanoTime()}(int a, int b) { return a + b; }
        """.trimIndent())
        val ex = assertThrows(MyceliumException::class.java) {
            Compiler.compile(cFile.toPath(), bogusCSupport)
        }
        assertTrue(ex.message!!.startsWith("mycelium:"))
        assertTrue(ex.message!!.contains("nonexistent-compiler-xyz"))
    }

    // --- Relative import resolution ---

    @Test fun relativeImportResolvesAgainstImportingFileDirectory() {
        val tmpDir = java.nio.file.Files.createTempDirectory("mycelium-relimport-").toFile()
        tmpDir.deleteOnExit()
        val subDir = File(tmpDir, "sub")
        subDir.mkdirs()
        subDir.deleteOnExit()

        val libFile = File(subDir, "lib.mjs")
        libFile.writeText("""export const value = 99;""".trimIndent())
        libFile.deleteOnExit()

        val mainFile = File(tmpDir, "main.mjs")
        mainFile.writeText("""
            import { value } from "./sub/lib.mjs";
            globalThis.__testResult = value;
        """.trimIndent())
        mainFile.deleteOnExit()

        // The JVM's actual working directory (used to resolve relative paths
        // naively) is the project/module directory, not tmpDir. Since tmpDir
        // is unrelated to the process cwd, this proves that "./sub/lib.mjs"
        // resolves against main.mjs's own directory rather than cwd.
        assertNotEquals(tmpDir.absolutePath, File(System.getProperty("user.dir")).absolutePath)

        val mc = vm
        mc.runFile(mainFile.absolutePath)
        val result = mc.eval("js", "globalThis.__testResult")
        assertEquals(99, result.asInt())
    }

    // --- npm node_modules bare-specifier resolution ---

    private fun writeFakePackage(tmpDir: File, main: String = "index.js"): File {
        val pkgDir = File(tmpDir, "node_modules/fake-pkg")
        pkgDir.mkdirs()
        pkgDir.deleteOnExit()
        File(pkgDir, "package.json").apply {
            writeText("""{"name": "fake-pkg", "version": "1.0.0", "main": "$main"}""")
            deleteOnExit()
        }
        File(pkgDir, main).apply {
            writeText("""export const greeting = "hello from fake-pkg";""")
            deleteOnExit()
        }
        return pkgDir
    }

    @Test fun bareSpecifierResolvesAgainstNodeModules() {
        val tmpDir = java.nio.file.Files.createTempDirectory("mycelium-npm-").toFile()
        tmpDir.deleteOnExit()
        writeFakePackage(tmpDir)

        val mainFile = File(tmpDir, "main.mjs")
        mainFile.writeText("""
            import { greeting } from "fake-pkg";
            globalThis.__npmTestResult = greeting;
        """.trimIndent())
        mainFile.deleteOnExit()

        val mc = vm
        mc.runFile(mainFile.absolutePath)
        val result = mc.eval("js", "globalThis.__npmTestResult")
        assertEquals("hello from fake-pkg", result.asString())
    }

    @Test fun bareSpecifierWalksUpParentDirectoriesForNodeModules() {
        val tmpDir = java.nio.file.Files.createTempDirectory("mycelium-npm-nested-").toFile()
        tmpDir.deleteOnExit()
        writeFakePackage(tmpDir)

        val subDir = File(tmpDir, "src/nested")
        subDir.mkdirs()
        subDir.deleteOnExit()

        val mainFile = File(subDir, "main.mjs")
        mainFile.writeText("""
            import { greeting } from "fake-pkg";
            globalThis.__npmNestedResult = greeting;
        """.trimIndent())
        mainFile.deleteOnExit()

        val mc = vm
        mc.runFile(mainFile.absolutePath)
        val result = mc.eval("js", "globalThis.__npmNestedResult")
        assertEquals("hello from fake-pkg", result.asString())
    }

    @Test fun bareSpecifierUsesExportsFieldOverMain() {
        val tmpDir = java.nio.file.Files.createTempDirectory("mycelium-npm-exports-").toFile()
        tmpDir.deleteOnExit()
        val pkgDir = File(tmpDir, "node_modules/exports-pkg")
        pkgDir.mkdirs()
        pkgDir.deleteOnExit()
        File(pkgDir, "package.json").apply {
            writeText(
                """
                {
                  "name": "exports-pkg",
                  "main": "wrong.js",
                  "exports": { ".": { "import": "esm.js", "default": "wrong.js" } }
                }
                """.trimIndent()
            )
            deleteOnExit()
        }
        File(pkgDir, "esm.js").apply {
            writeText("""export const value = "from exports field";""")
            deleteOnExit()
        }

        val mainFile = File(tmpDir, "main.mjs")
        mainFile.writeText("""
            import { value } from "exports-pkg";
            globalThis.__npmExportsResult = value;
        """.trimIndent())
        mainFile.deleteOnExit()

        val mc = vm
        mc.runFile(mainFile.absolutePath)
        val result = mc.eval("js", "globalThis.__npmExportsResult")
        assertEquals("from exports field", result.asString())
    }

    @Test fun hashSubpathImportResolves() {
        val tmpDir = java.nio.file.Files.createTempDirectory("mycelium-hash-imports-").toFile()
        tmpDir.deleteOnExit()
        File(tmpDir, "package.json").apply {
            writeText(
                """
                {
                  "name": "hash-imports-pkg",
                  "imports": {
                    "#greeting": "./lib/greeting.js",
                    "#conditional": { "node": "./lib/node-only.js", "default": "./lib/fallback.js" }
                  }
                }
                """.trimIndent()
            )
            deleteOnExit()
        }
        val libDir = File(tmpDir, "lib")
        libDir.mkdirs()
        libDir.deleteOnExit()
        File(libDir, "greeting.js").apply {
            writeText("""export const greeting = "hello from #greeting";""")
            deleteOnExit()
        }
        File(libDir, "node-only.js").apply {
            writeText("""export const value = "from node condition";""")
            deleteOnExit()
        }

        val mainFile = File(tmpDir, "main.mjs")
        mainFile.writeText(
            """
            import { greeting } from "#greeting";
            import { value } from "#conditional";
            globalThis.__hashImportGreeting = greeting;
            globalThis.__hashImportConditional = value;
            """.trimIndent()
        )
        mainFile.deleteOnExit()

        val mc = vm
        mc.runFile(mainFile.absolutePath)
        assertEquals("hello from #greeting", mc.eval("js", "globalThis.__hashImportGreeting").asString())
        assertEquals("from node condition", mc.eval("js", "globalThis.__hashImportConditional").asString())
    }

    // --- CommonJS require() (js.commonjs-require experimental option) ---
    //
    // Prototyped and reverted — see the comment on Mycelium.context. Enabling
    // js.commonjs-require replaces GraalJS's ES module loader with one (Node-compatible
    // NpmCompatibleESModuleLoader) that rejects any import specifier whose extension
    // isn't on its own hardcoded whitelist (js/mjs/cjs/json/node) with "Unsupported file
    // extension" *before* InterceptingFileSystem.newByteChannel ever runs. That broke
    // every cross-language import (.c/.rb/.rs) and the TypeScript-via-esbuild import
    // (.ts) below in one shot — 16 of 62 tests failed with the option enabled. No test
    // exercises it here because it cannot be safely left on; see cjs-module.js in
    // test resources and TODO.md for what was learned:
    //   - `require()` IS injected as a global and DOES successfully load a CJS module —
    //     but only via an absolute path.
    //   - Relative specifiers (`require("./foo.js")`) resolve against the fixed
    //     `js.commonjs-require-cwd` the Context was built with, not against the
    //     requiring file's own directory (confirmed experimentally: identical relative
    //     require() succeeds when the target happens to sit directly under
    //     commonjs-require-cwd, and fails everywhere else with "Cannot load script").
    //     Since that cwd is a Context-build-time option, it can't be re-pointed per
    //     `runFile` call without rebuilding the Context per entry point, which conflicts
    //     with Mycelium's single shared Context.

    // --- TypeScript via esbuild ---

    @Test fun jsImportsTypeScript() {
        val mc = vm
        val path = testResourcePath("typed.ts")
        runJsWithImports(mc, """
            import { add } from "$path";
            globalThis.__tsResult = add(4, 5);
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__tsResult")
        assertEquals(9, result.asInt())
    }

    // --- CJS -> ESM transpilation (via esbuild, see CjsCompiler) ---

    @Test fun jsImportsCjsModule() {
        val mc = vm
        val path = testResourcePath("cjs-add.js")
        runJsWithImports(mc, """
            import cjsAdd from "$path";
            globalThis.__cjsResult = cjsAdd.add(4, 5);
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__cjsResult")
        assertEquals(9, result.asInt())
    }

    @Test fun jsImportsCjsModuleWithExportsAssignments() {
        val mc = vm
        val path = testResourcePath("cjs-module.js")
        runJsWithImports(mc, """
            import cjsModule from "$path";
            globalThis.__cjsAddResult = cjsModule.add(2, 3);
            globalThis.__cjsGreeting = cjsModule.greeting;
        """.trimIndent())
        assertEquals(5, mc.eval("js", "globalThis.__cjsAddResult").asInt())
        assertEquals("hello from CJS", mc.eval("js", "globalThis.__cjsGreeting").asString())
    }

    @Test fun cjsModuleCanRequireRelativeCjsModule() {
        val mc = vm
        val path = testResourcePath("cjs-requires-relative.js")
        runJsWithImports(mc, """
            import mod from "$path";
            globalThis.__cjsRequireResult = mod.addTwice(3, 4);
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__cjsRequireResult")
        assertEquals(14, result.asInt())
    }

    // --- CJS module cache (see InterceptingFileSystem.cjsModuleCache) ---

    @Test fun cjsModuleCacheReturnsSameObject() {
        val mc = vm
        val path = testResourcePath("cjs-add.js")
        val dir = File(path).parentFile.absolutePath
        mc.eval("js", "globalThis.__cjsA = globalThis.__mycelium_require__(\"$path\", \"$dir\")")
        mc.eval("js", "globalThis.__cjsB = globalThis.__mycelium_require__(\"$path\", \"$dir\")")
        val same = mc.eval("js", "globalThis.__cjsA === globalThis.__cjsB")
        assertEquals(true, same.asBoolean())
    }

    @Test fun cjsCircularRequire() {
        val mc = vm
        val path = testResourcePath("circular-a.js")
        val dir = File(path).parentFile.absolutePath
        mc.eval("js", "globalThis.__circResult = globalThis.__mycelium_require__(\"$path\", \"$dir\")")
        val result = mc.eval("js", "JSON.stringify(globalThis.__circResult)")
        assertEquals("""{"loaded":true,"bSeenAtLoadTime":true}""", result.asString())
    }

    // --- Node stdlib `path` module ---

    @Test fun importPathModule() {
        val mc = vm
        runJsWithImports(mc, """
            import path from 'path';
            globalThis.__pathJoinResult = path.join('/foo', 'bar', 'baz.js');
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__pathJoinResult")
        assertEquals("/foo/bar/baz.js", result.asString())
    }

    @Test fun importNodePrefixedPath() {
        val mc = vm
        runJsWithImports(mc, """
            import { dirname, basename, extname } from 'node:path';
            globalThis.__pathPartsResult =
                JSON.stringify([dirname('/foo/bar.js'), basename('/foo/bar.js'), extname('/foo/bar.js')]);
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__pathPartsResult")
        assertEquals("""["/foo","bar.js",".js"]""", result.asString())
    }

    @Test fun cjsModuleCanRequirePathCoreModule() {
        val mc = vm
        val tmpDir = java.nio.file.Files.createTempDirectory("mycelium-cjs-require-path-").toFile()
        tmpDir.deleteOnExit()
        val cjsFile = File(tmpDir, "uses-path.js")
        cjsFile.writeText("""
            const path = require('path');
            module.exports = { joined: path.join('a', 'b', 'c.js') };
        """.trimIndent())
        cjsFile.deleteOnExit()

        runJsWithImports(mc, """
            import mod from "${cjsFile.absolutePath}";
            globalThis.__cjsPathResult = mod.joined;
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__cjsPathResult")
        assertEquals("a/b/c.js", result.asString())
    }

    @Test fun nonexistentBareSpecifierGivesFriendlyError() {
        val tmpDir = java.nio.file.Files.createTempDirectory("mycelium-npm-missing-").toFile()
        tmpDir.deleteOnExit()
        // An (empty) node_modules directory marks this as an npm-style project — that's
        // what makes a missing package worth a friendly error rather than silently falling
        // back to plain-path behavior (see resolveNodeModule's NoNodeModulesTree case).
        val nodeModules = File(tmpDir, "node_modules")
        nodeModules.mkdirs()
        nodeModules.deleteOnExit()

        val mainFile = File(tmpDir, "main.mjs")
        mainFile.writeText("""
            import "does-not-exist-pkg";
        """.trimIndent())
        mainFile.deleteOnExit()

        val mc = vm
        val ex = assertThrows(PolyglotException::class.java) {
            mc.runFile(mainFile.absolutePath)
        }
        // On GraalVM 24.1.1, a host exception thrown from a host callback during JS module
        // resolution surfaced as ex.isHostException == true, unwrappable via
        // ex.asHostException(), with the original message text intact. On 24.2.1 the same
        // throw surfaced as a *guest* exception instead (isHostException == false), with
        // ex.message reduced to the interop toString of the wrapped host object
        // ("JavaObject[...]") rather than the original message text. On 25.0.0 it reverted
        // back to the 24.1.1 shape (isHostException == true, original message text intact).
        // No Mycelium production code depends on isHostException (only this test did), so
        // this is purely an assertion update, not a functional fix.
        assertTrue(ex.isHostException)
        assertTrue(ex.asHostException() is MyceliumException)
        assertTrue(ex.message?.contains("cannot find module") == true)
    }

    // --- Node stdlib `events` module ---

    @Test fun importEventsModule() {
        val mc = vm
        runJsWithImports(mc, """
            import { EventEmitter } from 'events';
            const emitter = new EventEmitter();
            let received = null;
            emitter.on('greet', (name) => { received = 'hello ' + name; });
            emitter.emit('greet', 'world');
            globalThis.__eventsResult = received;
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__eventsResult")
        assertEquals("hello world", result.asString())
    }

    // Mirrors Node's actual `events` module shape: `require('events')` (here, the ESM
    // default export) is the `EventEmitter` constructor itself, with `EventEmitter` also
    // attached to it as a self-referencing static property — see events.mjs's own doc
    // comment on `EventEmitter.EventEmitter = EventEmitter`. Packages that destructure
    // `const { EventEmitter } = require('events')` and packages that use the module's
    // default export directly both need to land on the exact same constructor.
    @Test fun eventEmitterSelfReference() {
        val mc = vm
        runJsWithImports(mc, """
            import events from 'events';
            globalThis.__eventsSelfRef = events.EventEmitter === events;
        """.trimIndent())
        assertTrue(mc.eval("js", "globalThis.__eventsSelfRef").asBoolean())
    }

    // --- Node stdlib `util` module ---

    @Test fun importUtilModule() {
        val mc = vm
        runJsWithImports(mc, """
            import util from 'util';
            globalThis.__utilResult = util.format('%s is %d years old', 'Bob', 42);
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__utilResult")
        assertEquals("Bob is 42 years old", result.asString())
    }

    // --- Node stdlib `os` module ---

    @Test fun importOsModule() {
        val mc = vm
        runJsWithImports(mc, """
            import os from 'os';
            globalThis.__osPlatform = os.platform();
            globalThis.__osHomedir = os.homedir();
        """.trimIndent())
        val platform = mc.eval("js", "globalThis.__osPlatform")
        val homedir = mc.eval("js", "globalThis.__osHomedir")
        assertTrue(platform.isString && platform.asString().isNotEmpty())
        assertTrue(homedir.isString && homedir.asString().isNotEmpty())
    }

    // --- Node stdlib `fs` module ---

    // --- --yolo auto-install ---

    // Real network + npm dependent: creates a fresh temp project with no node_modules,
    // imports a tiny real npm package, and lets --yolo shell out to `npm install` to
    // fetch it before retrying resolution. Skipped (not failed) wherever npm isn't on
    // PATH, mirroring jsImportsRust's assumeTrue-gated skip for rustc above.
    @Test fun yoloAutoInstallsMissingPackage() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping --yolo network test")

        val tmpDir = java.nio.file.Files.createTempDirectory("mycelium-yolo-").toFile()
        tmpDir.deleteOnExit()

        val mainFile = File(tmpDir, "main.mjs")
        mainFile.writeText(
            """
            import isOdd from "is-odd";
            globalThis.__yoloResult = isOdd(3);
            """.trimIndent()
        )
        mainFile.deleteOnExit()

        val mc = Mycelium(yolo = true)
        mc.runFile(mainFile.absolutePath)
        val result = mc.eval("js", "globalThis.__yoloResult")
        assertTrue(result.asBoolean())
    }

    @Test fun importFsModule() {
        val mc = vm
        val tmpFile = java.nio.file.Files.createTempFile("mycelium-fs-test-", ".txt").toFile()
        tmpFile.deleteOnExit()

        runJsWithImports(mc, """
            import fs from 'fs';
            fs.writeFileSync("${tmpFile.absolutePath}", "hello from mycelium fs");
            globalThis.__fsResult = fs.readFileSync("${tmpFile.absolutePath}", "utf8");
        """.trimIndent())
        val result = mc.eval("js", "globalThis.__fsResult")
        assertEquals("hello from mycelium fs", result.asString())
    }

    // fs's createWriteStream/createReadStream open their backing fd asynchronously (a
    // microtask 'open' event, mirroring real Node), and every subsequent step —
    // read/write chunks, 'finish'/'end'/'close' — is itself microtask-based too (see
    // fs.mjs's `nextTick`), so this fully settles before `runFile` returns, same as the
    // stream.mjs-only tests above.
    @Test fun fsCreateWriteStreamAndReadStreamRoundTrip() {
        val mc = vm
        val tmpFile = java.nio.file.Files.createTempFile("mycelium-fs-stream-test-", ".txt").toFile()
        tmpFile.delete()
        tmpFile.deleteOnExit()

        runJsWithImports(mc, """
            import fs from 'fs';

            const ws = fs.createWriteStream("${tmpFile.absolutePath}");
            let opened = false;
            let finished = false;
            ws.on('open', () => { opened = true; });
            ws.on('finish', () => { finished = true; });
            ws.write('hello ');
            ws.write('from ');
            ws.end('write stream', () => {
                const rs = fs.createReadStream("${tmpFile.absolutePath}");
                const chunks = [];
                let ended = false;
                rs.on('data', (chunk) => chunks.push(chunk));
                rs.on('end', () => { ended = true; });
                globalThis.__fsStreamResult = () => {
                    let s = '';
                    for (const chunk of chunks) {
                        for (let i = 0; i < chunk.length; i++) s += String.fromCharCode(chunk[i]);
                    }
                    return s;
                };
                globalThis.__fsStreamEnded = () => ended;
            });
            globalThis.__fsStreamOpened = () => opened;
            globalThis.__fsStreamFinished = () => finished;
        """.trimIndent())
        assertTrue(mc.eval("js", "globalThis.__fsStreamOpened()").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__fsStreamFinished()").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__fsStreamEnded()").asBoolean())
        assertEquals(
            "hello from write stream",
            mc.eval("js", "globalThis.__fsStreamResult()").asString()
        )
    }

    @Test fun fsOpenSyncReadWriteCloseFdApi() {
        val mc = vm
        val tmpFile = java.nio.file.Files.createTempFile("mycelium-fs-fd-test-", ".txt").toFile()
        tmpFile.deleteOnExit()

        runJsWithImports(mc, """
            import fs from 'fs';

            const fd = fs.openSync("${tmpFile.absolutePath}", 'w');
            const data = 'fd based write';
            fs.writeSync(fd, data);
            fs.closeSync(fd);

            const readFd = fs.openSync("${tmpFile.absolutePath}", 'r');
            const buf = new Array(data.length).fill(0);
            const n = fs.readSync(readFd, buf, 0, data.length, 0);
            fs.closeSync(readFd);

            let s = '';
            for (let i = 0; i < n; i++) s += String.fromCharCode(buf[i]);
            globalThis.__fsFdResult = s;
            globalThis.__fsFdBytesRead = n;
        """.trimIndent())
        assertEquals("fd based write", mc.eval("js", "globalThis.__fsFdResult").asString())
        assertEquals(14, mc.eval("js", "globalThis.__fsFdBytesRead").asInt())
    }

    // --- Node stdlib `buffer` module ---

    @Test fun importBufferModule() {
        val mc = vm
        runJsWithImports(mc, """
            import { Buffer } from 'buffer';
            const hex = Buffer.from('hello').toString('hex');
            const concatLen = Buffer.concat([Buffer.from('ab'), Buffer.from('cd')]).toString('utf8');
            const b64 = Buffer.from('hello').toString('base64');
            const roundTrip = Buffer.from(b64, 'base64').toString('utf8');
            globalThis.__bufHex = hex;
            globalThis.__bufConcat = concatLen;
            globalThis.__bufRoundTrip = roundTrip;
            globalThis.__bufIsBuffer = Buffer.isBuffer(Buffer.alloc(3));
            globalThis.__bufAlloc = JSON.stringify(Array.from(Buffer.alloc(3, 1)));
        """.trimIndent())
        assertEquals("68656c6c6f", mc.eval("js", "globalThis.__bufHex").asString())
        assertEquals("abcd", mc.eval("js", "globalThis.__bufConcat").asString())
        assertEquals("hello", mc.eval("js", "globalThis.__bufRoundTrip").asString())
        assertTrue(mc.eval("js", "globalThis.__bufIsBuffer").asBoolean())
        assertEquals("[1,1,1]", mc.eval("js", "globalThis.__bufAlloc").asString())
    }

    @Test fun bufferTypedReadWrite() {
        val mc = vm
        runJsWithImports(mc, """
            import { Buffer } from 'buffer';

            const buf = Buffer.alloc(10);
            buf.writeUInt32BE(0x01020304, 0);
            buf.writeInt16LE(-1234, 4);
            buf.writeFloatBE(1.5, 6);

            globalThis.__u32be = buf.readUInt32BE(0);
            globalThis.__i16le = buf.readInt16LE(4);

            const bigBuf = Buffer.alloc(8);
            bigBuf.writeBigUInt64BE(1n, 0);
            globalThis.__bigU64 = bigBuf.readBigUInt64BE(0).toString();

            const negBig = Buffer.alloc(8);
            negBig.writeBigInt64LE(-5n, 0);
            globalThis.__bigI64 = negBig.readBigInt64LE(0).toString();
        """.trimIndent())
        assertEquals(0x01020304, mc.eval("js", "globalThis.__u32be").asInt())
        assertEquals(-1234, mc.eval("js", "globalThis.__i16le").asInt())
        assertEquals("1", mc.eval("js", "globalThis.__bigU64").asString())
        assertEquals("-5", mc.eval("js", "globalThis.__bigI64").asString())
    }

    @Test fun bufferSwapAndLastIndexOfAndStatics() {
        val mc = vm
        runJsWithImports(mc, """
            import { Buffer } from 'buffer';

            const swap16Buf = Buffer.from([0x01, 0x02, 0x03, 0x04]);
            swap16Buf.swap16();
            globalThis.__swap16 = JSON.stringify(Array.from(swap16Buf));

            const swap32Buf = Buffer.from([0x01, 0x02, 0x03, 0x04]);
            swap32Buf.swap32();
            globalThis.__swap32 = JSON.stringify(Array.from(swap32Buf));

            const lastIdxBuf = Buffer.from('abcabc');
            globalThis.__lastIndexOf = lastIdxBuf.lastIndexOf('bc');

            globalThis.__bufferOf = JSON.stringify(Array.from(Buffer.of(1, 2, 3)));

            const typed = new Uint8Array([9, 8, 7, 6]);
            globalThis.__copyBytesFrom = JSON.stringify(Array.from(Buffer.copyBytesFrom(typed, 1, 2)));

            globalThis.__toJSON = JSON.stringify(Buffer.from([1, 2, 3]).toJSON());
        """.trimIndent())
        assertEquals("[2,1,4,3]", mc.eval("js", "globalThis.__swap16").asString())
        assertEquals("[4,3,2,1]", mc.eval("js", "globalThis.__swap32").asString())
        assertEquals(4, mc.eval("js", "globalThis.__lastIndexOf").asInt())
        assertEquals("[1,2,3]", mc.eval("js", "globalThis.__bufferOf").asString())
        assertEquals("[8,7]", mc.eval("js", "globalThis.__copyBytesFrom").asString())
        assertEquals("{\"type\":\"Buffer\",\"data\":[1,2,3]}", mc.eval("js", "globalThis.__toJSON").asString())
    }

    // --- Node stdlib `url` module ---

    @Test fun importUrlModule() {
        val mc = vm
        runJsWithImports(mc, """
            import url, { URL } from 'url';
            const parsed = url.parse('https://user:pass@example.com:8080/path?a=1#frag');
            globalThis.__urlParsedHost = parsed.hostname;
            globalThis.__urlParsedPort = parsed.port;
            globalThis.__urlParsedPath = parsed.pathname;
            const u = new URL('https://example.com/foo?x=1');
            globalThis.__urlHref = u.href;
            globalThis.__urlHost = u.hostname;
        """.trimIndent())
        assertEquals("example.com", mc.eval("js", "globalThis.__urlParsedHost").asString())
        assertEquals("8080", mc.eval("js", "globalThis.__urlParsedPort").asString())
        assertEquals("/path", mc.eval("js", "globalThis.__urlParsedPath").asString())
        assertEquals("https://example.com/foo?x=1", mc.eval("js", "globalThis.__urlHref").asString())
        assertEquals("example.com", mc.eval("js", "globalThis.__urlHost").asString())
    }

    // --- Node stdlib `stream` module ---

    @Test fun importStreamModule() {
        val mc = vm
        runJsWithImports(mc, """
            import { Readable, Writable } from 'stream';

            const chunks = [];
            const writable = new Writable({
                write(chunk, encoding, callback) {
                    chunks.push(chunk);
                    callback();
                }
            });

            const source = ['hello', ' ', 'world'];
            const readable = new Readable({
                read() {
                    if (source.length === 0) {
                        this.push(null);
                    } else {
                        this.push(source.shift());
                    }
                }
            });

            let finished = false;
            writable.on('finish', () => { finished = true; });
            readable.pipe(writable);

            // All of Readable/Writable's event delivery here is microtask-based (see
            // stream.mjs's `nextTick`, which is a plain `Promise.resolve().then`), and
            // GraalJS drains the microtask queue to completion before a top-level
            // `context.eval` call returns — so by the time `runFile` returns below, the
            // whole pipe chain (including the final 'finish' event) has already settled;
            // no macrotask timer is needed to observe the end state.
            globalThis.__streamData = () => chunks.join('');
            globalThis.__streamFinished = () => finished;
        """.trimIndent())
        assertEquals("hello world", mc.eval("js", "globalThis.__streamData()").asString())
        assertTrue(mc.eval("js", "globalThis.__streamFinished()").asBoolean())
    }

    // Mirrors Node's actual `stream` module shape: `require('stream')` is the `Stream`
    // constructor itself, with `Readable`/`Writable`/`Duplex`/`Transform`/`PassThrough`
    // attached as static properties (`util.inherits(SendStream, Stream)`-style consumers
    // need `Stream` itself; `{ Readable } = require('stream')`-style consumers need the
    // static properties) — see stream.mjs's own doc comment on `Stream.Readable = Readable`
    // etc.
    @Test fun streamStaticProperties() {
        val mc = vm
        runJsWithImports(mc, """
            import Stream from 'stream';
            globalThis.__streamReadableType = typeof Stream.Readable;
            globalThis.__streamWritableType = typeof Stream.Writable;
            globalThis.__streamDuplexType = typeof Stream.Duplex;
            globalThis.__streamTransformType = typeof Stream.Transform;
            globalThis.__streamPassThroughType = typeof Stream.PassThrough;
        """.trimIndent())
        assertEquals("function", mc.eval("js", "globalThis.__streamReadableType").asString())
        assertEquals("function", mc.eval("js", "globalThis.__streamWritableType").asString())
        assertEquals("function", mc.eval("js", "globalThis.__streamDuplexType").asString())
        assertEquals("function", mc.eval("js", "globalThis.__streamTransformType").asString())
        assertEquals("function", mc.eval("js", "globalThis.__streamPassThroughType").asString())
    }

    @Test fun streamReadableDestroyLifecycle() {
        val mc = vm
        runJsWithImports(mc, """
            import { Readable } from 'stream';

            const readable = new Readable({ read() {} });
            let sawError = null;
            let sawClose = false;
            readable.on('error', (err) => { sawError = err.message; });
            readable.on('close', () => { sawClose = true; });

            globalThis.__readableDestroyedBefore = readable.destroyed;
            readable.destroy(new Error('boom'));
            globalThis.__readableDestroyedAfter = () => readable.destroyed;
            globalThis.__readableClosed = () => readable.closed;
            globalThis.__readableErroredMessage = () => (readable.errored ? readable.errored.message : null);
            globalThis.__readableSawError = () => sawError;
            globalThis.__readableSawClose = () => sawClose;
            globalThis.__readableGetter = () => readable.readable;
        """.trimIndent())
        assertFalse(mc.eval("js", "globalThis.__readableDestroyedBefore").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__readableDestroyedAfter()").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__readableClosed()").asBoolean())
        assertEquals("boom", mc.eval("js", "globalThis.__readableErroredMessage()").asString())
        assertEquals("boom", mc.eval("js", "globalThis.__readableSawError()").asString())
        assertTrue(mc.eval("js", "globalThis.__readableSawClose()").asBoolean())
        assertFalse(mc.eval("js", "globalThis.__readableGetter()").asBoolean())
    }

    @Test fun streamReadableSetEncodingAndProperties() {
        val mc = vm
        runJsWithImports(mc, """
            import { Readable } from 'stream';

            const chunks = [];
            const readable = new Readable({ read() {} });
            readable.setEncoding('utf8');
            readable.on('data', (chunk) => { chunks.push(typeof chunk); });
            readable.push(Buffer.from('hi'));
            readable.push(null);

            globalThis.__readableHighWaterMark = readable.readableHighWaterMark;
            globalThis.__readableObjectMode = readable.readableObjectMode;
            globalThis.__readableChunkTypes = () => JSON.stringify(chunks);
            globalThis.__readableEnded = () => readable.readableEnded;
        """.trimIndent())
        assertEquals(16384, mc.eval("js", "globalThis.__readableHighWaterMark").asInt())
        assertFalse(mc.eval("js", "globalThis.__readableObjectMode").asBoolean())
        assertEquals("[\"string\"]", mc.eval("js", "globalThis.__readableChunkTypes()").asString())
        assertTrue(mc.eval("js", "globalThis.__readableEnded()").asBoolean())
    }

    @Test fun streamWritableDestroyLifecycle() {
        val mc = vm
        runJsWithImports(mc, """
            import { Writable } from 'stream';

            const writable = new Writable({ write(chunk, enc, cb) { cb(); } });
            let sawClose = false;
            writable.on('close', () => { sawClose = true; });

            globalThis.__writableBefore = writable.writable;
            writable.destroy();
            globalThis.__writableAfter = () => writable.writable;
            globalThis.__writableDestroyed = () => writable.destroyed;
            globalThis.__writableClosed = () => writable.closed;
            globalThis.__writableSawClose = () => sawClose;
            globalThis.__writableHighWaterMark = writable.writableHighWaterMark;
            globalThis.__writableCorked = () => { writable.cork(); return writable.writableCorked; };
        """.trimIndent())
        assertTrue(mc.eval("js", "globalThis.__writableBefore").asBoolean())
        assertFalse(mc.eval("js", "globalThis.__writableAfter()").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__writableDestroyed()").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__writableClosed()").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__writableSawClose()").asBoolean())
        assertEquals(16384, mc.eval("js", "globalThis.__writableHighWaterMark").asInt())
        assertEquals(1, mc.eval("js", "globalThis.__writableCorked()").asInt())
    }

    @Test fun streamDuplexInheritsBothSides() {
        val mc = vm
        runJsWithImports(mc, """
            import { Duplex } from 'stream';

            const duplex = new Duplex({
                read() {},
                write(chunk, enc, cb) { cb(); },
            });
            globalThis.__duplexReadable = duplex.readable;
            globalThis.__duplexWritable = duplex.writable;
            globalThis.__duplexDestroyedBefore = duplex.destroyed;
            duplex.destroy();
            globalThis.__duplexDestroyedAfter = () => duplex.destroyed;
            globalThis.__duplexClosed = () => duplex.closed;
        """.trimIndent())
        assertTrue(mc.eval("js", "globalThis.__duplexReadable").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__duplexWritable").asBoolean())
        assertFalse(mc.eval("js", "globalThis.__duplexDestroyedBefore").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__duplexDestroyedAfter()").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__duplexClosed()").asBoolean())
    }

    @Test fun streamFinishedAndPipelineAndReadableFrom() {
        val mc = vm
        runJsWithImports(mc, """
            import { Readable, Writable, finished, pipeline } from 'stream';

            const readable = Readable.from(['a', 'b', 'c']);
            const collected = [];
            const writable = new Writable({
                write(chunk, enc, cb) { collected.push(chunk); cb(); }
            });

            let finishedCalled = false;
            let pipelineCalled = false;
            let pipelineErr = 'unset';

            pipeline(readable, writable, (err) => {
                pipelineCalled = true;
                pipelineErr = err;
            });
            finished(writable, () => { finishedCalled = true; });

            globalThis.__pipelineCollected = () => collected.join('');
            globalThis.__pipelineCalled = () => pipelineCalled;
            globalThis.__pipelineErr = () => pipelineErr;
            globalThis.__finishedCalled = () => finishedCalled;
        """.trimIndent())
        // Allow microtasks queued by pipeline/finished during the previous eval to settle.
        assertEquals("abc", mc.eval("js", "globalThis.__pipelineCollected()").asString())
        assertTrue(mc.eval("js", "globalThis.__pipelineCalled()").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__pipelineErr() === undefined || globalThis.__pipelineErr() === null").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__finishedCalled()").asBoolean())
    }

    // --- Node stdlib `crypto` module ---

    @Test fun importCryptoModule() {
        val mc = vm
        runJsWithImports(mc, """
            import crypto from 'crypto';
            globalThis.__cryptoSha256 = crypto.createHash('sha256').update('hello').digest('hex');
            globalThis.__cryptoHmac = crypto.createHmac('sha256', 'secret').update('hello').digest('hex');
            globalThis.__cryptoRandomBytesLen = crypto.randomBytes(16).length;
            globalThis.__cryptoUUID = crypto.randomUUID();
            globalThis.__cryptoTimingSafeEqual = crypto.timingSafeEqual(
                crypto.createHash('md5').update('a').digest(),
                crypto.createHash('md5').update('a').digest()
            );
        """.trimIndent())
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            mc.eval("js", "globalThis.__cryptoSha256").asString()
        )
        assertEquals(16, mc.eval("js", "globalThis.__cryptoRandomBytesLen").asInt())
        assertTrue(mc.eval("js", "globalThis.__cryptoUUID").asString().matches(Regex("[0-9a-f-]{36}")))
        assertTrue(mc.eval("js", "globalThis.__cryptoTimingSafeEqual").asBoolean())
    }

    @Test fun cryptoPbkdf2SyncDerivesKey() {
        val mc = vm
        // RFC 6070 test vector 1: PBKDF2-HMAC-SHA1("password", "salt", 1, 20).
        runJsWithImports(mc, """
            import crypto from 'crypto';
            globalThis.__pbkdf2 = crypto.pbkdf2Sync('password', 'salt', 1, 20, 'sha1').toString('hex');
        """.trimIndent())
        assertEquals(
            "0c60c80f961f0e71f3a9b524af6012062fe037a6",
            mc.eval("js", "globalThis.__pbkdf2").asString()
        )
    }

    @Test fun cryptoSignAndVerifyRsa() {
        val mc = vm
        runJsWithImports(mc, """
            import crypto from 'crypto';
            const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
            const data = 'the quick brown fox';
            const signer = crypto.createSign('sha256');
            signer.update(data);
            const signature = signer.sign(privateKey);
            const verifier = crypto.createVerify('sha256');
            verifier.update(data);
            globalThis.__rsaVerifyOk = verifier.verify(publicKey, signature);

            const tamperedVerifier = crypto.createVerify('sha256');
            tamperedVerifier.update(data + '!');
            globalThis.__rsaVerifyTampered = tamperedVerifier.verify(publicKey, signature);
        """.trimIndent())
        assertTrue(mc.eval("js", "globalThis.__rsaVerifyOk").asBoolean())
        assertFalse(mc.eval("js", "globalThis.__rsaVerifyTampered").asBoolean())
    }

    @Test fun cryptoSignAndVerifyEc() {
        val mc = vm
        runJsWithImports(mc, """
            import crypto from 'crypto';
            const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
            const data = 'ec signed message';
            const signature = crypto.sign('sha256', Buffer.from(data), privateKey);
            globalThis.__ecVerifyOk = crypto.verify('sha256', Buffer.from(data), publicKey, signature);
            globalThis.__ecVerifyTampered = crypto.verify('sha256', Buffer.from(data + 'x'), publicKey, signature);
        """.trimIndent())
        assertTrue(mc.eval("js", "globalThis.__ecVerifyOk").asBoolean())
        assertFalse(mc.eval("js", "globalThis.__ecVerifyTampered").asBoolean())
    }

    @Test fun cryptoGenerateKeyPairSync() {
        val mc = vm
        runJsWithImports(mc, """
            import crypto from 'crypto';
            const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', {
                modulusLength: 2048,
                publicKeyEncoding: { type: 'spki', format: 'pem' },
                privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
            });
            globalThis.__pubIsPem = typeof publicKey === 'string' && publicKey.includes('BEGIN PUBLIC KEY');
            globalThis.__privIsPem = typeof privateKey === 'string' && privateKey.includes('BEGIN PRIVATE KEY');

            const keyObjectPair = crypto.generateKeyPairSync('ec', { namedCurve: 'secp384r1' });
            globalThis.__pubKeyObjType = keyObjectPair.publicKey.type;
            globalThis.__privKeyObjType = keyObjectPair.privateKey.type;
            globalThis.__ecAsymmetricType = keyObjectPair.publicKey.asymmetricKeyType;
        """.trimIndent())
        assertTrue(mc.eval("js", "globalThis.__pubIsPem").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__privIsPem").asBoolean())
        assertEquals("public", mc.eval("js", "globalThis.__pubKeyObjType").asString())
        assertEquals("private", mc.eval("js", "globalThis.__privKeyObjType").asString())
        assertEquals("ec", mc.eval("js", "globalThis.__ecAsymmetricType").asString())
    }

    @Test fun cryptoHkdfSyncMatchesRfc5869Vector() {
        val mc = vm
        // RFC 5869 §A.1 test case 1 (HKDF-SHA256).
        runJsWithImports(mc, """
            import crypto from 'crypto';
            const ikm = Buffer.from('0b'.repeat(22), 'hex');
            const salt = Buffer.from('000102030405060708090a0b0c', 'hex');
            const info = Buffer.from('f0f1f2f3f4f5f6f7f8f9', 'hex');
            globalThis.__hkdf = crypto.hkdfSync('sha256', ikm, salt, info, 42).toString('hex');
        """.trimIndent())
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            mc.eval("js", "globalThis.__hkdf").asString()
        )
    }

    @Test fun cryptoScryptSyncMatchesRfc7914Vector() {
        val mc = vm
        // RFC 7914 §12 test vector 1: scrypt("", "", N=16, r=1, p=1, dkLen=64).
        runJsWithImports(mc, """
            import crypto from 'crypto';
            globalThis.__scrypt = crypto.scryptSync('', '', 64, { N: 16, r: 1, p: 1 }).toString('hex');
        """.trimIndent())
        assertEquals(
            "77d6576238657b203b19ca42c18a0497f16b4844e3074ae8dfdffa3fede21442" +
                "fcd0069ded0948f8326a753a0fc81f17e8d3e0fb2e0d3628cf35e20c38d18906",
            mc.eval("js", "globalThis.__scrypt").asString()
        )
    }

    @Test fun cryptoRandomFillSync() {
        val mc = vm
        runJsWithImports(mc, """
            import crypto from 'crypto';
            const buf = Buffer.alloc(32);
            crypto.randomFillSync(buf);
            globalThis.__randomFillNonZero = buf.some((b) => b !== 0);
            globalThis.__randomFillLen = buf.length;

            const buf2 = Buffer.alloc(16, 0xff);
            crypto.randomFillSync(buf2, 4, 8);
            globalThis.__randomFillPartialUntouchedStart = buf2[0] === 0xff && buf2[3] === 0xff;
            globalThis.__randomFillPartialUntouchedEnd = buf2[12] === 0xff && buf2[15] === 0xff;
        """.trimIndent())
        assertTrue(mc.eval("js", "globalThis.__randomFillNonZero").asBoolean())
        assertEquals(32, mc.eval("js", "globalThis.__randomFillLen").asInt())
        assertTrue(mc.eval("js", "globalThis.__randomFillPartialUntouchedStart").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__randomFillPartialUntouchedEnd").asBoolean())
    }

    // --- `globalThis.crypto` (Web Crypto API) ---

    @Test fun globalCryptoExists() {
        val mc = vm
        assertEquals("function", mc.eval("js", "typeof globalThis.crypto.randomUUID").asString())
        assertEquals("function", mc.eval("js", "typeof globalThis.crypto.getRandomValues").asString())
        val uuid = mc.eval("js", "globalThis.crypto.randomUUID()")
        assertTrue(uuid.asString().matches(Regex("[0-9a-f-]{36}")))
    }

    // --- `Error.captureStackTrace` polyfill (GraalJS exposes no V8-style stack-trace API
    // of its own — needed by express, via http-errors/depd) ---

    @Test fun errorCaptureStackTraceTrimsConstructorFrame() {
        val mc = vm
        val stack = mc.eval(
            "js",
            """
            function MyError(msg) {
                Error.call(this, msg);
                this.message = msg;
                this.name = 'MyError';
                Error.captureStackTrace(this, MyError);
            }
            MyError.prototype = Object.create(Error.prototype);

            function makeError() {
                return new MyError('boom');
            }
            makeError().stack;
            """.trimIndent()
        ).asString()
        assertTrue(stack.startsWith("MyError: boom"), "expected header, got: $stack")
        assertFalse(stack.contains("MyError ("), "constructor frame should be trimmed: $stack")
        assertTrue(stack.contains("makeError"), "caller frame should remain: $stack")
    }

    @Test fun errorCaptureStackTraceDefaultsStackTraceLimitToTen() {
        val mc = vm
        assertEquals(10, mc.eval("js", "Error.stackTraceLimit").asInt())
    }

    @Test fun errorCaptureStackTraceWithoutConstructorOptStillProducesStack() {
        val mc = vm
        val stack = mc.eval(
            "js",
            """
            var obj = {};
            Error.captureStackTrace(obj);
            obj.stack;
            """.trimIndent()
        ).asString()
        assertTrue(stack.startsWith("Error"), "expected a bare Error header, got: $stack")
    }

    // --- Python site-packages / Ruby gem bare-specifier resolution ---
    //
    // resolvePythonPackage/resolveRubyGem both accept an explicit `searchPaths` override
    // (defaulting to the real python3/ruby-subprocess-derived paths), which lets the
    // matching logic itself be tested against a fake site-packages/gem directory layout
    // without needing a real Python or Ruby installation. The default-parameter behavior
    // (i.e. the subprocess discovery mechanism) is covered separately below, gated with
    // assumeTrue exactly like jsImportsRust's rustc check, since python3/ruby aren't
    // provisioned by flake.nix.

    @Test fun resolvePythonPackageFindsPackageWithInitPy() {
        val siteDir = java.nio.file.Files.createTempDirectory("mycelium-site-").toFile()
        siteDir.deleteOnExit()
        val pkgDir = File(siteDir, "fakepkg")
        pkgDir.mkdirs()
        pkgDir.deleteOnExit()
        val initPy = File(pkgDir, "__init__.py")
        initPy.writeText("value = 42")
        initPy.deleteOnExit()

        val fs = InterceptingFileSystem()
        val resolved = fs.resolvePythonPackage("fakepkg", listOf(siteDir.toPath()))
        assertEquals(initPy.absoluteFile.toPath(), resolved)
    }

    @Test fun resolvePythonPackageFindsPlainModuleFile() {
        val siteDir = java.nio.file.Files.createTempDirectory("mycelium-site-").toFile()
        siteDir.deleteOnExit()
        val moduleFile = File(siteDir, "fakemodule.py")
        moduleFile.writeText("value = 1")
        moduleFile.deleteOnExit()

        val fs = InterceptingFileSystem()
        val resolved = fs.resolvePythonPackage("fakemodule", listOf(siteDir.toPath()))
        assertEquals(moduleFile.absoluteFile.toPath(), resolved)
    }

    @Test fun resolvePythonPackageReturnsNullWhenNotFound() {
        val siteDir = java.nio.file.Files.createTempDirectory("mycelium-site-").toFile()
        siteDir.deleteOnExit()

        val fs = InterceptingFileSystem()
        assertNull(fs.resolvePythonPackage("does-not-exist", listOf(siteDir.toPath())))
    }

    @Test fun resolveRubyGemFindsDirectFile() {
        val loadDir = java.nio.file.Files.createTempDirectory("mycelium-loadpath-").toFile()
        loadDir.deleteOnExit()
        val gemFile = File(loadDir, "fakegem.rb")
        gemFile.writeText("FAKEGEM_VALUE = 1")
        gemFile.deleteOnExit()

        val fs = InterceptingFileSystem()
        val resolved = fs.resolveRubyGem("fakegem", listOf(loadDir.toPath()))
        assertEquals(gemFile.absoluteFile.toPath(), resolved)
    }

    @Test fun resolveRubyGemFindsNestedGemLayout() {
        val loadDir = java.nio.file.Files.createTempDirectory("mycelium-loadpath-").toFile()
        loadDir.deleteOnExit()
        val gemSubDir = File(loadDir, "nestedgem")
        gemSubDir.mkdirs()
        gemSubDir.deleteOnExit()
        val gemFile = File(gemSubDir, "nestedgem.rb")
        gemFile.writeText("NESTEDGEM_VALUE = 1")
        gemFile.deleteOnExit()

        val fs = InterceptingFileSystem()
        val resolved = fs.resolveRubyGem("nestedgem", listOf(loadDir.toPath()))
        assertEquals(gemFile.absoluteFile.toPath(), resolved)
    }

    @Test fun resolveRubyGemReturnsNullWhenNotFound() {
        val loadDir = java.nio.file.Files.createTempDirectory("mycelium-loadpath-").toFile()
        loadDir.deleteOnExit()

        val fs = InterceptingFileSystem()
        assertNull(fs.resolveRubyGem("does-not-exist", listOf(loadDir.toPath())))
    }

    // --- `process` global ---

    @Test fun processGlobalExists() {
        val mc = vm
        val result = mc.eval("js", "typeof process")
        assertEquals("object", result.asString())
    }

    @Test fun processEnvReadsSystemEnv() {
        val mc = vm
        val result = mc.eval("js", "process.env.HOME || process.env.PATH || ''")
        assertTrue(result.asString().isNotEmpty())
    }

    @Test fun processEnvSetWorks() {
        val mc = vm
        mc.eval("js", "process.env.MYCELIUM_TEST_ENV_SET_VAR = 'bar'")
        val result = mc.eval("js", "process.env.MYCELIUM_TEST_ENV_SET_VAR")
        assertEquals("bar", result.asString())
        assertTrue(mc.eval("js", "'MYCELIUM_TEST_ENV_SET_VAR' in process.env").asBoolean())
    }

    @Test fun processCwdWorks() {
        val mc = vm
        val result = mc.eval("js", "process.cwd()")
        assertTrue(result.isString)
        assertTrue(result.asString().isNotEmpty())
    }

    @Test fun processPlatformIsLinux() {
        val mc = vm
        val result = mc.eval("js", "process.platform")
        assertEquals("linux", result.asString())
    }

    @Test fun processCoreModuleImportWorks() {
        val mc = vm
        runJsWithImports(mc, """
            import process, { platform, env } from 'process';
            globalThis.__processDefaultIsGlobal = process === globalThis.process;
            globalThis.__processPlatform = platform;
            globalThis.__processHasEnv = typeof env === 'object';
        """.trimIndent())
        assertTrue(mc.eval("js", "globalThis.__processDefaultIsGlobal").asBoolean())
        assertEquals("linux", mc.eval("js", "globalThis.__processPlatform").asString())
        assertTrue(mc.eval("js", "globalThis.__processHasEnv").asBoolean())
    }

    // --- Manifest-based (package.json) auto-install ---

    @Test fun manifestInstallRunsNpmInstall() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping manifest-install test")

        val tmpDir = java.nio.file.Files.createTempDirectory("mycelium-manifest-install-").toFile()
        tmpDir.deleteOnExit()
        File(tmpDir, "package.json").apply {
            writeText(
                """{"name": "manifest-install-test", "version": "1.0.0", "dependencies": {"is-odd": "^3.0.1"}}"""
            )
            deleteOnExit()
        }
        val mainFile = File(tmpDir, "main.mjs")
        mainFile.writeText(
            """
            import isOdd from "is-odd";
            globalThis.__manifestInstallResult = isOdd(3);
            """.trimIndent()
        )
        mainFile.deleteOnExit()

        val mc = Mycelium()
        mc.runFile(mainFile.absolutePath)
        assertTrue(File(tmpDir, "node_modules").isDirectory)
        assertTrue(File(tmpDir, "node_modules/is-odd").isDirectory)
        val result = mc.eval("js", "globalThis.__manifestInstallResult")
        assertTrue(result.asBoolean())
    }

    @Test fun noInstallFlagSkipsInstall() {
        val tmpDir = java.nio.file.Files.createTempDirectory("mycelium-manifest-noinstall-").toFile()
        tmpDir.deleteOnExit()
        File(tmpDir, "package.json").apply {
            writeText(
                """{"name": "manifest-noinstall-test", "version": "1.0.0", "dependencies": {"is-odd": "^3.0.1"}}"""
            )
            deleteOnExit()
        }
        val mainFile = File(tmpDir, "main.mjs")
        mainFile.writeText("""globalThis.__noInstallRan = true;""")
        mainFile.deleteOnExit()

        val mc = Mycelium(autoInstall = false)
        mc.runFile(mainFile.absolutePath)
        assertTrue(mc.eval("js", "globalThis.__noInstallRan").asBoolean())
        assertFalse(File(tmpDir, "node_modules").exists())
    }

    // Real python3/ruby-subprocess-dependent: verifies the "run once at startup, cache the
    // result" discovery mechanism itself actually produces a usable, non-empty list of
    // real directories on a machine where the respective runtime is installed. Skipped
    // (not failed) wherever the runtime isn't on PATH — python3/ruby aren't provisioned by
    // flake.nix (only GraalVM, clang, and rustc are), mirroring jsImportsRust's
    // assumeTrue-gated skip for rustc.
    @Test fun pythonSearchPathDiscoveryFindsRealDirectoriesWhenAvailable() {
        assumeTrue(isCommandAvailable("python3"), "python3 not available in PATH — skipping Python resolution test")

        val fs = InterceptingFileSystem()
        val paths = fs.computePythonSearchPaths()
        assertTrue(paths.isNotEmpty(), "expected python3's sys.path to yield at least one real directory")
        assertTrue(paths.all { java.nio.file.Files.isDirectory(it) })
    }

    @Test fun rubySearchPathDiscoveryFindsRealDirectoriesWhenAvailable() {
        assumeTrue(isCommandAvailable("ruby"), "ruby not available in PATH — skipping Ruby resolution test")

        val fs = InterceptingFileSystem()
        val paths = fs.computeRubySearchPaths()
        assertTrue(paths.isNotEmpty(), "expected ruby's \$LOAD_PATH to yield at least one real directory")
        assertTrue(paths.all { java.nio.file.Files.isDirectory(it) })
    }

    // --- Node stdlib `assert` module ---

    @Test fun importAssertModule() {
        val mc = vm
        runJsWithImports(mc, """
            import assert from 'assert';
            let threw = false;
            assert.strictEqual(1, 1);
            try {
                assert.strictEqual(1, 2);
            } catch (e) {
                threw = e instanceof assert.AssertionError;
            }
            globalThis.__assertThrew = threw;
            globalThis.__assertOkPasses = (function () {
                try { assert(true); return true; } catch (e) { return false; }
            })();
            globalThis.__assertDeepEqual = (function () {
                try { assert.deepStrictEqual({ a: 1, b: [1, 2] }, { a: 1, b: [1, 2] }); return true; } catch (e) { return false; }
            })();
        """.trimIndent())
        assertTrue(mc.eval("js", "globalThis.__assertThrew").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__assertOkPasses").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__assertDeepEqual").asBoolean())
    }

    // --- Node stdlib `querystring` module ---

    @Test fun importQuerystringModule() {
        val mc = vm
        runJsWithImports(mc, """
            import querystring from 'querystring';
            const parsed = querystring.parse('foo=bar&baz=qux');
            globalThis.__qsFoo = parsed.foo;
            globalThis.__qsBaz = parsed.baz;
            globalThis.__qsStringify = querystring.stringify({ a: '1', b: '2' });
            globalThis.__qsEscape = querystring.escape('a b/c');
        """.trimIndent())
        assertEquals("bar", mc.eval("js", "globalThis.__qsFoo").asString())
        assertEquals("qux", mc.eval("js", "globalThis.__qsBaz").asString())
        assertEquals("a=1&b=2", mc.eval("js", "globalThis.__qsStringify").asString())
        assertEquals("a%20b%2Fc", mc.eval("js", "globalThis.__qsEscape").asString())
    }

    // --- Node stdlib `string_decoder` module ---

    @Test fun importStringDecoderModule() {
        val mc = vm
        runJsWithImports(mc, """
            import { StringDecoder } from 'string_decoder';
            const decoder = new StringDecoder('utf8');
            // "café" in UTF-8 is [0x63, 0x61, 0x66, 0xC3, 0xA9] — split the multi-byte
            // 'é' (0xC3 0xA9) across two writes to exercise cross-chunk buffering.
            let out = decoder.write(new Uint8Array([0x63, 0x61, 0x66, 0xC3]));
            out += decoder.write(new Uint8Array([0xA9]));
            out += decoder.end();
            globalThis.__stringDecoderOut = out;
        """.trimIndent())
        assertEquals("café", mc.eval("js", "globalThis.__stringDecoderOut").asString())
    }

    // --- Node stdlib `zlib` module ---

    @Test fun importZlibModule() {
        val mc = vm
        runJsWithImports(mc, """
            import zlib from 'zlib';
            const original = 'hello mycelium, this is a gzip round trip test';
            const compressed = zlib.gzipSync(original);
            const decompressed = zlib.gunzipSync(compressed);
            let decompressedStr = '';
            for (let i = 0; i < decompressed.length; i++) decompressedStr += String.fromCharCode(decompressed[i]);
            globalThis.__zlibRoundTrip = decompressedStr;
            globalThis.__zlibCompressedIsSmallerOrEqual = compressed.length > 0;

            const deflated = zlib.deflateSync(original);
            const inflated = zlib.inflateSync(deflated);
            let inflatedStr = '';
            for (let i = 0; i < inflated.length; i++) inflatedStr += String.fromCharCode(inflated[i]);
            globalThis.__zlibDeflateRoundTrip = inflatedStr;
        """.trimIndent())
        assertEquals(
            "hello mycelium, this is a gzip round trip test",
            mc.eval("js", "globalThis.__zlibRoundTrip").asString()
        )
        assertTrue(mc.eval("js", "globalThis.__zlibCompressedIsSmallerOrEqual").asBoolean())
        assertEquals(
            "hello mycelium, this is a gzip round trip test",
            mc.eval("js", "globalThis.__zlibDeflateRoundTrip").asString()
        )
    }

    // GraalJS drains the microtask queue to completion before a top-level `context.eval`
    // call returns (see importStreamModule's comment above), and every async step in
    // zlib.mjs's streaming Transform classes and stream.mjs's Readable/Writable base
    // classes is microtask-based (`Promise.resolve().then`) rather than a real timer or
    // background thread — so the whole gzip.pipe(gunzip) chain, including the final
    // 'end' event, has already settled by the time `runFile` returns below.
    @Test fun zlibStreamingGzipGunzipRoundTrip() {
        val mc = vm
        runJsWithImports(mc, """
            import zlib from 'zlib';

            const gzip = zlib.createGzip();
            const gunzip = zlib.createGunzip();
            const chunks = [];
            let ended = false;
            gunzip.on('data', (chunk) => chunks.push(chunk));
            gunzip.on('end', () => { ended = true; });

            gzip.pipe(gunzip);
            gzip.write('hello ');
            gzip.write('streaming ');
            gzip.end('world, this is a longer message to span a couple of chunks');

            globalThis.__zlibStreamResult = () => {
                let s = '';
                for (const chunk of chunks) {
                    for (let i = 0; i < chunk.length; i++) s += String.fromCharCode(chunk[i]);
                }
                return s;
            };
            globalThis.__zlibStreamEnded = () => ended;
        """.trimIndent())
        assertEquals(
            "hello streaming world, this is a longer message to span a couple of chunks",
            mc.eval("js", "globalThis.__zlibStreamResult()").asString()
        )
        assertTrue(mc.eval("js", "globalThis.__zlibStreamEnded()").asBoolean())
    }

    @Test fun zlibStreamingDeflateInflateRoundTrip() {
        val mc = vm
        runJsWithImports(mc, """
            import zlib from 'zlib';

            const deflate = zlib.createDeflate();
            const inflate = zlib.createInflate();
            const chunks = [];
            let finished = false;
            inflate.on('data', (chunk) => chunks.push(chunk));
            inflate.on('end', () => { finished = true; });

            deflate.pipe(inflate);
            deflate.write('the quick brown fox ');
            deflate.end('jumps over the lazy dog');

            globalThis.__zlibDeflateStreamResult = () => {
                let s = '';
                for (const chunk of chunks) {
                    for (let i = 0; i < chunk.length; i++) s += String.fromCharCode(chunk[i]);
                }
                return s;
            };
            globalThis.__zlibDeflateStreamFinished = () => finished;
        """.trimIndent())
        assertEquals(
            "the quick brown fox jumps over the lazy dog",
            mc.eval("js", "globalThis.__zlibDeflateStreamResult()").asString()
        )
        assertTrue(mc.eval("js", "globalThis.__zlibDeflateStreamFinished()").asBoolean())
    }

    // --- Node stdlib `http` module ---
    //
    // Client path is verified against a plain `com.sun.net.httpserver.HttpServer` started
    // directly from Kotlin (never touching the polyglot Context), so the JS thread's
    // blocking `HttpURLConnection` call in http.mjs's ClientRequest never needs the guest
    // Context to be re-entered from another thread while it's already entered — avoiding
    // the deadlock that would occur if the JS-implemented http server's own request
    // handler (invoked on `HttpServer`'s internal dispatch thread) were on the other end
    // of that same blocking call. The server path is verified in the opposite direction:
    // JS creates an http.mjs `Server` and starts listening (a call that returns immediately
    // without blocking, so the Context is left well before any request arrives), then a
    // plain Kotlin-side `HttpURLConnection` — outside the Context entirely — drives a
    // request against it.

    @Test fun importHttpModule() {
        val mc = vm

        // Client: JS http.get() against a plain Kotlin-side server.
        val plainServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        plainServer.createContext("/ping") { exchange ->
            val body = "pong".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        plainServer.executor = null
        plainServer.start()
        try {
            val plainPort = plainServer.address.port
            runJsWithImports(mc, """
                import http from 'http';
                http.get('http://127.0.0.1:$plainPort/ping', (res) => {
                    let chunks = [];
                    res.on('data', (chunk) => chunks.push(chunk));
                    res.on('end', () => {
                        let str = '';
                        for (const chunk of chunks) {
                            for (let i = 0; i < chunk.length; i++) str += String.fromCharCode(chunk[i]);
                        }
                        globalThis.__httpClientBody = str;
                        globalThis.__httpClientStatus = res.statusCode;
                    });
                });
            """.trimIndent())
            assertEquals("pong", mc.eval("js", "globalThis.__httpClientBody").asString())
            assertEquals(200, mc.eval("js", "globalThis.__httpClientStatus").asInt())
        } finally {
            plainServer.stop(0)
        }

        // Server: JS http.createServer() driven by a plain Kotlin-side HttpURLConnection.
        //
        // Deliberately uses its own, throwaway `Mycelium` instance rather than the shared
        // `vm`: once the request below is issued, `com.sun.net.httpserver.HttpServer`'s own
        // internal dispatch thread enters the polyglot Context to run the JS request
        // handler — a legitimate one-off cross-thread handoff (GraalVM transfers context
        // ownership to whichever thread next enters, as long as no other thread is
        // concurrently inside it, which holds here since `listen()` already returned control
        // to this test thread well before the request is made). But that handoff is *not*
        // reversible without extra plumbing this module doesn't implement: after the
        // dispatch thread has entered and left, GraalVM (observed empirically) refuses to
        // transfer the Context back to the original thread, so the test thread must never
        // touch this Context again afterward — using a dedicated instance keeps that
        // one-way handoff from poisoning the shared `vm` (and therefore every other test in
        // this class, since they all reuse it). This matches real Mycelium usage, where a
        // script that starts a server and returns is done touching the Context from the
        // main thread entirely — the server's own listener thread keeps the JVM alive and
        // keeps handling requests indefinitely on its own.
        val serverMc = Mycelium()
        runJsWithImports(serverMc, """
            import http from 'http';
            globalThis.__httpServer = http.createServer((req, res) => {
                res.writeHead(200, { 'Content-Type': 'text/plain' });
                res.end('hello from mycelium http server');
            });
            globalThis.__httpServer.listen(0, '127.0.0.1', () => {
                globalThis.__httpServerReady = true;
                globalThis.__httpServerPort = globalThis.__httpServer.address().port;
            });
        """.trimIndent())
        assertTrue(serverMc.eval("js", "globalThis.__httpServerReady").asBoolean())
        val jsServerPort = serverMc.eval("js", "globalThis.__httpServerPort").asInt()

        val conn = URI("http://127.0.0.1:$jsServerPort/").toURL().openConnection() as HttpURLConnection
        try {
            assertEquals(200, conn.responseCode)
            val responseBody = conn.inputStream.readBytes().toString(Charsets.UTF_8)
            assertEquals("hello from mycelium http server", responseBody)
        } finally {
            conn.disconnect()
        }
    }

    // --- Node stdlib `http`/`https` `Agent` ---
    //
    // Our client requests are each backed by their own HttpURLConnection/HttpsURLConnection
    // (which do their own connection pooling internally at the JVM level — see http.mjs's
    // Agent doc comment), so there's no real pooling behavior to exercise here; this just
    // checks the shape (constructor options, globalAgent, methods) that consumers poke at.

    @Test fun importHttpAgentModule() {
        val mc = vm
        runJsWithImports(mc, """
            import http from 'http';
            const agent = new http.Agent({ keepAlive: true, maxSockets: 5 });
            globalThis.__httpAgentKeepAlive = agent.keepAlive;
            globalThis.__httpAgentMaxSockets = agent.maxSockets;
            globalThis.__httpAgentGetName = agent.getName({ host: 'example.com', port: 8080 });
            globalThis.__httpGlobalAgentType = typeof http.globalAgent;
            globalThis.__httpGlobalAgentKeepAlive = http.globalAgent.keepAlive;
            agent.destroy();
        """.trimIndent())
        assertTrue(mc.eval("js", "globalThis.__httpAgentKeepAlive").asBoolean())
        assertEquals(5, mc.eval("js", "globalThis.__httpAgentMaxSockets").asInt())
        assertEquals("example.com:8080", mc.eval("js", "globalThis.__httpAgentGetName").asString())
        assertEquals("object", mc.eval("js", "globalThis.__httpGlobalAgentType").asString())
        assertEquals(false, mc.eval("js", "globalThis.__httpGlobalAgentKeepAlive").asBoolean())
    }

    @Test fun importHttpsAgentModule() {
        val mc = vm
        runJsWithImports(mc, """
            import https from 'https';
            const agent = new https.Agent({ keepAlive: true });
            globalThis.__httpsAgentKeepAlive = agent.keepAlive;
            globalThis.__httpsGlobalAgentType = typeof https.globalAgent;
        """.trimIndent())
        assertTrue(mc.eval("js", "globalThis.__httpsAgentKeepAlive").asBoolean())
        assertEquals("object", mc.eval("js", "globalThis.__httpsGlobalAgentType").asString())
    }

    // --- Node stdlib `http2` module (client side) ---
    //
    // Verified against a plain `com.sun.net.httpserver.HttpServer` (HTTP/1.1-only) rather
    // than a real HTTP/2 server — same "never touches the polyglot Context from another
    // thread" rationale as importHttpModule's client-path test above. `java.net.http.
    // HttpClient` built with `Version.HTTP_2` transparently falls back to HTTP/1.1 against
    // a server that doesn't speak HTTP/2 (per the JDK's own HttpClient docs), so this still
    // exercises http2.mjs's real request/response plumbing (session, stream, pseudo-header
    // handling, response body delivery) end-to-end, just not real HTTP/2 wire framing.

    @Test fun importHttp2ModuleClientRoundTrip() {
        val plainServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        plainServer.createContext("/h2ping") { exchange ->
            val body = "h2 pong".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        plainServer.executor = null
        plainServer.start()
        try {
            val plainPort = plainServer.address.port
            val mc = Mycelium()
            val tmpFile = File.createTempFile("mycelium-http2-client-test-", ".mjs")
            tmpFile.deleteOnExit()
            tmpFile.writeText(
                """
                import http2 from 'node:http2';
                const session = http2.connect(`http://127.0.0.1:$plainPort`);
                const req = session.request({
                    [http2.constants.HTTP2_HEADER_METHOD]: 'GET',
                    [http2.constants.HTTP2_HEADER_PATH]: '/h2ping',
                });
                let chunks = [];
                req.on('response', (headers) => {
                    globalThis.__http2Status = headers[http2.constants.HTTP2_HEADER_STATUS];
                });
                req.on('data', (chunk) => chunks.push(chunk));
                req.on('end', () => {
                    let str = '';
                    for (const chunk of chunks) {
                        for (let i = 0; i < chunk.length; i++) str += String.fromCharCode(chunk[i]);
                    }
                    globalThis.__http2Body = str;
                    globalThis.__http2Done = true;
                    session.close();
                });
                req.end();
                """.trimIndent()
            )
            mc.runFile(tmpFile.absolutePath)
            assertTrue(mc.eval("js", "globalThis.__http2Done").asBoolean())
            assertEquals(200, mc.eval("js", "globalThis.__http2Status").asInt())
            assertEquals("h2 pong", mc.eval("js", "globalThis.__http2Body").asString())
        } finally {
            plainServer.stop(0)
        }
    }

    // --- Node stdlib `net` module ---
    //
    // net.mjs's server/client machinery is backed by dedicated daemon threads (accept-loop
    // and per-socket readers, see net.mjs's own doc comment) that call back into the shared
    // GraalJS context the same way http.mjs's server side does. A real client+server round
    // trip would need *two* such background threads (accept + reader) potentially touching
    // the context around the same time, which risks exactly the concurrent-access failure
    // this module's own comment warns about — so, per the task's own fallback, this only
    // verifies the module's shape rather than attempting a live round trip.

    @Test fun importNetModule() {
        val mc = vm
        runJsWithImports(mc, """
            import net from 'net';
            globalThis.__netCreateServerType = typeof net.createServer;
            globalThis.__netCreateConnectionType = typeof net.createConnection;
        """.trimIndent())
        assertEquals("function", mc.eval("js", "globalThis.__netCreateServerType").asString())
        assertEquals("function", mc.eval("js", "globalThis.__netCreateConnectionType").asString())
    }

    // --- Node stdlib `tls` module ---

    @Test fun importTlsModule() {
        val mc = vm
        runJsWithImports(mc, """
            import tls from 'tls';
            globalThis.__tlsConnectType = typeof tls.connect;
            globalThis.__tlsCreateServerType = typeof tls.createServer;
            globalThis.__tlsTLSSocketType = typeof tls.TLSSocket;
        """.trimIndent())
        assertEquals("function", mc.eval("js", "globalThis.__tlsConnectType").asString())
        assertEquals("function", mc.eval("js", "globalThis.__tlsCreateServerType").asString())
        assertEquals("function", mc.eval("js", "globalThis.__tlsTLSSocketType").asString())
    }

    // --- Node stdlib `tty` module ---

    @Test fun importTtyModule() {
        val mc = vm
        runJsWithImports(mc, """
            import tty from 'tty';
            globalThis.__ttyIsatty = tty.isatty(0);
            globalThis.__ttyReadStreamType = typeof tty.ReadStream;
            globalThis.__ttyWriteStreamType = typeof tty.WriteStream;
        """.trimIndent())
        assertEquals(false, mc.eval("js", "globalThis.__ttyIsatty").asBoolean())
        assertEquals("function", mc.eval("js", "globalThis.__ttyReadStreamType").asString())
        assertEquals("function", mc.eval("js", "globalThis.__ttyWriteStreamType").asString())
    }

    // --- Node stdlib `child_process` module ---
    //
    // execSync (and the other *Sync APIs) never spawn extra threads — everything is a
    // plain blocking Java I/O call made directly from the calling JS thread (see
    // child_process.mjs's own doc comment) — so it's safe to run against the shared `vm`
    // the same as any other synchronous stdlib call.
    //
    // spawn()/exec(), by contrast, stream stdout/stderr via dedicated daemon reader
    // threads that call back into the context to `emit('data', ...)`, plus a separate
    // waiter thread that emits `exit`/`close` once the process terminates — the same
    // "another thread enters the context" pattern net.mjs's socket reader and
    // TIMERS_INIT_SCRIPT's timer thread already rely on. Per those tests' own precedent
    // (see setTimeoutWorks/pollUntilTrue above), these use a dedicated, throwaway
    // Mycelium instance and poll for completion rather than touching the shared `vm`.

    @Test fun importChildProcessModule() {
        val mc = vm
        runJsWithImports(mc, """
            import cp from 'child_process';
            globalThis.__cpExecType = typeof cp.exec;
            globalThis.__cpExecSyncType = typeof cp.execSync;
            globalThis.__cpSpawnType = typeof cp.spawn;
            globalThis.__cpSpawnSyncType = typeof cp.spawnSync;
            globalThis.__cpExecFileType = typeof cp.execFile;
            globalThis.__cpExecFileSyncType = typeof cp.execFileSync;
            globalThis.__cpForkType = typeof cp.fork;
            globalThis.__cpChildProcessType = typeof cp.ChildProcess;
        """.trimIndent())
        assertEquals("function", mc.eval("js", "globalThis.__cpExecType").asString())
        assertEquals("function", mc.eval("js", "globalThis.__cpExecSyncType").asString())
        assertEquals("function", mc.eval("js", "globalThis.__cpSpawnType").asString())
        assertEquals("function", mc.eval("js", "globalThis.__cpSpawnSyncType").asString())
        assertEquals("function", mc.eval("js", "globalThis.__cpExecFileType").asString())
        assertEquals("function", mc.eval("js", "globalThis.__cpExecFileSyncType").asString())
        assertEquals("function", mc.eval("js", "globalThis.__cpForkType").asString())
        assertEquals("function", mc.eval("js", "globalThis.__cpChildProcessType").asString())
    }

    @Test fun childProcessExecSyncRunsCommand() {
        val mc = vm
        runJsWithImports(mc, """
            import { execSync } from 'child_process';
            const out = execSync('echo hello');
            globalThis.__cpExecSyncResult = out.toString();
        """.trimIndent())
        assertEquals("hello\n", mc.eval("js", "globalThis.__cpExecSyncResult").asString())
    }

    @Test fun childProcessSpawnRunsCommand() {
        val mc = Mycelium()
        runJsWithImports(mc, """
            import { spawn } from 'child_process';
            globalThis.__cpSpawnDone = false;
            globalThis.__cpSpawnOutput = '';
            const child = spawn('echo', ['hello', 'from', 'spawn']);
            let chunks = [];
            child.stdout.on('data', (chunk) => chunks.push(chunk));
            child.on('close', (code) => {
                let str = '';
                for (const chunk of chunks) {
                    for (let i = 0; i < chunk.length; i++) str += String.fromCharCode(chunk[i]);
                }
                globalThis.__cpSpawnOutput = str;
                globalThis.__cpSpawnExitCode = code;
                globalThis.__cpSpawnDone = true;
            });
        """.trimIndent())
        assertTrue(
            pollUntilTrue(mc, "globalThis.__cpSpawnDone"),
            "spawn()'d process did not report 'close' within the timeout"
        )
        assertEquals("hello from spawn\n", mc.eval("js", "globalThis.__cpSpawnOutput").asString())
        assertEquals(0, mc.eval("js", "globalThis.__cpSpawnExitCode").asInt())
    }

    @Test fun childProcessExecRunsCommand() {
        val mc = Mycelium()
        runJsWithImports(mc, """
            import { exec } from 'child_process';
            globalThis.__cpExecDone = false;
            exec('echo hello from exec', (error, stdout, stderr) => {
                globalThis.__cpExecError = error;
                globalThis.__cpExecStdout = stdout;
                globalThis.__cpExecDone = true;
            });
        """.trimIndent())
        assertTrue(
            pollUntilTrue(mc, "globalThis.__cpExecDone"),
            "exec()'d command's callback did not fire within the timeout"
        )
        assertTrue(mc.eval("js", "globalThis.__cpExecError === null").asBoolean())
        assertEquals("hello from exec\n", mc.eval("js", "globalThis.__cpExecStdout").asString())
    }

    // --- Global `Buffer` (no import required) ---

    @Test fun globalBufferExists() {
        val mc = vm
        assertEquals("function", mc.eval("js", "typeof globalThis.Buffer").asString())
        val hex = mc.eval("js", "globalThis.Buffer.from('hello').toString('hex')")
        assertEquals("68656c6c6f", hex.asString())
    }

    // --- Global `TextEncoder`/`TextDecoder` (WHATWG Encoding API) ---

    @Test fun globalTextEncoderDecoderExist() {
        val mc = vm
        assertTrue(mc.eval("js", "new TextEncoder().encode('hello') instanceof Uint8Array").asBoolean())
        val roundTrip = mc.eval("js", "new TextDecoder().decode(new TextEncoder().encode('hello'))")
        assertEquals("hello", roundTrip.asString())
    }

    // --- `setTimeout`/`setInterval`/`setImmediate` globals ---
    //
    // setImmediate (and any zero-delay, non-repeating setTimeout) resolves on the JS
    // microtask queue (see TIMERS_INIT_SCRIPT's `nextTick`), so it fires on whichever
    // thread is already inside the context — safe to observe on the shared `vm` with a
    // single eval call.
    //
    // A genuine delay (setTimeout with ms > 0, or any setInterval) is instead backed by a
    // dedicated daemon `java.lang.Thread` that sleeps for real wall-clock time and then
    // calls back into the context itself (see TIMERS_INIT_SCRIPT's `runDelayed`) — the same
    // "another thread enters the context" pattern as http.mjs's server dispatch thread,
    // which (per the doc comment on importHttpModule's server half above) permanently
    // forfeits the context back to that thread once it touches it. So, like the
    // http.createServer / express.listen tests above, these use a dedicated, throwaway
    // Mycelium instance rather than the shared `vm`.

    @Test fun setImmediateWorks() {
        val mc = vm
        assertEquals("function", mc.eval("js", "typeof setImmediate").asString())
        mc.eval("js", """
            globalThis.__setImmediateFired = false;
            setImmediate(() => { globalThis.__setImmediateFired = true; });
        """.trimIndent())
        assertTrue(mc.eval("js", "globalThis.__setImmediateFired").asBoolean())
    }

    // A single-shot (non-repeating) timer's background thread only ever touches the
    // context for the brief instant it invokes its callback (see TIMERS_INIT_SCRIPT's
    // runDelayed), so polling from this thread in between is safe the vast majority of the
    // time — but if a poll's eval() happens to land in that exact instant, GraalJS throws
    // "Multi threaded access requested ... but is not allowed" rather than queuing or
    // blocking (confirmed empirically — see setIntervalWorks' doc comment for the repeating
    // case, which hits this unavoidably rather than just rarely). Retrying past that
    // exception here is enough for the non-repeating case, where the window is narrow.
    private fun pollUntilTrue(mc: Mycelium, expr: String, timeoutMs: Long = 5000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                if (mc.eval("js", expr).asBoolean()) return true
            } catch (e: IllegalStateException) {
                // Raced the timer thread's own brief context entry — just retry.
            }
            Thread.sleep(20)
        }
        return mc.eval("js", expr).asBoolean()
    }

    @Test fun setTimeoutWorks() {
        val mc = Mycelium()
        assertEquals("function", mc.eval("js", "typeof setTimeout").asString())
        mc.eval("js", """
            globalThis.__setTimeoutFired = false;
            setTimeout(() => { globalThis.__setTimeoutFired = true; }, 10);
        """.trimIndent())
        assertTrue(
            pollUntilTrue(mc, "globalThis.__setTimeoutFired"),
            "setTimeout callback did not fire within the timeout"
        )
    }

    @Test fun setIntervalWorks() {
        val mc = Mycelium()
        assertEquals("function", mc.eval("js", "typeof setInterval").asString())
        // Unlike setTimeoutWorks' single-shot fire, an interval's background timer thread
        // ticks repeatedly (see TIMERS_INIT_SCRIPT's runDelayed do/while loop), staying
        // inside the context for the entire tick (sleep, then invoke the callback) each
        // time around. Round-tripping a read-back eval() from *this* thread while that timer
        // thread could still be mid-tick was found (empirically) to throw "Multi threaded
        // access requested ... but is not allowed" — GraalJS Contexts don't allow two
        // threads touching them at once, and there's no reliable window from the host side
        // to know the timer thread is between ticks rather than inside one. So the callback
        // below calls `clearInterval` on *itself* once it's fired 3 times — entirely from
        // within the timer thread's own already-entered call, with no external thread
        // involved — which both proves the interval fires more than once and exercises
        // clearInterval, without this thread ever needing to touch the context while a tick
        // might be in flight.
        mc.eval("js", """
            globalThis.__intervalCount = 0;
            globalThis.__intervalId = setInterval(() => {
                globalThis.__intervalCount++;
                if (globalThis.__intervalCount >= 3) {
                    clearInterval(globalThis.__intervalId);
                }
            }, 10);
        """.trimIndent())
        // Comfortably longer than 3 ticks' worth of real time (10ms each), so the timer
        // thread is certain to have already self-cleared and exited by the time this reads
        // the final count back.
        Thread.sleep(300)
        val finalCount = mc.eval("js", "globalThis.__intervalCount").asInt()
        assertEquals(3, finalCount, "expected setInterval to fire exactly 3 times before self-clearing via clearInterval")
    }

    // --- Subpath exports (package.json `"exports"` mapping a subpath to a file, distinct
    // from a wildcard/conditions map like bareSpecifierUsesExportsFieldOverMain's ".") ---

    @Test fun bareSpecifierSubpathUsesExportsField() {
        val tmpDir = java.nio.file.Files.createTempDirectory("mycelium-npm-subpath-").toFile()
        tmpDir.deleteOnExit()
        val pkgDir = File(tmpDir, "node_modules/subpath-pkg")
        pkgDir.mkdirs()
        pkgDir.deleteOnExit()
        File(pkgDir, "package.json").apply {
            writeText(
                """
                {
                  "name": "subpath-pkg",
                  "exports": { "./sub": "./lib/sub.js" }
                }
                """.trimIndent()
            )
            deleteOnExit()
        }
        val libDir = File(pkgDir, "lib")
        libDir.mkdirs()
        libDir.deleteOnExit()
        File(libDir, "sub.js").apply {
            writeText("""export const value = "hello from subpath export";""")
            deleteOnExit()
        }

        val mainFile = File(tmpDir, "main.mjs")
        mainFile.writeText("""
            import { value } from "subpath-pkg/sub";
            globalThis.__subpathExportsResult = value;
        """.trimIndent())
        mainFile.deleteOnExit()

        val mc = vm
        mc.runFile(mainFile.absolutePath)
        val result = mc.eval("js", "globalThis.__subpathExportsResult")
        assertEquals("hello from subpath export", result.asString())
    }

    // --- Node stdlib `readline` / `readline/promises` modules ---
    //
    // Backed by a real `java.io.BufferedReader` running on a dedicated daemon thread (see
    // readline.mjs's `_readLineAsync`) — the same "background thread calls back into the
    // context" pattern child_process.mjs's spawn()/exec() use (see childProcessSpawnRunsCommand
    // above), so these use a throwaway `Mycelium` instance and poll for completion rather
    // than touching the shared `vm`. The `input` option is a plain JS object exposing
    // `_javaInputStream` (a `ByteArrayInputStream` built from a Java string), letting the
    // test feed synthetic stdin without needing a real terminal.

    @Test fun importReadlineModuleQuestionAndLineEvent() {
        val mc = Mycelium()
        runJsWithImports(mc, """
            import readline from 'readline';
            const JByteArrayInputStream = Java.type('java.io.ByteArrayInputStream');
            const JByteArray = Java.type('byte[]');
            const JCharset = Java.type('java.nio.charset.StandardCharsets');
            const buf = JCharset.UTF_8.encode('hello mycelium\n');
            const bytes = new JByteArray(buf.remaining());
            buf.get(bytes);
            const stream = new JByteArrayInputStream(bytes);
            globalThis.__rlOutput = '';
            const rl = readline.createInterface({
                input: { _javaInputStream: stream },
                output: { write: (s) => { globalThis.__rlOutput += s; } },
            });
            globalThis.__rlDone = false;
            rl.on('line', (line) => {
                globalThis.__rlLine = line;
                globalThis.__rlDone = true;
                rl.close();
            });
        """.trimIndent())
        assertTrue(
            pollUntilTrue(mc, "globalThis.__rlDone"),
            "readline Interface did not emit 'line' within the timeout"
        )
        assertEquals("hello mycelium", mc.eval("js", "globalThis.__rlLine").asString())
    }

    @Test fun importReadlinePromisesModuleQuestionResolves() {
        val mc = Mycelium()
        runJsWithImports(mc, """
            import { createInterface } from 'readline/promises';
            const JByteArrayInputStream = Java.type('java.io.ByteArrayInputStream');
            const JByteArray = Java.type('byte[]');
            const JCharset = Java.type('java.nio.charset.StandardCharsets');
            const buf = JCharset.UTF_8.encode('42\n');
            const bytes = new JByteArray(buf.remaining());
            buf.get(bytes);
            const stream = new JByteArrayInputStream(bytes);
            const rl = createInterface({
                input: { _javaInputStream: stream },
                output: { write: () => {} },
            });
            globalThis.__rlpDone = false;
            rl.question('What is the answer? ').then((answer) => {
                globalThis.__rlpAnswer = answer;
                globalThis.__rlpDone = true;
                rl.close();
            });
        """.trimIndent())
        assertTrue(
            pollUntilTrue(mc, "globalThis.__rlpDone"),
            "readline/promises question() did not resolve within the timeout"
        )
        assertEquals("42", mc.eval("js", "globalThis.__rlpAnswer").asString())
    }

    // --- Node stdlib `worker_threads` module (stub) ---

    @Test fun importWorkerThreadsModule() {
        val mc = vm
        runJsWithImports(mc, """
            import { isMainThread, parentPort, workerData, threadId, Worker, MessageChannel } from 'worker_threads';
            globalThis.__wtIsMainThread = isMainThread;
            globalThis.__wtParentPort = parentPort;
            globalThis.__wtWorkerData = workerData;
            globalThis.__wtThreadId = threadId;
            let threw = false;
            try {
                new Worker('./whatever.js');
            } catch (e) {
                threw = true;
                globalThis.__wtWorkerErrorMessage = e.message;
            }
            globalThis.__wtWorkerThrew = threw;

            const channel = new MessageChannel();
            globalThis.__wtChannelDone = false;
            channel.port2.on('message', (msg) => {
                globalThis.__wtChannelMessage = msg;
                globalThis.__wtChannelDone = true;
            });
            channel.port1.postMessage('ping');
        """.trimIndent())
        assertTrue(mc.eval("js", "globalThis.__wtIsMainThread").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__wtParentPort === null").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__wtWorkerData === null").asBoolean())
        assertEquals(0, mc.eval("js", "globalThis.__wtThreadId").asInt())
        assertTrue(mc.eval("js", "globalThis.__wtWorkerThrew").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__wtWorkerErrorMessage").asString().contains("not yet supported"))
        assertTrue(
            pollUntilTrue(mc, "globalThis.__wtChannelDone"),
            "MessageChannel port2 did not receive the posted message within the timeout"
        )
        assertEquals("ping", mc.eval("js", "globalThis.__wtChannelMessage").asString())
    }

    // --- Node stdlib `module` module (stub) ---

    @Test fun importModuleModule() {
        val mc = vm
        runJsWithImports(mc, """
            import { builtinModules, isBuiltin, createRequire, Module } from 'module';
            globalThis.__modBuiltinHasHttp = builtinModules.includes('http');
            globalThis.__modBuiltinHasReadline = builtinModules.includes('readline');
            globalThis.__modIsBuiltinPath = isBuiltin('path');
            globalThis.__modIsBuiltinNodePath = isBuiltin('node:path');
            globalThis.__modIsBuiltinFake = isBuiltin('totally-not-a-builtin');
            globalThis.__modModuleType = typeof Module;

            const require = createRequire(import.meta.url);
            const events = require('events');
            globalThis.__modRequireEventsType = typeof events.EventEmitter;
        """.trimIndent())
        assertTrue(mc.eval("js", "globalThis.__modBuiltinHasHttp").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__modBuiltinHasReadline").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__modIsBuiltinPath").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__modIsBuiltinNodePath").asBoolean())
        assertEquals(false, mc.eval("js", "globalThis.__modIsBuiltinFake").asBoolean())
        assertEquals("function", mc.eval("js", "globalThis.__modModuleType").asString())
        assertEquals("function", mc.eval("js", "globalThis.__modRequireEventsType").asString())
    }
}

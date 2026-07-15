package org.mycelium.library

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.graalvm.polyglot.PolyglotException
import java.io.File

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

    // --- `globalThis.crypto` (Web Crypto API) ---

    @Test fun globalCryptoExists() {
        val mc = vm
        assertEquals("function", mc.eval("js", "typeof globalThis.crypto.randomUUID").asString())
        assertEquals("function", mc.eval("js", "typeof globalThis.crypto.getRandomValues").asString())
        val uuid = mc.eval("js", "globalThis.crypto.randomUUID()")
        assertTrue(uuid.asString().matches(Regex("[0-9a-f-]{36}")))
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
}

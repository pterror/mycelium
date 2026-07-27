package org.mycelium.library

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

// Bun.Glob / Bun.$ (Bun Shell) / Bun.resolveSync/.resolve / Bun.pathToFileURL/.fileURLToPath —
// a follow-up slice of BUN_INIT_SCRIPT's Bun global, alongside BunCompatTest.kt (file/.serve/
// .env/.argv) and BunWriteSpawnTest.kt (write/spawn/spawnSync). Kept as its own file — same
// "avoid merge friction with a concurrent worktree" reasoning as BunWriteSpawnTest.kt's own
// header comment — with the bunCommand()/runBun()/runBunInDir() helpers duplicated file-local
// rather than shared for the same reason. See docs/reference/bun-compat.md for the
// authoritative, checked-against-upstream description of what's implemented and its known
// limitations (Glob: no extglob negation `!(...)`/`+(...)`/`@(...)`; Shell: a well-defined
// POSIX-`/bin/sh`-backed subset, not real Bun's own from-scratch cross-platform shell — see
// that section's own doc comment in Mycelium.kt for the full list of what does/doesn't match).
//
// Same live-parity discipline as BunCompatTest.kt: every behavior that can mechanically be
// diffed against a real `bun` process is, via the helpers below — not hardcoded golden values,
// gated with `assumeTrue(bunCommand() != null, ...)` so the suite degrades gracefully (skips,
// doesn't fail) without a working `bun`/`nix shell nixpkgs#bun`.
class BunGlobShellTest {
    companion object {
        private var resolvedBunCommand: List<String>? = null
        private var bunCommandResolved = false

        private fun isCommandAvailable(command: List<String>): Boolean =
            try {
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
                    .also { it.inputStream.readAllBytes(); it.waitFor() }
                    .exitValue() == 0
            } catch (e: java.io.IOException) {
                false
            }

        fun bunCommand(): List<String>? {
            if (bunCommandResolved) return resolvedBunCommand
            bunCommandResolved = true
            resolvedBunCommand = when {
                isCommandAvailable(listOf("bun", "--version")) -> listOf("bun")
                isCommandAvailable(listOf("nix", "shell", "nixpkgs#bun", "--command", "bun", "--version")) ->
                    listOf("nix", "shell", "nixpkgs#bun", "--command", "bun")
                else -> null
            }
            return resolvedBunCommand
        }

        fun runBun(scriptFile: File, dir: File? = null): String {
            val command = bunCommand() ?: error("bun not available")
            val builder = ProcessBuilder(command + listOf("run", scriptFile.absolutePath))
                .redirectErrorStream(false)
            if (dir != null) builder.directory(dir)
            val process = builder.start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw RuntimeException("bun run failed (exit $exitCode): $stderr")
            }
            return stdout.trim()
        }

        fun runBunInDir(dir: File, script: String): String {
            val scriptFile = File(dir, "bun-glob-shell-probe.mjs")
            scriptFile.writeText(script)
            return runBun(scriptFile, dir = dir)
        }
    }

    private fun runJsInDir(mc: Mycelium, dir: File, script: String) {
        val file = File(dir, "entry.mjs")
        file.writeText(script)
        mc.runFile(file.absolutePath)
    }

    private fun markBunProject(dir: File) {
        File(dir, "bunfig.toml").writeText("")
    }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buf = ByteArrayOutputStream()
        System.setOut(PrintStream(buf, true, "UTF-8"))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buf.toString("UTF-8")
    }

    private fun evalJson(mc: Mycelium, expr: String): String =
        mc.eval("js", "JSON.stringify($expr)").asString()

    // --- Bun.Glob: happy paths ---
    //
    // NOTE: every `.scan()`/`.scanSync()` call below passes an explicit `cwd` (the test's
    // own temp directory) rather than relying on the default (`process.cwd()`). This test
    // harness's plain `Mycelium()` + `runFile` never threads a per-instance `cwd` through
    // (that's a daemon-mode-only constructor param — see Mycelium's own doc comment), so
    // `process.cwd()` here reports the real JVM/Gradle process directory, not the temp
    // directory the entry script lives in — matching how BunWriteSpawnTest.kt/
    // BunCompatTest.kt avoid the same relative-path/cwd ambiguity by using absolute paths
    // throughout.

    @Test fun globScanFindsNestedFilesRelativeByDefault() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        File(dir, "a").mkdirs()
        File(dir, "a/x.txt").writeText("1")
        File(dir, "a/y.js").writeText("2")
        File(dir, "b/c").mkdirs()
        File(dir, "b/c/z.txt").writeText("3")
        runJsInDir(
            mc, dir,
            """
            const out = [];
            for await (const f of new Bun.Glob("**/*.txt").scan(${dir.absolutePath.jsQuoted()})) out.push(f);
            globalThis.__out = out.sort();
            """.trimIndent()
        )
        assertEquals("[\"a/x.txt\",\"b/c/z.txt\"]", evalJson(mc, "globalThis.__out"))
    }

    @Test fun globScanTopLevelStarDoesNotRecurse() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        File(dir, "a").mkdirs()
        File(dir, "a/y.js").writeText("2")
        File(dir, "top.js").writeText("3")
        runJsInDir(
            mc, dir,
            """
            const out = [];
            for await (const f of new Bun.Glob("*.js").scan(${dir.absolutePath.jsQuoted()})) out.push(f);
            globalThis.__out = out.sort();
            """.trimIndent()
        )
        // "*.js" (no leading "**/") must only match the top-level file, not "a/y.js" —
        // verified against real bun via globScanLiveParityAgainstRealBun below.
        assertEquals("[\"top.js\"]", evalJson(mc, "globalThis.__out"))
    }

    @Test fun globScanDotOptionDefaultExcludesDotEntriesAndTrueIncludesThem() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        File(dir, "d").mkdirs()
        File(dir, "d/.hidden.txt").writeText("1")
        File(dir, "d/visible.txt").writeText("2")
        runJsInDir(
            mc, dir,
            """
            const cwd = ${dir.absolutePath.jsQuoted()};
            const withoutDot = [];
            for await (const f of new Bun.Glob("**/*.txt").scan({ cwd })) withoutDot.push(f);
            const withDot = [];
            for await (const f of new Bun.Glob("**/*.txt").scan({ cwd, dot: true })) withDot.push(f);
            globalThis.__withoutDot = withoutDot.sort();
            globalThis.__withDot = withDot.sort();
            """.trimIndent()
        )
        assertEquals("[\"d/visible.txt\"]", evalJson(mc, "globalThis.__withoutDot"))
        assertEquals("[\"d/.hidden.txt\",\"d/visible.txt\"]", evalJson(mc, "globalThis.__withDot"))
    }

    @Test fun globScanOnlyFilesDefaultExcludesDirectoriesAndFalseIncludesThem() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        // Scans a nested "root" directory rather than `dir` itself — `dir` also holds
        // the entry.mjs/bunfig.toml this test harness writes (see runJsInDir/
        // markBunProject), which would otherwise show up in a "**" scan alongside the
        // fixture files this test actually cares about.
        val root = File(dir, "root").apply { mkdirs() }
        File(root, "sub").mkdirs()
        File(root, "sub/f.txt").writeText("1")
        runJsInDir(
            mc, dir,
            """
            const cwd = ${root.absolutePath.jsQuoted()};
            const filesOnly = [];
            for await (const f of new Bun.Glob("**").scan({ cwd })) filesOnly.push(f);
            const withDirs = [];
            for await (const f of new Bun.Glob("**").scan({ cwd, onlyFiles: false })) withDirs.push(f);
            globalThis.__filesOnly = filesOnly.sort();
            globalThis.__withDirs = withDirs.sort();
            """.trimIndent()
        )
        assertEquals("[\"sub/f.txt\"]", evalJson(mc, "globalThis.__filesOnly"))
        assertEquals("[\"sub\",\"sub/f.txt\"]", evalJson(mc, "globalThis.__withDirs"))
    }

    @Test fun globScanAbsoluteOptionReturnsAbsolutePaths() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        File(dir, "x.txt").writeText("1")
        runJsInDir(
            mc, dir,
            """
            const out = [];
            for await (const f of new Bun.Glob("*.txt").scan({ cwd: ${dir.absolutePath.jsQuoted()}, absolute: true })) out.push(f);
            globalThis.__out = out;
            """.trimIndent()
        )
        val expected = File(dir, "x.txt").absolutePath
        assertEquals("[\"${expected.replace("\\", "\\\\")}\"]", evalJson(mc, "globalThis.__out"))
    }

    @Test fun globScanCwdOptionAcceptsBareStringShorthand() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        File(dir, "sub").mkdirs()
        File(dir, "sub/f.txt").writeText("1")
        runJsInDir(
            mc, dir,
            """
            const out = [];
            for await (const f of new Bun.Glob("*.txt").scan(${File(dir, "sub").absolutePath.jsQuoted()})) out.push(f);
            globalThis.__out = out;
            """.trimIndent()
        )
        assertEquals("[\"f.txt\"]", evalJson(mc, "globalThis.__out"))
    }

    // --- Bun.Glob: match() ---

    @Test fun globMatchDoesNotApplyDotFilteringUnlikeScan() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            "globalThis.__r = new Bun.Glob('*').match('.dotfile');"
        )
        // Verified against real bun 1.3.13: `new Bun.Glob("*").match(".dotfile")` is `true`
        // — "dot" only filters `.scan()`'s filesystem traversal, not `.match()`'s pure
        // string test.
        assertTrue(mc.eval("js", "globalThis.__r").asBoolean())
    }

    @Test fun globMatchSupportsBraceAlternation() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            globalThis.__a = new Bun.Glob("**/*.{txt,js}").match("dir/x.txt");
            globalThis.__b = new Bun.Glob("**/*.{txt,js}").match("dir/y.js");
            globalThis.__c = new Bun.Glob("**/*.{txt,js}").match("dir/z.rb");
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__a").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__b").asBoolean())
        assertFalse(mc.eval("js", "globalThis.__c").asBoolean())
    }

    @Test fun globMatchCharacterClasses() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            globalThis.__range = new Bun.Glob("[a-z]/x.txt").match("a/x.txt");
            globalThis.__negated = new Bun.Glob("[!xy]").match("z");
            globalThis.__negatedMiss = new Bun.Glob("[!xy]").match("x");
            globalThis.__qmark = new Bun.Glob("a?c").match("abc");
            globalThis.__qmarkNoCrossSep = new Bun.Glob("a?c").match("a/c");
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__range").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__negated").asBoolean())
        assertFalse(mc.eval("js", "globalThis.__negatedMiss").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__qmark").asBoolean())
        assertFalse(mc.eval("js", "globalThis.__qmarkNoCrossSep").asBoolean())
    }

    @Test fun globMatchingNothingReturnsEmptyScan() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        File(dir, "a.txt").writeText("1")
        runJsInDir(
            mc, dir,
            """
            const out = [];
            for await (const f of new Bun.Glob("**/*.nonexistentext").scan(${dir.absolutePath.jsQuoted()})) out.push(f);
            globalThis.__out = out;
            """.trimIndent()
        )
        assertEquals("[]", evalJson(mc, "globalThis.__out"))
    }

    @Test fun globScanSyncReturnsIterableMatchingScan() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        File(dir, "a").mkdirs()
        File(dir, "a/x.txt").writeText("1")
        runJsInDir(
            mc, dir,
            "globalThis.__out = [...new Bun.Glob('**/*.txt').scanSync(${dir.absolutePath.jsQuoted()})].sort();"
        )
        assertEquals("[\"a/x.txt\"]", evalJson(mc, "globalThis.__out"))
    }

    @Test fun globDeeplyNestedDirectoriesMatchViaDoubleStar() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        val deep = File(dir, "l1/l2/l3/l4/l5")
        deep.mkdirs()
        File(deep, "deep.txt").writeText("1")
        runJsInDir(
            mc, dir,
            """
            const out = [];
            for await (const f of new Bun.Glob("**/*.txt").scan(${dir.absolutePath.jsQuoted()})) out.push(f);
            globalThis.__out = out;
            """.trimIndent()
        )
        assertEquals("[\"l1/l2/l3/l4/l5/deep.txt\"]", evalJson(mc, "globalThis.__out"))
    }

    @Test fun globNonexistentCwdThrows() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            globalThis.__threw = false;
            try {
                for await (const f of new Bun.Glob("*.txt").scan({ cwd: "${File(dir, "nope").absolutePath.replace("\\", "\\\\")}" })) {}
            } catch (e) {
                globalThis.__threw = true;
                globalThis.__msg = e.message;
            }
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__threw").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__msg").asString().contains("ENOENT"))
    }

    @Test fun globSymlinkToFileExcludedByDefaultIncludedWithOnlyFilesFalseOrFollowSymlinks() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        val target = File(dir, "real.txt")
        target.writeText("1")
        val link = File(dir, "link.txt").toPath()
        try {
            java.nio.file.Files.createSymbolicLink(link, target.toPath())
        } catch (e: java.io.IOException) {
            assumeTrue(false, "symlinks not supported on this filesystem: ${e.message}")
            return
        } catch (e: UnsupportedOperationException) {
            assumeTrue(false, "symlinks not supported on this filesystem")
            return
        }
        runJsInDir(
            mc, dir,
            """
            const cwd = ${dir.absolutePath.jsQuoted()};
            const defaultScan = [];
            for await (const f of new Bun.Glob("*.txt").scan({ cwd })) defaultScan.push(f);
            const onlyFilesFalse = [];
            for await (const f of new Bun.Glob("*.txt").scan({ cwd, onlyFiles: false })) onlyFilesFalse.push(f);
            const followLinks = [];
            for await (const f of new Bun.Glob("*.txt").scan({ cwd, followSymlinks: true })) followLinks.push(f);
            globalThis.__defaultScan = defaultScan.sort();
            globalThis.__onlyFilesFalse = onlyFilesFalse.sort();
            globalThis.__followLinks = followLinks.sort();
            """.trimIndent()
        )
        assertEquals("[\"real.txt\"]", evalJson(mc, "globalThis.__defaultScan"))
        assertEquals("[\"link.txt\",\"real.txt\"]", evalJson(mc, "globalThis.__onlyFilesFalse"))
        assertEquals("[\"link.txt\",\"real.txt\"]", evalJson(mc, "globalThis.__followLinks"))
    }

    // --- Bun.Glob: live parity against real bun ---

    @Test fun globScanLiveParityAgainstRealBun() {
        assumeTrue(bunCommand() != null, "bun not available in this environment")
        val dir = createTempDirectory()
        File(dir, "a").mkdirs()
        File(dir, "a/x.txt").writeText("1")
        File(dir, "a/y.js").writeText("2")
        File(dir, "b/c").mkdirs()
        File(dir, "b/c/z.txt").writeText("3")
        File(dir, "top.txt").writeText("4")
        File(dir, "d").mkdirs()
        File(dir, "d/.hidden.txt").writeText("5")

        val probeScript = """
            async function collect(pattern, opts) {
                const g = new Bun.Glob(pattern);
                const out = [];
                for await (const f of g.scan(opts)) out.push(f);
                return out.sort();
            }
            console.log(JSON.stringify({
                plain: await collect("**/*.txt"),
                withDot: await collect("**/*.txt", { dot: true }),
                topOnly: await collect("*.txt"),
                braces: await collect("**/*.{txt,js}"),
            }));
        """.trimIndent()

        val realOutput = runBunInDir(dir, probeScript)

        val mc = Mycelium()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            const cwd = ${dir.absolutePath.jsQuoted()};
            async function collect(pattern, opts) {
                const g = new Bun.Glob(pattern);
                const out = [];
                for await (const f of g.scan(Object.assign({ cwd }, opts))) out.push(f);
                return out.sort();
            }
            globalThis.__result = JSON.stringify({
                plain: await collect("**/*.txt"),
                withDot: await collect("**/*.txt", { dot: true }),
                topOnly: await collect("*.txt"),
                braces: await collect("**/*.{txt,js}"),
            });
            """.trimIndent()
        )
        val shimOutput = mc.eval("js", "globalThis.__result").asString()
        assertEquals(realOutput, shimOutput, "shim Bun.Glob output diverged from real bun")
    }

    // --- Bun.$ (Bun Shell): happy paths ---

    @Test fun shellEchoTextHappyPath() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(mc, dir, "globalThis.__r = await Bun.${'$'}`echo hello world`.text();")
        assertEquals("hello world\n", mc.eval("js", "globalThis.__r").asString())
    }

    @Test fun shellJsonMethodParsesOutput() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(mc, dir, "globalThis.__r = await Bun.${'$'}`echo '{\"a\":1}'`.json();")
        assertEquals(1L, mc.eval("js", "globalThis.__r.a").asLong())
    }

    @Test fun shellAwaitedDirectlyExposesExitCodeStdoutStderr() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            const r = await Bun.${'$'}`echo hi`.quiet();
            globalThis.__exitCode = r.exitCode;
            globalThis.__stdout = r.stdout.toString();
            """.trimIndent()
        )
        assertEquals(0L, mc.eval("js", "globalThis.__exitCode").asLong())
        assertEquals("hi\n", mc.eval("js", "globalThis.__stdout").asString())
    }

    @Test fun shellNonZeroExitThrowsShellErrorByDefault() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            globalThis.__threw = false;
            try {
                await Bun.${'$'}`exit 3`.quiet();
            } catch (e) {
                globalThis.__threw = true;
                globalThis.__exitCode = e.exitCode;
                globalThis.__name = e.name;
            }
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__threw").asBoolean())
        assertEquals(3L, mc.eval("js", "globalThis.__exitCode").asLong())
        assertEquals("ShellError", mc.eval("js", "globalThis.__name").asString())
    }

    @Test fun shellNothrowSuppressesThrowAndReportsExitCode() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            "globalThis.__exitCode = (await Bun.${'$'}`exit 5`.nothrow().quiet()).exitCode;"
        )
        assertEquals(5L, mc.eval("js", "globalThis.__exitCode").asLong())
    }

    @Test fun shellQuietSuppressesConsoleOutputButNotCapturedBuffer() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        val printed = captureStdout {
            runJsInDir(
                mc, dir,
                "globalThis.__r = (await Bun.${'$'}`echo quiettest`.quiet()).stdout.toString();"
            )
        }
        assertFalse(printed.contains("quiettest"), "quiet() should suppress printing to the console")
        assertEquals("quiettest\n", mc.eval("js", "globalThis.__r").asString())
    }

    @Test fun shellWithoutQuietAlsoPrintsToConsole() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        val printed = captureStdout {
            runJsInDir(mc, dir, "await Bun.${'$'}`echo notquiet`;")
        }
        assertTrue(printed.contains("notquiet"), "without quiet(), output should also reach the console")
    }

    @Test fun shellPipeWorks() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """globalThis.__r = await Bun.${'$'}`printf "b\na\nc\n" | sort`.quiet().text();"""
        )
        assertEquals("a\nb\nc\n", mc.eval("js", "globalThis.__r").asString())
    }

    @Test fun shellAndOrOperators() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            globalThis.__and = await Bun.${'$'}`echo one && echo two`.quiet().text();
            globalThis.__or = await Bun.${'$'}`false || echo fallback`.quiet().text();
            """.trimIndent()
        )
        assertEquals("one\ntwo\n", mc.eval("js", "globalThis.__and").asString())
        assertEquals("fallback\n", mc.eval("js", "globalThis.__or").asString())
    }

    @Test fun shellRedirectionAppendAndTruncate() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        val outFile = File(dir, "shellout.txt")
        runJsInDir(
            mc, dir,
            """
            await Bun.${'$'}`echo line1 > ${outFile.absolutePath.jsQuoted()}`.quiet();
            await Bun.${'$'}`echo line2 >> ${outFile.absolutePath.jsQuoted()}`.quiet();
            """.trimIndent()
        )
        assertEquals("line1\nline2\n", outFile.readText())
    }

    @Test fun shellCwdOptionChangesWorkingDirectory() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        val sub = File(dir, "sub").apply { mkdirs() }
        runJsInDir(
            mc, dir,
            "globalThis.__r = await Bun.${'$'}`pwd`.cwd(${sub.absolutePath.jsQuoted()}).quiet().text();"
        )
        assertEquals(sub.canonicalFile.absolutePath, mc.eval("js", "globalThis.__r").asString().trim())
    }

    @Test fun shellEnvOptionExposesVariableAndMergesRatherThanReplaces() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            globalThis.__foo = await Bun.${'$'}`echo ${'$'}FOO`.env({ FOO: "barbaz" }).quiet().text();
            // A deliberate, documented divergence from real Bun (whose .env() fully
            // replaces the environment): this shim MERGES onto the inherited environment
            // so external commands relying on ${'$'}PATH keep working (see Bun.${'$'}'s own doc
            // comment in Mycelium.kt). Confirm PATH survives an .env() call here.
            globalThis.__path = await Bun.${'$'}`echo ${'$'}PATH`.env({ FOO: "barbaz" }).quiet().text();
            """.trimIndent()
        )
        assertEquals("barbaz\n", mc.eval("js", "globalThis.__foo").asString())
        assertTrue(mc.eval("js", "globalThis.__path").asString().trim().isNotEmpty())
    }

    // --- Bun.$: interpolation / injection safety (security-relevant) ---

    @Test fun shellInterpolationTreatsSpacesAsOneArgumentNotWordSplit() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            const v = "a b c";
            globalThis.__r = await Bun.${'$'}`echo ${'$'}{v}`.quiet().text();
            """.trimIndent()
        )
        assertEquals("a b c\n", mc.eval("js", "globalThis.__r").asString())
    }

    @Test fun shellInterpolationDoesNotEvaluateCommandSubstitution() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            const evil = "${'$'}(echo INJECTED)";
            globalThis.__r = await Bun.${'$'}`echo ${'$'}{evil}`.quiet().text();
            """.trimIndent()
        )
        assertEquals("\$(echo INJECTED)\n", mc.eval("js", "globalThis.__r").asString())
    }

    @Test fun shellInterpolationDoesNotEvaluateBacktickSubstitution() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            const evil = "`echo INJECTED`";
            globalThis.__r = await Bun.${'$'}`echo ${'$'}{evil}`.quiet().text();
            """.trimIndent()
        )
        assertEquals("`echo INJECTED`\n", mc.eval("js", "globalThis.__r").asString())
    }

    @Test fun shellInterpolationDoesNotEvaluateSemicolonOrAndOrChaining() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        val marker = File(dir, "injected-marker.txt")
        runJsInDir(
            mc, dir,
            """
            const semi = "; touch ${marker.absolutePath}";
            const andand = "a && touch ${marker.absolutePath}";
            globalThis.__semi = await Bun.${'$'}`echo ${'$'}{semi}`.quiet().text();
            globalThis.__and = await Bun.${'$'}`echo ${'$'}{andand}`.quiet().text();
            """.trimIndent()
        )
        assertEquals("; touch ${marker.absolutePath}\n", mc.eval("js", "globalThis.__semi").asString())
        assertEquals("a && touch ${marker.absolutePath}\n", mc.eval("js", "globalThis.__and").asString())
        assertFalse(marker.exists(), "interpolated shell metacharacters must not be executed")
    }

    @Test fun shellInterpolationDoesNotExpandGlobs() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        File(dir, "one.txt").writeText("1")
        File(dir, "two.txt").writeText("2")
        runJsInDir(
            mc, dir,
            """
            const pattern = "*.txt";
            globalThis.__r = await Bun.${'$'}`echo ${'$'}{pattern}`.quiet().text();
            """.trimIndent()
        )
        assertEquals("*.txt\n", mc.eval("js", "globalThis.__r").asString())
    }

    @Test fun shellArrayInterpolationJoinsElementsWithSpacesEachQuoted() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            const arr = ["x", "y", "; touch pwned"];
            globalThis.__r = await Bun.${'$'}`echo ${'$'}{arr}`.quiet().text();
            """.trimIndent()
        )
        assertEquals("x y ; touch pwned\n", mc.eval("js", "globalThis.__r").asString())
        assertFalse(File(dir, "pwned").exists())
    }

    // --- Bun.$: live parity against real bun ---

    @Test fun shellLiveParityInjectionSafety() {
        assumeTrue(bunCommand() != null, "bun not available in this environment")
        val dir = createTempDirectory()
        val probeScript = """
            const payloads = ["${'$'}(echo INJ)", "`echo INJ`", "; echo INJ", "a && echo INJ", "*.txt"];
            const out = [];
            for (const p of payloads) {
                out.push(await Bun.${'$'}`echo ${'$'}{p}`.quiet().text());
            }
            console.log(JSON.stringify(out));
        """.trimIndent()
        val realOutput = runBunInDir(dir, probeScript)

        val mc = Mycelium()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            const payloads = ["${'$'}(echo INJ)", "`echo INJ`", "; echo INJ", "a && echo INJ", "*.txt"];
            const out = [];
            for (const p of payloads) {
                out.push(await Bun.${'$'}`echo ${'$'}{p}`.quiet().text());
            }
            globalThis.__result = JSON.stringify(out);
            """.trimIndent()
        )
        val shimOutput = mc.eval("js", "globalThis.__result").asString()
        assertEquals(realOutput, shimOutput, "shim Bun.\$ injection-safety output diverged from real bun")
    }

    @Test fun shellLiveParityPipeAndAndOr() {
        assumeTrue(bunCommand() != null, "bun not available in this environment")
        val dir = createTempDirectory()
        val probeScript = """
            const results = {
                pipe: await Bun.${'$'}`printf "b\na\nc\n" | sort`.quiet().text(),
                and: await Bun.${'$'}`echo one && echo two`.quiet().text(),
                or: await Bun.${'$'}`false || echo fallback`.quiet().text(),
            };
            console.log(JSON.stringify(results));
        """.trimIndent()
        val realOutput = runBunInDir(dir, probeScript)

        val mc = Mycelium()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            globalThis.__result = JSON.stringify({
                pipe: await Bun.${'$'}`printf "b\na\nc\n" | sort`.quiet().text(),
                and: await Bun.${'$'}`echo one && echo two`.quiet().text(),
                or: await Bun.${'$'}`false || echo fallback`.quiet().text(),
            });
            """.trimIndent()
        )
        val shimOutput = mc.eval("js", "globalThis.__result").asString()
        assertEquals(realOutput, shimOutput, "shim Bun.\$ pipe/&&/|| output diverged from real bun")
    }

    // --- Bun.resolveSync / Bun.resolve ---

    @Test fun resolveSyncResolvesRelativeFileWithExtension() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        File(dir, "pkg").mkdirs()
        File(dir, "pkg/foo.js").writeText("export default 1;")
        runJsInDir(
            mc, dir,
            "globalThis.__r = Bun.resolveSync('./pkg/foo.js', ${dir.absolutePath.jsQuoted()});"
        )
        assertEquals(File(dir, "pkg/foo.js").canonicalPath, mc.eval("js", "globalThis.__r").asString())
    }

    @Test fun resolveSyncResolvesExtensionlessAndDirectoryIndex() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        File(dir, "pkg").mkdirs()
        File(dir, "pkg/foo.js").writeText("export default 1;")
        File(dir, "pkg/index.js").writeText("export default 2;")
        runJsInDir(
            mc, dir,
            """
            globalThis.__noExt = Bun.resolveSync('./pkg/foo', ${dir.absolutePath.jsQuoted()});
            globalThis.__dirIndex = Bun.resolveSync('./pkg', ${dir.absolutePath.jsQuoted()});
            """.trimIndent()
        )
        assertEquals(File(dir, "pkg/foo.js").canonicalPath, mc.eval("js", "globalThis.__noExt").asString())
        assertEquals(File(dir, "pkg/index.js").canonicalPath, mc.eval("js", "globalThis.__dirIndex").asString())
    }

    @Test fun resolveSyncCoreModuleGetsNodePrefix() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(mc, dir, "globalThis.__r = Bun.resolveSync('fs', ${dir.absolutePath.jsQuoted()});")
        // Verified against real bun 1.3.13: `Bun.resolveSync('fs', dir)` -> `'node:fs'`.
        assertEquals("node:fs", mc.eval("js", "globalThis.__r").asString())
    }

    @Test fun resolveSyncMissingModuleThrows() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            globalThis.__threw = false;
            try {
                Bun.resolveSync('./nope.js', ${dir.absolutePath.jsQuoted()});
            } catch (e) {
                globalThis.__threw = true;
            }
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__threw").asBoolean())
    }

    @Test fun resolveAsyncMatchesResolveSync() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        File(dir, "pkg").mkdirs()
        File(dir, "pkg/foo.js").writeText("export default 1;")
        runJsInDir(
            mc, dir,
            """
            globalThis.__sync = Bun.resolveSync('./pkg/foo.js', ${dir.absolutePath.jsQuoted()});
            globalThis.__async = await Bun.resolve('./pkg/foo.js', ${dir.absolutePath.jsQuoted()});
            """.trimIndent()
        )
        assertEquals(
            mc.eval("js", "globalThis.__sync").asString(),
            mc.eval("js", "globalThis.__async").asString()
        )
    }

    // --- Bun.pathToFileURL / Bun.fileURLToPath ---

    @Test fun pathToFileURLRoundTripsWithFileURLToPath() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        val target = File(dir, "x.txt").apply { writeText("1") }
        runJsInDir(
            mc, dir,
            """
            const u = Bun.pathToFileURL(${target.absolutePath.jsQuoted()});
            globalThis.__href = u.href;
            globalThis.__back = Bun.fileURLToPath(u);
            globalThis.__backFromString = Bun.fileURLToPath(u.href);
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__href").asString().startsWith("file://"))
        assertEquals(target.canonicalPath, mc.eval("js", "globalThis.__back").asString())
        assertEquals(target.canonicalPath, mc.eval("js", "globalThis.__backFromString").asString())
    }

    @Test fun fileURLToPathRejectsNonFileScheme() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            globalThis.__threw = false;
            try {
                Bun.fileURLToPath('https://example.com/foo');
            } catch (e) {
                globalThis.__threw = true;
            }
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__threw").asBoolean())
    }

    @Test fun pathToFileURLLiveParityAgainstRealBun() {
        assumeTrue(bunCommand() != null, "bun not available in this environment")
        val dir = createTempDirectory()
        val target = File(dir, "x.txt").apply { writeText("1") }
        val probeScript = """
            const u = Bun.pathToFileURL(${target.absolutePath.jsQuoted()});
            console.log(JSON.stringify({ href: u.href, back: Bun.fileURLToPath(u) }));
        """.trimIndent()
        val realOutput = runBunInDir(dir, probeScript)

        val mc = Mycelium()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            const u = Bun.pathToFileURL(${target.absolutePath.jsQuoted()});
            globalThis.__result = JSON.stringify({ href: u.href, back: Bun.fileURLToPath(u) });
            """.trimIndent()
        )
        val shimOutput = mc.eval("js", "globalThis.__result").asString()
        assertEquals(realOutput, shimOutput)
    }
}

// Same small helpers as BunCompatTest.kt/BunWriteSpawnTest.kt, duplicated here for the same
// "avoid merge friction across concurrent worktrees" reason as their own copies.
private fun String.jsQuoted(): String =
    "\"" + this.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun createTempDirectory(): File =
    kotlin.io.path.createTempDirectory("mycelium-bun-glob-shell-test-").toFile()

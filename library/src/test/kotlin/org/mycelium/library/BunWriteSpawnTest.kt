package org.mycelium.library

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

// Bun.write() / Bun.spawn() / Bun.spawnSync() — a follow-up slice of BUN_INIT_SCRIPT's Bun
// global on top of BunCompatTest.kt's Bun.file/.serve/.env/.argv coverage. Kept as its own
// file (rather than appended to BunCompatTest.kt) specifically to avoid merge friction with
// another concurrent worktree that may also be touching BunCompatTest.kt — see
// docs/reference/bun-compat.md for the authoritative, checked-against-upstream description
// of what's implemented and its known limitations (stdio only supports the 'pipe'/'ignore'/
// 'inherit' string modes, stdout/stderr are a simplified push-based stream rather than a
// true WHATWG ReadableStream, no options.timeout).
//
// Same live-parity discipline as BunCompatTest.kt: every behavior that can mechanically be
// diffed against a real `bun` process is, via the duplicated bunCommand()/runBun()/
// runBunInDir() helpers below (kept file-local rather than shared, for the same
// merge-friction reason cited above) — not hardcoded golden values.
class BunWriteSpawnTest {
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
            val scriptFile = File(dir, "bun-write-spawn-probe.mjs")
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

    // --- Bun.write() ---

    @Test fun bunWriteStringHappyPath() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        val target = File(dir, "out.txt")
        runJsInDir(
            mc,
            dir,
            """
            globalThis.__n = await Bun.write(${target.absolutePath.jsQuoted()}, "hello world");
            """.trimIndent()
        )
        assertEquals(11L, mc.eval("js", "globalThis.__n").asLong())
        assertEquals("hello world", target.readText())
    }

    @Test fun bunWriteTypedArray() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        val target = File(dir, "out.bin")
        runJsInDir(
            mc,
            dir,
            """
            globalThis.__n = await Bun.write(${target.absolutePath.jsQuoted()}, new Uint8Array([104, 105]));
            """.trimIndent()
        )
        assertEquals(2L, mc.eval("js", "globalThis.__n").asLong())
        assertEquals("hi", target.readText())
    }

    @Test fun bunWriteFromBunFileSource() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        val source = File(dir, "source.txt")
        source.writeText("copy me")
        val target = File(dir, "copy.txt")
        runJsInDir(
            mc,
            dir,
            """
            globalThis.__n = await Bun.write(
                ${target.absolutePath.jsQuoted()},
                Bun.file(${source.absolutePath.jsQuoted()})
            );
            """.trimIndent()
        )
        assertEquals(7L, mc.eval("js", "globalThis.__n").asLong())
        assertEquals("copy me", target.readText())
    }

    @Test fun bunWriteFromResponseSource() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        val target = File(dir, "resp.txt")
        runJsInDir(
            mc,
            dir,
            """
            globalThis.__n = await Bun.write(${target.absolutePath.jsQuoted()}, new Response("resp body"));
            """.trimIndent()
        )
        assertEquals(9L, mc.eval("js", "globalThis.__n").asLong())
        assertEquals("resp body", target.readText())
    }

    // Adversarial: writing into a directory that doesn't exist yet. Verified against real
    // Bun 1.3.13 that this does NOT reject — Bun.write silently creates missing parent
    // directories, unlike Node's fs.writeFile (which requires the directory to already
    // exist and rejects ENOENT otherwise).
    @Test fun bunWriteCreatesMissingParentDirectories() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        val target = File(dir, "nosuchdir/nested/out.txt")
        runJsInDir(
            mc,
            dir,
            """
            globalThis.__n = await Bun.write(${target.absolutePath.jsQuoted()}, "x");
            """.trimIndent()
        )
        assertEquals(1L, mc.eval("js", "globalThis.__n").asLong())
        assertTrue(target.exists())
        assertEquals("x", target.readText())
    }

    // Relative destination paths deliberately use each side's own absolute directory
    // (rather than a bare relative string) — Bun.write, like Bun.file, bypasses Mycelium's
    // InterceptingFileSystem and resolves relative paths against the *JVM process's* real
    // cwd, not the running script's own directory (see BUN_INIT_SCRIPT's Bun.file comment
    // for the same documented bypass); a bare relative path would land in the test JVM's
    // actual working directory for the shim side while real `bun run` (invoked with
    // ProcessBuilder#directory(dir) below) would resolve it against `dir` — the two sides
    // would silently write to different places. Using an absolute path on both sides keeps
    // this an apples-to-apples comparison of Bun.write's *return value* and file-creation
    // behavior, which is what this test actually checks.
    @Test fun bunWriteMatchesRealBunLive() {
        assumeTrue(bunCommand() != null, "bun not available — skipping live parity check")
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        val liveDir = createTempDirectory()
        markBunProject(liveDir)

        fun probeBody(base: File) = """
            var __n1 = await Bun.write(${File(base, "plain.txt").absolutePath.jsQuoted()}, "hello world");
            var __n2 = await Bun.write(${File(base, "typed.bin").absolutePath.jsQuoted()}, new Uint8Array([104, 105]));
            var __n3 = await Bun.write(${File(base, "nested/deep/created.txt").absolutePath.jsQuoted()}, "created");
            var __result = JSON.stringify({ n1: __n1, n2: __n2, n3: __n3 });
        """.trimIndent()

        val liveResult = runBunInDir(liveDir, probeBody(liveDir) + "\nconsole.log(__result);")
        runJsInDir(mc, dir, probeBody(dir) + "\nglobalThis.__result = __result;")
        val shimResult = mc.eval("js", "globalThis.__result").asString()

        assertEquals(liveResult, shimResult)
        assertEquals("hello world", File(dir, "plain.txt").readText())
        assertEquals("hi", File(dir, "typed.bin").readText())
        assertEquals("created", File(dir, "nested/deep/created.txt").readText())
        assertEquals("hello world", File(liveDir, "plain.txt").readText())
    }

    // --- Bun.spawn() ---

    @Test fun bunSpawnHappyPath() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc,
            dir,
            """
            const proc = Bun.spawn(["echo", "hello", "world"]);
            globalThis.__pid = proc.pid;
            const reader = proc.stdout.getReader();
            let text = "";
            const decoder = new TextDecoder ? new TextDecoder() : null;
            while (true) {
                const { value, done } = await reader.read();
                if (done) break;
                for (const b of value) text += String.fromCharCode(b);
            }
            globalThis.__stdout = text;
            globalThis.__exitCode = await proc.exited;
            globalThis.__exitCodeProp = proc.exitCode;
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__pid").asInt() > 0)
        assertEquals("hello world\n", mc.eval("js", "globalThis.__stdout").asString())
        assertEquals(0, mc.eval("js", "globalThis.__exitCode").asInt())
        assertEquals(0, mc.eval("js", "globalThis.__exitCodeProp").asInt())
    }

    @Test fun bunSpawnStreamTextConvenienceHelper() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc,
            dir,
            """
            const proc = Bun.spawn(["echo", "convenience"]);
            globalThis.__text = await proc.stdout.text();
            await proc.exited;
            """.trimIndent()
        )
        assertEquals("convenience\n", mc.eval("js", "globalThis.__text").asString())
    }

    @Test fun bunSpawnNonexistentCommandThrowsSynchronously() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc,
            dir,
            """
            try {
                Bun.spawn(["mycelium_test_definitely_nonexistent_cmd_xyz"]);
                globalThis.__threw = false;
            } catch (e) {
                globalThis.__threw = true;
                globalThis.__code = e.code;
                globalThis.__message = e.message;
            }
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__threw").asBoolean())
        assertEquals("ENOENT", mc.eval("js", "globalThis.__code").asString())
        assertTrue(
            mc.eval("js", "globalThis.__message").asString()
                .contains("mycelium_test_definitely_nonexistent_cmd_xyz")
        )
    }

    @Test fun bunSpawnNonexistentCommandErrorShapeMatchesRealBunLive() {
        assumeTrue(bunCommand() != null, "bun not available — skipping live parity check")
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)

        val probeBody = """
            var __r;
            try {
                Bun.spawn(["mycelium_test_definitely_nonexistent_cmd_xyz"]);
                __r = { threw: false };
            } catch (e) {
                __r = { threw: true, code: e.code, message: e.message };
            }
        """.trimIndent()

        val liveJson = runBunInDir(dir, probeBody + "\nconsole.log(JSON.stringify(__r));")
        runJsInDir(mc, dir, probeBody + "\nglobalThis.__result = JSON.stringify(__r);")
        val shimJson = mc.eval("js", "globalThis.__result").asString()

        assertEquals(liveJson, shimJson)
    }

    @Test fun bunSpawnExitCodePassthrough() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc,
            dir,
            """
            const proc = Bun.spawn(["sh", "-c", "exit 7"]);
            globalThis.__exitCode = await proc.exited;
            globalThis.__exitCodeProp = proc.exitCode;
            """.trimIndent()
        )
        assertEquals(7, mc.eval("js", "globalThis.__exitCode").asInt())
        assertEquals(7, mc.eval("js", "globalThis.__exitCodeProp").asInt())
    }

    @Test fun bunSpawnLargeOutput() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc,
            dir,
            """
            const proc = Bun.spawn(["sh", "-c", "yes x | head -c 200000"]);
            const text = await proc.stdout.text();
            globalThis.__len = text.length;
            await proc.exited;
            """.trimIndent()
        )
        assertEquals(200000, mc.eval("js", "globalThis.__len").asInt())
    }

    @Test fun bunSpawnStdinPipeRoundTrip() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc,
            dir,
            """
            const proc = Bun.spawn(["cat"], { stdin: "pipe" });
            proc.stdin.write("piped in\n");
            proc.stdin.end();
            globalThis.__out = await proc.stdout.text();
            await proc.exited;
            """.trimIndent()
        )
        assertEquals("piped in\n", mc.eval("js", "globalThis.__out").asString())
    }

    @Test fun bunSpawnKillSetsSignalCode() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc,
            dir,
            """
            const proc = Bun.spawn(["sleep", "10"]);
            proc.kill();
            await proc.exited;
            globalThis.__exitCodeProp = proc.exitCode;
            globalThis.__signalCode = proc.signalCode;
            globalThis.__killed = proc.killed;
            """.trimIndent()
        )
        assertNull(mc.eval("js", "globalThis.__exitCodeProp").let { if (it.isNull) null else it.asInt() })
        assertEquals("SIGTERM", mc.eval("js", "globalThis.__signalCode").asString())
        assertTrue(mc.eval("js", "globalThis.__killed").asBoolean())
    }

    // --- Bun.spawnSync() ---

    @Test fun bunSpawnSyncHappyPath() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc,
            dir,
            """
            const r = Bun.spawnSync(["echo", "sync hello"]);
            globalThis.__success = r.success;
            globalThis.__exitCode = r.exitCode;
            globalThis.__stdout = r.stdout.toString();
            globalThis.__pid = r.pid;
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__success").asBoolean())
        assertEquals(0, mc.eval("js", "globalThis.__exitCode").asInt())
        assertEquals("sync hello\n", mc.eval("js", "globalThis.__stdout").asString())
        assertTrue(mc.eval("js", "globalThis.__pid").asInt() > 0)
    }

    // Adversarial: captures both stdout and stderr, plus a nonzero exit code, in one shot —
    // the classic case that risks a ProcessBuilder pipe deadlock if not handled carefully
    // (see BUN_INIT_SCRIPT's own doc comment on this).
    @Test fun bunSpawnSyncCapturesStdoutAndStderrWithExitCode() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc,
            dir,
            """
            const r = Bun.spawnSync(["sh", "-c", "echo out1; echo err1 1>&2; exit 3"]);
            globalThis.__stdout = r.stdout.toString();
            globalThis.__stderr = r.stderr.toString();
            globalThis.__exitCode = r.exitCode;
            globalThis.__success = r.success;
            """.trimIndent()
        )
        assertEquals("out1\n", mc.eval("js", "globalThis.__stdout").asString())
        assertEquals("err1\n", mc.eval("js", "globalThis.__stderr").asString())
        assertEquals(3, mc.eval("js", "globalThis.__exitCode").asInt())
        assertFalse(mc.eval("js", "globalThis.__success").asBoolean())
    }

    @Test fun bunSpawnSyncNonexistentCommandThrowsSynchronously() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc,
            dir,
            """
            try {
                Bun.spawnSync(["mycelium_test_definitely_nonexistent_cmd_xyz"]);
                globalThis.__threw = false;
            } catch (e) {
                globalThis.__threw = true;
                globalThis.__code = e.code;
            }
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__threw").asBoolean())
        assertEquals("ENOENT", mc.eval("js", "globalThis.__code").asString())
    }

    @Test fun bunSpawnSyncCwdAndEnvOptions() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        val subDir = File(dir, "cwd-target")
        subDir.mkdirs()
        runJsInDir(
            mc,
            dir,
            """
            const r1 = Bun.spawnSync(["pwd"], { cwd: ${subDir.absolutePath.jsQuoted()} });
            globalThis.__cwd = r1.stdout.toString().trim();

            const r2 = Bun.spawnSync(["sh", "-c", "echo ${'$'}MYCELIUM_TEST_VAR"], {
                env: { MYCELIUM_TEST_VAR: "barbaz", PATH: (globalThis.process && globalThis.process.env.PATH) || "" },
            });
            globalThis.__env = r2.stdout.toString().trim();
            """.trimIndent()
        )
        // Resolve symlinks the same way `pwd` would print a real, canonical path (macOS
        // tmp dirs are frequently a symlink; not an issue on this repo's Linux CI, but
        // resolving defensively costs nothing).
        assertEquals(subDir.canonicalPath, File(mc.eval("js", "globalThis.__cwd").asString()).canonicalPath)
        assertEquals("barbaz", mc.eval("js", "globalThis.__env").asString())
    }

    @Test fun bunSpawnSyncMatchesRealBunLiveForStdoutStderrAndExitCode() {
        assumeTrue(bunCommand() != null, "bun not available — skipping live parity check")
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)

        val probeBody = """
            const r = Bun.spawnSync(["sh", "-c", "echo out1; echo err1 1>&2; exit 3"]);
            var __result = JSON.stringify({
                stdout: r.stdout.toString(),
                stderr: r.stderr.toString(),
                exitCode: r.exitCode,
                success: r.success,
            });
        """.trimIndent()

        val liveJson = runBunInDir(dir, probeBody + "\nconsole.log(__result);")
        runJsInDir(mc, dir, probeBody + "\nglobalThis.__result = __result;")
        val shimJson = mc.eval("js", "globalThis.__result").asString()

        assertEquals(liveJson, shimJson)
    }

    @Test fun bunSpawnSyncEmptyCommandArrayThrows() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc,
            dir,
            """
            try {
                Bun.spawnSync([]);
                globalThis.__threw = false;
            } catch (e) {
                globalThis.__threw = true;
                globalThis.__name = e.name;
            }
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__threw").asBoolean())
        assertEquals("TypeError", mc.eval("js", "globalThis.__name").asString())
    }
}

private fun String.jsQuoted(): String =
    "\"" + this.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun createTempDirectory(): File =
    kotlin.io.path.createTempDirectory("mycelium-bun-write-spawn-test-").toFile()

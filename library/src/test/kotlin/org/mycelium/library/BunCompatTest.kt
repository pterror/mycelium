package org.mycelium.library

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Bun-compat shim: runtime detection (BunRuntime.kt) + the `Bun` global (BUN_INIT_SCRIPT in
// Mycelium.kt). Scope is deliberately a partial slice of real Bun's API — see
// docs/reference/bun-compat.md for the checked-against-upstream list of what is/isn't
// covered (Bun.file/.serve/.env/.argv/.version/.revision only; no dlopen/ffi/sqlite/spawn/
// write/hash/password/build/Transpiler/bun:test/WebSocket upgrades).
//
// Parity with real Bun is checked *live*, not via hardcoded golden values: `bun` is a
// version-pinned nixpkgs package already declared in this repo's flake.nix devShell (added
// for building docs/), not a flaky network dependency, so every check below that can
// mechanically be diffed against a live `bun` process is — see bunCommand()/runBun()/
// runBunInDir()/startBunServerProcess() below, mirroring NodeParityTest's `node`-shelling-out
// pattern. Gated with `assumeTrue(bunCommand() != null, ...)` so the suite degrades
// gracefully (skips, doesn't fail) in an environment with no `bun` on PATH and no working
// `nix shell nixpkgs#bun` fallback — but in this repo's own dev environment (flake.nix
// provides bun 1.3.13) these actually run and actually compare live output.
//
// A few properties are inherently unable to be diffed 1:1 against a different binary
// (e.g. `Bun.argv[0]`/`Bun.version`/`Bun.revision` — real bun's own binary path/version
// string can never equal Mycelium's JVM-launcher path/an arbitrary version string) — those
// are documented as such at the point they're asserted, comparing *shape* rather than exact
// value where a real value can't apply.
class BunCompatTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun setUpVm() {
            vm = Mycelium()
        }

        lateinit var vm: Mycelium

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

        // Resolves once per test run (not per test) — same rationale as
        // NodeParityTest.nodeCommand(): a `nix shell` probe is too expensive to repeat per
        // test. Tries plain `bun` on PATH first (true in this repo's own `nix develop`
        // shell — flake.nix adds `pkgs.bun`), then falls back to `nix shell nixpkgs#bun`
        // for an environment where a bare shell doesn't have it on PATH yet. `null` means
        // no working bun was found at all — callers gate on this via `assumeTrue`.
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

        // Runs `bun run <scriptFile> args...` to completion (optionally in `dir`, so a
        // script's own relative-path references resolve the way a real Bun invocation
        // from that directory would), returning trimmed stdout. Throws with stderr
        // attached on nonzero exit so a real bun crash is distinguishable from "the
        // printed result didn't match".
        fun runBun(scriptFile: File, args: List<String> = emptyList(), dir: File? = null): String {
            val command = bunCommand() ?: error("bun not available")
            val builder = ProcessBuilder(command + listOf("run", scriptFile.absolutePath) + args)
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

        // Writes `script` to a probe file inside `dir` and runs it there via `runBun` —
        // used for the MIME-type/ENOENT live-parity checks below, where the script
        // references bare relative filenames the same way a real Bun script run from that
        // directory would.
        fun runBunInDir(dir: File, script: String): String {
            val scriptFile = File(dir, "bun-live-probe.mjs")
            scriptFile.writeText(script)
            return runBun(scriptFile, dir = dir)
        }

        // Starts `bun run <scriptContent>` as a long-running background process (for
        // Bun.serve live-parity checks) and returns it un-waited alongside the port it
        // reports having bound. The script is expected to `console.log("PORT:" + port)`
        // as its very first line of output before blocking on the server. The caller owns
        // the returned Process and must destroy it (`destroyForcibly()`) when done.
        fun startBunServerProcess(scriptContent: String): Pair<Process, Int> {
            val scriptFile = File.createTempFile("mycelium-bun-server-", ".mjs")
            scriptFile.deleteOnExit()
            scriptFile.writeText(scriptContent)
            val command = bunCommand() ?: error("bun not available")
            val process = ProcessBuilder(command + listOf("run", scriptFile.absolutePath))
                .redirectErrorStream(false)
                .start()
            val portLine = process.inputStream.bufferedReader().readLine()
                ?: run {
                    val stderr = process.errorStream.bufferedReader().readText()
                    process.destroyForcibly()
                    throw RuntimeException("real bun server produced no output (stderr: $stderr)")
                }
            val port = portLine.removePrefix("PORT:").trim().toInt()
            return process to port
        }
    }

    private fun runJsWithImports(mc: Mycelium, script: String) {
        val tmpFile = File.createTempFile("mycelium-bun-test-", ".mjs")
        tmpFile.deleteOnExit()
        tmpFile.writeText(script)
        mc.runFile(tmpFile.absolutePath)
    }

    // Same JS-in-a-known-directory helper as runJsWithImports, but the temp file is placed
    // inside a caller-supplied directory (rather than the system temp root) so
    // BunRuntime.isBunProject's ancestor walk sees whatever lockfile/config the test put
    // there. `runFile` treats the file's own parent directory as the project root for Bun
    // detection purposes (see Mycelium.runFile's `maybeInjectBunGlobals` call).
    private fun runJsInDir(mc: Mycelium, dir: File, script: String) {
        val file = File(dir, "entry.mjs")
        file.writeText(script)
        mc.runFile(file.absolutePath)
    }

    // --- 1. Runtime detection (BunRuntime), unit-level ---

    @Test fun detectsBunViaLockfile() {
        val dir = createTempDirectory()
        File(dir, "bun.lockb").writeBytes(ByteArray(4)) // binary format — presence is enough
        assertTrue(BunRuntime.isBunProject(dir))
        assertTrue(BunRuntime.detect(dir, "console.log('hi')"))
    }

    @Test fun detectsBunViaBunfigToml() {
        val dir = createTempDirectory()
        File(dir, "bunfig.toml").writeText("[install]\n")
        assertTrue(BunRuntime.isBunProject(dir))
    }

    @Test fun nodeSignalWinsOverBunSignalInSameDirectory() {
        // Per TODO.md's "Bun/Deno Compatibility Strategy": "a project with both
        // bunfig.toml and pnpm-lock.yaml is Node, not Bun" — Node signals are checked
        // first and win outright.
        val dir = createTempDirectory()
        File(dir, "bunfig.toml").writeText("[install]\n")
        File(dir, "pnpm-lock.yaml").writeText("lockfileVersion: '6.0'\n")
        assertFalse(BunRuntime.isBunProject(dir))
    }

    @Test fun noSignalsMeansNotBun() {
        val dir = createTempDirectory()
        assertFalse(BunRuntime.isBunProject(dir))
        assertFalse(BunRuntime.detect(dir, "console.log('plain node-ish script')"))
    }

    @Test fun detectsBunViaImportSpecifierPrefix() {
        assertTrue(BunRuntime.hasBunImportSpecifier("import { Database } from \"bun:sqlite\";"))
        assertTrue(BunRuntime.hasBunImportSpecifier("import { serve } from 'bun:test';"))
        assertTrue(BunRuntime.hasBunImportSpecifier("const ffi = require(\"bun:ffi\");"))
        assertTrue(BunRuntime.hasBunImportSpecifier("await import(\"bun:jsc\");"))
    }

    @Test fun bareBunSpecifierIsNotADetectionSignal() {
        // A bare `from "bun"` (the cross-runtime npm shim package, not a `bun:`-prefixed
        // core module) is deliberately not a signal — see BunRuntime's own doc comment.
        assertFalse(BunRuntime.hasBunImportSpecifier("import { serve } from \"bun\";"))
    }

    @Test fun isBunProjectWalksUpAncestors() {
        val root = createTempDirectory()
        File(root, "bun.lockb").writeBytes(ByteArray(0))
        val nested = File(root, "src/deeply/nested")
        nested.mkdirs()
        assertTrue(BunRuntime.isBunProject(nested))
    }

    // --- 2. Conditional injection end-to-end: Bun global present only when detected ---

    @Test fun bunGlobalAbsentByDefault() {
        val mc = vm
        runJsWithImports(mc, """
            globalThis.__bunType = typeof globalThis.Bun;
            // Node-compat globals must be unaffected by Bun *not* being detected — this is
            // the regression check for "don't break existing Node-compat behavior" per the
            // task this was implemented under.
            globalThis.__processType = typeof globalThis.process;
            globalThis.__bufferType = typeof globalThis.Buffer;
        """.trimIndent())
        assertEquals("undefined", mc.eval("js", "globalThis.__bunType").asString())
        assertEquals("object", mc.eval("js", "globalThis.__processType").asString())
        assertEquals("function", mc.eval("js", "globalThis.__bufferType").asString())
    }

    @Test fun bunGlobalPresentWhenLockfileDetected() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        File(dir, "bun.lockb").writeBytes(ByteArray(0))
        runJsInDir(mc, dir, """
            globalThis.__bunType = typeof globalThis.Bun;
            // Node-compat globals stay available alongside Bun's — real Bun also
            // implements most of Node's global surface for compat, this shim doesn't hide
            // process/Buffer/etc. just because Bun was detected.
            globalThis.__processType = typeof globalThis.process;
        """.trimIndent())
        assertEquals("object", mc.eval("js", "globalThis.__bunType").asString())
        assertEquals("object", mc.eval("js", "globalThis.__processType").asString())
    }

    @Test fun bunGlobalPresentWhenBunfigDetected() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        File(dir, "bunfig.toml").writeText("[install]\n")
        runJsInDir(mc, dir, "globalThis.__bunType = typeof globalThis.Bun;")
        assertEquals("object", mc.eval("js", "globalThis.__bunType").asString())
    }

    @Test fun bunGlobalPresentWhenBunImportSpecifierDetectedViaEvalModule() {
        val mc = Mycelium()
        // Never actually calls `require` (would throw — this is an ES module, not CJS) —
        // the literal text is enough to trigger BunRuntime.hasBunImportSpecifier's
        // source-level detection, independent of any lockfile/config on disk.
        mc.evalModule(
            "js",
            """
            function neverCalled() { return require('bun:sqlite'); }
            globalThis.__mp_bunType = typeof globalThis.Bun;
            """.trimIndent()
        )
        assertEquals("object", mc.eval("js", "globalThis.__mp_bunType").asString())
    }

    // --- 3. Bun.file() ---

    @Test fun bunFileHappyPath() {
        val mc = vm
        val dir = createTempDirectory()
        File(dir, "bunfig.toml").writeText("")
        val dataFile = File(dir, "greeting.txt")
        dataFile.writeText("hello from bun.file")
        runJsInDir(mc, dir, """
            const f = Bun.file(${dataFile.absolutePath.jsQuoted()});
            globalThis.__text = await f.text();
            globalThis.__exists = await f.exists();
            globalThis.__size = f.size;
            globalThis.__type = f.type;
            const ab = await f.arrayBuffer();
            globalThis.__abLen = ab.byteLength;
            globalThis.__abCtor = ab.constructor.name;
        """.trimIndent())
        assertEquals("hello from bun.file", mc.eval("js", "globalThis.__text").asString())
        assertTrue(mc.eval("js", "globalThis.__exists").asBoolean())
        assertEquals(dataFile.length(), mc.eval("js", "globalThis.__size").asLong())
        // .type for a .txt file is live-diffed against real bun in
        // bunFileMimeTypeGuessesMatchRealBunLive — just a sanity non-empty check here.
        assertTrue(mc.eval("js", "globalThis.__type").asString().isNotEmpty())
        assertEquals(dataFile.length(), mc.eval("js", "globalThis.__abLen").asLong())
        assertEquals("ArrayBuffer", mc.eval("js", "globalThis.__abCtor").asString())
    }

    @Test fun bunFileMimeTypeGuessesMatchRealBunLive() {
        assumeTrue(bunCommand() != null, "bun not available — skipping live parity check")
        // Extensions BUN_INIT_SCRIPT's MIME_TYPES table covers, plus a no-extension case —
        // every one of these is now diffed against a live `bun` process's own
        // `Bun.file(name).type`, not a value transcribed by hand. Includes a handful of the
        // short/obscure source-code extensions ("c"/"h"/"t"/"s"/"java") that MIME_TYPES only
        // gained once it was regenerated via tooling/generate-bun-mime-table.mjs against a
        // broad corpus (see that script's own header + BUN_INIT_SCRIPT's MIME_TYPES comment)
        // — these are exactly the cases BunFuzzTest.kt's bunFileMimeTypeGuessLiveDiffFuzz
        // used to have to dodge (via a length >= 5 restriction) before the table covered them.
        val extensions = listOf(
            "json", "txt", "html", "js", "mjs", "ts", "css", "md", "xml", "csv",
            "png", "jpg", "jpeg", "gif", "svg", "wasm", "pdf", "zip", "unknownext",
            "c", "h", "t", "s", "java", "lua", "sql", "toml",
        )
        val mc = Mycelium()
        val dir = createTempDirectory()
        File(dir, "bunfig.toml").writeText("")
        for (ext in extensions) {
            val dataFile = File(dir, "test.$ext")
            dataFile.writeText("")
            val liveType = runBunInDir(dir, "console.log(Bun.file(\"test.$ext\").type);")
            runJsInDir(mc, dir, "globalThis.__t = Bun.file(${dataFile.absolutePath.jsQuoted()}).type;")
            val shimType = mc.eval("js", "globalThis.__t").asString()
            assertEquals(liveType, shimType, "extension .$ext")
        }
        // No-extension file too — live-diffed the same way.
        val noExtFile = File(dir, "noextensionfile")
        noExtFile.writeText("")
        val liveNoExtType = runBunInDir(dir, "console.log(Bun.file(\"noextensionfile\").type);")
        runJsInDir(mc, dir, "globalThis.__t = Bun.file(${noExtFile.absolutePath.jsQuoted()}).type;")
        assertEquals(liveNoExtType, mc.eval("js", "globalThis.__t").asString(), "no-extension file")
    }

    @Test fun bunFileNonexistentPath() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        File(dir, "bunfig.toml").writeText("")
        val missing = File(dir, "does-not-exist.json").absolutePath
        runJsInDir(mc, dir, """
            const f = Bun.file(${missing.jsQuoted()});
            globalThis.__type = f.type;
            globalThis.__exists = await f.exists();
            globalThis.__size = f.size;
            try {
                await f.text();
                globalThis.__threw = false;
            } catch (e) {
                globalThis.__threw = true;
                globalThis.__errCode = e.code;
                globalThis.__errName = e.name;
                globalThis.__errMessage = e.message;
            }
        """.trimIndent())
        // `.type` is still guessed from the path alone even though the file doesn't exist
        // (no stat performed for `.type`) — the actual guessed string is live-diffed
        // against real bun in bunFileMimeTypeGuessesMatchRealBunLive; this just checks the
        // no-stat *behavior* (a plausible non-empty MIME string, not an exception).
        assertTrue(mc.eval("js", "globalThis.__type").asString().isNotEmpty())
        assertFalse(mc.eval("js", "globalThis.__exists").asBoolean())
        assertEquals(0L, mc.eval("js", "globalThis.__size").asLong())
        assertTrue(mc.eval("js", "globalThis.__threw").asBoolean())
    }

    // Live parity: the ENOENT-shaped error `Bun.file(missing).text()` rejects with, diffed
    // against a real `bun` process hitting the exact same (nonexistent) absolute path —
    // using the same path string means `code`/`name`/`message` (which embeds the path) can
    // be compared for byte-for-byte equality, not just "looks similar".
    @Test fun bunFileNonexistentPathErrorShapeMatchesRealBunLive() {
        assumeTrue(bunCommand() != null, "bun not available — skipping live parity check")
        val mc = Mycelium()
        val dir = createTempDirectory()
        File(dir, "bunfig.toml").writeText("")
        val missingPath = File(dir, "does-not-exist.json").absolutePath
        // Shared probe body — only the final "how do we get the result out" line differs
        // between the live-bun run (console.log) and the shim run (a global assignment
        // read back via mc.eval), so this is a true apples-to-apples behavioral check.
        val probeBody = """
            var __r;
            try {
                await Bun.file(${missingPath.jsQuoted()}).text();
                __r = { threw: false };
            } catch (e) {
                __r = { threw: true, code: e.code, name: e.name, message: e.message };
            }
        """.trimIndent()

        val liveJson = runBunInDir(dir, probeBody + "\nconsole.log(JSON.stringify(__r));")
        runJsInDir(mc, dir, probeBody + "\nglobalThis.__result = JSON.stringify(__r);")
        val shimJson = mc.eval("js", "globalThis.__result").asString()

        assertEquals(liveJson, shimJson)
    }

    @Test fun bunFileEmptyFile() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        File(dir, "bunfig.toml").writeText("")
        val emptyFile = File(dir, "empty.txt")
        emptyFile.writeText("")
        // Absolute path deliberately, not "empty.txt" relative to `dir`: Bun.file bypasses
        // the FileSystem SPI entirely (same intentional bypass as the node-stdlib fs.mjs
        // shim — see BUN_INIT_SCRIPT's own comment), so a relative path resolves against
        // this *JVM process's* real cwd, not the script's own directory. Verified this
        // matches real Bun too: `bun run subdir/script.js` from a parent directory, with
        // the script doing `Bun.file("root.txt")`, resolves against the invocation cwd —
        // not `subdir/` — under real bun 1.3.13 as well.
        runJsInDir(mc, dir, """
            const f = Bun.file(${emptyFile.absolutePath.jsQuoted()});
            globalThis.__text = await f.text();
            globalThis.__size = f.size;
            const ab = await f.arrayBuffer();
            globalThis.__abLen = ab.byteLength;
        """.trimIndent())
        // Verified against real bun 1.3.13: an empty file's `.text()` resolves to `""`,
        // `.size` is `0`, `.arrayBuffer()` resolves to a zero-length ArrayBuffer.
        assertEquals("", mc.eval("js", "globalThis.__text").asString())
        assertEquals(0L, mc.eval("js", "globalThis.__size").asLong())
        assertEquals(0, mc.eval("js", "globalThis.__abLen").asInt())
    }

    @Test fun bunFileJsonHelper() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        File(dir, "bunfig.toml").writeText("")
        val jsonFile = File(dir, "data.json")
        jsonFile.writeText("{\"a\":1,\"b\":[2,3]}")
        // Absolute path — see bunFileEmptyFile's comment for why a relative path would
        // resolve against the JVM's own cwd rather than `dir`.
        runJsInDir(mc, dir, """
            const parsed = await Bun.file(${jsonFile.absolutePath.jsQuoted()}).json();
            globalThis.__a = parsed.a;
            globalThis.__bLen = parsed.b.length;
        """.trimIndent())
        assertEquals(1, mc.eval("js", "globalThis.__a").asInt())
        assertEquals(2, mc.eval("js", "globalThis.__bLen").asInt())
    }

    // --- 4. Bun.env ---

    @Test fun bunEnvSharesProcessEnv() {
        val mc = Mycelium(cwd = createTempDirectory().absolutePath, env = mapOf("MYCELIUM_BUN_TEST_VAR" to "hello"))
        val dir = createTempDirectory()
        File(dir, "bunfig.toml").writeText("")
        runJsInDir(mc, dir, """
            globalThis.__val = Bun.env.MYCELIUM_BUN_TEST_VAR;
            globalThis.__same = Bun.env === process.env;
        """.trimIndent())
        assertEquals("hello", mc.eval("js", "globalThis.__val").asString())
        // The *value* of `Bun.env === process.env` is live-diffed against real bun in
        // bunEnvIdentityMatchesRealBunLive; this test's own job is just checking the shim
        // actually reflects an env var through Bun.env at all.
        assertTrue(mc.eval("js", "globalThis.__same").asBoolean())
    }

    // Live parity: whether `Bun.env === process.env` really is `true` under real Bun (not
    // just assumed from documentation/memory) — same object-identity question, diffed
    // against a live `bun -e` invocation rather than hardcoded.
    @Test fun bunEnvIdentityMatchesRealBunLive() {
        assumeTrue(bunCommand() != null, "bun not available — skipping live parity check")
        val scriptFile = File.createTempFile("mycelium-bun-env-identity-", ".mjs")
        scriptFile.deleteOnExit()
        scriptFile.writeText("console.log(Bun.env === process.env);")
        val liveOutput = runBun(scriptFile)
        val liveIdentity = liveOutput.trim().toBooleanStrict()

        val mc = Mycelium()
        val dir = createTempDirectory()
        File(dir, "bunfig.toml").writeText("")
        runJsInDir(mc, dir, "globalThis.__same = Bun.env === process.env;")
        val shimIdentity = mc.eval("js", "globalThis.__same").asBoolean()

        assertEquals(liveIdentity, shimIdentity)
    }

    @Test fun bunEnvMutationVisibleImmediately() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        File(dir, "bunfig.toml").writeText("")
        runJsInDir(mc, dir, """
            Bun.env.MYCELIUM_BUN_WRITE_TEST = "written";
            globalThis.__readBack = process.env.MYCELIUM_BUN_WRITE_TEST;
        """.trimIndent())
        assertEquals("written", mc.eval("js", "globalThis.__readBack").asString())
    }

    // --- 5. Bun.argv / Bun.version / Bun.revision ---

    @Test fun bunArgvShapeMatchesRealBunConvention() {
        // Non-live sanity check on the shim alone (shape it *can* deliver: 2 elements,
        // [execPath, scriptPath] — see BUN_INIT_SCRIPT's own comment for why extra CLI
        // args aren't available at all). The live cross-check against real bun's own
        // argv convention is bunArgvShapeMatchesRealBunLive below.
        val mc = Mycelium()
        val dir = createTempDirectory()
        File(dir, "bunfig.toml").writeText("")
        runJsInDir(mc, dir, """
            globalThis.__argvLen = Bun.argv.length;
            globalThis.__argv0 = Bun.argv[0];
            globalThis.__argv1 = Bun.argv[1];
        """.trimIndent())
        assertEquals(2, mc.eval("js", "globalThis.__argvLen").asInt())
        assertTrue(mc.eval("js", "globalThis.__argv0").asString().isNotEmpty())
        assertTrue(mc.eval("js", "globalThis.__argv1").asString().endsWith("entry.mjs"))
    }

    // Live parity: runs the *same* script file, with the *same* absolute path, through
    // both real bun (with two extra CLI args, since real bun actually supports them) and
    // the shim (via Mycelium.runFile, which never threads extra CLI args through at all —
    // see BUN_INIT_SCRIPT's comment). `argv[0]` (the executable path) can never match
    // between the two — they're different binaries — so this compares the parts that
    // *can* line up: using the identical absolute script path string for both runs means
    // `argv[1]` must come out byte-for-byte identical if both follow the same
    // [execPath, scriptPath, ...args] convention, which is asserted directly against real
    // bun's own live output rather than a hardcoded assumption about its shape.
    @Test fun bunArgvShapeMatchesRealBunLive() {
        assumeTrue(bunCommand() != null, "bun not available — skipping live parity check")
        val dir = createTempDirectory()
        File(dir, "bunfig.toml").writeText("")
        val scriptFile = File(dir, "argv-live-probe.mjs")
        scriptFile.writeText("globalThis.__argv = Bun.argv;\nconsole.log(JSON.stringify(Bun.argv));")

        val liveJson = runBun(scriptFile, listOf("foo", "bar"), dir = dir)
        val liveArgv = org.json.JSONArray(liveJson).let { arr -> (0 until arr.length()).map { arr.getString(it) } }
        // Real bun's own convention, verified live rather than assumed: execPath,
        // scriptPath, then trailing CLI args in order.
        assertEquals(4, liveArgv.size, "real bun's own Bun.argv for 2 trailing args")
        assertEquals(scriptFile.absolutePath, liveArgv[1])
        assertEquals("foo", liveArgv[2])
        assertEquals("bar", liveArgv[3])

        val mc = Mycelium()
        mc.runFile(scriptFile.absolutePath)
        val shimArgvLen = mc.eval("js", "globalThis.__argv.length").asInt()
        val shimArgv1 = mc.eval("js", "globalThis.__argv[1]").asString()

        // The shim can't reproduce trailing args (documented limitation) but the element
        // it *does* claim to be the script path must equal what real bun reports in the
        // same position for the same file.
        assertEquals(2, shimArgvLen)
        assertEquals(liveArgv[1], shimArgv1)
    }

    @Test fun bunVersionAndRevisionAreNonEmptyStrings() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        File(dir, "bunfig.toml").writeText("")
        runJsInDir(mc, dir, """
            globalThis.__version = Bun.version;
            globalThis.__revision = Bun.revision;
            globalThis.__versionIsSemverLike = /^\d+\.\d+\.\d+/.test(Bun.version);
        """.trimIndent())
        assertTrue(mc.eval("js", "globalThis.__version").asString().isNotEmpty())
        assertTrue(mc.eval("js", "globalThis.__revision").asString().isNotEmpty())
        assertTrue(mc.eval("js", "globalThis.__versionIsSemverLike").asBoolean())
    }

    // --- 6. Bun.serve() ---
    //
    // Each test below uses its own dedicated Mycelium instance, mirroring
    // MyceliumIntegrationTest's importHttpModule test: once a real HTTP request is issued
    // against the JDK HttpServer backing Bun.serve, its accept/dispatch thread enters the
    // polyglot Context to run the JS `fetch` handler, and GraalVM does not hand the Context
    // back to the original (test) thread afterward — so a shared `vm` would be poisoned
    // for every other test in this class. All assertions after issuing HTTP requests below
    // read from the HTTP responses themselves, never from `mc.eval(...)` again.

    @Test fun bunServeHappyPath() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        File(dir, "bunfig.toml").writeText("")
        runJsInDir(mc, dir, """
            globalThis.__server = Bun.serve({
                port: 0,
                fetch(req) {
                    const url = new URL(req.url);
                    return new Response("hello " + req.method + " " + url.pathname, {
                        status: 200,
                        headers: { "X-Test": "1" },
                    });
                },
            });
            globalThis.__port = globalThis.__server.port;
        """.trimIndent())
        val port = mc.eval("js", "globalThis.__port").asInt()
        val conn = URI("http://127.0.0.1:$port/greet").toURL().openConnection() as HttpURLConnection
        try {
            assertEquals(200, conn.responseCode)
            assertEquals("hello GET /greet", conn.inputStream.readBytes().toString(Charsets.UTF_8))
            assertEquals("1", conn.getHeaderField("X-Test"))
        } finally {
            conn.disconnect()
        }
    }

    @Test fun bunServeDefaultPortIs3000WhenUnspecified() {
        // Bun's documented default port (used when `Bun.serve({ fetch })` omits `port`
        // entirely) is 3000 — this shim matches it (see BUN_INIT_SCRIPT's bunServe:
        // `options.port !== undefined ? Number(options.port) : 3000`). Not independently
        // re-verified against the real binary in this pass (would need a free port 3000 in
        // whatever environment runs this probe). This test skips itself (rather than
        // failing) if port 3000 is already bound by something else in the sandbox running
        // the test suite — observed to be the case in the dev environment this was
        // written in (some other long-running dev tool already listens on 3000), so a hard
        // assertion here would be a permanent, environment-specific failure unrelated to
        // the shim's own correctness.
        val mc = Mycelium()
        val dir = createTempDirectory()
        File(dir, "bunfig.toml").writeText("")
        try {
            runJsInDir(mc, dir, """
                globalThis.__server = Bun.serve({ fetch() { return new Response("ok"); } });
                globalThis.__port = globalThis.__server.port;
                globalThis.__server.stop();
            """.trimIndent())
        } catch (e: Exception) {
            // PolyglotException wraps the host BindException rather than chaining it via
            // `.cause` — `.asHostException()` unwraps it when this *is* a host exception,
            // caught for any (non-Bun-related) exception since a plain `.message` text
            // check is otherwise the only fallback.
            val hostException = (e as? org.graalvm.polyglot.PolyglotException)
                ?.takeIf { it.isHostException }
                ?.asHostException()
            val bindConflict = generateSequence(hostException) { it.cause }.any { it is java.net.BindException } ||
                (e.message?.contains("Address already in use") == true)
            assumeTrue(
                !bindConflict,
                "port 3000 already in use in this environment — skipping (see test comment)"
            )
            throw e
        }
        assertEquals(3000, mc.eval("js", "globalThis.__port").asInt())
    }

    @Test fun bunServeHandlerThrowsReturnsDefault500() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        File(dir, "bunfig.toml").writeText("")
        runJsInDir(mc, dir, """
            globalThis.__server = Bun.serve({
                port: 0,
                development: false,
                fetch(req) { throw new Error("boom"); },
            });
            globalThis.__port = globalThis.__server.port;
        """.trimIndent())
        val port = mc.eval("js", "globalThis.__port").asInt()
        val conn = URI("http://127.0.0.1:$port/").toURL().openConnection() as HttpURLConnection
        try {
            // The exact status/body/content-type real bun answers with here is live-diffed
            // in bunServeDefault500MatchesRealBunLive; this just checks the shim itself
            // answers *something* sane for a thrown handler rather than hanging/crashing.
            assertEquals(500, conn.responseCode)
            assertTrue(conn.errorStream.readBytes().toString(Charsets.UTF_8).isNotEmpty())
        } finally {
            conn.disconnect()
        }
    }

    @Test fun bunServeHandlerReturningNonResponseGetsPlaceholder200() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        File(dir, "bunfig.toml").writeText("")
        runJsInDir(mc, dir, """
            globalThis.__server = Bun.serve({
                port: 0,
                fetch(req) { return "not a response"; },
            });
            globalThis.__port = globalThis.__server.port;
        """.trimIndent())
        val port = mc.eval("js", "globalThis.__port").asInt()
        val conn = URI("http://127.0.0.1:$port/").toURL().openConnection() as HttpURLConnection
        try {
            // The exact placeholder body real bun answers with here is live-diffed in
            // bunServeNonResponseReturnMatchesRealBunLive; this just checks the shim
            // itself treats a non-Response return as success (200), not an error.
            assertEquals(200, conn.responseCode)
            assertTrue(conn.inputStream.readBytes().toString(Charsets.UTF_8).isNotEmpty())
        } finally {
            conn.disconnect()
        }
    }

    // Live parity: spins up a *real* `bun` process running `Bun.serve` with the same
    // fetch-throws scenario, hits it over real HTTP, and diffs its response
    // (status/body/content-type) against the shim's own server for the identical
    // scenario — not a value transcribed by hand during development.
    @Test fun bunServeDefault500MatchesRealBunLive() {
        assumeTrue(bunCommand() != null, "bun not available — skipping live parity check")
        val serverScript = """
            const server = Bun.serve({
                port: 0,
                development: false,
                fetch(req) { throw new Error("boom"); },
            });
            console.log("PORT:" + server.port);
        """.trimIndent()
        val (process, livePort) = startBunServerProcess(serverScript)
        try {
            val liveConn = URI("http://127.0.0.1:$livePort/").toURL().openConnection() as HttpURLConnection
            val liveStatus: Int
            val liveBody: String
            val liveContentType: String?
            try {
                liveStatus = liveConn.responseCode
                liveBody = liveConn.errorStream.readBytes().toString(Charsets.UTF_8)
                liveContentType = liveConn.getHeaderField("Content-Type")
            } finally {
                liveConn.disconnect()
            }

            val mc = Mycelium()
            val dir = createTempDirectory()
            File(dir, "bunfig.toml").writeText("")
            runJsInDir(mc, dir, """
                globalThis.__server = Bun.serve({
                    port: 0,
                    development: false,
                    fetch(req) { throw new Error("boom"); },
                });
                globalThis.__port = globalThis.__server.port;
            """.trimIndent())
            val shimPort = mc.eval("js", "globalThis.__port").asInt()
            val shimConn = URI("http://127.0.0.1:$shimPort/").toURL().openConnection() as HttpURLConnection
            val shimStatus: Int
            val shimBody: String
            val shimContentType: String?
            try {
                shimStatus = shimConn.responseCode
                shimBody = shimConn.errorStream.readBytes().toString(Charsets.UTF_8)
                shimContentType = shimConn.getHeaderField("Content-Type")
            } finally {
                shimConn.disconnect()
            }

            assertEquals(liveStatus, shimStatus)
            assertEquals(liveBody, shimBody)
            assertEquals(liveContentType, shimContentType)
        } finally {
            process.destroyForcibly()
        }
    }

    // Live parity: same idea as above, for the "fetch returned something other than a
    // Response" scenario.
    @Test fun bunServeNonResponseReturnMatchesRealBunLive() {
        assumeTrue(bunCommand() != null, "bun not available — skipping live parity check")
        val serverScript = """
            const server = Bun.serve({
                port: 0,
                fetch(req) { return "not a response"; },
            });
            console.log("PORT:" + server.port);
        """.trimIndent()
        val (process, livePort) = startBunServerProcess(serverScript)
        try {
            val liveConn = URI("http://127.0.0.1:$livePort/").toURL().openConnection() as HttpURLConnection
            val liveStatus: Int
            val liveBody: String
            try {
                liveStatus = liveConn.responseCode
                liveBody = liveConn.inputStream.readBytes().toString(Charsets.UTF_8)
            } finally {
                liveConn.disconnect()
            }

            val mc = Mycelium()
            val dir = createTempDirectory()
            File(dir, "bunfig.toml").writeText("")
            runJsInDir(mc, dir, """
                globalThis.__server = Bun.serve({
                    port: 0,
                    fetch(req) { return "not a response"; },
                });
                globalThis.__port = globalThis.__server.port;
            """.trimIndent())
            val shimPort = mc.eval("js", "globalThis.__port").asInt()
            val shimConn = URI("http://127.0.0.1:$shimPort/").toURL().openConnection() as HttpURLConnection
            val shimStatus: Int
            val shimBody: String
            try {
                shimStatus = shimConn.responseCode
                shimBody = shimConn.inputStream.readBytes().toString(Charsets.UTF_8)
            } finally {
                shimConn.disconnect()
            }

            assertEquals(liveStatus, shimStatus)
            assertEquals(liveBody, shimBody)
        } finally {
            process.destroyForcibly()
        }
    }

    @Test fun bunServeAsyncFetchHandler() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        File(dir, "bunfig.toml").writeText("")
        runJsInDir(mc, dir, """
            globalThis.__server = Bun.serve({
                port: 0,
                async fetch(req) {
                    await new Promise((resolve) => resolve());
                    return new Response("async ok");
                },
            });
            globalThis.__port = globalThis.__server.port;
        """.trimIndent())
        val port = mc.eval("js", "globalThis.__port").asInt()
        val conn = URI("http://127.0.0.1:$port/").toURL().openConnection() as HttpURLConnection
        try {
            assertEquals(200, conn.responseCode)
            assertEquals("async ok", conn.inputStream.readBytes().toString(Charsets.UTF_8))
        } finally {
            conn.disconnect()
        }
    }

    @Test fun bunServeAsyncFetchHandlerRejectionReturnsDefault500() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        File(dir, "bunfig.toml").writeText("")
        runJsInDir(mc, dir, """
            globalThis.__server = Bun.serve({
                port: 0,
                development: false,
                async fetch(req) {
                    await new Promise((resolve) => resolve());
                    throw new Error("async boom");
                },
            });
            globalThis.__port = globalThis.__server.port;
        """.trimIndent())
        val port = mc.eval("js", "globalThis.__port").asInt()
        val conn = URI("http://127.0.0.1:$port/").toURL().openConnection() as HttpURLConnection
        try {
            assertEquals(500, conn.responseCode)
            assertEquals("Something went wrong!", conn.errorStream.readBytes().toString(Charsets.UTF_8))
        } finally {
            conn.disconnect()
        }
    }

    @Test fun bunServeRequestBodyReadableAsTextAndJson() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        File(dir, "bunfig.toml").writeText("")
        runJsInDir(mc, dir, """
            globalThis.__server = Bun.serve({
                port: 0,
                async fetch(req) {
                    const body = await req.text();
                    return new Response(JSON.stringify({
                        method: req.method,
                        contentType: req.headers.get("content-type"),
                        body,
                    }));
                },
            });
            globalThis.__port = globalThis.__server.port;
        """.trimIndent())
        val port = mc.eval("js", "globalThis.__port").asInt()
        val conn = URI("http://127.0.0.1:$port/echo").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "text/plain")
        try {
            conn.outputStream.use { it.write("payload-body".toByteArray()) }
            assertEquals(200, conn.responseCode)
            val respBody = conn.inputStream.readBytes().toString(Charsets.UTF_8)
            assertTrue(respBody.contains("\"method\":\"POST\""))
            assertTrue(respBody.contains("payload-body"))
            assertTrue(respBody.contains("text/plain"))
        } finally {
            conn.disconnect()
        }
    }

    @Test fun bunServeHandlesConcurrentSequentialRequests() {
        // The underlying JDK HttpServer is single-threaded here (setExecutor(null) — same
        // as http.mjs's Server class), so "concurrent" requests are actually serviced one
        // at a time on that one dispatch thread (a single polyglot Context cannot be
        // entered by two threads at once regardless). This test issues several requests
        // concurrently from the *client* side to prove that serialization doesn't corrupt
        // per-request state (each response must reflect its own request's path, not some
        // other in-flight request's).
        val mc = Mycelium()
        val dir = createTempDirectory()
        File(dir, "bunfig.toml").writeText("")
        runJsInDir(mc, dir, """
            globalThis.__server = Bun.serve({
                port: 0,
                fetch(req) {
                    const url = new URL(req.url);
                    return new Response(url.pathname);
                },
            });
            globalThis.__port = globalThis.__server.port;
        """.trimIndent())
        val port = mc.eval("js", "globalThis.__port").asInt()

        val n = 8
        val pool = Executors.newFixedThreadPool(n)
        val latch = CountDownLatch(n)
        val results = java.util.concurrent.ConcurrentHashMap<Int, String>()
        try {
            for (i in 0 until n) {
                pool.submit {
                    try {
                        val conn = URI("http://127.0.0.1:$port/path-$i").toURL().openConnection() as HttpURLConnection
                        val body = conn.inputStream.readBytes().toString(Charsets.UTF_8)
                        results[i] = body
                        conn.disconnect()
                    } finally {
                        latch.countDown()
                    }
                }
            }
            assertTrue(latch.await(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdown()
        }
        assertEquals(n, results.size)
        for (i in 0 until n) {
            assertEquals("/path-$i", results[i])
        }
    }
}

// Small helper: renders a Kotlin string as a double-quoted JS string literal, escaping
// backslashes/quotes — used to safely embed absolute file paths (which may contain
// backslashes on some platforms, though not in practice under this Linux CI) into
// generated JS source in the tests above.
private fun String.jsQuoted(): String =
    "\"" + this.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun createTempDirectory(): File =
    kotlin.io.path.createTempDirectory("mycelium-bun-test-").toFile()

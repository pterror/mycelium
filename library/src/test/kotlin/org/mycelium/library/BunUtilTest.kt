package org.mycelium.library

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

// Utility/crypto/misc Bun.* APIs — Bun.hash(.*)/CryptoHasher/password/deepEquals/escapeHTML/
// inspect/sleep(Sync)/nanoseconds/which/randomUUIDv7/gzipSync-family/stringWidth. Kept as its
// own file (rather than appended to BunCompatTest.kt/BunWriteSpawnTest.kt) specifically to
// avoid merge friction with other concurrent worktrees also touching Bun-compat test files —
// see docs/reference/bun-compat.md for the authoritative, checked-against-upstream
// description of what's implemented here and what's deliberately left out.
//
// Same live-parity discipline as the sibling Bun test files: every behavior that can
// mechanically be diffed against a real `bun` process is, via the duplicated
// bunCommand()/runBun()/runBunInDir() helpers below (kept file-local rather than shared) —
// not hardcoded golden values, except where explicitly noted (Bun.peek is not implemented at
// all — see docs — and a handful of hash algorithms this shim doesn't attempt are simply
// never asserted).
class BunUtilTest {
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
            val scriptFile = File(dir, "bun-util-probe.mjs")
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

    private fun String.jsQuoted(): String =
        "\"" + this.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // --- Bun.hash() / Bun.hash.* — live parity against real bun ---

    @Test fun bunHashDefaultMatchesWyhashLiveParity() {
        assumeTrue(bunCommand() != null, "bun not available")
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        val inputs = listOf("", "a", "abc", "hello world", "x".repeat(50), "the quick brown fox jumps over the lazy dog")
        for (input in inputs) {
            runJsInDir(
                mc, dir,
                """
                globalThis.__h = Bun.hash(${input.jsQuoted()}).toString();
                globalThis.__wy = Bun.hash.wyhash(${input.jsQuoted()}).toString();
                """.trimIndent()
            )
            val shimHash = mc.eval("js", "globalThis.__h").asString()
            val shimWyhash = mc.eval("js", "globalThis.__wy").asString()
            assertEquals(shimWyhash, shimHash, "Bun.hash() should equal Bun.hash.wyhash() for '$input'")

            val real = runBunInDir(dir, "console.log(Bun.hash(${input.jsQuoted()}).toString());")
            assertEquals(real, shimHash, "Bun.hash('$input') should match real bun")
        }
    }

    @Test fun bunHashWyhashSeededLiveParity() {
        assumeTrue(bunCommand() != null, "bun not available")
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(dir.let { mc }, dir, "globalThis.__h = Bun.hash.wyhash(\"abc\", 42n).toString();")
        val shim = mc.eval("js", "globalThis.__h").asString()
        val real = runBunInDir(dir, "console.log(Bun.hash.wyhash(\"abc\", 42n).toString());")
        assertEquals(real, shim)
    }

    @Test fun bunHashCrc32LiveParityWithSeed() {
        assumeTrue(bunCommand() != null, "bun not available")
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        for ((input, seed) in listOf("abc" to null, "abc" to 5, "abc" to 42, "x".repeat(100) to 7, "" to null)) {
            val call = if (seed == null) "Bun.hash.crc32(${input.jsQuoted()})" else "Bun.hash.crc32(${input.jsQuoted()}, $seed)"
            runJsInDir(mc, dir, "globalThis.__h = $call;")
            val shim = mc.eval("js", "globalThis.__h").asLong()
            val real = runBunInDir(dir, "console.log($call);").toLong()
            assertEquals(real, shim, "crc32('$input', seed=$seed)")
        }
    }

    @Test fun bunHashAdler32IgnoresSeedLiveParity() {
        assumeTrue(bunCommand() != null, "bun not available")
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        // Documented real-Bun quirk: adler32's seed argument is silently ignored.
        runJsInDir(mc, dir, "globalThis.__a = Bun.hash.adler32(\"abc\"); globalThis.__b = Bun.hash.adler32(\"abc\", 7);")
        assertEquals(mc.eval("js", "globalThis.__a").asLong(), mc.eval("js", "globalThis.__b").asLong())
        val real = runBunInDir(dir, "console.log(Bun.hash.adler32(\"abc\"));").toLong()
        assertEquals(real, mc.eval("js", "globalThis.__a").asLong())
    }

    @Test fun bunHashMurmurVariantsLiveParity() {
        assumeTrue(bunCommand() != null, "bun not available")
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        val inputs = listOf("", "abc", "x".repeat(30), "x".repeat(100), "the quick brown fox jumps over the lazy dog")
        for (input in inputs) {
            runJsInDir(
                mc, dir,
                """
                globalThis.__v2 = Bun.hash.murmur32v2(${input.jsQuoted()});
                globalThis.__v3 = Bun.hash.murmur32v3(${input.jsQuoted()});
                globalThis.__v264 = Bun.hash.murmur64v2(${input.jsQuoted()}).toString();
                """.trimIndent()
            )
            val real2 = runBunInDir(dir, "console.log(Bun.hash.murmur32v2(${input.jsQuoted()}));").toLong()
            val real3 = runBunInDir(dir, "console.log(Bun.hash.murmur32v3(${input.jsQuoted()}));").toLong()
            val real264 = runBunInDir(dir, "console.log(Bun.hash.murmur64v2(${input.jsQuoted()}).toString());")
            assertEquals(real2, mc.eval("js", "globalThis.__v2").asLong(), "murmur32v2('$input')")
            assertEquals(real3, mc.eval("js", "globalThis.__v3").asLong(), "murmur32v3('$input')")
            assertEquals(real264, mc.eval("js", "globalThis.__v264").asString(), "murmur64v2('$input')")
        }
    }

    @Test fun bunHashCityHash64LiveParityAcrossLengthBranches() {
        assumeTrue(bunCommand() != null, "bun not available")
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        // Deliberately covers every CityHash64 length-dispatch branch: 0-16, 17-32, 33-64,
        // and the >64 iterative-mixing loop (including a non-multiple-of-64 tail).
        val inputs = listOf("", "a", "abc", "x".repeat(16), "x".repeat(20), "x".repeat(32), "x".repeat(40), "x".repeat(64), "x".repeat(65), "x".repeat(200))
        for (input in inputs) {
            runJsInDir(mc, dir, "globalThis.__h = Bun.hash.cityHash64(${input.jsQuoted()}).toString();")
            val shim = mc.eval("js", "globalThis.__h").asString()
            val real = runBunInDir(dir, "console.log(Bun.hash.cityHash64(${input.jsQuoted()}).toString());")
            assertEquals(real, shim, "cityHash64 len=${input.length}")
        }
        // Seeded variant too.
        runJsInDir(mc, dir, "globalThis.__hs = Bun.hash.cityHash64(\"abc\", 42n).toString();")
        val realSeeded = runBunInDir(dir, "console.log(Bun.hash.cityHash64(\"abc\", 42n).toString());")
        assertEquals(realSeeded, mc.eval("js", "globalThis.__hs").asString())
    }

    @Test fun bunHashCityHash32NotImplemented() {
        // Documented, deliberate gap — see BUN_INIT_SCRIPT's own comment and
        // docs/reference/bun-compat.md: the >24-byte iterative-mixing path could not be
        // reverse-engineered to bit-exactness, so cityHash32 is left out entirely rather
        // than risk a silently-wrong hash.
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(mc, dir, "globalThis.__type = typeof (Bun.hash && Bun.hash.cityHash32);")
        assertEquals("undefined", mc.eval("js", "globalThis.__type").asString())
    }

    @Test fun bunHashXxHashAndRapidhashNotImplemented() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            globalThis.__xx32 = typeof Bun.hash.xxHash32;
            globalThis.__xx64 = typeof Bun.hash.xxHash64;
            globalThis.__xx3 = typeof Bun.hash.xxHash3;
            globalThis.__rapid = typeof Bun.hash.rapidhash;
            """.trimIndent()
        )
        for (name in listOf("__xx32", "__xx64", "__xx3", "__rapid")) {
            assertEquals("undefined", mc.eval("js", "globalThis.$name").asString())
        }
    }

    // --- Bun.CryptoHasher ---

    @Test fun cryptoHasherSha256LiveParity() {
        assumeTrue(bunCommand() != null, "bun not available")
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            const h = new Bun.CryptoHasher("sha256");
            h.update("hello");
            h.update(" world");
            globalThis.__hex = h.digest("hex");
            """.trimIndent()
        )
        val shim = mc.eval("js", "globalThis.__hex").asString()
        val real = runBunInDir(
            dir,
            """
            const h = new Bun.CryptoHasher("sha256");
            h.update("hello");
            h.update(" world");
            console.log(h.digest("hex"));
            """.trimIndent()
        )
        assertEquals(real, shim)
    }

    @Test fun cryptoHasherMd5AndBase64LiveParity() {
        assumeTrue(bunCommand() != null, "bun not available")
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(mc, dir, "globalThis.__b64 = new Bun.CryptoHasher(\"md5\").update(\"abc\").digest(\"base64\");")
        val shim = mc.eval("js", "globalThis.__b64").asString()
        val real = runBunInDir(dir, "console.log(new Bun.CryptoHasher(\"md5\").update(\"abc\").digest(\"base64\"));")
        assertEquals(real, shim)
    }

    @Test fun cryptoHasherHmacLiveParity() {
        assumeTrue(bunCommand() != null, "bun not available")
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(mc, dir, "globalThis.__h = new Bun.CryptoHasher(\"sha256\", \"mykey\").update(\"data\").digest(\"hex\");")
        val shim = mc.eval("js", "globalThis.__h").asString()
        val real = runBunInDir(dir, "console.log(new Bun.CryptoHasher(\"sha256\", \"mykey\").update(\"data\").digest(\"hex\"));")
        assertEquals(real, shim)
    }

    @Test fun cryptoHasherDigestReturnsBufferByDefault() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            const d = new Bun.CryptoHasher("sha256").update("x").digest();
            globalThis.__isBuffer = Buffer.isBuffer(d);
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__isBuffer").asBoolean())
    }

    @Test fun cryptoHasherDigestResetsStateLikeRealBun() {
        // Verified against real Bun 1.3.13: calling digest() a second time (with no update()
        // in between) hashes the empty string, i.e. the digest resets after finalizing —
        // this shim gets this "for free" from java.security.MessageDigest's own documented
        // reset-after-digest() behavior.
        assumeTrue(bunCommand() != null, "bun not available")
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            const h = new Bun.CryptoHasher("md5");
            h.update("test");
            h.digest("hex");
            globalThis.__second = h.digest("hex");
            """.trimIndent()
        )
        val shim = mc.eval("js", "globalThis.__second").asString()
        val real = runBunInDir(
            dir,
            """
            const h = new Bun.CryptoHasher("md5");
            h.update("test");
            h.digest("hex");
            console.log(h.digest("hex"));
            """.trimIndent()
        )
        assertEquals(real, shim)
    }

    @Test fun cryptoHasherCopyIndependence() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            const hh = new Bun.CryptoHasher("sha256");
            hh.update("abc");
            const hh2 = hh.copy();
            hh.update("def");
            hh2.update("xyz");
            globalThis.__orig = hh.digest("hex");
            globalThis.__copy = hh2.digest("hex");
            """.trimIndent()
        )
        assertNotEquals(mc.eval("js", "globalThis.__orig").asString(), mc.eval("js", "globalThis.__copy").asString())
    }

    @Test fun cryptoHasherUnsupportedAlgorithmThrowsClearly() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            globalThis.__threw = false;
            try {
                new Bun.CryptoHasher("blake2b256");
            } catch (e) {
                globalThis.__threw = true;
                globalThis.__msg = String(e.message);
            }
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__threw").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__msg").asString().contains("blake2b256"))
    }

    @Test fun cryptoHasherStaticHashConvenience() {
        assumeTrue(bunCommand() != null, "bun not available")
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(mc, dir, "globalThis.__h = Bun.CryptoHasher.hash(\"sha256\", \"abc\", \"hex\");")
        val shim = mc.eval("js", "globalThis.__h").asString()
        val real = runBunInDir(dir, "console.log(Bun.CryptoHasher.hash(\"sha256\", \"abc\", \"hex\"));")
        assertEquals(real, shim)
    }

    // --- Bun.password ---

    @Test fun passwordHashAndVerifyRoundTrip() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            const hash = await Bun.password.hash("s3cret");
            globalThis.__correct = await Bun.password.verify("s3cret", hash);
            globalThis.__wrong = await Bun.password.verify("nope", hash);
            globalThis.__prefix = hash.startsWith("${'$'}mycelium-pbkdf2-sha256${'$'}");
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__correct").asBoolean())
        assertFalse(mc.eval("js", "globalThis.__wrong").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__prefix").asBoolean())
    }

    @Test fun passwordHashSyncAndVerifySyncRoundTrip() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            const hash = Bun.password.hashSync("s3cret");
            globalThis.__correct = Bun.password.verifySync("s3cret", hash);
            globalThis.__wrong = Bun.password.verifySync("nope", hash);
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__correct").asBoolean())
        assertFalse(mc.eval("js", "globalThis.__wrong").asBoolean())
    }

    @Test fun passwordVerifyRejectsForeignHashFormat() {
        // A real-Bun-produced argon2id hash cannot be verified by this shim — documented
        // divergence; asserted as a clear thrown error rather than a silent `false`.
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            globalThis.__threw = false;
            try {
                await Bun.password.verify("x", "${'$'}argon2id${'$'}v=19${'$'}m=65536,t=2,p=1${'$'}abc${'$'}def");
            } catch (e) {
                globalThis.__threw = true;
            }
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__threw").asBoolean())
    }

    // --- Bun.deepEquals ---

    @Test fun deepEqualsLiveParityAcrossShapes() {
        assumeTrue(bunCommand() != null, "bun not available")
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        val checks = listOf(
            """Bun.deepEquals({a:1,b:[1,2,3]}, {a:1,b:[1,2,3]})""",
            """Bun.deepEquals({a:1}, {a:1,b:2})""",
            """Bun.deepEquals({a:1}, {a:1,b:2}, true)""",
            """Bun.deepEquals([1,2],[1,2])""",
            """Bun.deepEquals(NaN, NaN)""",
            """Bun.deepEquals(new Set([1,2]), new Set([2,1]))""",
            """Bun.deepEquals(0, -0)""",
            """Bun.deepEquals(0, -0, true)""",
            """Bun.deepEquals({a:1,b:undefined}, {a:1})""",
            """Bun.deepEquals({a:1,b:undefined}, {a:1}, true)""",
            """Bun.deepEquals([1,,3], [1,undefined,3])""",
            """Bun.deepEquals(new Date(2020,1,1), new Date(2020,1,1))""",
            """Bun.deepEquals(/abc/gi, /abc/gi)""",
            """Bun.deepEquals(new Map([[1,2]]), new Map([[1,2]]))""",
            """Bun.deepEquals({a:1}, {a:"1"})""",
        )
        for (expr in checks) {
            val shim = runBunEval(mc, dir, expr)
            val real = runBunInDir(dir, "console.log($expr);")
            assertEquals(real, shim, expr)
        }
    }

    private fun runBunEval(mc: Mycelium, dir: File, expr: String): String {
        runJsInDir(mc, dir, "globalThis.__r = ($expr);")
        return mc.eval("js", "String(globalThis.__r)").asString()
    }

    // --- Bun.escapeHTML ---

    @Test fun escapeHTMLLiveParity() {
        assumeTrue(bunCommand() != null, "bun not available")
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        val checks = listOf(
            """Bun.escapeHTML("<b>hi & \"quote\" 'x's</b>")""",
            """Bun.escapeHTML(123)""",
            """Bun.escapeHTML(null)""",
            """Bun.escapeHTML(undefined)""",
            """Bun.escapeHTML(true)""",
            """Bun.escapeHTML([1,"<a>",3])""",
        )
        for (expr in checks) {
            val shim = runBunEval(mc, dir, expr)
            val real = runBunInDir(dir, "console.log($expr);")
            assertEquals(real, shim, expr)
        }
    }

    @Test fun escapeHTMLThrowsForSymbol() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            globalThis.__threw = false;
            try {
                Bun.escapeHTML(Symbol("x"));
            } catch (e) {
                globalThis.__threw = e instanceof TypeError;
            }
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__threw").asBoolean())
    }

    // --- Bun.inspect ---

    @Test fun inspectBasicShapes() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            globalThis.__str = Bun.inspect("hi");
            globalThis.__num = Bun.inspect(42);
            globalThis.__arr = Bun.inspect([1,2,3]);
            globalThis.__obj = Bun.inspect({a:1,b:2});
            globalThis.__big = Bun.inspect(123n);
            globalThis.__undef = Bun.inspect(undefined);
            globalThis.__null = Bun.inspect(null);
            """.trimIndent()
        )
        assertEquals("'hi'", mc.eval("js", "globalThis.__str").asString())
        assertEquals("42", mc.eval("js", "globalThis.__num").asString())
        assertEquals("[ 1, 2, 3 ]", mc.eval("js", "globalThis.__arr").asString())
        assertEquals("{ a: 1, b: 2 }", mc.eval("js", "globalThis.__obj").asString())
        assertEquals("123n", mc.eval("js", "globalThis.__big").asString())
        assertEquals("undefined", mc.eval("js", "globalThis.__undef").asString())
        assertEquals("null", mc.eval("js", "globalThis.__null").asString())
    }

    @Test fun inspectHandlesCircularReferences() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            const o = {a: 1};
            o.self = o;
            globalThis.__s = Bun.inspect(o);
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__s").asString().contains("Circular"))
    }

    @Test fun inspectCustomSymbolMatchesNodeUtilInspectCustom() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            globalThis.__isSymbol = typeof Bun.inspect.custom === 'symbol';
            globalThis.__matches = Bun.inspect.custom === Symbol.for('nodejs.util.inspect.custom');
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__isSymbol").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__matches").asBoolean())
    }

    // --- Bun.sleep / Bun.sleepSync ---

    @Test fun sleepResolvesAfterApproximatelyRequestedDelay() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            const t0 = Date.now();
            await Bun.sleep(30);
            globalThis.__elapsed = Date.now() - t0;
            """.trimIndent()
        )
        val elapsed = mc.eval("js", "globalThis.__elapsed").asLong()
        assertTrue(elapsed >= 25, "expected at least ~30ms elapsed, got $elapsed")
    }

    @Test fun sleepSyncBlocksForApproximatelyRequestedDelay() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            const t0 = Date.now();
            Bun.sleepSync(30);
            globalThis.__elapsed = Date.now() - t0;
            """.trimIndent()
        )
        val elapsed = mc.eval("js", "globalThis.__elapsed").asLong()
        assertTrue(elapsed >= 25, "expected at least ~30ms elapsed, got $elapsed")
    }

    // --- Bun.nanoseconds ---

    @Test fun nanosecondsIsMonotonicallyIncreasing() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            globalThis.__t0 = Bun.nanoseconds();
            globalThis.__type0 = typeof globalThis.__t0;
            for (let i = 0; i < 1000000; i++) {}
            globalThis.__t1 = Bun.nanoseconds();
            """.trimIndent()
        )
        assertEquals("number", mc.eval("js", "globalThis.__type0").asString())
        assertTrue(mc.eval("js", "globalThis.__t1").asDouble() > mc.eval("js", "globalThis.__t0").asDouble())
    }

    // --- Bun.which ---

    @Test fun whichFindsRealExecutableLiveParity() {
        assumeTrue(bunCommand() != null, "bun not available")
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(mc, dir, "globalThis.__ls = Bun.which(\"ls\");")
        val shim = mc.eval("js", "globalThis.__ls").asString()
        assertNotNull(shim)
        assertTrue(File(shim).canExecute())
    }

    @Test fun whichReturnsNullForNonexistentCommand() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(mc, dir, "globalThis.__r = Bun.which(\"nonexistent_cmd_xyz_123\");")
        assertTrue(mc.eval("js", "globalThis.__r").isNull)
    }

    @Test fun whichRespectsPATHOverride() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(mc, dir, "globalThis.__r = Bun.which(\"ls\", {PATH: \"/nonexistent-dir-xyz\"});")
        assertTrue(mc.eval("js", "globalThis.__r").isNull)
    }

    // --- Bun.randomUUIDv7 ---

    @Test fun randomUUIDv7ProducesValidV7Uuid() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            globalThis.__u = Bun.randomUUIDv7();
            globalThis.__u2 = Bun.randomUUIDv7("hex");
            globalThis.__b64 = Bun.randomUUIDv7("base64");
            """.trimIndent()
        )
        val uuid = mc.eval("js", "globalThis.__u").asString()
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(uuid), uuid)
        val uuid2 = mc.eval("js", "globalThis.__u2").asString()
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(uuid2), uuid2)
        val b64 = mc.eval("js", "globalThis.__b64").asString()
        assertEquals(24, b64.length) // 16 bytes base64-encoded, with padding
    }

    @Test fun randomUUIDv7IsMonotonicallyOrderedByTimestamp() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            globalThis.__a = Bun.randomUUIDv7("hex", 1000);
            globalThis.__b = Bun.randomUUIDv7("hex", 2000);
            """.trimIndent()
        )
        val a = mc.eval("js", "globalThis.__a").asString()
        val b = mc.eval("js", "globalThis.__b").asString()
        assertTrue(a < b, "uuid at earlier timestamp should sort before uuid at later timestamp")
    }

    // --- Bun.gzipSync / gunzipSync / deflateSync / inflateSync ---

    @Test fun gzipRoundTrip() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            const data = new TextEncoder().encode("hello world hello world hello world");
            const gz = Bun.gzipSync(data);
            globalThis.__isU8 = gz instanceof Uint8Array;
            const back = Bun.gunzipSync(gz);
            globalThis.__text = new TextDecoder().decode(back);
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__isU8").asBoolean())
        assertEquals("hello world hello world hello world", mc.eval("js", "globalThis.__text").asString())
    }

    @Test fun deflateRoundTrip() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            const data = new TextEncoder().encode("deflate me please deflate me please");
            const d = Bun.deflateSync(data);
            const back = Bun.inflateSync(d);
            globalThis.__text = new TextDecoder().decode(back);
            """.trimIndent()
        )
        assertEquals("deflate me please deflate me please", mc.eval("js", "globalThis.__text").asString())
    }

    @Test fun realBunCanGunzipShimOutputAndViceVersa() {
        assumeTrue(bunCommand() != null, "bun not available")
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        val payload = "interop payload interop payload interop payload"

        // Shim compresses, real bun decompresses.
        runJsInDir(
            mc, dir,
            """
            const gz = Bun.gzipSync(new TextEncoder().encode(${payload.jsQuoted()}));
            globalThis.__b64 = Buffer.from(gz).toString('base64');
            """.trimIndent()
        )
        val shimGzB64 = mc.eval("js", "globalThis.__b64").asString()
        val decompressedByReal = runBunInDir(
            dir,
            """
            const gz = Buffer.from(${shimGzB64.jsQuoted()}, 'base64');
            console.log(new TextDecoder().decode(Bun.gunzipSync(gz)));
            """.trimIndent()
        )
        assertEquals(payload, decompressedByReal)

        // Real bun compresses, shim decompresses.
        val realGzB64 = runBunInDir(
            dir,
            """
            const gz = Bun.gzipSync(new TextEncoder().encode(${payload.jsQuoted()}));
            console.log(Buffer.from(gz).toString('base64'));
            """.trimIndent()
        )
        runJsInDir(
            mc, dir,
            """
            const gz = Buffer.from(${realGzB64.jsQuoted()}, 'base64');
            globalThis.__decoded = new TextDecoder().decode(Bun.gunzipSync(gz));
            """.trimIndent()
        )
        assertEquals(payload, mc.eval("js", "globalThis.__decoded").asString())
    }

    // --- Bun.stringWidth ---

    @Test fun stringWidthLiveParityForCommonCases() {
        assumeTrue(bunCommand() != null, "bun not available")
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        val checks = listOf(
            """Bun.stringWidth("hello")""",
            """Bun.stringWidth("你好")""",
        )
        for (expr in checks) {
            val shim = runBunEval(mc, dir, expr)
            val real = runBunInDir(dir, "console.log($expr);")
            assertEquals(real, shim, expr)
        }
    }

    // --- Bun.peek — deliberately NOT implemented, documented as a gap ---

    @Test fun peekIsNotImplemented() {
        // See docs/reference/bun-compat.md: synchronously introspecting a Promise's
        // internal [[PromiseState]]/[[PromiseResult]] has no ECMAScript-level equivalent,
        // and GraalJS's Promise implementation doesn't expose a Java-interop hook for it —
        // implementing it would require either patching GraalJS internals or waiting a
        // microtask turn (which would silently violate peek's whole synchronous contract).
        // Left absent rather than faked.
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(mc, dir, "globalThis.__type = typeof Bun.peek;")
        assertEquals("undefined", mc.eval("js", "globalThis.__type").asString())
    }
}

private fun createTempDirectory(): File =
    kotlin.io.path.createTempDirectory("mycelium-bun-util-test-").toFile()

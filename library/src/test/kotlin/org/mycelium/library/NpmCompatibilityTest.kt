package org.mycelium.library

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import java.io.File

// Runs *real* npm packages (fetched from the registry via a real `npm install`) end-to-end
// through Mycelium, to find out what's actually missing from Node.js compatibility rather
// than what we assume is missing. Every test here is gated on npm being on PATH (like
// yoloAutoInstallsMissingPackage / manifestInstallRunsNpmInstall in
// MyceliumIntegrationTest) since it needs real network access to the npm registry.
//
// Findings as of this writing (GraalVM 24.2.1 / Node stdlib shims in
// library/src/main/resources/node-stdlib/):
//
//   - lodash (CJS, UMD-style): WORKS. Resolves via node_modules -> package.json "main" ->
//     CJS-to-ESM transpile path.
//   - minimist (CJS): WORKS. Same path as lodash.
//   - uuid (pure ESM, v14): FAILS. dist/v4.js references the bare global `crypto` (the Web
//     Crypto API — `crypto.randomUUID`/`crypto.getRandomValues`, which real Node.js exposes
//     as a `globalThis.crypto` since Node 19), but Mycelium only exposes Node's `crypto` as
//     an *importable module* (`import crypto from 'crypto'` — see coreModules /
//     crypto.mjs), never as a global. Fails with "ReferenceError: crypto is not defined".
//   - chalk (pure ESM, v5): FAILS. source/index.js imports `#ansi-styles` and
//     `#supports-color` — Node's `imports` field (subpath imports, package.json
//     `"imports"` key), a private-to-the-package specifier-remapping mechanism distinct
//     from `exports`. InterceptingFileSystem.resolveNodeModule only walks node_modules
//     trees for bare specifiers; it has no handling for `#`-prefixed imports-field
//     specifiers at all, so `#ansi-styles` is treated as an ordinary (nonexistent)
//     package name. Fails with "cannot find module '#ansi-styles'".
//   - dotenv (CJS): "works" in the sense that it doesn't throw and prints its own
//     "injected env" tip, but is functionally BROKEN: process.env is a Proxy (see
//     PROCESS_INIT_SCRIPT in Mycelium.kt) whose `get` trap always re-reads live from
//     `java.lang.System.getenv()` and ignores the underlying target object entirely — but
//     the proxy defines no `set` trap, so `process.env.FOO = 'bar'` silently succeeds
//     (writing to the proxy's invisible default target) without ever becoming observable
//     through a later `process.env.FOO` read. Any package that mutates process.env at
//     runtime (dotenv being the canonical example) silently no-ops.
//
// Not yet caught by any existing MyceliumIntegrationTest node-stdlib test since those all
// either read process.env (never write it) or use the `crypto` module import form directly
// (never the bare `crypto` global).
class NpmCompatibilityTest {
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

    private fun npmInstall(dir: File, vararg packages: String) {
        val exitCode = ProcessBuilder(listOf("npm", "install") + packages)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
            .let {
                val output = it.inputStream.bufferedReader().readText()
                val code = it.waitFor()
                if (code != 0) System.err.println("npm install output:\n$output")
                code
            }
        assertEquals(0, exitCode, "npm install failed for ${packages.joinToString(", ")}")
    }

    private fun writeAndRun(dir: File, script: String): Mycelium {
        val mainFile = File(dir, "test.mjs")
        mainFile.writeText(script)
        val mc = Mycelium()
        mc.runFile(mainFile.absolutePath)
        return mc
    }

    private fun tempProjectDir(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    // --- 1. lodash — pure JS utility library, CJS. WORKS. ---

    @Test fun lodashChunkWorks() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping npm compatibility test")

        val dir = tempProjectDir("mycelium-npm-lodash-")
        npmInstall(dir, "lodash")

        val mc = writeAndRun(
            dir,
            """
            import _ from 'lodash';
            globalThis.__lodashResult = JSON.stringify(_.chunk([1, 2, 3, 4, 5], 2));
            """.trimIndent()
        )
        val result = mc.eval("js", "globalThis.__lodashResult")
        assertEquals("[[1,2],[3,4],[5]]", result.asString())
    }

    // --- 2. chalk — terminal colors, pure ESM (v5). FAILS: package.json `imports` field
    // (subpath imports like `#ansi-styles`) is not resolved by Mycelium at all. ---

    @Test fun chalkFailsOnSubpathImports() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping npm compatibility test")

        val dir = tempProjectDir("mycelium-npm-chalk-")
        npmInstall(dir, "chalk")

        val ex = assertThrows(Exception::class.java) {
            writeAndRun(
                dir,
                """
                import chalk from 'chalk';
                globalThis.__chalkResult = chalk.blue('Hello world');
                """.trimIndent()
            )
        }
        // Exact failure: chalk's source/index.js does `import ansiStyles from '#ansi-styles'`
        // (Node's package.json "imports" field), which resolveNodeModule has no support for
        // — it's treated as an ordinary bare specifier and "resolved" against node_modules,
        // where a package literally named "#ansi-styles" naturally doesn't exist.
        val message = ex.message ?: ""
        assertTrue(
            message.contains("#ansi-styles") || (ex.cause?.message ?: "").contains("#ansi-styles"),
            "expected failure to mention the unresolved '#ansi-styles' subpath import, got: $message"
        )
    }

    // --- 3. uuid — generates UUIDs, pure ESM (v14). FAILS: v4.js references the bare
    // global `crypto` (Web Crypto API), which Mycelium never installs as a global — only
    // as the importable `crypto` module. ---

    @Test fun uuidFailsOnMissingGlobalCrypto() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping npm compatibility test")

        val dir = tempProjectDir("mycelium-npm-uuid-")
        npmInstall(dir, "uuid")

        val ex = assertThrows(Exception::class.java) {
            writeAndRun(
                dir,
                """
                import { v4 as uuidv4 } from 'uuid';
                globalThis.__uuidResult = uuidv4();
                """.trimIndent()
            )
        }
        val message = ex.message ?: ""
        assertTrue(
            message.contains("crypto") || (ex.cause?.message ?: "").contains("crypto"),
            "expected failure to mention the missing global 'crypto', got: $message"
        )
    }

    // --- 4. minimist — CLI arg parser, pure JS CJS. WORKS. ---

    @Test fun minimistParsesArgsWorks() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping npm compatibility test")

        val dir = tempProjectDir("mycelium-npm-minimist-")
        npmInstall(dir, "minimist")

        val mc = writeAndRun(
            dir,
            """
            import minimist from 'minimist';
            globalThis.__minimistResult = JSON.stringify(minimist(['--foo', 'bar', '-x', '3']));
            """.trimIndent()
        )
        val result = mc.eval("js", "globalThis.__minimistResult")
        val parsed = result.asString()
        assertTrue(parsed.contains("\"foo\":\"bar\""))
        assertTrue(parsed.contains("\"x\":3"))
    }

    // --- 5. dotenv — reads .env files via fs + path + os. Does not throw, but is
    // functionally broken: process.env writes are silently discarded (see class doc). ---

    @Test fun dotenvConfigRunsButEnvWriteIsSilentlyLost() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping npm compatibility test")

        val dir = tempProjectDir("mycelium-npm-dotenv-")
        npmInstall(dir, "dotenv")
        val envFile = File(dir, ".env")
        envFile.writeText("MYCELIUM_DOTENV_TEST_VAR=hello_from_dotenv\n")

        // dotenv's default `.env` lookup is relative to process.cwd() (matching real
        // Node.js — a script run via `node some/dir/script.js` still has cwd = the
        // invoking shell's directory, not the script's own directory), and
        // PROCESS_INIT_SCRIPT.process.cwd() reflects the JVM's *real* working directory
        // (java.lang.System.getProperty('user.dir')) — process.chdir() is a no-op stub, so
        // guest code has no way to change it. That's a second, separate Node-compat gap
        // (uncovered by writing this test) from the process.env write-back one below, so an
        // explicit `path` is passed here to keep this test isolated to the env-write bug.
        val mc = writeAndRun(
            dir,
            """
            import dotenv from 'dotenv';
            const result = dotenv.config({ path: "${envFile.absolutePath}" });
            globalThis.__dotenvError = result.error ? String(result.error) : null;
            globalThis.__dotenvParsedVar = result.parsed ? result.parsed.MYCELIUM_DOTENV_TEST_VAR : null;
            globalThis.__dotenvEnvVar = process.env.MYCELIUM_DOTENV_TEST_VAR;
            """.trimIndent()
        )
        assertNull(mc.eval("js", "globalThis.__dotenvError").asString().takeIf { it != "null" }, "dotenv.config() reported an error")
        // dotenv did parse the .env file correctly...
        assertEquals("hello_from_dotenv", mc.eval("js", "globalThis.__dotenvParsedVar").asString())
        // ...but the write to process.env never became observable, because
        // PROCESS_INIT_SCRIPT's envProxy `get` trap always re-reads live from
        // java.lang.System.getenv() and ignores whatever the (trap-less) `set` wrote to the
        // proxy's target. This assertion documents the current (broken) behavior — it
        // should start FAILING once process.env writes are fixed to be observable, at which
        // point this test should be updated to assertEquals("hello_from_dotenv", ...).
        val envVar = mc.eval("js", "globalThis.__dotenvEnvVar")
        assertTrue(envVar.isNull, "expected process.env write to still be silently lost (known gap); got: ${if (envVar.isNull) "null" else envVar.asString()}")
    }
}

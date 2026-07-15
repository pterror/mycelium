package org.mycelium.library

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

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
//   - uuid (pure ESM, v14): WORKS. dist/v4.js references the bare global `crypto` (the Web
//     Crypto API — `crypto.randomUUID`/`crypto.getRandomValues`), which is now exposed as
//     `globalThis.crypto` (see GLOBAL_CRYPTO_INIT_SCRIPT in Mycelium.kt), matching real
//     Node.js since Node 19, in addition to the pre-existing importable `crypto` module.
//   - chalk (pure ESM, v5): WORKS. source/index.js imports `#ansi-styles` and
//     `#supports-color` — Node's `imports` field (subpath imports, package.json
//     `"imports"` key), a private-to-the-package specifier-remapping mechanism distinct
//     from `exports`. InterceptingFileSystem now detects `#`-prefixed specifiers and
//     resolves them against the nearest ancestor package.json's `"imports"` field (see
//     resolveSubpathImport / IMPORTS_RESOLVER).
//   - dotenv (CJS): WORKS, including the previously-broken part: process.env is a Proxy
//     (see PROCESS_INIT_SCRIPT in Mycelium.kt) that now has a `set` trap tracking
//     overrides in a JS-side object, checked before falling back to the live
//     `java.lang.System.getenv()` snapshot — so `process.env.FOO = 'bar'` is now
//     observable through a later `process.env.FOO` read, and packages that mutate
//     process.env at runtime (dotenv being the canonical example) behave correctly.
//
// Second round — harder packages (axios, express, semver, ms, debug), now 5/5 pass:
//
//   - semver (pure JS CJS, no exotic package.json fields): WORKS. Same node_modules ->
//     "main" -> CJS-to-ESM path as lodash/minimist.
//   - ms (pure JS CJS): WORKS. Its package.json declares `"main": "./index"` — no file
//     extension. `InterceptingFileSystem` now probes `./index.js`, `.mjs`, `.cjs`,
//     `.json`, and `./index/index.*` whenever a resolved path (package.json "main"/
//     "exports", a `require()` target, or a generic ESM import) doesn't exist as-is — see
//     `resolveExtensionlessPath`, applied in `resolvePackageEntryPoint`,
//     `resolveRequireTarget`, and `newByteChannel`/`checkAccess`.
//   - debug (CJS, uses `tty` for TTY-detection): WORKS. `tty` is now a registered
//     synthetic core module (node-stdlib/tty.mjs) — a minimal stub (`isatty()` always
//     false, stub `ReadStream`/`WriteStream`) sufficient for TTY-detection probing to
//     succeed rather than throw. Also fixes express transitively (see below).
//   - axios (pure ESM/dual, v1.x): WORKS. axios's `exports["."]` is a *nested* conditions
//     object — `{types: {...}, bun: {...}, "react-native": {...}, browser: {...},
//     default: {require: "./dist/node/axios.cjs", default: "./index.js"}}` — where even
//     the `"default"` condition's value is itself another `{require, default}` object
//     rather than a string. `ENTRY_POINT_RESOLVER`'s condition matching
//     (`RESOLVE_CONDITIONS_FN`, shared with `IMPORTS_RESOLVER`) is now recursive —
//     descending into any condition whose value is itself an object, in priority order
//     (`node`/`import`/`default` for exports; `node`/`import`/`require`/`default` for
//     imports) — rather than assuming resolution bottoms out after one level.
//   - express (CJS, big dependency tree): WORKS for the exact same reason as debug — it
//     pulls in `debug` transitively (via `body-parser`), so it needed the same `tty`
//     module. Not a distinct express-specific fix.
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

    // --- 2. chalk — terminal colors, pure ESM (v5). WORKS: package.json `imports` field
    // (subpath imports like `#ansi-styles`) is now resolved by Mycelium. ---

    @Test fun chalkResolvesSubpathImports() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping npm compatibility test")

        val dir = tempProjectDir("mycelium-npm-chalk-")
        npmInstall(dir, "chalk")

        val mc = writeAndRun(
            dir,
            """
            import chalk from 'chalk';
            globalThis.__chalkResult = chalk.blue('Hello world');
            """.trimIndent()
        )
        val result = mc.eval("js", "globalThis.__chalkResult")
        assertTrue(result.asString().contains("Hello world"))
    }

    // --- 3. uuid — generates UUIDs, pure ESM (v14). WORKS: v4.js references the bare
    // global `crypto` (Web Crypto API), which Mycelium now exposes as `globalThis.crypto`
    // in addition to the importable `crypto` module. ---

    @Test fun uuidGeneratesUuidViaGlobalCrypto() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping npm compatibility test")

        val dir = tempProjectDir("mycelium-npm-uuid-")
        npmInstall(dir, "uuid")

        val mc = writeAndRun(
            dir,
            """
            import { v4 as uuidv4 } from 'uuid';
            globalThis.__uuidResult = uuidv4();
            """.trimIndent()
        )
        val result = mc.eval("js", "globalThis.__uuidResult")
        assertTrue(
            result.asString().matches(Regex("[0-9a-f-]{36}")),
            "expected a UUID-shaped string, got: ${result.asString()}"
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

    // --- 5. dotenv — reads .env files via fs + path + os. WORKS, including the
    // process.env write becoming observable (see class doc). ---

    @Test fun dotenvConfigWritesObservableProcessEnv() {
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
        // guest code has no way to change it. That's a separate, still-open Node-compat gap
        // from the process.env write-back one this test covers, so an explicit `path` is
        // passed here to keep this test isolated to the env-write behavior.
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
        // dotenv parses the .env file correctly...
        assertEquals("hello_from_dotenv", mc.eval("js", "globalThis.__dotenvParsedVar").asString())
        // ...and the write to process.env is now observable, since PROCESS_INIT_SCRIPT's
        // envProxy `set` trap tracks overrides that `get` consults before falling back to
        // the live java.lang.System.getenv() snapshot.
        assertEquals("hello_from_dotenv", mc.eval("js", "globalThis.__dotenvEnvVar").asString())
    }

    // --- 6. semver — version parsing, pure JS CJS. WORKS. ---

    @Test fun semverParsesAndComparesVersions() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping npm compatibility test")

        val dir = tempProjectDir("mycelium-npm-semver-")
        npmInstall(dir, "semver")

        val mc = writeAndRun(
            dir,
            """
            import semver from 'semver';
            globalThis.__semverValid = semver.valid('1.2.3');
            globalThis.__semverGt = semver.gt('1.2.3', '1.2.0');
            """.trimIndent()
        )
        assertEquals("1.2.3", mc.eval("js", "globalThis.__semverValid").asString())
        assertTrue(mc.eval("js", "globalThis.__semverGt").asBoolean())
    }

    // --- 7. ms — time string parser, pure JS CJS. WORKS: package.json declares
    // `"main": "./index"` with no file extension — InterceptingFileSystem now probes
    // `./index.js` (and siblings: `.mjs`/`.cjs`/`.json`, plus `<path>/index.*`) whenever a
    // resolved path doesn't exist as-is, both for package.json "main"/"exports" resolution
    // and for GraalJS's own generic ESM path resolution (see resolveExtensionlessPath). ---

    @Test fun msParsesTimeStrings() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping npm compatibility test")

        val dir = tempProjectDir("mycelium-npm-ms-")
        npmInstall(dir, "ms")

        val mc = writeAndRun(
            dir,
            """
            import ms from 'ms';
            globalThis.__msResult = ms('2 days');
            """.trimIndent()
        )
        val result = mc.eval("js", "globalThis.__msResult")
        assertEquals(172800000, result.asInt())
    }

    // --- 8. debug — logging, CJS, `require("tty")` for TTY detection. WORKS: `tty` is now
    // among Mycelium's synthetic core modules (see node-stdlib/tty.mjs) — a minimal stub
    // (`isatty()` always false, stub `ReadStream`/`WriteStream`) just enough for `debug`'s
    // (and `chalk`'s) TTY-detection probing to succeed without throwing. ---

    @Test fun debugLoadsSuccessfully() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping npm compatibility test")

        val dir = tempProjectDir("mycelium-npm-debug-")
        npmInstall(dir, "debug")

        val mc = writeAndRun(
            dir,
            """
            import debug from 'debug';
            const log = debug('mycelium:test');
            globalThis.__debugType = typeof log;
            log('hello %s', 'world');
            """.trimIndent()
        )
        assertEquals("function", mc.eval("js", "globalThis.__debugType").asString())
    }

    // --- 9. axios — HTTP client, ESM/dual (v1.x). WORKS: `exports["."]` is a *nested*
    // conditions object (the `"default"` condition's own value is itself another
    // `{require, default}` object rather than a string) — ENTRY_POINT_RESOLVER's condition
    // matching (RESOLVE_CONDITIONS_FN) is now recursive, descending into any condition
    // whose value is itself an object rather than assuming one level of nesting is the
    // bottom, so it bottoms out correctly regardless of nesting depth. Only checks that the
    // module loads and exposes the expected shape — no real HTTP request is made. ---

    @Test fun axiosLoadsSuccessfully() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping npm compatibility test")

        val dir = tempProjectDir("mycelium-npm-axios-")
        npmInstall(dir, "axios")

        val mc = writeAndRun(
            dir,
            """
            import axios from 'axios';
            globalThis.__axiosType = typeof axios;
            globalThis.__axiosGetType = typeof axios.get;
            """.trimIndent()
        )
        assertEquals("function", mc.eval("js", "globalThis.__axiosType").asString())
        assertEquals("function", mc.eval("js", "globalThis.__axiosGetType").asString())
    }

    // --- 10. express — HTTP server framework, CJS, big dependency tree. WORKS for the same
    // underlying reason as debug (#8) now working: express -> body-parser -> debug ->
    // `require("tty")`, and `tty` is now a registered core module. Not a distinct
    // express-specific fix — included here to document that a realistic multi-level
    // dependency chain resolves correctly too, not just the single-package case. ---

    @Test fun expressLoadsSuccessfully() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping npm compatibility test")

        val dir = tempProjectDir("mycelium-npm-express-")
        npmInstall(dir, "express")

        val mc = writeAndRun(
            dir,
            """
            import express from 'express';
            const app = express();
            globalThis.__expressType = typeof app.listen;
            """.trimIndent()
        )
        assertEquals("function", mc.eval("js", "globalThis.__expressType").asString())
    }

    // --- 11. express, end-to-end: a real HTTP round trip through an express server running
    // under Mycelium, including express's built-in 404 handler — which goes through
    // `http-errors`' `createError()`, which unconditionally calls
    // `Error.captureStackTrace(err, createError)` (see http-errors/index.js and the
    // Error.captureStackTrace polyfill in Mycelium.kt). This is the concrete path that
    // motivated the polyfill: without it, `express()` fails the very first time any request
    // reaches the 404 fallback (or any handler that constructs an http-errors instance),
    // even though `expressLoadsSuccessfully` above (which never issues a real request)
    // already passes with a bare GraalJS `Error.captureStackTrace` gap. ---

    @Test fun expressServerHandlesRequestsAndFourOhFours() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping npm compatibility test")

        val dir = tempProjectDir("mycelium-npm-express-server-")
        npmInstall(dir, "express")

        // Dedicated Mycelium instance, not the shared `vm` — see the equivalent comment on
        // MyceliumIntegrationTest's http.createServer test for why: once a real HTTP request
        // reaches the JS request handler, the HTTP server's own dispatch thread takes
        // ownership of the polyglot Context, and GraalVM doesn't hand that back to the
        // thread that called `listen()`.
        val mc = Mycelium()
        val mainFile = File(dir, "test.mjs")
        mainFile.writeText(
            """
            import express from 'express';
            const app = express();
            app.get('/hello', (req, res) => {
                res.status(200).json({ message: 'hello from express' });
            });
            const server = app.listen(0, '127.0.0.1', () => {
                globalThis.__expressServerReady = true;
                globalThis.__expressServerPort = server.address().port;
            });
            """.trimIndent()
        )
        mc.runFile(mainFile.absolutePath)

        assertTrue(mc.eval("js", "globalThis.__expressServerReady").asBoolean())
        val port = mc.eval("js", "globalThis.__expressServerPort").asInt()

        val helloConn = URI("http://127.0.0.1:$port/hello").toURL().openConnection() as HttpURLConnection
        helloConn.connectTimeout = 5000
        helloConn.readTimeout = 5000
        try {
            assertEquals(200, helloConn.responseCode)
            val body = helloConn.inputStream.readBytes().toString(Charsets.UTF_8)
            assertTrue(body.contains("hello from express"), "unexpected body: $body")
        } finally {
            helloConn.disconnect()
        }

        // Hits express's built-in 404 fallback, which constructs an http-errors instance —
        // exercising Error.captureStackTrace for real (see class-level test doc above).
        val notFoundConn = URI("http://127.0.0.1:$port/does-not-exist").toURL().openConnection() as HttpURLConnection
        try {
            assertEquals(404, notFoundConn.responseCode)
        } finally {
            notFoundConn.disconnect()
        }
    }

    // --- 12. http2 core module — client side, backed by java.net.http.HttpClient. Not one
    // of the ten original packages above, but exercised here the same way: a real npm
    // package (axios) whose optional http2 transport (lib/adapters/http.js's
    // `http2Transport`, lib/helpers/Http2Sessions.js) imports Mycelium's `node-stdlib/
    // http2.mjs` at module scope via `import http2 from 'http2'`. This only checks that the
    // module loads with the right shape (http2.connect/constants), not a live HTTP/2
    // round trip against a real server. ---

    @Test fun http2ModuleLoadsWithExpectedShape() {
        val mc = Mycelium()
        val tmpFile = File.createTempFile("mycelium-http2-test-", ".mjs")
        tmpFile.deleteOnExit()
        tmpFile.writeText(
            """
            import http2 from 'node:http2';
            globalThis.__http2ConnectType = typeof http2.connect;
            globalThis.__http2Method = http2.constants.HTTP2_HEADER_METHOD;
            globalThis.__http2Status = http2.constants.HTTP2_HEADER_STATUS;
            globalThis.__http2DefaultSettingsType = typeof http2.getDefaultSettings();
            """.trimIndent()
        )
        mc.runFile(tmpFile.absolutePath)
        assertEquals("function", mc.eval("js", "globalThis.__http2ConnectType").asString())
        assertEquals(":method", mc.eval("js", "globalThis.__http2Method").asString())
        assertEquals(":status", mc.eval("js", "globalThis.__http2Status").asString())
        assertEquals("object", mc.eval("js", "globalThis.__http2DefaultSettingsType").asString())
    }
}

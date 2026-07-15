package org.mycelium.library

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.graalvm.polyglot.PolyglotException
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
//
// Third round — harder still (ws, commander, yargs, glob, pino, zod, nanoid, inquirer),
// originally 4/8 pass:
//
//   - ws (WebSocket server, uses net/crypto/stream/http/events): WORKS. All transitive
//     requirements were already-implemented core modules.
//   - zod (pure JS/TS, zero deps): WORKS. Same shape as lodash/semver.
//   - nanoid (uses crypto): WORKS. Bottoms out on the same `globalThis.crypto`
//     (`crypto.getRandomValues`) mechanism uuid's v4 generator already exercises.
//   - commander (ESM v15): originally FAILED on `import { stripVTControlCharacters } from
//     'node:util'` — `util` was registered but didn't export that function. Now WORKS:
//     `util.mjs` gained `stripVTControlCharacters` (see commanderLoadsAndParsesOptions).
//   - yargs (ESM v18): originally FAILED because its transitive dependency escalade
//     declares `exports["./sync"]` as an *array* of fallback candidates
//     (`[{import:{...}, require:{...}}, "./sync/index.js"]`), a distinct exports pattern
//     from the nested-conditions-object shape axios already covers — `RESOLVE_CONDITIONS_FN`
//     didn't recurse into arrays, silently failing resolution and falling back to a literal
//     on-disk path check against a directory (`EISDIR`-shaped "Is a directory"). Array
//     fallback resolution is now implemented (`resolveConditions` tries each array element
//     in order) and the escalade gap is fixed; its platform shim also does `import {
//     builtinModules } from 'node:module'`, and `module` is now a registered core module
//     too — yargs now WORKS end-to-end (see yargsParsesArgv).
//   - glob (v11): originally FAILED with "unknown core module 'node:fs/promises'" — fixed by
//     registering a real `fs-promises.mjs` core module, then progressed to `URL` not being
//     exposed as a global (minimatch/path-scurry construct `new URL(...)` at module scope).
//     `URL`/`URLSearchParams` are now bare globals too (backed by the existing `url` core
//     module) — glob now WORKS end-to-end, given an explicit `cwd` option (Mycelium's
//     `process.cwd()` reflects the JVM's real working directory rather than the running
//     script's directory, same caveat as dotenv above) — see globFindsFilesWithExplicitCwd.
//   - pino: originally FAILED with "unknown core module 'node:diagnostics_channel'" — fixed
//     by registering a real `diagnostics_channel.mjs` core module, then progressed to a
//     missing `worker_threads` core module (pino's thread-transport machinery), then to
//     `node-stdlib/buffer.mjs` not exporting a `constants` object (thread-stream reads
//     `buffer.constants.MAX_STRING_LENGTH`), then to thread-stream referencing the bare Node
//     global `global` directly (`global.WeakRef`/`global.FinalizationRegistry` fallback
//     checks), which Mycelium didn't expose. `buffer.mjs` now exports `constants`, and
//     `globalThis.global = globalThis` is set up during context init — pino now WORKS
//     end-to-end — see pinoLogsMessage.
//   - inquirer: originally FAILED with "unknown core module 'node:async_hooks'" — fixed by
//     registering a real `async_hooks.mjs` core module, then progressed to a missing
//     `node:readline` core module. Now that `readline` is registered too, it progresses
//     past both and fails on an unrelated CJS/ESM interop gap instead: `chardet` is CJS and
//     its named export `detect` is used via `import { detect } from 'chardet'` in
//     `@inquirer/external-editor`'s ESM entry point, and Mycelium's CJS-to-ESM named-export
//     inference doesn't pick it up ("The requested module 'chardet' does not provide an
//     export named 'detect'") — see inquirerFailsOnChardetNamedExportInterop.
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
            globalThis.__debugNamespace = log.namespace;
            globalThis.__debugEnabledResultType = typeof debug.enabled('test');
            """.trimIndent()
        )
        assertEquals("function", mc.eval("js", "globalThis.__debugType").asString())
        assertEquals("mycelium:test", mc.eval("js", "globalThis.__debugNamespace").asString())
        assertEquals(
            "boolean",
            mc.eval("js", "globalThis.__debugEnabledResultType").asString(),
            "debug.enabled('test') should return a boolean"
        )
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
            globalThis.__axiosDefaultsType = typeof axios.defaults;
            globalThis.__axiosDefaultsHeadersType = typeof axios.defaults.headers;
            globalThis.__axiosDefaultsHeadersCommonType = typeof axios.defaults.headers.common;
            globalThis.__axiosDefaultsHasTimeout = 'timeout' in axios.defaults;
            globalThis.__axiosDefaultsTransformRequestType = typeof axios.defaults.transformRequest;
            """.trimIndent()
        )
        assertEquals("function", mc.eval("js", "globalThis.__axiosType").asString())
        assertEquals("function", mc.eval("js", "globalThis.__axiosGetType").asString())
        assertEquals("object", mc.eval("js", "globalThis.__axiosDefaultsType").asString())
        assertEquals("object", mc.eval("js", "globalThis.__axiosDefaultsHeadersType").asString())
        assertEquals("object", mc.eval("js", "globalThis.__axiosDefaultsHeadersCommonType").asString())
        assertTrue(mc.eval("js", "globalThis.__axiosDefaultsHasTimeout").asBoolean())
        assertEquals("object", mc.eval("js", "globalThis.__axiosDefaultsTransformRequestType").asString())
    }

    // --- 10. express — HTTP server framework, CJS, big dependency tree. WORKS for the same
    // underlying reason as debug (#8) now working: express -> body-parser -> debug ->
    // `require("tty")`, and `tty` is now a registered core module. Not a distinct
    // express-specific fix — included here to document that a realistic multi-level
    // dependency chain resolves correctly too, not just the single-package case. ---

    // Redundant with expressServerHandlesRequestsAndFourOhFours (below) as a bare "does it
    // load" check, so this instead exercises middleware registration — app.use() pushing
    // onto the app's internal router stack — without spinning up a real HTTP server.

    @Test fun expressMiddlewareRegistersOnRouterStack() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping npm compatibility test")

        val dir = tempProjectDir("mycelium-npm-express-middleware-")
        npmInstall(dir, "express")

        val mc = writeAndRun(
            dir,
            """
            import express from 'express';
            const app = express();
            function myMiddleware(req, res, next) { next(); }
            // Express 5 exposes the app's underlying router as a lazily-created `app.router`
            // getter; Express 4 exposes it as `app._router` (also lazily created, on first
            // app.use()/app.get()/etc). Checking both keeps this working across whichever
            // major version `npm install express` resolves to.
            const routerBefore = app.router || app._router;
            const stackLengthBefore = routerBefore ? routerBefore.stack.length : -1;
            app.use(myMiddleware);
            const router = app.router || app._router;
            globalThis.__expressHasRouter = typeof router === 'object' || typeof router === 'function';
            globalThis.__expressStackGrew = router.stack.length > stackLengthBefore;
            globalThis.__expressStackHasMiddleware = router.stack.some(
                (layer) => layer.handle === myMiddleware
            );
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__expressHasRouter").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__expressStackGrew").asBoolean())
        assertTrue(mc.eval("js", "globalThis.__expressStackHasMiddleware").asBoolean())
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

    // --- 13. ws — WebSocket server/client, uses net/crypto/stream/http/events. WORKS: all
    // of ws's transitive requirements (net.Server, crypto hashing for the handshake, the
    // Duplex stream base class, http.Server) are already-implemented core modules; nothing
    // new needed. Only checks that a server can be constructed and closed — no real
    // handshake/frame round trip. ---

    @Test fun wsServerConstructsAndCloses() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping npm compatibility test")

        val dir = tempProjectDir("mycelium-npm-ws-")
        npmInstall(dir, "ws")

        val mc = writeAndRun(
            dir,
            """
            import { WebSocketServer } from 'ws';
            const wss = new WebSocketServer({ port: 0 });
            globalThis.__wsAddressType = typeof wss.address;
            wss.close();
            """.trimIndent()
        )
        assertEquals("function", mc.eval("js", "globalThis.__wsAddressType").asString())
    }

    // --- 14. commander — CLI framework, pure JS (v15). FAILS, and not for the reason
    // expected going in: commander's `lib/command.js` has two separate at-module-scope
    // imports from core modules Mycelium only partially supports — `import childProcess
    // from 'node:child_process'` (entirely unregistered, no child_process.mjs at all) and
    // `import { stripVTControlCharacters } from 'node:util'` (util *is* registered, but
    // node-stdlib/util.mjs now exports `stripVTControlCharacters` — see util.mjs). ES module
    // linking resolves/validates named imports for the whole module graph before any module
    // body runs, so the `node:util` import used to fail first (before commander's own
    // `node:child_process` gap was ever exercised); now `util` provides the export and
    // commander runs end-to-end. ---

    @Test fun commanderLoadsAndParsesOptions() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping npm compatibility test")

        val dir = tempProjectDir("mycelium-npm-commander-")
        npmInstall(dir, "commander")

        val mc = writeAndRun(
            dir,
            """
            import { Command } from 'commander';
            const program = new Command();
            program.name('test').version('1.0.0').option('-d, --debug');
            program.parse(['node', 'test', '--debug']);
            globalThis.__commanderDebug = program.opts().debug;
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__commanderDebug").asBoolean())
    }

    // --- 15. yargs — CLI framework, ESM (v18). Originally FAILED, not on a missing core
    // module: yargs's platform shim (lib/platform-shims/esm.mjs) does `import escalade from
    // 'escalade/sync'`, and escalade's package.json `exports["./sync"]` is an *array* of
    // fallback candidates — `[{import: {...}, require: {...}}, "./sync/index.js"]` — a
    // distinct "try each in order" exports pattern from the nested-conditions-object shape
    // axios already exercises (see axiosLoadsSuccessfully above). `RESOLVE_CONDITIONS_FN`
    // in InterceptingFileSystem only recursed into plain conditions *objects* (checking
    // `hasOwnProperty` for known condition keys); given an array, `typeof node === "object"`
    // is true (arrays are objects) but none of `node`/`import`/`default` are own keys of an
    // array (its own keys are `"0"`, `"1"`, ...), so resolution silently returned null and
    // fell through to a literal on-disk path check against the *export key itself*
    // (`./sync`) — which exists as a directory (`escalade/sync/`), not a file, hence the
    // `EISDIR`-shaped "Error: Is a directory" rather than a "no such export" error.
    //
    // `resolveConditions` now recurses into arrays (trying each element in declaration
    // order, recursing into objects/nested-arrays, per Node's own array-fallback
    // semantics), which fixes the escalade resolution. The same platform shim also does
    // `import { builtinModules } from 'node:module'` — `module` is now a registered core
    // module too, so yargs now WORKS end-to-end. ---

    @Test fun yargsParsesArgv() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping npm compatibility test")

        val dir = tempProjectDir("mycelium-npm-yargs-")
        npmInstall(dir, "yargs")

        val mc = writeAndRun(
            dir,
            """
            import yargs from 'yargs';
            import { hideBin } from 'yargs/helpers';
            const argv = yargs(hideBin(['node', 'test', '--name', 'mycelium'])).argv;
            globalThis.__yargsName = argv.name;
            """.trimIndent()
        )
        assertEquals("mycelium", mc.eval("js", "globalThis.__yargsName").asString())
    }

    // --- 16. glob — file matching, uses path/fs. Originally FAILED: glob v11's
    // implementation (via the `path-scurry`/`minimatch` dependency chain) does
    // `require('node:fs/promises')` (or `import ... from 'node:fs/promises'` depending on
    // the entry point) — the promise-based `fs` API surface, which wasn't registered as its
    // own core module (only plain `fs` was — see library/src/main/resources/node-stdlib/
    // fs.mjs; there was no fs-promises.mjs).
    //
    // `fs/promises`/`node:fs/promises` are now registered core modules (backed by
    // node-stdlib/fs-promises.mjs, re-exporting fs.mjs's existing `promises` namespace both
    // as named exports and as the default export, matching Node's `require('fs/promises')`
    // shape), which unblocked loading but exposed a second gap: minimatch/path-scurry
    // construct `new URL(...)` at module scope, and `URL` wasn't exposed as a global in
    // Mycelium's guest environment (only importable via the `url` core module, per the
    // project's TODO — "GraalJS ships no URL/URLSearchParams globals").
    //
    // `URL`/`URLSearchParams` are now bare globals too (implemented the same way as the
    // existing `Buffer` global — requiring the real `url` core module through
    // `__mycelium_require__` rather than duplicating its implementation) — glob now WORKS
    // end-to-end, given an explicit `cwd` option: Mycelium's `process.cwd()` reflects the
    // JVM's real working directory rather than the running script's directory (the same
    // caveat noted on the dotenv test above), so glob's default `cwd: process.cwd()` doesn't
    // land in the temp project dir without an override. ---

    @Test fun globFindsFilesWithExplicitCwd() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping npm compatibility test")

        val dir = tempProjectDir("mycelium-npm-glob-")
        npmInstall(dir, "glob")
        File(dir, "dummy.json").writeText("{}")

        val mc = writeAndRun(
            dir,
            """
            import { globSync } from 'glob';
            const files = globSync('*.json', { cwd: ${'"'}${dir.absolutePath}${'"'} });
            globalThis.__globHasMatches = files.length > 0;
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__globHasMatches").asBoolean())
    }

    // --- 17. pino — fast logger, uses stream/os/events/sonic-boom. Originally FAILED:
    // pino's core (lib/tools.js, imported at module scope from pino.js) does
    // `require('node:diagnostics_channel')` — a core module Mycelium didn't register at
    // all (not even a stub), unlike `tty` (registered specifically to unblock debug/chalk/
    // express — see class doc above).
    //
    // `diagnostics_channel`/`node:diagnostics_channel` are now registered core modules
    // (node-stdlib/diagnostics_channel.mjs — a genuinely-working pub/sub implementation,
    // not just a stub: `channel`/`subscribe`/`publish`/`hasSubscribers`/`tracingChannel`
    // all function, just with no built-in engine-instrumented channels firing
    // automatically), which unblocked loading but exposed a second gap: pino's
    // thread-transport machinery does `require('worker_threads')`, which wasn't registered
    // as a core module either.
    //
    // `worker_threads` is now a registered core module too, which unblocked loading but
    // exposed a third gap: thread-stream (pino's worker-thread transport helper) does `const
    // buffer = require('buffer'); ... buffer.constants.MAX_STRING_LENGTH`, and
    // node-stdlib/buffer.mjs didn't export a `constants` object at all (only the `Buffer`
    // class), so the read threw "Cannot read property 'MAX_STRING_LENGTH' of undefined".
    //
    // `buffer.mjs` now exports a `constants` object too (`MAX_LENGTH`/`MAX_STRING_LENGTH`,
    // also attached as `Buffer.constants`), which unblocked loading but exposed a fourth
    // gap: thread-stream also references the bare Node global `global` directly (not
    // `globalThis`), and Mycelium's guest environment exposed no `global` alias.
    //
    // `globalThis.global = globalThis` is now set up during context init (mirroring real
    // Node, where `global`/`globalThis` are literally the same object) — pino now WORKS
    // end-to-end. ---

    @Test fun pinoLogsMessage() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping npm compatibility test")

        val dir = tempProjectDir("mycelium-npm-pino-")
        npmInstall(dir, "pino")

        val mc = writeAndRun(
            dir,
            """
            import pino from 'pino';
            const logger = pino({ level: 'info' });
            logger.info('hello from mycelium');
            globalThis.__pinoLoaded = true;
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__pinoLoaded").asBoolean())
    }

    // --- 18. zod — schema validation, pure JS/TS, zero deps. WORKS. Same "pure JS, no
    // exotic package.json fields or core-module dependencies" shape as lodash/semver. ---

    @Test fun zodValidatesSchema() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping npm compatibility test")

        val dir = tempProjectDir("mycelium-npm-zod-")
        npmInstall(dir, "zod")

        val mc = writeAndRun(
            dir,
            """
            import { z } from 'zod';
            const schema = z.object({ name: z.string(), age: z.number() });
            globalThis.__zodValidResult = schema.safeParse({ name: 'mycelium', age: 1 }).success;
            globalThis.__zodInvalidResult = schema.safeParse({ name: 'mycelium', age: 'not a number' }).success;
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__zodValidResult").asBoolean())
        assertFalse(mc.eval("js", "globalThis.__zodInvalidResult").asBoolean())
    }

    // --- 19. nanoid — ID generator, uses crypto. WORKS: like uuid, nanoid's non-secure and
    // secure code paths both bottom out on the Web Crypto API (`crypto.getRandomValues`)
    // exposed as `globalThis.crypto`, the same mechanism uuid's v4 generator relies on. ---

    @Test fun nanoidGeneratesIdOfExpectedLength() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping npm compatibility test")

        val dir = tempProjectDir("mycelium-npm-nanoid-")
        npmInstall(dir, "nanoid")

        val mc = writeAndRun(
            dir,
            """
            import { nanoid } from 'nanoid';
            globalThis.__nanoidResult = nanoid();
            """.trimIndent()
        )
        val result = mc.eval("js", "globalThis.__nanoidResult").asString()
        assertEquals(21, result.length)
    }

    // --- 20. inquirer — interactive prompts, uses readline/tty/stream/events. Originally
    // FAILED: inquirer's core (`@inquirer/core`, pulled in transitively) does
    // `require('node:async_hooks')` (used for its `AsyncLocalStorage`-based prompt-context
    // tracking) at module scope, and `async_hooks` wasn't among Mycelium's synthetic core
    // modules — a separate gap from the `tty` one debug/chalk/express already exercise (see
    // class doc above); `tty` alone wasn't enough for inquirer to load.
    //
    // `async_hooks`/`node:async_hooks` are now registered core modules (node-stdlib/
    // async_hooks.mjs — `AsyncLocalStorage` genuinely threads a "current store" through
    // synchronous `run()`/`getStore()`/`enterWith()` calls via a push/pop stack;
    // `createHook`/`executionAsyncId`/`triggerAsyncId`/`AsyncResource` are present as inert
    // stubs since GraalJS has no underlying async-resource hooks to wire real tracking to),
    // which unblocked loading but exposed a second gap: inquirer also needs
    // `node:readline`, which wasn't registered as a core module either.
    //
    // `readline`/`node:readline` (plus `readline/promises`) are now registered core modules
    // too — but inquirer still doesn't run end-to-end, on a gap unrelated to any missing
    // core module: `@inquirer/external-editor`'s ESM entry does `import { detect } from
    // 'chardet'`, chardet is CJS (`module.exports = ChardetCls; ChardetCls.detect = ...`),
    // and Mycelium's CJS-to-ESM named-export inference doesn't surface `detect` as a named
    // export of the synthesized ESM wrapper — "The requested module 'chardet' does not
    // provide an export named 'detect'". Open gap, not part of this sweep. Only checks that
    // importing the module fails at load time — no interactive prompt is attempted. ---

    @Test fun inquirerFailsOnChardetNamedExportInterop() {
        assumeTrue(isCommandAvailable("npm"), "npm not available in PATH — skipping npm compatibility test")

        val dir = tempProjectDir("mycelium-npm-inquirer-")
        npmInstall(dir, "inquirer")

        val ex = assertThrows(PolyglotException::class.java) {
            writeAndRun(
                dir,
                """
                import inquirer from 'inquirer';
                globalThis.__inquirerPromptType = typeof inquirer.prompt;
                """.trimIndent()
            )
        }
        assertFalse(
            ex.message!!.contains("async_hooks"),
            "async_hooks should now resolve — unexpected error: ${ex.message}"
        )
        assertFalse(
            ex.message!!.contains("node:readline"),
            "readline should now resolve — unexpected error: ${ex.message}"
        )
        assertTrue(
            ex.message!!.contains("chardet") && ex.message!!.contains("detect"),
            "unexpected error: ${ex.message}"
        )
    }
}

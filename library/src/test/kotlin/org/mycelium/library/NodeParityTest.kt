package org.mycelium.library

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

// Programmatic API-surface diffing against a *real* Node.js binary: for every module
// Mycelium ships in library/src/main/resources/node-stdlib/, this asks real Node "what do
// you export from this module?" and asks Mycelium the same question, then reports the
// difference. Unlike NpmCompatibilityTest (which exercises real *packages* end-to-end and
// only tells you a dependency chain broke somewhere), this gives a precise, per-export punch
// list of exactly which stdlib surface is missing — the "what should we build next" signal.
//
// Gated on `node` being available, same shape as NpmCompatibilityTest gating on `npm`: try
// plain `node` on PATH first, then fall back to `nix shell nixpkgs#nodejs --command node`
// (this repo's devShell doesn't reliably put a `node` binary on PATH, so on a bare `nix
// develop` shell the nix-shell fallback is expected to be what actually runs these tests).
//
// This is deliberately *not* pass/fail per missing export — Mycelium's stdlib is a
// deliberately partial shim, not a goal of 1:1 parity with Node for every module. Each
// dynamic test prints a clear gap/extra report and only hard-fails when a module fails to
// *load* in Mycelium but loads fine in Node (a "not wired up at all" signal) or a behavioral
// case produces a different result — read the test output (`--info`), not just red/green,
// for the full picture of what's missing.
//
// Implementation note: Mycelium-side evaluation always uses a *static* `import` inside a
// module body passed to Mycelium.evalModule (mirroring MyceliumIntegrationTest's own
// evalModule usage), never dynamic `import()` — this sidesteps having to reason about
// whether GraalJS drains a returned Promise's microtask queue before evalModule hands
// control back, since static imports/module bodies run to completion synchronously as part
// of the eval call itself.
class NodeParityTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun setUpVm() {
            vm = Mycelium()
        }

        lateinit var vm: Mycelium

        // Every core module Mycelium implements — see
        // library/src/main/resources/node-stdlib/*.mjs. Keep this list in sync with that
        // directory; a stray .mjs file with no entry here silently gets no parity coverage.
        val MODULES = listOf(
            "assert", "buffer", "child_process", "crypto", "events", "fs", "http", "http2",
            "https", "net", "os", "path", "process", "querystring", "stream",
            "string_decoder", "tls", "tty", "url", "util", "zlib"
        )

        private var resolvedNodeCommand: List<String>? = null
        private var nodeCommandResolved = false

        // Resolves once per test run (not per test) since spawning `nix shell` just to probe
        // availability is expensive — NpmCompatibilityTest's isCommandAvailable-per-test
        // pattern is fine for a cheap `npm --version` probe but a `nix shell` invocation is
        // not cheap enough to repeat ~30 times.
        fun nodeCommand(): List<String>? {
            if (nodeCommandResolved) return resolvedNodeCommand
            nodeCommandResolved = true
            resolvedNodeCommand = when {
                isCommandAvailable(listOf("node", "--version")) -> listOf("node")
                isCommandAvailable(listOf("nix", "shell", "nixpkgs#nodejs", "--command", "node", "--version")) ->
                    listOf("nix", "shell", "nixpkgs#nodejs", "--command", "node")
                else -> null
            }
            return resolvedNodeCommand
        }

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

        // Runs a snippet of JS through real Node via `node -e`, returning trimmed stdout.
        // Throws with stderr attached on nonzero exit so callers can distinguish "Node itself
        // errored" from "the printed result didn't match what we expected".
        fun runNode(script: String): String {
            val command = nodeCommand() ?: error("node not available")
            val process = ProcessBuilder(command + listOf("-e", script))
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw RuntimeException("node -e failed (exit $exitCode): $stderr")
            }
            return stdout.trim()
        }

        // Evaluates `moduleBody` (which must assign a string to `globalThis.__mp_result`) as
        // an ES module through Mycelium, then reads that global back out. `moduleBody` is
        // free to use top-level `import` — evalModule executes the whole module body
        // synchronously before returning, so the global is guaranteed set by the time it's
        // read.
        fun runMycelium(moduleBody: String): String {
            vm.evalModule("js", moduleBody)
            val result = vm.eval("js", "globalThis.__mp_result")
            return if (result.isString) result.asString() else result.toString()
        }

        fun nodeModuleExports(module: String): List<String> {
            val json = runNode(
                "const m = require('$module'); console.log(JSON.stringify(Object.keys(m).sort()));"
            )
            return parseJsonStringArray(json)
        }

        fun myceliumModuleExports(module: String): List<String> {
            val json = runMycelium(
                "import * as m from '$module';\n" +
                    "globalThis.__mp_result = JSON.stringify(Object.keys(m).sort());"
            )
            return parseJsonStringArray(json)
        }

        // Minimal JSON string-array parser — avoids pulling in a JSON library dependency just
        // for `["a","b"]`-shaped output. Assumes no escaped characters in the identifiers
        // being parsed (true for every JS export/property name this file deals with).
        fun parseJsonStringArray(json: String): List<String> {
            val trimmed = json.trim()
            if (trimmed == "[]") return emptyList()
            return trimmed
                .removePrefix("[")
                .removeSuffix("]")
                .split(",")
                .map { it.trim().removePrefix("\"").removeSuffix("\"") }
                .filter { it.isNotEmpty() }
        }

        // Formats a "here's exactly what's missing / extra" report used by every dynamic
        // test below. Doesn't assert by itself — callers decide what's fatal.
        fun diffReport(label: String, nodeSet: Set<String>, myceliumSet: Set<String>): String {
            val missing = (nodeSet - myceliumSet).sorted()
            val extra = (myceliumSet - nodeSet).sorted()
            val sb = StringBuilder()
            sb.appendLine("$label: Node has ${nodeSet.size}, Mycelium has ${myceliumSet.size}")
            if (missing.isEmpty()) {
                sb.appendLine("  gaps: none")
            } else {
                sb.appendLine("  gaps (in Node, missing from Mycelium): $missing")
            }
            if (extra.isNotEmpty()) {
                sb.appendLine("  extras (in Mycelium, not in Node): $extra")
            }
            return sb.toString()
        }
    }

    // --- 1. Export surface parity, one dynamic test per module. ---
    //
    // Not a strict pass/fail on any gap — the assertion only fires if *loading the module at
    // all* fails in Mycelium but not in Node (a hard break — the module isn't registered/
    // importable), or Mycelium's export set is completely empty while Node's isn't (a sign
    // the module is a stub rather than just incomplete). Partial gaps are reported via
    // println, not by failing the test — see class doc for why.

    @TestFactory
    fun `parity check - exports`(): List<DynamicTest> {
        return MODULES.map { module ->
            dynamicTest("$module exports match Node") {
                assumeTrue(nodeCommand() != null, "node not available — skipping parity test")

                val nodeExports = try {
                    nodeModuleExports(module).toSet()
                } catch (e: Exception) {
                    fail<Unit>("Node itself failed to load module '$module': ${e.message}")
                    return@dynamicTest
                }

                val myceliumExports = try {
                    myceliumModuleExports(module).toSet()
                } catch (e: Exception) {
                    // Mycelium failing to load a module Node loads fine is the one thing this
                    // test treats as a hard failure — it means the module isn't registered/
                    // importable at all, not just that some exports are missing.
                    fail<Unit>(
                        "Mycelium failed to load module '$module' (Node loads it fine with " +
                            "${nodeExports.size} exports): ${e.message}"
                    )
                    return@dynamicTest
                }

                val report = diffReport(module, nodeExports, myceliumExports)
                println(report)

                if (nodeExports.isNotEmpty() && myceliumExports.isEmpty()) {
                    fail<Unit>(
                        "Mycelium's '$module' exports nothing at all, but Node exports " +
                            "${nodeExports.size} members — module is likely a stub or " +
                            "mis-wired.\n$report"
                    )
                }
            }
        }
    }

    // --- 2. Prototype/static-method parity for modules where the default export IS the
    // interesting object (an instance-producing class/constructor), not a namespace of named
    // functions. Export-name diffing alone doesn't catch a method missing from
    // EventEmitter.prototype, since `Object.keys(m)` for `require('events')` is just
    // `["EventEmitter", ...]` — the methods live one level deeper, on the prototype. ---

    private data class PrototypeCase(
        val label: String,
        val nodeExpr: String,
        val myceliumImport: String,
        val myceliumExpr: String,
    )

    @TestFactory
    fun `parity check - prototypes`(): List<DynamicTest> {
        val cases = listOf(
            PrototypeCase(
                "EventEmitter.prototype",
                "require('events').EventEmitter.prototype",
                "import { EventEmitter } from 'events';",
                "EventEmitter.prototype"
            ),
            PrototypeCase(
                "EventEmitter statics",
                "require('events').EventEmitter",
                "import { EventEmitter } from 'events';",
                "EventEmitter"
            ),
            PrototypeCase(
                "Buffer.prototype",
                "require('buffer').Buffer.prototype",
                "import { Buffer } from 'buffer';",
                "Buffer.prototype"
            ),
            PrototypeCase(
                "Buffer statics",
                "require('buffer').Buffer",
                "import { Buffer } from 'buffer';",
                "Buffer"
            ),
            PrototypeCase(
                "stream.Readable.prototype",
                "require('stream').Readable.prototype",
                "import { Readable } from 'stream';",
                "Readable.prototype"
            ),
            PrototypeCase(
                "stream.Writable.prototype",
                "require('stream').Writable.prototype",
                "import { Writable } from 'stream';",
                "Writable.prototype"
            ),
            PrototypeCase(
                "StringDecoder.prototype",
                "require('string_decoder').StringDecoder.prototype",
                "import { StringDecoder } from 'string_decoder';",
                "StringDecoder.prototype"
            ),
        )
        return cases.map { case ->
            dynamicTest("${case.label} matches Node") {
                assumeTrue(nodeCommand() != null, "node not available — skipping parity test")

                val nodeMethods = try {
                    parseJsonStringArray(
                        runNode("console.log(JSON.stringify(Object.getOwnPropertyNames(${case.nodeExpr}).sort()));")
                    ).toSet()
                } catch (e: Exception) {
                    fail<Unit>("Node failed evaluating '${case.nodeExpr}': ${e.message}")
                    return@dynamicTest
                }

                val myceliumMethods = try {
                    val json = runMycelium(
                        "${case.myceliumImport}\n" +
                            "globalThis.__mp_result = JSON.stringify(Object.getOwnPropertyNames(${case.myceliumExpr}).sort());"
                    )
                    parseJsonStringArray(json).toSet()
                } catch (e: Exception) {
                    fail<Unit>(
                        "Mycelium failed evaluating '${case.myceliumExpr}' (Node succeeds " +
                            "with ${nodeMethods.size} members): ${e.message}"
                    )
                    return@dynamicTest
                }

                val report = diffReport(case.label, nodeMethods, myceliumMethods)
                println(report)

                if (nodeMethods.isNotEmpty() && myceliumMethods.isEmpty()) {
                    fail<Unit>("Mycelium's '${case.myceliumExpr}' has no own properties at all.\n$report")
                }
            }
        }
    }

    // --- 3. fs sync/async surface presence. Node's fs exports both `readFileSync` and
    // `readFile` (callback-based async) for most operations; the export-name diff above
    // already reports the full gap, but this makes the sync-vs-async split explicit as its
    // own signal, since a shim can plausibly implement only the sync half. Purely
    // informational (always passes) — see printed output for the actual counts. ---

    @TestFactory
    fun `parity check - fs sync vs async coverage`(): List<DynamicTest> {
        return listOf("fs").map { module ->
            dynamicTest("$module has matching sync/async pairs") {
                assumeTrue(nodeCommand() != null, "node not available — skipping parity test")

                val nodeExports = nodeModuleExports(module).toSet()
                val myceliumExports = myceliumModuleExports(module).toSet()

                val nodeSyncFns = nodeExports.filter { it.endsWith("Sync") }
                val nodeAsyncCounterparts = nodeSyncFns.filter { it.removeSuffix("Sync") in nodeExports }

                val myceliumSyncFns = myceliumExports.filter { it.endsWith("Sync") }
                val myceliumAsyncCounterparts = myceliumSyncFns.filter { it.removeSuffix("Sync") in myceliumExports }

                println(
                    "$module sync/async pairs — Node: ${nodeAsyncCounterparts.size}/${nodeSyncFns.size} " +
                        "sync fns have an async counterpart; Mycelium: " +
                        "${myceliumAsyncCounterparts.size}/${myceliumSyncFns.size}"
                )
                val missingAsyncCounterparts = myceliumSyncFns - myceliumAsyncCounterparts.toSet()
                if (missingAsyncCounterparts.isNotEmpty()) {
                    println(
                        "  Mycelium sync fns with no async counterpart: $missingAsyncCounterparts " +
                            "(informational only, not a failure)"
                    )
                }
            }
        }
    }

    // --- 4. crypto.getHashes()/getCiphers() — algorithm-list parity. These are
    // runtime-enumerated lists (OpenSSL-backed in real Node) rather than a fixed API
    // surface, and Mycelium's crypto is JVM-backed (java.security), so the actual algorithm
    // *sets* are expected to differ — this documents the difference rather than asserting
    // equality. Purely informational. ---

    @TestFactory
    fun `parity check - crypto algorithm lists`(): List<DynamicTest> {
        return listOf("getHashes", "getCiphers").map { fnName ->
            dynamicTest("crypto.$fnName() algorithm list") {
                assumeTrue(nodeCommand() != null, "node not available — skipping parity test")

                val nodeList = try {
                    parseJsonStringArray(
                        runNode("console.log(JSON.stringify(require('crypto').$fnName()));")
                    ).toSet()
                } catch (e: Exception) {
                    println("Node crypto.$fnName() failed: ${e.message}")
                    return@dynamicTest
                }

                val myceliumList = try {
                    parseJsonStringArray(
                        runMycelium(
                            "import * as crypto from 'crypto';\n" +
                                "globalThis.__mp_result = JSON.stringify(crypto.$fnName ? crypto.$fnName() : []);"
                        )
                    ).toSet()
                } catch (e: Exception) {
                    println("Mycelium crypto.$fnName() failed or is unimplemented: ${e.message}")
                    return@dynamicTest
                }

                println(diffReport("crypto.$fnName()", nodeList, myceliumList))
                if (myceliumList.isEmpty() && nodeList.isNotEmpty()) {
                    println("  Mycelium's crypto.$fnName() returns nothing — likely unimplemented.")
                }
            }
        }
    }

    // --- 5. Behavioral (not just shape) parity: run the same expression through both
    // runtimes and compare the actual computed result, not just whether the function
    // exists. ---

    private data class BehaviorCase(
        val label: String,
        val nodeScript: String,
        val myceliumScript: String,
    )

    @TestFactory
    fun `deep parity check - behavioral equivalence`(): List<DynamicTest> {
        val cases = listOf(
            BehaviorCase(
                "path.join('a', 'b', 'c')",
                "console.log(require('path').join('a', 'b', 'c'));",
                "import { join } from 'path';\nglobalThis.__mp_result = join('a', 'b', 'c');"
            ),
            BehaviorCase(
                "path.resolve('/foo', 'bar')",
                "console.log(require('path').resolve('/foo', 'bar'));",
                "import { resolve } from 'path';\nglobalThis.__mp_result = resolve('/foo', 'bar');"
            ),
            BehaviorCase(
                "util.format('%s %d', 'hello', 42)",
                "console.log(require('util').format('%s %d', 'hello', 42));",
                "import { format } from 'util';\nglobalThis.__mp_result = format('%s %d', 'hello', 42);"
            ),
            BehaviorCase(
                "url.parse('https://example.com/path?q=1').host",
                "console.log(require('url').parse('https://example.com/path?q=1').host);",
                "import { parse } from 'url';\nglobalThis.__mp_result = parse('https://example.com/path?q=1').host;"
            ),
            BehaviorCase(
                "querystring.stringify({a: 1, b: [2, 3]})",
                "console.log(require('querystring').stringify({a: 1, b: [2, 3]}));",
                "import { stringify } from 'querystring';\nglobalThis.__mp_result = stringify({a: 1, b: [2, 3]});"
            ),
            BehaviorCase(
                "os.platform()",
                "console.log(require('os').platform());",
                "import { platform } from 'os';\nglobalThis.__mp_result = platform();"
            ),
            BehaviorCase(
                "os.arch()",
                "console.log(require('os').arch());",
                "import { arch } from 'os';\nglobalThis.__mp_result = arch();"
            ),
            BehaviorCase(
                "Buffer.from('hello').toString('hex')",
                "console.log(Buffer.from('hello').toString('hex'));",
                "import { Buffer } from 'buffer';\nglobalThis.__mp_result = Buffer.from('hello').toString('hex');"
            ),
            BehaviorCase(
                "crypto.createHash('sha256').update('hello').digest('hex')",
                "console.log(require('crypto').createHash('sha256').update('hello').digest('hex'));",
                "import { createHash } from 'crypto';\nglobalThis.__mp_result = createHash('sha256').update('hello').digest('hex');"
            ),
        )

        return cases.map { case ->
            dynamicTest(case.label) {
                assumeTrue(nodeCommand() != null, "node not available — skipping parity test")

                val nodeResult = try {
                    runNode(case.nodeScript)
                } catch (e: Exception) {
                    fail<Unit>("Node failed evaluating ${case.label}: ${e.message}")
                    return@dynamicTest
                }

                val myceliumResult = try {
                    runMycelium(case.myceliumScript)
                } catch (e: Exception) {
                    fail<Unit>(
                        "Mycelium failed evaluating ${case.label} (Node produced " +
                            "'$nodeResult'): ${e.message}"
                    )
                    return@dynamicTest
                }

                println("${case.label}: Node='$nodeResult' Mycelium='$myceliumResult'")
                assertEquals(nodeResult, myceliumResult, "behavioral mismatch for ${case.label}")
            }
        }
    }
}

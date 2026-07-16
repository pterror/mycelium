package org.mycelium.library

import java.nio.charset.StandardCharsets
import java.nio.channels.SeekableByteChannel
import java.nio.file.Path

// Node stdlib modules Mycelium ships as synthetic ESM resources rather than resolving
// through node_modules — see library/src/main/resources/node-stdlib/. Both the bare
// ("path") and "node:"-prefixed forms are accepted, per Node's own convention.
object CoreModuleRegistry {
    const val CORE_MODULE_PREFIX = "/.mycelium-core/"

    val coreModules = mapOf(
        "path" to "path.mjs",
        "node:path" to "path.mjs",
        "events" to "events.mjs",
        "node:events" to "events.mjs",
        "util" to "util.mjs",
        "node:util" to "util.mjs",
        "util/types" to "util-types.mjs",
        "node:util/types" to "util-types.mjs",
        "os" to "os.mjs",
        "node:os" to "os.mjs",
        "fs" to "fs.mjs",
        "node:fs" to "fs.mjs",
        "fs/promises" to "fs-promises.mjs",
        "node:fs/promises" to "fs-promises.mjs",
        "buffer" to "buffer.mjs",
        "node:buffer" to "buffer.mjs",
        "url" to "url.mjs",
        "node:url" to "url.mjs",
        "stream" to "stream.mjs",
        "node:stream" to "stream.mjs",
        "crypto" to "crypto.mjs",
        "node:crypto" to "crypto.mjs",
        "process" to "process.mjs",
        "node:process" to "process.mjs",
        "assert" to "assert.mjs",
        "node:assert" to "assert.mjs",
        "assert/strict" to "assert.mjs",
        "node:assert/strict" to "assert.mjs",
        "querystring" to "querystring.mjs",
        "node:querystring" to "querystring.mjs",
        "string_decoder" to "string_decoder.mjs",
        "node:string_decoder" to "string_decoder.mjs",
        "zlib" to "zlib.mjs",
        "node:zlib" to "zlib.mjs",
        "http" to "http.mjs",
        "node:http" to "http.mjs",
        "https" to "https.mjs",
        "node:https" to "https.mjs",
        "tty" to "tty.mjs",
        "node:tty" to "tty.mjs",
        "http2" to "http2.mjs",
        "node:http2" to "http2.mjs",
        "net" to "net.mjs",
        "node:net" to "net.mjs",
        "tls" to "tls.mjs",
        "node:tls" to "tls.mjs",
        "child_process" to "child_process.mjs",
        "node:child_process" to "child_process.mjs",
        "diagnostics_channel" to "diagnostics_channel.mjs",
        "node:diagnostics_channel" to "diagnostics_channel.mjs",
        "async_hooks" to "async_hooks.mjs",
        "node:async_hooks" to "async_hooks.mjs",
        "readline" to "readline.mjs",
        "node:readline" to "readline.mjs",
        "readline/promises" to "readline-promises.mjs",
        "node:readline/promises" to "readline-promises.mjs",
        "worker_threads" to "worker_threads.mjs",
        "node:worker_threads" to "worker_threads.mjs",
        "module" to "module.mjs",
        "node:module" to "module.mjs",
        "dns" to "dns.mjs",
        "node:dns" to "dns.mjs",
        "dns/promises" to "dns-promises.mjs",
        "node:dns/promises" to "dns-promises.mjs",
        "timers" to "timers.mjs",
        "node:timers" to "timers.mjs",
        "timers/promises" to "timers-promises.mjs",
        "node:timers/promises" to "timers-promises.mjs",
        "perf_hooks" to "perf_hooks.mjs",
        "node:perf_hooks" to "perf_hooks.mjs",
    )

    fun isCoreModule(specifier: String): Boolean = coreModules.containsKey(specifier)

    fun syntheticCoreModulePath(resourceFile: String): Path =
        Path.of("$CORE_MODULE_PREFIX$resourceFile")

    fun readCoreModule(path: Path): SeekableByteChannel {
        val resourceName = path.toString().removePrefix(CORE_MODULE_PREFIX)
        val stream = javaClass.classLoader.getResourceAsStream("node-stdlib/$resourceName")
            ?: throw MyceliumException("mycelium: unknown core module resource '$resourceName'")
        val content = stream.use { it.readBytes() }.toString(StandardCharsets.UTF_8)
        return ReadOnlyBufferChannel(StandardCharsets.UTF_8.encode(content))
    }
}

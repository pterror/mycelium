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
        "vm" to "vm.mjs",
        "node:vm" to "vm.mjs",
    )

    fun isCoreModule(specifier: String): Boolean = coreModules.containsKey(specifier)

    fun syntheticCoreModulePath(resourceFile: String): Path =
        Path.of("$CORE_MODULE_PREFIX$resourceFile")

    fun readCoreModule(path: Path): SeekableByteChannel {
        val resourceName = path.toString().removePrefix(CORE_MODULE_PREFIX)
        val stream = javaClass.classLoader.getResourceAsStream("node-stdlib/$resourceName")
            ?: throw MyceliumException("mycelium: unknown core module resource '$resourceName'")
        val content = stream.use { it.readBytes() }.toString(StandardCharsets.UTF_8)
        return ReadOnlyBufferChannel(StandardCharsets.UTF_8.encode(normalizeCoreModuleSpecifiers(content)))
    }

    // GraalJS's own ESM loader (DefaultESModuleLoader, GraalVM 24.2/25.0/25.1) keys its
    // module cache differently depending on whether an import specifier contains a colon.
    // A specifier like "events" (no colon) is resolved to a TruffleFile via our FileSystem
    // and the cache is keyed on that file's path — but a specifier like "node:events" is
    // first parsed as an *absolute URI* (scheme "node") by the loader itself, purely as
    // string parsing, before our FileSystem is ever consulted; that routes it through a
    // completely different method (loadModuleFromURL, as opposed to loadModuleFromFile)
    // whose cache key is the raw specifier string ("node:events") rather than any resolved
    // path — our FileSystem's own resolution of the "node:" URI (see
    // InterceptingFileSystem.parsePath(URI)) only supplies the byte content, it has no
    // influence on the cache key GraalJS uses. The two keys ("node:events" vs
    // "/.mycelium-core/events.mjs") never match as strings, so a module written with the
    // "node:" prefix and the equivalent bare form are never recognized as the same module
    // by GraalJS — each is loaded and evaluated separately, producing two distinct classes
    // (e.g. two different `EventEmitter` constructors) even though both ultimately read the
    // exact same resource. This can't be fixed by anything our FileSystem returns from
    // parsePath, since the URI-shaped route's cache key is decided before our FileSystem is
    // even called.
    //
    // The fix applied here: normalize every "node:"-prefixed *import/export specifier* for a
    // recognized core module to the bare form before GraalJS ever parses the source, so
    // resolving that module always takes the bare-specifier (loadModuleFromFile) route and
    // therefore always shares one cached module instance with every other place that imports
    // the same core module — which is what lets e.g. stream.mjs's Readable/Writable and
    // http.mjs's IncomingMessage/ServerResponse extend the exact same EventEmitter class as
    // everything else that imports "events"/"node:events", instead of each ending up with its
    // own separately-evaluated copy.
    //
    // Applied to our own bundled node-stdlib resources (via readCoreModule, above) so
    // core-module-to-core-module imports are unaffected by which form the .mjs source
    // happens to use, AND to every plain project/npm .js/.mjs file InterceptingFileSystem
    // reads (see its newByteChannel), so a project that writes `import { EventEmitter } from
    // "node:events"` at its own top level still shares identity with whatever core modules
    // it transitively imports (which, per the previous paragraph, always resolve `events` via
    // the bare route). Safe to run over arbitrary project/npm source too, unlike a blanket
    // "any node:-prefixed string" rewrite would be: the regex only fires immediately after an
    // `import`/`export ... from`/dynamic `import(` token, and only for specifiers matching a
    // *known* core module name — so an unrelated string literal that merely looks like
    // "node:events" (e.g. used as data, not as an import specifier) is never touched.
    private val CORE_MODULE_IMPORT_SPECIFIER = Regex(
        """((?:\bfrom\s*|\bimport\s*\(\s*))(['"])node:([\w/-]+)\2"""
    )

    fun normalizeCoreModuleSpecifiers(content: String): String {
        if (!content.contains("node:")) return content
        return CORE_MODULE_IMPORT_SPECIFIER.replace(content) { match ->
            val (prefix, quote, moduleName) = match.destructured
            if (coreModules.containsKey(moduleName)) "$prefix$quote$moduleName$quote" else match.value
        }
    }
}

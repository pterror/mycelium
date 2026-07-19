package org.mycelium.library

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

// Implements Node-style CommonJS `require()` — resolution, the module cache, and actually
// running a module's source through Node's function-wrapping convention — plus the CJS-
// to-ESM interop shim InterceptingFileSystem hands GraalJS's ESM loader when an `import`
// targets a CJS file. `context` is a lambda (rather than a plain property) for the same
// reason as on NodeModuleResolver: this runtime is constructed before InterceptingFileSystem
// has a real GraalVM Context to hand it.
class CjsRuntime(
    private val context: () -> Context,
    private val nodeModuleResolver: NodeModuleResolver,
) {
    // `require()`d CommonJS modules, keyed by resolved absolute path (as a string). Each
    // cached Value is the module's Node-style module object — `{ exports, id, filename,
    // loaded }` — not just its exports, and is inserted into the cache *before* the
    // module body runs (see requireCjsModule). That's what makes a circular require (A
    // requires B while B is still in the middle of requiring A) return the same,
    // possibly-still-partial `module.exports` object reference instead of recursively
    // re-evaluating A and stack-overflowing — and what makes `require(x) === require(x)`
    // true and top-level module side effects run exactly once.
    val cjsModuleCache = mutableMapOf<String, org.graalvm.polyglot.Value>()

    // Backs the `require` shim InterceptingFileSystem prepends to transpiled CJS output
    // (see the CJS branch of InterceptingFileSystem.newByteChannel) — wired up as the JS
    // global `__mycelium_require__` in Mycelium.init. `fromDirStr` is the requiring file's
    // own directory, captured at transpile time (not the source-directory stack, which has
    // already been popped by the time the transpiled module's top-level code — including
    // its `require(...)` calls — actually runs).
    fun requireModule(specifier: String, fromDirStr: String): org.graalvm.polyglot.Value {
        val fromDir = Path.of(fromDirStr)
        val resolved = resolveRequireTarget(specifier, fromDir)
        // Mycelium has no real N-API/native-addon loader (no dlopen, no Node-API host
        // bridge) — a `.node` file can never actually be `require()`d, whether or not it
        // exists on disk. Reporting it the same way Node reports a genuinely-missing
        // module (MODULE_NOT_FOUND, rather than e.g. attempting to read the binary as JS
        // source and letting GraalJS's parser choke on it with a `.code`-less SyntaxError)
        // is what lets packages with a graceful "no prebuilt binary for this platform"
        // fallback path — like sharp's own `errors.push(err)`-per-candidate loop in
        // dist/sharp.mjs — actually take it instead of crashing on a missing `.code`. See
        // throwModuleNotFound and NpmCompatibilityTest.sharpFailsGracefullyWithNodeShapedError.
        if (!resolved.startsWith(CoreModuleRegistry.CORE_MODULE_PREFIX) && resolved.extension == "node") {
            throwModuleNotFound(specifier, fromDir)
        }
        val cacheKey = resolved.toString()

        cjsModuleCache[cacheKey]?.let { return it.getMember("exports") }

        return if (resolved.startsWith(CoreModuleRegistry.CORE_MODULE_PREFIX)) {
            requireCoreModule(resolved, cacheKey)
        } else {
            requireCjsModule(resolved, cacheKey)
        }
    }

    // Backs `require.resolve(specifier)` — wired up as the JS global
    // `__mycelium_require_resolve__` in Mycelium.init. Runs the exact same resolution
    // logic `require()` itself uses (relative paths, core modules, node_modules walk) but
    // hands back the resolved path/specifier without ever loading the module. Core modules
    // resolve back to their bare specifier (e.g. "fs"), matching Node's own
    // `require.resolve('fs') === 'fs'` behavior, rather than exposing Mycelium's internal
    // synthetic resource path.
    fun resolveRequireSpecifier(specifier: String, fromDirStr: String): String {
        val fromDir = Path.of(fromDirStr)
        if (!specifier.startsWith(".") && !specifier.startsWith("/")) {
            CoreModuleRegistry.coreModules[specifier]?.let { return specifier }
        }
        val resolved = resolveRequireTarget(specifier, fromDir)
        if (resolved.startsWith(CoreModuleRegistry.CORE_MODULE_PREFIX)) return specifier
        return resolved.toString()
    }

    // Backs `require.resolve.paths(specifier)` — the list of `node_modules` directories
    // that would be searched for `specifier` starting from `fromDirStr`, in search order.
    // Returns an empty array for relative/absolute specifiers and core modules (Node
    // returns `null` for core modules; an empty array is close enough for the common
    // "does resolution work" use case and avoids modeling a nullable return through the
    // polyglot boundary).
    fun resolveRequireSpecifierPaths(specifier: String, fromDirStr: String): Array<String> {
        if (specifier.startsWith(".") || specifier.startsWith("/")) return emptyArray()
        if (CoreModuleRegistry.isCoreModule(specifier)) return emptyArray()
        val result = mutableListOf<String>()
        var dir: Path? = Path.of(fromDirStr).toAbsolutePath().normalize()
        while (dir != null) {
            result.add(dir.resolve("node_modules").toString())
            dir = dir.parent
        }
        return result.toTypedArray()
    }

    // Node's CJS/ESM interop statically analyzes a required CJS module's exports object
    // (via cjs-module-lexer) and synthesizes a named ES export for each of its own
    // properties — that's what makes `import { detect } from 'chardet'` work even though
    // chardet only ever does `module.exports.detect = ...` / `exports.detect = ...`, never
    // an explicit ESM `export`. Mycelium has no static lexer, so this mirrors that by
    // actually `require()`-ing the module right now (via requireModule, the exact same
    // path/cache `__mycelium_require__` uses at runtime — see cjsModuleCache) purely to
    // discover its own member keys, then bakes a `export const x = _mod.x;` per member
    // into the generated shim alongside the whole-module default export. Because
    // requireModule shares cjsModuleCache with `__mycelium_require__`, the module's
    // top-level code still only runs once: this call runs it (if not already cached), and
    // the `__mycelium_require__` call embedded in the shim below just replays the cached
    // `module.exports` when the shim itself is evaluated.
    fun buildCjsEsmShim(path: String, dir: String): String {
        val exportsValue = requireModule(path, dir)
        val sb = StringBuilder()
        sb.append("const _mod = globalThis.__mycelium_require__(")
        sb.append(jsonStringLiteral(path)).append(", ").append(jsonStringLiteral(dir)).append(");\n")
        sb.append("export default _mod;\n")
        // `module.exports = function(){...}` (or any other non-object primitive) is a
        // valid CJS export shape — hasMembers() is false for those (well, functions do
        // have members, but e.g. a string/number export doesn't), so guard before
        // enumerating rather than letting memberKeys throw/return garbage.
        if (exportsValue.hasMembers()) {
            for (key in exportsValue.memberKeys) {
                // Node never re-exports "default" as a named binding (it's already the
                // default export above), and only valid JS identifiers/non-reserved-words
                // can become `export const <name> = ...;` declarations — anything else
                // (numeric keys, "foo-bar", etc.) is simply skipped, same as cjs-module-lexer.
                if (key == "default" || !JsSupport.isValidExportName(key)) continue
                sb.append("export const ").append(key).append(" = _mod.").append(key).append(";\n")
            }
        }
        return sb.toString()
    }

    // Resolves a `require()` specifier the same way Node would: relative/absolute paths
    // resolve against the requiring file's directory (trying a few conventional
    // extensions if the exact path doesn't exist), core modules resolve to their
    // synthetic resource path, and everything else resolves via the same node_modules
    // walk bare ESM imports use.
    private fun resolveRequireTarget(specifier: String, fromDir: Path): Path {
        if (specifier.startsWith(".") || specifier.startsWith("/")) {
            val candidate = fromDir.resolve(specifier).normalize()
            // Previously short-circuited on `candidate.extension.isNotEmpty()` to skip
            // extension-probing an already-extensioned candidate — but `Path.extension`
            // just looks for the last `.` in the filename, which false-positives on
            // filenames that contain a `.` without it being a real extension separator
            // (e.g. `require('./Reflect.getPrototypeOf')` resolving to the file
            // `Reflect.getPrototypeOf.js`: `.getPrototypeOf` reads as a nonempty
            // "extension" even though the specifier has no real extension at all). Always
            // falling through to resolveExtensionlessPath when the literal path doesn't
            // resolve to a regular file is safe either way — it returns `candidate`
            // unchanged if none of the probed variants exist either, so genuinely-missing
            // modules still error out exactly as before.
            //
            // Checks `isRegularFile`, not `exists` — `exists` is also true for directories,
            // and a bare `require("./transports")` naming a directory (whose real entry
            // point is `./transports/index.js`) used to short-circuit here and return the
            // directory itself, which `requireCjsModule`'s later `resolved.readText()` then
            // choked on with an unqualified, guest-stack-free "Is a directory" IOException
            // (see engine.io, a socket.io transitive dependency, in NpmCompatibilityTest).
            if (Files.isRegularFile(candidate)) return candidate
            val resolved = nodeModuleResolver.resolveExtensionlessPath(candidate)
            // resolveExtensionlessPath hands back `candidate` itself, unresolved, when none
            // of the probed variants exist — surface Node's usual "cannot find module"
            // error here (with a real `.code`, see throwModuleNotFound) rather than letting
            // the caller's `Path.readText()` blow up with a raw, unqualified
            // FileSystemException that has no `.code` for guest code to inspect (e.g.
            // sharp's native-binding loader probes several `require("../src/build/Release/
            // sharp-*.node")`-shaped relative paths that are never actually shipped in the
            // npm package, expecting a Node-shaped MODULE_NOT_FOUND it can catch).
            if (!Files.isRegularFile(resolved)) throwModuleNotFound(specifier, fromDir)
            return resolved
        }
        CoreModuleRegistry.coreModules[specifier]?.let { return CoreModuleRegistry.syntheticCoreModulePath(it) }
        // preferRequire = true: this is a CJS require() target, so a dual-mode package's
        // exports map should prefer its "require" condition over "import" — see
        // NodeModuleResolver.resolveNodeModule's own comment.
        return when (val resolution = nodeModuleResolver.resolveNodeModule(specifier, fromDir, preferRequire = true)) {
            is NodeModuleResolver.Resolution.Found -> resolution.path
            is NodeModuleResolver.Resolution.PackageNotFound, NodeModuleResolver.Resolution.NoNodeModulesTree ->
                throwModuleNotFound(specifier, fromDir)
        }
    }

    // Node's stdlib shims (path, events, ...) are shipped as genuine ESM sources rather
    // than CommonJS (see node-stdlib/ resources), so they're evaluated as an ES module
    // directly instead of being run through the Node-style CJS wrapper requireCjsModule
    // uses. Still cached under the same `{ exports, loaded }` shape as CJS modules so the
    // cache-lookup fast path in requireModule works uniformly regardless of which kind of
    // module was loaded.
    private fun requireCoreModule(resolved: Path, cacheKey: String): org.graalvm.polyglot.Value {
        val content = readChannelToString(CoreModuleRegistry.readCoreModule(resolved))
        // Core modules live at a synthetic path with no real parent directory on disk (and
        // have no relative imports of their own to resolve), so their temp file goes in
        // the system temp dir rather than alongside a nonexistent "resolved.parent".
        return requireEsmModule(content, cacheKey, tmpFileDir = null)
    }

    // Evaluates `content` as a genuine ES module (rather than through the Node-style CJS
    // wrapper requireCjsModule uses) and caches the result the same way, for callers that
    // determined the target isn't actually CommonJS — core stdlib modules, and `.js` files
    // that are nominally CommonJS by Node's package.json rule but contain genuine ESM
    // syntax anyway (see NodeModuleResolver.looksLikeEsm).
    private fun requireEsmModule(content: String, cacheKey: String, tmpFileDir: File?): org.graalvm.polyglot.Value {
        // Mirrors Mycelium.evalModule: writing to a real, `.mjs`-suffixed temp file
        // (rather than an in-memory CharSequence Source) is what actually makes GraalJS
        // both parse it as a module *and* hand context.eval() back the module's namespace
        // object instead of `undefined` — a bare `.mimeType(...)` hint on a CharSequence
        // Source was tried and evaluated fine but returned an empty namespace. The temp
        // file is created alongside the source's own directory (when known) so any
        // relative imports inside `content` keep resolving against the right base
        // directory.
        val tmpFile = File.createTempFile("mycelium-require-esm-", ".mjs", tmpFileDir)
        val moduleValue = try {
            tmpFile.writeText(content)
            context().eval(Source.newBuilder("js", tmpFile).build())
        } finally {
            tmpFile.delete()
        }
        // Mirrors Node's CJS/ESM interop: a module with a default export hands that back
        // verbatim; a module with only named exports hands back its whole namespace
        // object instead.
        val exports = if (moduleValue.hasMember("default")) moduleValue.getMember("default") else moduleValue
        val moduleObj = context().eval("js", "({ exports: undefined, loaded: true })")
        moduleObj.putMember("exports", exports)
        cjsModuleCache[cacheKey] = moduleObj
        return exports
    }

    // Runs `resolved`'s source through Node's own CJS module-wrapping convention —
    // wrapping it in `(function(exports, require, module, __filename, __dirname) { ... })`
    // and invoking that function with a fresh `module`/`exports` object — rather than
    // transpiling to ESM (the previous CjsCompiler/esbuild approach). This is what makes
    // `module`, `exports`, `require`, `__filename` and `__dirname` all behave exactly as
    // they do in Node without any static rewriting, and (combined with inserting the
    // module object into the cache *before* calling the wrapper below) is what makes
    // circular requires return partial exports instead of stack-overflowing.
    private fun requireCjsModule(resolved: Path, cacheKey: String): org.graalvm.polyglot.Value {
        val filename = resolved.toString()
        val dir = (resolved.parent ?: resolved).toString()
        val content = resolved.readText()

        // See NodeModuleResolver.looksLikeEsm: a file can be *treated* as CommonJS by
        // Node's own package.json-driven rule while still containing genuine ESM syntax —
        // evaluate such content as a real ES module instead of wrapping it as CJS (which
        // would be a syntax error, since `export`/top-level `import` aren't legal inside a
        // function body).
        if (nodeModuleResolver.looksLikeEsm(content)) {
            return requireEsmModule(content, cacheKey, tmpFileDir = (resolved.parent ?: resolved).toFile())
        }

        val moduleObj = context().eval(
            "js",
            "({ exports: {}, id: ${jsonStringLiteral(filename)}, " +
                "filename: ${jsonStringLiteral(filename)}, loaded: false })"
        )
        // Inserted into the cache *before* the module body runs below — this is what
        // makes a circular require (A requires B while B is still in the middle of
        // requiring A) return the same, possibly-still-partial `module.exports` object
        // reference instead of recursively re-evaluating A and stack-overflowing.
        cjsModuleCache[cacheKey] = moduleObj

        // A `require("./data.json")` should hand back the parsed JSON value itself (as in
        // Node), not run JSON source text through the function wrapper below (which would
        // parse as a syntactically-invalid function body for anything but a bare object
        // literal statement).
        if (resolved.extension == "json") {
            val parsed = context().eval("js", "(function(json) { return JSON.parse(json); })").execute(content)
            moduleObj.putMember("exports", parsed)
            moduleObj.putMember("loaded", true)
            return parsed
        }

        // Each module gets its own `require` function, closing over *that* module's own
        // directory — not the entry file's — so a relative `require("./x")` inside a
        // nested module resolves against the directory it actually lives in.
        val requireForModule = context().eval(
            "js",
            "(function() {\n" +
                "    const req = function(id) { return globalThis.__mycelium_require__(id, ${jsonStringLiteral(dir)}); };\n" +
                "    req.resolve = function(id) { return globalThis.__mycelium_require_resolve__(id, ${jsonStringLiteral(dir)}); };\n" +
                "    req.resolve.paths = function(id) { return Array.from(globalThis.__mycelium_require_resolve_paths__(id, ${jsonStringLiteral(dir)})); };\n" +
                "    return req;\n" +
                "})()"
        )

        val wrapperSource = "(function(exports, require, module, __filename, __dirname) {\n" +
            content +
            "\n})"
        val wrapperFn = context().eval(Source.newBuilder("js", wrapperSource, filename).build())

        wrapperFn.execute(moduleObj.getMember("exports"), requireForModule, moduleObj, filename, dir)
        moduleObj.putMember("loaded", true)
        return moduleObj.getMember("exports")
    }

    // Throws a genuine, guest-catchable JS `Error` shaped like Node's own require()
    // failure — `code: "MODULE_NOT_FOUND"` and a "Cannot find module '...'" message —
    // rather than letting a Kotlin `MyceliumException` (or a raw filesystem exception)
    // cross the polyglot boundary as an opaque host exception. GraalJS surfaces an
    // uncaught Kotlin/Java exception thrown from a `ProxyExecutable` (the `require()` shim
    // — see Mycelium.requireFn) to guest `catch` blocks as a value with no `.code`
    // property at all, which is exactly what used to make sharp's own error-formatting
    // loop (`err.code.endsWith("MODULE_NOT_FOUND")`) crash with a `TypeError: Cannot read
    // property 'endsWith' of undefined` instead of reporting its own "no native binding
    // found" message — see NpmCompatibilityTest.sharpFailsGracefullyWithNodeShapedError. Building the `Error` via
    // `context.eval` and handing it to `Value.throwException()` (rather than just
    // `throw MyceliumException(...)`) is what makes it arrive in guest code as a real,
    // `instanceof Error` object with a working `.code`.
    private fun throwModuleNotFound(specifier: String, fromDir: Path): Nothing {
        val errorValue = context().eval(
            "js",
            "(function (message, code) {\n" +
                "    var e = new Error(message);\n" +
                "    e.code = code;\n" +
                "    return e;\n" +
                "})"
        ).execute(
            "Cannot find module '$specifier'\nRequire stack:\n- $fromDir",
            "MODULE_NOT_FOUND"
        )
        throw errorValue.throwException()
    }

    // Escapes `s` as a JS double-quoted string literal, for splicing an arbitrary
    // filesystem path into generated JS source text.
    private fun jsonStringLiteral(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun readChannelToString(channel: SeekableByteChannel): String {
        // ReadOnlyBufferChannel (the only SeekableByteChannel InterceptingFileSystem hands
        // out that isn't backed by a real file) fills the destination buffer's backing
        // array directly without advancing its position — relying on `hasRemaining()`
        // to drive the read loop (as ordinary SeekableByteChannel consumers do) would
        // spin the buffer's position at 0 forever. Advance it manually from each call's
        // return value instead.
        val size = channel.size().toInt()
        val buffer = java.nio.ByteBuffer.allocate(size)
        channel.use {
            var total = 0
            while (total < size) {
                val n = it.read(buffer)
                if (n < 0) break
                total += n
                buffer.position(total)
            }
        }
        buffer.flip()
        return StandardCharsets.UTF_8.decode(buffer).toString()
    }
}

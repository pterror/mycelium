package org.mycelium.library

import org.graalvm.polyglot.Context
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

// Implements (a subset of) Node.js's own module-resolution algorithm: the node_modules
// walk, package.json `main`/`exports` entry-point resolution, subpath exports/imports, and
// CJS/ESM file-kind detection. Pure with respect to the filesystem and package.json content
// — takes paths/specifiers in, returns paths out — except for parsing package.json itself,
// which is done via a tiny embedded JS snippet run through `context` (reused purely to get
// GraalJS's `JSON.parse` rather than pulling in a separate JVM JSON library).
//
// `context` is a lambda rather than a plain property because this resolver is constructed
// (as a field initializer) before InterceptingFileSystem.init() has supplied the real
// GraalVM Context — the lambda defers that lookup until a resolution actually needs it.
class NodeModuleResolver(private val context: () -> Context) {
    sealed class Resolution {
        data class Found(val path: Path) : Resolution()
        object PackageNotFound : Resolution()
        object NoNodeModulesTree : Resolution()
    }

    // Implements (a subset of) Node.js's node_modules resolution algorithm: starting from
    // `fromDir`, check `node_modules/<packageName>` in that directory and each of its
    // ancestors in turn (not just the nearest `node_modules` directory — a package may
    // live in a higher-level `node_modules` even if a closer one exists without it),
    // stopping at the first match.
    // `preferRequire` distinguishes a CJS `require()` resolution from an ESM `import`
    // resolution — both funnel through this same node_modules walk, but a dual-mode
    // package's `exports` map can declare sibling `"import"`/`"require"` conditions with no
    // enclosing `"default"` (e.g. `ws`'s `{".": {"import": "./wrapper.mjs", "require":
    // "./index.js"}}`), and picking the wrong one hands back a module built for the wrong
    // module system. See resolvePackageEntryPoint/resolveSubpathExport, which thread this
    // through to the condition-priority list.
    fun resolveNodeModule(specifier: String, fromDir: Path, preferRequire: Boolean = false): Resolution {
        val (packageName, subPath) = splitSpecifier(specifier)
        var dir: Path? = fromDir.toAbsolutePath().normalize()
        var sawNodeModulesTree = false
        while (dir != null) {
            val nodeModules = dir.resolve("node_modules")
            if (Files.isDirectory(nodeModules)) {
                sawNodeModulesTree = true
                val packageDir = nodeModules.resolve(packageName)
                if (Files.isDirectory(packageDir)) {
                    val resolved = if (subPath != null) {
                        resolveSubpathExport(packageDir, subPath, preferRequire)
                            ?: resolveExtensionlessPath(packageDir.resolve(subPath)).let { candidate ->
                                // isRegularFile, not exists: a subpath that names an existing
                                // directory with no usable index.* falls through
                                // resolveExtensionlessPath unresolved (it returns the
                                // directory itself) — Files.exists would still be true for
                                // that directory and wrongly report it as "found", handing
                                // the caller a directory path that later chokes with an
                                // unqualified "Is a directory" IOException (same class of bug
                                // fixed in resolveExtensionlessPath/resolveRequireTarget).
                                if (!Files.isRegularFile(candidate)) return Resolution.PackageNotFound
                                candidate
                            }
                    } else {
                        resolvePackageEntryPoint(packageDir, preferRequire)
                    }
                    return Resolution.Found(resolved)
                }
            }
            dir = dir.parent
        }
        return if (sawNodeModulesTree) Resolution.PackageNotFound else Resolution.NoNodeModulesTree
    }

    // Splits a bare specifier into its package name and an optional subpath, honoring
    // scoped packages (`@scope/name[/subpath]`) as well as plain ones (`name[/subpath]`).
    fun splitSpecifier(specifier: String): Pair<String, String?> {
        val segments = specifier.split("/")
        return if (specifier.startsWith("@") && segments.size >= 2) {
            val packageName = "${segments[0]}/${segments[1]}"
            val rest = segments.drop(2).joinToString("/")
            packageName to rest.ifEmpty { null }
        } else {
            val packageName = segments[0]
            val rest = segments.drop(1).joinToString("/")
            packageName to rest.ifEmpty { null }
        }
    }

    // Reads `package.json` and resolves it to a concrete entry-point file, preferring the
    // `exports` field (`exports["."].import`/`.require` per `preferRequire`, or `.default`)
    // and falling back to `main`, then finally to the conventional `index.js`. `preferRequire`
    // is `true` when this resolution is for a CJS `require()` (see CjsRuntime.resolveRequireTarget)
    // and `false` for an ESM `import` (see InterceptingFileSystem.parsePath) — see
    // resolveNodeModule's own comment for why the distinction matters.
    fun resolvePackageEntryPoint(packageDir: Path, preferRequire: Boolean = false): Path {
        val packageJson = packageDir.resolve("package.json")
        val entry = if (Files.exists(packageJson)) {
            val json = packageJson.readText()
            try {
                context().eval("js", ENTRY_POINT_RESOLVER).execute(json, preferRequire).asString()
            } catch (e: Exception) {
                throw MyceliumException(
                    "mycelium: failed to parse '$packageJson' — ${e.message}", e
                )
            }
        } else {
            "index.js"
        }
        return resolveExtensionlessPath(packageDir.resolve(entry).normalize())
    }

    // Resolves a package-relative subpath specifier (e.g. `es-errors/eval` -> subPath
    // `eval`) against that package's own `package.json` `"exports"` map, per Node's subpath
    // exports resolution — distinct from (and consulted before falling back to) a literal
    // `packageDir.resolve(subPath)` file check, since modern packages increasingly ship an
    // `"exports"` map whose subpath keys don't correspond 1:1 with files on disk (e.g.
    // `es-errors`'s `"./eval": "./eval.js"`, where the exports *key* omits the extension
    // the file actually has). Supports both exact subpath keys and single-`*`-wildcard
    // pattern keys (e.g. `"./features/*": "./src/features/*.js"`), matching Node's own
    // subpath pattern matching. Returns null (falling back to the caller's literal-path
    // check) when there's no `"exports"` field, no `package.json`, or no matching entry.
    // `preferRequire` has the same meaning as on resolvePackageEntryPoint/resolveNodeModule
    // — `true` for a CJS `require()` resolution, `false` for an ESM `import`.
    fun resolveSubpathExport(packageDir: Path, subPath: String, preferRequire: Boolean = false): Path? {
        val packageJson = packageDir.resolve("package.json")
        if (!Files.exists(packageJson)) return null
        val json = packageJson.readText()
        val resolved = try {
            context().eval("js", SUBPATH_EXPORT_RESOLVER).execute(json, subPath, preferRequire).let {
                if (it.isString) it.asString() else null
            }
        } catch (e: Exception) {
            null
        } ?: return null
        return resolveExtensionlessPath(packageDir.resolve(resolved).normalize())
    }

    // Mirrors Node's extension/directory-index probing for a path that doesn't resolve to
    // an existing *file* as-is — e.g. a package.json `"main": "./index"` field with no file
    // extension, a `require`/`import` specifier written without one, or (the directory case)
    // a specifier like `require("./transports")` that names a directory containing its own
    // `index.js`. If `path` names an existing regular file, it's returned unchanged;
    // otherwise this tries, in order, `<path>.js`, `<path>.mjs`, `<path>.cjs`, `<path>.json`,
    // then the same four extensions under `<path>/index.*`. Returns `path` itself if none of
    // the probed candidates exist, leaving the caller to surface Node's usual "cannot find
    // module" error against the original specifier.
    //
    // Deliberately checks `Files.isRegularFile` rather than `Files.exists` for the
    // fast-path/unchanged-return case: `Files.exists` is also true for directories, so a
    // specifier that happens to name an existing directory (e.g. engine.io's
    // `require("./transports")`, where `./transports` is a directory whose real entry point
    // is `./transports/index.js`) used to be returned as-is instead of falling through to the
    // `<path>/index.*` probing below — the directory path was then handed straight to
    // `Files.newByteChannel`/`Path.readText()`, which throw an unqualified "Is a directory"
    // IOException with no guest-code stack frame (see NpmCompatibilityTest's socket.io/
    // mongoose findings). A package.json `"exports"`/`"main"` field or wildcard subpath export
    // that maps directly to a directory (rather than a file) hits this same fixed path, since
    // every caller of this function funnels through here.
    fun resolveExtensionlessPath(path: Path): Path {
        if (Files.isRegularFile(path)) return path
        val extensions = listOf("js", "mjs", "cjs", "json")
        for (ext in extensions) {
            val withExt = Path.of("$path.$ext")
            if (Files.isRegularFile(withExt)) return withExt
        }
        for (ext in extensions) {
            val indexWithExt = path.resolve("index.$ext")
            if (Files.isRegularFile(indexWithExt)) return indexWithExt
        }
        return path
    }

    // Resolves a `#`-prefixed "subpath import" specifier (e.g. `#ansi-styles`) against the
    // nearest ancestor `package.json`'s `"imports"` field, per Node's subpath-imports
    // resolution — a private-to-the-package specifier-remapping mechanism distinct from
    // `"exports"`. Walks up from `fromDir` looking for the first `package.json` that both
    // exists and declares an `"imports"` entry for `specifier`; returns null (letting the
    // caller report a "cannot find module" error) if none is found. The resolved value is
    // returned as an absolute path relative to that package.json's own directory.
    fun resolveSubpathImport(specifier: String, fromDir: Path): Path? {
        var dir: Path? = fromDir.toAbsolutePath().normalize()
        while (dir != null) {
            val packageJson = dir.resolve("package.json")
            if (Files.exists(packageJson)) {
                val json = packageJson.readText()
                val resolved = try {
                    context().eval("js", IMPORTS_RESOLVER).execute(json, specifier).let {
                        if (it.isString) it.asString() else null
                    }
                } catch (e: Exception) {
                    null
                }
                if (resolved != null) return dir.resolve(resolved).normalize()
                // A package.json with no matching (or no) "imports" entry stops the walk —
                // subpath imports are private to the *nearest* package boundary, mirroring
                // Node's own behavior of not searching past the first package.json found.
                return null
            }
            dir = dir.parent
        }
        return null
    }

    // Decides whether a `.js`/`.cjs` file should be treated as CommonJS (and therefore
    // transpiled to ESM before GraalJS sees it). `.mjs` is always ESM, `.cjs` is always
    // CommonJS; for plain `.js`, Node's own rule applies — walk up to the nearest ancestor
    // `package.json` and check its `"type"` field, defaulting to CommonJS when absent
    // (either because there's no package.json at all, or it doesn't say `"type": "module"`).
    fun isCjsFile(path: Path): Boolean {
        when (path.extension) {
            "mjs" -> return false
            "cjs" -> return true
            "js" -> {}
            else -> return false
        }
        var dir = path.parent
        while (dir != null) {
            val packageJson = dir.resolve("package.json")
            if (Files.exists(packageJson)) {
                return try {
                    val json = packageJson.readText()
                    context().eval("js", PACKAGE_TYPE_RESOLVER).execute(json).asString() != "module"
                } catch (e: Exception) {
                    true
                }
            }
            dir = dir.parent
        }
        return true
    }

    // Whether `content`'s top level actually uses ESM `import`/`export` syntax, regardless
    // of what isCjsFile concluded from the file's extension/package.json — a `.js` file
    // under a package.json lacking `"type": "module"` is *treated* as CommonJS by Node's
    // own rule, but some fixtures/packages in the wild (and in this codebase's own tests,
    // e.g. writeFakePackage) ship genuine ESM source under that configuration anyway.
    // Wrapping such a file in the CJS `(function(exports, require, module, ...) { ... })`
    // wrapper would be a syntax error (`export`/top-level `import` aren't legal inside a
    // function body), so both CjsRuntime.requireCjsModule and InterceptingFileSystem's
    // ESM-import branch of newByteChannel check this first and fall back to evaluating the
    // content as a genuine ES module.
    private val esmSyntaxPattern = Regex("(?m)^\\s*(import\\s|import\\{|export\\s|export\\{|export\\*)")

    fun looksLikeEsm(content: String): Boolean = esmSyntaxPattern.containsMatchIn(content)

    companion object {
        private val PACKAGE_TYPE_RESOLVER = """
            (function (json) {
                try { return JSON.parse(json).type || "commonjs"; }
                catch (e) { return "commonjs"; }
            })
        """.trimIndent()

        // Resolves a package.json `"exports"` (or `"imports"`) conditions value to a plain
        // path string. A conditions value isn't necessarily a flat `{import, require,
        // default}` object with string leaves — packages like axios nest another
        // conditions object *inside* a condition's own value (e.g. `exports["."].default`
        // is itself `{require: "...", default: "..."}`), so this recurses into any
        // condition whose value is an object rather than assuming one level of nesting is
        // the bottom. `conditions`, in priority order, are the only keys ever descended
        // into — unlisted keys (`types`, `browser`, `bun`, `react-native`, ...) are
        // ignored at every level, mirroring how Node only matches conditions it actually
        // supports rather than falling back to an arbitrary platform-specific one.
        private val RESOLVE_CONDITIONS_FN = """
            function resolveConditions(node, conditions) {
                if (typeof node === "string") return node;
                if (Array.isArray(node)) {
                    // Node's array-fallback form for exports/imports targets: try each
                    // element in declaration order and use the first that resolves to
                    // something (a string, or an object/nested-array that itself resolves
                    // via recursion) — e.g. escalade's
                    // `"./sync": [{"import": "./sync/index.mjs"}, "./sync/index.js"]`.
                    for (var j = 0; j < node.length; j++) {
                        var candidate = resolveConditions(node[j], conditions);
                        if (candidate) return candidate;
                    }
                    return null;
                }
                if (!node || typeof node !== "object") return null;
                for (var i = 0; i < conditions.length; i++) {
                    if (Object.prototype.hasOwnProperty.call(node, conditions[i])) {
                        var result = resolveConditions(node[conditions[i]], conditions);
                        if (result) return result;
                    }
                }
                return null;
            }
        """.trimIndent()

        // `preferRequire` picks the condition-priority order: a CJS `require()` resolution
        // (see CjsRuntime.resolveRequireTarget) prefers the `"require"` condition over
        // `"import"`, and vice versa for an ESM `import` (see InterceptingFileSystem.parsePath)
        // — a dual-mode package's `exports` map can declare sibling `"import"`/`"require"`
        // conditions with no enclosing `"default"` (e.g. `ws`'s `{".": {"import":
        // "./wrapper.mjs", "require": "./index.js"}}`), and always preferring `"import"`
        // regardless of which module system is actually asking hands a `require()` caller
        // an ESM-only module built for a different module system than the one requesting it.
        private val ENTRY_POINT_RESOLVER = """
            (function (json, preferRequire) {
                $RESOLVE_CONDITIONS_FN
                var pkg = JSON.parse(json);
                var exp = pkg.exports;
                var conditions = preferRequire ? ["node", "require", "default"] : ["node", "import", "default"];
                if (exp) {
                    var dot = typeof exp === "string" ? exp : (exp["."] !== undefined ? exp["."] : exp);
                    var resolved = resolveConditions(dot, conditions);
                    if (resolved) return resolved;
                }
                return pkg.main || "index.js";
            })
        """.trimIndent()

        // Resolves a `#`-prefixed subpath-imports specifier against a package.json's
        // "imports" field. The mapped value can be a plain string, or a conditions object
        // keyed by environment (e.g. "node", "import", "require", "default") — per Node's
        // own resolution order, "node" is preferred, then "import", then "default".
        // Returns null (not a string) when there's no "imports" field or no matching entry,
        // which the Kotlin caller distinguishes from a real string result via `isString`.
        private val IMPORTS_RESOLVER = """
            (function (json, specifier) {
                $RESOLVE_CONDITIONS_FN
                try {
                    var pkg = JSON.parse(json);
                    var imports = pkg.imports;
                    if (!imports || typeof imports !== "object") return null;
                    var entry = imports[specifier];
                    if (entry === undefined) return null;
                    return resolveConditions(entry, ["node", "import", "require", "default"]);
                } catch (e) {
                    return null;
                }
            })
        """.trimIndent()

        // Resolves a package-relative subpath specifier (the part of `pkg/foo/bar` after
        // the package name — see resolveSubpathExport) against that package's own
        // `package.json` `"exports"` map. Tries an exact `"./<subPath>"` key first, then
        // falls back to a single-`*`-wildcard pattern key (Node requires the wildcard match
        // to be the *highest-specificity* one, but in practice packages don't define
        // overlapping patterns, so first-match-in-declaration-order is sufficient here).
        // Returns null (not a string) when there's no `"exports"` field, `exports` is a bare
        // string (meaning the package has no subpath exports at all, only a root export),
        // or no key/pattern matches — which the Kotlin caller distinguishes from a real
        // string result via `isString`, falling back to a literal on-disk path check.
        // `preferRequire` has the same meaning as on ENTRY_POINT_RESOLVER above — `true` for
        // a CJS `require()` resolution, `false` for an ESM `import`.
        private val SUBPATH_EXPORT_RESOLVER = """
            (function (json, subPath, preferRequire) {
                $RESOLVE_CONDITIONS_FN
                try {
                    var pkg = JSON.parse(json);
                    var exp = pkg.exports;
                    if (!exp || typeof exp !== "object") return null;
                    var conditions = preferRequire ? ["node", "require", "default"] : ["node", "import", "default"];
                    var key = "./" + subPath;
                    if (Object.prototype.hasOwnProperty.call(exp, key)) {
                        return resolveConditions(exp[key], conditions);
                    }
                    var keys = Object.keys(exp);
                    for (var i = 0; i < keys.length; i++) {
                        var k = keys[i];
                        var starIdx = k.indexOf("*");
                        if (starIdx === -1) continue;
                        var prefix = k.slice(0, starIdx);
                        var suffix = k.slice(starIdx + 1);
                        if (key.indexOf(prefix) === 0 && key.slice(key.length - suffix.length) === suffix) {
                            var matched = key.slice(prefix.length, key.length - suffix.length);
                            var target = resolveConditions(exp[k], conditions);
                            if (typeof target === "string") return target.split("*").join(matched);
                        }
                    }
                    return null;
                } catch (e) {
                    return null;
                }
            })
        """.trimIndent()
    }
}

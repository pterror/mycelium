package org.mycelium.library.compiler

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

// Maps a language ID (e.g. "coffeescript", "typescript") to the CompilerSpec describing how
// to compile it to JS. Ships with entries for every compile-to-JS language Mycelium knows
// about out of the box; `register` lets a consumer add support for further languages without
// modifying this file.
//
// A class (constructed by Mycelium, the composition root) rather than a global `object`, so
// a consumer embedding Mycelium as a library can own its own registry instance — e.g. two
// independent Mycelium instances in the same JVM registering different/conflicting
// languageIds without stepping on each other's global state, or a test constructing a
// registry with only the specs it needs.
class CompilerRegistry {
    private val specs = mutableMapOf<String, CompilerSpec>()

    init {
        register(
            CompilerSpec(
                languageId = "typescript",
                // Bundled: compiled via EsbuildBackend (io.mvnpm:esbuild-java), not an
                // npm-resolved "typescript" package.
                npmPackageName = null,
                defaultVersion = "0.24.0",
                compileFunction = "",
                compileOptions = emptyMap(),
                outputIsObject = false,
                // `tsc --version` prints "Version 5.4.5" — only consulted by
                // CompileToJsDispatcher.resolveTscOverride when a project opts into the real
                // `tsc` instead of this BUNDLED EsbuildBackend registration; see its own doc
                // comment for what "opts into" means.
                versionCommand = listOf("--version"),
                versionExtractor = { output -> Regex("""(\d+\.\d+\.\d+)""").find(output)?.groupValues?.get(1) },
            )
        )
        register(
            CompilerSpec(
                languageId = "coffeescript",
                npmPackageName = "coffeescript",
                defaultVersion = "2.7.0",
                compileFunction = "require('coffeescript').compile",
                compileOptions = mapOf("bare" to true),
                outputIsObject = false,
                // Not consulted by the JS_API backend itself (which reads the resolved npm
                // package's own package.json "version" field directly rather than shelling
                // out — see CompilerDownloader.installedPackageVersion) but declared anyway
                // for API completeness/future use: `coffee --version` prints "CoffeeScript
                // version 2.7.0".
                versionCommand = listOf("--version"),
                versionExtractor = { output ->
                    Regex("""CoffeeScript version\s+(\d+\.\d+\.\d+)""").find(output)?.groupValues?.get(1)
                },
            )
        )
        register(
            CompilerSpec(
                languageId = "civet",
                npmPackageName = "@danielx/civet",
                // "0.9.24" (this registration's previous pin) was never actually a
                // published version — confirmed against the real npm registry, whose
                // 0.9.x line stops at 0.9.8; discovered when jsImportsCivet's stale
                // `assumeTrue(npm on PATH)` gate (removed this session, see
                // MyceliumIntegrationTest.kt) was finally lifted and the test could
                // actually reach the registry. "0.11.14" is the current "latest" dist-tag
                // as of this writing.
                defaultVersion = "0.11.14",
                compileFunction = "require('@danielx/civet').compile",
                // `sync: true` is required to get a plain string back synchronously —
                // without it, civet.compile returns a Promise (it supports async plugins/
                // macros in general), which NpmJsApiBackend has no way to await from
                // Kotlin-side host code. `js: true` selects plain-JS output (as opposed to
                // leaving in TypeScript-flavored syntax).
                compileOptions = mapOf("js" to true, "sync" to true),
                outputIsObject = false,
                // `civet --version` prints "@danielx/civet: 0.11.14".
                versionCommand = listOf("--version"),
                versionExtractor = { output -> Regex("""(\d+\.\d+\.\d+)""").find(output)?.groupValues?.get(1) },
            )
        )
        register(
            CompilerSpec(
                languageId = "livescript",
                npmPackageName = "livescript",
                defaultVersion = "1.6.0",
                // `require('livescript').compile(code, options)` returns a plain JS string
                // by default (verified against the published lib/index.js source) — it only
                // returns a `{code, map, ...}` object when `options.map` is set, which we
                // never set here, so `outputIsObject: false` is correct.
                compileFunction = "require('livescript').compile",
                // `bare: true` mirrors CoffeeScript's own entry above: without it, LiveScript
                // (like CoffeeScript) wraps the whole compiled module body in an IIFE, which
                // would hide top-level declarations from the rest of Mycelium's ESM-shim
                // machinery (InterceptingFileSystem's export-detection expects top-level
                // `var`/`function` declarations it can rewrite into `export`s).
                compileOptions = mapOf("bare" to true),
                outputIsObject = false,
                // `lsc --version` prints "LiveScript 1.6.0".
                versionCommand = listOf("--version"),
                versionExtractor = { output ->
                    Regex("""LiveScript\s+(\d+\.\d+\.\d+)""").find(output)?.groupValues?.get(1)
                },
            )
        )
        // Elm, PureScript, and ReScript: none of them expose a JS-callable
        // `compile(source, options)` function the way CoffeeScript/Civet/LiveScript do, so
        // each is routed through SubprocessBackend (BackendType.SUBPROCESS) instead of
        // NpmJsApiBackend:
        //   - `elm` on npm (https://registry.npmjs.org/elm) is just an installer — its
        //     description is literally "Installer for Elm: just downloads the binary into
        //     node_modules" (optionalDependencies per-platform binaries, `bin: {elm: "bin/elm"}`).
        //     There is no `require('elm')`-able JS API; the only interface is the native
        //     `elm` binary, invoked as a subprocess.
        //   - `purescript` on npm (https://registry.npmjs.org/purescript) is likewise a
        //     wrapper that installs a native `purs` binary (`bin: {purs: "purs.bin"}`,
        //     description "PureScript wrapper that makes it available as a local dependency")
        //     — no JS compile function, subprocess-only.
        //   - `rescript` on npm (https://registry.npmjs.org/rescript) ships the OCaml-based
        //     native toolchain (`bsc`/`rescript`/`bstracing` binaries) plus a compiled JS
        //     *runtime* (`./lib/js/*`, `./lib/es6/*` — the stdlib ReScript programs import,
        //     not a compiler entry point). Its `exports` map has no `./compiler` or similar
        //     JS-callable entry; compilation is subprocess-only via the native `bsc` binary,
        //     which lives at a platform-specific path (e.g. `linux/bsc.exe`,
        //     `darwinarm64/bsc.exe`) rather than anywhere `package.json`'s own `bin` field
        //     names — the `.exe` suffix is a historical OCaml-native-toolchain naming
        //     convention carried over onto every platform, not actually Windows-specific.
        //
        // See each registration's own comment below for compile-workflow specifics
        // (elm.json scaffolding for Elm, `output/<Module>/index.js` for PureScript, stdout
        // for ReScript) and their caveats.
        register(
            CompilerSpec(
                languageId = "elm",
                npmPackageName = "elm",
                // Latest npm "elm" dist-tag as of this writing; wraps compiler release 0.19.1.
                defaultVersion = "0.19.1-6",
                backendType = BackendType.SUBPROCESS,
                // `elm --version` prints just the bare version, e.g. "0.19.1" — no prefix/
                // suffix text to strip at all, unlike CoffeeScript/LiveScript's own output.
                versionCommand = listOf("--version"),
                versionExtractor = { output -> Regex("""(\d+\.\d+\.\d+)""").find(output)?.groupValues?.get(1) },
                subprocessBinaryPath = { packageDir -> packageDir.resolve("bin/elm") },
                subprocessOutputMode = SubprocessOutputMode.OUTPUT_FILE,
                // Project-aware path (see ProjectBuildRunner/CompileToJsDispatcher.
                // compileProjectAware): when a real elm.json is found walking up from the
                // imported .elm file, it's used as-is (its own "source-directories" and
                // "dependencies" — no more single-package/elm-core-only limitation) instead
                // of the minimal scaffolded one below. `elm make <sourcePath> --output=...`
                // compiles that specific entry module plus every module it transitively
                // imports (resolved via the project's own source-directories) into one JS
                // bundle exposing a global `Elm.<ModuleName>.init()` — the same "whole
                // dependency graph in one file" shape a hand-written elm.json-less single
                // file could never produce. Different entry modules imported from the same
                // project get their own bundle (elmBundlePathFor hashes the absolute source
                // path), since `elm make` only bundles what a given entry point actually
                // needs rather than "every module in the project" the way spago/rescript do.
                projectFileNames = listOf("elm.json"),
                projectBuildArgs = { _, _, sourcePath, outputDir ->
                    listOf("make", sourcePath.toString(), "--output=${elmBundlePathFor(outputDir, sourcePath)}")
                },
                resolveProjectOutput = { _, outputDir, sourcePath -> elmBundlePathFor(outputDir, sourcePath) },
                // CAVEAT: `elm make` refuses to run without a discoverable `elm.json` in (or
                // above) its working directory — with none present, it interactively prompts
                // "Would you like me to create an elm.json file for you? [Y/n]:" on stdin,
                // which would hang forever against a subprocess with no attached terminal.
                // Worked around by writing a minimal `elm.json` into the (freshly created,
                // per-compile) working directory before every invocation, with
                // "source-directories" pointing at the real source file's own parent
                // directory (so the file is compiled in place — no copying needed) and a
                // single explicit dependency on elm/core (the only package every Elm module
                // implicitly needs; anything beyond that a given source file imports will
                // fail to resolve, since there is no dependency-declaration mechanism for a
                // single loose .elm file the way `elm.json` normally expects one).
                // `elm make` additionally resolves/downloads elm/core (and its own transitive
                // dependencies) from https://package.elm-lang.org into `$ELM_HOME` on first
                // use if not already cached there — unlike Mycelium's own npm-package
                // downloading (CompilerDownloader), this is the *Elm* compiler's own
                // dependency-fetching, entirely outside Mycelium's control, and requires
                // network access + a writable $ELM_HOME the first time any Elm file is
                // compiled on a given machine.
                compileArgs = { sourcePath, outputPath, workDir ->
                    writeMinimalElmJson(workDir, sourcePath.parent)
                    listOf("make", sourcePath.toString(), "--output=${outputPath}")
                },
            )
        )
        register(
            CompilerSpec(
                languageId = "purescript",
                npmPackageName = "purescript",
                // Latest npm "purescript" dist-tag as of this writing; wraps compiler
                // release 0.15.15 (post-0.15, `purs` emits ES modules rather than CommonJS —
                // GraalJS evaluates the result as an ES module the same way it does
                // TypeScript/CoffeeScript output, via JsSupport, so this is compatible).
                defaultVersion = "0.15.15",
                backendType = BackendType.SUBPROCESS,
                // `purs --version` prints just the bare version, e.g. "0.15.15".
                versionCommand = listOf("--version"),
                versionExtractor = { output -> Regex("""(\d+\.\d+\.\d+)""").find(output)?.groupValues?.get(1) },
                subprocessBinaryPath = { packageDir -> packageDir.resolve("purs.bin") },
                subprocessOutputMode = SubprocessOutputMode.OUTPUT_FILE,
                // Project-aware path (see ProjectBuildRunner/CompileToJsDispatcher.
                // compileProjectAware): when a real spago.yaml (current, "Spago Next"
                // config format) or spago.dhall (legacy Dhall-based config) is found walking
                // up from the imported .purs file, the whole project is built with
                // `spago build` instead of compiling just the one file with `purs compile`
                // — spago resolves the project's package set/dependencies and compiles
                // every module's full cross-module import graph, which single-file `purs
                // compile` fundamentally cannot (see the CAVEAT below). `spago build`
                // writes each compiled module to `output/<ModuleName>/index.js` under the
                // project root by default (the same layout `purs compile --output` uses,
                // just always rooted at the project rather than a scratch workDir), so
                // resolveProjectOutput reuses the exact same module-name-from-source
                // extraction the single-file fallback's `locateOutput` already does.
                //
                // Unlike Elm/ReScript, the project build here is driven by a *different*
                // binary than single-file compilation: spago (the package manager/build-tool
                // CLI) is a separate project from `purs` (the compiler itself) and is not
                // provided by the `purescript` npm package at all (that package only ships
                // `purs`/`purs.bin`). Unlike the old bare-"spago"-on-PATH-only approach, spago
                // is itself published on npm (https://registry.npmjs.org/spago) as a
                // self-contained ES module bundle (`bin/bundle.js`, no native/platform-specific
                // binaries despite wrapping a Haskell-toolchain-era CLI — the current
                // TypeScript rewrite ships as plain JS with a `#!/usr/bin/env -S node
                // --no-warnings=...` shebang), so CompileToJsDispatcher.resolveProjectBuildBinary
                // can resolve/download it through the exact same node_modules -> PATH -> cache
                // -> auto-download chain SubprocessBackend already gets for every other
                // language — see `projectBuildNpmPackage`'s own doc comment on CompilerSpec.
                // A locally- or globally-installed spago (node_modules or PATH) still wins over
                // Mycelium's own auto-downloaded copy, matching every other language's
                // "user's own install takes precedence" precedent.
                projectFileNames = listOf("spago.yaml", "spago.dhall"),
                projectBuildNpmPackage = "spago",
                // Latest npm "spago" dist-tag as of this writing (the "Spago Next"/PureScript
                // registry-era rewrite, entirely unrelated in version numbering to `purs`
                // 0.15.15 above — see projectBuildToolVersion's own doc comment).
                projectBuildToolVersion = "1.0.4",
                projectBuildPathBinaryName = "spago",
                projectBuildBinaryPath = { packageDir -> packageDir.resolve("bin/bundle.js") },
                projectBuildArgs = { _, _, _, _ -> listOf("build") },
                resolveProjectOutput = { projectRoot, _, sourcePath ->
                    val moduleName = extractPureScriptModuleName(sourcePath) ?: "Main"
                    projectRoot.resolve("output").resolve(moduleName).resolve("index.js")
                },
                // CAVEAT: `purs bundle` (which used to produce a single-file JS bundle) was
                // removed in 0.15 — the documented modern workflow is "compile with `purs
                // compile`, then hand the resulting ES modules under `output/` to a real
                // bundler (esbuild/webpack/Parcel)". Bundling a second time here would be
                // redundant with (and architecturally opposed to) EsbuildBackend's own
                // transform-only, import-preserving approach for TypeScript, so this instead
                // just runs `purs compile <file> --output <workDir>/output` and reads back
                // the single JS file `purs` wrote for the compiled module directly
                // (`output/<ModuleName>/index.js`, located via `locateOutput` below) — the
                // module's own `import`/`export` statements toward *other* PureScript modules
                // are left completely unresolved, since there are none: single-file
                // compilation only works at all for source that imports nothing beyond
                // implicitly-available `Prim`. Any real cross-module PureScript program needs
                // its full module graph compiled together (a Spago/pulp project), which is
                // out of scope for Mycelium's per-file compile-to-JS model.
                compileArgs = { sourcePath, _, workDir ->
                    listOf("compile", sourcePath.toString(), "--output", workDir.resolve("output").toString())
                },
                locateOutput = { workDir, sourcePath, _ ->
                    val moduleName = extractPureScriptModuleName(sourcePath) ?: "Main"
                    workDir.resolve("output").resolve(moduleName).resolve("index.js")
                },
            )
        )
        register(
            CompilerSpec(
                languageId = "rescript",
                npmPackageName = "rescript",
                // Latest npm "rescript" dist-tag as of this writing.
                defaultVersion = "11.1.4",
                backendType = BackendType.SUBPROCESS,
                // `bsc.exe -version` prints e.g. "11.1.4" (the ReScript/BuckleScript OCaml
                // toolchain's own version flag spelling, `-version` rather than `--version`).
                versionCommand = listOf("-version"),
                versionExtractor = { output -> Regex("""(\d+\.\d+\.\d+)""").find(output)?.groupValues?.get(1) },
                subprocessBinaryPath = { packageDir -> packageDir.resolve(rescriptPlatformDir()).resolve("bsc.exe") },
                // Of the three, ReScript's `bsc` is the simplest to drive: as a standalone
                // ("barebone") single-file compiler (as opposed to `rescript`/`bsb`, the
                // build-system entry point that expects a whole `bsconfig.json`-described
                // project), `bsc <file>.res` compiles exactly that one file and writes the
                // resulting JS straight to stdout with no project/config file needed at all
                // — no elm.json- or output-directory-locating workaround required.
                subprocessOutputMode = SubprocessOutputMode.STDOUT,
                compileArgs = { sourcePath, _, _ -> listOf(sourcePath.toString()) },
                // Project-aware path (see ProjectBuildRunner/CompileToJsDispatcher.
                // compileProjectAware): when a real rescript.json (current config file name)
                // or bsconfig.json (legacy name, still supported) is found walking up from
                // the imported .res file, the whole project is built via the `rescript`
                // build-system binary instead of compiling just the one file with the
                // barebone `bsc` compiler — `bsc <file>.res` alone has no notion of the
                // project's own module namespace/dependencies (see the "simplest to drive"
                // comment on the single-file `compileArgs` above: it works precisely because
                // it *ignores* project structure) and can't resolve cross-module imports the
                // way `bsc`-invoked-by-`rescript`, with the full project's build graph, can.
                // The `rescript` npm package conveniently ships this build-system binary
                // (`rescript.exe`) in the very same platform-specific directory as `bsc.exe`
                // (confirmed against the package's own layout: `<platform>/{bsc,rescript,
                // bstracing}.exe`), so — unlike PureScript's spago — no separate
                // PATH-searched tool or additional download is needed here at all.
                // `rescript build` compiles every module in the project and writes each
                // one's output to a `.bs.js` file adjacent to its own `.res` source (the
                // default `suffix` — a project configuring a different one, e.g. `.res.mjs`,
                // via rescript.json's "suffix"/"package-specs" field is a known limitation of
                // this hardcoded default; ReScript's rescript.json can also redirect output
                // to a separate directory via "sources"/monorepo-style configuration, which
                // resolveProjectOutput's simple same-directory adjacency assumption doesn't
                // account for either).
                projectFileNames = listOf("rescript.json", "bsconfig.json"),
                projectBuildBinaryPath = { packageDir ->
                    packageDir.resolve(rescriptPlatformDir()).resolve("rescript.exe")
                },
                projectBuildArgs = { _, _, _, _ -> listOf("build") },
                resolveProjectOutput = { _, _, sourcePath ->
                    val base = sourcePath.toString().removeSuffix(".res").removeSuffix(".resi")
                    Path.of("$base.bs.js")
                },
            )
        )
        register(
            CompilerSpec(
                languageId = "imba",
                npmPackageName = "imba",
                // Latest npm dist-tag as of this writing — Imba's 2.x line has never shipped
                // a stable release, only alpha prereleases (confirmed against the registry:
                // dist-tags.latest itself is "2.0.0-alpha.252"), so unlike every other
                // JS_API-backed language here, `defaultVersion` is inherently as unstable as
                // Imba's own release process is.
                //
                // CAVEAT: Imba 2.x is alpha-quality software — no stable release exists to
                // pin to instead, breaking changes between alpha builds are possible, and
                // this registration should be treated as experimental accordingly.
                defaultVersion = "2.0.0-alpha.252",
                // `require('imba/compiler').compile(code, options)` (confirmed against the
                // published tarball's src/compiler/index.mjs / compiler.mjs) — a package
                // *subpath* require, not `require('imba')` itself, resolved through imba's
                // own package.json "exports" map (`"./compiler": {"require":
                // "./dist/compiler.cjs", "default": "./dist/compiler.mjs", ...}`) by
                // NodeModuleResolver.resolveSubpathExport; `__mycelium_require__`'s
                // preferRequire=true path picks the "require" condition, landing on the CJS
                // build rather than the ESM one.
                compileFunction = "require('imba/compiler').compile",
                // `compile()` returns a whole Compilation instance (confirmed against
                // src/compiler/compilation.mjs: `this.js = ""`, populated during `compile()`)
                // rather than a plain string or a `{code}`-shaped object — so unlike
                // CoffeeScript/Civet/LiveScript's `outputIsObject`, Imba needs `outputProperty`
                // to name its own differently-named output field instead.
                outputProperty = "js",
            )
        )
        register(
            CompilerSpec(
                languageId = "gleam",
                // Gleam's actual compiler is a native Rust binary distributed only via
                // GitHub Releases (https://github.com/gleam-lang/gleam/releases) — the
                // `gleam` package that does exist on the npm registry is an unrelated
                // isomorphic-entities JS library, confirmed by inspecting its registry
                // metadata, so there's no npmPackageName here at all.
                npmPackageName = null,
                // Pinned to a real, published release tag — verified against
                // https://github.com/gleam-lang/gleam/releases/tag/v1.5.1's own asset list
                // (gleam-v1.5.1-{x86_64,aarch64}-{unknown-linux-musl,apple-darwin}.tar.gz,
                // gleam-v1.5.1-x86_64-pc-windows-msvc.zip).
                defaultVersion = "1.5.1",
                backendType = BackendType.SUBPROCESS,
                // Gleam has no notion of compiling a single loose file at all — every real
                // compile needs a gleam.toml (package name, dependencies, target config all
                // live there; there's no `gleam compile-one-file.gleam` equivalent to Elm's
                // scaffolded-elm.json workaround or ReScript's standalone `bsc`). So this
                // registration is project-aware-only: `projectFileNames` below is the only
                // path this language ever compiles through. Importing a .gleam file with no
                // gleam.toml anywhere above it hits CompileToJsDispatcher.compile's "not
                // BUNDLED but has no npmPackageName" branch (since projectRoot is null and
                // npmPackageName is null here) and throws a MyceliumException accordingly —
                // an accurate diagnostic for "there's no single-file compile mode", even
                // though its wording was written for the npm case.
                projectFileNames = listOf("gleam.toml"),
                projectBuildPathBinaryName = "gleam",
                // GitHub-Releases-sourced, not npm-sourced — see ToolResolver.kt's
                // GitHubRelease and CompilerSpec.projectBuildToolSources' own doc comments for
                // why this needs its own ToolSource list rather than
                // projectBuildNpmPackage/projectBuildBinaryPath's npm-node_modules-shaped
                // resolution. Same "user's own install wins" precedence as every other
                // language: project-local -> system PATH -> Mycelium's own GitHub-release
                // download cache.
                projectBuildToolSources = { sourcePath ->
                    buildList {
                        sourcePath.parent?.let { add(ProjectLocalBin(it)) }
                        add(SystemPath())
                        add(
                            GitHubRelease(
                                owner = "gleam-lang",
                                repo = "gleam",
                                tag = "v1.5.1",
                                assetName = gleamReleaseAssetName("1.5.1"),
                            )
                        )
                    }
                },
                projectBuildArgs = { _, _, _, _ -> listOf("build", "--target", "javascript") },
                // `gleam build --target javascript` compiles every module in the project and
                // writes each one's output to build/dev/javascript/<package_name>/<module
                // path relative to src/>.mjs (module path preserving its own subdirectory
                // structure — e.g. src/foo/bar.gleam -> .../foo/bar.mjs), <package_name>
                // being gleam.toml's own "name" key, not the source file's own name.
                resolveProjectOutput = { projectRoot, _, sourcePath ->
                    val packageName = extractGleamPackageName(projectRoot)
                    val srcDir = projectRoot.resolve("src").toAbsolutePath().normalize()
                    val modulePath = srcDir.relativize(sourcePath.toAbsolutePath().normalize())
                        .toString()
                        .removeSuffix(".gleam")
                    projectRoot.resolve("build/dev/javascript").resolve(packageName).resolve("$modulePath.mjs")
                },
            )
        )
        register(
            CompilerSpec(
                languageId = "dart",
                // No npm package at all — the Dart SDK is Google's own multi-gigabyte
                // per-platform archive (storage.googleapis.com), not published to any package
                // registry Mycelium otherwise talks to. Resolved via ToolResolver/ToolSource
                // instead (see toolBinaryName/toolSources below and ToolResolver.kt's
                // DartSdkDownload), the same "third shape" CompilerSpec.projectBuildToolSources
                // introduced for Gleam's GitHub-Releases-only `gleam` binary — just for
                // single-file compilation rather than a project build, since `dart compile
                // js`/`dart2js` (unlike Gleam) can compile one loose file directly with no
                // pubspec.yaml required at all.
                npmPackageName = null,
                // Latest Dart stable release as of this writing
                // (https://storage.googleapis.com/dart-archive/channels/stable/release/3.5.4/
                // sdk/ — a real, published SDK build).
                defaultVersion = "3.5.4",
                backendType = BackendType.SUBPROCESS,
                versionCommand = listOf("--version"),
                // `dart2js --version` (and `dart --version`) print e.g. "Dart SDK version:
                // 3.5.4 (stable) (...)" — to *stderr*, not stdout, which is why VersionCheck
                // captures both streams combined rather than stdout alone.
                versionExtractor = { output ->
                    Regex("""Dart SDK version:\s*(\d+\.\d+\.\d+)""").find(output)?.groupValues?.get(1)
                        ?: Regex("""(\d+\.\d+\.\d+)""").find(output)?.groupValues?.get(1)
                },
                toolBinaryName = "dart2js",
                toolSources = { sourcePath ->
                    val constraint = findPackageJsonConstraint(sourcePath.parent, "dart")
                    val versionCheck = VersionCheck(
                        listOf("--version"),
                        { output ->
                            Regex("""Dart SDK version:\s*(\d+\.\d+\.\d+)""").find(output)?.groupValues?.get(1)
                                ?: Regex("""(\d+\.\d+\.\d+)""").find(output)?.groupValues?.get(1)
                        },
                        constraint?.versionSpec ?: "*",
                    )
                    // An exact version to download when neither PATH nor a project-local
                    // install satisfies the constraint (SemverResolver has no npm registry to
                    // resolve a range against here — the Dart SDK isn't an npm package — so an
                    // exact-version constraint is used as-is and anything else falls back to
                    // this registration's own defaultVersion).
                    val downloadVersion =
                        constraint?.versionSpec?.let { org.mycelium.library.registry.SemverResolver.parseVersion(it)?.raw }
                            ?: "3.5.4"
                    buildList {
                        sourcePath.parent?.let { add(ProjectLocalBin(it, versionCheck)) }
                        add(SystemPath(versionCheck))
                        add(DartSdkDownload(dartSdkCacheDir(downloadVersion), downloadVersion))
                    }
                },
                subprocessOutputMode = SubprocessOutputMode.OUTPUT_FILE,
                // `dart2js <source> -o <output>` compiles exactly one entry file (plus
                // whatever it imports via relative `package:`-less imports) straight to JS —
                // dart2js has always supported single-file compilation this way, unlike
                // `dart compile js` (the newer, `pub`-aware wrapper subcommand under the main
                // `dart` binary) which expects a full pub package (pubspec.yaml) around the
                // entry point. Using dart2js directly avoids needing a scaffolded pubspec.yaml
                // the way Elm's CompilerSpec scaffolds a minimal elm.json.
                compileArgs = { sourcePath, outputPath, _ ->
                    listOf(sourcePath.toString(), "-o", outputPath.toString())
                },
                // CAVEAT: like PureScript's single-file `purs compile` fallback, this only
                // works for Dart source that imports nothing beyond Dart's own `dart:core`-ish
                // implicitly-available libraries — a real multi-file/pub-package Dart program
                // needs a pubspec.yaml-rooted `dart compile js` project build, which is out of
                // scope for Mycelium's per-file compile-to-JS model (see PureScript's own
                // CAVEAT above for the same limitation stated at more length).
            )
        )
        register(
            CompilerSpec(
                languageId = "kotlinjs",
                // Kotlin/JS is the one interesting case where Mycelium's own host language is
                // also the guest language being compiled: `mycelium` itself is a Kotlin/JVM
                // program on GraalVM, and the Kotlin compiler is already on its own classpath
                // (kotlin-stdlib, transitively pulled in by the Kotlin Gradle plugin this
                // project already builds with). Investigated calling kotlinc-js's actual
                // compiler API (K2JSCompiler) as a direct in-process JVM library call — no
                // subprocess at all, the same way NpmJsApiBackend calls into the shared GraalJS
                // Context in-process rather than shelling out — but that needs the full
                // Kotlin/JS *cross-compilation* classpath (kotlin-stdlib-js.klib, the JS IR
                // backend, kotlinx-serialization-adjacent compiler plugins some real projects
                // require, ...) as extra Gradle dependencies, none of which this module
                // currently declares, and pinning a compiler-API-compatible Kotlin compiler
                // version independently of whatever Kotlin version Gradle itself builds
                // Mycelium with risks exactly the kind of classpath/ABI mismatch this project's
                // own build.gradle.kts already flags for GraalVM's Truffle jars (see its own
                // comment on graalVersion). So: falls back to the subprocess `kotlinc-js`
                // binary instead, published on npm as `kotlin-compiler`
                // (https://www.npmjs.com/package/kotlin-compiler — a JetBrains-adjacent
                // wrapper that bundles a `kotlinc-js` distribution, `bin: {kotlinc-js: ...}`),
                // resolved through the exact same npm node_modules/cache/download chain every
                // other npm-backed SUBPROCESS language (Elm/PureScript/ReScript) already gets —
                // no bespoke ToolSource needed here at all.
                npmPackageName = "kotlin-compiler",
                defaultVersion = "1.9.24",
                backendType = BackendType.SUBPROCESS,
                versionCommand = listOf("-version"),
                // `kotlinc-js -version` prints e.g. "info: kotlinc-js 1.9.24 (JRE ...)" to
                // stderr.
                versionExtractor = { output ->
                    Regex("""kotlinc-js\s+(\d+\.\d+\.\d+)""").find(output)?.groupValues?.get(1)
                },
                subprocessBinaryPath = { packageDir -> packageDir.resolve("bin/kotlinc-js") },
                subprocessOutputMode = SubprocessOutputMode.OUTPUT_FILE,
                // `kotlinc-js -output <outputPath> <sourcePath>` compiles one entry file (plus
                // anything it imports from the same source set) to a single JS file exposing
                // top-level declarations on a Kotlin/JS-generated module object — mirrors
                // Elm/PureScript's OUTPUT_FILE shape.
                compileArgs = { sourcePath, outputPath, _ ->
                    listOf("-output", outputPath.toString(), sourcePath.toString())
                },
                // CAVEAT: single-file compilation only works for Kotlin source needing nothing
                // beyond kotlin-stdlib-js (implicitly available) — a real Kotlin/JS project's
                // multi-file/Gradle-module structure (build.gradle.kts-declared source sets and
                // dependencies) is out of scope for Mycelium's per-file compile-to-JS model,
                // the same limitation PureScript/Dart's single-file fallbacks document above.
            )
        )
        register(
            CompilerSpec(
                languageId = "scalajs",
                // scala-cli (https://scala-cli.virtuslab.org) is the modern, self-contained
                // Scala CLI that resolves/drives the actual Scala.js compiler + linker
                // internally (fetching them from Maven/Coursier on first use) — distributed as
                // native platform binaries via GitHub Releases and Coursier, not npm; no
                // self-contained npm package bundles a working Scala.js toolchain the way
                // Elm/PureScript/ReScript's npm wrappers do, so there's no npmPackageName here.
                // Auto-download (a GitHub-Releases ToolSource, the way Gleam's `gleam` gets
                // one) is left as a follow-up: only ProjectLocalBin/SystemPath are wired up for
                // now, so this registration only helps a project/environment (e.g. this
                // codebase's own NixOS flake, or a Nix devShell) that already provides
                // scala-cli on PATH or in node_modules/.bin — a real gap versus Dart/Kotlin-JS
                // above, called out explicitly rather than silently.
                npmPackageName = null,
                defaultVersion = "1.5.0",
                backendType = BackendType.SUBPROCESS,
                versionCommand = listOf("version", "--offline"),
                // `scala-cli version --offline` prints e.g. "Scala CLI version: 1.5.0" (the
                // `--offline` flag skips scala-cli's own "check for updates" network call,
                // which would otherwise slow down/flake every single resolution).
                versionExtractor = { output ->
                    Regex("""version:?\s*(\d+\.\d+\.\d+)""", RegexOption.IGNORE_CASE)
                        .find(output)?.groupValues?.get(1)
                },
                toolBinaryName = "scala-cli",
                toolSources = { sourcePath ->
                    val constraint = findPackageJsonConstraint(sourcePath.parent, "scala-cli")
                    val versionCheck = constraint?.let {
                        VersionCheck(
                            listOf("version", "--offline"),
                            { output ->
                                Regex("""version:?\s*(\d+\.\d+\.\d+)""", RegexOption.IGNORE_CASE)
                                    .find(output)?.groupValues?.get(1)
                            },
                            it.versionSpec,
                        )
                    }
                    buildList {
                        sourcePath.parent?.let { add(ProjectLocalBin(it, versionCheck)) }
                        add(SystemPath(versionCheck))
                    }
                },
                subprocessOutputMode = SubprocessOutputMode.OUTPUT_FILE,
                // `scala-cli package --js <source> -o <output> --force` compiles one entry
                // file (scala-cli resolves whatever `//> using` directives/Scala.js version the
                // source declares, downloading the Scala.js linker/library on first use — this
                // is exactly the "scala-cli handles all the complexity" property that makes it
                // the only realistic Scala.js entry point for Mycelium at all) to a single JS
                // bundle. `--force` overwrites `outputPath` if scala-cli's own caching left a
                // stale file at the same scratch path from an earlier compile.
                compileArgs = { sourcePath, outputPath, _ ->
                    listOf("package", "--js", sourcePath.toString(), "-o", outputPath.toString(), "--force")
                },
                // CAVEAT: single-file only, same limitation as Dart/Kotlin-JS/PureScript above
                // — a real multi-file Scala.js project (its own project.scala/build.sbt,
                // cross-module dependencies) is out of scope for Mycelium's per-file
                // compile-to-JS model.
            )
        )
        // Fable was also investigated and NOT added — it's the closest candidate among the
        // languages considered but still doesn't fit the existing JS_API/SUBPROCESS pattern
        // without pulling in a dependency Mycelium otherwise avoids entirely:
        //   - Fable: closest candidate. `@fable-org/fable-compiler-js` (verified against the
        //     npm registry: `"bin": {"fable": "index.js"}`, `"type": "module"`) needs no
        //     .NET SDK. But its `bin` is a plain ESM JS file that only runs via its own
        //     `#!/usr/bin/env node` shebang — i.e. it requires a real `node` binary on PATH
        //     to execute at all, since SubprocessBackend execs `binaryPath` directly (no
        //     `node` prefix, unlike a hypothetical NodeJsApiBackend). That's a dependency
        //     Mycelium's other compile-to-JS languages (CoffeeScript/Civet/LiveScript via
        //     NpmJsApiBackend's in-Context `require()`; Elm/PureScript/ReScript's native
        //     binaries) never need — adding it would be the first compile-to-JS language to
        //     require an external Node.js installation, undermining the "no external tools
        //     beyond GraalVM itself" design this whole registry otherwise upholds.
    }

    fun resolve(languageId: String): CompilerSpec? = specs[languageId]

    fun register(spec: CompilerSpec) {
        specs[spec.languageId] = spec
    }

    // All registered language IDs (built-ins plus anything a consumer has `register`ed) —
    // used by e.g. `mycelium compiler list` to enumerate what's available without needing to
    // know the specific set of built-in languages ahead of time.
    fun languageIds(): Set<String> = specs.keys

    // Writes a minimal `elm.json` into `workDir` so `elm make` (invoked with `workDir` as its
    // working directory — see SubprocessBackend) finds a project file instead of
    // interactively prompting to create one. `sourceDir` (the real source file's own parent
    // directory) is used as the sole "source-directories" entry so the file is compiled
    // in place, with no copying — see the "elm" CompilerSpec registration's own CAVEAT
    // comment above for why this only supports source needing nothing beyond elm/core.
    private fun writeMinimalElmJson(workDir: Path, sourceDir: Path) {
        val json = """
            {
                "type": "application",
                "source-directories": [
                    ${jsonStringLiteral(sourceDir.toString())}
                ],
                "elm-version": "0.19.1",
                "dependencies": {
                    "direct": {
                        "elm/core": "1.0.5"
                    },
                    "indirect": {}
                },
                "test-dependencies": {
                    "direct": {},
                    "indirect": {}
                }
            }
        """.trimIndent()
        Files.writeString(workDir.resolve("elm.json"), json)
    }

    private fun jsonStringLiteral(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // Where the project-aware Elm build (see the "elm" CompilerSpec registration's
    // projectBuildArgs/resolveProjectOutput above) writes/reads the single bundled JS file
    // for a given entry module — keyed by a hash of the entry source file's own absolute
    // path (rather than e.g. its bare module name) since two different projects, or two
    // different entry modules within one project, must never collide on the same bundle
    // file under the shared `.mycelium-build/elm` scratch directory ProjectBuildRunner
    // creates per project.
    private fun elmBundlePathFor(outputDir: Path, sourcePath: Path): Path {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sourcePath.toAbsolutePath().normalize().toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return outputDir.resolve("$digest.js")
    }

    // Best-effort extraction of a PureScript source file's own module name (e.g. "Main" from
    // "module Main where" or "module Data.Foo.Bar (baz) where") — used by the "purescript"
    // CompilerSpec's `locateOutput` to find the one JS file `purs compile` wrote for this
    // module under `output/<ModuleName>/index.js`. Falls back to null (the caller then
    // defaults to "Main") if no `module ... where` header is found at all, e.g. malformed
    // source — `purs compile` itself will already have failed with a clearer diagnostic in
    // that case, since PureScript requires every file to declare a module.
    private val moduleNameRegex = Regex("""(?m)^\s*module\s+([A-Za-z_][A-Za-z0-9_.]*)""")
    private fun extractPureScriptModuleName(sourcePath: Path): String? =
        moduleNameRegex.find(Files.readString(sourcePath))?.groupValues?.get(1)

    // Best-effort extraction of a Gleam project's own package name (gleam.toml's top-level
    // `name = "..."` key) — used by the "gleam" CompilerSpec's `resolveProjectOutput` to find
    // where `gleam build --target javascript` writes a given module's compiled output
    // (build/dev/javascript/<package_name>/...), which is keyed by the *project's* declared
    // name, not any particular source file's own name. Falls back to the project root
    // directory's own name if gleam.toml has no (or a malformed) "name" key — `gleam build`
    // itself will already have failed with a clearer diagnostic in that case, since every
    // gleam.toml is required to declare one.
    private val gleamPackageNameRegex = Regex("""(?m)^\s*name\s*=\s*"([^"]+)"""")
    private fun extractGleamPackageName(projectRoot: Path): String =
        gleamPackageNameRegex.find(Files.readString(projectRoot.resolve("gleam.toml")))?.groupValues?.get(1)
            ?: projectRoot.fileName.toString()

    // Maps this JVM's OS/architecture (plus the caller-supplied Gleam version) to the exact
    // GitHub release asset name Gleam's own release workflow publishes — verified against
    // https://github.com/gleam-lang/gleam/releases/tag/v1.5.1's actual asset list. Windows
    // ships a .zip; every other platform ships a .tar.gz (both handled by
    // ToolResolver.kt's GitHubRelease).
    private fun gleamReleaseAssetName(version: String): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val isArm = arch.contains("aarch64") || arch.contains("arm")
        val target = when {
            os.contains("win") -> "x86_64-pc-windows-msvc"
            os.contains("mac") || os.contains("darwin") -> if (isArm) "aarch64-apple-darwin" else "x86_64-apple-darwin"
            else -> if (isArm) "aarch64-unknown-linux-musl" else "x86_64-unknown-linux-musl"
        }
        val extension = if (os.contains("win")) "zip" else "tar.gz"
        return "gleam-v$version-$target.$extension"
    }

    // Maps this JVM's OS/architecture to the platform-specific subdirectory name the
    // `rescript` npm package ships its native `bsc.exe`/`rescript.exe`/`bstracing.exe`
    // binaries under (mirrors the package's own `install.js`, which does this same
    // switch to decide which optionalDependency sub-package to require at runtime).
    private fun rescriptPlatformDir(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val isArm = arch.contains("aarch64") || arch.contains("arm")
        return when {
            os.contains("win") -> "win32"
            os.contains("mac") || os.contains("darwin") -> if (isArm) "darwinarm64" else "darwin"
            else -> if (isArm) "linuxarm64" else "linux"
        }
    }

    // Where a given version of the Dart SDK gets downloaded/extracted to (DartSdkDownload's
    // own cacheDir) — mirrors CompilerDownloader.cacheDirFor's own
    // "$XDG_CACHE_HOME/mycelium/..." convention, just under a "compilers/dart-sdk/<version>"
    // subpath rather than CompilerDownloader's npm-shaped "compilers/<languageId>/<version>",
    // since CompilerRegistry has no CompilerDownloader instance of its own to delegate to (a
    // registry only describes languages declaratively; it doesn't own any download
    // machinery).
    private fun dartSdkCacheDir(version: String): Path {
        val base = System.getenv("XDG_CACHE_HOME") ?: java.io.File(System.getProperty("user.home"), ".cache").path
        return java.io.File(base, "mycelium/compilers/dart-sdk/$version").toPath()
    }
}

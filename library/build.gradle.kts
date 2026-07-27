plugins { id("buildlogic.kotlin-library-conventions") }

// NOTE: 25.1.3 was requested, but org.graalvm.polyglot:ruby / org.graalvm.ruby:ruby
// (and the old org.graalvm.polyglot:llvm coordinate) have not been published to
// Maven Central past 25.0.0/25.0.1 — TruffleRuby's Maven distribution appears to
// be stalled/deprecated upstream. Mixing truffle-api 25.1.3 with truffleruby
// 25.0.0 was tried and fails hard at runtime (ClassCastException in Truffle's
// DSL-generated inline-cache code — the two are ABI-incompatible), so every
// GraalVM artifact here must stay pinned to the same version. 25.0.0 is
// therefore the highest version where all of truffle-api/polyglot/js/python/
// java/ruby/wasm/llvm/llvm-native are simultaneously available. See TODO.md
// for the follow-up when upstream publishes matching 25.1.x Ruby/LLVM jars.
val graalVersion = "25.0.0"

// --- JVM-mode language packs -------------------------------------------------------------
//
// Every Truffle language — including GraalJS itself — is a separate, opt-in language pack.
// The core runtime carries only the language-agnostic polyglot/truffle framework
// (`org.graalvm.polyglot:polyglot`, `org.graalvm.truffle:truffle-api`); nothing in this
// module's own Kotlin source references any language-specific artifact's Java classes
// directly (Languages.kt only ever passes GraalVM *string* language IDs to
// `Context#eval`/`Source#newBuilder`, resolved dynamically by the polyglot engine at
// runtime). Each language's Maven artifacts are declared `compileOnly` (satisfies the
// Kotlin compiler, contributes nothing to the runtime/application classpath) plus one
// dedicated, non-transitive Configuration per language pack below, so a consumer can opt
// into exactly the language jars it wants:
//   - `:app:jvmRun` (JVM mode, see app/build.gradle.kts) defaults to JS and adds packs
//     requested via `-Planguages=js,ruby,python,...`.
// `testImplementation` below still pulls in every pack unconditionally so the existing test
// suite (which exercises JS/Ruby/Python/LLVM/WASM directly) keeps working unchanged.
val jsLang: Configuration by configurations.creating { isCanBeConsumed = false }
val rubyLang: Configuration by configurations.creating { isCanBeConsumed = false }
val pythonLang: Configuration by configurations.creating { isCanBeConsumed = false }
val javaLang: Configuration by configurations.creating { isCanBeConsumed = false }
val llvmLang: Configuration by configurations.creating { isCanBeConsumed = false }
val wasmLang: Configuration by configurations.creating { isCanBeConsumed = false }
val luaLang: Configuration by configurations.creating { isCanBeConsumed = false }
val insightTool: Configuration by configurations.creating { isCanBeConsumed = false }

dependencies {
  api("org.graalvm.truffle:truffle-api:$graalVersion")
  api("org.graalvm.polyglot:polyglot:$graalVersion")

  compileOnly("org.graalvm.polyglot:js:$graalVersion")
  compileOnly("org.graalvm.polyglot:python:$graalVersion")
  compileOnly("org.graalvm.polyglot:java:$graalVersion")
  compileOnly("org.graalvm.polyglot:ruby:$graalVersion")
  compileOnly("org.graalvm.polyglot:wasm:$graalVersion")
  compileOnly("org.graalvm.polyglot:llvm:$graalVersion")
  compileOnly("org.graalvm.polyglot:llvm-native:$graalVersion")
  compileOnly("org.graalvm.ruby:ruby:$graalVersion")
  compileOnly("org.graalvm.tools:insight-tool:$graalVersion")

  jsLang("org.graalvm.polyglot:js:$graalVersion")
  pythonLang("org.graalvm.polyglot:python:$graalVersion")
  javaLang("org.graalvm.polyglot:java:$graalVersion")
  rubyLang("org.graalvm.polyglot:ruby:$graalVersion")
  rubyLang("org.graalvm.ruby:ruby:$graalVersion")
  llvmLang("org.graalvm.polyglot:llvm:$graalVersion")
  llvmLang("org.graalvm.polyglot:llvm-native:$graalVersion")
  wasmLang("org.graalvm.polyglot:wasm:$graalVersion")
  // Lua is Mycelium's own from-scratch Truffle language implementation (not a GraalVM-shipped
  // artifact like the packs above), so it's pulled in as a project dependency instead of a
  // Maven coordinate.
  luaLang(project(":lua"))
  insightTool("org.graalvm.tools:insight-tool:$graalVersion")

  implementation("io.mvnpm:esbuild-java:1.5.1")

  // Pure-Kotlin npm registry client + package cache (CompilerDownloader):
  // org.json for registry response parsing, commons-compress for tar/gzip streaming
  // extraction. Both are already transitively pulled in by the GraalVM artifacts above
  // (org.graalvm.shadowed:json, commons-compress via native-image tooling) but are
  // declared explicitly here since relying on an undeclared transitive dependency is
  // fragile — it could disappear silently if those artifacts' own dependencies change.
  implementation("org.json:json:20240303")
  implementation("org.apache.commons:commons-compress:1.26.2")

  // Property-based/fuzz testing for the Bun-compat shim surface (BunFuzzTest.kt). jqwik is
  // JUnit5-native (its own JUnit Platform TestEngine runs alongside Jupiter's under the
  // same `useJUnitPlatform()` task — see buildlogic.kotlin-common-conventions), so no
  // separate test task/build wiring is needed beyond this dependency. Scoped to
  // `library`'s test dependencies only, per the task — no other module needs it.
  testImplementation("net.jqwik:jqwik:1.9.1")
}

// Tests exercise Ruby/Python/LLVM/WASM directly against a real Context, so the test
// classpath keeps every language pack unconditionally, same as before this split
// (Configuration instances can no longer be passed directly to `dependencies { }` as of
// Gradle 8 — `extendsFrom` is the supported way to fold one Configuration's dependencies
// into another).
configurations.testImplementation.get().extendsFrom(jsLang, rubyLang, pythonLang, javaLang, llvmLang, wasmLang, luaLang, insightTool)
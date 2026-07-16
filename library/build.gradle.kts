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

dependencies {
  implementation("org.graalvm.truffle:truffle-api:$graalVersion")
  implementation("org.graalvm.polyglot:polyglot:$graalVersion")
  implementation("org.graalvm.polyglot:js:$graalVersion")
  implementation("org.graalvm.polyglot:python:$graalVersion")
  implementation("org.graalvm.polyglot:java:$graalVersion")
  implementation("org.graalvm.polyglot:ruby:$graalVersion")
  implementation("org.graalvm.polyglot:wasm:$graalVersion")
  implementation("org.graalvm.polyglot:llvm:$graalVersion")
  implementation("org.graalvm.polyglot:llvm-native:$graalVersion")
  implementation("org.graalvm.ruby:ruby:$graalVersion")
  implementation("io.mvnpm:esbuild-java:1.5.1")

  // Pure-Kotlin npm registry client + package cache (CompilerDownloader):
  // org.json for registry response parsing, commons-compress for tar/gzip streaming
  // extraction. Both are already transitively pulled in by the GraalVM artifacts above
  // (org.graalvm.shadowed:json, commons-compress via native-image tooling) but are
  // declared explicitly here since relying on an undeclared transitive dependency is
  // fragile — it could disappear silently if those artifacts' own dependencies change.
  implementation("org.json:json:20240303")
  implementation("org.apache.commons:commons-compress:1.26.2")
}
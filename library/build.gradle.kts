plugins { id("buildlogic.kotlin-library-conventions") }

val graalVersion = "24.1.1"

dependencies {
  implementation("org.graalvm.truffle:truffle-api:$graalVersion")
  implementation("org.graalvm.compiler:compiler:$graalVersion")
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
}
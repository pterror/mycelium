plugins { id("buildlogic.kotlin-library-conventions") }

dependencies {
  implementation("org.graalvm.polyglot:polyglot:24.1.1")
  implementation("org.graalvm.polyglot:js:24.1.1")
  implementation("org.graalvm.polyglot:python:24.1.1")
  implementation("org.graalvm.polyglot:java:24.1.1")
  implementation("org.graalvm.polyglot:ruby:24.1.1")
  implementation("org.graalvm.polyglot:wasm:24.1.1")
  implementation("org.graalvm.polyglot:llvm:24.1.1")
  implementation("org.graalvm.polyglot:llvm-native:24.1.1")
  implementation("org.graalvm.ruby:ruby:24.1.1")
}
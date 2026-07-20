plugins {
    java
}

repositories { mavenCentral() }

// Match the graalVersion pinned in library/build.gradle.kts — see that file's comment for
// why every GraalVM/Truffle artifact across this project must stay on the same version
// (mixing truffle-api versions across modules is ABI-incompatible at runtime).
val graalVersion = "25.0.0"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    implementation("org.graalvm.truffle:truffle-api:$graalVersion")
    annotationProcessor("org.graalvm.truffle:truffle-dsl-processor:$graalVersion")
    testAnnotationProcessor("org.graalvm.truffle:truffle-dsl-processor:$graalVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

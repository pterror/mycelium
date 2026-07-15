plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories { mavenCentral() }

dependencies {
    constraints { implementation("org.apache.commons:commons-text:1.11.0") }

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// JDK 23 reached end of life and was dropped from nixpkgs/foojay. The
// GraalVM in flake.nix now ships JDK 25, but the pinned Kotlin Gradle plugin
// (2.1.0-Beta2) doesn't know how to target JVM 25 bytecode yet, so target
// JDK 21 (LTS, available locally, well within GraalVM Truffle 24.2.1's
// supported range) instead.
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

tasks.named<Test>("test") {
    useJUnitPlatform()
    // GraalVM Context instances are memory-hungry; without a cap the default test JVM
    // heap sizing can lead to OutOfMemoryError when several tests each spin one up.
    jvmArgs = listOf("-Xmx2g")
}

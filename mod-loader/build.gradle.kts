plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories { mavenCentral() }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

// ASM is bundled into this fat jar (see tasks.jar below) and exposed as `implementation`
// (not just used internally) so that mod jars — loaded via a URLClassLoader whose parent
// is this jar's classloader (see ModDiscovery.kt) — can use ASM to write their bytecode
// transformers without having to shade their own copy of it. See example-mod's
// build.gradle.kts, which depends on ASM as `compileOnly` for exactly this reason.
dependencies {
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-tree:9.7")
}

// Production mod loader: a Java agent that discovers mod jars (each with a mod.json
// descriptor) at JVM startup, loads their declared ModTransformer implementations, and
// applies ASM-based bytecode patches to targeted classes at class-load time. This is a
// self-contained fat jar (ASM classes bundled in) since it's loaded via `-javaagent:` on
// the bare JVM classpath, before Gradle's normal dependency resolution/classpath
// machinery — or the application's own classpath — is involved.
tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "org.mycelium.modloader.ModLoaderAgentKt",
            "Can-Retransform-Classes" to "true",
        )
    }
    from({
        configurations.runtimeClasspath.get().filter { it.exists() }.map { if (it.isDirectory) it else zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

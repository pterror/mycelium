plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories { mavenCentral() }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-tree:9.7")
}

// Proof-of-concept Java agent: patches GraalJS's JSAddNodeGen bytecode at class-load time
// so int+int addition returns one extra — e.g. `1 + 1 === 3`. This is a self-contained
// fat jar (ASM classes bundled in) since it's loaded via `-javaagent:` on the bare JVM
// classpath, before Gradle's normal dependency resolution/classpath machinery is involved.
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

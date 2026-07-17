plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories { mavenCentral() }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    // See example-mod/build.gradle.kts for why ModTransformer/ASM are compileOnly here:
    // both are supplied at runtime by the mod-loader's own classloader, and bundling a
    // second copy into this mod's jar would create a classloader-identity mismatch.
    compileOnly(project(":mod-loader"))
    compileOnly("org.ow2.asm:asm:9.7")
    compileOnly("org.ow2.asm:asm-tree:9.7")
}

// operator-overloading-mod's jar IS the mod jar: mod.json plus the compiled
// OperatorOverloadingTransformer .class file — nothing else bundled in. Drop this jar
// into the mods directory (see :app's jvmRun task, which does exactly that when
// -Pmod is passed).
tasks.jar {
    archiveBaseName.set("operator-overloading-mod")
}

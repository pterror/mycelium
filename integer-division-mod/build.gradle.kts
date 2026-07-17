plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories { mavenCentral() }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    // See example-mod/build.gradle.kts for why these are compileOnly: ModTransformer and
    // ASM are both supplied at runtime by the mod-loader's own classloader, so bundling
    // them here would create a second, incompatible copy of each class on the mod's
    // classloader.
    compileOnly(project(":mod-loader"))
    compileOnly("org.ow2.asm:asm:9.7")
    compileOnly("org.ow2.asm:asm-tree:9.7")
}

// integer-division-mod's jar IS the mod jar: mod.json (see src/main/resources/mod.json)
// at the root plus the compiled IntegerDivisionTransformer .class file — nothing else
// bundled in, since ModTransformer/ASM come from the mod-loader's classloader at runtime
// (see the `compileOnly` comment above). Drop this jar straight into the mods directory
// (see :app's jvmRun task, which does exactly that when -Pmod is passed).
tasks.jar {
    archiveBaseName.set("integer-division-mod")
}

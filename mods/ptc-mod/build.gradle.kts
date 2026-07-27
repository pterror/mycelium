plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories { mavenCentral() }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

// Keep in sync with the GraalJS version this mod was investigated against (see
// TailCallTransformer's class doc comment — javap'd against js-language-25.0.0.jar) and
// with library/build.gradle.kts's `graalVersion`.
val graalVersion = "25.0.0"

dependencies {
    // See example-mod/build.gradle.kts for why ModTransformer/ASM are compileOnly here:
    // both are supplied at runtime by the mod-loader's own classloader, and bundling a
    // second copy into this mod's jar would create a classloader-identity mismatch.
    compileOnly(project(":mod-loader"))
    compileOnly("org.ow2.asm:asm:9.7")
    compileOnly("org.ow2.asm:asm-tree:9.7")

    // Unlike the other mods so far, TailCallState (the runtime half of this mod — see its
    // doc comment) calls straight into GraalJS/Truffle runtime types (JSArguments,
    // JSFunction, JSFunctionObject, RootCallTarget, VirtualFrame...) instead of only doing
    // ASM bytecode surgery. These are `compileOnly` for the exact same reason as
    // ModTransformer/ASM above: at actual runtime, GraalJS's own classloader already
    // provides every one of these classes — bundling a second copy into this mod's jar
    // would just create a duplicate, incompatible class identity the moment
    // `ModLoaderAgent` appends this jar to the system classloader search path.
    compileOnly("org.graalvm.truffle:truffle-api:$graalVersion")
    compileOnly("org.graalvm.js:js-language:$graalVersion")
}

// ptc-mod's jar IS the mod jar: mod.json (see src/main/resources/mod.json) plus the
// compiled TailCall/TailCallState/TailCallTransformer .class files — nothing else bundled
// in, since ModTransformer/ASM/GraalJS/Truffle all come from the mod-loader's classloader
// or (for GraalJS/Truffle) the application classloader at runtime — see the dependency
// comments above. Drop this jar into the mods directory (see :app's jvmRun task, which
// does exactly that when -Pmod is passed).
tasks.jar {
    archiveBaseName.set("ptc-mod")
}

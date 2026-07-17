package org.mycelium.modloader

import java.io.File
import java.lang.instrument.Instrumentation
import java.util.jar.JarFile

/**
 * `-javaagent:` entry point (see `build.gradle.kts`'s `Premain-Class` manifest
 * attribute). This is the production mod loader: it discovers mod jars in the
 * configured mods directory, loads each one's declared [ModTransformer]s, and
 * registers a single [ModDispatchTransformer] with the JVM's instrumentation API to
 * apply them at class-load time.
 *
 * Mods directory resolution — see [resolveModsDir]:
 *   1. `-Dmycelium.mods.dir=<path>` system property
 *   2. `MYCELIUM_MODS_DIR` environment variable
 *   3. `mods/` relative to the working directory
 *
 * Started life as a proof of concept that hardcoded a single patch to GraalJS's
 * `JSAddNodeGen` (making `1 + 1 === 3`) directly in this file. That patch now lives as
 * a real mod in the `example-mod` module (see `CursedAdditionTransformer`) — this file
 * is purely generic infrastructure with no knowledge of any specific mod.
 */
fun premain(agentArgs: String?, inst: Instrumentation) {
    val modsDir = resolveModsDir()
    println("[mod-loader] Agent starting — mods directory: ${modsDir.absolutePath}")

    val mods = discoverMods(modsDir)
    if (mods.isEmpty()) {
        println("[mod-loader] No mods loaded — agent installed but inactive")
        return
    }

    val totalTransformers = mods.sumOf { it.transformers.size }
    println("[mod-loader] Loaded ${mods.size} mod(s), $totalTransformers transformer(s) total")

    // Makes each mod jar's classes visible to the *application* classloader (not just
    // this mod's own URLClassLoader). Needed so that bytecode patched into application
    // classes (e.g. via PatchDsl's `redirectCallsTo`) can INVOKESTATIC a mod-provided
    // replacement method — the patched class is loaded by the application classloader,
    // which otherwise has no way to see classes that only exist inside a mod jar.
    for (mod in mods) {
        appendModJarToSystemClassLoaderSearch(inst, mod)
    }

    inst.addTransformer(ModDispatchTransformer(mods))
    println("[mod-loader] Dispatch transformer registered")
}

/**
 * Adds [mod]'s own jar to the system classloader's search path via
 * [Instrumentation.appendToSystemClassLoaderSearch], so that classes it declares (e.g.
 * a replacement method used as a `redirectCallsTo` target) resolve from the application
 * classloader, not just from the mod's own [java.net.URLClassLoader]. Best-effort: a
 * failure here only means that mod's redirect-style patches (if any) won't resolve —
 * logged, not fatal to the rest of the agent.
 */
private fun appendModJarToSystemClassLoaderSearch(inst: Instrumentation, mod: LoadedMod) {
    val jarUrl = mod.classLoader.getURLs().firstOrNull() ?: return
    val jarFile = try {
        File(jarUrl.toURI())
    } catch (t: Throwable) {
        println("[mod-loader] Warning: couldn't resolve jar path for mod '${mod.descriptor.id}' ($jarUrl): $t")
        return
    }
    try {
        inst.appendToSystemClassLoaderSearch(JarFile(jarFile))
        println("[mod-loader] Added '${jarFile.name}' to the system classloader search path")
    } catch (t: Throwable) {
        println("[mod-loader] Warning: failed to add '${jarFile.name}' to the system classloader search path: $t")
    }
}

package org.mycelium.modloader

import java.lang.instrument.Instrumentation

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

    inst.addTransformer(ModDispatchTransformer(mods))
    println("[mod-loader] Dispatch transformer registered")
}

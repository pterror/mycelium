package org.mycelium.modloader

import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

/** System property (falls back to an env var of the same name with `.` -> `_`) naming the mods directory. */
const val MODS_DIR_PROPERTY = "mycelium.mods.dir"
const val DEFAULT_MODS_DIR = "mods"

/** Resolves the mods directory: `-Dmycelium.mods.dir=...` > `MYCELIUM_MODS_DIR` env var > `mods/` under the working dir. */
fun resolveModsDir(): File {
    val fromProperty = System.getProperty(MODS_DIR_PROPERTY)
    val fromEnv = System.getenv("MYCELIUM_MODS_DIR")
    val path = fromProperty ?: fromEnv ?: DEFAULT_MODS_DIR
    return File(path)
}

/**
 * Scans [modsDir] for `.jar` files, parses each one's `mod.json`, and loads its declared
 * transformer classes. Jars that fail to parse/load are skipped with a logged warning —
 * one bad mod shouldn't prevent the others (or the host application) from starting.
 */
fun discoverMods(modsDir: File): List<LoadedMod> {
    if (!modsDir.isDirectory) {
        println("[mod-loader] Mods directory '${modsDir.path}' does not exist — no mods loaded")
        return emptyList()
    }

    val jarFiles = modsDir.listFiles { f -> f.isFile && f.extension == "jar" }
        ?.sortedBy { it.name }
        ?: emptyList()

    if (jarFiles.isEmpty()) {
        println("[mod-loader] No mod jars found in '${modsDir.path}'")
        return emptyList()
    }

    val loaded = mutableListOf<LoadedMod>()
    for (jarFile in jarFiles) {
        try {
            loaded += loadMod(jarFile)
        } catch (t: Throwable) {
            println("[mod-loader] Failed to load mod jar '${jarFile.name}': $t")
            t.printStackTrace()
        }
    }
    return loaded.sortedBy { it.descriptor.priority }
}

private fun loadMod(jarFile: File): LoadedMod {
    val descriptorJson = JarFile(jarFile).use { jar ->
        val entry = jar.getEntry("mod.json")
            ?: throw ModDescriptorException("'${jarFile.name}' has no mod.json at its root")
        jar.getInputStream(entry).bufferedReader().use { it.readText() }
    }
    val descriptor = ModDescriptor.parse(descriptorJson)

    // Parent is this classloader (the mod-loader's own) so mod code can see
    // ModTransformer/etc. without needing its own copy on its classpath.
    val classLoader = URLClassLoader(
        arrayOf(jarFile.toURI().toURL()),
        ModTransformer::class.java.classLoader,
    )

    val transformers = descriptor.transformers.map { className ->
        val clazz = try {
            Class.forName(className, true, classLoader)
        } catch (e: ClassNotFoundException) {
            throw ModDescriptorException("mod '${descriptor.id}' declares transformer '$className' which was not found in ${jarFile.name}")
        }
        if (!ModTransformer::class.java.isAssignableFrom(clazz)) {
            throw ModDescriptorException("mod '${descriptor.id}' transformer '$className' does not implement ModTransformer")
        }
        val instance = clazz.getDeclaredConstructor().newInstance()
        instance as ModTransformer
    }

    println("[mod-loader] Loaded mod: ${descriptor.name} v${descriptor.version} (${transformers.size} transformer${if (transformers.size == 1) "" else "s"})")

    return LoadedMod(descriptor, transformers, classLoader)
}

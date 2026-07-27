package org.mycelium.modloader

import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

/** System property (falls back to an env var of the same name with `.` -> `_`) naming the mods directory. */
const val MODS_DIR_PROPERTY = "mycelium.mods.dir"
const val DEFAULT_MODS_DIR = "mods"

/**
 * System property carrying the runtime GraalVM/Truffle version, used to validate a
 * mod's `targetVersion` against what's actually running. The agent runs via `premain`,
 * before the application classpath (and thus any GraalVM classes it might otherwise be
 * able to introspect) is guaranteed to be available, so a system property is the
 * simplest reliable source: `-Dmycelium.graal.version=25.0.0`.
 */
const val GRAAL_VERSION_PROPERTY = "mycelium.graal.version"

/** Resolves the mods directory: `-Dmycelium.mods.dir=...` > `MYCELIUM_MODS_DIR` env var > `mods/` under the working dir. */
fun resolveModsDir(): File {
    val fromProperty = System.getProperty(MODS_DIR_PROPERTY)
    val fromEnv = System.getenv("MYCELIUM_MODS_DIR")
    val path = fromProperty ?: fromEnv ?: DEFAULT_MODS_DIR
    return File(path)
}

/** Resolves the runtime GraalVM/Truffle version, or null if not known. See [GRAAL_VERSION_PROPERTY]. */
fun resolveGraalVersion(): String? = System.getProperty(GRAAL_VERSION_PROPERTY)

/** A parsed-but-not-yet-instantiated mod: its descriptor is known, but its transformer classes haven't been loaded. */
private data class ModCandidate(val jarFile: File, val descriptor: ModDescriptor)

/**
 * Scans [modsDir] for `.jar` files, parses each one's `mod.json`, validates
 * `depends`/`breaks` and `targetVersion`, resolves a load order that respects
 * dependencies (topological sort), and loads each surviving mod's declared
 * transformer classes. Jars that fail to parse/load, or that fail dependency
 * validation, are skipped with a logged error — one bad mod shouldn't prevent the
 * others (or the host application) from starting.
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

    // Phase 1: parse every mod.json. A parse failure only removes that one jar.
    val candidates = mutableListOf<ModCandidate>()
    for (jarFile in jarFiles) {
        try {
            candidates += ModCandidate(jarFile, parseDescriptor(jarFile))
        } catch (t: Throwable) {
            println("[mod-loader] Failed to parse mod jar '${jarFile.name}': $t")
            t.printStackTrace()
        }
    }

    val byId = LinkedHashMap<String, ModCandidate>()
    for (candidate in candidates) {
        val existing = byId.put(candidate.descriptor.id, candidate)
        if (existing != null) {
            println(
                "[mod-loader] Error: duplicate mod id '${candidate.descriptor.id}' " +
                    "(${existing.jarFile.name} and ${candidate.jarFile.name}) — keeping the later one",
            )
        }
    }

    // Phase 2: depends/breaks validation, then topological sort. Determines final load order.
    val loadOrder = resolveLoadOrder(byId)

    // Phase 3: instantiate transformers, in dependency order, for mods that survived validation.
    val loaded = mutableListOf<LoadedMod>()
    for (id in loadOrder) {
        val candidate = byId.getValue(id)
        try {
            checkTargetVersion(candidate.descriptor)
            loaded += instantiateMod(candidate.jarFile, candidate.descriptor)
        } catch (t: Throwable) {
            println("[mod-loader] Failed to load mod jar '${candidate.jarFile.name}': $t")
            t.printStackTrace()
        }
    }
    return loaded
}

/** Reads and parses a jar's `mod.json` without instantiating any of its transformer classes. */
private fun parseDescriptor(jarFile: File): ModDescriptor {
    val descriptorJson = JarFile(jarFile).use { jar ->
        val entry = jar.getEntry("mod.json")
            ?: throw ModDescriptorException("'${jarFile.name}' has no mod.json at its root")
        jar.getInputStream(entry).bufferedReader().use { it.readText() }
    }
    return ModDescriptor.parse(descriptorJson)
}

/**
 * Validates `depends`/`breaks` for every candidate against the full candidate set, then
 * topologically sorts the survivors so dependencies load before dependents. Mods that
 * fail validation, or that participate in a dependency cycle, are logged and excluded
 * from the returned order.
 */
private fun resolveLoadOrder(byId: Map<String, ModCandidate>): List<String> {
    val versionById = byId.mapValues { (_, c) -> c.descriptor.version }

    // Step 1: depends/breaks validation.
    val valid = LinkedHashSet<String>()
    for (candidate in byId.values) {
        val descriptor = candidate.descriptor
        var ok = true

        for ((depId, range) in descriptor.depends) {
            val depVersion = versionById[depId]
            if (depVersion == null) {
                println(
                    "[mod-loader] Error: mod '${descriptor.id}' depends on '$depId' ($range) " +
                        "which is not present — skipping '${descriptor.id}'",
                )
                ok = false
            } else if (!SemVer.matches(depVersion, range)) {
                println(
                    "[mod-loader] Error: mod '${descriptor.id}' depends on '$depId' $range " +
                        "but found version $depVersion — skipping '${descriptor.id}'",
                )
                ok = false
            }
        }

        for ((breakId, range) in descriptor.breaks) {
            val breakVersion = versionById[breakId]
            if (breakVersion != null && SemVer.matches(breakVersion, range)) {
                println(
                    "[mod-loader] Error: mod '${descriptor.id}' is incompatible with '$breakId' $range " +
                        "(found $breakVersion) — skipping '${descriptor.id}'",
                )
                ok = false
            }
        }

        if (ok) valid += descriptor.id
    }

    // Step 2: topological sort (Kahn's algorithm) over the mods that passed validation.
    // Edges run dependency -> dependent (a dependency must be sorted before whatever
    // depends on it). Edges to mods that didn't pass validation were already reported
    // above and are simply dropped here.
    val dependents = HashMap<String, MutableList<String>>()
    val inDegree = HashMap<String, Int>()
    for (id in valid) inDegree[id] = 0
    for (id in valid) {
        val descriptor = byId.getValue(id).descriptor
        for (depId in descriptor.depends.keys) {
            if (depId in valid) {
                dependents.getOrPut(depId) { mutableListOf() }.add(id)
                inDegree[id] = inDegree.getValue(id) + 1
            }
        }
    }

    val queue = ArrayDeque<String>()
    for (id in valid) {
        if (inDegree.getValue(id) == 0) queue.addLast(id)
    }

    val sorted = mutableListOf<String>()
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        sorted += id
        for (dependent in dependents[id].orEmpty()) {
            val remaining = inDegree.getValue(dependent) - 1
            inDegree[dependent] = remaining
            if (remaining == 0) queue.addLast(dependent)
        }
    }

    val cyclic = valid - sorted.toSet()
    if (cyclic.isNotEmpty()) {
        println("[mod-loader] Error: dependency cycle detected among mods: ${cyclic.sorted().joinToString(", ")} — skipping")
    }

    return sorted
}

/** Warns (does not fail) if a mod's `targetVersion` doesn't match the running GraalVM version. */
private fun checkTargetVersion(descriptor: ModDescriptor) {
    val targetVersion = descriptor.targetVersion ?: return
    val runtimeVersion = resolveGraalVersion() ?: return
    if (!SemVer.matches(runtimeVersion, targetVersion)) {
        println(
            "[mod-loader] Warning: mod '${descriptor.id}' targets GraalVM $targetVersion " +
                "but runtime is $runtimeVersion",
        )
    }
}

private fun instantiateMod(jarFile: File, descriptor: ModDescriptor): LoadedMod {
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

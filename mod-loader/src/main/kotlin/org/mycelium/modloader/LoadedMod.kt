package org.mycelium.modloader

/**
 * A mod jar that's been discovered, parsed, and had its declared [ModTransformer]
 * classes instantiated. [classLoader] is kept alive for the lifetime of the process —
 * it owns the transformer instances and any classes they load lazily.
 */
data class LoadedMod(
    val descriptor: ModDescriptor,
    val transformers: List<ModTransformer>,
    val classLoader: java.net.URLClassLoader,
)

/** A single [ModTransformer] paired with the mod (and its priority) it came from. */
data class PrioritizedTransformer(
    val modId: String,
    val modName: String,
    val priority: Int,
    val transformer: ModTransformer,
)

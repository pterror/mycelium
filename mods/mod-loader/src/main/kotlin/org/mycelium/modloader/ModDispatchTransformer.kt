package org.mycelium.modloader

import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain

/**
 * The single [ClassFileTransformer] registered with [java.lang.instrument.Instrumentation]
 * (JVM instrumentation only allows composing multiple transformers by registering several
 * of them and letting each see the previous one's output — this class does the
 * mod-loader-specific part of that: routing a given class to *only* the mods that
 * declared interest in it, in priority order).
 *
 * Transformers for the same class run in ascending priority order (lower priority number
 * runs first — same convention as Mixin), each one seeing the previous transformer's
 * output. That's how multiple mods compose patches to the same class.
 */
class ModDispatchTransformer(mods: List<LoadedMod>) : ClassFileTransformer {
    // Pre-computed once at registration time: for each target class name, the ordered
    // list of transformers that want to see it.
    private val transformersByClass: Map<String, List<PrioritizedTransformer>>

    init {
        val byClass = LinkedHashMap<String, MutableList<PrioritizedTransformer>>()
        for (mod in mods) {
            for (transformer in mod.transformers) {
                for (target in transformer.targetClasses()) {
                    byClass.getOrPut(target) { mutableListOf() }
                        .add(PrioritizedTransformer(mod.descriptor.id, mod.descriptor.name, mod.descriptor.priority, transformer))
                }
            }
        }
        // Stable sort by priority — ties keep discovery order.
        for (list in byClass.values) {
            list.sortBy { it.priority }
        }
        transformersByClass = byClass

        // Conflict detection: classes targeted by more than one mod. Not an error —
        // priority ordering handles composition — just informative.
        for ((className, transformers) in byClass) {
            if (transformers.size > 1) {
                val modIds = transformers.distinctBy { it.modId }
                if (modIds.size > 1) {
                    val summary = modIds.joinToString(", ") { "${it.modId} (priority ${it.priority})" }
                    println("[mod-loader] Warning: class $className targeted by multiple mods: $summary")
                }
            }
        }
    }

    override fun transform(
        loader: ClassLoader?,
        className: String?,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray,
    ): ByteArray? {
        if (className == null) return null
        val transformers = transformersByClass[className] ?: return null

        var bytecode = classfileBuffer
        for (prioritized in transformers) {
            val result = try {
                prioritized.transformer.transform(className, bytecode)
            } catch (t: Throwable) {
                println("[mod-loader] Transformer from mod '${prioritized.modId}' threw while transforming $className: $t")
                t.printStackTrace()
                null
            }
            if (result != null) {
                bytecode = result
                println("[mod-loader] Applied transform from mod '${prioritized.modId}' (${prioritized.modName}) to $className")
            }
        }
        return if (bytecode !== classfileBuffer) bytecode else null
    }
}

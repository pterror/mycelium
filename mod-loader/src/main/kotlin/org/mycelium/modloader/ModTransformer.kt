package org.mycelium.modloader

/**
 * Interface that mod authors implement. Each transformer targets specific classes
 * and provides bytecode transformations via ASM.
 *
 * Implementations are loaded from mod jars via a dedicated [java.net.URLClassLoader]
 * (see [ModLoaderAgent]) whose parent is the mod-loader's own classloader — that's
 * why this interface lives in the mod-loader jar itself, so mod code and the loader
 * agree on a single class identity for it.
 */
interface ModTransformer {
    /**
     * The internal class names (using `/` separators, e.g.
     * `com/oracle/truffle/js/nodes/binary/JSAddNodeGen`) this transformer wants to
     * patch. Only called once at registration time.
     */
    fun targetClasses(): Set<String>

    /**
     * Transform the class bytecode. Called when a targeted class is being loaded.
     * Return the modified bytecode, or null to skip (leave bytecode unchanged).
     */
    fun transform(className: String, classfileBuffer: ByteArray): ByteArray?
}

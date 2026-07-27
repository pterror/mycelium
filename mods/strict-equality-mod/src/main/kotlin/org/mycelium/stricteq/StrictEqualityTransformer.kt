package org.mycelium.stricteq

import org.mycelium.modloader.ModTransformer
import org.mycelium.modloader.patchClass

/**
 * Patches GraalJS's `NodeFactory.createBinaryEqual` — the private static factory method
 * invoked for JS `==`/`!=` operators — so its `JSEqualNode.create(a, b)` fallback call is
 * redirected to `JSIdenticalNode.create(a, b)` instead. Both factory methods share the
 * exact same descriptor
 * (`(Lcom/oracle/truffle/js/nodes/JavaScriptNode;Lcom/oracle/truffle/js/nodes/JavaScriptNode;)Lcom/oracle/truffle/js/nodes/JavaScriptNode;`),
 * so the redirect is a drop-in swap: `==` now behaves like `===` and `!=` like `!==`.
 *
 * Investigation with `javap -c -p` on js-language-25.0.0.jar's `NodeFactory.class` showed
 * `JSEqualNode.create` is called from exactly one call site in the whole class — inside
 * `createBinaryEqual` — so the class-wide `redirectCallsTo` (which scans every method) is
 * safe here: there's nothing else in `NodeFactory` it could accidentally catch.
 */
class StrictEqualityTransformer : ModTransformer {
    override fun targetClasses() = setOf("com/oracle/truffle/js/nodes/NodeFactory")

    override fun transform(className: String, classfileBuffer: ByteArray): ByteArray? {
        return patchClass(classfileBuffer) {
            redirectCallsTo(
                "com/oracle/truffle/js/nodes/binary/JSEqualNode",
                "create"
            ).to(
                "com/oracle/truffle/js/nodes/binary/JSIdenticalNode",
                "create"
            )
        }
    }
}

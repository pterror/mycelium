package org.mycelium.nullsafe

import org.mycelium.modloader.InsnBuilder
import org.mycelium.modloader.ModTransformer
import org.mycelium.modloader.patchClass
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode

/**
 * Second example mod, alongside `example-mod`'s `CursedAdditionTransformer`: patches
 * GraalJS's `PropertyGetNode$TypeErrorPropertyGetNode.getValue` so that reading a property
 * off `null`/`undefined` returns `undefined` instead of throwing a `TypeError` —
 * `null.foo` becomes `undefined` rather than crashing.
 *
 * Investigation with `javap -c -p` on js-language-25.0.0.jar showed
 * `TypeErrorPropertyGetNode` is constructed from two call sites in `PropertyGetNode`:
 *
 *  - `createUndefinedPropertyNode` — builds the node with a fresh
 *    `PropertyCacheNode$NullCheckNode` as its `receiverCheck`.
 *  - `createUndefinedJSObjectPropertyNode` — a different "required property missing on a
 *    live JSObject" case, which passes through whatever `AbstractShapeCheckNode` it was
 *    given.
 *
 * The initial approach here checked the runtime type of `this.receiverCheck`, assuming
 * only the `NullCheckNode` construction site corresponded to `null`/`undefined` receivers.
 * That assumption was wrong: GraalJS represents JS `null`/`undefined` internally as the
 * `JSDynamicObject` singletons `Null.instance`/`Undefined.instance`, each with its own real
 * `Shape` — so `null.foo` actually goes through the ordinary shape-check cache path and
 * lands here via `createUndefinedJSObjectPropertyNode`, with a `ShapeCheckNode`
 * `receiverCheck` (confirmed by instrumenting `getValue` to print
 * `receiverCheck.getClass().getName()` at runtime — it printed
 * `PropertyCacheNode$ShapeCheckNode` for `null.foo`, not `NullCheckNode`).
 *
 * So instead this mod checks the **receiver value itself** — mirroring the exact
 * three-way check `getValue`'s own (usually-disabled) assertion already performs:
 * `receiver == Undefined.instance || receiver == Null.instance || receiver == null`. Only
 * when one of those holds do we short-circuit and return the method's own `defaultValue`
 * argument (which callers already pass as `Undefined.instance`) instead of falling into the
 * original throwing code — so a *bona fide* required-property-missing error on a real,
 * non-null object (the other construction site's other use) still throws as before.
 *
 * `getValue`'s descriptor is
 * `(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Lcom/oracle/truffle/js/nodes/access/PropertyGetNode;Z)Ljava/lang/Object;`
 * — an *instance* method, so local var 0 is `this`, 1 is `receiver`, 2 is `key`, 3 is
 * `defaultValue`, 4 is the owning `PropertyGetNode` ("root"), 5 is `isMethod`.
 */
class NullSafeTransformer : ModTransformer {
    override fun targetClasses(): Set<String> =
        setOf("com/oracle/truffle/js/nodes/access/PropertyGetNode\$TypeErrorPropertyGetNode")

    override fun transform(className: String, classfileBuffer: ByteArray): ByteArray? {
        println("[null-safe] Intercepted $className — applying patch")
        return try {
            patchClass(classfileBuffer) {
                atMethodEntry("getValue") {
                    // if (receiver == Undefined.instance || receiver == Null.instance ||
                    //     receiver == null) return defaultValue;
                    // (falls through to the original throwing code otherwise — mirrors the
                    // three-way check getValue's own assertion already performs, see the
                    // class doc comment above)
                    val skip = LabelNode()
                    val returnDefault = LabelNode()

                    // Both `skip` and `returnDefault` below are brand-new branch targets
                    // introduced by this patch, each reachable *only* via a jump (never by
                    // fall-through — every path into them passes through either an
                    // unconditional ARETURN or is itself the target of a conditional jump
                    // whose fallthrough sibling is a further check), so each needs an
                    // explicit StackMapTable entry or the verifier rejects the method with
                    // "Expecting a stackmap frame at branch target". ClassWriter here runs
                    // with COMPUTE_MAXS (not COMPUTE_FRAMES — see patchClass in mod-loader's
                    // PatchDsl.kt), so frames at any *new* merge point introduced by a mod
                    // must be supplied by hand. Both frames here are simply "identical to
                    // method entry": none of the injected instructions touch a local
                    // variable, and every branch above leaves the operand stack empty.
                    fun InsnBuilder.frameLikeMethodEntry() = raw(
                        FrameNode(
                            Opcodes.F_NEW,
                            6,
                            arrayOf<Any>(
                                "com/oracle/truffle/js/nodes/access/PropertyGetNode\$TypeErrorPropertyGetNode", // this
                                "java/lang/Object", // receiver
                                "java/lang/Object", // key
                                "java/lang/Object", // defaultValue
                                "com/oracle/truffle/js/nodes/access/PropertyGetNode", // root
                                Opcodes.INTEGER, // isMethod (boolean)
                            ),
                            0,
                            arrayOf<Any>(),
                        ),
                    )

                    aload(1) // receiver
                    getstatic(
                        "com/oracle/truffle/js/runtime/objects/Undefined",
                        "instance",
                        "Lcom/oracle/truffle/js/runtime/objects/JSDynamicObject;",
                    )
                    raw(JumpInsnNode(Opcodes.IF_ACMPEQ, returnDefault))

                    aload(1) // receiver
                    getstatic(
                        "com/oracle/truffle/js/runtime/objects/Null",
                        "instance",
                        "Lcom/oracle/truffle/js/runtime/objects/JSDynamicObject;",
                    )
                    raw(JumpInsnNode(Opcodes.IF_ACMPEQ, returnDefault))

                    aload(1) // receiver
                    raw(JumpInsnNode(Opcodes.IFNONNULL, skip))

                    raw(returnDefault)
                    frameLikeMethodEntry()
                    aload(3) // defaultValue
                    areturn()

                    raw(skip)
                    frameLikeMethodEntry()
                }
            }
        } catch (t: Throwable) {
            println("[null-safe] Patch FAILED, leaving class unmodified: $t")
            t.printStackTrace()
            null
        }
    }
}

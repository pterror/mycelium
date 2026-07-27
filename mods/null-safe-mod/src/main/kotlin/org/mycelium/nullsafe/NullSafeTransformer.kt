package org.mycelium.nullsafe

import org.mycelium.modloader.InsnBuilder
import org.mycelium.modloader.ModTransformer
import org.mycelium.modloader.patchClass
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
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
    override fun targetClasses(): Set<String> = setOf(
        "com/oracle/truffle/js/nodes/access/PropertyGetNode\$TypeErrorPropertyGetNode",
        "com/oracle/truffle/js/nodes/access/ReadElementNode",
    )

    override fun transform(className: String, classfileBuffer: ByteArray): ByteArray? {
        println("[null-safe] Intercepted $className — applying patch")
        return try {
            when (className) {
                "com/oracle/truffle/js/nodes/access/PropertyGetNode\$TypeErrorPropertyGetNode" ->
                    patchTypeErrorPropertyGetNode(classfileBuffer)
                "com/oracle/truffle/js/nodes/access/ReadElementNode" ->
                    patchReadElementNode(classfileBuffer)
                else -> null
            }
        } catch (t: Throwable) {
            println("[null-safe] Patch FAILED, leaving class unmodified: $t")
            t.printStackTrace()
            null
        }
    }

    /**
     * Patches `PropertyGetNode$TypeErrorPropertyGetNode.getValue` — see the class doc comment
     * above for the full investigation behind this specific patch.
     */
    private fun patchTypeErrorPropertyGetNode(classfileBuffer: ByteArray): ByteArray? =
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

    /**
     * Patches GraalJS's `ReadElementNode` so that computed/bracket property access on
     * `null`/`undefined` — `null[0]`, `undefined["key"]` — returns `undefined` instead of
     * throwing, mirroring [patchTypeErrorPropertyGetNode]'s handling of dotted access.
     *
     * `ReadElementNode` is the node behind `target[index]`. Its public API is a family of
     * `executeWithTargetAndIndex*` overloads (see `javap -c -p` on
     * js-language-25.0.0.jar), each specialized for a different index type (`Object`/`int`/
     * `long`) and some additionally taking a `defaultValue` parameter. Every one of them is a
     * one-liner that immediately forwards to `executeTypeDispatch`/`executeTypeDispatchInt`/
     * `executeTypeDispatchDouble`, which is where the actual (potentially null-unsafe) shape
     * dispatch cache lives.
     *
     * Rather than chasing the dispatch cache down into whatever internal node ultimately
     * throws (there's no single `TypeErrorReadElementNode`-shaped class to target the way
     * `PropertyGetNode` has), this patches at the outer wrapper: every `executeWithTargetAndIndex`
     * overload that **returns `Object`** gets a null-check preamble injected — if the target
     * (always local variable 1, since these are instance methods) is `Null.instance`,
     * `Undefined.instance`, or Java `null`, return `Undefined.instance` immediately instead of
     * falling through to `executeTypeDispatch`.
     *
     * The `executeWithTargetAndIndexInt`/`executeWithTargetAndIndexDouble` overloads (which
     * return primitive `int`/`double`) are deliberately left unpatched: there's no sensible
     * primitive value to stand in for `undefined`, and GraalJS already falls back to the
     * `Object`-returning path via `UnexpectedResultException` when the int/double-typed
     * assumption doesn't hold (see `executeWithTarget`'s exception-table-driven fallback),
     * so those callers still end up going through the patched `Object` overload.
     * `executeWithTargetAndIndexOrDefault` is also left alone — it's used by `??`/optional
     * chaining call sites that already pass a caller-supplied default and handle nullish
     * targets themselves upstream.
     *
     * Each overload has a different local-variable layout (index argument may be `Object`,
     * `int`, or `long`; a `defaultValue` parameter may or may not follow it), so — unlike the
     * `getValue` patch above, which uses `atMethodEntry` to patch every overload uniformly —
     * this drops down to iterating `classNode.methods` directly and computes a
     * descriptor-specific `StackMapTable` frame per method (see [frameLocalsFor]), since
     * `ClassWriter` here runs in `COMPUTE_MAXS` (not `COMPUTE_FRAMES`) mode and won't infer
     * frames at the new branch targets this patch introduces.
     */
    private fun patchReadElementNode(classfileBuffer: ByteArray): ByteArray? =
        patchClass(classfileBuffer) {
            for (method in classNode.methods) {
                if (method.name != "executeWithTargetAndIndex") continue
                val methodType = Type.getMethodType(method.desc)
                if (methodType.returnType != Type.getType(Any::class.java)) continue

                val locals = frameLocalsFor(methodType)
                val returnUndefined = LabelNode()
                val skip = LabelNode()

                fun InsnBuilder.frame() = raw(
                    FrameNode(Opcodes.F_NEW, locals.size, locals, 0, arrayOf()),
                )

                val builder = InsnBuilder()
                builder.apply {
                    aload(1) // target
                    getstatic(
                        "com/oracle/truffle/js/runtime/objects/Undefined",
                        "instance",
                        "Lcom/oracle/truffle/js/runtime/objects/JSDynamicObject;",
                    )
                    raw(JumpInsnNode(Opcodes.IF_ACMPEQ, returnUndefined))

                    aload(1) // target
                    getstatic(
                        "com/oracle/truffle/js/runtime/objects/Null",
                        "instance",
                        "Lcom/oracle/truffle/js/runtime/objects/JSDynamicObject;",
                    )
                    raw(JumpInsnNode(Opcodes.IF_ACMPEQ, returnUndefined))

                    aload(1) // target
                    raw(JumpInsnNode(Opcodes.IFNONNULL, skip))

                    raw(returnUndefined)
                    frame()
                    getstatic(
                        "com/oracle/truffle/js/runtime/objects/Undefined",
                        "instance",
                        "Lcom/oracle/truffle/js/runtime/objects/JSDynamicObject;",
                    )
                    areturn()

                    raw(skip)
                    frame()
                }

                method.instructions.insert(builder.instructions)
                recordPatch()
                println("[null-safe] Injected null-check at entry of method ${method.name}${method.desc}")
            }
        }

    /**
     * Builds the `StackMapTable` local-variable-type list for a `ReadElementNode.
     * executeWithTargetAndIndex` overload's method-entry frame (`this` plus each declared
     * argument, verification-type-mapped per JVMS rules — primitives to their boxed
     * `Opcodes.INTEGER`/`LONG`/etc. sentinel, objects/arrays to their internal name).
     * Every branch target this patch introduces sits at the very top of the method, before
     * any instruction has touched a local or the operand stack, so this is always identical
     * to the method's true entry frame — just computed per-descriptor since different
     * overloads (`Object`/`int`/`long` index, with or without a trailing `defaultValue`) have
     * different local layouts.
     */
    private fun frameLocalsFor(methodType: Type): Array<Any> {
        val locals = mutableListOf<Any>("com/oracle/truffle/js/nodes/access/ReadElementNode")
        for (argType in methodType.argumentTypes) {
            locals += when (argType.sort) {
                Type.OBJECT, Type.ARRAY -> argType.internalName
                Type.LONG -> Opcodes.LONG
                Type.DOUBLE -> Opcodes.DOUBLE
                Type.FLOAT -> Opcodes.FLOAT
                else -> Opcodes.INTEGER // BOOLEAN, BYTE, CHAR, SHORT, INT
            }
        }
        return locals.toTypedArray()
    }
}

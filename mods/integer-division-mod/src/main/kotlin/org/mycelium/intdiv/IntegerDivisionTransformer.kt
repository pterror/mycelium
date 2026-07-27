package org.mycelium.intdiv

import org.mycelium.modloader.InsnBuilder
import org.mycelium.modloader.ModTransformer
import org.mycelium.modloader.patchClass
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.VarInsnNode

/**
 * [InsnBuilder] only has `int`-flavored load/store/arithmetic helpers (the DSL's existing
 * mods are all int-only) — these fill in the `double`-flavored opcodes `doDouble` needs,
 * via [InsnBuilder.raw] rather than touching the shared `PatchDsl.kt`.
 */
private fun InsnBuilder.dload(index: Int) = raw(VarInsnNode(Opcodes.DLOAD, index))
private fun InsnBuilder.dstore(index: Int) = raw(VarInsnNode(Opcodes.DSTORE, index))
private fun InsnBuilder.ddiv() = raw(InsnNode(Opcodes.DDIV))
private fun InsnBuilder.dreturn() = raw(InsnNode(Opcodes.DRETURN))

/**
 * Patches GraalJS's `JSDivideNode` so JS `/` truncates toward zero for both its int and
 * double fast paths, instead of the default IEEE-754 floating point division — C-style
 * integer division, e.g. `7 / 2 === 3` and `-7 / 2 === -3`.
 *
 * Investigation with `javap -c -p` on js-language-25.0.0.jar's
 * `com.oracle.truffle.js.nodes.binary.JSDivideNode` (the hand-written base class, NOT the
 * Truffle-DSL-generated `JSDivideNodeGen` subclass — confirmed by also running `javap` on
 * `JSDivideNodeGen`, which has no `doInt1`/`doDouble` of its own and only ever calls the
 * inherited ones via `invokevirtual`) showed all four candidate methods are *instance*
 * methods (`protected`, non-static), so the `redirectCallsTo` approach used by
 * `example-mod`'s `CursedAdditionTransformer` (which only works for `INVOKESTATIC` call
 * sites) doesn't apply here — this mod uses `atMethodEntry` on `JSDivideNode` itself
 * instead, the same pattern `null-safe-mod`'s `NullSafeTransformer` uses:
 *
 * ```
 * protected int doInt1(int, int):            // descriptor (II)I, this=0 a=1 b=2
 *     iload_1; iload_2; irem; ifne L1; iload_1; iload_2; idiv; ireturn
 *     L1: new ArithmeticException; dup; invokespecial <init>; athrow
 *
 * protected int doInt2(int, int):            // descriptor (II)I
 *     aload_0; iload_1; iload_2; invokevirtual doInt1:(II)I; ireturn
 *
 * protected int doInt3(int, int):            // descriptor (II)I
 *     aload_0; iload_1; iload_2; invokevirtual doInt1:(II)I; ireturn
 *
 * protected double doDouble(double, double): // descriptor (DD)D, this=0 a=1-2 b=3-4
 *     dload_1; dload_3; ddiv; dreturn
 * ```
 *
 * `doInt1` only takes the fast int path when the division is exact (zero remainder),
 * throwing `ArithmeticException` otherwise so the interpreter falls back to `doDouble`.
 * `doInt2`/`doInt3` just delegate to `doInt1` via `invokevirtual` — patching `doInt1`
 * alone covers all three int-typed call sites `JSDivideNodeGen` has.
 *
 * The patch:
 *  - `doInt1`: replaced wholesale via `atMethodEntry` with `iload_1; iload_2; idiv;
 *    ireturn` — Java's `/` on `int` already truncates toward zero, so this is a drop-in
 *    C-style divide with no exact-division precondition (and, as a side effect, no more
 *    `ArithmeticException`-driven fallback to `doDouble` for inexact int/int divisions —
 *    they're now handled directly here instead).
 *  - `doDouble`: replaced wholesale via `atMethodEntry` with `a / b` followed by
 *    truncation toward zero (`Math.floor` for non-negative results, `Math.ceil` for
 *    negative ones) so mixed int/double or double/double divisions still truncate
 *    consistently with the int fast path.
 *
 * Both replacements are unconditional (`ireturn`/`dreturn` right at method entry), so the
 * original method bodies become dead code below the injected instructions. Initially this
 * seemed safe to leave alone — nothing jumps there anymore, so surely the verifier doesn't
 * care? Empirically, it does: a standalone verification harness (`defineClass` on the
 * patched bytecode through a throwaway `ClassLoader`, then loading `JSDivideNodeGen`
 * through the *same* loader so it actually resolves the patched superclass — an earlier,
 * subtly wrong version of that harness let the parent loader's normal delegation load an
 * *unpatched* copy first, which silently made the patch look like a no-op) reproduced a
 * `VerifyError: Expecting a stack map frame` at the first original instruction immediately
 * following the injected `ireturn`/`dreturn`. Both replacement blocks therefore end with an
 * explicit `F_NEW` frame — identical in shape to the method's own entry frame — describing
 * that (now unreachable, but still verified) fall-through point, the same
 * "new merge/branch point needs its own hand-supplied frame under `COMPUTE_MAXS`" rule
 * `null-safe-mod` documents. `doDouble`'s truncation logic additionally introduces two
 * *reachable* new branch targets of its own (the `floor`/`done` labels below), which get
 * their own `F_NEW` frames for the same reason.
 */
class IntegerDivisionTransformer : ModTransformer {
    override fun targetClasses(): Set<String> =
        setOf("com/oracle/truffle/js/nodes/binary/JSDivideNode")

    override fun transform(className: String, classfileBuffer: ByteArray): ByteArray? {
        println("[integer-division] Intercepted $className — applying patch")
        return try {
            patchClass(classfileBuffer) {
                atMethodEntry("doInt1") {
                    // return a / b; — Java's int `/` already truncates toward zero.
                    iload(1)
                    iload(2)
                    idiv()
                    ireturn()

                    // Dead code follows (the original exact-division check) — give it a
                    // frame identical to method entry so the verifier accepts it. See the
                    // class doc comment above for why this is needed.
                    raw(
                        FrameNode(
                            Opcodes.F_NEW,
                            3,
                            arrayOf<Any>(
                                "com/oracle/truffle/js/nodes/binary/JSDivideNode",
                                Opcodes.INTEGER,
                                Opcodes.INTEGER,
                            ),
                            0,
                            arrayOf<Any>(),
                        ),
                    )
                }

                atMethodEntry("doDouble") {
                    // double r = a / b;
                    // return r >= 0 ? Math.floor(r) : Math.ceil(r);
                    val ceilBranch = LabelNode()
                    val floorLabel = LabelNode()
                    val doneLabel = LabelNode()

                    // Owner class name used in every frame's `this` slot below.
                    val owner = "com/oracle/truffle/js/nodes/binary/JSDivideNode"

                    dload(1) // a
                    dload(3) // b
                    ddiv()
                    dstore(5) // r — fresh local slot 5-6 (this=0, a=1-2, b=3-4)

                    dload(5)
                    raw(InsnNode(Opcodes.DCONST_0))
                    raw(InsnNode(Opcodes.DCMPG)) // pushes 1 if r > 0 (or NaN), 0 if ==, -1 if <
                    raw(JumpInsnNode(Opcodes.IFLT, ceilBranch)) // r < 0 -> ceil

                    // Both frames below describe the same locals — `this`, `a` (DOUBLE),
                    // `b` (DOUBLE), `r` (DOUBLE) — since neither branch touches a local
                    // variable, only the operand stack differs between them.
                    fun InsnBuilder.locals(): Array<Any> = arrayOf(
                        owner,
                        Opcodes.DOUBLE,
                        Opcodes.DOUBLE,
                        Opcodes.DOUBLE,
                    )

                    // r >= 0: floor(r)
                    raw(floorLabel)
                    raw(FrameNode(Opcodes.F_NEW, 4, locals(), 0, arrayOf<Any>()))
                    dload(5)
                    raw(
                        MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "java/lang/Math",
                            "floor",
                            "(D)D",
                            false,
                        ),
                    )
                    raw(JumpInsnNode(Opcodes.GOTO, doneLabel))

                    // r < 0: ceil(r)
                    raw(ceilBranch)
                    raw(FrameNode(Opcodes.F_NEW, 4, locals(), 0, arrayOf<Any>()))
                    dload(5)
                    raw(
                        MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "java/lang/Math",
                            "ceil",
                            "(D)D",
                            false,
                        ),
                    )

                    raw(doneLabel)
                    raw(FrameNode(Opcodes.F_NEW, 4, locals(), 1, arrayOf<Any>(Opcodes.DOUBLE)))
                    dreturn()

                    // Dead code follows (the original `a / b; return;`) — give it a frame
                    // identical to method entry (locals only up to `this`/`a`/`b`; the dead
                    // code never references our temporary `r` slot) so the verifier accepts
                    // it. See the class doc comment above for why this is needed.
                    raw(
                        FrameNode(
                            Opcodes.F_NEW,
                            3,
                            arrayOf<Any>(owner, Opcodes.DOUBLE, Opcodes.DOUBLE),
                            0,
                            arrayOf<Any>(),
                        ),
                    )
                }
            }
        } catch (t: Throwable) {
            println("[integer-division] Patch FAILED, leaving class unmodified: $t")
            t.printStackTrace()
            null
        }
    }
}

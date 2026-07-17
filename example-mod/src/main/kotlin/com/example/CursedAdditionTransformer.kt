package com.example

import org.mycelium.modloader.ModTransformer
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode

/**
 * Example mod, and the direct descendant of the original mod-loader proof of concept:
 * patches GraalJS's `JSAddNodeGen` bytecode at class-load time so int+int addition
 * returns one extra — e.g. `1 + 1 === 3`. Demonstrates the full mod chain end to end: a
 * mod jar with a `mod.json` descriptor, loaded by the generic mod-loader agent, whose
 * `ModTransformer` rewrites bytecode of a class deep inside a bundled Truffle language
 * implementation — observable from a plain user script without touching GraalJS's own
 * source or jar.
 *
 * Target: `com.oracle.truffle.js.nodes.binary.JSAddNodeGen`, the Truffle-DSL-generated
 * subclass of `JSAddNode` that GraalJS actually instantiates and executes at runtime.
 *
 * Investigation with `javap -c -p` on js-language-25.0.0.jar showed the int-fast-path
 * addition logic itself (`Math.addExact` / a raw `iadd`) lives in the hand-written
 * `JSAddNode.doInt(int,int)` / `JSAddNode.doIntTruncate(int,int)` static methods — NOT
 * in `JSAddNodeGen` directly. `JSAddNodeGen`'s generated `execute*` methods call those via
 * `invokestatic` and use the returned `int` immediately. So rather than searching for an
 * `IADD` opcode inside `JSAddNodeGen` (there isn't one — the add happens in the callee),
 * this patches every call site: right after each `invokestatic` to
 * `JSAddNode.doInt`/`doIntTruncate`, it splices in `ICONST_1` + `IADD`, incrementing
 * whatever int result GraalJS's own arithmetic just produced.
 */
class CursedAdditionTransformer : ModTransformer {
    override fun targetClasses(): Set<String> =
        setOf("com/oracle/truffle/js/nodes/binary/JSAddNodeGen")

    override fun transform(className: String, classfileBuffer: ByteArray): ByteArray? {
        println("[cursed-addition] Intercepted $className — applying patch")
        return try {
            patchAddResult(classfileBuffer)
        } catch (t: Throwable) {
            println("[cursed-addition] Patch FAILED, leaving class unmodified: $t")
            t.printStackTrace()
            null
        }
    }

    companion object {
        private const val TARGET_OWNER = "com/oracle/truffle/js/nodes/binary/JSAddNode"
        private val TARGET_METHODS = setOf("doInt", "doIntTruncate")

        fun patchAddResult(classfileBuffer: ByteArray): ByteArray {
            val classNode = ClassNode()
            val classReader = ClassReader(classfileBuffer)
            // Keep existing StackMapTable frames (don't SKIP_FRAMES): our insertion
            // doesn't add any new branch targets or change the stack shape after the
            // patched call sites (an int is still on top before and after
            // ICONST_1/IADD), so the original frames stay valid — COMPUTE_MAXS is
            // enough, no need for the (classpath-sensitive) COMPUTE_FRAMES.
            classReader.accept(classNode, 0)

            var patchCount = 0
            for (method in classNode.methods) {
                val iter = method.instructions.iterator()
                while (iter.hasNext()) {
                    val insn = iter.next()
                    if (insn is MethodInsnNode &&
                        insn.opcode == Opcodes.INVOKESTATIC &&
                        insn.owner == TARGET_OWNER &&
                        insn.name in TARGET_METHODS
                    ) {
                        // Stack right after the call: [..., intResult]. Push 1 and add
                        // it in — net stack shape is unchanged (still one int on top),
                        // so this doesn't require recomputing stack map frames, just
                        // local/stack maxs.
                        method.instructions.insert(insn, InsnNode(Opcodes.IADD))
                        method.instructions.insert(insn, InsnNode(Opcodes.ICONST_1))
                        patchCount++
                        println("[cursed-addition] Patched call site to ${insn.name} in method ${method.name}${method.desc}")
                    }
                }
            }

            if (patchCount == 0) {
                println("[cursed-addition] WARNING: no call sites matched — class shape may have changed, leaving unmodified")
                return classfileBuffer
            }

            println("[cursed-addition] Patched $patchCount call site(s)")
            val classWriter = ClassWriter(ClassWriter.COMPUTE_MAXS)
            classNode.accept(classWriter)
            return classWriter.toByteArray()
        }
    }
}

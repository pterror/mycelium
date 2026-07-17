package org.mycelium.modloader

import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode

/**
 * Proof-of-concept `-javaagent:` entry point (see [build.gradle.kts]'s `Premain-Class`
 * manifest attribute). Demonstrates the full "mod loader" chain end to end: a
 * transforming classloader hooks class loading, ASM rewrites bytecode of a class deep
 * inside a bundled Truffle language implementation, and the patched behavior is
 * observable from a plain user script — without touching GraalJS's own source or jar.
 */
fun premain(agentArgs: String?, inst: Instrumentation) {
    println("[mod-loader] Agent loaded — patching JSAddNodeGen to make 1+1=3")
    inst.addTransformer(AddNodeTransformer())
}

/**
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
class AddNodeTransformer : ClassFileTransformer {
    override fun transform(
        loader: ClassLoader?,
        className: String?,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray,
    ): ByteArray? {
        if (className != "com/oracle/truffle/js/nodes/binary/JSAddNodeGen") return null

        println("[mod-loader] Intercepted JSAddNodeGen — applying patch")
        return try {
            patchAddResult(classfileBuffer)
        } catch (t: Throwable) {
            println("[mod-loader] Patch FAILED, leaving class unmodified: $t")
            t.printStackTrace()
            null
        }
    }
}

private const val TARGET_OWNER = "com/oracle/truffle/js/nodes/binary/JSAddNode"
private val TARGET_METHODS = setOf("doInt", "doIntTruncate")

fun patchAddResult(classfileBuffer: ByteArray): ByteArray {
    val classNode = ClassNode()
    val classReader = ClassReader(classfileBuffer)
    // Keep existing StackMapTable frames (don't SKIP_FRAMES): our insertion doesn't add
    // any new branch targets or change the stack shape after the patched call sites (an
    // int is still on top before and after ICONST_1/IADD), so the original frames stay
    // valid — COMPUTE_MAXS is enough, no need for the (classpath-sensitive) COMPUTE_FRAMES.
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
                // Stack right after the call: [..., intResult]. Push 1 and add it in —
                // net stack shape is unchanged (still one int on top), so this doesn't
                // require recomputing stack map frames, just local/stack maxs.
                method.instructions.insert(insn, InsnNode(Opcodes.IADD))
                method.instructions.insert(insn, InsnNode(Opcodes.ICONST_1))
                patchCount++
                println("[mod-loader] Patched call site to ${insn.name} in method ${method.name}${method.desc}")
            }
        }
    }

    if (patchCount == 0) {
        println("[mod-loader] WARNING: no call sites matched — class shape may have changed, leaving unmodified")
        return classfileBuffer
    }

    println("[mod-loader] Patched $patchCount call site(s)")
    val classWriter = ClassWriter(ClassWriter.COMPUTE_MAXS)
    classNode.accept(classWriter)
    return classWriter.toByteArray()
}

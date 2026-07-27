package org.mycelium.modloader

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode

/**
 * A small DSL for the common ASM patching patterns mod authors need — splicing
 * instructions around call sites, redirecting calls to a replacement method, and
 * injecting at method entry/exit — without hand-rolling `ClassReader`/`ClassWriter`
 * boilerplate or `InsnList` bookkeeping every time.
 *
 * This is a convenience layer, not a framework: anything it doesn't cover is still
 * reachable by dropping down to raw ASM (see [InsnBuilder.raw] and [ClassPatcher.classNode]).
 *
 * See `org.mycelium.example.CursedAdditionTransformer` (in the `example-mod` module) for
 * a worked example using both the
 * [ClassPatcher.redirectCallsTo] and [ClassPatcher.afterCallsTo] approaches side by side.
 */

/**
 * Entry point: parses [classfileBuffer] into a [ClassNode], runs [block] against a
 * [ClassPatcher] wrapping it, and re-serializes. Returns `null` (meaning "no change,
 * leave the original bytecode untouched") if [block] didn't end up patching anything —
 * this mirrors the [ModTransformer.transform] contract, so most transformers can just
 * `return patchClass(classfileBuffer) { ... }` directly.
 */
fun patchClass(classfileBuffer: ByteArray, block: ClassPatcher.() -> Unit): ByteArray? {
    val classNode = ClassNode()
    val classReader = ClassReader(classfileBuffer)
    // EXPAND_FRAMES: normalizes every StackMapTable entry (including ones this class
    // doesn't touch) into the uniform "full frame" (F_NEW) representation instead of
    // leaving them in the class file's original compressed encoding (F_SAME/F_APPEND/
    // F_CHOP/...). Compressed frames encode their offset_delta implicitly via which frame
    // "family" was chosen at compile time, relative to whatever the previous frame in the
    // method happened to be — splicing new instructions (and, worse, new branch targets
    // that need their OWN new frames — see [ClassPatcher.atMethodEntry] docs for the
    // "expecting a stackmap frame at branch target" failure mode this avoids) shifts byte
    // offsets and can shift which frame is "previous," silently invalidating a compressed
    // frame's meaning. Expanded frames carry the full local/stack type lists explicitly, so
    // they stay correct regardless of what mod authors splice in around them, and
    // ClassWriter re-compresses them automatically on output (independent of read-side
    // expansion) since [ClassWriter] here still runs in `COMPUTE_MAXS` mode, not
    // `COMPUTE_FRAMES` — see the comment on that below for why.
    classReader.accept(classNode, ClassReader.EXPAND_FRAMES)

    val patcher = ClassPatcher(classNode)
    patcher.block()

    if (patcher.patchCount == 0) {
        return null
    }

    println("[mod-loader] Patched ${patcher.patchCount} call site(s) in ${classNode.name.substringAfterLast('/')}")

    val classWriter = ClassWriter(ClassWriter.COMPUTE_MAXS)
    classNode.accept(classWriter)
    return classWriter.toByteArray()
}

/** Whether a [MethodInsnNode] is one of the "call" opcodes ASM uses for method invocations. */
private fun isInvoke(opcode: Int): Boolean =
    opcode == Opcodes.INVOKESTATIC ||
        opcode == Opcodes.INVOKEVIRTUAL ||
        opcode == Opcodes.INVOKESPECIAL ||
        opcode == Opcodes.INVOKEINTERFACE

/**
 * Wraps a parsed [ClassNode] with helpers for locating and splicing around call sites.
 * Instances are created (and [patchCount] read back) exclusively by [patchClass] — mod
 * authors only see this inside the `block` lambda passed to it.
 */
class ClassPatcher(val classNode: ClassNode) {
    /** How many call sites (or entry/exit points) were patched across all helper calls so far. */
    var patchCount = 0
        private set

    /** All call instructions across every method matching `owner` + one of `methodNames`. */
    private fun matchingCallSites(owner: String, methodNames: Array<out String>): List<Pair<MethodNode, MethodInsnNode>> {
        val names = methodNames.toSet()
        val result = mutableListOf<Pair<MethodNode, MethodInsnNode>>()
        for (method in classNode.methods) {
            for (insn in method.instructions) {
                if (insn is MethodInsnNode && isInvoke(insn.opcode) && insn.owner == owner && insn.name in names) {
                    result += method to insn
                }
            }
        }
        return result
    }

    /**
     * Finds every call site matching `owner` + one of `methodNames` across all methods of
     * this class, and splices the instructions built by [block] immediately AFTER each one.
     * Useful for post-processing a call's return value (see [CursedAdditionTransformer]'s
     * `doIntTruncate` patch, which adds 1 to the freshly-returned int).
     */
    fun afterCallsTo(owner: String, vararg methodNames: String, block: InsnBuilder.() -> Unit) {
        val sites = matchingCallSites(owner, methodNames)
        for ((method, insn) in sites) {
            val builder = InsnBuilder()
            builder.block()
            method.instructions.insert(insn, builder.instructions)
            patchCount++
            println("[mod-loader] Spliced instructions after call to ${insn.owner}.${insn.name} in method ${method.name}${method.desc}")
        }
    }

    /**
     * Same as [afterCallsTo], but splices the instructions built by [block] immediately
     * BEFORE each matching call site.
     */
    fun beforeCallsTo(owner: String, vararg methodNames: String, block: InsnBuilder.() -> Unit) {
        val sites = matchingCallSites(owner, methodNames)
        for ((method, insn) in sites) {
            val builder = InsnBuilder()
            builder.block()
            method.instructions.insertBefore(insn, builder.instructions)
            patchCount++
            println("[mod-loader] Spliced instructions before call to ${insn.owner}.${insn.name} in method ${method.name}${method.desc}")
        }
    }

    /**
     * Finds every call site matching `owner` + one of `methodNames` and returns a
     * [RedirectBuilder] to point them at a different method — typically a mod-provided
     * static replacement (see [ModLoaderAgent]'s `appendToSystemClassLoaderSearch`, which is
     * what makes mod classes visible to redirect targets on the application classloader).
     */
    fun redirectCallsTo(owner: String, vararg methodNames: String): RedirectBuilder {
        val sites = matchingCallSites(owner, methodNames)
        return RedirectBuilder(this, sites)
    }

    /**
     * Records one successful patch. Called by [RedirectBuilder.to] on this same instance.
     * Also public so mod authors who drop down to raw [classNode] iteration (e.g. to inject
     * differently-shaped instructions per overload, which the uniform [atMethodEntry] can't
     * express) can still register their patch(es) with [patchCount] — otherwise [patchClass]
     * would conclude nothing changed and discard the (correctly) rewritten bytecode.
     */
    fun recordPatch() {
        patchCount++
    }

    /**
     * Injects the instructions built by [block] at the very start of every method named
     * [methodName] (across all overloads — matches by name only).
     */
    fun atMethodEntry(methodName: String, block: InsnBuilder.() -> Unit) {
        for (method in classNode.methods) {
            if (method.name != methodName) continue
            val builder = InsnBuilder()
            builder.block()
            method.instructions.insert(builder.instructions)
            patchCount++
            println("[mod-loader] Injected at entry of method ${method.name}${method.desc}")
        }
    }

    /**
     * Injects the instructions built by [block] immediately before every `xRETURN`/`RETURN`
     * instruction in every method named [methodName] (across all overloads — matches by name
     * only). Note: the injected instructions run with whatever value is already on the stack
     * about to be returned still there — [block] should leave the stack shape unchanged if it
     * needs the original return path to keep working (use [InsnBuilder.dup]/[InsnBuilder.pop]
     * as needed, or replace the return outright via [InsnBuilder.raw]).
     */
    fun atMethodReturn(methodName: String, block: InsnBuilder.() -> Unit) {
        for (method in classNode.methods) {
            if (method.name != methodName) continue
            val returns = method.instructions.filter { it.opcode in RETURN_OPCODES }
            for (returnInsn in returns) {
                val builder = InsnBuilder()
                builder.block()
                method.instructions.insertBefore(returnInsn, builder.instructions)
                patchCount++
            }
            if (returns.isNotEmpty()) {
                println("[mod-loader] Injected at ${returns.size} return site(s) of method ${method.name}${method.desc}")
            }
        }
    }

    private companion object {
        val RETURN_OPCODES = setOf(
            Opcodes.RETURN,
            Opcodes.IRETURN,
            Opcodes.LRETURN,
            Opcodes.FRETURN,
            Opcodes.DRETURN,
            Opcodes.ARETURN,
        )
    }
}

private fun InsnList.filter(predicate: (AbstractInsnNode) -> Boolean): List<AbstractInsnNode> {
    val result = mutableListOf<AbstractInsnNode>()
    for (insn in this) {
        if (predicate(insn)) result += insn
    }
    return result
}

/**
 * Redirects a previously-located set of call sites (see [ClassPatcher.redirectCallsTo]) to a
 * different method. Only the [MethodInsnNode.owner]/[MethodInsnNode.name] are changed — the
 * opcode and descriptor are left as-is, so [to]'s replacement method must have the exact same
 * descriptor (parameter types + return type) as the original call, or a warning is logged.
 */
class RedirectBuilder internal constructor(
    private val patcher: ClassPatcher,
    private val sites: List<Pair<MethodNode, MethodInsnNode>>,
) {
    /**
     * Redirects every matched call site to `newOwner.newName` (a static method, called with
     * INVOKESTATIC — `newOwner` uses `/` separators). The descriptor of the call site is left
     * untouched, so `newName` must accept/return exactly what the original method did.
     */
    fun to(newOwner: String, newName: String) {
        for ((method, insn) in sites) {
            val originalOwner = insn.owner
            val originalName = insn.name
            verifyDescriptor(newOwner, newName, insn.desc)
            insn.owner = newOwner
            insn.name = newName
            insn.opcode = Opcodes.INVOKESTATIC
            insn.itf = false
            patcher.recordPatch()
            println(
                "[mod-loader] Redirected call $originalOwner.$originalName -> $newOwner.$newName " +
                    "in method ${method.name}${method.desc} (desc ${insn.desc})",
            )
        }
        if (sites.isEmpty()) {
            println("[mod-loader] Warning: redirectCallsTo(...).to($newOwner, $newName) matched no call sites")
        }
    }

    /**
     * Best-effort check that a same-descriptor replacement method actually exists on
     * [newOwner] — resolved via reflection so it works whether or not the class is loaded
     * yet. Only warns (never fails/throws): the target class may not be visible on this
     * classloader at patch time (e.g. before [java.lang.instrument.Instrumentation]
     * .appendToSystemClassLoaderSearch has taken effect for this class' loader), in which
     * case the check is silently skipped rather than producing a false-positive warning.
     */
    private fun verifyDescriptor(newOwner: String, newName: String, expectedDesc: String) {
        val binaryName = newOwner.replace('/', '.')
        val clazz = try {
            Class.forName(binaryName, false, javaClass.classLoader)
        } catch (_: Throwable) {
            return
        }
        val expectedType = Type.getMethodType(expectedDesc)
        val match = clazz.declaredMethods.any { m ->
            m.name == newName && Type.getMethodDescriptor(m) == expectedType.descriptor
        }
        if (!match) {
            println(
                "[mod-loader] Warning: no method $newOwner.$newName with descriptor " +
                    "$expectedDesc found — redirect target's signature may not match the original call",
            )
        }
    }
}

/**
 * Builds an [InsnList] using short, opcode-shaped helper calls instead of constructing
 * `*InsnNode` instances by hand. Used by [ClassPatcher.afterCallsTo]/[beforeCallsTo]/
 * [atMethodEntry]/[atMethodReturn] blocks.
 */
class InsnBuilder {
    val instructions: InsnList = InsnList()

    /** Pushes an int constant, picking the most compact instruction for its range. */
    fun iconst(value: Int) {
        val insn: AbstractInsnNode = when (value) {
            -1 -> InsnNode(Opcodes.ICONST_M1)
            0 -> InsnNode(Opcodes.ICONST_0)
            1 -> InsnNode(Opcodes.ICONST_1)
            2 -> InsnNode(Opcodes.ICONST_2)
            3 -> InsnNode(Opcodes.ICONST_3)
            4 -> InsnNode(Opcodes.ICONST_4)
            5 -> InsnNode(Opcodes.ICONST_5)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> IntInsnNode(Opcodes.BIPUSH, value)
            in Short.MIN_VALUE..Short.MAX_VALUE -> IntInsnNode(Opcodes.SIPUSH, value)
            else -> LdcInsnNode(value)
        }
        instructions.add(insn)
    }

    fun iadd() = instructions.add(InsnNode(Opcodes.IADD))
    fun isub() = instructions.add(InsnNode(Opcodes.ISUB))
    fun imul() = instructions.add(InsnNode(Opcodes.IMUL))
    fun idiv() = instructions.add(InsnNode(Opcodes.IDIV))
    fun ineg() = instructions.add(InsnNode(Opcodes.INEG))
    fun ireturn() = instructions.add(InsnNode(Opcodes.IRETURN))
    fun areturn() = instructions.add(InsnNode(Opcodes.ARETURN))
    fun pop() = instructions.add(InsnNode(Opcodes.POP))
    fun dup() = instructions.add(InsnNode(Opcodes.DUP))
    fun swap() = instructions.add(InsnNode(Opcodes.SWAP))

    fun aload(index: Int) = instructions.add(VarInsnNode(Opcodes.ALOAD, index))
    fun iload(index: Int) = instructions.add(VarInsnNode(Opcodes.ILOAD, index))
    fun astore(index: Int) = instructions.add(VarInsnNode(Opcodes.ASTORE, index))
    fun istore(index: Int) = instructions.add(VarInsnNode(Opcodes.ISTORE, index))

    /** Pushes a constant. Accepts [String], [Int], [Long], [Float], [Double], or ASM [Type]. */
    fun ldc(value: Any) = instructions.add(LdcInsnNode(value))

    fun invokestatic(owner: String, name: String, desc: String) =
        instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, owner, name, desc, false))

    fun invokevirtual(owner: String, name: String, desc: String) =
        instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, name, desc, false))

    fun getstatic(owner: String, name: String, desc: String) =
        instructions.add(FieldInsnNode(Opcodes.GETSTATIC, owner, name, desc))

    fun putstatic(owner: String, name: String, desc: String) =
        instructions.add(FieldInsnNode(Opcodes.PUTSTATIC, owner, name, desc))

    fun getfield(owner: String, name: String, desc: String) =
        instructions.add(FieldInsnNode(Opcodes.GETFIELD, owner, name, desc))

    fun putfield(owner: String, name: String, desc: String) =
        instructions.add(FieldInsnNode(Opcodes.PUTFIELD, owner, name, desc))

    /** Escape hatch: appends a raw, hand-built instruction node for anything not covered above. */
    fun raw(insn: AbstractInsnNode) = instructions.add(insn)
}

package org.mycelium.ptc

import org.mycelium.modloader.InsnBuilder
import org.mycelium.modloader.ModTransformer
import org.mycelium.modloader.patchClass
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode

/**
 * Proper tail calls (PTC) via a trampoline — the most invasive mod in this repo so far.
 * Prevents stack overflow on tail-recursive **self**-calls (`function f(...) { ... return
 * f(...); }`) by never letting the JVM/Truffle stack grow for them in the first place.
 *
 * ## Investigation summary (`javap -c -p` on `js-language-25.0.0.jar`)
 *
 * The starting assumption — patch `FunctionRootNode.executeInRealm` to loop, and patch
 * `JSFunctionCallNode`/`DirectJSFunctionCacheNode` to short-circuit a call in tail
 * position — turned out to be exactly right, but the devil is in finding the *one* choke
 * point each half of that needs, since GraalJS's call dispatch is a wide inline cache with
 * many specialized node shapes:
 *
 *  - **Detecting tail position.** `ReturnNode.createTerminalPositionReturn` (used by the
 *    parser when `isInTerminalPosition()` holds) builds a
 *    `ReturnNode$TerminalPositionReturnNode`, whose `execute(VirtualFrame)` is refreshingly
 *    trivial: `return expression.execute(frame);` — no `ReturnException` involved at all
 *    (that's `ReturnNode$ReturnValueInExceptionNode`, used for *non*-terminal returns,
 *    which unwind via a thrown `ReturnException` instead). So "is this return in tail
 *    position" is already known statically by the node *type* — the parser did the work.
 *    What's missing is a way to tell the *callee node* (a `JSFunctionCallNode`) that it is
 *    specifically the one being executed as `expression` here, not, say, an argument of
 *    some other call. See [patchTerminalPositionReturnNode].
 *
 *  - **The actual call choke point.** `JSFunctionCallNode` has ~30 nested node classes
 *    (`Call0Node`/`Call1Node`/`CallNNode`/`CallSpreadNode`, the parallel `Invoke*Node`
 *    family for method calls, and a whole `*CacheNode` inline-cache hierarchy —
 *    `DirectJSFunctionCacheNode`, `GenericJSFunctionCacheNode`, `BoundFunctionDataCacheNode`,
 *    `JSProxyCallCacheNode`, `ForeignCallNode`, ...). Chasing the cache hierarchy down to
 *    `DirectJSFunctionCacheNode.executeCall` (which does the real
 *    `DirectCallNode.call(args)`, the actual Truffle-stack-growing operation) looked
 *    promising at first, but every one of those specialized cache nodes is reached through
 *    exactly one place: `CallNode.execute`/`InvokeNode.execute` (the base classes every
 *    `Call*Node`/`Invoke*Node` shares) both end by calling `this.executeCall(args)` — a
 *    concrete, **non-overridden** method declared directly on the abstract
 *    `JSFunctionCallNode` base class itself, which internally walks the cache-node chain.
 *    `javap` confirms none of `Call0Node`/`CallSpreadNode`/`InvokeNode`/`Invoke0Node`/
 *    `Invoke1Node`/`InvokeNNode`/`InvokeSpreadNode` override `executeCall` — so
 *    `JSFunctionCallNode.executeCall(Object[])` is the single choke point for *every* JS
 *    function/method call, regardless of arity or call shape. See
 *    [patchJSFunctionCallNode].
 *
 *  - **The trampoline driver.** `FunctionRootNode.executeInRealm(VirtualFrame)` was, before
 *    this patch, a one-liner: `return body.execute(frame);`. That's the natural place to
 *    loop. See [patchFunctionRootNode].
 *
 * ## The mechanism, end to end
 *
 * 1. [patchTerminalPositionReturnNode] makes `TerminalPositionReturnNode.execute` check,
 *    *before* evaluating its `expression` child, whether that child is a
 *    `JSFunctionCallNode` **and** the enclosing root is a plain `FunctionRootNode` (not a
 *    constructor/generator/async root — trampolining those is out of scope for this PoC,
 *    and getting it wrong there would be a correctness bug, not just a missed
 *    optimization — see [TailCallState.markTailPosition]'s doc for why). If so, it records
 *    that exact call-node instance in [TailCallState.markTailPosition] before executing it.
 *
 * 2. [patchJSFunctionCallNode] makes `JSFunctionCallNode.executeCall` check, as the very
 *    first thing it does, whether `this` is the node [TailCallState.consumeIfMatch] was
 *    told to expect. If so, it skips the real call entirely and returns a [TailCall]
 *    sentinel wrapping the already-computed `Object[]` call arguments (function object,
 *    receiver/new-target, user args — GraalJS's `JSArguments` packed layout) instead.
 *
 * 3. That sentinel is an ordinary `Object` as far as everything between the call node and
 *    the function body's root is concerned — it just falls out through
 *    `TerminalPositionReturnNode.execute`'s `return expression.execute(frame)` and the rest
 *    of the (typically trivial) enclosing statement machinery as the function's return
 *    value would have. It's [patchFunctionRootNode] — patched into
 *    `FunctionRootNode.executeInRealm` — that recognizes it and loops: as long as the
 *    result is a [TailCall], hand it to [TailCallState.trampolineStep], which either
 *    reuses the *same* `VirtualFrame` (self-recursion — the actual trampoline, see that
 *    method's doc) or falls back to a real, bounded `CallTarget.call(...)` (anything else).
 *
 * ## Scope and limitations (read before relying on this for deep recursion)
 *
 * - Only **direct self tail-recursion** — `return f(...)` written literally inside `f`
 *   itself — gets the actual stack-frame-free trampoline treatment. `return f(...)` where
 *   `f` is a *different* function (including **mutual** recursion, e.g. `isEven`/`isOdd`
 *   calling each other) still goes through this mod's machinery but falls back to an
 *   ordinary `CallTarget.call(...)` per hop in [TailCallState.trampolineStep] — each such
 *   hop still costs one bounded Java stack frame, so deep-enough mutual recursion can still
 *   overflow. A general cross-function trampoline would need every `FunctionRootNode` to
 *   share one common trampoline "driver" sitting below all of them on the stack, which is
 *   a much bigger restructuring than a bytecode patch — out of scope here.
 * - Constructors, generators, and async functions are deliberately excluded (their root
 *   node isn't `FunctionRootNode`, or their control flow doesn't run through
 *   `TerminalPositionReturnNode` the same way) — ordinary (non-trampolined, safe) behavior
 *   for those.
 * - A tail call whose argument count doesn't match the current frame's (e.g. a spread call
 *   `return f(...arr)` where `arr`'s length differs from `f`'s declared arity) falls back
 *   to the ordinary-call path in [TailCallState.trampolineStep] rather than trampolining —
 *   safe, just not optimized.
 */
class TailCallTransformer : ModTransformer {
    override fun targetClasses(): Set<String> = setOf(
        "com/oracle/truffle/js/nodes/control/ReturnNode\$TerminalPositionReturnNode",
        "com/oracle/truffle/js/nodes/function/JSFunctionCallNode",
        "com/oracle/truffle/js/nodes/function/FunctionRootNode",
    )

    override fun transform(className: String, classfileBuffer: ByteArray): ByteArray? {
        println("[ptc] Intercepted $className — applying patch")
        return try {
            when (className) {
                "com/oracle/truffle/js/nodes/control/ReturnNode\$TerminalPositionReturnNode" ->
                    patchTerminalPositionReturnNode(classfileBuffer)
                "com/oracle/truffle/js/nodes/function/JSFunctionCallNode" ->
                    patchJSFunctionCallNode(classfileBuffer)
                "com/oracle/truffle/js/nodes/function/FunctionRootNode" ->
                    patchFunctionRootNode(classfileBuffer)
                else -> null
            }
        } catch (t: Throwable) {
            println("[ptc] Patch FAILED, leaving class unmodified: $t")
            t.printStackTrace()
            null
        }
    }

    /**
     * Patches `ReturnNode$TerminalPositionReturnNode.execute(VirtualFrame)` — see the class
     * doc comment (step 1) for the full picture. `execute`'s locals are `this` (index 0)
     * and `frame` (index 1); nothing new is stored, so the merge frame at [skip] is
     * identical to the method's own entry frame.
     */
    private fun patchTerminalPositionReturnNode(classfileBuffer: ByteArray): ByteArray? =
        patchClass(classfileBuffer) {
            atMethodEntry("execute") {
                val skip = LabelNode()

                fun InsnBuilder.frameLikeMethodEntry() = raw(
                    FrameNode(
                        Opcodes.F_NEW,
                        2,
                        arrayOf<Any>(
                            "com/oracle/truffle/js/nodes/control/ReturnNode\$TerminalPositionReturnNode",
                            "com/oracle/truffle/api/frame/VirtualFrame",
                        ),
                        0,
                        arrayOf(),
                    ),
                )

                // if (expression instanceof JSFunctionCallNode
                //     && getRootNode() instanceof FunctionRootNode) {
                //     TailCallState.markTailPosition(expression);
                // }
                // (falls through to the original `return expression.execute(frame)` either way)
                aload(0)
                getfield(
                    "com/oracle/truffle/js/nodes/control/ReturnNode",
                    "expression",
                    "Lcom/oracle/truffle/js/nodes/JavaScriptNode;",
                )
                raw(TypeInsnNode(Opcodes.INSTANCEOF, "com/oracle/truffle/js/nodes/function/JSFunctionCallNode"))
                raw(JumpInsnNode(Opcodes.IFEQ, skip))

                aload(0)
                raw(
                    MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "com/oracle/truffle/api/nodes/Node",
                        "getRootNode",
                        "()Lcom/oracle/truffle/api/nodes/RootNode;",
                        false,
                    ),
                )
                raw(TypeInsnNode(Opcodes.INSTANCEOF, "com/oracle/truffle/js/nodes/function/FunctionRootNode"))
                raw(JumpInsnNode(Opcodes.IFEQ, skip))

                aload(0)
                getfield(
                    "com/oracle/truffle/js/nodes/control/ReturnNode",
                    "expression",
                    "Lcom/oracle/truffle/js/nodes/JavaScriptNode;",
                )
                invokestatic("org/mycelium/ptc/TailCallState", "markTailPosition", "(Ljava/lang/Object;)V")

                raw(skip)
                frameLikeMethodEntry()
            }
        }

    /**
     * Patches `JSFunctionCallNode.executeCall(Object[])` — see the class doc comment
     * (step 2). Locals: `this` (index 0), `args` (index 1, `Object[]`); again nothing new
     * is stored before falling through to the original body, so the merge frame at
     * [notTail] matches method entry.
     */
    private fun patchJSFunctionCallNode(classfileBuffer: ByteArray): ByteArray? =
        patchClass(classfileBuffer) {
            atMethodEntry("executeCall") {
                val notTail = LabelNode()

                fun InsnBuilder.frameLikeMethodEntry() = raw(
                    FrameNode(
                        Opcodes.F_NEW,
                        2,
                        arrayOf<Any>(
                            "com/oracle/truffle/js/nodes/function/JSFunctionCallNode",
                            "[Ljava/lang/Object;",
                        ),
                        0,
                        arrayOf(),
                    ),
                )

                // if (TailCallState.consumeIfMatch(this)) return new TailCall(args);
                aload(0)
                invokestatic("org/mycelium/ptc/TailCallState", "consumeIfMatch", "(Ljava/lang/Object;)Z")
                raw(JumpInsnNode(Opcodes.IFEQ, notTail))

                raw(TypeInsnNode(Opcodes.NEW, "org/mycelium/ptc/TailCall"))
                dup()
                aload(1)
                raw(
                    MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        "org/mycelium/ptc/TailCall",
                        "<init>",
                        "([Ljava/lang/Object;)V",
                        false,
                    ),
                )
                areturn()

                raw(notTail)
                frameLikeMethodEntry()
            }
        }

    /**
     * Patches `FunctionRootNode.executeInRealm(VirtualFrame)` — see the class doc comment
     * (step 3). Unlike the two splices above, this replaces the *entire* method body
     * (previously a one-liner: `return body.execute(frame);`) with a trampoline loop, since
     * there's no sensible way to express "loop around the rest of the method" as a
     * prologue/epilogue splice — so this drops to raw `classNode.methods` iteration (the
     * same escape hatch `null-safe-mod`'s `ReadElementNode` patch uses) instead of the
     * `atMethodEntry`/`atMethodReturn` DSL helpers.
     *
     * Equivalent Java:
     * ```java
     * protected Object executeInRealm(VirtualFrame frame) {
     *     Object result = body.execute(frame);
     *     while (result instanceof TailCall) {
     *         result = TailCallState.trampolineStep(this, body, frame, (TailCall) result);
     *     }
     *     return result;
     * }
     * ```
     * Locals: `this` (0), `frame` (1), `result` (2, newly introduced — `Object`). Both the
     * loop-condition check and the loop-end merge point are reached only via jumps with an
     * empty operand stack and this same three-slot local layout, so both get an identical
     * hand-written `StackMapTable` frame (`ClassWriter` here runs in `COMPUTE_MAXS`, not
     * `COMPUTE_FRAMES` — see `patchClass`'s doc comment in `PatchDsl.kt`).
     */
    private fun patchFunctionRootNode(classfileBuffer: ByteArray): ByteArray? =
        patchClass(classfileBuffer) {
            for (method in classNode.methods) {
                if (method.name != "executeInRealm") continue

                method.instructions.clear()
                method.tryCatchBlocks.clear()
                method.localVariables?.clear()

                val loopStart = LabelNode()
                val loopEnd = LabelNode()

                fun loopFrame() = FrameNode(
                    Opcodes.F_NEW,
                    3,
                    arrayOf<Any>(
                        "com/oracle/truffle/js/nodes/function/FunctionRootNode",
                        "com/oracle/truffle/api/frame/VirtualFrame",
                        "java/lang/Object",
                    ),
                    0,
                    arrayOf(),
                )

                val builder = InsnBuilder()
                builder.apply {
                    // Object result = body.execute(frame);
                    aload(0)
                    getfield(
                        "com/oracle/truffle/js/nodes/function/FunctionRootNode",
                        "body",
                        "Lcom/oracle/truffle/js/nodes/JavaScriptNode;",
                    )
                    aload(1)
                    raw(
                        MethodInsnNode(
                            Opcodes.INVOKEVIRTUAL,
                            "com/oracle/truffle/js/nodes/JavaScriptNode",
                            "execute",
                            "(Lcom/oracle/truffle/api/frame/VirtualFrame;)Ljava/lang/Object;",
                            false,
                        ),
                    )
                    astore(2)

                    // while (result instanceof TailCall) {
                    raw(loopStart)
                    raw(loopFrame())
                    aload(2)
                    raw(TypeInsnNode(Opcodes.INSTANCEOF, "org/mycelium/ptc/TailCall"))
                    raw(JumpInsnNode(Opcodes.IFEQ, loopEnd))

                    //   result = TailCallState.trampolineStep(this, body, frame, (TailCall) result);
                    aload(0) // currentRoot
                    aload(0)
                    getfield(
                        "com/oracle/truffle/js/nodes/function/FunctionRootNode",
                        "body",
                        "Lcom/oracle/truffle/js/nodes/JavaScriptNode;",
                    ) // currentBody
                    aload(1) // frame
                    aload(2)
                    raw(TypeInsnNode(Opcodes.CHECKCAST, "org/mycelium/ptc/TailCall"))
                    raw(
                        MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "org/mycelium/ptc/TailCallState",
                            "trampolineStep",
                            "(Lcom/oracle/truffle/api/nodes/RootNode;" +
                                "Lcom/oracle/truffle/js/nodes/JavaScriptNode;" +
                                "Lcom/oracle/truffle/api/frame/VirtualFrame;" +
                                "Lorg/mycelium/ptc/TailCall;)Ljava/lang/Object;",
                            false,
                        ),
                    )
                    astore(2)
                    raw(JumpInsnNode(Opcodes.GOTO, loopStart))

                    // }
                    // return result;
                    raw(loopEnd)
                    raw(loopFrame())
                    aload(2)
                    areturn()
                }

                method.instructions.add(builder.instructions)
                recordPatch()
                println("[ptc] Rewrote executeInRealm with a trampoline loop")
            }
        }
}

package org.mycelium.ptc

import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import com.oracle.truffle.js.nodes.JavaScriptNode
import com.oracle.truffle.js.runtime.JSArguments
import com.oracle.truffle.js.runtime.builtins.JSFunction
import com.oracle.truffle.js.runtime.builtins.JSFunctionObject
import com.oracle.truffle.js.runtime.objects.Undefined

/**
 * Runtime coordination point for the three bytecode splices in [TailCallTransformer].
 * Every method here is called directly (`INVOKESTATIC`) from patched GraalJS bytecode —
 * see that class's doc comment for why each of these three methods exists and exactly
 * where it's called from. Kept as a plain `object` with `@JvmStatic` methods (rather than
 * top-level functions) so the generated class has a stable, single name
 * (`org/mycelium/ptc/TailCallState`) for the patched call sites to reference.
 *
 * This class (unlike [TailCall], which is intentionally GraalJS-API-free) directly
 * depends on GraalJS/Truffle runtime types (`JSArguments`, `JSFunction`, `JSFunctionObject`,
 * `RootCallTarget`, `VirtualFrame`...) — see `ptc-mod/build.gradle.kts` for the
 * `compileOnly` dependencies this needs (`org.graalvm.truffle:truffle-api` and
 * `org.graalvm.js:js-language`, matching the version investigated via `javap`). Both
 * resolve at actual runtime from GraalJS's own classloader — this mod's jar is only ever
 * appended to the *search path*, not given its own copy of those classes (see
 * `ModLoaderAgent.appendModJarToSystemClassLoaderSearch`), so there's no risk of a
 * duplicate/incompatible class identity the way bundling ASM or `ModTransformer` would
 * cause.
 */
object TailCallState {
    /**
     * Per-thread "the next `JSFunctionCallNode.executeCall` to run, if it's this exact
     * node, is in tail position" marker. Set by the patched
     * `ReturnNode$TerminalPositionReturnNode.execute` right before it evaluates its
     * `expression` child (only when that child literally *is* a `JSFunctionCallNode` —
     * see [TailCallTransformer.patchTerminalPositionReturnNode]), and consumed
     * (test-and-cleared) by the very next `executeCall` to run.
     *
     * Identity (`===`), not just "some call is pending", is what makes this safe: a call
     * like `return f(g())` sets this to the *outer* call node (`f`'s), but `g`'s own
     * `executeCall` runs first (argument evaluation happens before the call) — `g`'s node
     * reference doesn't match what was stored, so `g` proceeds as an ordinary call, and by
     * the time `f`'s own `executeCall` runs the marker is still sitting there matching
     * `f`'s node exactly. Whatever runs in between (including a fully nested, unrelated
     * function call that itself hits a `TerminalPositionReturnNode` and overwrites this
     * ThreadLocal) can only ever cause a missed optimization (marker no longer matches by
     * the time the real tail call's `executeCall` runs) — never a wrong one, since a
     * mismatch always falls through to the ordinary call path.
     */
    private val expectedCallNode = ThreadLocal<Any?>()

    /** Called from the patched `TerminalPositionReturnNode.execute` — see class doc. */
    @JvmStatic
    fun markTailPosition(callNode: Any) {
        expectedCallNode.set(callNode)
    }

    /**
     * Called from the very start of the patched `JSFunctionCallNode.executeCall`. Always
     * clears the marker (whether or not it matched) — by the time any `executeCall` runs,
     * whatever tail-position window was open has closed, matched or not.
     */
    @JvmStatic
    fun consumeIfMatch(callNode: Any): Boolean {
        val expected = expectedCallNode.get()
        expectedCallNode.set(null)
        return expected === callNode
    }

    /**
     * The trampoline's single "hop": called in a loop from the patched
     * `FunctionRootNode.executeInRealm` (see
     * [TailCallTransformer.patchFunctionRootNode]) each time the function body's result is
     * a [TailCall] sentinel instead of a real value.
     *
     * Two cases:
     *  - **Self-recursion** (the callee's `CallTarget`'s `RootNode` is the exact same
     *    `FunctionRootNode` instance currently executing, and the argument count matches):
     *    overwrite the *current* frame's argument array **in place** — `frame.getArguments()`
     *    returns the live backing array, not a copy, so mutating its elements is visible to
     *    whatever reads them next — and re-run the function body against the same
     *    [VirtualFrame]. No new Java stack frame, no new Truffle call: this is the actual
     *    trampoline, and it's what turns unbounded self tail-recursion into a bounded loop.
     *  - **Everything else** (calling a different function, a non-`JSFunctionObject`
     *    callee, a mismatched argument count from a spread call, etc.): perform the real
     *    call via `CallTarget.call(...)` and return its result directly. This still costs
     *    one ordinary (bounded) Java stack frame per hop — mutual recursion between two
     *    *different* functions is **not** trampolined by this PoC (see the class doc on
     *    [TailCallTransformer] for why that's a materially bigger undertaking) — but
     *    correctness is preserved either way.
     */
    @JvmStatic
    fun trampolineStep(currentRoot: RootNode, currentBody: JavaScriptNode, frame: VirtualFrame, tailCall: TailCall): Any {
        @Suppress("UNCHECKED_CAST")
        val args = tailCall.args as Array<Any?>
        val callee = JSArguments.getFunctionObject(args)
        val calleeTarget: CallTarget? = if (callee is JSFunctionObject) JSFunction.getCallTarget(callee) else null

        if (calleeTarget is RootCallTarget && calleeTarget.rootNode === currentRoot) {
            val frameArgs = frame.arguments
            if (frameArgs.size == args.size) {
                System.arraycopy(args, 0, frameArgs, 0, args.size)
                return currentBody.execute(frame) ?: Undefined.instance
            }
        }

        return calleeTarget?.call(*args) ?: Undefined.instance
    }
}

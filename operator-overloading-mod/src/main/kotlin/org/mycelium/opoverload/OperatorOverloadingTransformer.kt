package org.mycelium.opoverload

import org.mycelium.modloader.ModTransformer
import org.mycelium.modloader.patchClass
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode

/**
 * Unlocks GraalJS 25.0.0's built-in (but gated) operator-overloading infrastructure.
 *
 * ## Investigation
 *
 * `js-language-25.0.0.jar` (`find ~/.gradle -name "js-language-25.0.0.jar"`) ships a
 * *complete* operator-overloading implementation:
 *
 *  - `com.oracle.truffle.js.runtime.builtins.JSOverloadedOperatorsObject` — a JS object
 *    subclass carrying an attached `OperatorSet`.
 *  - `com.oracle.truffle.js.runtime.objects.OperatorSet` — maps operator name
 *    (`"+"`, `"=="`, unary `"-"`, ...) to self/left/right implementation functions.
 *  - Every binary/unary op node (`JSAddNode`, `JSOverloadedUnaryNode`, ...) already has
 *    `hasOverloadedOperators()`/dispatch-to-`OperatorSet` checks baked in — none of that
 *    needed patching.
 *  - `com.oracle.truffle.js.builtins.OperatorsBuiltins` — implements the JS-visible
 *    `Operators(...)` constructor (`OperatorsBuiltins$OperatorsNode.doOperators`) that
 *    turns a table of operator implementations into a constructor function objects can
 *    be built from (GraalJS's now-dormant take on the old TC39 "operator overloading" /
 *    "with operators from" proposal).
 *
 * `javap -c -p` on `OperatorsBuiltins.class` showed the task's assumed gate,
 * `checkOverloadedOperatorsAllowed(JSDynamicObject, Node)`, delegates to
 * `overloadedOperatorsAllowed(JSDynamicObject)` and throws a `TypeError` ("use of
 * overloaded operators is not enabled by a `with operators from` clause") only when that
 * returns `false`. In this build `overloadedOperatorsAllowed` is compiled to a bare
 * `return true;` (`iconst_1; ireturn`) — so by the time any code reaches
 * `checkOverloadedOperatorsAllowed`, it can never actually throw. This mod still patches
 * it (see [patchOperatorsBuiltins] below) to make that explicit and future-proof against
 * GraalJS builds where the check isn't already-dead code, per the task's primary ask —
 * but on 25.0.0 it turns out to be a no-op patch in practice.
 *
 * The **real** gate is one level up. `javap -c` on `JSRealm.class` shows its global-object
 * setup only installs the `Operators` builtin at all when
 * `getContextOptions().isOperatorOverloading()` is true:
 *
 * ```
 * invokevirtual JSContextOptions.isOperatorOverloading:()Z
 * ifeq          <skip past putFunctionsFromContainer(..., OperatorsBuiltins.BUILTINS)>
 * ```
 *
 * `JSContextOptions` itself backs that with a real polyglot context option,
 * `js.operator-overloading` (`JSContextOptions.OPERATOR_OVERLOADING`), whose
 * `OptionDescriptor` (see `JSContextOptionsOptionDescriptors.class`) is declared
 * `OptionCategory.USER` / `OptionStability.EXPERIMENTAL` — a normal user-facing option
 * (no `allowExperimentalOptions(true)` needed on the polyglot `Context.Builder`, that
 * gate only applies to `EXPERT`/`INTERNAL` categories), just defaulted to `false`
 * (`iconst_0` in `JSContextOptions`'s static initializer).
 *
 * So: with the option left at its default, `Operators` is never installed as a global at
 * all, and nothing in a script can ever construct a `JSOverloadedOperatorsObject` in the
 * first place — `checkOverloadedOperatorsAllowed` never even gets a chance to matter.
 *
 * ## What this mod does
 *
 * Rather than requiring every embedder to know about and pass
 * `.option("js.operator-overloading", "true")` when building a polyglot `Context` (which
 * Mycelium's mod-loader — a pure bytecode-patching system — has no hook into), this mod
 * patches `JSContextOptions.isOperatorOverloading()` itself to unconditionally return
 * `true`. That single getter patch is what actually exposes the feature:
 * `JSRealm`'s already-existing `if (getContextOptions().isOperatorOverloading())` check
 * now always takes the "install `Operators`" branch, so every JS context gets the
 * `Operators` global for free, with no application-level changes required.
 *
 * Combined with the (belt-and-suspenders) `checkOverloadedOperatorsAllowed` no-op patch,
 * this fully unlocks the feature: `Operators({...})` becomes callable from JS, and
 * arithmetic/comparison operators on objects built from it dispatch through the
 * `OperatorSet` machinery that was already there.
 *
 * ## The JS-visible API, reverse-engineered by testing against the patched runtime
 *
 * `Operators` is a single global function, `Operators(selfTable, ...classTables)`:
 *
 *  - `selfTable` is a plain object whose keys are operator names (`"+"`, `"=="`, unary
 *    `"-"`, ...) mapped to implementation functions, plus an optional `open: [...]` array
 *    naming which operators subclasses are allowed to further override.
 *  - Each of the (optional, variadic) `classTables` describes cross-type operator
 *    behavior against another already-overloaded class: `{ left: OtherCtor, "+": fn }` /
 *    `{ right: OtherCtor, "+": fn }` (exactly one of `left`/`right`, never both —
 *    `ConstructOperatorSetNode.construct` throws otherwise).
 *  - `Operators(...)` itself returns a constructor-shaped object, but it is *not* meant
 *    to be `new`-ed directly (doing so throws `ArrayIndexOutOfBoundsException` deep in
 *    `JSArguments.getNewTarget` — its generated constructor unconditionally expects to
 *    be reached via a subclass's `super()` call, i.e. `new.target` set). The working
 *    pattern is to extend it:
 *
 *    ```js
 *    class Vec extends Operators({
 *      "+"(a, b) { return new Vec(a.x + b.x); },
 *    }) {
 *      constructor(x) { super(); this.x = x; }
 *    }
 *    new Vec(1) + new Vec(2); // -> Vec { x: 3 }, dispatched through OperatorSet
 *    ```
 *
 *    This confirms `Operators` is GraalJS's dormant implementation of the old TC39
 *    "operator overloading" proposal's `class Foo extends Operators({...})` shape (the
 *    superseded "with operators from" clause never made it into GraalJS's parser as
 *    actual syntax — only this desugared-by-hand `extends Operators(...)` form exists).
 */
class OperatorOverloadingTransformer : ModTransformer {
    override fun targetClasses(): Set<String> = setOf(
        "com/oracle/truffle/js/builtins/OperatorsBuiltins",
        "com/oracle/truffle/js/runtime/JSContextOptions",
    )

    override fun transform(className: String, classfileBuffer: ByteArray): ByteArray? {
        println("[operator-overloading] Intercepted $className — applying patch")
        return try {
            when (className) {
                "com/oracle/truffle/js/builtins/OperatorsBuiltins" ->
                    patchOperatorsBuiltins(classfileBuffer)
                "com/oracle/truffle/js/runtime/JSContextOptions" ->
                    patchJSContextOptions(classfileBuffer)
                else -> null
            }
        } catch (t: Throwable) {
            println("[operator-overloading] Patch FAILED, leaving class unmodified: $t")
            t.printStackTrace()
            null
        }
    }

    /**
     * Patches `OperatorsBuiltins.checkOverloadedOperatorsAllowed(JSDynamicObject, Node)` —
     * a `static void` method — to `return` immediately, before the
     * `overloadedOperatorsAllowed(...)` check (and its `TypeError`-throwing branch) ever
     * runs. See the class doc comment above: on 25.0.0 `overloadedOperatorsAllowed`
     * already always returns `true`, so this is effectively belt-and-suspenders, but it's
     * the task's explicit primary ask and guards against any GraalJS build where that
     * isn't the case.
     */
    private fun patchOperatorsBuiltins(classfileBuffer: ByteArray): ByteArray? =
        patchClass(classfileBuffer) {
            // Can't use atMethodEntry here: it only *prepends* instructions, and the
            // original body's `athrow`-terminated branch would be left dangling after our
            // unconditional `return` with no stack map frame describing it — the JVM
            // verifier rejects that as "Expecting a stack map frame" (confirmed by trying
            // atMethodEntry first: it loads fine but the JS language then fails to
            // initialize with a VerifyError at exactly this offset). Replacing the whole
            // method body instead avoids ever having unreachable, frame-less bytecode.
            for (method in classNode.methods) {
                if (method.name != "checkOverloadedOperatorsAllowed") continue
                method.instructions = InsnList().apply { add(InsnNode(Opcodes.RETURN)) }
                method.tryCatchBlocks.clear()
                recordPatch()
                println("[mod-loader] Replaced body of method ${method.name}${method.desc} with a bare `return`")
            }
        }

    /**
     * Patches `JSContextOptions.isOperatorOverloading()` — a simple `boolean` getter
     * (`aload_0; getfield operatorOverloading; ireturn`) — to unconditionally push `true`
     * and return immediately, ignoring the backing field (and thus the
     * `js.operator-overloading` context option's default-`false` value) entirely.
     *
     * This is the patch that actually matters: `JSRealm`'s global-object setup only wires
     * up the JS-visible `Operators` constructor when this getter reports `true` (see the
     * class doc comment above), so flipping it unlocks `Operators(...)` from JS with no
     * embedder-side `Context.Builder` changes needed.
     */
    private fun patchJSContextOptions(classfileBuffer: ByteArray): ByteArray? =
        patchClass(classfileBuffer) {
            // Same reasoning as patchOperatorsBuiltins above: replace the whole method
            // body (`aload_0; getfield; ireturn`) rather than prepending via
            // atMethodEntry, so there's no leftover frame-less dead code for the
            // verifier to choke on.
            for (method in classNode.methods) {
                if (method.name != "isOperatorOverloading") continue
                method.instructions = InsnList().apply {
                    add(InsnNode(Opcodes.ICONST_1))
                    add(InsnNode(Opcodes.IRETURN))
                }
                method.tryCatchBlocks.clear()
                recordPatch()
                println("[mod-loader] Replaced body of method ${method.name}${method.desc} with `return true`")
            }
        }
}

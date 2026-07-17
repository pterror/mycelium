package org.mycelium.example

import org.mycelium.modloader.ModTransformer
import org.mycelium.modloader.patchClass

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
 * addition logic itself lives in the hand-written `JSAddNode.doInt(int,int)` /
 * `JSAddNode.doIntTruncate(int,int)` static methods (both `(II)I`) — NOT in
 * `JSAddNodeGen` directly:
 *
 * ```
 * protected static int doInt(int, int):
 *     iload_0; iload_1; invokestatic Math.addExact:(II)I; ireturn
 * protected static int doIntTruncate(int, int):
 *     iload_0; iload_1; iadd; ireturn
 * ```
 *
 * `JSAddNodeGen`'s generated `execute*` methods call those via `invokestatic`. This mod
 * demonstrates both ways [org.mycelium.modloader.PatchDsl] supports rewriting those call
 * sites, applied to one method each purely to show the contrast:
 *
 *  - `doInt` calls are **redirected** wholesale to [cursedDoInt], a same-signature
 *    drop-in replacement provided by this mod's own jar. This only works because
 *    [org.mycelium.modloader.ModLoaderAgent] adds every mod jar to the system
 *    classloader search path (`Instrumentation.appendToSystemClassLoaderSearch`),
 *    making `org.mycelium.example.CursedAdditionTransformer` — and thus `cursedDoInt` —
 *    resolvable from the application classloader that loads `JSAddNodeGen`.
 *  - `doIntTruncate` calls are left alone, but every call site gets `ICONST_1; IADD`
 *    spliced in right after it, incrementing whatever int result GraalJS's own
 *    arithmetic just produced — a raw instruction splice, no replacement method needed.
 */
class CursedAdditionTransformer : ModTransformer {
    override fun targetClasses(): Set<String> =
        setOf("com/oracle/truffle/js/nodes/binary/JSAddNodeGen")

    override fun transform(className: String, classfileBuffer: ByteArray): ByteArray? {
        println("[cursed-addition] Intercepted $className — applying patch")
        return try {
            patchClass(classfileBuffer) {
                // Approach 1: redirect doInt calls to our replacement method (must be
                // reachable from JSAddNodeGen's classloader — see appendToSystemClassLoaderSearch).
                redirectCallsTo(
                    "com/oracle/truffle/js/nodes/binary/JSAddNode",
                    "doInt",
                ).to(
                    "org/mycelium/example/CursedAdditionTransformer",
                    "cursedDoInt",
                )

                // Approach 2: raw instruction splice for doIntTruncate — no replacement
                // method needed, just add 1 to the int already sitting on the stack.
                afterCallsTo(
                    "com/oracle/truffle/js/nodes/binary/JSAddNode",
                    "doIntTruncate",
                ) {
                    iconst(1)
                    iadd()
                }
            }
        } catch (t: Throwable) {
            println("[cursed-addition] Patch FAILED, leaving class unmodified: $t")
            t.printStackTrace()
            null
        }
    }

    companion object {
        /**
         * Drop-in replacement for `JSAddNode.doInt(int, int): int` — same descriptor
         * (`(II)I`), same overflow-checking behavior (`Math.addExact`, so overflow still
         * falls through to `doIntOverflow` exactly as before), plus the curse. Must stay
         * `@JvmStatic` so it compiles to a *static* method directly on the outer
         * `CursedAdditionTransformer` class (not `CursedAdditionTransformer$Companion`) —
         * that's the class name [redirectCallsTo] targets above, and `redirectCallsTo`'s
         * redirected calls use `INVOKESTATIC`.
         */
        @JvmStatic
        fun cursedDoInt(a: Int, b: Int): Int = Math.addExact(a, b) + 1
    }
}

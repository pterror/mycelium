package org.mycelium.library.compiler

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyObject
import org.mycelium.library.MyceliumException
import java.nio.file.Path

// A CompileToJsBackend for npm-package-backed compile-to-JS languages (CoffeeScript, Civet,
// ...) — as opposed to EsbuildBackend, the one *bundled* backend (used only for TypeScript).
// Rather than shelling out or embedding a second JS engine, this `require()`s the compiler
// package straight into the same shared GraalJS Context Mycelium already runs guest code in
// (via the `__mycelium_require__` bridge Mycelium.kt wires up for CjsRuntime — see
// InterceptingFileSystem.requireModule), then calls whatever function `CompilerSpec.
// compileFunction` names (e.g. "require('coffeescript').compile") with the source text and
// the spec's configured options.
//
// One instance is created per CompileToJsDispatcher.compile call (not shared/cached) since
// `moduleResolutionDir` — where in the filesystem `require('coffeescript')` should be
// resolved from — varies per resolution (project node_modules vs. the global compiler cache),
// and is therefore dispatch-specific rather than backend-global the way EsbuildBackend (which
// needs no such directory) is.
class NpmJsApiBackend(
    private val context: Context,
    // e.g. "require('coffeescript').compile" — see CompilerSpec.compileFunction's own doc
    // comment. Only the "require('pkg').member.chain" shape is supported; parsed once up
    // front (in init) rather than re-parsed on every compile call.
    compileFunctionExpr: String,
    // The directory `__mycelium_require__`'s own node_modules walk should start from —
    // typically either the compiled file's project root (if the compiler package is
    // installed there) or CompilerCache/CompilerDownloader's on-disk cache directory for
    // this language+version.
    private val moduleResolutionDir: Path,
    // Mirrors CompilerSpec.outputIsObject/outputProperty, already combined by
    // CompilerSpec.createBackend into the one property name this backend actually needs:
    // null if the compile function's return value IS the compiled JS string itself, or the
    // property name to read (e.g. "code" for CompilerSpec.outputIsObject compilers, "js" for
    // Imba's Compilation/Document-shaped result) if it returns an object instead.
    private val outputProperty: String?,
) : CompileToJsBackend {
    private val packageName: String
    private val memberChain: List<String>

    init {
        val match = COMPILE_FUNCTION_PATTERN.matchEntire(compileFunctionExpr)
            ?: throw MyceliumException(
                "mycelium: unsupported compileFunction expression '$compileFunctionExpr' — expected " +
                    "\"require('pkg').member(.chain)*\""
            )
        packageName = match.groupValues[1]
        memberChain = match.groupValues[2]
            .split(".")
            .filter { it.isNotEmpty() }
    }

    override fun compile(source: String, sourcePath: Path, options: Map<String, Any>): String {
        val requireFn = context.getBindings("js").getMember("__mycelium_require__")
            ?: throw MyceliumException("mycelium: __mycelium_require__ is not wired up on this Context")

        val module = try {
            requireFn.execute(packageName, moduleResolutionDir.toString())
        } catch (e: Exception) {
            throw MyceliumException(
                "mycelium: failed to require('$packageName') from '$moduleResolutionDir' — ${e.message}",
                e
            )
        }

        var fn: Value = module
        for (member in memberChain) {
            fn = fn.getMember(member)
                ?: throw MyceliumException(
                    "mycelium: '$packageName' has no member '$member' (resolving '${memberChain.joinToString(".")}')"
                )
        }
        if (!fn.canExecute()) {
            throw MyceliumException(
                "mycelium: '$packageName${if (memberChain.isEmpty()) "" else "." + memberChain.joinToString(".")}' is not callable"
            )
        }

        val optionsValue = ProxyObject.fromMap(options)
        val result = try {
            fn.execute(source, optionsValue)
        } catch (e: Exception) {
            throw MyceliumException(
                "mycelium: '$packageName' failed to compile '${sourcePath.fileName}' — ${e.message}",
                e
            )
        }

        return if (outputProperty != null) {
            val output = result.getMember(outputProperty)
                ?: throw MyceliumException(
                    "mycelium: '$packageName' compile result has no '$outputProperty' property"
                )
            output.asString()
        } else {
            result.asString()
        }
    }

    companion object {
        private val COMPILE_FUNCTION_PATTERN = Regex("""require\('([^']+)'\)((?:\.[A-Za-z_$][A-Za-z0-9_$]*)*)""")
    }
}

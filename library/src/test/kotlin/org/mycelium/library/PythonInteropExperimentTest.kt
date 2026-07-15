package org.mycelium.library

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Regression/investigation tests for Python host/target interop (see TODO.md).
 *
 * Findings from this investigation:
 *
 * 1. Basic Python eval, function definitions, and JS<->Python cross-language calls all
 *    work fine on a *plain* polyglot Context (no custom FileSystem attached). See the
 *    passing tests below.
 *
 * 2. Running Mycelium's actual app (`Polyglot.eval("python", ...)` from a `.mjs` file)
 *    used to crash before even reaching GraalPython, with:
 *        mycelium: cannot find module '<frozen importlib._bootstrap>' ...
 *    or
 *        mycelium: don't know how to import '<frozen importlib._bootstrap>' ...
 *    GraalPython resolves its own frozen bootstrap modules through the same
 *    `TruffleLanguage.Env` / `FileSystem` machinery used for real user imports. Mycelium's
 *    `InterceptingFileSystem` was treating that synthetic, non-file identifier
 *    (`<frozen importlib._bootstrap>`) as a genuine import specifier — either as an npm
 *    bare specifier (`parsePath`) or as a "don't know this extension" error
 *    (`newByteChannel`) — and hard-failing instead of letting it fall through to the
 *    delegate filesystem (which GraalPython itself already knows how to handle via
 *    NoSuchFileException, falling back to its embedded frozen bytecode).
 *    Fixed in `InterceptingFileSystem.isSyntheticIdentifier` — paths of the form `<...>`
 *    now bypass both interception paths.
 *
 * 3. After that fix, a second, deeper issue remains: evaluating Python from JS through a
 *    named `Source` (a real file path, as `Mycelium.runFile` and `Mycelium.evalModule` both
 *    use) *while a custom `FileSystem` is attached via `allowIO`* crashes with:
 *        SystemError: Unable to set dict of object of type foreign
 *    Standard output shows the Python code's side effects (e.g. `print`) DID run before the
 *    crash — this is not a pure teardown artifact, but it also isn't simply "eval fails":
 *    the guest computation completes and something in module/namespace bridging afterward
 *    throws. It reproduces with a minimal 2-language (js, python) Context and disappears
 *    entirely if either (a) the custom FileSystem is removed (`allowIO` default) or
 *    (b) the Source has no file name (inline `context.eval(lang, code)` instead of a named
 *    `Source`). Tried `python.PosixModuleBackend=java` as a mitigation — did not help.
 *    This looks like a genuine GraalPython 24.1.1 limitation/bug in how it builds a
 *    module's `__dict__` when GraalPython's own filesystem view has been swapped out from
 *    under it (its os/posix layer likely falls back to an "emulated" mode that hands back a
 *    foreign/polyglot-proxied object where GraalPython expects a native PDict). Not fixed —
 *    tracked as blocked pending a GraalPython upgrade or a narrower repro to file upstream.
 *    See the `@Disabled` test below for a live repro.
 */
class PythonInteropExperimentTest {

    @Test fun basicPythonEval() {
        Context.newBuilder("python").allowAllAccess(true).build().use { ctx ->
            val result = ctx.eval("python", "40 + 2")
            assertEquals(42, result.asInt())
        }
    }

    @Test fun pythonFunctionDef() {
        Context.newBuilder("python").allowAllAccess(true).build().use { ctx ->
            ctx.eval("python", "def greet(name): return f'Hello, {name}!'")
            val greet = ctx.getBindings("python").getMember("greet")
            val result = greet.execute("World")
            assertEquals("Hello, World!", result.asString())
        }
    }

    @Test fun jsPolyglotEvalPython() {
        // Inline eval (no named Source, no custom FileSystem) works fine.
        Context.newBuilder("js", "python").allowAllAccess(true).build().use { ctx ->
            val result = ctx.eval("js", """Polyglot.eval("python", "40 + 2")""")
            assertEquals(42, result.asInt())
        }
    }

    @Test fun pythonAsHostImportingJs() {
        Context.newBuilder("python", "js").allowAllAccess(true).build().use { ctx ->
            val result = ctx.eval("python", "40 + 2")
            assertEquals(42, result.asInt())
        }
    }

    @Test fun mycelium_runFile_jsPolyglotEvalPython_noLongerHardFailsOnFrozenBootstrap() {
        // Reproduces the app/modulestest scenario. Before the isSyntheticIdentifier fix in
        // InterceptingFileSystem, this failed immediately with "cannot find module
        // '<frozen importlib._bootstrap>'" — a false-positive from Mycelium's own import
        // interception, not from GraalPython. It now gets past that and hits the deeper,
        // still-unresolved GraalPython dict/foreign-object issue documented above.
        val dir = java.nio.file.Files.createTempDirectory("mycelium-py-test")
        val f = dir.resolve("main.mjs").toFile()
        f.writeText("""Polyglot.eval("python", "print('hello from python polyglot eval!')")""")
        val mc = Mycelium(listOf(JsSupport, PythonSupport))
        val ex = assertThrows(org.graalvm.polyglot.PolyglotException::class.java) {
            mc.runFile(f.path)
        }
        // Confirms we're past the false-positive import-interception bug and now hitting
        // only the deeper GraalPython dict/foreign-object issue.
        assertTrue(ex.message?.contains("Unable to set dict of object of type foreign") == true)
    }

    @Disabled("Blocked on GraalPython 24.1.1: crashes with " +
        "'SystemError: Unable to set dict of object of type foreign' when a named Source " +
        "is evaluated through a Context with a custom FileSystem attached. See class doc.")
    @Test fun jsModuleSourceWithCustomFsPolyglotEvalPython() {
        val fs = InterceptingFileSystem()
        val ctx = Context.newBuilder("js", "python").allowAllAccess(true)
            .allowIO(IOAccess.newBuilder().fileSystem(fs).build())
            .build()
        val mc = Mycelium(listOf(JsSupport, PythonSupport))
        fs.init(ctx, mc)
        val code = """Polyglot.eval("python", "print('hi')")"""
        val source = Source.newBuilder("js", code, "test.mjs").build()
        ctx.eval(source)
    }
}

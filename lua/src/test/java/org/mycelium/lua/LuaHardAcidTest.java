package org.mycelium.lua;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harder real-world acid tests than {@link LuaLibraryAcidTest}: larger, more demanding
 * single-file libraries ({@code lume}, {@code luaunit}) and a multi-file library ({@code
 * Penlight}) exercised through the real {@code require}/{@code package.path} machinery in {@link
 * org.mycelium.lua.stdlib.LuaPackageLib}, rather than {@code LuaLibraryAcidTest}'s IIFE-wrapper
 * trick (which only works for dependency-free single files).
 *
 * <p>Downloaded sources live under {@code src/test/resources/lua-libs/} (single-file libraries)
 * and {@code src/test/resources/lua-libs/penlight/pl/} (Penlight modules, laid out to match their
 * {@code require("pl.xxx")} module names). None of it is modified from upstream.
 */
class LuaHardAcidTest {

    private Context context;

    @BeforeEach
    void setUp() {
        context = Context.newBuilder("lua")
                .allowCreateThread(true)
                .allowIO(IOAccess.ALL)
                .allowEnvironmentAccess(EnvironmentAccess.INHERIT)
                .build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private Value eval(String source) {
        return context.eval("lua", source);
    }

    /** Reads a downloaded library's source out of {@code src/test/resources/lua-libs/}. */
    private static String readLibrary(String fileName) {
        try (InputStream in = LuaHardAcidTest.class.getClassLoader()
                .getResourceAsStream("lua-libs/" + fileName)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource: lua-libs/" + fileName);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Wraps a library's source in an IIFE that captures its top-level {@code return} value as
     * {@code local _lib}, then appends {@code testCode} (which references {@code _lib}) to the
     * same chunk. Matches {@code LuaLibraryAcidTest}'s pattern.
     */
    private Value runLibraryTest(String libraryFileName, String testCode) {
        String libSource = readLibrary(libraryFileName);
        String wrapped = "local _lib = (function()\n" + libSource + "\nend)()\n" + testCode;
        return eval(wrapped);
    }

    /** Absolute, forward-slashed path to {@code src/test/resources/lua-libs/penlight}, for
     * splicing into {@code package.path} snippets (Lua path templates use {@code ?} plus literal
     * separators, not Java {@link Path} objects). */
    private static String penlightDir() {
        return Path.of("src/test/resources/lua-libs/penlight").toAbsolutePath().toString().replace('\\', '/');
    }

    // ---------------------------------------------------------------------
    // 1. lume (rxi) — single-file utility-belt library. Heavy on closures, varargs, string
    // patterns, select/unpack, pcall, math, and iterators; a good "kitchen sink" stress test that
    // doesn't need require().
    // ---------------------------------------------------------------------

    @Test
    void lumeExercisesItsCoreApi() {
        runLibraryTest("lume.lua", """
                local lume = _lib

                assert(lume.clamp(5, 1, 3) == 3)
                assert(lume.round(2.7) == 3)
                assert(lume.lerp(0, 10, 0.5) == 5)

                local t = {1, 2, 3, 4, 5}
                assert(#lume.filter(t, function(x) return x > 3 end) == 2)
                assert(lume.find(t, 3) == 3)
                assert(lume.reduce(t, function(a, b) return a + b end) == 15)

                local s = lume.map(t, function(x) return x * 2 end)
                assert(s[1] == 2 and s[5] == 10)

                assert(lume.count(t) == 5)
                assert(type(lume.uuid()) == "string")
                assert(lume.trim("  hello  ") == "hello")
                assert(lume.wordwrap("hello world foo bar", 10) ~= nil)

                local ser = lume.serialize({a = 1, b = "hi"})
                assert(type(ser) == "string")
                local deser = lume.deserialize(ser)
                assert(deser.a == 1 and deser.b == "hi")

                -- A few more of lume's heavier features: varargs/select-based combine/array,
                -- closures via lume.fn, and pattern-based split/format.
                assert(lume.round(-2.5) == -3 or lume.round(-2.5) == -2)
                assert(lume.split("a,b,c", ",")[2] == "b")
                assert(lume.format("{1} and {2}", {"x", "y"}) == "x and y")

                local calls = 0
                local inc = lume.fn(function(n) calls = calls + n end, 5)
                inc()
                assert(calls == 5)

                assert(lume.isarray({1, 2, 3}))
                assert(not lume.isarray({a = 1}))
                """);
    }

    // ---------------------------------------------------------------------
    // 2. luaunit (bluebird75) — a ~3600-line unit testing framework. Exercises debug library
    // (traceback/getinfo), string library extensively, metatables, pcall/xpcall, error handling,
    // select/unpack, table library, and os.clock/os.getenv. This is loaded and then used to run a
    // tiny in-line test suite through its real runner, so any breakage inside the harness itself
    // (not just the library's top-level load) surfaces as an assertion failure below.
    // ---------------------------------------------------------------------

    /**
     * luaunit's very first line is {@code require("math")} (an old Lua-5.0-vs-5.1-detection
     * idiom: {@code math} always exists as a table, so requiring it just needs to succeed). Real
     * PUC-Rio Lua 5.1 makes that trivially true &mdash; {@code luaL_openlibs} registers every
     * standard library into {@code package.loaded} as it opens it (see {@code lauxlib.c}'s {@code
     * luaL_register}), so {@code require("math")}/{@code require("string")}/etc. are all
     * short-circuited cache hits. {@link org.mycelium.lua.stdlib.LuaPackageLib#install} now does
     * the same: after all standard libraries are installed as globals, it pre-seeds {@code
     * package.loaded} with each of them, so {@code require("math")} etc. resolve to the existing
     * library table without touching the file searcher.
     */
    @Test
    void luaunitRequiresBuiltinLibraryThatIsPreregisteredInPackageLoaded() {
        String libSource = readLibrary("luaunit.lua");
        String unseeded = "local _lib = (function()\n" + libSource + "\nend)()\nreturn 'ok'";
        Value result = eval(unseeded);
        assertTrue(result.asString().equals("ok"), "expected luaunit.lua to load without needing a manual shim");
    }

    @Test
    void luaunitRunsAMinimalSuiteToCompletion() {
        String libSource = readLibrary("luaunit.lua");
        String wrapped = "local _lib = (function()\n" + libSource + "\nend)()\n" + """
                local lu = _lib

                TestExample = {}
                function TestExample:testAdd()
                    lu.assertEquals(1 + 1, 2)
                end
                function TestExample:testString()
                    lu.assertEquals(string.upper("hello"), "HELLO")
                end
                function TestExample:testTable()
                    local t = {1, 2, 3}
                    lu.assertEquals(#t, 3)
                end

                local runner = lu.LuaUnit.new()
                runner:setOutputType("text")
                local result = runner:runSuite()
                assert(result == 0, "luaunit tests failed with exit code " .. tostring(result))
                """;
        eval(wrapped);
    }

    // ---------------------------------------------------------------------
    // 3. Penlight (lunarmodules) — multi-file library exercised through the real
    // require()/package.path machinery (LuaPackageLib), not the IIFE trick. tablex/stringx/class
    // all load and run cleanly (including tablex.map/filter's internal require("pl.List"), and
    // List/Map's own require("pl.class")/require("pl.pretty") chains — see the extra modules
    // fetched into src/test/resources/lua-libs/penlight/pl/ beyond what the task listed: List.lua,
    // Map.lua, pretty.lua, operator.lua, types.lua, compat.lua, none of which needed any further
    // fixes or workarounds). Full pl.init (which additionally pulls in pl.import_into and wires
    // every Penlight module into a single namespace) wasn't attempted — that's a much wider
    // dependency surface for marginal extra coverage over what's already exercised below.
    // ---------------------------------------------------------------------

    @Test
    void penlightTablexMapFilterReduce() {
        eval(String.format("""
                package.path = "%s/?.lua;%s/?/init.lua;" .. package.path
                local tablex = require("pl.tablex")

                local doubled = tablex.map(function(x) return x * 2 end, {1, 2, 3})
                assert(doubled[1] == 2 and doubled[2] == 4 and doubled[3] == 6)

                local evens = tablex.filter({1, 2, 3, 4, 5, 6}, function(x) return x %% 2 == 0 end)
                assert(#evens == 3)

                local sum = tablex.reduce("+", {1, 2, 3, 4, 5})
                assert(sum == 15)

                assert(tablex.size({1, 2, 3}) == 3)
                assert(tablex.find({10, 20, 30}, 20) == 2)

                local copy = tablex.deepcopy({a = 1, b = {c = 2}})
                assert(copy.a == 1 and copy.b.c == 2)
                """, penlightDir(), penlightDir()));
    }

    @Test
    void penlightStringxTrimSplitStartswith() {
        eval(String.format("""
                package.path = "%s/?.lua;%s/?/init.lua;" .. package.path
                local stringx = require("pl.stringx")

                assert(stringx.strip("  hello  ") == "hello")
                assert(stringx.startswith("hello world", "hello"))
                assert(stringx.endswith("hello world", "world"))
                local parts = stringx.split("a,b,c", ",")
                assert(#parts == 3 and parts[2] == "b")
                assert(stringx.join(",", {"a", "b", "c"}) == "a,b,c")
                assert(stringx.title("hello world") == "Hello World")
                """, penlightDir(), penlightDir()));
    }

    @Test
    void penlightClassInheritance() {
        eval(String.format("""
                package.path = "%s/?.lua;%s/?/init.lua;" .. package.path
                local class = require("pl.class")
                local Animal = class()
                function Animal:_init(name) self.name = name end
                function Animal:speak() return self.name .. " makes a sound" end

                local Dog = class(Animal)
                function Dog:speak() return self.name .. " barks" end

                local d = Dog("Rex")
                assert(d:speak() == "Rex barks")
                """, penlightDir(), penlightDir()));
    }

    // ---------------------------------------------------------------------
    // Notes on libraries not included above:
    //
    //   - The task asked for the official PUC-Rio Lua 5.1 test suite (lua.org/tests/5.1/). Those
    //     files are not published as individually fetchable raw sources anywhere stable enough to
    //     `curl` in a test-resources download step (the historical `lua.org/tests/5.1/` tarball
    //     path 404s as of this writing, and no mirror hosts the unpacked .lua files directly) — so
    //     rather than fake a "download" of hand-copied corner cases and label it official, that
    //     suite is left out of this file entirely. The corner cases it's famous for (numeric
    //     edge cases, deep closures/upvalue sharing, coroutine yield-from-pcall/for-in, long
    //     bracket levels, weak tables) are already covered piecemeal across LuaAcidTest.
    // ---------------------------------------------------------------------
}

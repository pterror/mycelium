package org.mycelium.lua;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs *real* pure-Lua libraries (fetched from their upstream GitHub repos, embedded verbatim
 * under {@code src/test/resources/lua-libs/}) end-to-end through the Truffle Lua 5.1
 * implementation, mirroring {@code library}'s {@code NpmCompatibilityTest} for the JS runtime:
 * synthetic spec-compliance tests ({@link LuaAcidTest}) catch spec violations, but real-world
 * libraries exercise combinations of features (metatables + closures + string patterns +
 * varargs, all at once) that hand-written tests rarely think to combine.
 *
 * <p>None of these libraries is modified — the downloaded source is embedded as-is. Since
 * {@code require} isn't implemented here (these are all genuinely single-file, dependency-free
 * libraries — no internal {@code require} calls needed), each library's source is loaded by
 * wrapping it in an immediately-invoked anonymous function and capturing whatever it {@code
 * return}s at the top level:
 *
 * <pre>{@code local _lib = (function() <library source> end)() }</pre>
 *
 * That local is then referenced by a small hand-written snippet exercising the library's public
 * API, concatenated onto the end of the same chunk and run through a single {@code context.eval}
 * call.
 */
class LuaLibraryAcidTest {

    private Context context;

    @BeforeEach
    void setUp() {
        context = Context.newBuilder("lua").allowCreateThread(true).build();
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
        try (InputStream in = LuaLibraryAcidTest.class.getClassLoader()
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
     * same chunk.
     */
    private Value runLibraryTest(String libraryFileName, String testCode) {
        String libSource = readLibrary(libraryFileName);
        String wrapped = "local _lib = (function()\n" + libSource + "\nend)()\n" + testCode;
        return eval(wrapped);
    }

    // ---------------------------------------------------------------------
    // 1. inspect.lua (kikito) — pretty-prints Lua tables. Exercises metatables, string library,
    // type()/tostring, recursion, table iteration.
    // ---------------------------------------------------------------------

    @Test
    void inspectPrettyPrintsTablesAndPrimitives() {
        runLibraryTest("inspect.lua", """
                assert(type(_lib) == "table" or type(_lib) == "function")
                local inspectFn = _lib
                if type(inspectFn) == "table" then
                    -- inspect.lua's module table is itself callable via __call.
                    assert(getmetatable(inspectFn) ~= nil)
                end

                local flatResult = inspectFn({1, 2, 3})
                assert(type(flatResult) == "string")
                assert(flatResult:find("1"))
                assert(flatResult:find("2"))
                assert(flatResult:find("3"))

                local nestedResult = inspectFn({a = 1, b = {c = 2}})
                assert(type(nestedResult) == "string")
                assert(nestedResult:find("a"))
                assert(nestedResult:find("b"))
                assert(nestedResult:find("c"))

                -- Cyclic tables shouldn't blow the stack — inspect.lua detects and labels cycles.
                local cyclic = {}
                cyclic.self = cyclic
                local cyclicResult = inspectFn(cyclic)
                assert(type(cyclicResult) == "string")
                """);
    }

    // ---------------------------------------------------------------------
    // 2. middleclass (kikito) — class-based OO. Exercises metatables heavily (__index,
    // __newindex, __call, __tostring), closures, rawset/rawget.
    // ---------------------------------------------------------------------

    @Test
    void middleclassSupportsInheritanceAndInstanceOf() {
        runLibraryTest("middleclass.lua", """
                local class = _lib

                local Animal = class('Animal')
                function Animal:initialize(name)
                    self.name = name
                end
                function Animal:speak()
                    return self.name .. " makes a sound"
                end

                local Dog = class('Dog', Animal)
                function Dog:speak()
                    return self.name .. " barks"
                end

                local a = Animal:new("Generic")
                assert(a:speak() == "Generic makes a sound")

                local d = Dog:new("Rex")
                assert(d:speak() == "Rex barks")

                assert(d:isInstanceOf(Dog))
                assert(d:isInstanceOf(Animal))
                assert(not a:isInstanceOf(Dog))

                assert(Dog:isSubclassOf(Animal))
                assert(not Animal:isSubclassOf(Dog))

                assert(d.class == Dog)
                assert(d.class.name == "Dog")
                """);
    }

    // ---------------------------------------------------------------------
    // 3. json.lua (rxi) — encode/decode JSON. Exercises string library (find, sub, byte, char,
    // format, gsub), table manipulation, type checking, error handling.
    // ---------------------------------------------------------------------

    @Test
    void jsonEncodesAndDecodesRoundTrip() {
        runLibraryTest("json.lua", """
                local json = _lib

                local encoded = json.encode({1, 2, 3})
                assert(encoded == "[1,2,3]", "got: " .. encoded)

                local decodedArr = json.decode("[1,2,3]")
                assert(decodedArr[1] == 1 and decodedArr[2] == 2 and decodedArr[3] == 3)

                local obj = { name = "mycelium", value = 42, nested = { a = true, b = false } }
                local objEncoded = json.encode(obj)
                local objDecoded = json.decode(objEncoded)
                assert(objDecoded.name == "mycelium")
                assert(objDecoded.value == 42)
                assert(objDecoded.nested.a == true)
                assert(objDecoded.nested.b == false)

                -- Strings with escapes round-trip correctly.
                local withEscapes = json.decode(json.encode("line1\\nline2\\ttabbed\\"quoted\\""))
                assert(withEscapes == "line1\\nline2\\ttabbed\\"quoted\\"")

                assert(json.decode("null") == nil or json.decode("null") == json.null)
                assert(json.decode("true") == true)
                assert(json.decode("false") == false)
                assert(json.decode("3.14") == 3.14)
                """);
    }

    // ---------------------------------------------------------------------
    // 4. 30log (Yonaba) — minimal class library. Exercises metatables, setmetatable, rawget,
    // type. Distributed as a single, deliberately dense/minified 30-line file.
    // ---------------------------------------------------------------------

    @Test
    void thirtyLogSupportsClassesAndInheritanceChain() {
        // 30log's actual API (not "is_a", which belongs to a different similarly-shaped OO
        // library) is `instanceOf`/`subclassOf`, and the constructor hook is `init` (called via
        // `self.init(instance, ...)` inside `instantiate`), not `initialize`.
        runLibraryTest("30log.lua", """
                local class = _lib

                local Base = class('Base')
                function Base:init(x) self.x = x end
                function Base:getX() return self.x end

                local Mid = Base:extend('Mid')
                function Mid:getXDoubled() return self.x * 2 end

                local Leaf = Mid:extend('Leaf')

                local instance = Leaf(21)
                assert(instance:getX() == 21)
                assert(instance:getXDoubled() == 42)

                assert(instance:instanceOf(Leaf))
                assert(instance:instanceOf(Mid))
                assert(instance:instanceOf(Base))

                assert(Leaf:subclassOf(Mid))
                assert(Mid:subclassOf(Base))
                assert(not Base:subclassOf(Mid))
                """);
    }

    // ---------------------------------------------------------------------
    // 5. serpent (pkulchenko) — Lua table serialization to Lua source (and back). Guards its
    // `debug`/`setfenv`/`string.dump` usage (`debug and debug.getmetatable or getmetatable`,
    // `if setfenv then ... end`, `pcall(string.dump, ...)`) so it loads fine without any of
    // those; only the block/line/dump *table-serialization* paths (not function-dumping) are
    // exercised here.
    // ---------------------------------------------------------------------

    @Test
    void serpentSerializesAndDeserializesTables() {
        runLibraryTest("serpent.lua", """
                local serpent = _lib
                assert(type(serpent.dump) == "function")
                assert(type(serpent.load) == "function")

                local data = { 1, 2, 3, name = "mycelium", nested = { a = 1, b = 2 } }
                local serialized = serpent.dump(data)
                assert(type(serialized) == "string")

                -- serpent.load's real signature is pcall-shaped: (ok, value), not (value, err) —
                -- it literally `return pcall(f)` internally (see serpent.lua's deserialize()).
                local ok, loaded = serpent.load(serialized)
                assert(ok, "serpent.load failed: " .. tostring(loaded))
                assert(loaded[1] == 1 and loaded[2] == 2 and loaded[3] == 3)
                assert(loaded.name == "mycelium")
                assert(loaded.nested.a == 1 and loaded.nested.b == 2)

                local line = serpent.line({1, 2, 3})
                assert(type(line) == "string")
                """);
    }

    // ---------------------------------------------------------------------
    // 6. fun.lua (luafun) — functional-iterator library. Exercises closures, generic-for
    // iterators, varargs, table operations (map/filter/reduce/zip/chain).
    // ---------------------------------------------------------------------

    @Test
    void funLuaMapsFiltersReducesAndZips() {
        runLibraryTest("fun.lua", """
                local fun = _lib

                local doubled = fun.iter({1, 2, 3, 4}):map(function(x) return x * 2 end):totable()
                assert(#doubled == 4)
                assert(doubled[1] == 2 and doubled[2] == 4 and doubled[3] == 6 and doubled[4] == 8)

                local evens = fun.iter({1, 2, 3, 4, 5, 6}):filter(function(x) return x % 2 == 0 end):totable()
                assert(#evens == 3)
                assert(evens[1] == 2 and evens[2] == 4 and evens[3] == 6)

                local sum = fun.iter({1, 2, 3, 4, 5}):reduce(function(acc, x) return acc + x end, 0)
                assert(sum == 15)

                local zipped = fun.zip({1, 2, 3}, {"a", "b", "c"}):totable()
                assert(#zipped == 3)

                local chained = fun.chain({1, 2}, {3, 4}):totable()
                assert(#chained == 4)
                assert(chained[1] == 1 and chained[4] == 4)
                """);
    }

    // ---------------------------------------------------------------------
    // Notes on libraries considered but not included:
    //
    //   - serpent's `string.dump`-based function serialization isn't exercised above (only
    //     table serialization) since `string.dump` isn't implemented here — but that path is
    //     itself wrapped in `pcall` by serpent, so it degrades gracefully rather than crashing;
    //     not disabled, just narrower in scope.
    // ---------------------------------------------------------------------
}

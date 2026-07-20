package org.mycelium.lua.stdlib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mycelium.lua.runtime.LuaFunction;
import org.mycelium.lua.runtime.LuaNil;
import org.mycelium.lua.runtime.LuaTable;

/**
 * End-to-end smoke tests for the standard library, invoking builtins directly through their
 * {@link CallTarget}s (bypassing the parser, which doesn't exist yet) using the same calling
 * convention the interpreter uses: {@code args[0]} is the {@link LuaFunction} itself.
 */
class LuaStdLibTest {

    /**
     * Minimal no-op language, sufficient to construct {@link com.oracle.truffle.api.nodes.RootNode}s
     * in tests. A {@link TruffleLanguage} instance must belong to a running Truffle context before
     * any {@code RootNode} can be built against it, so tests spin up a real (parser-less)
     * {@link Context} for this language id and recover the language instance {@link #createContext}
     * stashed, rather than constructing the language directly.
     */
    @TruffleLanguage.Registration(id = "lua-test", name = "lua-test")
    public static final class TestLanguage extends TruffleLanguage<Object> {
        static TestLanguage instance;

        @Override
        protected Object createContext(Env env) {
            instance = this;
            return new Object();
        }
    }

    private Context polyglotContext;
    private LuaTable globals;
    private TruffleLanguage<?> language;

    @BeforeEach
    void setUp() {
        polyglotContext = Context.newBuilder("lua-test").build();
        polyglotContext.initialize("lua-test");
        polyglotContext.enter();
        language = TestLanguage.instance;
        globals = new LuaTable();
        LuaStdLib.installAll(language, globals);
    }

    @AfterEach
    void tearDown() {
        polyglotContext.leave();
        polyglotContext.close();
    }

    private static Object call(LuaFunction fn, Object... args) {
        Object[] full = new Object[args.length + 1];
        full[0] = fn;
        System.arraycopy(args, 0, full, 1, args.length);
        return fn.getCallTarget().call(full);
    }

    private LuaFunction get(String name) {
        return (LuaFunction) globals.rawget(name);
    }

    private LuaFunction getIn(LuaTable t, String name) {
        return (LuaFunction) t.rawget(name);
    }

    @Test
    void typeAndTostring() {
        assertEquals("number", call(get("type"), 1.0));
        assertEquals("nil", call(get("type"), LuaNil.INSTANCE));
        assertEquals("string", call(get("type"), "hi"));
        assertEquals("42", call(get("tostring"), 42.0));
    }

    @Test
    void tonumberWithAndWithoutBase() {
        assertEquals(42.0, call(get("tonumber"), "42"));
        assertEquals(255.0, call(get("tonumber"), "ff", 16.0));
        assertEquals(LuaNil.INSTANCE, call(get("tonumber"), "not a number"));
    }

    @Test
    void assertPassesThroughOrThrows() {
        Object[] passed = (Object[]) call(get("assert"), 1.0);
        assertEquals(1.0, passed[0]);
        try {
            call(get("assert"), Boolean.FALSE, "boom");
            throw new AssertionError("expected error");
        } catch (org.mycelium.lua.LuaException e) {
            assertEquals("boom", e.getErrorObject());
        }
    }

    @Test
    void pcallCatchesErrors() {
        LuaFunction thrower = LuaBuiltinRootNode.wrap(language, "thrower", (frame, cn) -> {
            throw org.mycelium.lua.LuaException.runtimeError(null, "kaboom");
        });
        Object[] result = (Object[]) call(get("pcall"), thrower);
        assertEquals(Boolean.FALSE, result[0]);
        assertEquals("kaboom", result[1]);

        LuaFunction ok = LuaBuiltinRootNode.wrap(language, "ok", (frame, cn) -> 7.0);
        Object[] result2 = (Object[]) call(get("pcall"), ok);
        assertEquals(Boolean.TRUE, result2[0]);
        assertEquals(7.0, result2[1]);
    }

    @Test
    void pairsAndNextWalkTheWholeTable() {
        LuaTable t = new LuaTable();
        t.rawset(1L, "a");
        t.rawset(2L, "b");
        t.rawset("k", "v");

        Object[] triple = (Object[]) call(get("pairs"), t);
        LuaFunction iter = (LuaFunction) triple[0];
        int count = 0;
        Object key = LuaNil.INSTANCE;
        while (true) {
            Object result = call(iter, t, key);
            if (result == LuaNil.INSTANCE) {
                break;
            }
            Object[] kv = (Object[]) result;
            key = kv[0];
            count++;
        }
        assertEquals(3, count);
    }

    @Test
    void ipairsStopsAtFirstHole() {
        LuaTable t = new LuaTable();
        t.rawset(1L, "a");
        t.rawset(2L, "b");
        Object[] triple = (Object[]) call(get("ipairs"), t);
        LuaFunction iter = (LuaFunction) triple[0];
        Object r1 = call(iter, t, 0.0);
        Object[] kv1 = (Object[]) r1;
        assertEquals(1.0, kv1[0]);
        assertEquals("a", kv1[1]);
        Object r2 = call(iter, t, 1.0);
        Object[] kv2 = (Object[]) r2;
        assertEquals(2.0, kv2[0]);
        Object r3 = call(iter, t, 2.0);
        assertEquals(LuaNil.INSTANCE, r3);
    }

    @Test
    void selectAndUnpack() {
        Object[] rest = (Object[]) call(get("select"), 2.0, "a", "b", "c");
        assertEquals(2, rest.length);
        assertEquals("b", rest[0]);
        assertEquals("c", rest[1]);
        assertEquals(3.0, call(get("select"), "#", "a", "b", "c"));

        LuaTable t = new LuaTable();
        t.rawset(1L, "x");
        t.rawset(2L, "y");
        t.rawset(3L, "z");
        Object[] u = (Object[]) call(get("unpack"), t);
        assertEquals(3, u.length);
        assertEquals("z", u[2]);
    }

    @Test
    void rawAccessAndMetatables() {
        LuaTable t = new LuaTable();
        call(get("rawset"), t, "x", 1.0);
        assertEquals(1.0, call(get("rawget"), t, "x"));
        assertTrue((Boolean) call(get("rawequal"), 1.0, 1.0));
        assertFalse((Boolean) call(get("rawequal"), 1.0, "1"));

        LuaTable mt = new LuaTable();
        call(get("setmetatable"), t, mt);
        assertEquals(mt, call(get("getmetatable"), t));
    }

    @Test
    void mathBasics() {
        LuaTable math = (LuaTable) globals.rawget("math");
        assertEquals(3.0, call(getIn(math, "abs"), -3.0));
        assertEquals(2.0, call(getIn(math, "floor"), 2.7));
        assertEquals(3.0, call(getIn(math, "ceil"), 2.1));
        assertEquals(9.0, call(getIn(math, "max"), 1.0, 9.0, 4.0));
        assertEquals(1.0, call(getIn(math, "min"), 1.0, 9.0, 4.0));
        assertEquals(Math.PI, (double) math.rawget("pi"), 1e-9);
        Object[] modf = (Object[]) call(getIn(math, "modf"), 3.75);
        assertEquals(3.0, modf[0]);
        assertEquals(0.75, (double) modf[1], 1e-9);
    }

    @Test
    void stringBasics() {
        LuaTable string = (LuaTable) globals.rawget("string");
        assertEquals(5.0, call(getIn(string, "len"), "hello"));
        assertEquals("ELLO", call(getIn(string, "upper"), "ello"));
        assertEquals("ell", call(getIn(string, "sub"), "hello", 2.0, 4.0));
        assertEquals("olleh", call(getIn(string, "reverse"), "hello"));
        assertEquals("hihihi", call(getIn(string, "rep"), "hi", 3.0));
        assertEquals("A", call(getIn(string, "char"), 65.0));
        assertEquals(65.0, ((Object[]) call(getIn(string, "byte"), "A"))[0]);
    }

    @Test
    void stringFormat() {
        LuaTable string = (LuaTable) globals.rawget("string");
        assertEquals("x=5", call(getIn(string, "format"), "x=%d", 5.0));
        assertEquals("3.14", call(getIn(string, "format"), "%.2f", 3.14159));
        assertEquals("hi there", call(getIn(string, "format"), "%s %s", "hi", "there"));
        assertEquals("ff", call(getIn(string, "format"), "%x", 255.0));
        assertEquals("100%", call(getIn(string, "format"), "%d%%", 100.0));
    }

    @Test
    void stringFindMatchGsub() {
        LuaTable string = (LuaTable) globals.rawget("string");
        Object[] found = (Object[]) call(getIn(string, "find"), "hello world", "wor");
        assertEquals(7.0, found[0]);
        assertEquals(9.0, found[1]);

        assertEquals("world", call(getIn(string, "match"), "hello world", "%a+$"));

        Object[] capped = (Object[]) call(getIn(string, "find"), "key=value", "(%a+)=(%a+)");
        assertEquals("key", capped[2]);
        assertEquals("value", capped[3]);

        Object[] gsubResult = (Object[]) call(getIn(string, "gsub"), "hello world", "o", "0");
        assertEquals("hell0 w0rld", gsubResult[0]);
        assertEquals(2.0, gsubResult[1]);
    }

    @Test
    void stringGmatchIteratesAllMatches() {
        LuaTable string = (LuaTable) globals.rawget("string");
        LuaFunction iter = (LuaFunction) call(getIn(string, "gmatch"), "one two three", "%a+");
        java.util.List<Object> words = new java.util.ArrayList<>();
        while (true) {
            Object result = call(iter);
            if (result == LuaNil.INSTANCE) {
                break;
            }
            words.add(result);
        }
        assertEquals(java.util.List.of("one", "two", "three"), words);
    }

    @Test
    void stringMetatableEnablesMethodStyleIndexing() {
        LuaTable stringLib = (LuaTable) globals.rawget("string");
        LuaTable metatable = org.mycelium.lua.runtime.LuaStringMetatable.get();
        assertEquals(stringLib, metatable.rawget("__index"));
    }

    @Test
    void tableInsertRemoveConcatSort() {
        LuaTable table = (LuaTable) globals.rawget("table");
        LuaTable t = new LuaTable();
        call(getIn(table, "insert"), t, "a");
        call(getIn(table, "insert"), t, "b");
        call(getIn(table, "insert"), t, 1.0, "z");
        assertEquals("z", t.rawget(1L));
        assertEquals("a", t.rawget(2L));
        assertEquals("b", t.rawget(3L));

        Object removed = call(getIn(table, "remove"), t);
        assertEquals("b", removed);
        assertEquals(2, t.rawlen());

        assertEquals("z-a", call(getIn(table, "concat"), t, "-"));

        LuaTable sortMe = new LuaTable();
        sortMe.rawset(1L, "banana");
        sortMe.rawset(2L, "apple");
        sortMe.rawset(3L, "cherry");
        call(getIn(table, "sort"), sortMe);
        assertEquals("apple", sortMe.rawget(1L));
        assertEquals("banana", sortMe.rawget(2L));
        assertEquals("cherry", sortMe.rawget(3L));
    }
}

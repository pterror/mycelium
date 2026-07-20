package org.mycelium.lua.stdlib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coroutine tests, evaluating real Lua source through the full parser/interpreter
 * (unlike {@link LuaStdLibTest}, which invokes builtins' call targets directly) since coroutines
 * need a real {@code TruffleLanguage.Env} (for {@code env.newTruffleThreadBuilder}) that only
 * exists behind an actual {@code lua} polyglot {@link Context}.
 */
class LuaCoroutineLibTest {

    private Context context;

    @BeforeEach
    void setUp() {
        // Coroutines are thread-per-coroutine: the backing thread must be allowed to enter this
        // context (Lua 5.1 coroutines never run concurrently with the thread that resumed them —
        // see LuaLanguage#isThreadAccessAllowed — but Truffle still requires allowCreateThread).
        context = Context.newBuilder("lua").allowCreateThread(true).build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private Value eval(String source) {
        return context.eval("lua", source);
    }

    @Test
    void createAndStatusBeforeResume() {
        Value status = eval("""
                local co = coroutine.create(function() end)
                return coroutine.status(co)
                """);
        assertEquals("suspended", status.asString());
    }

    @Test
    void basicResumeAndYieldRoundTrip() {
        Value result = eval("""
                local co = coroutine.create(function(a, b)
                    local x = coroutine.yield(a + b)
                    return x * 2
                end)
                local ok1, sum = coroutine.resume(co, 3, 4)
                local statusAfterYield = coroutine.status(co)
                local ok2, doubled = coroutine.resume(co, 10)
                return {ok1, sum, statusAfterYield, ok2, doubled, coroutine.status(co)}
                """);
        assertTrue(result.getArrayElement(0).asBoolean());
        assertEquals(7.0, result.getArrayElement(1).asDouble());
        assertEquals("suspended", result.getArrayElement(2).asString());
        assertTrue(result.getArrayElement(3).asBoolean());
        assertEquals(20.0, result.getArrayElement(4).asDouble());
        assertEquals("dead", result.getArrayElement(5).asString());
    }

    @Test
    void resumeAfterDeadFails() {
        Value result = eval("""
                local co = coroutine.create(function() return 1 end)
                coroutine.resume(co)
                local ok, err = coroutine.resume(co)
                return {ok, err}
                """);
        assertFalse(result.getArrayElement(0).asBoolean());
        assertEquals("cannot resume dead coroutine", result.getArrayElement(1).asString());
    }

    @Test
    void errorInCoroutineIsReportedNotThrown() {
        Value result = eval("""
                local co = coroutine.create(function() error("boom") end)
                local ok, err = coroutine.resume(co)
                return {ok, err}
                """);
        assertFalse(result.getArrayElement(0).asBoolean());
        assertTrue(result.getArrayElement(1).asString().matches(".*:\\d+: boom"));
    }

    @Test
    void wrapPropagatesValuesAndErrors() {
        Value sum = eval("""
                local gen = coroutine.wrap(function()
                    coroutine.yield(1)
                    coroutine.yield(2)
                    return 3
                end)
                return {gen(), gen(), gen()}
                """);
        assertEquals(1.0, sum.getArrayElement(0).asDouble());
        assertEquals(2.0, sum.getArrayElement(1).asDouble());
        assertEquals(3.0, sum.getArrayElement(2).asDouble());
    }

    @Test
    void wrapThrowsHostLuaExceptionOnError() {
        Value wrapped = eval("""
                return coroutine.wrap(function() error("kaboom") end)
                """);
        try {
            wrapped.execute();
            throw new AssertionError("expected an error");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("kaboom"));
        }
    }

    @Test
    void yieldFromNestedCallDepth() {
        Value result = eval("""
                local function inner()
                    coroutine.yield("from inner")
                end
                local function outer()
                    inner()
                    return "outer done"
                end
                local co = coroutine.create(outer)
                local ok1, v1 = coroutine.resume(co)
                local ok2, v2 = coroutine.resume(co)
                return {v1, v2}
                """);
        assertEquals("from inner", result.getArrayElement(0).asString());
        assertEquals("outer done", result.getArrayElement(1).asString());
    }

    @Test
    void typeOfCoroutineIsThread() {
        Value result = eval("""
                local co = coroutine.create(function() end)
                return type(co)
                """);
        assertEquals("thread", result.asString());
    }

    @Test
    void statusReflectsNormalWhileResumingAnother() {
        Value result = eval("""
                local inner = coroutine.create(function()
                    coroutine.yield()
                end)
                local outerStatus
                local outer = coroutine.create(function()
                    coroutine.resume(inner)
                    outerStatus = coroutine.status(coroutine.running())
                end)
                coroutine.resume(outer)
                return outerStatus
                """);
        assertEquals("running", result.asString());
    }
}

package org.mycelium.lua;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Spec-compliance "acid" tests for the Lua 5.1 Truffle implementation, exercising the full
 * parser/interpreter pipeline (unlike {@link org.mycelium.lua.stdlib.LuaStdLibTest}, which invokes
 * builtins' call targets directly).
 *
 * <p>Each test evaluates a self-verifying Lua chunk full of {@code assert()} calls; a passing test
 * means every assertion inside the chunk held. Tests that need to inspect a returned value do so
 * on the Java side via the {@link Value} that {@code context.eval} hands back.
 *
 * <p>Section references (&sect;N.N) are to the Lua 5.1 Reference Manual.
 */
class LuaAcidTest {

    private Context context;

    @BeforeEach
    void setUp() {
        // allowCreateThread: coroutines are thread-per-coroutine (see LuaLanguage's class doc).
        context = Context.newBuilder("lua").allowCreateThread(true).build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private Value eval(String source) {
        return context.eval("lua", source);
    }

    // ---------------------------------------------------------------------
    // 1. Types and values (§2.2)
    // ---------------------------------------------------------------------

    @Test
    void allEightTypesReported() {
        eval("""
                assert(type(nil) == "nil")
                assert(type(true) == "boolean")
                assert(type(false) == "boolean")
                assert(type(42) == "number")
                assert(type(42.5) == "number")
                assert(type("hi") == "string")
                assert(type({}) == "table")
                assert(type(print) == "function")
                assert(type(coroutine.create(function() end)) == "thread")
                """);
    }

    @Test
    void truthinessRules() {
        eval("""
                local function truthy(v) if v then return true else return false end end
                assert(truthy(true))
                assert(not truthy(false))
                assert(not truthy(nil))
                assert(truthy(0))
                assert(truthy(""))
                assert(truthy(0.0))
                assert(truthy({}))
                """);
    }

    @Test
    void integerFloatSameNumberType() {
        eval("""
                assert(1 == 1.0)
                assert(type(1) == type(1.0))
                assert(3/2 == 1.5)
                """);
    }

    // ---------------------------------------------------------------------
    // 2. Arithmetic operators (§2.5.1)
    // ---------------------------------------------------------------------

    @Test
    void basicArithmetic() {
        eval("""
                assert(2 + 3 == 5)
                assert(10 - 4 == 6)
                assert(3 * 4 == 12)
                assert(10 / 3 == 10/3)
                assert(math.abs(10/3 - 3.3333333333) < 1e-6)
                assert(10 % 3 == 1)
                assert(2 ^ 10 == 1024)
                """);
    }

    @Test
    void unaryMinus() {
        eval("""
                assert(-(-5) == 5)
                assert(-0 == 0)
                local x = 5
                assert(-x == -5)
                """);
    }

    @Test
    void stringToNumberCoercionInArithmetic() {
        eval("""
                assert("3" + "4" == 7)
                assert("1e2" + 0 == 100)
                assert("  10  " + 0 == 10)
                assert("0x10" + 0 == 16)
                """);
    }

    @Test
    void failedCoercionErrors() {
        eval("""
                local ok, err = pcall(function() return "hello" + 1 end)
                assert(not ok)
                local ok2 = pcall(function() return {} + 1 end)
                assert(not ok2)
                """);
    }

    @Test
    void moduloEdgeCases() {
        eval("""
                assert(-1 % 5 == 4)
                assert(1 % -5 == -4)
                assert(5 % 3 == 2)
                assert(-5 % 3 == 1)
                assert(5.5 % 2 == 1.5)
                """);
    }

    @Test
    void powerOperator() {
        eval("""
                assert(math.abs(2^0.5 - math.sqrt(2)) < 1e-9)
                assert(2^0 == 1)
                assert(2^-1 == 0.5)
                local negRootIsNan = (-1)^0.5
                assert(negRootIsNan ~= negRootIsNan)
                """);
    }

    @Test
    void arithmeticMetamethods() {
        eval("""
                local mt = {}
                mt.__add = function(a, b) return "add" end
                mt.__sub = function(a, b) return "sub" end
                mt.__mul = function(a, b) return "mul" end
                mt.__div = function(a, b) return "div" end
                mt.__mod = function(a, b) return "mod" end
                mt.__pow = function(a, b) return "pow" end
                mt.__unm = function(a) return "unm" end
                local t = setmetatable({}, mt)
                assert(t + 1 == "add")
                assert(t - 1 == "sub")
                assert(t * 1 == "mul")
                assert(t / 1 == "div")
                assert(t % 1 == "mod")
                assert(t ^ 1 == "pow")
                assert(-t == "unm")
                """);
    }

    // ---------------------------------------------------------------------
    // 3. Comparison operators (§2.5.2)
    // ---------------------------------------------------------------------

    @Test
    void numberComparisons() {
        eval("""
                assert(1 < 2)
                assert(not (2 < 1))
                assert(1 <= 1)
                assert(not (2 <= 1))
                assert(2 > 1)
                assert(1 >= 1)
                """);
    }

    @Test
    void stringComparisonsLexicographic() {
        eval("""
                assert("abc" < "abd")
                assert("a" < "b")
                assert("abc" <= "abc")
                assert(not ("b" < "a"))
                """);
    }

    @Test
    void equalityAcrossTypesIsAlwaysFalse() {
        eval("""
                assert(1 ~= "1")
                assert(0 ~= false)
                assert(nil ~= false)
                assert(not (1 == "1"))
                """);
    }

    @Test
    void referenceEqualityForTablesAndFunctions() {
        eval("""
                local t1, t2 = {}, {}
                assert(t1 ~= t2)
                assert(t1 == t1)
                local f1 = function() end
                local f2 = function() end
                assert(f1 ~= f2)
                assert(f1 == f1)
                """);
    }

    @Test
    void notEqualIsNegationOfEqual() {
        eval("""
                assert((1 ~= 2) == not (1 == 2))
                assert((1 ~= 1) == not (1 == 1))
                """);
    }

    @Test
    void comparisonMetamethods() {
        eval("""
                local mt = {}
                mt.__eq = function(a, b) return true end
                mt.__lt = function(a, b) return true end
                mt.__le = function(a, b) return true end
                local a = setmetatable({}, mt)
                local b = setmetatable({}, mt)
                assert(a == b)
                assert(a < b)
                assert(a <= b)
                """);
    }

    // ---------------------------------------------------------------------
    // 4. Logical operators (§2.5.3)
    // ---------------------------------------------------------------------

    @Test
    void andOrReturnOperandsNotBooleans() {
        eval("""
                assert(1 and 2 == 2)
                assert((1 and 2) == 2)
                assert((nil and 1) == nil)
                assert((1 or 2) == 1)
                assert((nil or 2) == 2)
                assert((false or nil) == nil)
                """);
    }

    @Test
    void shortCircuitEvaluation() {
        eval("""
                local called = false
                local function boom() called = true; error("boom") end
                local r = true or boom()
                assert(not called)
                local r2 = false and boom()
                assert(not called)
                assert(r2 == false)
                """);
    }

    @Test
    void notOperatorReturnsBoolean() {
        eval("""
                assert(not nil == true)
                assert(not false == true)
                assert(not 0 == false)
                assert(not "" == false)
                assert(type(not 5) == "boolean")
                """);
    }

    // ---------------------------------------------------------------------
    // 5. String concatenation (§2.5.4)
    // ---------------------------------------------------------------------

    @Test
    void stringConcatenation() {
        eval("""
                assert("hello" .. " " .. "world" == "hello world")
                assert(1 .. 2 == "12")
                assert(1.5 .. "" == "1.5")
                """);
    }

    @Test
    void concatMetamethod() {
        eval("""
                local mt = { __concat = function(a, b) return "concatenated" end }
                local t = setmetatable({}, mt)
                assert((t .. "x") == "concatenated")
                assert(("x" .. t) == "concatenated")
                """);
    }

    // ---------------------------------------------------------------------
    // 6. Length operator (§2.5.5)
    // ---------------------------------------------------------------------

    @Test
    void lengthOperator() {
        eval("""
                assert(#"hello" == 5)
                assert(#"" == 0)
                assert(#{1,2,3} == 3)
                assert(#{} == 0)
                """);
    }

    @Test
    void lengthMetamethod() {
        eval("""
                local mt = { __len = function(t) return 42 end }
                local t = setmetatable({}, mt)
                assert(#t == 42)
                """);
    }

    // ---------------------------------------------------------------------
    // 7. Table constructors (§2.5.7)
    // ---------------------------------------------------------------------

    @Test
    void listStyleTableConstructor() {
        eval("""
                local t = {1, 2, 3}
                assert(t[1] == 1 and t[2] == 2 and t[3] == 3)
                assert(#t == 3)
                """);
    }

    @Test
    void recordStyleTableConstructor() {
        eval("""
                local t = {x = 1, y = 2}
                assert(t.x == 1 and t.y == 2)
                """);
    }

    @Test
    void bracketStyleTableConstructor() {
        eval("""
                local t = {[1] = 1, ["key"] = "val"}
                assert(t[1] == 1)
                assert(t["key"] == "val")
                """);
    }

    @Test
    void mixedTableConstructor() {
        eval("""
                local t = {1, 2, x = 3, [5] = 5}
                assert(t[1] == 1 and t[2] == 2 and t.x == 3 and t[5] == 5)
                """);
    }

    @Test
    void trailingCommaAndSemicolonSeparators() {
        eval("""
                local t1 = {1, 2, 3,}
                assert(#t1 == 3)
                local t2 = {1; 2; 3}
                assert(#t2 == 3 and t2[1] == 1 and t2[2] == 2 and t2[3] == 3)
                """);
    }

    @Test
    void lastExpressionExpandsInTableConstructor() {
        eval("""
                local function f() return 1, 2, 3 end
                local t = {f()}
                assert(#t == 3 and t[1] == 1 and t[2] == 2 and t[3] == 3)
                local t2 = {f(), f()}
                assert(#t2 == 4)
                assert(t2[1] == 1 and t2[2] == 1 and t2[3] == 2 and t2[4] == 3)
                """);
    }

    // ---------------------------------------------------------------------
    // 8. Control flow
    // ---------------------------------------------------------------------

    @Test
    void ifElseifElse() {
        eval("""
                local function classify(n)
                    if n < 0 then return "neg"
                    elseif n == 0 then return "zero"
                    elseif n < 10 then return "small"
                    else return "big"
                    end
                end
                assert(classify(-1) == "neg")
                assert(classify(0) == "zero")
                assert(classify(5) == "small")
                assert(classify(100) == "big")
                """);
    }

    @Test
    void whileLoopAndBreak() {
        eval("""
                local i = 0
                while i < 5 do i = i + 1 end
                assert(i == 5)

                local j = 0
                while true do
                    j = j + 1
                    if j == 3 then break end
                end
                assert(j == 3)

                local count = 0
                while false do count = count + 1 end
                assert(count == 0)
                """);
    }

    @Test
    void repeatUntilExecutesAtLeastOnce() {
        eval("""
                local count = 0
                repeat
                    count = count + 1
                until true
                assert(count == 1)

                local i = 0
                repeat
                    local done = (i >= 3)
                    i = i + 1
                until done
                assert(i == 4)
                """);
    }

    @Test
    void numericForLoop() {
        eval("""
                local sum = 0
                for i = 1, 5 do sum = sum + i end
                assert(sum == 15)

                local desc = {}
                for i = 5, 1, -1 do desc[#desc+1] = i end
                assert(#desc == 5 and desc[1] == 5 and desc[5] == 1)

                local count = 0
                for i = 1, 10, 2 do count = count + 1 end
                assert(count == 5)

                local floats = {}
                for i = 1.0, 2.0, 0.5 do floats[#floats+1] = i end
                assert(#floats == 3)

                local ranEmpty = true
                for i = 5, 1 do ranEmpty = false end
                assert(ranEmpty)
                """);
    }

    @Test
    void genericForWithPairsAndIpairs() {
        eval("""
                local t = {10, 20, 30}
                local sum = 0
                for i, v in ipairs(t) do sum = sum + v end
                assert(sum == 60)

                local t2 = {a = 1, b = 2, c = 3}
                local count = 0
                for k, v in pairs(t2) do count = count + 1 end
                assert(count == 3)
                """);
    }

    @Test
    void genericForWithCustomIterator() {
        eval("""
                local function range(n)
                    local i = 0
                    return function()
                        i = i + 1
                        if i <= n then return i end
                    end
                end
                local sum = 0
                for v in range(4) do sum = sum + v end
                assert(sum == 10)
                """);
    }

    @Test
    void breakExitsInnermostLoopOnly() {
        eval("""
                local outerCount = 0
                for i = 1, 3 do
                    outerCount = outerCount + 1
                    for j = 1, 3 do
                        if j == 2 then break end
                    end
                end
                assert(outerCount == 3)
                """);
    }

    // ---------------------------------------------------------------------
    // 9. Functions
    // ---------------------------------------------------------------------

    @Test
    void basicFunctionDefinitionAndCall() {
        eval("""
                local function add(a, b) return a + b end
                assert(add(2, 3) == 5)
                """);
    }

    @Test
    void multipleReturnValues() {
        eval("""
                local function f() return 1, 2, 3 end
                local a, b, c = f()
                assert(a == 1 and b == 2 and c == 3)
                """);
    }

    @Test
    void returnValueAdjustment() {
        eval("""
                local function f() return 1, 2, 3 end
                local a, b = f()
                assert(a == 1 and b == 2)

                local x, y, z, w = f()
                assert(x == 1 and y == 2 and z == 3 and w == nil)

                local single = (f())
                assert(single == 1)
                """);
    }

    @Test
    void varargsBasics() {
        eval("""
                local function count(...) return select('#', ...) end
                assert(count(1, 2, 3) == 3)
                assert(count() == 0)

                local function sum(...)
                    local args = {...}
                    local s = 0
                    for _, v in ipairs(args) do s = s + v end
                    return s
                end
                assert(sum(1, 2, 3, 4) == 10)

                local function firstTwo(...)
                    local a, b = ...
                    return a, b
                end
                local a, b = firstTwo(7, 8, 9)
                assert(a == 7 and b == 8)
                """);
    }

    @Test
    void closuresAndUpvalues() {
        eval("""
                local function makeCounter()
                    local n = 0
                    return function()
                        n = n + 1
                        return n
                    end
                end
                local c1 = makeCounter()
                local c2 = makeCounter()
                assert(c1() == 1)
                assert(c1() == 2)
                assert(c2() == 1)
                assert(c1() == 3)
                """);
    }

    @Test
    void sharedUpvalueBetweenClosures() {
        eval("""
                local function makePair()
                    local n = 0
                    local function inc() n = n + 1 end
                    local function get() return n end
                    return inc, get
                end
                local inc, get = makePair()
                assert(get() == 0)
                inc()
                inc()
                assert(get() == 2)
                """);
    }

    @Test
    void recursiveFactorial() {
        eval("""
                local function fact(n)
                    if n <= 1 then return 1 end
                    return n * fact(n - 1)
                end
                assert(fact(5) == 120)
                assert(fact(0) == 1)
                """);
    }

    @Test
    void mutualRecursionViaLocals() {
        eval("""
                local isEven, isOdd
                isEven = function(n) if n == 0 then return true else return isOdd(n - 1) end end
                isOdd = function(n) if n == 0 then return false else return isEven(n - 1) end end
                assert(isEven(10))
                assert(isOdd(7))
                assert(not isEven(7))
                """);
    }

    @Test
    void methodCallSyntaxPassesSelf() {
        eval("""
                local t = {value = 10}
                function t:getValue() return self.value end
                function t:addTo(n) return self.value + n end
                assert(t:getValue() == 10)
                assert(t:addTo(5) == 15)
                """);
    }

    @Test
    void stringCallSyntax() {
        eval("""
                local function f(s) return s .. "!" end
                assert(f "hello" == "hello!")
                """);
    }

    @Test
    void tableCallSyntax() {
        eval("""
                local function f(t) return t[1] + t[2] + t[3] end
                assert(f {1, 2, 3} == 6)
                """);
    }

    // ---------------------------------------------------------------------
    // 10. Tables and metatables (§2.8)
    // ---------------------------------------------------------------------

    @Test
    void rawgetRawsetBypassMetamethods() {
        eval("""
                local t = setmetatable({}, {
                    __index = function() return "intercepted" end,
                    __newindex = function() error("should not be called") end,
                })
                rawset(t, "x", 1)
                assert(rawget(t, "x") == 1)
                assert(rawget(t, "missing") == nil)
                """);
    }

    @Test
    void indexAsFunction() {
        eval("""
                local t = setmetatable({}, {
                    __index = function(tbl, key) return "computed:" .. key end,
                })
                assert(t.foo == "computed:foo")
                t.bar = "set"
                assert(t.bar == "set")
                """);
    }

    @Test
    void indexAsTableChainLookup() {
        eval("""
                local base = {greeting = "hi"}
                local derived = setmetatable({}, {__index = base})
                assert(derived.greeting == "hi")
                derived.greeting = "yo"
                assert(derived.greeting == "yo")
                assert(base.greeting == "hi")
                """);
    }

    @Test
    void newindexAsFunction() {
        eval("""
                local log = {}
                local t = setmetatable({}, {
                    __newindex = function(tbl, key, value) log[key] = value end,
                })
                t.x = 42
                assert(log.x == 42)
                assert(rawget(t, "x") == nil)
                """);
    }

    @Test
    void newindexAsTableRedirectsWrites() {
        eval("""
                local storage = {}
                local t = setmetatable({}, {__newindex = storage})
                t.x = 1
                assert(storage.x == 1)
                assert(rawget(t, "x") == nil)
                """);
    }

    @Test
    void callMetamethodMakesTableCallable() {
        eval("""
                local t = setmetatable({}, {
                    __call = function(self, a, b) return a + b end,
                })
                assert(t(3, 4) == 7)
                """);
    }

    @Test
    void tostringMetamethod() {
        eval("""
                local t = setmetatable({}, {
                    __tostring = function(self) return "custom!" end,
                })
                assert(tostring(t) == "custom!")
                """);
    }

    @Test
    void getmetatableAndSetmetatable() {
        eval("""
                local mt = {}
                local t = setmetatable({}, mt)
                assert(getmetatable(t) == mt)
                assert(getmetatable({}) == nil)
                """);
    }

    @Test
    void protectedMetatable() {
        eval("""
                local t = setmetatable({}, {__metatable = "protected"})
                assert(getmetatable(t) == "protected")
                local ok = pcall(setmetatable, t, {})
                assert(not ok)
                """);
    }

    // ---------------------------------------------------------------------
    // 11. String library (§5.4)
    // ---------------------------------------------------------------------

    @Test
    void stringByteCharRoundtrip() {
        eval("""
                assert(string.char(string.byte("A")) == "A")
                assert(string.byte("A") == 65)
                assert(string.char(72, 105) == "Hi")
                """);
    }

    @Test
    void stringLenAndSub() {
        eval("""
                assert(string.len("hello") == 5)
                assert(string.sub("hello", 2, 4) == "ell")
                assert(string.sub("hello", -3) == "llo")
                assert(string.sub("hello", -3, -2) == "ll")
                assert(string.sub("hello", 2) == "ello")
                """);
    }

    @Test
    void stringRepAndReverse() {
        eval("""
                assert(string.rep("ab", 3) == "ababab")
                assert(string.rep("x", 0) == "")
                assert(string.reverse("hello") == "olleh")
                """);
    }

    @Test
    void stringUpperLower() {
        eval("""
                assert(string.upper("Hello") == "HELLO")
                assert(string.lower("Hello") == "hello")
                """);
    }

    @Test
    void stringFindPlainAndPattern() {
        eval("""
                local s, e = string.find("hello world", "wor")
                assert(s == 7 and e == 9)

                local s2, e2, cap = string.find("key=value", "(%a+)=")
                assert(cap == "key")

                assert(string.find("a.b.c", ".", 1, true) == 2)
                """);
    }

    @Test
    void stringMatchCaptures() {
        eval("""
                assert(string.match("hello world", "%a+$") == "world")
                local y, m, d = string.match("2024-01-15", "(%d+)-(%d+)-(%d+)")
                assert(y == "2024" and m == "01" and d == "15")
                """);
    }

    @Test
    void stringGmatchIteration() {
        eval("""
                local words = {}
                for w in string.gmatch("one two three", "%a+") do
                    words[#words+1] = w
                end
                assert(#words == 3)
                assert(words[1] == "one" and words[2] == "two" and words[3] == "three")
                """);
    }

    @Test
    void stringGsubVariants() {
        eval("""
                local s, n = string.gsub("hello world", "o", "0")
                assert(s == "hell0 w0rld" and n == 2)

                local s2 = string.gsub("hello world", "%w+", function(w) return w:upper() end)
                assert(s2 == "HELLO WORLD")

                local s3 = string.gsub("$name is $age", "%$(%w+)", {name = "Bob", age = "30"})
                assert(s3 == "Bob is 30")

                local s4, n4 = string.gsub("aaaa", "a", "b", 2)
                assert(s4 == "bbaa" and n4 == 2)
                """);
    }

    @Test
    void stringFormatDirectives() {
        eval("""
                assert(string.format("%d", 42) == "42")
                assert(string.format("%s", "hi") == "hi")
                assert(string.format("%5.2f", 3.14159) == " 3.14")
                assert(string.format("%x", 255) == "ff")
                assert(string.format("%o", 8) == "10")
                assert(string.format("%%") == "%")
                assert(string.format("%5d", 3) == "    3")
                assert(string.format("%-5d|", 3) == "3    |")
                """);
    }

    @Test
    void stringFormatNegativeHex() {
        eval("""
                assert(string.format("%x", -1) == "ffffffff")
                """);
    }

    @Test
    void stringFormatQAndE() {
        eval("""
                local q = string.format("%q", 'he said "hi"\\n')
                assert(type(q) == "string")
                local e = string.format("%e", 12345.6789)
                assert(type(e) == "string")
                local g = string.format("%g", 0.0001)
                assert(type(g) == "string")
                """);
    }

    @Test
    void stringMethodSyntax() {
        eval("""
                assert(("hello"):upper() == "HELLO")
                assert(("hello"):len() == 5)
                assert(("  hi  "):match("%S+") == "hi")
                """);
    }

    // ---------------------------------------------------------------------
    // 12. Math library (§5.6)
    // ---------------------------------------------------------------------

    @Test
    void mathAbsCeilFloor() {
        eval("""
                assert(math.abs(-5) == 5)
                assert(math.abs(5) == 5)
                assert(math.ceil(2.1) == 3)
                assert(math.floor(2.9) == 2)
                assert(math.floor(-2.1) == -3)
                """);
    }

    @Test
    void mathSqrtAndTrig() {
        eval("""
                assert(math.sqrt(9) == 3)
                assert(math.abs(math.sin(0)) < 1e-9)
                assert(math.abs(math.cos(0) - 1) < 1e-9)
                assert(math.abs(math.pi - 3.14159) < 1e-4)
                """);
    }

    @Test
    void mathHugeAndMinMax() {
        eval("""
                assert(math.huge > 1e300)
                assert(math.max(1, 9, 4) == 9)
                assert(math.min(1, 9, 4) == 1)
                assert(math.max(5) == 5)
                """);
    }

    @Test
    void mathRandomInRange() {
        eval("""
                for i = 1, 50 do
                    local r = math.random()
                    assert(r >= 0 and r < 1)
                end
                for i = 1, 50 do
                    local r = math.random(10)
                    assert(r >= 1 and r <= 10)
                end
                for i = 1, 50 do
                    local r = math.random(5, 10)
                    assert(r >= 5 and r <= 10)
                end
                """);
    }

    @Test
    void mathModfReturnsTwoValues() {
        eval("""
                local intPart, fracPart = math.modf(3.75)
                assert(intPart == 3)
                assert(math.abs(fracPart - 0.75) < 1e-9)
                """);
    }

    // ---------------------------------------------------------------------
    // 13. Table library (§5.5)
    // ---------------------------------------------------------------------

    @Test
    void tableInsertAtEndAndPosition() {
        eval("""
                local t = {1, 2, 3}
                table.insert(t, 4)
                assert(#t == 4 and t[4] == 4)
                table.insert(t, 1, 0)
                assert(#t == 5 and t[1] == 0 and t[2] == 1)
                """);
    }

    @Test
    void tableRemoveFromEndAndPosition() {
        eval("""
                local t = {1, 2, 3, 4}
                local last = table.remove(t)
                assert(last == 4 and #t == 3)
                local first = table.remove(t, 1)
                assert(first == 1 and #t == 2 and t[1] == 2)
                """);
    }

    @Test
    void tableConcatWithSeparator() {
        eval("""
                assert(table.concat({1, 2, 3}, ",") == "1,2,3")
                assert(table.concat({"a", "b", "c"}) == "abc")
                assert(table.concat({1, 2, 3, 4}, "-", 2, 3) == "2-3")
                """);
    }

    @Test
    void tableSortDefaultAndCustom() {
        eval("""
                local t = {3, 1, 4, 1, 5, 9, 2, 6}
                table.sort(t)
                for i = 1, #t - 1 do assert(t[i] <= t[i+1]) end

                local t2 = {3, 1, 4, 1, 5}
                table.sort(t2, function(a, b) return a > b end)
                for i = 1, #t2 - 1 do assert(t2[i] >= t2[i+1]) end
                """);
    }

    @Test
    void tableMaxn() {
        eval("""
                local t = {1, 2, 3}
                assert(table.maxn(t) == 3)
                local t2 = {}
                t2[10] = "x"
                assert(table.maxn(t2) == 10)
                """);
    }

    // ---------------------------------------------------------------------
    // 14. Coroutines (§2.11, §5.2)
    // ---------------------------------------------------------------------

    @Test
    void basicCreateResumeYieldCycle() {
        eval("""
                local co = coroutine.create(function(x)
                    local y = coroutine.yield(x + 1)
                    return y + 1
                end)
                local ok1, v1 = coroutine.resume(co, 10)
                assert(ok1 and v1 == 11)
                local ok2, v2 = coroutine.resume(co, 20)
                assert(ok2 and v2 == 21)
                """);
    }

    @Test
    void coroutineStatusTransitions() {
        eval("""
                local co = coroutine.create(function()
                    coroutine.yield()
                end)
                assert(coroutine.status(co) == "suspended")
                coroutine.resume(co)
                assert(coroutine.status(co) == "suspended")
                coroutine.resume(co)
                assert(coroutine.status(co) == "dead")
                """);
    }

    @Test
    void errorInCoroutineReturnsFalsePlusError() {
        eval("""
                local co = coroutine.create(function() error("kaboom") end)
                local ok, err = coroutine.resume(co)
                assert(not ok)
                assert(err == "kaboom")
                assert(coroutine.status(co) == "dead")
                """);
    }

    @Test
    void coroutineWrapFunctionInterface() {
        eval("""
                local gen = coroutine.wrap(function()
                    for i = 1, 3 do coroutine.yield(i) end
                end)
                assert(gen() == 1)
                assert(gen() == 2)
                assert(gen() == 3)
                """);
    }

    @Test
    void nestedCoroutines() {
        eval("""
                local inner = coroutine.create(function()
                    coroutine.yield("inner-yield")
                    return "inner-done"
                end)
                local outer = coroutine.create(function()
                    local ok, v = coroutine.resume(inner)
                    coroutine.yield(v)
                    local ok2, v2 = coroutine.resume(inner)
                    return v2
                end)
                local ok1, r1 = coroutine.resume(outer)
                assert(r1 == "inner-yield")
                local ok2, r2 = coroutine.resume(outer)
                assert(r2 == "inner-done")
                """);
    }

    @Test
    void statusIsRunningForCoroutineCheckingItself() {
        // Note: by the time `outer` checks its own status here, `inner` has already yielded back
        // to it, so `outer` is once again the currently-executing coroutine ("running") — not
        // "normal" (that status applies to a coroutine that is itself currently *suspended* while
        // waiting on one it resumed, i.e. observed from a third party while `inner` is still live).
        eval("""
                local inner = coroutine.create(function() coroutine.yield() end)
                local outerStatus
                -- Forward-declare `outer` so the closure captures the local (not a global): a
                -- `local x = f(x)`-shaped initializer never sees its own new local (§2.4.7-ish
                -- scoping rule — the new local only comes into scope after its declaring statement).
                local outer
                outer = coroutine.create(function()
                    coroutine.resume(inner)
                    outerStatus = coroutine.status(outer)
                end)
                coroutine.resume(outer)
                assert(outerStatus == "running")
                """);
    }

    // ---------------------------------------------------------------------
    // 15. Error handling (§2.4.7, §5.1)
    // ---------------------------------------------------------------------

    @Test
    void errorWithStringMessage() {
        eval("""
                local ok, err = pcall(function() error("boom") end)
                assert(not ok)
                assert(err == "boom")
                """);
    }

    @Test
    void errorWithTableObject() {
        eval("""
                local errObj = {code = 42}
                local ok, err = pcall(function() error(errObj) end)
                assert(not ok)
                assert(err == errObj)
                assert(err.code == 42)
                """);
    }

    @Test
    void pcallSuccessAndFailure() {
        eval("""
                local ok, result = pcall(function() return 1 + 1 end)
                assert(ok and result == 2)

                local ok2, err = pcall(function() error("fail") end)
                assert(not ok2 and err == "fail")

                local ok3, a, b = pcall(function() return 1, 2 end)
                assert(ok3 and a == 1 and b == 2)
                """);
    }

    @Test
    void xpcallWithErrorHandler() {
        eval("""
                local handlerCalled = false
                local ok, result = xpcall(function() error("oops") end, function(e)
                    handlerCalled = true
                    return "handled:" .. e
                end)
                assert(not ok)
                assert(handlerCalled)
                assert(result == "handled:oops")
                """);
    }

    @Test
    void deepRecursionErrorsRatherThanCrashing() {
        eval("""
                local function recurse(n) return recurse(n + 1) end
                local ok, err = pcall(recurse, 1)
                assert(not ok)
                """);
    }

    // ---------------------------------------------------------------------
    // 16. loadstring / load
    // ---------------------------------------------------------------------

    @Test
    void loadstringExecutesCode() {
        eval("""
                local f = loadstring("return 1 + 2")
                assert(f() == 3)
                """);
    }

    @Test
    void loadstringInvalidSyntaxReturnsNilAndError() {
        eval("""
                local f, err = loadstring("invalid (")
                assert(f == nil)
                assert(type(err) == "string")
                """);
    }

    @Test
    void loadedChunkSeesGlobalsNotCallerLocals() {
        eval("""
                globalVar = 99
                local localVar = 123
                local f = loadstring("return globalVar")
                assert(f() == 99)

                local f2 = loadstring("return localVar")
                local ok, result = pcall(f2)
                assert(ok)
                assert(result == nil)
                """);
    }

    // ---------------------------------------------------------------------
    // 17. Numeric edge cases
    // ---------------------------------------------------------------------

    @Test
    void nanAndInfinityBehavior() {
        eval("""
                local nan = 0/0
                assert(nan ~= nan)
                assert(math.huge + 1 == math.huge)
                assert(-math.huge < 0)
                """);
    }

    @Test
    void tostringFormatting() {
        eval("""
                assert(tostring(1) == "1")
                assert(tostring(1.5) == "1.5")
                assert(not string.find(tostring(1), "%."))
                """);
    }

    @Test
    void tonumberWithBases() {
        eval("""
                assert(tonumber("0xff") == 255)
                assert(tonumber("10", 2) == 2)
                assert(tonumber("z", 36) == 35)
                assert(tonumber("ff", 16) == 255)
                assert(tonumber("not a number") == nil)
                """);
    }

    @Test
    void largeNumbers() {
        eval("""
                assert(1e308 > 1e307)
                assert(1e308 * 10 == math.huge)
                """);
    }

    @Test
    void decimalLiteralWithLeadingZeros() {
        // §2.1: a decimal numeral is plain digits with an optional fractional part/exponent —
        // leading zeros carry no special meaning (no octal interpretation, unlike C).
        eval("""
                assert(00001 == 1)
                assert(007 == 7)
                assert(00001.5 == 1.5)
                """);
    }

    @Test
    void hexFloatLiteralsAreNotValidLua51Syntax() {
        // §2.1: Lua 5.1's hex numeral is integer-only — no fractional part, no binary/'p'
        // exponent (that syntax is a 5.2+ / C99-strtod extension). "0x.1e2p2" must therefore be
        // rejected at the lexer, not silently parsed as a float.
        assertThrows(PolyglotException.class, () -> eval("return 0x.1e2p2"));
    }

    // ---------------------------------------------------------------------
    // 18. Scoping
    // ---------------------------------------------------------------------

    @Test
    void localShadowsGlobal() {
        eval("""
                x = "global"
                local function f()
                    local x = "local"
                    return x
                end
                assert(f() == "local")
                assert(x == "global")
                """);
    }

    @Test
    void doBlockCreatesScope() {
        eval("""
                local x = 1
                do
                    local x = 2
                    assert(x == 2)
                end
                assert(x == 1)
                """);
    }

    @Test
    void nestedLocalScoping() {
        eval("""
                local a = 1
                do
                    local a = 2
                    do
                        local a = 3
                        assert(a == 3)
                    end
                    assert(a == 2)
                end
                assert(a == 1)
                """);
    }

    @Test
    void upvalueSharingWithinSameScope() {
        eval("""
                local shared = 0
                local function inc() shared = shared + 1 end
                local function dec() shared = shared - 1 end
                inc(); inc(); inc(); dec()
                assert(shared == 2)
                """);
    }

    // ---------------------------------------------------------------------
    // Additional value-returning tests (verified from Java side)
    // ---------------------------------------------------------------------

    @Test
    void topLevelReturnValueCrossesPolyglotBoundary() {
        Value result = eval("return 1 + 2 * 3");
        assertEquals(7.0, result.asDouble());
    }

    @Test
    void assertFailurePropagatesAsPolyglotException() {
        assertThrows(PolyglotException.class, () -> eval("assert(1 == 2, 'nope')"));
    }

    @Test
    void multipleAssignmentSwap() {
        eval("""
                local a, b = 1, 2
                a, b = b, a
                assert(a == 2 and b == 1)
                """);
    }

    // ---------------------------------------------------------------------
    // 19. io library (§5.7)
    // ---------------------------------------------------------------------

    @Test
    void ioWriteAndReadFile() throws Exception {
        Path tmp = Files.createTempFile("lua-io-test", ".txt");
        try {
            String path = tmp.toAbsolutePath().toString();
            eval(String.format("""
                    local f = assert(io.open("%s", "w"))
                    f:write("hello world")
                    f:close()
                    local f2 = assert(io.open("%s", "r"))
                    local content = f2:read("*a")
                    f2:close()
                    assert(content == "hello world", "got: " .. tostring(content))
                    """, path, path));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void ioReadFormats() throws Exception {
        Path tmp = Files.createTempFile("lua-io-formats", ".txt");
        Path tmp2 = Files.createTempFile("lua-io-formats2", ".txt");
        try {
            Files.writeString(tmp, "first line\n123 45\nrest of the file content");
            Files.writeString(tmp2, "99 rest\nignored\n");
            String path = tmp.toAbsolutePath().toString();
            String path2 = tmp2.toAbsolutePath().toString();
            eval(String.format("""
                    local f = assert(io.open("%s", "r"))
                    assert(f:read("*l") == "first line")
                    assert(f:read("*n") == 123)
                    assert(f:read("*n") == 45)
                    assert(f:read(4) == "\\nres")
                    assert(f:read("*a") == "t of the file content")
                    f:close()

                    local f2 = assert(io.open("%s", "r"))
                    local n, l = f2:read("*n", "*l")
                    assert(n == 99, "got n=" .. tostring(n))
                    assert(l == " rest", "got l=" .. tostring(l))
                    f2:close()
                    """, path, path2));
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(tmp2);
        }
    }

    @Test
    void ioLines() throws Exception {
        Path tmp = Files.createTempFile("lua-io-lines", ".txt");
        try {
            Files.writeString(tmp, "alpha\nbeta\ngamma\n");
            String path = tmp.toAbsolutePath().toString();
            eval(String.format("""
                    local lines = {}
                    for line in io.lines("%s") do
                        lines[#lines+1] = line
                    end
                    assert(#lines == 3)
                    assert(lines[1] == "alpha")
                    assert(lines[2] == "beta")
                    assert(lines[3] == "gamma")
                    """, path));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void ioSeek() throws Exception {
        Path tmp = Files.createTempFile("lua-io-seek", ".txt");
        try {
            String path = tmp.toAbsolutePath().toString();
            eval(String.format("""
                    local f = assert(io.open("%s", "w"))
                    f:write("0123456789")
                    f:close()

                    local f2 = assert(io.open("%s", "r"))
                    assert(f2:seek("set", 5) == 5)
                    assert(f2:read(2) == "56")
                    assert(f2:seek("cur") == 7)
                    assert(f2:seek("end") == 10)
                    assert(f2:seek("set", 0) == 0)
                    assert(f2:read("*a") == "0123456789")
                    f2:close()
                    """, path, path));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void ioType() throws Exception {
        Path tmp = Files.createTempFile("lua-io-type", ".txt");
        try {
            String path = tmp.toAbsolutePath().toString();
            eval(String.format("""
                    assert(io.type(io.stdout) == "file")
                    local f = assert(io.open("%s", "w"))
                    f:close()
                    assert(io.type(f) == "closed file")
                    assert(io.type("string") == nil)
                    assert(io.type(42) == nil)
                    """, path));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void ioStdStreams() {
        eval("""
                assert(io.stdin ~= nil)
                assert(io.stdout ~= nil)
                assert(io.stderr ~= nil)
                assert(io.type(io.stdin) == "file")
                assert(io.type(io.stdout) == "file")
                assert(io.type(io.stderr) == "file")
                """);
    }

    @Test
    void ioDefaultInputOutput() throws Exception {
        Path tmp = Files.createTempFile("lua-io-default", ".txt");
        try {
            String path = tmp.toAbsolutePath().toString();
            eval(String.format("""
                    io.output("%s")
                    io.write("hello default")
                    io.close()

                    local f2 = assert(io.open("%s", "r"))
                    local content = f2:read("*a")
                    f2:close()
                    assert(content == "hello default", "got: " .. tostring(content))
                    """, path, path));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void typeOfFileHandle() {
        eval("""
                assert(type(io.stdout) == "userdata")
                """);
    }

    // ---------------------------------------------------------------------
    // 20. os library (§5.8)
    // ---------------------------------------------------------------------

    @Test
    void osClock() {
        eval("""
                local c = os.clock()
                assert(type(c) == "number")
                assert(c >= 0)
                """);
    }

    @Test
    void osTime() {
        eval("""
                local t = os.time()
                assert(type(t) == "number")
                assert(t > 1000000000)
                """);
    }

    @Test
    void osDate() {
        eval("""
                local t = os.date("*t")
                assert(type(t) == "table")
                assert(type(t.year) == "number")
                assert(type(t.month) == "number")
                assert(type(t.day) == "number")
                assert(type(t.hour) == "number")
                assert(type(t.min) == "number")
                assert(type(t.sec) == "number")
                assert(type(t.wday) == "number")
                assert(type(t.yday) == "number")

                local y = os.date("%Y")
                assert(type(y) == "string")
                assert(#y == 4)

                local h = os.date("!%H", 0)
                assert(h == "00", "got: " .. tostring(h))
                """);
    }

    @Test
    void osDifftime() {
        eval("""
                assert(os.difftime(100, 40) == 60)
                local t1 = os.time()
                local t2 = t1 + 10
                assert(os.difftime(t2, t1) == 10)
                """);
    }

    @Test
    void osGetenv() {
        // os.getenv reaches out through TruffleLanguage.Env#getEnvironment(), which honors the
        // polyglot Context's environment-access policy; the shared `context` from setUp() doesn't
        // opt into that (matching every other test's minimal-permissions default), so this test
        // builds its own context with environment access enabled rather than widening the default
        // for every other test in the file.
        try (Context envContext = Context.newBuilder("lua")
                .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.INHERIT)
                .build()) {
            envContext.eval("lua", """
                    local p = os.getenv("PATH")
                    assert(type(p) == "string")
                    assert(os.getenv("NONEXISTENT_VAR_12345") == nil)
                    """);
        }
    }

    @Test
    void osTmpname() {
        eval("""
                local n = os.tmpname()
                assert(type(n) == "string")
                assert(#n > 0)
                """);
    }

    @Test
    void osRemoveAndRename() throws Exception {
        Path tmp = Files.createTempFile("lua-os-remove", ".txt");
        Path renameTarget = tmp.resolveSibling(tmp.getFileName() + ".renamed");
        Files.deleteIfExists(renameTarget);
        try {
            String path = tmp.toAbsolutePath().toString();
            String missingPath = path + ".does-not-exist";
            String renamedPath = renameTarget.toAbsolutePath().toString();
            eval(String.format("""
                    assert(os.remove("%s") == true)

                    local ok, err = os.remove("%s")
                    assert(ok == nil)
                    assert(type(err) == "string")

                    local f = assert(io.open("%s", "w"))
                    f:write("data")
                    f:close()
                    assert(os.rename("%s", "%s") == true)
                    local f2 = assert(io.open("%s", "r"))
                    assert(f2:read("*a") == "data")
                    f2:close()

                    local ok2, err2 = os.rename("%s", "%s")
                    assert(ok2 == nil)
                    assert(type(err2) == "string")
                    """,
                    path, missingPath, path, path, renamedPath, renamedPath, missingPath, missingPath + ".x"));
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(renameTarget);
        }
    }

    @Test
    void osSetlocale() {
        eval("""
                local loc = os.setlocale(nil)
                assert(type(loc) == "string")
                assert(#loc > 0)
                """);
    }

    // ---------------------------------------------------------------------
    // 21. require / package (§5.3)
    // ---------------------------------------------------------------------

    @Test
    void requireLoadsFile() throws Exception {
        Path dir = Files.createTempDirectory("lua-require");
        try {
            Files.writeString(dir.resolve("mod.lua"), "return {value = 42}");
            String dirPath = dir.toAbsolutePath().toString();
            eval(String.format("""
                    package.path = "%s/?.lua;" .. package.path
                    local m = require("mod")
                    assert(type(m) == "table")
                    assert(m.value == 42)
                    """, dirPath));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void requireCachesModules() throws Exception {
        Path dir = Files.createTempDirectory("lua-require-cache");
        try {
            Files.writeString(dir.resolve("mod.lua"), "return {}");
            String dirPath = dir.toAbsolutePath().toString();
            eval(String.format("""
                    package.path = "%s/?.lua;" .. package.path
                    local m1 = require("mod")
                    local m2 = require("mod")
                    assert(m1 == m2)
                    """, dirPath));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void requirePreload() {
        eval("""
                package.preload["mymod"] = function() return {x = 42} end
                local m = require("mymod")
                assert(m.x == 42)
                """);
    }

    @Test
    void requireNotFoundError() {
        eval("""
                local ok, err = pcall(require, "nonexistent_module_xyz")
                assert(not ok)
                assert(type(err) == "string")
                assert(string.find(err, "nonexistent_module_xyz") ~= nil)
                """);
    }

    @Test
    void packageLoadedManual() {
        eval("""
                package.loaded["foo"] = {bar = 1}
                assert(require("foo").bar == 1)
                """);
    }

    @Test
    void dofileExecutesFile() throws Exception {
        Path tmp = Files.createTempFile("lua-dofile", ".lua");
        try {
            Files.writeString(tmp, "return 99");
            String path = tmp.toAbsolutePath().toString();
            eval(String.format("""
                    local v = dofile("%s")
                    assert(v == 99)
                    """, path));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void requireDottedNames() throws Exception {
        Path dir = Files.createTempDirectory("lua-require-dotted");
        try {
            Path sub = dir.resolve("foo");
            Files.createDirectory(sub);
            Files.writeString(sub.resolve("bar.lua"), "return {y = 7}");
            String dirPath = dir.toAbsolutePath().toString();
            eval(String.format("""
                    package.path = "%s/?.lua;" .. package.path
                    local m = require("foo.bar")
                    assert(m.y == 7)
                    """, dirPath));
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // Best-effort cleanup.
                }
            });
        }
    }

    // ---------------------------------------------------------------------
    // 22. debug library (§5.9)
    // ---------------------------------------------------------------------

    @Test
    void debugTraceback() {
        eval("""
                local s = debug.traceback()
                assert(type(s) == "string")
                assert(string.find(s, "stack traceback") ~= nil)
                """);
    }

    @Test
    void debugTracebackWithMessage() {
        eval("""
                local s = debug.traceback("custom msg")
                assert(s:sub(1, 10) == "custom msg", "got: " .. s)
                """);
    }

    @Test
    void debugGetinfoBuiltin() {
        eval("""
                local info = debug.getinfo(print)
                assert(type(info) == "table")
                assert(info.what == "C")
                """);
    }

    @Test
    void debugGetinfoFunction() {
        eval("""
                local info = debug.getinfo(function() end)
                assert(type(info) == "table")
                assert(info.what == "Lua")
                """);
    }

    @Test
    void debugGetmetatable() {
        eval("""
                local t = setmetatable({}, {__metatable = "protected"})
                assert(getmetatable(t) == "protected")
                assert(type(debug.getmetatable(t)) == "table")
                """);
    }

    @Test
    void debugSetmetatable() {
        eval("""
                local t = setmetatable({}, {__metatable = "protected"})
                local newmt = {marker = true}
                debug.setmetatable(t, newmt)
                assert(debug.getmetatable(t) == newmt)
                assert(getmetatable(t).marker == true)
                """);
    }

    @Test
    void debugGetregistry() {
        eval("""
                local r1 = debug.getregistry()
                local r2 = debug.getregistry()
                assert(type(r1) == "table")
                assert(r1 == r2)
                """);
    }

    @Test
    void debugStubsDontError() {
        eval("""
                assert(debug.getlocal(1, 1) == nil)
                assert(debug.setlocal(1, 1, "x") == nil)
                assert(debug.sethook() == nil)
                assert(debug.gethook() == nil)
                """);
    }
}

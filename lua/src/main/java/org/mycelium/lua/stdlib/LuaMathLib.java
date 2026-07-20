package org.mycelium.lua.stdlib;

import com.oracle.truffle.api.TruffleLanguage;
import java.util.Random;
import org.mycelium.lua.LuaException;
import org.mycelium.lua.nodes.controlflow.LuaReturnException;
import org.mycelium.lua.runtime.LuaNil;
import org.mycelium.lua.runtime.LuaTable;

import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.arg;
import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.allArgs;
import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.wrap;
import static org.mycelium.lua.stdlib.LuaLibUtil.checkNumber;
import static org.mycelium.lua.stdlib.LuaLibUtil.isNil;

/** Lua 5.1 &sect;5.6: the {@code math} library. */
final class LuaMathLib {

    private LuaMathLib() {}

    static void install(TruffleLanguage<?> language, LuaTable globals) {
        LuaTable math = new LuaTable();
        globals.rawset("math", math);

        math.rawset("huge", Double.POSITIVE_INFINITY);
        math.rawset("pi", Math.PI);

        // A single shared Random instance, reseeded on demand by math.randomseed.
        Random[] rng = {new Random()};

        math.rawset("abs", wrap(language, "abs", (frame, cn) -> Math.abs(checkNumber("abs", 1, arg(frame, 1)))));
        math.rawset("ceil", wrap(language, "ceil", (frame, cn) -> Math.ceil(checkNumber("ceil", 1, arg(frame, 1)))));
        math.rawset("floor", wrap(language, "floor", (frame, cn) -> Math.floor(checkNumber("floor", 1, arg(frame, 1)))));
        math.rawset("sqrt", wrap(language, "sqrt", (frame, cn) -> Math.sqrt(checkNumber("sqrt", 1, arg(frame, 1)))));
        math.rawset("sin", wrap(language, "sin", (frame, cn) -> Math.sin(checkNumber("sin", 1, arg(frame, 1)))));
        math.rawset("cos", wrap(language, "cos", (frame, cn) -> Math.cos(checkNumber("cos", 1, arg(frame, 1)))));
        math.rawset("tan", wrap(language, "tan", (frame, cn) -> Math.tan(checkNumber("tan", 1, arg(frame, 1)))));
        math.rawset("asin", wrap(language, "asin", (frame, cn) -> Math.asin(checkNumber("asin", 1, arg(frame, 1)))));
        math.rawset("acos", wrap(language, "acos", (frame, cn) -> Math.acos(checkNumber("acos", 1, arg(frame, 1)))));
        math.rawset("atan", wrap(language, "atan", (frame, cn) -> Math.atan(checkNumber("atan", 1, arg(frame, 1)))));
        math.rawset("atan2", wrap(language, "atan2", (frame, cn) ->
                Math.atan2(checkNumber("atan2", 1, arg(frame, 1)), checkNumber("atan2", 2, arg(frame, 2)))));
        math.rawset("log", wrap(language, "log", (frame, cn) -> Math.log(checkNumber("log", 1, arg(frame, 1)))));
        math.rawset("log10", wrap(language, "log10", (frame, cn) -> Math.log10(checkNumber("log10", 1, arg(frame, 1)))));
        math.rawset("exp", wrap(language, "exp", (frame, cn) -> Math.exp(checkNumber("exp", 1, arg(frame, 1)))));
        math.rawset("pow", wrap(language, "pow", (frame, cn) ->
                Math.pow(checkNumber("pow", 1, arg(frame, 1)), checkNumber("pow", 2, arg(frame, 2)))));
        math.rawset("deg", wrap(language, "deg", (frame, cn) -> Math.toDegrees(checkNumber("deg", 1, arg(frame, 1)))));
        math.rawset("rad", wrap(language, "rad", (frame, cn) -> Math.toRadians(checkNumber("rad", 1, arg(frame, 1)))));

        math.rawset("max", wrap(language, "max", (frame, cn) -> {
            Object[] args = allArgs(frame);
            if (args.length == 0) {
                throw LuaException.runtimeError(null, "bad argument #1 to 'max' (value expected)");
            }
            double best = checkNumber("max", 1, args[0]);
            for (int i = 1; i < args.length; i++) {
                best = Math.max(best, checkNumber("max", i + 1, args[i]));
            }
            return best;
        }));
        math.rawset("min", wrap(language, "min", (frame, cn) -> {
            Object[] args = allArgs(frame);
            if (args.length == 0) {
                throw LuaException.runtimeError(null, "bad argument #1 to 'min' (value expected)");
            }
            double best = checkNumber("min", 1, args[0]);
            for (int i = 1; i < args.length; i++) {
                best = Math.min(best, checkNumber("min", i + 1, args[i]));
            }
            return best;
        }));

        math.rawset("fmod", wrap(language, "fmod", (frame, cn) -> {
            double x = checkNumber("fmod", 1, arg(frame, 1));
            double y = checkNumber("fmod", 2, arg(frame, 2));
            // Lua's math.fmod matches C's fmod: result has the same sign as x, which is exactly
            // Java's % operator semantics for doubles.
            return x % y;
        }));

        math.rawset("modf", wrap(language, "modf", (frame, cn) -> {
            double x = checkNumber("modf", 1, arg(frame, 1));
            double intPart = x >= 0 ? Math.floor(x) : Math.ceil(x);
            double fracPart = x - intPart;
            throw new LuaReturnException(new Object[] {intPart, fracPart});
        }));

        math.rawset("random", wrap(language, "random", (frame, cn) -> {
            Object mArg = arg(frame, 1);
            Object nArg = arg(frame, 2);
            if (isNil(mArg)) {
                return rng[0].nextDouble();
            }
            long m = (long) checkNumber("random", 1, mArg);
            if (isNil(nArg)) {
                if (m < 1) {
                    throw LuaException.runtimeError(null, "bad argument #1 to 'random' (interval is empty)");
                }
                return (double) (1 + (long) (rng[0].nextDouble() * m));
            }
            long n = (long) checkNumber("random", 2, nArg);
            if (m > n) {
                throw LuaException.runtimeError(null, "bad argument #2 to 'random' (interval is empty)");
            }
            long range = n - m + 1;
            return (double) (m + (long) (rng[0].nextDouble() * range));
        }));

        math.rawset("randomseed", wrap(language, "randomseed", (frame, cn) -> {
            double seed = checkNumber("randomseed", 1, arg(frame, 1));
            rng[0] = new Random(Double.doubleToLongBits(seed));
            return LuaNil.INSTANCE;
        }));
    }
}

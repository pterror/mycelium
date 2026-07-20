package org.mycelium.lua.stdlib;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.source.Source;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.mycelium.lua.LuaContext;
import org.mycelium.lua.LuaException;
import org.mycelium.lua.LuaLanguage;
import org.mycelium.lua.nodes.LuaRootNode;
import org.mycelium.lua.nodes.controlflow.LuaReturnException;
import org.mycelium.lua.parser.LuaLexer;
import org.mycelium.lua.parser.LuaParseError;
import org.mycelium.lua.parser.LuaParser;
import org.mycelium.lua.runtime.LuaFunction;
import org.mycelium.lua.runtime.LuaNil;
import org.mycelium.lua.runtime.LuaTable;

import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.arg;
import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.wrap;
import static org.mycelium.lua.stdlib.LuaLibUtil.*;

/**
 * Lua 5.1 &sect;5.3: the module/package system ({@code require}, the {@code package} table, and
 * the {@code package.loaders} searcher chain). Implemented standalone against the filesystem
 * (via {@code java.nio.file}), matching the pattern {@link LuaIoLib} already uses for {@code
 * io.open} &mdash; no dependency on Mycelium's own module system.
 */
final class LuaPackageLib {

    /** Lua 5.1's default {@code package.path}: search the current directory for either
     * {@code modname.lua} or {@code modname/init.lua}. */
    private static final String DEFAULT_PATH = "./?.lua;./?/init.lua";

    private LuaPackageLib() {}

    static void install(TruffleLanguage<?> language, LuaContext context) {
        LuaTable globals = context.getGlobals();

        LuaTable pkg = new LuaTable();
        globals.rawset("package", pkg);

        LuaTable loaded = new LuaTable();
        LuaTable preload = new LuaTable();
        pkg.rawset("loaded", loaded);
        pkg.rawset("preload", preload);
        pkg.rawset("path", DEFAULT_PATH);
        pkg.rawset("cpath", ""); // No C modules in this implementation.
        // Lua 5.1 §5.3: dir-sep, path-sep, "?" wildcard, executable-dir mark, ignore-mark.
        pkg.rawset("config", "/\n;\n?\n!\n-");

        LuaTable loaders = new LuaTable();
        loaders.rawset(1.0, makePreloadSearcher(language, preload));
        loaders.rawset(2.0, makeLuaSearcher(language, pkg));
        pkg.rawset("loaders", loaders);

        globals.rawset("require", wrap(language, "require", (frame, callNode) ->
                require(language, checkString("require", 1, arg(frame, 1)), pkg, loaded, callNode)));

        // `module` is deprecated even in Lua 5.1 and rarely used by modern code; a full
        // implementation (rewriting the caller's _ENV-equivalent global-lookup path, §5.3's
        // package.seeall handling, etc.) doesn't fit this tree-walk interpreter's calling
        // convention without much deeper plumbing. This stub creates the module table, registers
        // it in package.loaded, and returns it, which covers simple non-searching uses; it does
        // NOT rebind the caller's subsequent global writes into the module table.
        globals.rawset("module", wrap(language, "module", (frame, callNode) -> {
            String name = checkString("module", 1, arg(frame, 1));
            Object existing = loaded.rawget(name);
            LuaTable module = (existing instanceof LuaTable t) ? t : new LuaTable();
            module.rawset("_M", module);
            module.rawset("_NAME", name);
            int lastDot = name.lastIndexOf('.');
            module.rawset("_PACKAGE", lastDot >= 0 ? name.substring(0, lastDot + 1) : "");
            loaded.rawset(name, module);
            globals.rawset(name, module);
            return LuaNil.INSTANCE;
        }));
    }

    /**
     * {@code require(modname)} (Lua 5.1 &sect;5.3): checks {@code package.loaded} first (caching,
     * and letting circular requires observe a partially-loaded module), then walks {@code
     * package.loaders} in order looking for a searcher that resolves {@code modname} to a loader
     * function, then invokes that loader. The loader's return value becomes {@code
     * package.loaded[modname]} unless the loader already set that entry itself (e.g. a hand-written
     * {@code package.preload} loader that registers itself).
     */
    private static Object require(
            TruffleLanguage<?> language, String modname, LuaTable pkg, LuaTable loaded, IndirectCallNode callNode) {
        Object cached = loaded.rawget(modname);
        if (!isNil(cached)) {
            return cached;
        }

        Object loadersObj = pkg.rawget("loaders");
        LuaTable loadersTable = (loadersObj instanceof LuaTable t) ? t : null;
        StringBuilder triedMessage = new StringBuilder("module '" + modname + "' not found:");
        Object foundLoader = null;

        if (loadersTable != null) {
            int n = loadersTable.rawlen();
            for (long i = 1; i <= n && foundLoader == null; i++) {
                Object searcher = loadersTable.rawget(i);
                if (isNil(searcher)) {
                    continue;
                }
                Object[] result = LuaCallSupport.call(null, callNode, searcher, new Object[] {modname});
                Object first = LuaCallSupport.first(result);
                if (first instanceof LuaFunction) {
                    foundLoader = first;
                } else if (first instanceof String s) {
                    triedMessage.append(s);
                }
            }
        }

        if (foundLoader == null) {
            throw LuaException.runtimeError(null, triedMessage.toString());
        }

        Object[] loaderResult = LuaCallSupport.call(null, callNode, foundLoader, new Object[] {modname});
        Object loaderValue = LuaCallSupport.first(loaderResult);

        // The loader may have registered package.loaded[modname] itself (e.g. to support circular
        // requires that observe a partially-built module) — don't clobber that.
        Object already = loaded.rawget(modname);
        if (!isNil(already)) {
            return already;
        }
        Object toStore = isNil(loaderValue) ? Boolean.TRUE : loaderValue;
        loaded.rawset(modname, toStore);
        return toStore;
    }

    /** {@code package.loaders[1]} (Lua 5.1 &sect;5.3): looks up {@code package.preload[modname]}. */
    private static LuaFunction makePreloadSearcher(TruffleLanguage<?> language, LuaTable preload) {
        return wrap(language, "preload_searcher", (frame, callNode) -> {
            String modname = checkString("preload_searcher", 1, arg(frame, 1));
            Object loader = preload.rawget(modname);
            if (isNil(loader)) {
                return "\n\tno field package.preload['" + modname + "']";
            }
            return loader;
        });
    }

    /**
     * {@code package.loaders[2]} (Lua 5.1 &sect;5.3): searches {@code package.path} for a matching
     * {@code .lua} file (each {@code ;}-separated template's {@code ?} replaced by {@code modname}
     * with {@code .} turned into {@code /}), parses it, and returns the compiled chunk as a {@link
     * LuaFunction} ready for {@code require} to call with {@code modname} as its argument. Returns
     * a string describing every path tried if no file was found, per the searcher-chain protocol.
     */
    private static LuaFunction makeLuaSearcher(TruffleLanguage<?> language, LuaTable pkg) {
        return wrap(language, "lua_searcher", (frame, callNode) -> {
            String modname = checkString("lua_searcher", 1, arg(frame, 1));
            String path = optString(pkg.rawget("path"), DEFAULT_PATH);
            String fileFragment = modname.replace('.', '/');

            StringBuilder tried = new StringBuilder();
            for (String template : path.split(";", -1)) {
                if (template.isEmpty()) {
                    continue;
                }
                String filename = template.replace("?", fileFragment);
                Path file = Path.of(filename);
                if (Files.isRegularFile(file)) {
                    return loadModuleFile(language, callNode, file, filename);
                }
                tried.append("\n\tno file '").append(filename).append('\'');
            }
            return tried.toString();
        });
    }

    /** Reads, parses, and wraps {@code file} (whose display name is {@code chunkname}) as a
     * {@link LuaFunction}, matching the shared {@code load}/{@code loadstring} chunk-loading
     * pattern in {@link LuaBaseLib#loadChunk}. Parse errors propagate as {@link LuaException} (per
     * &sect;5.3, an error while loading a module is not swallowed &mdash; {@code require} is not
     * protected). */
    private static LuaFunction loadModuleFile(
            TruffleLanguage<?> language, IndirectCallNode callNode, Path file, String chunkname) {
        String code;
        try {
            code = Files.readString(file, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw LuaException.runtimeError(null, "cannot open " + chunkname + ": " + e.getMessage());
        }
        try {
            Source source = Source.newBuilder(LuaLanguage.ID, code, chunkname).build();
            LuaParser parser = new LuaParser(language, source);
            LuaRootNode chunkRootNode = parser.parse();
            CallTarget chunkCallTarget = chunkRootNode.getCallTarget();
            LuaFunction chunkFunction = new LuaFunction(chunkCallTarget, null);
            LuaContext context = LuaLanguage.getContext(callNode);
            chunkFunction.setEnvironment(context.getGlobals());
            return chunkFunction;
        } catch (LuaParseError | LuaLexer.LuaLexerException e) {
            throw LuaException.runtimeError(null, chunkname + ": " + e.getMessage());
        }
    }
}

package org.mycelium.lua.parser;

/**
 * Thrown when {@link LuaParser} encounters a token that cannot legally continue the grammar
 * production currently being parsed (Lua 5.1 Reference Manual, &sect;8, "The Complete Syntax of
 * Lua"). The message always carries the source name and 1-based line, mirroring the convention
 * used by {@link LuaLexer.LuaLexerException} and {@code org.mycelium.lua.LuaException}.
 */
public final class LuaParseError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public LuaParseError(String message) {
        super(message);
    }
}

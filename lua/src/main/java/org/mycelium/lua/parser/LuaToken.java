package org.mycelium.lua.parser;

/**
 * A single lexical token produced by {@link LuaLexer}.
 *
 * <p>{@code value} always holds the raw lexeme text as it appeared in the source
 * (e.g. a string token's value includes its quotes/brackets and any escape sequences
 * verbatim, rather than a decoded string). Decoding escape sequences and long-bracket
 * newline stripping is the parser's concern; the lexer only validates that the escapes
 * are well-formed.
 */
public final class LuaToken {

    /** All token kinds defined by the Lua 5.1 Reference Manual, §2.1. */
    public enum TokenType {
        // Keywords (§2.1)
        AND, BREAK, DO, ELSE, ELSEIF, END, FALSE, FOR, FUNCTION,
        IF, IN, LOCAL, NIL, NOT, OR, REPEAT, RETURN, THEN, TRUE, UNTIL, WHILE,

        // Symbols
        PLUS,       // +
        MINUS,      // -
        STAR,       // *
        SLASH,      // /
        PERCENT,    // %
        CARET,      // ^
        HASH,       // #
        LPAREN,     // (
        RPAREN,     // )
        LBRACE,     // {
        RBRACE,     // }
        LBRACKET,   // [
        RBRACKET,   // ]
        SEMICOLON,  // ;
        COLON,      // :
        COMMA,      // ,
        DOT,        // .
        DOTDOT,     // ..
        DOTDOTDOT,  // ...
        ASSIGN,     // =
        EQ,         // ==
        NEQ,        // ~=
        LT,         // <
        GT,         // >
        LE,         // <=
        GE,         // >=

        // Literals
        NUMBER,     // numeric literal
        STRING,     // string literal
        NAME,       // identifier

        // Special
        EOF
    }

    public final TokenType type;
    public final String value;
    public final int line;
    public final int column;

    /** 0-based character offset, into the source, of this token's first character. */
    public final int startOffset;

    /** Number of characters this token spans in the source (0 for the synthetic {@code EOF} token). */
    public final int length;

    public LuaToken(TokenType type, String value, int line, int column, int startOffset, int length) {
        this.type = type;
        this.value = value;
        this.line = line;
        this.column = column;
        this.startOffset = startOffset;
        this.length = length;
    }

    @Override
    public String toString() {
        return type + "(" + value + ") @" + line + ":" + column;
    }
}

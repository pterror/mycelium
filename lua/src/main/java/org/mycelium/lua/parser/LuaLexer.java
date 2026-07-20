package org.mycelium.lua.parser;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.mycelium.lua.parser.LuaToken.TokenType;

/**
 * Hand-written lexer for Lua 5.1, as specified by the Lua 5.1 Reference Manual, §2.1
 * ("Lexical Conventions").
 *
 * <p>The lexer is a simple one-token-of-lookahead scanner: {@link #next()} consumes and
 * returns the next token, {@link #peek()} returns it without consuming it.
 */
public final class LuaLexer {

    /** Thrown for any malformed token; carries the 1-based source line for reporting. */
    public static final class LuaLexerException extends RuntimeException {
        public final int line;

        public LuaLexerException(String message, int line) {
            super(message);
            this.line = line;
        }
    }

    private static final Map<String, TokenType> KEYWORDS = new HashMap<>();
    static {
        KEYWORDS.put("and", TokenType.AND);
        KEYWORDS.put("break", TokenType.BREAK);
        KEYWORDS.put("do", TokenType.DO);
        KEYWORDS.put("else", TokenType.ELSE);
        KEYWORDS.put("elseif", TokenType.ELSEIF);
        KEYWORDS.put("end", TokenType.END);
        KEYWORDS.put("false", TokenType.FALSE);
        KEYWORDS.put("for", TokenType.FOR);
        KEYWORDS.put("function", TokenType.FUNCTION);
        KEYWORDS.put("if", TokenType.IF);
        KEYWORDS.put("in", TokenType.IN);
        KEYWORDS.put("local", TokenType.LOCAL);
        KEYWORDS.put("nil", TokenType.NIL);
        KEYWORDS.put("not", TokenType.NOT);
        KEYWORDS.put("or", TokenType.OR);
        KEYWORDS.put("repeat", TokenType.REPEAT);
        KEYWORDS.put("return", TokenType.RETURN);
        KEYWORDS.put("then", TokenType.THEN);
        KEYWORDS.put("true", TokenType.TRUE);
        KEYWORDS.put("until", TokenType.UNTIL);
        KEYWORDS.put("while", TokenType.WHILE);
    }

    // §2.1: a decimal numeral is digits with an optional fractional part and an optional
    // exponent; a hex numeral in 5.1 is integer-only (no hex floats, no hex exponent).
    private static final Pattern DECIMAL_NUMBER =
            Pattern.compile("(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    private static final Pattern HEX_NUMBER = Pattern.compile("0[xX][0-9a-fA-F]+");

    private static final char EOF_CHAR = '\0';

    private final String source;
    private final String sourceName;
    private int pos;
    private int line = 1;
    private int column = 1;
    private LuaToken peeked;

    public LuaLexer(String source, String sourceName) {
        this.source = source;
        this.sourceName = sourceName;
    }

    /** Consumes and returns the next token. */
    public LuaToken next() {
        if (peeked != null) {
            LuaToken t = peeked;
            peeked = null;
            return t;
        }
        return scanToken();
    }

    /** Returns the next token without consuming it. */
    public LuaToken peek() {
        if (peeked == null) {
            peeked = scanToken();
        }
        return peeked;
    }

    /** Current 1-based source line. */
    public int getLine() {
        return line;
    }

    // ------------------------------------------------------------------
    // Scanning
    // ------------------------------------------------------------------

    private LuaToken scanToken() {
        skipWhitespaceAndComments();

        int startLine = line;
        int startCol = column;
        char c = peekChar();

        if (c == EOF_CHAR) {
            return tok(TokenType.EOF, "", startLine, startCol);
        }
        if (isNameStart(c)) {
            return readNameOrKeyword(startLine, startCol);
        }
        if (isDigit(c) || (c == '.' && isDigit(peekChar(1)))) {
            return readNumber(startLine, startCol);
        }
        if (c == '"' || c == '\'') {
            return readString(c, startLine, startCol);
        }
        if (c == '[') {
            int level = longBracketLevel(pos);
            if (level >= 0) {
                return readLongString(level, startLine, startCol);
            }
            advance();
            return tok(TokenType.LBRACKET, "[", startLine, startCol);
        }

        switch (c) {
            case '+': advance(); return tok(TokenType.PLUS, "+", startLine, startCol);
            case '-': advance(); return tok(TokenType.MINUS, "-", startLine, startCol);
            case '*': advance(); return tok(TokenType.STAR, "*", startLine, startCol);
            case '/': advance(); return tok(TokenType.SLASH, "/", startLine, startCol);
            case '%': advance(); return tok(TokenType.PERCENT, "%", startLine, startCol);
            case '^': advance(); return tok(TokenType.CARET, "^", startLine, startCol);
            case '#': advance(); return tok(TokenType.HASH, "#", startLine, startCol);
            case '(': advance(); return tok(TokenType.LPAREN, "(", startLine, startCol);
            case ')': advance(); return tok(TokenType.RPAREN, ")", startLine, startCol);
            case '{': advance(); return tok(TokenType.LBRACE, "{", startLine, startCol);
            case '}': advance(); return tok(TokenType.RBRACE, "}", startLine, startCol);
            case ']': advance(); return tok(TokenType.RBRACKET, "]", startLine, startCol);
            case ';': advance(); return tok(TokenType.SEMICOLON, ";", startLine, startCol);
            case ',': advance(); return tok(TokenType.COMMA, ",", startLine, startCol);
            case ':': advance(); return tok(TokenType.COLON, ":", startLine, startCol);
            case '.': {
                advance();
                if (peekChar() == '.') {
                    advance();
                    if (peekChar() == '.') {
                        advance();
                        return tok(TokenType.DOTDOTDOT, "...", startLine, startCol);
                    }
                    return tok(TokenType.DOTDOT, "..", startLine, startCol);
                }
                return tok(TokenType.DOT, ".", startLine, startCol);
            }
            case '=': {
                advance();
                if (peekChar() == '=') {
                    advance();
                    return tok(TokenType.EQ, "==", startLine, startCol);
                }
                return tok(TokenType.ASSIGN, "=", startLine, startCol);
            }
            case '~': {
                advance();
                if (peekChar() == '=') {
                    advance();
                    return tok(TokenType.NEQ, "~=", startLine, startCol);
                }
                // §2.1: Lua 5.1 has no standalone '~' operator (unlike 5.2+'s bitwise xor).
                throw error("unexpected symbol near '~'");
            }
            case '<': {
                advance();
                if (peekChar() == '=') {
                    advance();
                    return tok(TokenType.LE, "<=", startLine, startCol);
                }
                return tok(TokenType.LT, "<", startLine, startCol);
            }
            case '>': {
                advance();
                if (peekChar() == '=') {
                    advance();
                    return tok(TokenType.GE, ">=", startLine, startCol);
                }
                return tok(TokenType.GT, ">", startLine, startCol);
            }
            default:
                throw error("unexpected symbol near '" + c + "'");
        }
    }

    private void skipWhitespaceAndComments() {
        while (true) {
            char c = peekChar();
            if (c == ' ' || c == '\t' || c == '\f' || c == 0x0B) {
                advance();
            } else if (c == '\n' || c == '\r') {
                newline();
            } else if (c == '-' && peekChar(1) == '-') {
                advance();
                advance();
                int level = longBracketLevel(pos);
                if (level >= 0) {
                    // §2.1: long comment, e.g. --[[ ... ]] or --[==[ ... ]==]
                    scanLongBracketBody(level, true);
                } else {
                    // §2.1: short comment runs to end of line (not consumed here; the
                    // loop's next iteration handles the newline itself).
                    while (peekChar() != '\n' && peekChar() != '\r' && peekChar() != EOF_CHAR) {
                        advance();
                    }
                }
            } else {
                return;
            }
        }
    }

    private LuaToken readNameOrKeyword(int startLine, int startCol) {
        int start = pos;
        while (isNameContinue(peekChar())) {
            advance();
        }
        String text = source.substring(start, pos);
        TokenType kw = KEYWORDS.get(text);
        return tok(kw != null ? kw : TokenType.NAME, text, startLine, startCol);
    }

    private LuaToken readNumber(int startLine, int startCol) {
        int start = pos;
        boolean hex = peekChar() == '0' && (peekChar(1) == 'x' || peekChar(1) == 'X');
        if (hex) {
            advance();
            advance();
            while (isHexDigit(peekChar())) {
                advance();
            }
        } else {
            while (isDigit(peekChar()) || peekChar() == '.') {
                advance();
            }
            if (peekChar() == 'e' || peekChar() == 'E') {
                advance();
                if (peekChar() == '+' || peekChar() == '-') {
                    advance();
                }
                while (isDigit(peekChar())) {
                    advance();
                }
            }
        }
        // §2.1: maximal munch, mirroring the reference lexer's behavior of absorbing any
        // trailing identifier-like characters into the numeral before validating it — this
        // is what makes malformed numerals like "123abc" or "1..2" report an error here
        // rather than silently splitting into a number token followed by a name token.
        while (isNameContinue(peekChar())) {
            advance();
        }
        String text = source.substring(start, pos);
        boolean valid = hex ? HEX_NUMBER.matcher(text).matches() : DECIMAL_NUMBER.matcher(text).matches();
        if (!valid) {
            throw error("malformed number near '" + text + "'");
        }
        return tok(TokenType.NUMBER, text, startLine, startCol);
    }

    private LuaToken readString(char quote, int startLine, int startCol) {
        int start = pos;
        advance(); // opening quote
        while (true) {
            char c = peekChar();
            if (c == quote) {
                advance();
                break;
            }
            if (c == EOF_CHAR) {
                throw error("unfinished string");
            }
            if (c == '\n' || c == '\r') {
                // §2.1: short strings cannot contain a literal (unescaped) newline.
                throw error("unfinished string");
            }
            if (c == '\\') {
                advance();
                char e = peekChar();
                switch (e) {
                    case 'a': case 'b': case 'f': case 'n': case 'r':
                    case 't': case 'v': case '\\': case '"': case '\'':
                    case '[': case ']':
                        advance();
                        break;
                    case '\n':
                    case '\r':
                        // §2.1: a backslash immediately followed by a newline embeds a
                        // newline in the string.
                        newline();
                        break;
                    default:
                        if (isDigit(e)) {
                            // §2.1: \ddd — up to 3 decimal digits, value must be <= 255.
                            int n = 0;
                            int digits = 0;
                            while (digits < 3 && isDigit(peekChar())) {
                                n = n * 10 + (peekChar() - '0');
                                advance();
                                digits++;
                            }
                            if (n > 255) {
                                throw error("decimal escape too large");
                            }
                        } else if (e == EOF_CHAR) {
                            throw error("unfinished string");
                        } else {
                            throw error("invalid escape sequence '\\" + e + "'");
                        }
                }
            } else {
                advance();
            }
        }
        String text = source.substring(start, pos);
        return tok(TokenType.STRING, text, startLine, startCol);
    }

    private LuaToken readLongString(int level, int startLine, int startCol) {
        int start = pos;
        scanLongBracketBody(level, false);
        String text = source.substring(start, pos);
        return tok(TokenType.STRING, text, startLine, startCol);
    }

    /**
     * Consumes a long bracket body (string or comment) starting at the opening '[' of
     * {@code [=*[ ... ]=*]} and ending just past the matching close, per §2.1.
     */
    private void scanLongBracketBody(int level, boolean isComment) {
        advance(); // '['
        for (int i = 0; i < level; i++) {
            advance(); // '='
        }
        advance(); // second '['

        // §2.1: "if the first character is a newline, it is not included in the string".
        if (peekChar() == '\n' || peekChar() == '\r') {
            newline();
        }

        while (true) {
            char c = peekChar();
            if (c == EOF_CHAR) {
                throw error(isComment ? "unfinished long comment" : "unfinished long string");
            }
            if (c == ']' && matchLongBracketClose(level)) {
                for (int i = 0; i < level + 2; i++) {
                    advance();
                }
                return;
            }
            if (c == '\n' || c == '\r') {
                newline();
            } else {
                advance();
            }
        }
    }

    /** True if a long bracket opener {@code [=*[} starts at source index {@code p}. */
    private int longBracketLevel(int p) {
        if (p >= source.length() || source.charAt(p) != '[') {
            return -1;
        }
        int q = p + 1;
        int level = 0;
        while (q < source.length() && source.charAt(q) == '=') {
            level++;
            q++;
        }
        if (q < source.length() && source.charAt(q) == '[') {
            return level;
        }
        return -1;
    }

    /** True if a matching long bracket closer {@code ]=*]} of the given level starts here. */
    private boolean matchLongBracketClose(int level) {
        if (peekChar() != ']') {
            return false;
        }
        for (int i = 1; i <= level; i++) {
            if (peekChar(i) != '=') {
                return false;
            }
        }
        return peekChar(level + 1) == ']';
    }

    // ------------------------------------------------------------------
    // Character classes
    // ------------------------------------------------------------------

    private static boolean isNameStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isNameContinue(char c) {
        return isNameStart(c) || isDigit(c);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHexDigit(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    // ------------------------------------------------------------------
    // Low-level cursor helpers
    // ------------------------------------------------------------------

    private char peekChar() {
        return peekChar(0);
    }

    private char peekChar(int offset) {
        int p = pos + offset;
        return p < source.length() ? source.charAt(p) : EOF_CHAR;
    }

    private void advance() {
        pos++;
        column++;
    }

    /**
     * Consumes a line break at the current position. §2.1 (and the reference
     * implementation's behavior) treats any of "\n", "\r", "\n\r", "\r\n" as a single
     * line-ending, so a lone char is consumed and, if followed by the *other* newline
     * character, that one is swallowed too, counting as exactly one line increment.
     */
    private void newline() {
        char c = peekChar();
        pos++;
        char c2 = peekChar();
        if ((c == '\n' && c2 == '\r') || (c == '\r' && c2 == '\n')) {
            pos++;
        }
        line++;
        column = 1;
    }

    private LuaToken tok(TokenType type, String value, int line, int col) {
        return new LuaToken(type, value, line, col);
    }

    private LuaLexerException error(String message) {
        return new LuaLexerException(sourceName + ":" + line + ": " + message, line);
    }
}

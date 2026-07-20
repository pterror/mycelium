package org.mycelium.lua.parser;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.source.Source;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mycelium.lua.parser.LuaToken.TokenType;
import org.mycelium.lua.nodes.LuaAddNode;
import org.mycelium.lua.nodes.LuaAndNode;
import org.mycelium.lua.nodes.LuaAssignmentNode;
import org.mycelium.lua.nodes.LuaAssignmentTarget;
import org.mycelium.lua.nodes.LuaBlockNode;
import org.mycelium.lua.nodes.LuaBooleanLiteralNode;
import org.mycelium.lua.nodes.LuaBreakNode;
import org.mycelium.lua.nodes.LuaConcatNode;
import org.mycelium.lua.nodes.LuaDivNode;
import org.mycelium.lua.nodes.LuaDoBlockNode;
import org.mycelium.lua.nodes.LuaEqualNode;
import org.mycelium.lua.nodes.LuaExpressionNode;
import org.mycelium.lua.nodes.LuaExpressionStatementNode;
import org.mycelium.lua.nodes.LuaForGenericNode;
import org.mycelium.lua.nodes.LuaForNumericNode;
import org.mycelium.lua.nodes.LuaFunctionCallNode;
import org.mycelium.lua.nodes.LuaFunctionDefNode;
import org.mycelium.lua.nodes.LuaGlobalTarget;
import org.mycelium.lua.nodes.LuaGreaterEqualNode;
import org.mycelium.lua.nodes.LuaGreaterThanNode;
import org.mycelium.lua.nodes.LuaIfNode;
import org.mycelium.lua.nodes.LuaLenNode;
import org.mycelium.lua.nodes.LuaLessEqualNode;
import org.mycelium.lua.nodes.LuaLessThanNode;
import org.mycelium.lua.nodes.LuaLocalTarget;
import org.mycelium.lua.nodes.LuaMethodCallNode;
import org.mycelium.lua.nodes.LuaModNode;
import org.mycelium.lua.nodes.LuaMulNode;
import org.mycelium.lua.nodes.LuaNilLiteralNode;
import org.mycelium.lua.nodes.LuaNotEqualNode;
import org.mycelium.lua.nodes.LuaNotNode;
import org.mycelium.lua.nodes.LuaNumberLiteralNode;
import org.mycelium.lua.nodes.LuaOrNode;
import org.mycelium.lua.nodes.LuaPowNode;
import org.mycelium.lua.nodes.LuaReadGlobalNode;
import org.mycelium.lua.nodes.LuaReadLocalNode;
import org.mycelium.lua.nodes.LuaReadUpvalueNode;
import org.mycelium.lua.nodes.LuaRepeatNode;
import org.mycelium.lua.nodes.LuaReturnNode;
import org.mycelium.lua.nodes.LuaRootNode;
import org.mycelium.lua.nodes.LuaStatementNode;
import org.mycelium.lua.nodes.LuaStringLiteralNode;
import org.mycelium.lua.nodes.LuaSubNode;
import org.mycelium.lua.nodes.LuaTableConstructorNode;
import org.mycelium.lua.nodes.LuaTableFieldTarget;
import org.mycelium.lua.nodes.LuaTableReadNode;
import org.mycelium.lua.nodes.LuaUnaryMinusNode;
import org.mycelium.lua.nodes.LuaUpvalueTarget;
import org.mycelium.lua.nodes.LuaVarargNode;
import org.mycelium.lua.nodes.LuaWhileNode;
import org.mycelium.lua.runtime.LuaNil;

/**
 * Recursive-descent parser for Lua 5.1 (Reference Manual &sect;8), building Truffle nodes
 * directly &mdash; there is no intermediate AST.
 *
 * <p><b>Lookahead:</b> the parser buffers tokens from {@link LuaLexer#next()} in {@link #lookahead}
 * so it can look arbitrarily far ahead (in practice, at most two tokens are ever needed &mdash;
 * e.g. to distinguish a table constructor's {@code Name '='} field from a bare list-style entry).
 *
 * <p><b>Scope / variable resolution:</b> each Lua function being compiled has a {@link FunctionScope}
 * (holding a {@link FrameDescriptor.Builder} and a stack of block-level name-to-slot maps).
 * {@link FunctionScope#parent} chains outward to enclosing functions being compiled. A {@code NAME}
 * resolves by walking: current function's block scopes (innermost first) &rarr; each enclosing
 * function's block scopes (in turn, counting the number of function hops as {@code scopeDepth}) &rarr;
 * global. Because {@link org.mycelium.lua.nodes.LuaReadUpvalueNode}/{@link org.mycelium.lua.nodes.LuaWriteUpvalueNode}
 * walk the runtime chain of {@link org.mycelium.lua.runtime.LuaFunction#getEnclosingFrame()} references
 * directly (rather than needing per-level upvalue cells to be threaded through), a variable found
 * {@code depth} function-hops out only needs its {@code (depth, slot)} recorded &mdash; no extra
 * bookkeeping in the intermediate functions is required.
 */
public final class LuaParser {

    private static final Object[] NO_VALUES = new Object[0];

    private final TruffleLanguage<?> language;
    private final String sourceName;
    private final LuaLexer lexer;
    private final List<LuaToken> lookahead = new ArrayList<>();

    private FunctionScope currentFunction;

    public LuaParser(TruffleLanguage<?> language, Source source) {
        this.language = language;
        this.sourceName = source.getName();
        this.lexer = new LuaLexer(source.getCharacters().toString(), sourceName);
    }

    /** Parses the whole source as a Lua "chunk" (Lua 5.1 &sect;8), returning the compiled main-chunk root node. */
    public LuaRootNode parse() {
        currentFunction = new FunctionScope(null);
        currentFunction.vararg = true;
        currentFunction.pushBlock();

        LuaStatementNode block = parseBlock();
        expect(TokenType.EOF, "<eof>");

        currentFunction.popBlock();

        LuaStatementNode prologue = new ArgumentPrologueNode(new int[0], true);
        LuaStatementNode fullBody = new LuaBlockNode(new LuaStatementNode[] {prologue, block});
        FrameDescriptor frameDescriptor = currentFunction.frameBuilder.build();
        return new LuaRootNode(language, frameDescriptor, fullBody);
    }

    // ------------------------------------------------------------------
    // Scope management
    // ------------------------------------------------------------------

    /** One lexical block's local-variable declarations (name -> frame slot). */
    private static final class BlockScope {
        final Map<String, Integer> locals = new HashMap<>();
    }

    /** Per-compiled-function parsing state: frame layout, nested block scopes, and vararg-ness. */
    private static final class FunctionScope {
        final FunctionScope parent;
        final FrameDescriptor.Builder frameBuilder = FrameDescriptor.newBuilder();
        final List<BlockScope> blocks = new ArrayList<>();
        boolean vararg;
        int numUpvalues;

        FunctionScope(FunctionScope parent) {
            this.parent = parent;
            // Slot 0 is reserved, by convention, for the vararg storage (LuaRootNode.VARARGS_FRAME_SLOT).
            frameBuilder.addSlot(FrameSlotKind.Object, "(varargs)", null);
        }

        void pushBlock() {
            blocks.add(new BlockScope());
        }

        void popBlock() {
            blocks.remove(blocks.size() - 1);
        }

        int declareLocal(String name) {
            int slot = frameBuilder.addSlot(FrameSlotKind.Object, name, null);
            blocks.get(blocks.size() - 1).locals.put(name, slot);
            return slot;
        }

        Integer lookupLocal(String name) {
            for (int i = blocks.size() - 1; i >= 0; i--) {
                Integer slot = blocks.get(i).locals.get(name);
                if (slot != null) {
                    return slot;
                }
            }
            return null;
        }
    }

    private void pushScope() {
        currentFunction.pushBlock();
    }

    private void popScope() {
        currentFunction.popBlock();
    }

    /** Resolves a NAME occurring in read position: local -> upvalue -> global (see class doc). */
    private LuaExpressionNode resolveRead(String name) {
        FunctionScope fn = currentFunction;
        int depth = 0;
        while (fn != null) {
            Integer slot = fn.lookupLocal(name);
            if (slot != null) {
                if (depth == 0) {
                    return new LuaReadLocalNode(slot);
                }
                currentFunction.numUpvalues++;
                return new LuaReadUpvalueNode(depth, slot);
            }
            fn = fn.parent;
            depth++;
        }
        return new LuaReadGlobalNode(name);
    }

    /** Resolves a NAME occurring in assignment-target position (mirrors {@link #resolveRead}). */
    private LuaAssignmentTarget resolveTarget(String name) {
        FunctionScope fn = currentFunction;
        int depth = 0;
        while (fn != null) {
            Integer slot = fn.lookupLocal(name);
            if (slot != null) {
                if (depth == 0) {
                    return new LuaLocalTarget(slot);
                }
                currentFunction.numUpvalues++;
                return new LuaUpvalueTarget(depth, slot);
            }
            fn = fn.parent;
            depth++;
        }
        return new LuaGlobalTarget(name);
    }

    // ------------------------------------------------------------------
    // Token stream helpers
    // ------------------------------------------------------------------

    private LuaToken peek() {
        return peek(0);
    }

    private LuaToken peek(int distance) {
        while (lookahead.size() <= distance) {
            lookahead.add(lexer.next());
        }
        return lookahead.get(distance);
    }

    private LuaToken advance() {
        LuaToken t = peek(0);
        lookahead.remove(0);
        return t;
    }

    private boolean check(TokenType type) {
        return peek().type == type;
    }

    private boolean match(TokenType type) {
        if (check(type)) {
            advance();
            return true;
        }
        return false;
    }

    private LuaToken expect(TokenType type) {
        return expect(type, describe(type));
    }

    private LuaToken expect(TokenType type, String description) {
        LuaToken tok = peek();
        if (tok.type != type) {
            throw error("'" + description + "' expected near '" + tokenDisplay(tok) + "'");
        }
        return advance();
    }

    private static String describe(TokenType type) {
        return type == TokenType.EOF ? "<eof>" : type.name();
    }

    private static String tokenDisplay(LuaToken tok) {
        return tok.type == TokenType.EOF ? "<eof>" : tok.value;
    }

    private LuaParseError error(String message) {
        LuaToken tok = peek();
        return new LuaParseError(sourceName + ":" + tok.line + ": " + message);
    }

    // ------------------------------------------------------------------
    // Blocks and statements
    // ------------------------------------------------------------------

    private boolean isBlockEnd() {
        switch (peek().type) {
            case EOF:
            case END:
            case ELSE:
            case ELSEIF:
            case UNTIL:
                return true;
            default:
                return false;
        }
    }

    /** {@code chunk ::= {stat [';']} [laststat [';']]}; {@code block ::= chunk}. */
    private LuaStatementNode parseBlock() {
        List<LuaStatementNode> statements = new ArrayList<>();
        while (!isBlockEnd()) {
            if (check(TokenType.SEMICOLON)) {
                advance();
                continue;
            }
            if (check(TokenType.RETURN) || check(TokenType.BREAK)) {
                statements.add(parseLastStat());
                match(TokenType.SEMICOLON);
                break;
            }
            statements.add(parseStatement());
        }
        return new LuaBlockNode(statements.toArray(new LuaStatementNode[0]));
    }

    /** {@code laststat ::= return [explist] | break}. */
    private LuaStatementNode parseLastStat() {
        if (match(TokenType.BREAK)) {
            return new LuaBreakNode();
        }
        expect(TokenType.RETURN);
        List<LuaExpressionNode> exprs = new ArrayList<>();
        if (!isBlockEnd() && !check(TokenType.SEMICOLON)) {
            exprs = parseExprList();
        }
        return new LuaReturnNode(exprs.toArray(new LuaExpressionNode[0]));
    }

    private LuaStatementNode parseStatement() {
        switch (peek().type) {
            case IF:
                return parseIf();
            case WHILE:
                return parseWhile();
            case DO:
                return parseDo();
            case FOR:
                return parseFor();
            case REPEAT:
                return parseRepeat();
            case FUNCTION:
                return parseFunctionStat();
            case LOCAL: {
                advance();
                if (match(TokenType.FUNCTION)) {
                    return parseLocalFunction();
                }
                return parseLocalDecl();
            }
            default:
                return parseExprStat();
        }
    }

    /** {@code if exp then block {elseif exp then block} [else block] end}. */
    private LuaStatementNode parseIf() {
        expect(TokenType.IF);
        return parseIfRest();
    }

    /** Parses {@code exp then block ...} shared by both {@code if} and {@code elseif}; consumes the
     * final {@code end} at whichever nesting level terminates the chain. */
    private LuaStatementNode parseIfRest() {
        LuaExpressionNode condition = parseExpr();
        expect(TokenType.THEN);
        pushScope();
        LuaStatementNode thenBody = parseBlock();
        popScope();

        LuaStatementNode elseBody;
        if (match(TokenType.ELSEIF)) {
            elseBody = parseIfRest();
        } else if (match(TokenType.ELSE)) {
            pushScope();
            elseBody = parseBlock();
            popScope();
            expect(TokenType.END);
        } else {
            expect(TokenType.END);
            elseBody = null;
        }
        return new LuaIfNode(condition, thenBody, elseBody);
    }

    /** {@code while exp do block end}. */
    private LuaStatementNode parseWhile() {
        expect(TokenType.WHILE);
        LuaExpressionNode condition = parseExpr();
        expect(TokenType.DO);
        pushScope();
        LuaStatementNode body = parseBlock();
        popScope();
        expect(TokenType.END);
        return new LuaWhileNode(condition, body);
    }

    /** {@code do block end}. */
    private LuaStatementNode parseDo() {
        expect(TokenType.DO);
        pushScope();
        LuaStatementNode body = parseBlock();
        popScope();
        expect(TokenType.END);
        return new LuaDoBlockNode(body);
    }

    /** {@code repeat block until exp}. The condition is parsed with the block's scope still open,
     * so it can see locals declared in the body (Lua 5.1 &sect;2.4.4). */
    private LuaStatementNode parseRepeat() {
        expect(TokenType.REPEAT);
        pushScope();
        LuaStatementNode body = parseBlock();
        expect(TokenType.UNTIL);
        LuaExpressionNode condition = parseExpr();
        popScope();
        return new LuaRepeatNode(body, condition);
    }

    /**
     * {@code for Name '=' exp ',' exp [',' exp] do block end} or
     * {@code for namelist in explist do block end}. Disambiguated by whether {@code '='} or
     * {@code ','}/{@code in} follows the first {@code Name}.
     */
    private LuaStatementNode parseFor() {
        expect(TokenType.FOR);
        LuaToken firstName = expect(TokenType.NAME, "<name>");
        if (check(TokenType.ASSIGN)) {
            return parseForNumeric(firstName);
        }
        return parseForGeneric(firstName);
    }

    private LuaStatementNode parseForNumeric(LuaToken nameTok) {
        expect(TokenType.ASSIGN);
        LuaExpressionNode start = parseExpr();
        expect(TokenType.COMMA);
        LuaExpressionNode stop = parseExpr();
        LuaExpressionNode step;
        if (match(TokenType.COMMA)) {
            step = parseExpr();
        } else {
            step = new LuaNumberLiteralNode(1.0);
        }
        expect(TokenType.DO);
        pushScope();
        int slot = currentFunction.declareLocal(nameTok.value);
        LuaStatementNode body = parseBlock();
        popScope();
        expect(TokenType.END);
        return new LuaForNumericNode(start, stop, step, slot, body);
    }

    private LuaStatementNode parseForGeneric(LuaToken firstName) {
        List<String> names = new ArrayList<>();
        names.add(firstName.value);
        while (match(TokenType.COMMA)) {
            names.add(expect(TokenType.NAME, "<name>").value);
        }
        expect(TokenType.IN);
        List<LuaExpressionNode> exprs = parseExprList();
        expect(TokenType.DO);
        pushScope();
        int[] slots = new int[names.size()];
        for (int i = 0; i < names.size(); i++) {
            slots[i] = currentFunction.declareLocal(names.get(i));
        }
        LuaStatementNode body = parseBlock();
        popScope();
        expect(TokenType.END);
        return new LuaForGenericNode(exprs.toArray(new LuaExpressionNode[0]), slots, body);
    }

    /**
     * {@code function funcname funcbody}, {@code funcname ::= Name {'.' Name} [':' Name]}. Desugars
     * to an assignment: {@code function t.a.b:m() end} becomes {@code t.a.b.m = function(self) end}.
     */
    private LuaStatementNode parseFunctionStat() {
        expect(TokenType.FUNCTION);
        LuaToken nameTok = expect(TokenType.NAME, "<name>");
        List<String> path = new ArrayList<>();
        boolean isMethod = false;
        while (check(TokenType.DOT)) {
            advance();
            path.add(expect(TokenType.NAME, "<name>").value);
        }
        if (match(TokenType.COLON)) {
            path.add(expect(TokenType.NAME, "<name>").value);
            isMethod = true;
        }

        LuaExpressionNode funcExpr = parseFuncBody(isMethod);

        LuaAssignmentTarget target;
        if (path.isEmpty()) {
            target = resolveTarget(nameTok.value);
        } else {
            LuaExpressionNode tableExpr = resolveRead(nameTok.value);
            for (int i = 0; i < path.size() - 1; i++) {
                tableExpr = new LuaTableReadNode(tableExpr, new LuaStringLiteralNode(path.get(i)));
            }
            LuaExpressionNode keyExpr = new LuaStringLiteralNode(path.get(path.size() - 1));
            target = new LuaTableFieldTarget(tableExpr, keyExpr);
        }
        return new LuaAssignmentNode(new LuaAssignmentTarget[] {target}, new LuaExpressionNode[] {funcExpr});
    }

    /** {@code local function Name funcbody}. The name is declared as a local *before* the body is
     * parsed so the function can call itself recursively via an upvalue/local reference. */
    private LuaStatementNode parseLocalFunction() {
        LuaToken nameTok = expect(TokenType.NAME, "<name>");
        int slot = currentFunction.declareLocal(nameTok.value);
        LuaExpressionNode funcExpr = parseFuncBody(false);
        LuaAssignmentTarget target = new LuaLocalTarget(slot);
        return new LuaAssignmentNode(new LuaAssignmentTarget[] {target}, new LuaExpressionNode[] {funcExpr});
    }

    /** {@code local namelist ['=' explist]}. Values are evaluated (and any names within them
     * resolved) using the scope *before* the new locals are declared, matching Lua's rule that
     * {@code local x = x} reads the outer {@code x}. */
    private LuaStatementNode parseLocalDecl() {
        List<String> names = new ArrayList<>();
        names.add(expect(TokenType.NAME, "<name>").value);
        while (match(TokenType.COMMA)) {
            names.add(expect(TokenType.NAME, "<name>").value);
        }
        List<LuaExpressionNode> values = new ArrayList<>();
        if (match(TokenType.ASSIGN)) {
            values = parseExprList();
        }
        LuaAssignmentTarget[] targets = new LuaAssignmentTarget[names.size()];
        for (int i = 0; i < names.size(); i++) {
            int slot = currentFunction.declareLocal(names.get(i));
            targets[i] = new LuaLocalTarget(slot);
        }
        return new LuaAssignmentNode(targets, values.toArray(new LuaExpressionNode[0]));
    }

    /**
     * The ambiguous {@code varlist '=' explist | functioncall} statement form. Parses a single
     * {@code prefixexp} (tracking whether it is a plain "var" or ends in a call) and then decides:
     * a following {@code ','} or {@code '='} means it's the start of an (possibly multi-target)
     * assignment; otherwise it must itself be a function/method call used as a statement.
     */
    private LuaStatementNode parseExprStat() {
        Suffixed first = parseSuffixedExpr();
        if (check(TokenType.ASSIGN) || check(TokenType.COMMA)) {
            List<LuaAssignmentTarget> targets = new ArrayList<>();
            targets.add(requireTarget(first));
            while (match(TokenType.COMMA)) {
                targets.add(requireTarget(parseSuffixedExpr()));
            }
            expect(TokenType.ASSIGN);
            List<LuaExpressionNode> values = parseExprList();
            return new LuaAssignmentNode(
                    targets.toArray(new LuaAssignmentTarget[0]), values.toArray(new LuaExpressionNode[0]));
        }
        if (!first.isCall) {
            throw error("syntax error (function call expected)");
        }
        return new LuaExpressionStatementNode(first.expr);
    }

    private LuaAssignmentTarget requireTarget(Suffixed s) {
        if (s.targetTable != null) {
            return new LuaTableFieldTarget(s.targetTable, s.targetKey);
        }
        if (s.simpleName != null) {
            return resolveTarget(s.simpleName);
        }
        throw error("syntax error (cannot assign to this expression)");
    }

    // ------------------------------------------------------------------
    // Expressions
    // ------------------------------------------------------------------

    /** {@code explist ::= {exp ','} exp}. */
    private List<LuaExpressionNode> parseExprList() {
        List<LuaExpressionNode> list = new ArrayList<>();
        list.add(parseExpr());
        while (match(TokenType.COMMA)) {
            list.add(parseExpr());
        }
        return list;
    }

    private LuaExpressionNode parseExpr() {
        return parseOrExpr();
    }

    /** {@code or} (lowest precedence). */
    private LuaExpressionNode parseOrExpr() {
        LuaExpressionNode left = parseAndExpr();
        while (match(TokenType.OR)) {
            left = new LuaOrNode(left, parseAndExpr());
        }
        return left;
    }

    /** {@code and}. */
    private LuaExpressionNode parseAndExpr() {
        LuaExpressionNode left = parseComparisonExpr();
        while (match(TokenType.AND)) {
            left = new LuaAndNode(left, parseComparisonExpr());
        }
        return left;
    }

    /** {@code <  >  <=  >=  ~=  ==} (left-associative, non-chaining: each application starts fresh). */
    private LuaExpressionNode parseComparisonExpr() {
        LuaExpressionNode left = parseConcatExpr();
        while (true) {
            TokenType t = peek().type;
            if (t == TokenType.LT) {
                advance();
                left = new LuaLessThanNode(left, parseConcatExpr());
            } else if (t == TokenType.GT) {
                advance();
                left = new LuaGreaterThanNode(left, parseConcatExpr());
            } else if (t == TokenType.LE) {
                advance();
                left = new LuaLessEqualNode(left, parseConcatExpr());
            } else if (t == TokenType.GE) {
                advance();
                left = new LuaGreaterEqualNode(left, parseConcatExpr());
            } else if (t == TokenType.EQ) {
                advance();
                left = new LuaEqualNode(left, parseConcatExpr());
            } else if (t == TokenType.NEQ) {
                advance();
                left = new LuaNotEqualNode(left, parseConcatExpr());
            } else {
                break;
            }
        }
        return left;
    }

    /** {@code ..} (right-associative). */
    private LuaExpressionNode parseConcatExpr() {
        LuaExpressionNode left = parseAddExpr();
        if (match(TokenType.DOTDOT)) {
            LuaExpressionNode right = parseConcatExpr();
            return new LuaConcatNode(left, right);
        }
        return left;
    }

    /** {@code +  -} (left-associative binary). */
    private LuaExpressionNode parseAddExpr() {
        LuaExpressionNode left = parseMulExpr();
        while (true) {
            if (match(TokenType.PLUS)) {
                left = new LuaAddNode(left, parseMulExpr());
            } else if (match(TokenType.MINUS)) {
                left = new LuaSubNode(left, parseMulExpr());
            } else {
                break;
            }
        }
        return left;
    }

    /** {@code *  /  %} (left-associative). */
    private LuaExpressionNode parseMulExpr() {
        LuaExpressionNode left = parseUnaryExpr();
        while (true) {
            if (match(TokenType.STAR)) {
                left = new LuaMulNode(left, parseUnaryExpr());
            } else if (match(TokenType.SLASH)) {
                left = new LuaDivNode(left, parseUnaryExpr());
            } else if (match(TokenType.PERCENT)) {
                left = new LuaModNode(left, parseUnaryExpr());
            } else {
                break;
            }
        }
        return left;
    }

    /** {@code not  #  -} (unary; binds tighter than all binary operators except {@code ^}). */
    private LuaExpressionNode parseUnaryExpr() {
        if (match(TokenType.NOT)) {
            return new LuaNotNode(parseUnaryExpr());
        }
        if (match(TokenType.HASH)) {
            return new LuaLenNode(parseUnaryExpr());
        }
        if (match(TokenType.MINUS)) {
            return new LuaUnaryMinusNode(parseUnaryExpr());
        }
        return parsePowExpr();
    }

    /** {@code ^} (right-associative; binds tighter than unary operators on both sides, e.g.
     * {@code -2^2 == -(2^2)} and {@code 2^-2 == 2^(-2)}). */
    private LuaExpressionNode parsePowExpr() {
        LuaExpressionNode left = parsePrimaryExpr();
        if (match(TokenType.CARET)) {
            LuaExpressionNode right = parseUnaryExpr();
            return new LuaPowNode(left, right);
        }
        return left;
    }

    /** {@code nil | false | true | Number | String | '...' | function | prefixexp | tableconstructor}. */
    private LuaExpressionNode parsePrimaryExpr() {
        LuaToken tok = peek();
        switch (tok.type) {
            case NIL:
                advance();
                return new LuaNilLiteralNode();
            case TRUE:
                advance();
                return new LuaBooleanLiteralNode(true);
            case FALSE:
                advance();
                return new LuaBooleanLiteralNode(false);
            case NUMBER:
                advance();
                return new LuaNumberLiteralNode(parseNumber(tok.value));
            case STRING:
                advance();
                return new LuaStringLiteralNode(decodeString(tok.value));
            case DOTDOTDOT: {
                advance();
                if (!currentFunction.vararg) {
                    throw error("cannot use '...' outside a vararg function");
                }
                return new LuaVarargNode();
            }
            case FUNCTION:
                advance();
                return parseFuncBody(false);
            case LBRACE:
                return parseTableConstructor();
            case NAME:
            case LPAREN:
                return parseSuffixedExpr().expr;
            default:
                throw error("unexpected symbol near '" + tokenDisplay(tok) + "'");
        }
    }

    /** Result of parsing a {@code prefixexp} with any trailing suffixes ({@code .Name},
     * {@code [exp]}, {@code :Name args}, {@code args}) applied. Tracks enough information about
     * the *last* suffix applied to decide, at statement level, whether this is assignable (a
     * "var") or a call usable as a bare statement. */
    private static final class Suffixed {
        LuaExpressionNode expr;
        boolean isCall;
        String simpleName;
        LuaExpressionNode targetTable;
        LuaExpressionNode targetKey;
    }

    /** {@code prefixexp ::= var | functioncall | '(' exp ')'}, with {@code var}/{@code functioncall}'s
     * suffix chain ({@code '.'Name}, {@code '['exp']'}, {@code ':'Name args}, {@code args}) applied
     * left-to-right. */
    private Suffixed parseSuffixedExpr() {
        Suffixed s = new Suffixed();
        LuaToken tok = peek();
        if (tok.type == TokenType.NAME) {
            advance();
            s.simpleName = tok.value;
            s.expr = resolveRead(tok.value);
        } else if (tok.type == TokenType.LPAREN) {
            advance();
            LuaExpressionNode inner = parseExpr();
            expect(TokenType.RPAREN);
            // A parenthesized expression always truncates to a single value (Lua 5.1 &sect;2.5.7);
            // executeGeneric() on any node (including calls/varargs) already yields just the first
            // value, so no extra wrapper node is needed here.
            s.expr = inner;
        } else {
            throw error("unexpected symbol near '" + tokenDisplay(tok) + "'");
        }

        while (true) {
            if (match(TokenType.DOT)) {
                LuaToken nameTok = expect(TokenType.NAME, "<name>");
                LuaExpressionNode table = s.expr;
                LuaExpressionNode key = new LuaStringLiteralNode(nameTok.value);
                s.expr = new LuaTableReadNode(table, key);
                s.simpleName = null;
                s.targetTable = table;
                s.targetKey = key;
                s.isCall = false;
            } else if (match(TokenType.LBRACKET)) {
                LuaExpressionNode key = parseExpr();
                expect(TokenType.RBRACKET);
                LuaExpressionNode table = s.expr;
                s.expr = new LuaTableReadNode(table, key);
                s.simpleName = null;
                s.targetTable = table;
                s.targetKey = key;
                s.isCall = false;
            } else if (match(TokenType.COLON)) {
                LuaToken methodTok = expect(TokenType.NAME, "<name>");
                LuaExpressionNode[] args = parseArgs();
                s.expr = new LuaMethodCallNode(s.expr, methodTok.value, args);
                s.simpleName = null;
                s.targetTable = null;
                s.targetKey = null;
                s.isCall = true;
            } else if (check(TokenType.LPAREN) || check(TokenType.STRING) || check(TokenType.LBRACE)) {
                LuaExpressionNode[] args = parseArgs();
                s.expr = new LuaFunctionCallNode(s.expr, args);
                s.simpleName = null;
                s.targetTable = null;
                s.targetKey = null;
                s.isCall = true;
            } else {
                break;
            }
        }
        return s;
    }

    /** {@code args ::= '(' [explist] ')' | tableconstructor | String}. */
    private LuaExpressionNode[] parseArgs() {
        if (check(TokenType.STRING)) {
            LuaToken t = advance();
            return new LuaExpressionNode[] {new LuaStringLiteralNode(decodeString(t.value))};
        }
        if (check(TokenType.LBRACE)) {
            return new LuaExpressionNode[] {parseTableConstructor()};
        }
        expect(TokenType.LPAREN);
        List<LuaExpressionNode> args = new ArrayList<>();
        if (!check(TokenType.RPAREN)) {
            args = parseExprList();
        }
        expect(TokenType.RPAREN);
        return args.toArray(new LuaExpressionNode[0]);
    }

    /** {@code function funcbody}, where {@code funcbody ::= '(' [parlist] ')' block end} and
     * {@code parlist ::= namelist [',' '...'] | '...'}. Compiles the body into its own
     * {@link LuaRootNode}/{@link CallTarget}, wrapped in a {@link LuaFunctionDefNode} that captures
     * the defining frame at runtime. When {@code isMethod} is set (i.e. this is a {@code function
     * t:m() ... end} declaration), an implicit leading {@code self} parameter is added. */
    private LuaExpressionNode parseFuncBody(boolean isMethod) {
        expect(TokenType.LPAREN);

        FunctionScope parentFn = currentFunction;
        FunctionScope fn = new FunctionScope(parentFn);
        currentFunction = fn;
        fn.pushBlock();

        List<Integer> paramSlots = new ArrayList<>();
        if (isMethod) {
            paramSlots.add(fn.declareLocal("self"));
        }
        boolean isVararg = false;
        if (!check(TokenType.RPAREN)) {
            while (true) {
                if (check(TokenType.DOTDOTDOT)) {
                    advance();
                    isVararg = true;
                    break;
                }
                LuaToken p = expect(TokenType.NAME, "<name>");
                paramSlots.add(fn.declareLocal(p.value));
                if (!match(TokenType.COMMA)) {
                    break;
                }
            }
        }
        expect(TokenType.RPAREN);
        fn.vararg = isVararg;

        LuaStatementNode block = parseBlock();
        expect(TokenType.END);

        fn.popBlock();

        int[] slots = new int[paramSlots.size()];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = paramSlots.get(i);
        }

        LuaStatementNode prologue = new ArgumentPrologueNode(slots, isVararg);
        LuaStatementNode fullBody = new LuaBlockNode(new LuaStatementNode[] {prologue, block});
        FrameDescriptor frameDescriptor = fn.frameBuilder.build();
        LuaRootNode rootNode = new LuaRootNode(language, frameDescriptor, fullBody);
        CallTarget callTarget = rootNode.getCallTarget();
        int numUpvalues = fn.numUpvalues;

        currentFunction = parentFn;
        return new LuaFunctionDefNode(callTarget, numUpvalues);
    }

    /**
     * Synthetic prologue statement (not part of the Lua grammar) prepended to every compiled
     * function body: copies the actual call arguments (Lua 5.1 &sect;5, "adjusted" to the declared
     * parameter count, missing ones defaulting to {@code nil}) from the calling convention's raw
     * argument array into their frame slots, and &mdash; for vararg functions &mdash; stashes the
     * surplus positional arguments as the {@code ...} value at {@link LuaRootNode#VARARGS_FRAME_SLOT}.
     */
    private static final class ArgumentPrologueNode extends LuaStatementNode {
        private final int[] paramSlots;
        private final boolean vararg;

        ArgumentPrologueNode(int[] paramSlots, boolean vararg) {
            this.paramSlots = paramSlots;
            this.vararg = vararg;
        }

        @Override
        public void executeVoid(VirtualFrame frame) {
            Object[] args = LuaRootNode.getPositionalArguments(frame);
            for (int i = 0; i < paramSlots.length; i++) {
                frame.setObject(paramSlots[i], i < args.length ? args[i] : LuaNil.INSTANCE);
            }
            if (vararg) {
                Object[] extra =
                        args.length > paramSlots.length
                                ? Arrays.copyOfRange(args, paramSlots.length, args.length)
                                : NO_VALUES;
                frame.setObject(LuaRootNode.VARARGS_FRAME_SLOT, extra);
            }
        }
    }

    /** {@code tableconstructor ::= '{' [fieldlist] '}'}, {@code fieldlist ::= field {fieldsep field}
     * [fieldsep]}, {@code field ::= '[' exp ']' '=' exp | Name '=' exp | exp}, {@code fieldsep ::= ','
     * | ';'}. A {@code null} key entry in the resulting node marks a list-style field. */
    private LuaExpressionNode parseTableConstructor() {
        expect(TokenType.LBRACE);
        List<LuaExpressionNode> keys = new ArrayList<>();
        List<LuaExpressionNode> values = new ArrayList<>();
        while (!check(TokenType.RBRACE)) {
            if (check(TokenType.LBRACKET)) {
                advance();
                LuaExpressionNode key = parseExpr();
                expect(TokenType.RBRACKET);
                expect(TokenType.ASSIGN);
                LuaExpressionNode value = parseExpr();
                keys.add(key);
                values.add(value);
            } else if (check(TokenType.NAME) && peek(1).type == TokenType.ASSIGN) {
                LuaToken nameTok = advance();
                advance(); // '='
                LuaExpressionNode value = parseExpr();
                keys.add(new LuaStringLiteralNode(nameTok.value));
                values.add(value);
            } else {
                LuaExpressionNode value = parseExpr();
                keys.add(null);
                values.add(value);
            }
            if (check(TokenType.COMMA) || check(TokenType.SEMICOLON)) {
                advance();
            } else {
                break;
            }
        }
        expect(TokenType.RBRACE);
        return new LuaTableConstructorNode(keys.toArray(new LuaExpressionNode[0]), values.toArray(new LuaExpressionNode[0]));
    }

    // ------------------------------------------------------------------
    // Literal decoding
    // ------------------------------------------------------------------

    /** Decodes a {@code NUMBER} token's raw text (Lua 5.1 &sect;2.1): hex integers ({@code 0x..}) or
     * decimal numerals with an optional fractional part/exponent. */
    private double parseNumber(String text) {
        if (text.length() > 1 && text.charAt(0) == '0' && (text.charAt(1) == 'x' || text.charAt(1) == 'X')) {
            return new BigInteger(text.substring(2), 16).doubleValue();
        }
        return Double.parseDouble(text);
    }

    /** Decodes a {@code STRING} token's raw text (still carrying its quotes/brackets and, for short
     * strings, unprocessed backslash escapes verbatim) into the actual Lua string value. */
    private String decodeString(String raw) {
        if (raw.charAt(0) == '[') {
            return decodeLongString(raw);
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        int end = raw.length() - 1;
        while (i < end) {
            char c = raw.charAt(i);
            if (c != '\\') {
                sb.append(c);
                i++;
                continue;
            }
            i++;
            char e = raw.charAt(i);
            switch (e) {
                case 'a':
                    sb.append('\007');
                    i++;
                    break;
                case 'b':
                    sb.append('\b');
                    i++;
                    break;
                case 'f':
                    sb.append('\f');
                    i++;
                    break;
                case 'n':
                    sb.append('\n');
                    i++;
                    break;
                case 'r':
                    sb.append('\r');
                    i++;
                    break;
                case 't':
                    sb.append('\t');
                    i++;
                    break;
                case 'v':
                    sb.append('\013');
                    i++;
                    break;
                case '\\':
                    sb.append('\\');
                    i++;
                    break;
                case '"':
                    sb.append('"');
                    i++;
                    break;
                case '\'':
                    sb.append('\'');
                    i++;
                    break;
                case '[':
                    sb.append('[');
                    i++;
                    break;
                case ']':
                    sb.append(']');
                    i++;
                    break;
                case '\n':
                    sb.append('\n');
                    i++;
                    break;
                case '\r':
                    sb.append('\n');
                    i++;
                    if (i < end && raw.charAt(i) == '\n') {
                        i++;
                    }
                    break;
                default:
                    if (Character.isDigit(e)) {
                        int n = 0;
                        int digits = 0;
                        while (digits < 3 && i < end && Character.isDigit(raw.charAt(i))) {
                            n = n * 10 + (raw.charAt(i) - '0');
                            i++;
                            digits++;
                        }
                        sb.append((char) n);
                    } else {
                        // The lexer already validated escapes; anything else here would be a lexer bug.
                        sb.append(e);
                        i++;
                    }
            }
        }
        return sb.toString();
    }

    /** Decodes a long-bracket string {@code [=*[ ... ]=*]}, stripping the delimiters and a single
     * leading newline immediately after the opening bracket, if present (Lua 5.1 &sect;2.1). Long
     * strings have no escape processing. */
    private String decodeLongString(String raw) {
        int i = 1;
        int level = 0;
        while (raw.charAt(i) == '=') {
            level++;
            i++;
        }
        i++; // second '['
        if (i < raw.length() && (raw.charAt(i) == '\n' || raw.charAt(i) == '\r')) {
            char c0 = raw.charAt(i);
            i++;
            if (i < raw.length() && ((c0 == '\n' && raw.charAt(i) == '\r') || (c0 == '\r' && raw.charAt(i) == '\n'))) {
                i++;
            }
        }
        int contentEnd = raw.length() - (level + 2);
        return raw.substring(i, contentEnd);
    }
}

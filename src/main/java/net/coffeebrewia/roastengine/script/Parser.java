package net.coffeebrewia.roastengine.script;

import net.coffeebrewia.roastengine.script.Ast.Expr;
import net.coffeebrewia.roastengine.script.Ast.Stmt;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns tokens into a tree of statements.
 *
 * <p>A plain recursive-descent parser, with the precedence Python has: {@code or}, {@code and},
 * {@code not}, comparisons, {@code + -}, {@code * / %}, {@code **}, then calls, indexing and
 * attributes.
 */
final class Parser {

    private final List<Token> tokens;
    private int index;

    Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    /** Parses a whole script. */
    static List<Stmt> parse(String source) {
        return new Parser(new Lexer(source).tokenize()).program();
    }

    private List<Stmt> program() {
        List<Stmt> statements = new ArrayList<>();
        skipNewlines();
        while (!check(Token.Kind.END)) {
            statements.add(statement());
            skipNewlines();
        }
        return statements;
    }

    // --- Statements ---------------------------------------------------------------------

    private Stmt statement() {
        Token token = peek();
        if (token.kind() == Token.Kind.KEYWORD) {
            switch (token.text()) {
                case "if" -> {
                    return ifStatement("if");
                }
                case "while" -> {
                    return whileStatement();
                }
                case "for" -> {
                    return forStatement();
                }
                case "def" -> {
                    return functionDef();
                }
                case "return" -> {
                    advance();
                    Expr value = check(Token.Kind.NEWLINE) ? null : expression();
                    endLine();
                    return new Ast.Return(value, token.line());
                }
                case "break" -> {
                    advance();
                    endLine();
                    return new Ast.Break(token.line());
                }
                case "continue" -> {
                    advance();
                    endLine();
                    return new Ast.Continue(token.line());
                }
                case "pass" -> {
                    advance();
                    endLine();
                    return new Ast.Pass(token.line());
                }
                default -> {
                    // Falls through to an expression, e.g. a line starting with True.
                }
            }
        }
        Expr first = expression();
        for (String operator : new String[]{"=", "+=", "-=", "*=", "/="}) {
            if (checkOperator(operator)) {
                advance();
                Expr value = expression();
                endLine();
                if (!(first instanceof Ast.Name || first instanceof Ast.Index
                        || first instanceof Ast.Attribute)) {
                    throw new ScriptError("You can only put a value into a name, a list item or a property",
                            token.line());
                }
                return new Ast.Assign(first, operator, value, token.line());
            }
        }
        endLine();
        return new Ast.ExpressionStatement(first, token.line());
    }

    /** {@code if} and {@code elif}: an elif becomes an if inside the else, which keeps it simple. */
    private Stmt ifStatement(String keyword) {
        int line = peek().line();
        expectKeyword(keyword);
        Expr condition = expression();
        List<Stmt> then = block();
        List<Stmt> otherwise = new ArrayList<>();
        if (checkKeyword("elif")) {
            otherwise.add(ifStatement("elif"));
        } else if (checkKeyword("else")) {
            advance();
            otherwise = block();
        }
        return new Ast.If(condition, then, otherwise, line);
    }

    private Stmt whileStatement() {
        int line = peek().line();
        expectKeyword("while");
        Expr condition = expression();
        return new Ast.While(condition, block(), line);
    }

    private Stmt forStatement() {
        int line = peek().line();
        expectKeyword("for");
        String variable = expectName();
        expectKeyword("in");
        Expr iterable = expression();
        return new Ast.For(variable, iterable, block(), line);
    }

    private Stmt functionDef() {
        int line = peek().line();
        expectKeyword("def");
        String name = expectName();
        expectOperator("(");
        List<String> parameters = new ArrayList<>();
        if (!checkOperator(")")) {
            do {
                parameters.add(expectName());
            } while (matchOperator(","));
        }
        expectOperator(")");
        return new Ast.FunctionDef(name, parameters, block(), line);
    }

    /** A {@code :} then either an indented block or a single statement on the same line. */
    private List<Stmt> block() {
        expectOperator(":");
        if (!check(Token.Kind.NEWLINE)) {
            List<Stmt> single = new ArrayList<>();
            single.add(statement());
            return single;
        }
        advance(); // the newline
        if (!check(Token.Kind.INDENT)) {
            throw new ScriptError("This line ends with ':', so the lines under it need indenting",
                    previous().line());
        }
        advance();
        List<Stmt> statements = new ArrayList<>();
        skipNewlines();
        while (!check(Token.Kind.DEDENT) && !check(Token.Kind.END)) {
            statements.add(statement());
            skipNewlines();
        }
        if (check(Token.Kind.DEDENT)) {
            advance();
        }
        return statements;
    }

    // --- Expressions --------------------------------------------------------------------

    private Expr expression() {
        return or();
    }

    private Expr or() {
        Expr left = and();
        while (checkKeyword("or")) {
            int line = advance().line();
            left = new Ast.Logical("or", left, and(), line);
        }
        return left;
    }

    private Expr and() {
        Expr left = not();
        while (checkKeyword("and")) {
            int line = advance().line();
            left = new Ast.Logical("and", left, not(), line);
        }
        return left;
    }

    private Expr not() {
        if (checkKeyword("not")) {
            int line = advance().line();
            return new Ast.Unary("not", not(), line);
        }
        return comparison();
    }

    private static final List<String> COMPARISONS = List.of("==", "!=", "<=", ">=", "<", ">");

    private Expr comparison() {
        Expr left = sum();
        while (true) {
            if (checkKeyword("in")) {
                int line = advance().line();
                left = new Ast.Binary("in", left, sum(), line);
                continue;
            }
            String operator = currentOperator(COMPARISONS);
            if (operator == null) {
                return left;
            }
            int line = advance().line();
            left = new Ast.Binary(operator, left, sum(), line);
        }
    }

    private Expr sum() {
        Expr left = product();
        String operator;
        while ((operator = currentOperator(List.of("+", "-"))) != null) {
            int line = advance().line();
            left = new Ast.Binary(operator, left, product(), line);
        }
        return left;
    }

    private Expr product() {
        Expr left = unary();
        String operator;
        while ((operator = currentOperator(List.of("*", "/", "%"))) != null) {
            int line = advance().line();
            left = new Ast.Binary(operator, left, unary(), line);
        }
        return left;
    }

    private Expr unary() {
        if (checkOperator("-")) {
            int line = advance().line();
            return new Ast.Unary("-", unary(), line);
        }
        return power();
    }

    private Expr power() {
        Expr base = callOrAccess();
        if (checkOperator("**")) {
            int line = advance().line();
            return new Ast.Binary("**", base, unary(), line);
        }
        return base;
    }

    private Expr callOrAccess() {
        Expr value = primary();
        while (true) {
            if (checkOperator("(")) {
                int line = advance().line();
                List<Expr> arguments = new ArrayList<>();
                if (!checkOperator(")")) {
                    do {
                        arguments.add(expression());
                    } while (matchOperator(","));
                }
                expectOperator(")");
                value = new Ast.Call(value, arguments, line);
            } else if (checkOperator("[")) {
                int line = advance().line();
                Expr index = expression();
                expectOperator("]");
                value = new Ast.Index(value, index, line);
            } else if (checkOperator(".")) {
                int line = advance().line();
                value = new Ast.Attribute(value, expectName(), line);
            } else {
                return value;
            }
        }
    }

    private Expr primary() {
        Token token = peek();
        switch (token.kind()) {
            case NUMBER -> {
                advance();
                return new Ast.Literal(Double.parseDouble(token.text()), token.line());
            }
            case STRING -> {
                advance();
                return new Ast.Literal(token.text(), token.line());
            }
            case NAME -> {
                advance();
                return new Ast.Name(token.text(), token.line());
            }
            case KEYWORD -> {
                if (token.text().equals("True") || token.text().equals("False")) {
                    advance();
                    return new Ast.Literal(token.text().equals("True"), token.line());
                }
                if (token.text().equals("None")) {
                    advance();
                    return new Ast.Literal(null, token.line());
                }
            }
            case OPERATOR -> {
                if (token.text().equals("(")) {
                    advance();
                    Expr inner = expression();
                    expectOperator(")");
                    return inner;
                }
                if (token.text().equals("[")) {
                    advance();
                    List<Expr> items = new ArrayList<>();
                    if (!checkOperator("]")) {
                        do {
                            items.add(expression());
                        } while (matchOperator(","));
                    }
                    expectOperator("]");
                    return new Ast.ListLiteral(items, token.line());
                }
            }
            default -> {
                // Falls through to the error below.
            }
        }
        throw new ScriptError("I did not expect " + describe(token) + " here", token.line());
    }

    // --- Small helpers ------------------------------------------------------------------

    private static String describe(Token token) {
        return switch (token.kind()) {
            case NEWLINE -> "the end of the line";
            case END -> "the end of the script";
            case INDENT, DEDENT -> "this indentation";
            default -> "'" + token.text() + "'";
        };
    }

    private String currentOperator(List<String> operators) {
        Token token = peek();
        return token.kind() == Token.Kind.OPERATOR && operators.contains(token.text()) ? token.text() : null;
    }

    private Token peek() {
        return tokens.get(index);
    }

    private Token previous() {
        return tokens.get(Math.max(0, index - 1));
    }

    private Token advance() {
        Token token = tokens.get(index);
        if (token.kind() != Token.Kind.END) {
            index++;
        }
        return token;
    }

    private boolean check(Token.Kind kind) {
        return peek().kind() == kind;
    }

    private boolean checkOperator(String text) {
        return peek().is(Token.Kind.OPERATOR, text);
    }

    private boolean checkKeyword(String text) {
        return peek().is(Token.Kind.KEYWORD, text);
    }

    private boolean matchOperator(String text) {
        if (checkOperator(text)) {
            advance();
            return true;
        }
        return false;
    }

    private void expectOperator(String text) {
        if (!matchOperator(text)) {
            throw new ScriptError("I expected '" + text + "' but found " + describe(peek()), peek().line());
        }
    }

    private void expectKeyword(String text) {
        if (!checkKeyword(text)) {
            throw new ScriptError("I expected '" + text + "' but found " + describe(peek()), peek().line());
        }
        advance();
    }

    private String expectName() {
        if (!check(Token.Kind.NAME)) {
            throw new ScriptError("I expected a name but found " + describe(peek()), peek().line());
        }
        return advance().text();
    }

    private void endLine() {
        if (check(Token.Kind.NEWLINE)) {
            advance();
        } else if (!check(Token.Kind.END) && !check(Token.Kind.DEDENT)) {
            throw new ScriptError("I did not expect " + describe(peek()) + " at the end of this line",
                    peek().line());
        }
    }

    private void skipNewlines() {
        while (check(Token.Kind.NEWLINE)) {
            advance();
        }
    }
}

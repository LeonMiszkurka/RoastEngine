package net.coffeebrewia.roastengine.script;

import java.util.List;

/**
 * The shape of a parsed script: what the {@link Parser} builds and the {@link Interpreter} runs.
 * Every node carries the line it came from, so a mistake can point at the right line.
 */
final class Ast {

    private Ast() {
    }

    sealed interface Expr permits Literal, Name, ListLiteral, Unary, Binary, Logical, Call, Index, Attribute {
        int line();
    }

    /** A number, a piece of text, True, False or None. */
    record Literal(Object value, int line) implements Expr {
    }

    record Name(String name, int line) implements Expr {
    }

    record ListLiteral(List<Expr> items, int line) implements Expr {
    }

    /** {@code -x} or {@code not x}. */
    record Unary(String operator, Expr value, int line) implements Expr {
    }

    /** Arithmetic and comparisons. */
    record Binary(String operator, Expr left, Expr right, int line) implements Expr {
    }

    /** {@code and} / {@code or}, which stop as soon as the answer is known. */
    record Logical(String operator, Expr left, Expr right, int line) implements Expr {
    }

    record Call(Expr callee, List<Expr> arguments, int line) implements Expr {
    }

    record Index(Expr target, Expr index, int line) implements Expr {
    }

    record Attribute(Expr target, String name, int line) implements Expr {
    }

    sealed interface Stmt permits ExpressionStatement, Assign, If, While, For, FunctionDef, Return,
            Break, Continue, Pass {
        int line();
    }

    record ExpressionStatement(Expr value, int line) implements Stmt {
    }

    /** {@code x = 1}, {@code x += 1}, {@code items[0] = 2}, {@code self.speed = 3}. */
    record Assign(Expr target, String operator, Expr value, int line) implements Stmt {
    }

    /** {@code elif} chains are kept as an {@code If} inside {@code otherwise}. */
    record If(Expr condition, List<Stmt> then, List<Stmt> otherwise, int line) implements Stmt {
    }

    record While(Expr condition, List<Stmt> body, int line) implements Stmt {
    }

    record For(String variable, Expr iterable, List<Stmt> body, int line) implements Stmt {
    }

    record FunctionDef(String name, List<String> parameters, List<Stmt> body, int line) implements Stmt {
    }

    record Return(Expr value, int line) implements Stmt {
    }

    record Break(int line) implements Stmt {
    }

    record Continue(int line) implements Stmt {
    }

    record Pass(int line) implements Stmt {
    }
}

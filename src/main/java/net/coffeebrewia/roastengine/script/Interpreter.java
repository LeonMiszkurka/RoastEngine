package net.coffeebrewia.roastengine.script;

import net.coffeebrewia.roastengine.script.Ast.Expr;
import net.coffeebrewia.roastengine.script.Ast.Stmt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a parsed script.
 *
 * <p>Values are kept simple: numbers are {@code Double}, text is {@code String}, {@code True} and
 * {@code False} are {@code Boolean}, {@code None} is null, and a list is a {@code List<Object>}.
 * Anything the engine hands in (an object in the world, the player) arrives as a
 * {@link ScriptObject}, whose properties and methods scripts reach with a dot.
 *
 * <p>A script is a guest in the game loop, so it is kept on a leash: every statement counts
 * against a budget, loops and calls have depth limits, and there is no way to reach files, the
 * network or Java itself - the only things a script can touch are the functions the engine gives it.
 */
public final class Interpreter {

    /** Statements one event may run before it is stopped. A frame's worth of work, generously. */
    private static final int STEP_BUDGET = 200_000;
    private static final int MAX_CALL_DEPTH = 64;

    /** Something the script can call: a function it defined, or one the engine provides. */
    public interface Callable {
        Object call(List<Object> arguments);
    }

    /** Something with properties and methods, such as an object in the world. */
    public interface ScriptObject {
        Object property(String name);

        default void setProperty(String name, Object value) {
            throw new ScriptError("'" + name + "' cannot be changed", 0);
        }
    }

    /** Where names live: each block sees its own names and those around it. */
    static final class Scope {
        private final Map<String, Object> values = new HashMap<>();
        private final Scope parent;

        Scope(Scope parent) {
            this.parent = parent;
        }

        boolean has(String name) {
            return values.containsKey(name) || (parent != null && parent.has(name));
        }

        Object get(String name, int line) {
            if (values.containsKey(name)) {
                return values.get(name);
            }
            if (parent != null) {
                return parent.get(name, line);
            }
            throw new ScriptError("There is nothing called '" + name + "' here", line);
        }

        /** Assigns where the name already exists, otherwise makes it here. */
        void set(String name, Object value) {
            Scope scope = this;
            while (scope != null) {
                if (scope.values.containsKey(name)) {
                    scope.values.put(name, value);
                    return;
                }
                scope = scope.parent;
            }
            values.put(name, value);
        }

        void define(String name, Object value) {
            values.put(name, value);
        }
    }

    // Unwinding: these carry break/continue/return out of nested blocks.
    private static final class BreakSignal extends RuntimeException {
        BreakSignal() {
            super(null, null, false, false);
        }
    }

    private static final class ContinueSignal extends RuntimeException {
        ContinueSignal() {
            super(null, null, false, false);
        }
    }

    private static final class ReturnSignal extends RuntimeException {
        final Object value;

        ReturnSignal(Object value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    /** A function written in the script. */
    private final class ScriptFunction implements Callable {
        private final Ast.FunctionDef definition;
        private final Scope closure;

        ScriptFunction(Ast.FunctionDef definition, Scope closure) {
            this.definition = definition;
            this.closure = closure;
        }

        @Override
        public Object call(List<Object> arguments) {
            if (arguments.size() != definition.parameters().size()) {
                throw new ScriptError(definition.name() + "() wants " + definition.parameters().size()
                        + " value(s) but got " + arguments.size(), definition.line());
            }
            Scope scope = new Scope(closure);
            for (int i = 0; i < arguments.size(); i++) {
                scope.define(definition.parameters().get(i), arguments.get(i));
            }
            depth++;
            if (depth > MAX_CALL_DEPTH) {
                depth = 0;
                throw new ScriptError("This script calls itself too many times", definition.line());
            }
            try {
                run(definition.body(), scope);
                return null;
            } catch (ReturnSignal signal) {
                return signal.value;
            } finally {
                depth--;
            }
        }

        @Override
        public String toString() {
            return "<function " + definition.name() + ">";
        }
    }

    private final Scope globals = new Scope(null);
    private int steps;
    private int depth;

    public Interpreter() {
        Builtins.install(globals);
    }

    /** Makes a value or function available to scripts by name. */
    public void define(String name, Object value) {
        globals.define(name, value);
    }

    public boolean hasFunction(String name) {
        return globals.has(name) && globals.get(name, 0) instanceof Callable;
    }

    /** Runs a whole script once: its top-level lines, which usually define functions. */
    public void run(List<Stmt> program) {
        steps = 0;
        depth = 0;
        run(program, globals);
    }

    /** Calls one of the script's functions, such as {@code on_update}. */
    public Object callFunction(String name, List<Object> arguments) {
        steps = 0;
        depth = 0;
        Object function = globals.get(name, 0);
        if (!(function instanceof Callable callable)) {
            throw new ScriptError("'" + name + "' is not something that can be called", 0);
        }
        return callable.call(arguments);
    }

    // --- Statements ---------------------------------------------------------------------

    private void run(List<Stmt> statements, Scope scope) {
        for (Stmt statement : statements) {
            execute(statement, scope);
        }
    }

    private void execute(Stmt statement, Scope scope) {
        if (++steps > STEP_BUDGET) {
            throw new ScriptError("This script is taking too long - is a loop never finishing?",
                    statement.line());
        }
        if (statement instanceof Ast.ExpressionStatement expression) {
            evaluate(expression.value(), scope);
        } else if (statement instanceof Ast.Assign assign) {
            assign(assign, scope);
        } else if (statement instanceof Ast.If branch) {
            if (truthy(evaluate(branch.condition(), scope))) {
                run(branch.then(), new Scope(scope));
            } else {
                run(branch.otherwise(), new Scope(scope));
            }
        } else if (statement instanceof Ast.While loop) {
            while (truthy(evaluate(loop.condition(), scope))) {
                if (++steps > STEP_BUDGET) {
                    throw new ScriptError("This loop is taking too long - does it ever finish?",
                            loop.line());
                }
                try {
                    run(loop.body(), new Scope(scope));
                } catch (BreakSignal stop) {
                    break;
                } catch (ContinueSignal skip) {
                    // Next turn around the loop.
                }
            }
        } else if (statement instanceof Ast.For loop) {
            for (Object item : iterable(evaluate(loop.iterable(), scope), loop.line())) {
                Scope inner = new Scope(scope);
                inner.define(loop.variable(), item);
                try {
                    run(loop.body(), inner);
                } catch (BreakSignal stop) {
                    break;
                } catch (ContinueSignal skip) {
                    // Next item.
                }
            }
        } else if (statement instanceof Ast.FunctionDef definition) {
            scope.define(definition.name(), new ScriptFunction(definition, scope));
        } else if (statement instanceof Ast.Return returned) {
            throw new ReturnSignal(returned.value() == null ? null : evaluate(returned.value(), scope));
        } else if (statement instanceof Ast.Break) {
            throw new BreakSignal();
        } else if (statement instanceof Ast.Continue) {
            throw new ContinueSignal();
        }
        // Ast.Pass does nothing, which is the point of it.
    }

    private void assign(Ast.Assign assign, Scope scope) {
        Object value = evaluate(assign.value(), scope);
        if (!assign.operator().equals("=")) {
            Object current = evaluate(assign.target(), scope);
            value = binary(assign.operator().substring(0, 1), current, value, assign.line());
        }
        if (assign.target() instanceof Ast.Name name) {
            scope.set(name.name(), value);
        } else if (assign.target() instanceof Ast.Index index) {
            Object target = evaluate(index.target(), scope);
            if (!(target instanceof List<?> list)) {
                throw new ScriptError("Only lists can have items put into them", assign.line());
            }
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) list;
            items.set(listIndex(items, evaluate(index.index(), scope), assign.line()), value);
        } else if (assign.target() instanceof Ast.Attribute attribute) {
            Object target = evaluate(attribute.target(), scope);
            if (!(target instanceof ScriptObject object)) {
                throw new ScriptError("'" + attribute.name() + "' cannot be changed on this",
                        assign.line());
            }
            try {
                object.setProperty(attribute.name(), value);
            } catch (ScriptError e) {
                throw new ScriptError(e.getMessage(), assign.line());
            }
        }
    }

    // --- Expressions --------------------------------------------------------------------

    private Object evaluate(Expr expression, Scope scope) {
        if (++steps > STEP_BUDGET) {
            throw new ScriptError("This script is taking too long", expression.line());
        }
        if (expression instanceof Ast.Literal literal) {
            return literal.value();
        }
        if (expression instanceof Ast.Name name) {
            return scope.get(name.name(), name.line());
        }
        if (expression instanceof Ast.ListLiteral list) {
            List<Object> items = new ArrayList<>();
            for (Expr item : list.items()) {
                items.add(evaluate(item, scope));
            }
            return items;
        }
        if (expression instanceof Ast.Unary unary) {
            Object value = evaluate(unary.value(), scope);
            if (unary.operator().equals("not")) {
                return !truthy(value);
            }
            return -number(value, unary.line());
        }
        if (expression instanceof Ast.Logical logical) {
            Object left = evaluate(logical.left(), scope);
            if (logical.operator().equals("and")) {
                return truthy(left) ? evaluate(logical.right(), scope) : left;
            }
            return truthy(left) ? left : evaluate(logical.right(), scope);
        }
        if (expression instanceof Ast.Binary binary) {
            return binary(binary.operator(), evaluate(binary.left(), scope),
                    evaluate(binary.right(), scope), binary.line());
        }
        if (expression instanceof Ast.Index index) {
            Object target = evaluate(index.target(), scope);
            Object key = evaluate(index.index(), scope);
            if (target instanceof List<?> list) {
                return list.get(listIndex(list, key, index.line()));
            }
            if (target instanceof String text) {
                int at = listIndex(text.length(), key, index.line());
                return String.valueOf(text.charAt(at));
            }
            throw new ScriptError("Only lists and text can be indexed with []", index.line());
        }
        if (expression instanceof Ast.Attribute attribute) {
            Object target = evaluate(attribute.target(), scope);
            if (target instanceof ScriptObject object) {
                Object value = object.property(attribute.name());
                if (value == Builtins.MISSING) {
                    throw new ScriptError("This has nothing called '" + attribute.name() + "'",
                            attribute.line());
                }
                return value;
            }
            throw new ScriptError("'" + attribute.name() + "' does not belong to this", attribute.line());
        }
        if (expression instanceof Ast.Call call) {
            Object callee = evaluate(call.callee(), scope);
            List<Object> arguments = new ArrayList<>();
            for (Expr argument : call.arguments()) {
                arguments.add(evaluate(argument, scope));
            }
            if (!(callee instanceof Callable callable)) {
                throw new ScriptError(describe(call.callee()) + " is not something you can call",
                        call.line());
            }
            try {
                return callable.call(arguments);
            } catch (ScriptError error) {
                // Give the error the line of the call when it came from inside the engine.
                throw error.line() == 0 ? new ScriptError(error.getMessage(), call.line()) : error;
            }
        }
        throw new ScriptError("I cannot work out this expression", expression.line());
    }

    private static String describe(Expr expression) {
        if (expression instanceof Ast.Name name) {
            return "'" + name.name() + "'";
        }
        if (expression instanceof Ast.Attribute attribute) {
            return "'" + attribute.name() + "'";
        }
        return "this";
    }

    private Object binary(String operator, Object left, Object right, int line) {
        switch (operator) {
            case "==" -> {
                return equals(left, right);
            }
            case "!=" -> {
                return !equals(left, right);
            }
            case "in" -> {
                if (right instanceof List<?> list) {
                    return list.stream().anyMatch(item -> equals(item, left));
                }
                if (right instanceof String text) {
                    return text.contains(Builtins.text(left));
                }
                throw new ScriptError("'in' only works with a list or some text", line);
            }
            default -> {
                // Falls through to the arithmetic and comparisons below.
            }
        }
        if (operator.equals("+") && (left instanceof String || right instanceof String)) {
            return Builtins.text(left) + Builtins.text(right);
        }
        if (operator.equals("+") && left instanceof List<?> first && right instanceof List<?> second) {
            List<Object> joined = new ArrayList<>(first);
            joined.addAll(second);
            return joined;
        }
        double a = number(left, line);
        double b = number(right, line);
        return switch (operator) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> divide(a, b, line);
            case "%" -> b == 0 ? divide(a, b, line) : a % b;
            case "**" -> Math.pow(a, b);
            case "<" -> a < b;
            case ">" -> a > b;
            case "<=" -> a <= b;
            case ">=" -> a >= b;
            default -> throw new ScriptError("I don't know the operator '" + operator + "'", line);
        };
    }

    private static double divide(double a, double b, int line) {
        if (b == 0) {
            throw new ScriptError("Dividing by zero", line);
        }
        return a / b;
    }

    private static boolean equals(Object left, Object right) {
        if (left instanceof Double a && right instanceof Double b) {
            return a.doubleValue() == b.doubleValue();
        }
        return left == null ? right == null : left.equals(right);
    }

    /** What counts as true: anything but False, None, 0, empty text and an empty list. */
    public static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Double number) {
            return number != 0;
        }
        if (value instanceof String text) {
            return !text.isEmpty();
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        return true;
    }

    static double number(Object value, int line) {
        if (value instanceof Double number) {
            return number;
        }
        if (value instanceof Boolean flag) {
            return flag ? 1 : 0;
        }
        throw new ScriptError("I expected a number but got " + Builtins.describe(value), line);
    }

    private static List<Object> iterable(Object value, int line) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (value instanceof String text) {
            List<Object> characters = new ArrayList<>();
            for (char c : text.toCharArray()) {
                characters.add(String.valueOf(c));
            }
            return characters;
        }
        throw new ScriptError("'for' needs a list to go through, not " + Builtins.describe(value), line);
    }

    private static int listIndex(List<?> list, Object key, int line) {
        return listIndex(list.size(), key, line);
    }

    /** Python-style: -1 is the last item, and going past the end is an error, not a crash. */
    private static int listIndex(int size, Object key, int line) {
        int at = (int) number(key, line);
        if (at < 0) {
            at += size;
        }
        if (at < 0 || at >= size) {
            throw new ScriptError("There is no item " + Builtins.text(key) + " (there are "
                    + size + ")", line);
        }
        return at;
    }
}

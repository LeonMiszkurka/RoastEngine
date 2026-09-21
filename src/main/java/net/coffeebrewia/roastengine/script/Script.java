package net.coffeebrewia.roastengine.script;

import java.util.List;

/**
 * RoastScript, from the outside: the one class the rest of the engine and the Creator use.
 *
 * <pre>
 * Interpreter interpreter = new Interpreter();
 * interpreter.define("notice", Script.function("notice", 1, args -&gt; { ...; return null; }));
 * interpreter.run(Script.parse(source));           // defines the script's functions
 * interpreter.callFunction("on_update", List.of(0.016));
 * </pre>
 *
 * The language itself - the lexer, parser and interpreter - stays inside this package.
 */
public final class Script {

    /** What a {@link Interpreter.ScriptObject} returns for a property it does not have. */
    public static final Object MISSING = Builtins.MISSING;

    private Script() {
    }

    /**
     * Reads a script.
     *
     * @throws ScriptError when it cannot be read, with the line and a plain explanation
     */
    public static List<Ast.Stmt> parse(String source) {
        return Parser.parse(source);
    }

    /** Checks a script without running it, for the Creator's editor. Returns null when it is fine. */
    public static ScriptError check(String source) {
        try {
            Parser.parse(source);
            return null;
        } catch (ScriptError error) {
            return error;
        }
    }

    /** A function the engine provides, taking an exact number of values. */
    public static Interpreter.Callable function(String name, int arity, Interpreter.Callable body) {
        return Builtins.fn(name, arity, body);
    }

    /** A function the engine provides, taking between {@code least} and {@code most} values. */
    public static Interpreter.Callable function(String name, int least, int most,
                                                Interpreter.Callable body) {
        return Builtins.variadic(name, least, most, body);
    }

    /** A value as a script would show it: {@code 3} rather than {@code 3.0}, {@code None} for null. */
    public static String text(Object value) {
        return Builtins.text(value);
    }

    /** A value as a number, or a {@link ScriptError} saying what it got instead. */
    public static double number(Object value) {
        return Interpreter.number(value, 0);
    }

    /** True for everything except False, None, 0, empty text and an empty list. */
    public static boolean truthy(Object value) {
        return Interpreter.truthy(value);
    }
}

package net.coffeebrewia.roastengine.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The functions every script has without asking: numbers, text, lists and randomness.
 *
 * <p>Deliberately small, and deliberately free of anything that could reach outside the game:
 * no files, no network, no clock beyond the game's own, no way into Java. A script can only
 * touch the world through what the engine hands it.
 */
final class Builtins {

    /** What {@link Interpreter.ScriptObject} returns for a property it does not have. */
    static final Object MISSING = new Object();

    private static final Random RANDOM = new Random();

    private Builtins() {
    }

    static void install(Interpreter.Scope scope) {
        scope.define("print", fn("print", 1, args -> {
            System.out.println("[Script] " + text(args.get(0)));
            return null;
        }));
        scope.define("len", fn("len", 1, args -> {
            Object value = args.get(0);
            if (value instanceof List<?> list) {
                return (double) list.size();
            }
            if (value instanceof String string) {
                return (double) string.length();
            }
            throw new ScriptError("len() works on a list or some text, not " + describe(value), 0);
        }));
        scope.define("range", variadic("range", 1, 3, args -> {
            double start = args.size() > 1 ? Interpreter.number(args.get(0), 0) : 0;
            double end = Interpreter.number(args.get(args.size() > 1 ? 1 : 0), 0);
            double step = args.size() > 2 ? Interpreter.number(args.get(2), 0) : 1;
            if (step == 0) {
                throw new ScriptError("range() cannot step by zero", 0);
            }
            List<Object> values = new ArrayList<>();
            for (double value = start; step > 0 ? value < end : value > end; value += step) {
                values.add(value);
                if (values.size() > 100_000) {
                    throw new ScriptError("range() is too big", 0);
                }
            }
            return values;
        }));
        scope.define("append", fn("append", 2, args -> {
            if (!(args.get(0) instanceof List<?> list)) {
                throw new ScriptError("append() needs a list first", 0);
            }
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) list;
            items.add(args.get(1));
            return null;
        }));
        scope.define("remove_at", fn("remove_at", 2, args -> {
            if (!(args.get(0) instanceof List<?> list)) {
                throw new ScriptError("remove_at() needs a list first", 0);
            }
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) list;
            int at = (int) Interpreter.number(args.get(1), 0);
            if (at < 0 || at >= items.size()) {
                throw new ScriptError("There is no item " + at + " to remove", 0);
            }
            return items.remove(at);
        }));

        // Numbers.
        scope.define("abs", number1("abs", Math::abs));
        scope.define("round", number1("round", value -> (double) Math.round(value)));
        scope.define("floor", number1("floor", Math::floor));
        scope.define("ceil", number1("ceil", Math::ceil));
        scope.define("sqrt", number1("sqrt", value -> {
            if (value < 0) {
                throw new ScriptError("sqrt() needs a number that is not negative", 0);
            }
            return Math.sqrt(value);
        }));
        scope.define("sin", number1("sin", Math::sin));
        scope.define("cos", number1("cos", Math::cos));
        scope.define("radians", number1("radians", Math::toRadians));
        scope.define("degrees", number1("degrees", Math::toDegrees));
        scope.define("min", fn("min", 2, args -> Math.min(Interpreter.number(args.get(0), 0),
                Interpreter.number(args.get(1), 0))));
        scope.define("max", fn("max", 2, args -> Math.max(Interpreter.number(args.get(0), 0),
                Interpreter.number(args.get(1), 0))));
        scope.define("clamp", fn("clamp", 3, args -> Math.max(Interpreter.number(args.get(1), 0),
                Math.min(Interpreter.number(args.get(2), 0), Interpreter.number(args.get(0), 0)))));
        scope.define("number", fn("number", 1, args -> {
            Object value = args.get(0);
            if (value instanceof Double d) {
                return d;
            }
            try {
                return Double.parseDouble(text(value).trim());
            } catch (NumberFormatException e) {
                throw new ScriptError("'" + text(value) + "' is not a number", 0);
            }
        }));
        scope.define("text", fn("text", 1, args -> text(args.get(0))));
        scope.define("random", variadic("random", 0, 2, args -> {
            if (args.isEmpty()) {
                return RANDOM.nextDouble();
            }
            double low = args.size() > 1 ? Interpreter.number(args.get(0), 0) : 0;
            double high = Interpreter.number(args.get(args.size() > 1 ? 1 : 0), 0);
            return low + RANDOM.nextDouble() * (high - low);
        }));
        scope.define("pick", fn("pick", 1, args -> {
            if (!(args.get(0) instanceof List<?> list) || list.isEmpty()) {
                throw new ScriptError("pick() needs a list with something in it", 0);
            }
            return list.get(RANDOM.nextInt(list.size()));
        }));
    }

    // --- Making functions ---------------------------------------------------------------

    /** A function with an exact number of values. */
    static Interpreter.Callable fn(String name, int arity, Interpreter.Callable body) {
        return variadic(name, arity, arity, body);
    }

    /** A function that takes between {@code least} and {@code most} values. */
    static Interpreter.Callable variadic(String name, int least, int most, Interpreter.Callable body) {
        return new Interpreter.Callable() {
            @Override
            public Object call(List<Object> arguments) {
                if (arguments.size() < least || arguments.size() > most) {
                    String wanted = least == most ? String.valueOf(least) : least + " to " + most;
                    throw new ScriptError(name + "() wants " + wanted + " value(s) but got "
                            + arguments.size(), 0);
                }
                return body.call(arguments);
            }

            @Override
            public String toString() {
                return "<function " + name + ">";
            }
        };
    }

    private static Interpreter.Callable number1(String name, java.util.function.DoubleUnaryOperator body) {
        return fn(name, 1, args -> body.applyAsDouble(Interpreter.number(args.get(0), 0)));
    }

    // --- Showing values -----------------------------------------------------------------

    /** A value as a script would print it: whole numbers without a trailing ".0". */
    static String text(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Boolean flag) {
            return flag ? "True" : "False";
        }
        if (value instanceof Double number) {
            if (number == Math.rint(number) && !number.isInfinite() && Math.abs(number) < 1e15) {
                return String.valueOf((long) (double) number);
            }
            return String.valueOf((double) number);
        }
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            list.forEach(item -> parts.add(text(item)));
            return "[" + String.join(", ", parts) + "]";
        }
        return String.valueOf(value);
    }

    /** What kind of thing a value is, for error messages. */
    static String describe(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Double) {
            return "a number";
        }
        if (value instanceof String) {
            return "some text";
        }
        if (value instanceof Boolean) {
            return "True or False";
        }
        if (value instanceof List<?>) {
            return "a list";
        }
        if (value instanceof Interpreter.Callable) {
            return "a function";
        }
        return "something else";
    }
}

package net.coffeebrewia.roastengine.script;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RoastScript: the language modders write their scripts in. */
class ScriptTest {

    /** Runs a script and returns whatever it printed, one line per print(). */
    private static List<String> run(String source) {
        List<String> printed = new ArrayList<>();
        Interpreter interpreter = new Interpreter();
        interpreter.define("out", Builtins.fn("out", 1, args -> {
            printed.add(Builtins.text(args.get(0)));
            return null;
        }));
        interpreter.run(Parser.parse(source));
        return printed;
    }

    private static String value(String expression) {
        return run("out(" + expression + ")").get(0);
    }

    @Test
    void mathsAndTextWorkLikePython() {
        assertEquals("7", value("1 + 2 * 3"));
        assertEquals("9", value("(1 + 2) * 3"));
        assertEquals("8", value("2 ** 3"));
        assertEquals("1", value("7 % 3"));
        assertEquals("2.5", value("5 / 2"));
        assertEquals("-3", value("-(1 + 2)"));
        assertEquals("hi there", value("'hi' + ' ' + 'there'"));
        assertEquals("score: 5", value("'score: ' + 5"));
        assertEquals("True", value("2 < 3 and not (3 == 4)"));
        assertEquals("False", value("2 > 3 or 1 == 2"));
    }

    @Test
    void variablesIfsAndLoopsWork() {
        assertEquals(List.of("0", "1", "2"), run("""
                for i in range(3):
                    out(i)
                """));
        assertEquals(List.of("3", "2", "1"), run("""
                count = 3
                while count > 0:
                    out(count)
                    count -= 1
                """));
        assertEquals(List.of("big"), run("""
                size = 10
                if size < 5:
                    out('small')
                elif size < 9:
                    out('medium')
                else:
                    out('big')
                """));
        // break and continue
        assertEquals(List.of("1", "3"), run("""
                for i in range(5):
                    if i == 0 or i == 2:
                        continue
                    if i == 4:
                        break
                    out(i)
                """));
    }

    @Test
    void functionsListsAndClosuresWork() {
        assertEquals(List.of("6"), run("""
                def add(a, b):
                    return a + b

                out(add(1, 5))
                """));
        assertEquals(List.of("[1, 2, 7]", "3", "7"), run("""
                items = [1, 2, 3]
                items[2] = 7
                out(items)
                out(len(items))
                out(items[-1])
                """));
        assertEquals(List.of("10"), run("""
                def counter():
                    total = 0
                    def add(n):
                        total = total + n
                        return total
                    add(4)
                    return add(6)

                out(counter())
                """));
    }

    @Test
    void builtinsCoverTheBasics() {
        assertEquals("3", value("abs(-3)"));
        assertEquals("2", value("round(1.6)"));
        assertEquals("5", value("sqrt(25)"));
        assertEquals("4", value("clamp(9, 1, 4)"));
        assertEquals("1", value("min(1, 2)"));
        assertEquals("[0, 2, 4]", value("range(0, 6, 2)"));
        assertEquals("True", value("2 in [1, 2, 3]"));
        assertEquals("True", value("'ell' in 'hello'"));
        assertEquals("12", value("number('12')"));
        assertEquals("no", value("text('no')"));
        assertTrue(Double.parseDouble(value("random(1, 2)")) >= 1);
    }

    @Test
    void mistakesAreExplainedWithTheirLine() {
        ScriptError missing = assertThrows(ScriptError.class, () -> run("""
                x = 1
                out(y)
                """));
        assertEquals(2, missing.line());
        assertTrue(missing.getMessage().contains("nothing called 'y'"), missing.getMessage());

        ScriptError indentation = assertThrows(ScriptError.class, () -> run("""
                if True:
                out('here')
                """));
        assertTrue(indentation.getMessage().contains("indent"), indentation.getMessage());

        ScriptError zero = assertThrows(ScriptError.class, () -> run("out(1 / 0)"));
        assertTrue(zero.getMessage().contains("zero"));

        ScriptError text = assertThrows(ScriptError.class, () -> run("out('a' - 1)"));
        assertTrue(text.getMessage().contains("expected a number"), text.getMessage());

        ScriptError unclosed = assertThrows(ScriptError.class, () -> run("out('hello)"));
        assertTrue(unclosed.getMessage().contains("closing"), unclosed.getMessage());

        ScriptError callNothing = assertThrows(ScriptError.class, () -> run("""
                speed = 4
                speed(2)
                """));
        assertTrue(callNothing.getMessage().contains("not something you can call"));
    }

    @Test
    void aScriptCannotRunForever() {
        ScriptError forever = assertThrows(ScriptError.class, () -> run("""
                while True:
                    x = 1
                """));
        assertTrue(forever.getMessage().contains("too long"), forever.getMessage());

        ScriptError deep = assertThrows(ScriptError.class, () -> run("""
                def again(n):
                    return again(n + 1)

                again(0)
                """));
        assertTrue(deep.getMessage().contains("calls itself too many times"), deep.getMessage());
    }

    @Test
    void thereIsNoWayOutOfTheSandbox() {
        // No imports, no file or system access, no Java underneath.
        for (String attempt : List.of("import os", "open('secret.txt')", "eval('1')",
                "exec('1')", "__import__('os')", "system('ls')")) {
            assertThrows(ScriptError.class, () -> run(attempt), attempt + " should not work");
        }
    }

    @Test
    void theEngineCanHandInObjectsAndFunctions() {
        Interpreter interpreter = new Interpreter();
        List<String> moved = new ArrayList<>();
        interpreter.define("door", (Interpreter.ScriptObject) name -> switch (name) {
            case "name" -> "Front door";
            case "open" -> Builtins.fn("open", 0, args -> {
                moved.add("opened");
                return null;
            });
            default -> Builtins.MISSING;
        });
        interpreter.run(Parser.parse("""
                def on_interact():
                    door.open()
                    return door.name
                """));
        assertTrue(interpreter.hasFunction("on_interact"));
        assertEquals("Front door", interpreter.callFunction("on_interact", List.of()));
        assertEquals(List.of("opened"), moved);

        ScriptError unknown = assertThrows(ScriptError.class, () ->
                interpreter.run(Parser.parse("door.explode()")));
        assertTrue(unknown.getMessage().contains("nothing called 'explode'"), unknown.getMessage());
    }

    @Test
    void readsAListSpreadOverSeveralLines() {
        assertNull(Script.check("""
                def on_start():
                    words = [
                        "one",
                        "two"
                    ]
                    print(len(words))
                """));
    }

    @Test
    void allowsATrailingCommaInListsAndCalls() {
        assertNull(Script.check("""
                def on_start():
                    words = [
                        "one",
                        "two",
                    ]
                    print(
                        len(words),
                    )
                """));
    }
}

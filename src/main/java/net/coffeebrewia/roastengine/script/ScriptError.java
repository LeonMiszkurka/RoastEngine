package net.coffeebrewia.roastengine.script;

/**
 * Something wrong with a script, said plainly and with the line number, because the person
 * reading it is the modder who wrote the script - often their first ever program.
 */
public final class ScriptError extends RuntimeException {

    private final int line;

    public ScriptError(String message, int line) {
        super(message);
        this.line = line;
    }

    /** The line the script went wrong on, or 0 when it isn't known. */
    public int line() {
        return line;
    }

    /** The message as the Creator and the log show it: "line 12: ...". */
    public String describe() {
        return (line > 0 ? "line " + line + ": " : "") + getMessage();
    }
}

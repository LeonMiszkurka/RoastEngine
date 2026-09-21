package net.coffeebrewia.roastengine.script;

/** One piece of a script: a word, a number, a piece of text, an operator or a block marker. */
record Token(Kind kind, String text, int line) {

    enum Kind {NAME, KEYWORD, NUMBER, STRING, OPERATOR, NEWLINE, INDENT, DEDENT, END}

    boolean is(Kind wanted, String wantedText) {
        return kind == wanted && text.equals(wantedText);
    }

    @Override
    public String toString() {
        return kind + (text.isEmpty() ? "" : "(" + text + ")");
    }
}

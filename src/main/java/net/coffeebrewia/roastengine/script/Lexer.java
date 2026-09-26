package net.coffeebrewia.roastengine.script;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * Turns RoastScript source into tokens.
 *
 * <p>RoastScript is written like Python, so blocks are indentation: this is where a line's
 * indentation becomes {@link Token.Kind#INDENT} and {@link Token.Kind#DEDENT} tokens, and where
 * blank lines, comments and line continuations inside brackets disappear.
 */
final class Lexer {

    /** Words that are part of the language rather than names. */
    static final Set<String> KEYWORDS = Set.of(
            "if", "elif", "else", "while", "for", "in", "def", "return", "break", "continue",
            "pass", "and", "or", "not", "True", "False", "None");

    private final String source;
    private final List<Token> tokens = new ArrayList<>();
    private final Deque<Integer> indents = new ArrayDeque<>();
    private int index;
    private int line = 1;
    /** How many brackets are open; inside them, newlines are just spacing. */
    private int depth;

    Lexer(String source) {
        // A tab is four spaces, so mixing them still lines up the way it looks.
        this.source = source.replace("\t", "    ").replace("\r\n", "\n").replace("\r", "\n");
        indents.push(0);
    }

    List<Token> tokenize() {
        boolean atLineStart = true;
        while (index < source.length()) {
            if (atLineStart && depth == 0) {
                if (!handleIndentation()) {
                    // A blank or comment-only line: the next line still starts a line, so the
                    // indentation after it is read rather than skipped.
                    continue;
                }
                atLineStart = false;
            }
            char c = source.charAt(index);
            if (c == '\n') {
                index++;
                if (depth == 0) {
                    add(Token.Kind.NEWLINE, "\\n");
                }
                line++;
                // Inside brackets the next line is a continuation, so its indentation means
                // nothing - and treating it as a line start would close the block it is in.
                atLineStart = depth == 0;
                continue;
            }
            if (c == ' ') {
                index++;
                continue;
            }
            if (c == '#') {
                while (index < source.length() && source.charAt(index) != '\n') {
                    index++;
                }
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && index + 1 < source.length()
                    && Character.isDigit(source.charAt(index + 1)))) {
                readNumber();
            } else if (Character.isLetter(c) || c == '_') {
                readName();
            } else if (c == '"' || c == '\'') {
                readString(c);
            } else {
                readOperator();
            }
        }
        if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).kind() != Token.Kind.NEWLINE) {
            add(Token.Kind.NEWLINE, "\\n");
        }
        while (indents.size() > 1) {
            indents.pop();
            add(Token.Kind.DEDENT, "");
        }
        add(Token.Kind.END, "");
        return tokens;
    }

    /** @return false when the line holds nothing but spaces or a comment */
    private boolean handleIndentation() {
        int start = index;
        int spaces = 0;
        while (index < source.length() && source.charAt(index) == ' ') {
            spaces++;
            index++;
        }
        if (index >= source.length() || source.charAt(index) == '\n' || source.charAt(index) == '#') {
            // Blank or comment-only: skip the whole line without touching the indentation.
            while (index < source.length() && source.charAt(index) != '\n') {
                index++;
            }
            if (index < source.length()) {
                index++;
                line++;
            }
            return false;
        }
        if (spaces > indents.peek()) {
            indents.push(spaces);
            add(Token.Kind.INDENT, "");
        } else {
            while (spaces < indents.peek()) {
                indents.pop();
                add(Token.Kind.DEDENT, "");
            }
            if (spaces != indents.peek()) {
                throw new ScriptError("This line's indentation does not match the lines above it", line);
            }
        }
        index = Math.max(index, start + spaces);
        return true;
    }

    private void readNumber() {
        int start = index;
        boolean dot = false;
        while (index < source.length()) {
            char c = source.charAt(index);
            if (Character.isDigit(c)) {
                index++;
            } else if (c == '.' && !dot) {
                dot = true;
                index++;
            } else {
                break;
            }
        }
        add(Token.Kind.NUMBER, source.substring(start, index));
    }

    private void readName() {
        int start = index;
        while (index < source.length()) {
            char c = source.charAt(index);
            if (Character.isLetterOrDigit(c) || c == '_') {
                index++;
            } else {
                break;
            }
        }
        String text = source.substring(start, index);
        add(KEYWORDS.contains(text) ? Token.Kind.KEYWORD : Token.Kind.NAME, text);
    }

    private void readString(char quote) {
        index++; // the opening quote
        StringBuilder text = new StringBuilder();
        while (true) {
            if (index >= source.length() || source.charAt(index) == '\n') {
                throw new ScriptError("This text is missing its closing " + quote, line);
            }
            char c = source.charAt(index++);
            if (c == quote) {
                break;
            }
            if (c == '\\' && index < source.length()) {
                char escaped = source.charAt(index++);
                text.append(switch (escaped) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case '\\' -> '\\';
                    case '"' -> '"';
                    case '\'' -> '\'';
                    default -> escaped;
                });
            } else {
                text.append(c);
            }
        }
        add(Token.Kind.STRING, text.toString());
    }

    private static final List<String> OPERATORS = List.of(
            "**", "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=",
            "+", "-", "*", "/", "%", "=", "<", ">", "(", ")", "[", "]", ",", ":", ".");

    private void readOperator() {
        for (String operator : OPERATORS) {
            if (source.startsWith(operator, index)) {
                index += operator.length();
                if (operator.equals("(") || operator.equals("[")) {
                    depth++;
                } else if (operator.equals(")") || operator.equals("]")) {
                    depth = Math.max(0, depth - 1);
                }
                add(Token.Kind.OPERATOR, operator);
                return;
            }
        }
        throw new ScriptError("I don't understand the character '" + source.charAt(index) + "'", line);
    }

    private void add(Token.Kind kind, String text) {
        tokens.add(new Token(kind, text, line));
    }
}

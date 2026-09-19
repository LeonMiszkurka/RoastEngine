package net.coffeebrewia.roastengine.ui;

/** Retained state for a single-line text input. Drawn/updated via {@link Ui#textField}. */
public final class TextField {

    private final String label;
    private final boolean secret;
    private final int maxLength;
    private final StringBuilder text = new StringBuilder();

    public TextField(String label, boolean secret, int maxLength) {
        this.label = label;
        this.secret = secret;
        this.maxLength = maxLength;
    }

    public String label() {
        return label;
    }

    public boolean isSecret() {
        return secret;
    }

    public String text() {
        return text.toString();
    }

    public void setText(String value) {
        text.setLength(0);
        append(value);
    }

    void append(String value) {
        if (value == null) {
            return;
        }
        for (int i = 0; i < value.length() && text.length() < maxLength; i++) {
            char ch = value.charAt(i);
            if (ch >= 32 && ch < 127) {
                text.append(ch);
            }
        }
    }

    void backspace() {
        if (!text.isEmpty()) {
            text.setLength(text.length() - 1);
        }
    }

    /** Text as shown on screen: secrets are masked except for the last 4 characters. */
    String displayText() {
        if (!secret || text.length() <= 4) {
            return secret ? "*".repeat(text.length()) : text.toString();
        }
        return "*".repeat(Math.min(24, text.length() - 4)) + text.substring(text.length() - 4);
    }
}

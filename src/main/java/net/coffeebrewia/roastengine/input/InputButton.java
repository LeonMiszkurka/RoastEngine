package net.coffeebrewia.roastengine.input;

/**
 * A pressable control the game reads instead of a specific key or pad button.
 *
 * <p>{@code key} is the name an InputEdit API mod uses in its {@code input/scheme.json}.
 */
public enum InputButton {

    JUMP("jump"),
    SPRINT("sprint"),
    INTERACT("interact"),
    /** Opens the pause menu, and backs out of it again. */
    PAUSE("pause");

    private final String key;

    InputButton(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static InputButton byKey(String key) {
        for (InputButton button : values()) {
            if (button.key.equalsIgnoreCase(key)) {
                return button;
            }
        }
        return null;
    }
}

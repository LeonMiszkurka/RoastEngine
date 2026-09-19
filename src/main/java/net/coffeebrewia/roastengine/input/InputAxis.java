package net.coffeebrewia.roastengine.input;

/**
 * A continuous control the game reads instead of a key or a stick.
 *
 * <p>Movement axes run -1..1. Look axes are a per-frame delta in the same units as mouse
 * movement (window points), so a control scheme and the mouse can drive the camera together
 * without the game caring which one moved it.
 *
 * <p>{@code key} is the name an InputEdit API mod uses in its {@code input/scheme.json}.
 */
public enum InputAxis {

    /** Strafe: -1 left, +1 right. */
    MOVE_X("moveX"),
    /** Walk: -1 back, +1 forward. */
    MOVE_Y("moveY"),
    /** Turn, in window points this frame. */
    LOOK_X("lookX"),
    /** Pitch, in window points this frame. */
    LOOK_Y("lookY");

    private final String key;

    InputAxis(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** True for the axes measured per frame rather than held at a position. */
    public boolean isLook() {
        return this == LOOK_X || this == LOOK_Y;
    }

    public static InputAxis byKey(String key) {
        for (InputAxis axis : values()) {
            if (axis.key.equalsIgnoreCase(key)) {
                return axis;
            }
        }
        return null;
    }
}

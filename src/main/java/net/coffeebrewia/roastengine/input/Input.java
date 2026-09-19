package net.coffeebrewia.roastengine.input;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Polled input state fed by GLFW callbacks.
 *
 * <p>Callbacks fire during {@code glfwPollEvents()}; game code then queries the aggregated
 * state during update. {@link #endFrame()} clears per-frame data (presses, typed chars, deltas).
 */
public final class Input {

    private static final int KEY_COUNT = GLFW_KEY_LAST + 1;
    private static final int BUTTON_COUNT = GLFW_MOUSE_BUTTON_LAST + 1;

    private final boolean[] keysDown = new boolean[KEY_COUNT];
    /** Presses this frame, including OS key-repeat events (useful for text editing). */
    private final boolean[] keysPressed = new boolean[KEY_COUNT];
    private final boolean[] buttonsDown = new boolean[BUTTON_COUNT];
    private final boolean[] buttonsPressed = new boolean[BUTTON_COUNT];
    private final StringBuilder typedChars = new StringBuilder();

    private long window;
    private double mouseX;
    private double mouseY;
    private double mouseDeltaX;
    private double mouseDeltaY;
    private double scrollY;
    private boolean cursorCaptured;
    private boolean skipNextMouseDelta = true;

    /** Registers all GLFW input callbacks on the given window. */
    public void attach(long windowHandle) {
        this.window = windowHandle;

        glfwSetKeyCallback(windowHandle, (win, key, scancode, action, mods) -> {
            if (key < 0 || key >= KEY_COUNT) {
                return;
            }
            if (action == GLFW_PRESS) {
                keysDown[key] = true;
                keysPressed[key] = true;
            } else if (action == GLFW_REPEAT) {
                keysPressed[key] = true;
            } else if (action == GLFW_RELEASE) {
                keysDown[key] = false;
            }
        });

        glfwSetCharCallback(windowHandle, (win, codepoint) -> typedChars.appendCodePoint(codepoint));

        glfwSetMouseButtonCallback(windowHandle, (win, button, action, mods) -> {
            if (button < 0 || button >= BUTTON_COUNT) {
                return;
            }
            if (action == GLFW_PRESS) {
                buttonsDown[button] = true;
                buttonsPressed[button] = true;
            } else if (action == GLFW_RELEASE) {
                buttonsDown[button] = false;
            }
        });

        glfwSetCursorPosCallback(windowHandle, (win, x, y) -> {
            if (skipNextMouseDelta) {
                // Avoid a huge camera jump right after the cursor mode changes.
                skipNextMouseDelta = false;
            } else {
                mouseDeltaX += x - mouseX;
                mouseDeltaY += y - mouseY;
            }
            mouseX = x;
            mouseY = y;
        });

        glfwSetScrollCallback(windowHandle, (win, dx, dy) -> scrollY += dy);

        glfwSetWindowFocusCallback(windowHandle, (win, focused) -> {
            if (!focused) {
                java.util.Arrays.fill(keysDown, false);
                java.util.Arrays.fill(buttonsDown, false);
            }
        });
    }

    public void detach(long windowHandle) {
        // Callbacks are freed together with the window (see Window#destroy).
        this.window = 0L;
    }

    /** Clears per-frame state. Called by the engine after rendering. */
    public void endFrame() {
        java.util.Arrays.fill(keysPressed, false);
        java.util.Arrays.fill(buttonsPressed, false);
        typedChars.setLength(0);
        mouseDeltaX = 0;
        mouseDeltaY = 0;
        scrollY = 0;
    }

    // ---------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------

    public boolean isKeyDown(int key) {
        return key >= 0 && key < KEY_COUNT && keysDown[key];
    }

    public boolean wasKeyPressed(int key) {
        return key >= 0 && key < KEY_COUNT && keysPressed[key];
    }

    public boolean isMouseDown(int button) {
        return button >= 0 && button < BUTTON_COUNT && buttonsDown[button];
    }

    public boolean wasMousePressed(int button) {
        return button >= 0 && button < BUTTON_COUNT && buttonsPressed[button];
    }

    /** Printable characters typed this frame. */
    public String typedText() {
        return typedChars.toString();
    }

    public boolean isShortcutDown() {
        // Cmd on macOS, Ctrl everywhere else (either is accepted for simplicity).
        return isKeyDown(GLFW_KEY_LEFT_CONTROL) || isKeyDown(GLFW_KEY_RIGHT_CONTROL)
                || isKeyDown(GLFW_KEY_LEFT_SUPER) || isKeyDown(GLFW_KEY_RIGHT_SUPER);
    }

    public String clipboard() {
        String text = glfwGetClipboardString(window);
        return text == null ? "" : text;
    }

    public float mouseX() {
        return (float) mouseX;
    }

    public float mouseY() {
        return (float) mouseY;
    }

    public float mouseDeltaX() {
        return (float) mouseDeltaX;
    }

    public float mouseDeltaY() {
        return (float) mouseDeltaY;
    }

    public float scrollY() {
        return (float) scrollY;
    }

    // ---------------------------------------------------------------------
    // Cursor capture (for first-person camera control)
    // ---------------------------------------------------------------------

    public void setCursorCaptured(boolean captured) {
        if (cursorCaptured == captured) {
            return;
        }
        cursorCaptured = captured;
        glfwSetInputMode(window, GLFW_CURSOR, captured ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        if (captured && glfwRawMouseMotionSupported()) {
            glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE);
        }
        skipNextMouseDelta = true;
        mouseDeltaX = 0;
        mouseDeltaY = 0;
    }

    public boolean isCursorCaptured() {
        return cursorCaptured;
    }
}

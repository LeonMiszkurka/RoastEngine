package net.coffeebrewia.roastengine.ui;

import net.coffeebrewia.roastengine.input.Input;
import net.coffeebrewia.roastengine.render.Color;
import net.coffeebrewia.roastengine.render.Renderer2D;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Tiny immediate-mode UI: widgets are declared every frame inside
 * {@link #begin()} / {@link #end()} and return their interaction result directly.
 */
public final class Ui {

    private static final float LABEL_SCALE = 1.5f;

    private final Renderer2D r;
    private final Input input;

    private TextField focused;
    private Runnable clickSound = () -> { };
    /** The slider being dragged, identified by its label, or null. */
    private String draggingSlider;
    private boolean clickedEmptySpace;
    private float caretBlink;

    public Ui(Renderer2D renderer, Input input) {
        this.r = renderer;
        this.input = input;
    }

    /** Sound to play whenever a widget is clicked. */
    public void setClickSound(Runnable clickSound) {
        this.clickSound = clickSound == null ? () -> { } : clickSound;
    }

    private boolean clicked(boolean hit) {
        if (hit) {
            clickSound.run();
        }
        return hit;
    }

    public Renderer2D renderer() {
        return r;
    }

    private boolean inputBlocked;

    public void begin(float deltaSeconds) {
        inputBlocked = false;
        caretBlink = (caretBlink + deltaSeconds) % 1f;
        // Any click unfocuses the active field unless a text field claims it this frame.
        clickedEmptySpace = input.wasMousePressed(GLFW_MOUSE_BUTTON_LEFT);
        r.begin();
    }

    public void end() {
        if (clickedEmptySpace) {
            focused = null;
        }
        r.end();
    }

    public boolean hasFocusedField() {
        return focused != null;
    }

    public void clearFocus() {
        focused = null;
    }

    /**
     * While true, nothing counts as hovered, so no widget reacts. Set it while drawing the screen
     * behind a dialog, and clear it before drawing the dialog, so clicks can't reach through.
     */
    public void setInputBlocked(boolean blocked) {
        inputBlocked = blocked;
    }

    public boolean isHovered(float x, float y, float w, float h) {
        if (inputBlocked) {
            return false;
        }
        float mx = input.mouseX();
        float my = input.mouseY();
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ---------------------------------------------------------------------
    // Widgets
    // ---------------------------------------------------------------------

    public void panel(String title, float x, float y, float w, float h) {
        r.rect(x, y, w, h, Theme.PANEL);
        r.outline(x, y, w, h, 1f, Theme.PANEL_BORDER);
        if (title != null) {
            r.text(title, x + 16, y + 14, 2f, Theme.TEXT);
            r.rect(x + 16, y + 38, w - 32, 1, Theme.PANEL_BORDER);
        }
    }

    public void label(String text, float x, float y, float scale, Color color) {
        r.text(text, x, y, scale, color);
    }

    /** @return {@code true} on the frame the button is clicked. */
    public boolean button(String label, float x, float y, float w, float h, boolean enabled) {
        boolean hovered = enabled && isHovered(x, y, w, h);
        Color bg = !enabled ? Theme.ACCENT_DISABLED : hovered ? Theme.ACCENT_HOVER : Theme.ACCENT;
        r.rect(x, y, w, h, bg);
        r.textCentered(label, x, y, w, h, 2f, enabled ? Theme.TEXT_ON_ACCENT : Theme.TEXT_MUTED);
        return clicked(hovered && input.wasMousePressed(GLFW_MOUSE_BUTTON_LEFT));
    }

    public boolean button(String label, float x, float y, float w, float h) {
        return button(label, x, y, w, h, true);
    }

    /** Flat, panel-coloured button for toolbars and menu bars. */
    public boolean flatButton(String label, float x, float y, float w, float h, boolean active) {
        boolean hovered = isHovered(x, y, w, h);
        if (active || hovered) {
            r.rect(x, y, w, h, active ? Theme.ROW_HOVER : Theme.ROW);
        }
        r.textCentered(label, x, y, w, h, 1.75f, Theme.TEXT);
        return clicked(hovered && input.wasMousePressed(GLFW_MOUSE_BUTTON_LEFT));
    }

    /** A selectable row, e.g. in an asset or scene list. */
    public boolean selectable(String label, float x, float y, float w, float h, boolean selected) {
        boolean hovered = isHovered(x, y, w, h);
        if (selected || hovered) {
            r.rect(x, y, w, h, selected ? Theme.ACCENT.withAlpha(0.35f) : Theme.ROW_HOVER);
        }
        r.text(r.ellipsize(label, w - 16, 1.5f), x + 8, y + (h - Renderer2D.TEXT_HEIGHT * 1.5f) / 2f,
                1.5f, selected ? Theme.TEXT : Theme.TEXT_MUTED);
        return hovered && input.wasMousePressed(GLFW_MOUSE_BUTTON_LEFT);
    }

    /** @return {@code true} when clicked; the caller flips its own boolean. */
    public boolean checkbox(String label, float x, float y, boolean value) {
        float box = 16;
        float w = box + 8 + r.textWidth(label, 1.5f);
        boolean hovered = isHovered(x, y, w, box);
        r.rect(x, y, box, box, value ? Theme.ACCENT : Theme.INPUT);
        r.outline(x, y, box, box, 1f, hovered ? Theme.ACCENT_HOVER : Theme.PANEL_BORDER);
        if (value) {
            // Simple tick mark.
            r.rect(x + 4, y + 7, 3, 6, Theme.TEXT_ON_ACCENT);
            r.rect(x + 6, y + 9, 7, 3, Theme.TEXT_ON_ACCENT);
        }
        r.text(label, x + box + 8, y + (box - Renderer2D.TEXT_HEIGHT * 1.5f) / 2f, 1.5f, Theme.TEXT);
        return clicked(hovered && input.wasMousePressed(GLFW_MOUSE_BUTTON_LEFT));
    }

    /**
     * A horizontal slider. Click or drag anywhere on the track.
     *
     * @return the (possibly changed) value, always within [min, max]
     */
    public float slider(String label, float x, float y, float w, float value, float min, float max,
                        String valueText) {
        r.text(label, x, y, 1.5f, Theme.TEXT);
        float valueWidth = r.textWidth(valueText, 1.5f);
        r.text(valueText, x + w - valueWidth, y, 1.5f, Theme.TEXT_MUTED);

        float trackY = y + 22;
        float trackHeight = 6;
        boolean hovered = isHovered(x, trackY - 8, w, trackHeight + 16);
        if (hovered && input.wasMousePressed(GLFW_MOUSE_BUTTON_LEFT)) {
            draggingSlider = label;
            clickedEmptySpace = false;
        }
        if (label.equals(draggingSlider)) {
            if (input.isMouseDown(GLFW_MOUSE_BUTTON_LEFT)) {
                float fraction = Math.max(0f, Math.min(1f, (input.mouseX() - x) / w));
                value = min + fraction * (max - min);
            } else {
                draggingSlider = null;
                clickSound.run();
            }
        }
        value = Math.max(min, Math.min(max, value));

        float fraction = (value - min) / (max - min);
        r.rect(x, trackY, w, trackHeight, Theme.INPUT);
        r.rect(x, trackY, w * fraction, trackHeight, Theme.ACCENT);
        float knob = 16;
        r.rect(x + w * fraction - knob / 2f, trackY + trackHeight / 2f - knob / 2f, knob, knob,
                hovered || label.equals(draggingSlider) ? Theme.ACCENT_HOVER : Theme.TEXT);
        return value;
    }

    /**
     * An on/off switch with a label on the left.
     *
     * @return {@code true} when clicked; the caller flips its own boolean
     */
    public boolean toggle(String label, float x, float y, float w, boolean on) {
        float switchWidth = 46;
        float switchHeight = 22;
        float sx = x + w - switchWidth;
        boolean hovered = isHovered(x, y, w, switchHeight);
        r.text(label, x, y + (switchHeight - Renderer2D.TEXT_HEIGHT * 1.5f) / 2f, 1.5f, Theme.TEXT);
        r.rect(sx, y, switchWidth, switchHeight, on ? Theme.ACCENT : Theme.INPUT);
        r.outline(sx, y, switchWidth, switchHeight, 1f, hovered ? Theme.ACCENT_HOVER : Theme.PANEL_BORDER);
        float knob = switchHeight - 6;
        r.rect(on ? sx + switchWidth - knob - 3 : sx + 3, y + 3, knob, knob, Theme.TEXT);
        return clicked(hovered && input.wasMousePressed(GLFW_MOUSE_BUTTON_LEFT));
    }

    /** Dims the whole window behind a modal dialog. */
    public void modalBackdrop(float windowWidth, float windowHeight) {
        r.rect(0, 0, windowWidth, windowHeight, Theme.BACKGROUND.withAlpha(0.75f));
    }

    /**
     * Labelled single-line text input. Supports typing, backspace (with key repeat),
     * Ctrl/Cmd+V paste and Escape to unfocus.
     *
     * @return total height consumed (label + box), useful for vertical layout
     */
    public float textField(TextField field, float x, float y, float w) {
        float boxY = y + 18;
        float boxH = 30;
        r.text(field.label(), x, y, LABEL_SCALE, Theme.TEXT_MUTED);

        boolean hovered = isHovered(x, boxY, w, boxH);
        if (hovered && input.wasMousePressed(GLFW_MOUSE_BUTTON_LEFT)) {
            focused = field;
            clickedEmptySpace = false;
        }

        boolean isFocused = focused == field;
        if (isFocused) {
            handleTyping(field);
        }

        r.rect(x, boxY, w, boxH, Theme.INPUT);
        r.outline(x, boxY, w, boxH, isFocused ? 2f : 1f, isFocused ? Theme.INPUT_FOCUS : Theme.PANEL_BORDER);

        float textX = x + 8;
        float textY = boxY + (boxH - Renderer2D.TEXT_HEIGHT * LABEL_SCALE) / 2f;
        String shown = field.displayText();
        // Keep the end of long values visible by trimming from the left.
        while (!shown.isEmpty() && r.textWidth(shown, LABEL_SCALE) > w - 24) {
            shown = shown.substring(1);
        }
        r.text(shown, textX, textY, LABEL_SCALE, Theme.TEXT);

        if (isFocused && caretBlink < 0.5f) {
            float caretX = textX + r.textWidth(shown, LABEL_SCALE) + 2;
            r.rect(caretX, boxY + 6, 2, boxH - 12, Theme.TEXT);
        }
        return 18 + boxH;
    }

    private void handleTyping(TextField field) {
        if (input.wasKeyPressed(GLFW_KEY_ESCAPE) || input.wasKeyPressed(GLFW_KEY_ENTER)) {
            focused = null;
            return;
        }
        if (input.wasKeyPressed(GLFW_KEY_BACKSPACE)) {
            field.backspace();
        }
        if (input.isShortcutDown() && input.wasKeyPressed(GLFW_KEY_V)) {
            field.append(input.clipboard().trim());
        } else {
            field.append(input.typedText());
        }
    }
}

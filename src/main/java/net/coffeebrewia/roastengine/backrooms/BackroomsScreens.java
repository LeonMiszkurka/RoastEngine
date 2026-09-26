package net.coffeebrewia.roastengine.backrooms;

import net.coffeebrewia.roastengine.core.GameSettings;
import net.coffeebrewia.roastengine.input.Input;
import net.coffeebrewia.roastengine.render.Color;
import net.coffeebrewia.roastengine.render.Renderer2D;
import net.coffeebrewia.roastengine.ui.Theme;
import net.coffeebrewia.roastengine.ui.Ui;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

/**
 * What the Backrooms puts on screen: its home screen, the Options window that opens from it,
 * the reddening edges while something is near, and the screen that says it found you.
 *
 * <p>The home screen is drawn over the level itself rather than over a picture, so the place is
 * visible behind the menu, slowly turning.
 */
public final class BackroomsScreens {

    private static final Color YELLOW = Color.rgb(0xD8C85A);
    private static final Color BLOOD = Color.rgb(0x8E1F17);
    private static final Color DIM = Color.rgb(0x0A0A08);

    private static final float MENU_W = 460f;
    private static final float OPTIONS_W = 560f;
    /** Tall enough for every section plus the level list and the Back button under it. */
    private static final float OPTIONS_H = 664f;

    private final BackroomsMode backrooms;
    private final GameSettings settings;

    private boolean optionsOpen;
    /** Set when a slider moved, so the settings file is written once, on the way out. */
    private boolean settingsChanged;
    private boolean autoChecked;
    /**
     * The home screen only starts taking clicks once the mouse has been seen up. Gameplay holds
     * the cursor captured, so the click that brings the window back - or that opened the menu -
     * is still down on the frame the menu first appears, and would press whatever is under it.
     */
    private boolean mouseSettled;

    public BackroomsScreens(BackroomsMode backrooms, GameSettings settings) {
        this.backrooms = backrooms;
        this.settings = settings;
    }

    /** True while the Options window is up, so Esc closes that rather than opening the pause menu. */
    public boolean optionsOpen() {
        return optionsOpen;
    }

    public void closeOptions() {
        optionsOpen = false;
        backrooms.options().save();
        if (settingsChanged) {
            settings.save();
            settingsChanged = false;
        }
    }

    // ------------------------------------------------------------------
    // In the level
    // ------------------------------------------------------------------

    /**
     * Drawn with the rest of the HUD: the edges darken and redden as the monster closes in, which
     * is the only warning there is.
     */
    public void drawHud(Renderer2D r, float w, float h) {
        if (backrooms.phase() != BackroomsMode.Phase.PLAYING) {
            return;
        }
        float dread = backrooms.dread();
        if (dread <= 0.01f) {
            return;
        }
        float edge = Math.min(w, h) * (0.10f + 0.10f * dread);
        Color tint = BLOOD.withAlpha(Math.min(0.55f, dread * 0.55f));
        r.rect(0, 0, w, edge, tint);
        r.rect(0, h - edge, w, edge, tint);
        r.rect(0, edge, edge, h - edge * 2, tint);
        r.rect(w - edge, edge, edge, h - edge * 2, tint);
    }

    // ------------------------------------------------------------------
    // The screens that take the mouse
    // ------------------------------------------------------------------

    /** The home screen, the Options window and the caught screen. */
    public void drawMenus(Ui ui, Renderer2D r, Input input, float w, float h) {
        if (!input.isMouseDown(GLFW_MOUSE_BUTTON_LEFT)) {
            mouseSettled = true;
        }
        ui.setInputBlocked(!mouseSettled);
        switch (backrooms.phase()) {
            case MENU -> {
                if (optionsOpen) {
                    drawOptions(ui, r, w, h);
                } else {
                    drawHome(ui, r, w, h);
                }
            }
            case CAUGHT -> drawCaught(ui, r, w, h);
            default -> {
                // Playing and the intro have no menu of their own.
            }
        }
    }

    private void drawHome(Ui ui, Renderer2D r, float w, float h) {
        if (!autoChecked) {
            autoChecked = true;
            optionsOpen = backrooms.wantsOptionsOpen();
        }
        BackroomsConfig.Menu menu = backrooms.config().menu();
        // The level shows through, but only just: this is a menu, not a window.
        r.rect(0, 0, w, h, DIM.withAlpha(0.72f));

        float x = (w - MENU_W) / 2f;
        float titleY = h * 0.16f;
        r.textCentered(menu.title(), x, titleY, MENU_W, 60, 6f, YELLOW);
        if (!menu.subtitle().isBlank()) {
            r.textCentered(menu.subtitle(), x, titleY + 62, MENU_W, 24, 1.6f, Theme.TEXT_MUTED);
        }

        float buttonX = x + 70;
        float buttonWidth = MENU_W - 140;
        float buttonY = h * 0.42f;
        if (ui.button(menu.playLabel(), buttonX, buttonY, buttonWidth, 52)) {
            backrooms.play();
        }
        if (ui.button("Options", buttonX, buttonY + 66, buttonWidth, 52)) {
            optionsOpen = true;
        }
        if (ui.button("Leave", buttonX, buttonY + 132, buttonWidth, 52)) {
            backrooms.leave();
        }

        // What Play will actually do, so nothing is a surprise.
        BackroomsConfig.LevelConfig level = backrooms.level();
        float footer = buttonY + 210;
        r.textCentered(level.name(), x, footer, MENU_W, 22, 2f, Theme.TEXT);
        r.textCentered(level.description(), x, footer + 26, MENU_W, 20, 1.3f, Theme.TEXT_MUTED);
        boolean hunted = backrooms.options().monsters && backrooms.huntedHere();
        String monsters = !backrooms.options().monsters ? "Nothing down there (monsters off)"
                : !backrooms.huntedHere() ? "Nothing hunts here"
                : backrooms.monster().name() + " - " + name(backrooms.options().difficulty);
        r.textCentered(monsters, x, footer + 50, MENU_W, 20, 1.3f,
                hunted ? BLOOD : Theme.TEXT_MUTED);
        if (!backrooms.hasIntroFile() && !backrooms.config().menu().intro().isBlank()) {
            r.textCentered("No intro video yet - drop " + shortName(backrooms.config().menu().intro())
                            + " into the mod folder", x, h - 46, MENU_W, 18, 1.2f, Theme.TEXT_MUTED);
        }
    }

    private void drawOptions(Ui ui, Renderer2D r, float w, float h) {
        BackroomsOptions options = backrooms.options();
        ui.modalBackdrop(w, h);
        float x = (w - OPTIONS_W) / 2f;
        // On a short window the panel shrinks to fit rather than running off the bottom.
        float panelHeight = Math.min(OPTIONS_H, h - 40);
        float y = (h - panelHeight) / 2f;
        ui.panel("Options", x, y, OPTIONS_W, panelHeight);

        float left = x + 30;
        float width = OPTIONS_W - 60;
        float row = y + 64;

        r.text("Sound", left, row, 1.6f, YELLOW);
        row += 26;
        float music = ui.slider("Music", left, row, width, settings.musicVolume, 0f, 1f,
                percent(settings.musicVolume));
        row += 44;
        float effects = ui.slider("Sound effects", left, row, width, settings.effectsVolume, 0f, 1f,
                percent(settings.effectsVolume));
        row += 56;

        r.text("Looking around", left, row, 1.6f, YELLOW);
        row += 26;
        float sensitivity = ui.slider("Mouse sensitivity", left, row, width,
                settings.mouseSensitivity, 0.2f, 3f, String.format("%.2fx", settings.mouseSensitivity));
        row += 44;
        options.brightness = ui.slider("Brightness", left, row, width, options.brightness,
                BackroomsOptions.MIN_BRIGHTNESS, BackroomsOptions.MAX_BRIGHTNESS,
                percent(options.brightness));
        row += 56;

        // Sliders hand back what they are now. They take effect at once - the audio engine and
        // the camera read these every frame - but the file is only written when the window is
        // closed, rather than on every frame the mouse is held down.
        if (music != settings.musicVolume || effects != settings.effectsVolume
                || sensitivity != settings.mouseSensitivity) {
            settings.musicVolume = music;
            settings.effectsVolume = effects;
            settings.mouseSensitivity = sensitivity;
            settingsChanged = true;
        }

        r.text("Down there", left, row, 1.6f, YELLOW);
        row += 26;
        if (ui.checkbox("Monsters", left, row, options.monsters)) {
            options.monsters = !options.monsters;
        }
        row += 36;
        String difficulty = "Difficulty: " + name(options.difficulty);
        if (ui.button(difficulty, left, row, 220, 38, options.monsters)) {
            options.difficulty = options.difficulty.next();
        }
        r.text(describe(options.difficulty), left + 236, row + 12, 1.2f, Theme.TEXT_MUTED);
        row += 52;

        r.text("Level", left, row, 1.6f, YELLOW);
        row += 26;
        for (BackroomsConfig.LevelConfig level : backrooms.config().levels()) {
            boolean chosen = level.id().equals(backrooms.level().id());
            if (ui.selectable(level.name(), left, row, width, 34, chosen)) {
                backrooms.chooseLevel(level);
            }
            row += 38;
        }

        if (ui.button("Back", x + OPTIONS_W - 150, y + panelHeight - 52, 130, 40)) {
            closeOptions();
        }
    }

    private void drawCaught(Ui ui, Renderer2D r, float w, float h) {
        r.rect(0, 0, w, h, Color.rgb(0x000000).withAlpha(0.86f));
        float x = (w - MENU_W) / 2f;
        r.textCentered("IT FOUND YOU", x, h * 0.3f, MENU_W, 50, 5f, BLOOD);
        r.textCentered(backrooms.monster().name() + " caught you in " + backrooms.level().name(),
                x, h * 0.3f + 58, MENU_W, 22, 1.4f, Theme.TEXT_MUTED);

        if (!backrooms.caughtScreenReady()) {
            return;   // a moment to take it in before the buttons appear
        }
        float buttonX = x + 70;
        float buttonWidth = MENU_W - 140;
        float buttonY = h * 0.55f;
        if (ui.button("Try again", buttonX, buttonY, buttonWidth, 50)) {
            backrooms.retry();
        }
        if (ui.button("Home screen", buttonX, buttonY + 62, buttonWidth, 50)) {
            backrooms.showMenu();
        }
    }

    private static String percent(float value) {
        return Math.round(value * 100) + "%";
    }

    private static String name(Stalker.Difficulty difficulty) {
        return switch (difficulty) {
            case CALM -> "Calm";
            case NORMAL -> "Normal";
            case NIGHTMARE -> "Nightmare";
        };
    }

    private static String describe(Stalker.Difficulty difficulty) {
        return switch (difficulty) {
            case CALM -> "slow, and half deaf";
            case NORMAL -> "as the level was built";
            case NIGHTMARE -> "faster than you, and it hears you breathe";
        };
    }

    /** "video/BK_INTRO.mp4" -> "BK_INTRO.mp4". */
    private static String shortName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}

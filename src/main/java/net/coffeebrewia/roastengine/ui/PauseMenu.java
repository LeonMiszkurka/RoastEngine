package net.coffeebrewia.roastengine.ui;

import net.coffeebrewia.roastengine.core.GameSettings;
import net.coffeebrewia.roastengine.render.Renderer2D;

/**
 * The in-game pause menu: Resume, Settings and a way back to the main menu.
 *
 * <p>Settings are split into tabs. <b>General</b> holds volumes, mouse feel and which control
 * schemes are live; <b>Graphics</b> holds display options and the <em>Shaders</em> switch. Both
 * the shader look and any extra controller come from API mods, so each tab says what is loaded.
 */
public final class PauseMenu {

    /** What the player chose this frame. */
    public enum Action { NONE, RESUME, MAIN_MENU }

    private enum Page { MAIN, SETTINGS }

    private enum Tab { GENERAL, GRAPHICS }

    private final GameSettings settings;
    private final java.util.function.Supplier<String> shaderStatus;
    private final java.util.function.Supplier<String> controlStatus;
    private final java.util.function.Supplier<String> deviceStatus;
    private Page page = Page.MAIN;
    private Tab tab = Tab.GENERAL;
    private boolean settingsChanged;

    public PauseMenu(GameSettings settings, java.util.function.Supplier<String> shaderStatus,
                     java.util.function.Supplier<String> controlStatus,
                     java.util.function.Supplier<String> deviceStatus) {
        this.settings = settings;
        this.shaderStatus = shaderStatus;
        this.controlStatus = controlStatus;
        this.deviceStatus = deviceStatus;
    }

    /** Dev aid: opens straight onto a page ("main", "general" or "graphics"). */
    public void openAt(String where) {
        switch (where) {
            case "general" -> { page = Page.SETTINGS; tab = Tab.GENERAL; }
            case "graphics" -> { page = Page.SETTINGS; tab = Tab.GRAPHICS; }
            default -> page = Page.MAIN;
        }
    }

    /** Opens on the main page each time the game is paused. */
    public void open() {
        page = Page.MAIN;
    }

    /**
     * Handles Escape: backs out of settings, or resumes from the main page.
     *
     * @return true when the menu should close
     */
    public boolean back() {
        if (page == Page.SETTINGS) {
            leaveSettings();
            return false;
        }
        return true;
    }

    /** True while settings are being edited, so the game can apply them live. */
    public boolean inSettings() {
        return page == Page.SETTINGS;
    }

    public Action draw(Ui ui, float width, float height) {
        Renderer2D r = ui.renderer();
        ui.modalBackdrop(width, height);
        return page == Page.MAIN ? drawMain(ui, r, width, height) : drawSettings(ui, r, width, height);
    }

    // ---------------------------------------------------------------------
    // Main page
    // ---------------------------------------------------------------------

    private Action drawMain(Ui ui, Renderer2D r, float width, float height) {
        float panelWidth = 360;
        float panelHeight = 330;
        float x = (width - panelWidth) / 2f;
        float y = (height - panelHeight) / 2f;
        ui.panel(null, x, y, panelWidth, panelHeight);
        r.textCentered("Paused", x, y + 22, panelWidth, 40, 4f, Theme.TEXT);

        float buttonX = x + 30;
        float buttonWidth = panelWidth - 60;
        float buttonY = y + 100;
        Action action = Action.NONE;
        if (ui.button("Resume", buttonX, buttonY, buttonWidth, 46)) {
            action = Action.RESUME;
        }
        if (ui.button("Settings", buttonX, buttonY + 62, buttonWidth, 46)) {
            page = Page.SETTINGS;
        }
        if (ui.button("Main Menu", buttonX, buttonY + 124, buttonWidth, 46)) {
            action = Action.MAIN_MENU;
        }
        r.textCentered("Esc to resume", x, y + panelHeight - 34, panelWidth, 20, 1.5f, Theme.TEXT_MUTED);
        return action;
    }

    // ---------------------------------------------------------------------
    // Settings page
    // ---------------------------------------------------------------------

    private Action drawSettings(Ui ui, Renderer2D r, float width, float height) {
        float panelWidth = Math.min(560f, width - 60);
        float panelHeight = 510;
        float x = (width - panelWidth) / 2f;
        float y = (height - panelHeight) / 2f;
        ui.panel("Settings", x, y, panelWidth, panelHeight);

        // Tabs
        float tabY = y + 50;
        if (ui.flatButton("General", x + 16, tabY, 120, 30, tab == Tab.GENERAL)) {
            tab = Tab.GENERAL;
        }
        if (ui.flatButton("Graphics", x + 140, tabY, 120, 30, tab == Tab.GRAPHICS)) {
            tab = Tab.GRAPHICS;
        }
        r.rect(x + 16, tabY + 32, panelWidth - 32, 1, Theme.PANEL_BORDER);

        float contentX = x + 30;
        float contentWidth = panelWidth - 60;
        float contentY = tabY + 52;
        if (tab == Tab.GENERAL) {
            drawGeneral(ui, r, contentX, contentY, contentWidth);
        } else {
            drawGraphics(ui, r, contentX, contentY, contentWidth);
        }

        if (ui.button("Back", x + panelWidth - 150, y + panelHeight - 56, 130, 40)) {
            leaveSettings();
        }
        return Action.NONE;
    }

    private void drawGeneral(Ui ui, Renderer2D r, float x, float y, float width) {
        float master = ui.slider("Master volume", x, y, width, settings.masterVolume, 0f, 1f,
                percent(settings.masterVolume));
        float music = ui.slider("Music volume", x, y + 54, width, settings.musicVolume, 0f, 1f,
                percent(settings.musicVolume));
        float effects = ui.slider("Effects volume", x, y + 108, width, settings.effectsVolume, 0f, 1f,
                percent(settings.effectsVolume));
        float sensitivity = ui.slider("Mouse sensitivity", x, y + 162, width, settings.mouseSensitivity,
                0.1f, 3f, String.format("%.2fx", settings.mouseSensitivity));
        float fov = ui.slider("Field of view", x, y + 216, width, settings.fieldOfView, 50f, 110f,
                Math.round(settings.fieldOfView) + " deg");

        if (master != settings.masterVolume || music != settings.musicVolume
                || effects != settings.effectsVolume || sensitivity != settings.mouseSensitivity
                || fov != settings.fieldOfView) {
            settings.masterVolume = master;
            settings.musicVolume = music;
            settings.effectsVolume = effects;
            settings.mouseSensitivity = sensitivity;
            settings.fieldOfView = fov;
            settingsChanged = true;
        }

        r.text("Controls", x, y + 272, 1.5f, Theme.TEXT);
        r.text(r.ellipsize(controlStatus.get(), width, 1.25f), x, y + 292, 1.25f, Theme.TEXT_MUTED);
        r.text(r.ellipsize(deviceStatus.get(), width, 1.25f), x, y + 310, 1.25f, Theme.TEXT_MUTED);
        r.text("Install the InputEdit API, then a mod for your device.",
                x, y + 328, 1.25f, Theme.TEXT_MUTED);
    }

    private void drawGraphics(Ui ui, Renderer2D r, float x, float y, float width) {
        if (ui.toggle("Show FPS", x, y, width, settings.showFps)) {
            settings.showFps = !settings.showFps;
            settingsChanged = true;
        }
        if (ui.toggle("VSync  (applies on restart)", x, y + 44, width, settings.vsync)) {
            settings.vsync = !settings.vsync;
            settingsChanged = true;
        }
        if (ui.toggle("Enable Shaders", x, y + 88, width, settings.shadersEnabled)) {
            settings.shadersEnabled = !settings.shadersEnabled;
            settingsChanged = true;
        }
        r.text(r.ellipsize(shaderStatus.get(), width, 1.25f), x, y + 120, 1.25f, Theme.TEXT_MUTED);
        r.text("Shaders come from an API mod: install one from the Mod Browser and enable it.",
                x, y + 138, 1.25f, Theme.TEXT_MUTED);
    }

    private void leaveSettings() {
        page = Page.MAIN;
        if (settingsChanged) {
            settings.save();
            settingsChanged = false;
        }
    }

    private static String percent(float value) {
        return Math.round(value * 100) + "%";
    }
}

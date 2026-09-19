package net.coffeebrewia.roastengine.states;

import net.coffeebrewia.roastengine.core.CreatorLauncher;
import net.coffeebrewia.roastengine.core.Engine;
import net.coffeebrewia.roastengine.core.GameState;
import net.coffeebrewia.roastengine.modding.LocalMod;
import net.coffeebrewia.roastengine.modding.ModConfig;
import net.coffeebrewia.roastengine.modding.ModInfo;
import net.coffeebrewia.roastengine.modding.ModIoClient;
import net.coffeebrewia.roastengine.modding.ModIoClient.ConnectionStatus;
import net.coffeebrewia.roastengine.modding.ModManager;
import net.coffeebrewia.roastengine.render.Color;
import net.coffeebrewia.roastengine.render.Renderer2D;
import net.coffeebrewia.roastengine.ui.ModIcons;
import net.coffeebrewia.roastengine.ui.TextField;
import net.coffeebrewia.roastengine.ui.Theme;
import net.coffeebrewia.roastengine.ui.Ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;

import static org.lwjgl.opengl.GL11C.*;

/**
 * State 2 - Main menu dashboard: start button, mod.io configuration and mod browser.
 *
 * <p>All fields are only touched on the main thread; async results are delivered through
 * {@link Engine#runOnMainThread(Runnable)}.
 */
public final class MainMenuState implements GameState {

    private static final int PAGE_SIZE = 100;
    private static final float ROW_HEIGHT = 74f;

    private final Engine engine;
    private final Ui ui;
    private final Renderer2D r;
    /** Artwork the installed mods ship, loaded on demand while the browser is open. */
    private final ModIcons icons = new ModIcons();

    /** Where the player is in the mod.io email sign-in flow. */
    private enum AuthStep {SIGNED_OUT, SENDING, CODE_SENT, VERIFYING}

    private final TextField emailField = new TextField("Email address", false, 128);
    private final TextField codeField = new TextField("5-digit code", false, 8);

    private AuthStep authStep = AuthStep.SIGNED_OUT;
    private String authMessage = "";
    private boolean authFailed;

    private ConnectionStatus connection;
    private boolean connecting;

    private List<ModInfo> mods = List.of();
    private boolean loadingMods;
    private String browserMessage = "Configure mod.io to browse mods.";
    private final Set<Long> downloading = new HashSet<>();
    private final Map<Long, String> downloadErrors = new HashMap<>();
    private float scroll;
    private float deltaSeconds;
    private boolean firstEnter = true;
    private String creatorNotice = "";
    private boolean creatorNoticeIsError;
    private float creatorNoticeTimer;

    public MainMenuState(Engine engine, ConnectionStatus initialConnection) {
        this.engine = engine;
        this.ui = engine.ui();
        this.r = engine.renderer2D();
        this.connection = initialConnection;
    }

    @Override
    public void enter() {
        engine.input().setCursorCaptured(false);
        engine.audio().playMusic(net.coffeebrewia.roastengine.audio.SoundBank.MUSIC_MENU);
        if (firstEnter) {
            firstEnter = false;
            if (engine.modConfig().isSignedIn()) {
                // Already signed in from a previous session - go straight to the mod list.
                refreshMods();
            } else {
                browserMessage = "Sign in with mod.io Connect to browse mods.";
            }
        }
    }

    @Override
    public void exit() {
        // The textures are only needed while this screen is up; they reload on the way back.
        icons.dispose();
    }

    @Override
    public void update(float deltaSeconds) {
        // Immediate-mode UI handles interaction while drawing, see render().
        this.deltaSeconds = deltaSeconds;
        if (creatorNoticeTimer > 0) {
            creatorNoticeTimer -= deltaSeconds;
        }
    }

    /** Starts the Creator in its own process (it is a separate Gradle module). */
    private void launchCreator() {
        String error = CreatorLauncher.launch();
        creatorNoticeIsError = error != null;
        creatorNotice = error != null ? error : "Creator starting in a separate window...";
        creatorNoticeTimer = 6f;
    }

    @Override
    public void render() {
        glClearColor(Theme.BACKGROUND.r(), Theme.BACKGROUND.g(), Theme.BACKGROUND.b(), 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        float w = engine.window().width();
        float h = engine.window().height();

        ui.begin(deltaSeconds);

        // Header
        r.text("RoastEngine", 40, 26, 4f, Theme.TEXT);
        r.text("CoffeBrewIA  |  v0.1 MVP  |  " + Math.round(engine.fps()) + " FPS", 40, 66, 1.5f, Theme.TEXT_MUTED);

        float top = 100;
        float leftX = 40;
        float leftW = Math.min(380f, w * 0.38f);

        if (ui.button("Start Sandbox", leftX, top, leftW, 56)) {
            engine.states().switchTo(new SandboxState(engine, this));
        }
        if (ui.button("Multiplayer", leftX, top + 64, leftW, 40)) {
            engine.states().switchTo(new MultiplayerMenuState(engine, this));
        }
        if (ui.button("Open RoastEngine Creator", leftX, top + 112, leftW, 40)) {
            launchCreator();
        }
        if (creatorNoticeTimer > 0) {
            r.text(r.ellipsize(creatorNotice, leftW, 1.25f), leftX, top + 156, 1.25f,
                    creatorNoticeIsError ? Theme.ERROR : Theme.SUCCESS);
        }

        drawConnectPanel(leftX, top + 172, leftW, h - top - 172 - 30);

        float browserX = leftX + leftW + 24;
        drawModBrowser(browserX, top, w - browserX - 40, h - top - 30);

        ui.end();
    }

    // ---------------------------------------------------------------------
    // mod.io configuration
    // ---------------------------------------------------------------------

    /**
     * mod.io Connect: the flow console games use. The game already knows its own Game ID and
     * API key (shipped in {@code modio-defaults.properties}), so the player only ever types an
     * email address and the 5-digit code mod.io sends back.
     */
    private void drawConnectPanel(float x, float y, float w, float h) {
        ui.panel("Mod.io Connect", x, y, w, h);
        float fx = x + 16;
        float fw = w - 32;
        float fy = y + 54;
        ModConfig cfg = engine.modConfig();

        if (cfg.isSignedIn() && authStep != AuthStep.CODE_SENT) {
            String who = cfg.username().isBlank() ? "your mod.io account" : cfg.username();
            r.text("Signed in as", fx, fy, 1.5f, Theme.TEXT_MUTED);
            r.text(r.ellipsize(who, fw, 2f), fx, fy + 18, 2f, Theme.SUCCESS);
            fy += 54;
            if (ui.button("Sign Out", fx, fy, fw, 32)) {
                signOut();
            }
            fy += 44;
            if (connection != null) {
                r.text(r.ellipsize(connection.message(), fw, 1.25f), fx, fy, 1.25f,
                        connection.ok() ? Theme.TEXT_MUTED : Theme.ERROR);
            }
            return;
        }

        switch (authStep) {
            case SIGNED_OUT, SENDING -> {
                r.text("Sign in to browse and install mods.", fx, fy, 1.5f, Theme.TEXT_MUTED);
                fy += 24;
                fy += ui.textField(emailField, fx, fy, fw) + 12;
                boolean canSend = authStep != AuthStep.SENDING && emailField.text().contains("@");
                if (ui.button(authStep == AuthStep.SENDING ? "Sending..." : "Send Code",
                        fx, fy, fw, 36, canSend)) {
                    sendEmailCode();
                }
                fy += 48;
                r.text("mod.io emails you a 5-digit code.", fx, fy, 1.25f, Theme.TEXT_MUTED);
                fy += 16;
                r.text("No account? One is created for you.", fx, fy, 1.25f, Theme.TEXT_MUTED);
            }
            case CODE_SENT, VERIFYING -> {
                r.text(r.ellipsize("Code sent to " + emailField.text(), fw, 1.25f), fx, fy, 1.25f,
                        Theme.TEXT_MUTED);
                fy += 20;
                fy += ui.textField(codeField, fx, fy, fw) + 12;
                boolean canVerify = authStep != AuthStep.VERIFYING && codeField.text().length() >= 5;
                if (ui.button(authStep == AuthStep.VERIFYING ? "Connecting..." : "Connect",
                        fx, fy, fw, 36, canVerify)) {
                    verifyEmailCode();
                }
                fy += 46;
                if (ui.button("Use a different email", fx, fy, fw, 28)) {
                    authStep = AuthStep.SIGNED_OUT;
                    codeField.setText("");
                }
                fy += 40;
            }
        }

        if (!authMessage.isBlank()) {
            r.text(r.ellipsize(authMessage, fw, 1.25f), fx, fy, 1.25f,
                    authFailed ? Theme.ERROR : Theme.SUCCESS);
        }
    }

    private void sendEmailCode() {
        authStep = AuthStep.SENDING;
        setAuthMessage("Asking mod.io for a code...", false);
        engine.modIo().requestEmailCode(emailField.text()).whenComplete((ignored, error) ->
                engine.runOnMainThread(() -> {
                    if (error != null) {
                        authStep = AuthStep.SIGNED_OUT;
                        setAuthMessage(ModIoClient.describe(error), true);
                    } else {
                        authStep = AuthStep.CODE_SENT;
                        codeField.setText("");
                        setAuthMessage("Check your email for the code.", false);
                    }
                }));
    }

    private void verifyEmailCode() {
        authStep = AuthStep.VERIFYING;
        setAuthMessage("Verifying code...", false);
        engine.modIo().signInWithCode(codeField.text()).whenComplete((username, error) ->
                engine.runOnMainThread(() -> {
                    if (error != null) {
                        authStep = AuthStep.CODE_SENT;
                        setAuthMessage(ModIoClient.describe(error), true);
                        return;
                    }
                    authStep = AuthStep.SIGNED_OUT; // the signed-in branch takes over
                    codeField.setText("");
                    setAuthMessage("Signed in as " + username, false);
                    saveConfig();
                    connection = new ConnectionStatus(true, "Connected to mod.io");
                    refreshMods();
                }));
    }

    private void signOut() {
        engine.modConfig().signOut();
        saveConfig();
        authStep = AuthStep.SIGNED_OUT;
        mods = List.of();
        browserMessage = "Sign in to browse mods.";
        setAuthMessage("Signed out.", false);
    }

    private void saveConfig() {
        try {
            engine.modConfig().save();
        } catch (IOException e) {
            setAuthMessage("Could not save sign-in: " + e.getMessage(), true);
        }
    }

    private void setAuthMessage(String text, boolean failed) {
        authMessage = text;
        authFailed = failed;
        System.out.println("[ModIo] " + text);
    }

    // ---------------------------------------------------------------------
    // Mod browser
    // ---------------------------------------------------------------------

    private void refreshMods() {
        if (!engine.modConfig().isConfigured()) {
            browserMessage = "Configure mod.io to browse mods.";
            return;
        }
        loadingMods = true;
        browserMessage = "Loading mods...";
        engine.modIo().fetchMods(0, PAGE_SIZE).whenComplete((list, error) -> engine.runOnMainThread(() -> {
            loadingMods = false;
            scroll = 0;
            if (error != null) {
                mods = List.of();
                browserMessage = "Failed to load mods: " + ModIoClient.describe(error);
            } else {
                mods = list;
                browserMessage = list.isEmpty() ? "No mods found for this game." : null;
                System.out.println("[MainMenu] Fetched " + list.size() + " mod(s) from mod.io"
                        + (list.isEmpty() ? "" : ": " + list.stream().map(ModInfo::name).toList()));
            }
        }));
    }

    private void drawModBrowser(float x, float y, float w, float h) {
        ui.panel("Mod Browser", x, y, w, h);
        boolean canRefresh = !loadingMods && engine.modConfig().isConfigured();
        if (ui.button(loadingMods ? "Loading" : "Refresh", x + w - 126, y + 8, 110, 26, canRefresh)) {
            refreshMods();
        }

        ModManager modManager = engine.modManager();
        List<LocalMod> installed = modManager.installedMods();
        List<ModInfo> outdated = outdatedMods(installed);

        long worldCount = installed.stream().filter(LocalMod::isWorld).count();
        long apiCount = installed.stream().filter(LocalMod::isApi).count();
        long modCount = installed.size() - worldCount - apiCount;
        r.text("Installed: " + worldCount + " world(s), " + apiCount + " API(s), " + modCount + " mod(s)",
                x + 16, y + 50, 1.25f, Theme.TEXT_MUTED);
        if (!outdated.isEmpty()) {
            String label = outdated.size() + " update(s) available";
            r.text(label, x + 16, y + 66, 1.25f, Theme.ACCENT);
            float labelWidth = r.textWidth(label, 1.25f);
            if (ui.button("Update All", x + 24 + labelWidth, y + 58, 100, 24, downloading.isEmpty())) {
                outdated.forEach(this::download);
            }
        }

        float listX = x + 12;
        float listY = y + (outdated.isEmpty() ? 72 : 88);
        float listW = w - 24;
        float listH = h - (listY - y) - 12;

        if (browserMessage != null) {
            r.textCentered(browserMessage, listX, listY, listW, listH, 1.75f, Theme.TEXT_MUTED);
            return;
        }

        boolean listHovered = ui.isHovered(listX, listY, listW, listH);
        float maxScroll = Math.max(0, mods.size() * ROW_HEIGHT - listH);
        if (listHovered) {
            scroll -= engine.input().scrollY() * 40f;
        }
        scroll = Math.max(0, Math.min(maxScroll, scroll));

        r.pushClip(listX, listY, listW, listH);
        for (int i = 0; i < mods.size(); i++) {
            float rowY = listY + i * ROW_HEIGHT - scroll;
            if (rowY + ROW_HEIGHT < listY || rowY > listY + listH) {
                continue; // off screen
            }
            drawModRow(mods.get(i), installed, listX, rowY, listW, listHovered);
        }
        r.popClip();
    }

    /** Remote mods whose installed copy is out of date. */
    private List<ModInfo> outdatedMods(List<LocalMod> installed) {
        return mods.stream()
                .filter(ModInfo::isDownloadable)
                .filter(mod -> !downloading.contains(mod.id()))
                .filter(mod -> installedFor(installed, mod)
                        .filter(local -> local.modfileId() != mod.modfileId())
                        .isPresent())
                .toList();
    }

    private static Optional<LocalMod> installedFor(List<LocalMod> installed, ModInfo mod) {
        return installed.stream().filter(local -> local.modIoId() == mod.id()).findFirst();
    }

    /** A button that reads as a switch: filled when on, dim when off. */
    private boolean drawEnableButton(String label, float x, float y, float w, float h, boolean enabled) {
        if (enabled) {
            return ui.button(label, x, y, w, h);
        }
        boolean clicked = ui.flatButton(label, x, y, w, h, false);
        r.outline(x, y, w, h, 1f, Theme.ACCENT);
        return clicked;
    }

    private void playWorld(LocalMod world) {
        System.out.println("[MainMenu] Loading world mod: " + world.name() + " (" + world.folder() + ")");
        engine.states().switchTo(new SandboxState(engine, this, world));
    }

    private void drawModRow(ModInfo mod, List<LocalMod> installed, float x, float y, float w, boolean listHovered) {
        float rowH = ROW_HEIGHT - 6;
        boolean hovered = listHovered && ui.isHovered(x, y, w, rowH);
        r.rect(x, y, w, rowH, hovered ? Theme.ROW_HOVER : Theme.ROW);

        float buttonW = 130;
        Optional<LocalMod> local = installedFor(installed, mod);

        // The icon comes from the installed copy's icon.png; a mod that is not installed yet
        // (or ships none) gets the lettered tile instead.
        float iconSize = rowH - 20;
        icons.draw(r, local.orElse(null), mod.name(), x + 10, y + 10, iconSize);
        float textX = x + iconSize + 22;
        float textW = w - buttonW - (textX - x) - 28;

        String title = mod.name();
        if (local.isPresent()) {
            title += local.get().isWorld() ? "   [world]" : local.get().isApi() ? "   [API]" : "   [mod]";
        }
        r.text(r.ellipsize(title, textW, 2f), textX, y + 8, 2f, Theme.TEXT);
        String meta = "by " + mod.author() + "  |  " + mod.downloads() + " downloads  |  " + formatSize(mod.fileSize());
        r.text(r.ellipsize(meta, textW, 1.25f), textX, y + 30, 1.25f, Theme.TEXT_MUTED);

        String error = downloadErrors.get(mod.id());
        if (error != null) {
            r.text(r.ellipsize("Error: " + error, textW, 1.25f), textX, y + 48, 1.25f, Theme.ERROR);
        } else {
            r.text(r.ellipsize(mod.summary(), textW, 1.25f), textX, y + 48, 1.25f, Theme.TEXT_MUTED);
        }

        float bx = x + w - buttonW - 12;
        float topY = y + 6;
        float bottomY = y + 36;

        if (downloading.contains(mod.id())) {
            r.textCentered("Downloading...", bx, topY, buttonW, 26, 1.5f, Theme.ACCENT);
        } else if (!mod.isDownloadable()) {
            r.textCentered("No file", bx, topY, buttonW, 26, 1.5f, Theme.TEXT_MUTED);
        } else if (local.isEmpty()) {
            if (ui.button("Download", bx, topY, buttonW, 26) && listHovered) {
                download(mod);
            }
        } else if (local.get().modfileId() != mod.modfileId()) {
            // A newer file is live on mod.io than the one installed.
            if (ui.button("Update", bx, topY, buttonW, 26) && listHovered) {
                download(mod);
            }
        } else {
            r.textCentered("Installed", bx, topY, buttonW, 26, 1.5f, Theme.SUCCESS);
        }

        // World mods can be played straight from the browser; APIs and mods are switched on.
        if (local.isPresent() && !downloading.contains(mod.id())) {
            LocalMod installedMod = local.get();
            if (installedMod.isWorld()) {
                if (ui.button("Play World", bx, bottomY, buttonW, 26) && listHovered) {
                    playWorld(installedMod);
                }
            } else {
                boolean enabled = engine.modManager().isEnabled(installedMod);
                if (drawEnableButton(enabled ? "Disable" : "Enable", bx, bottomY, buttonW, 26, enabled)
                        && listHovered) {
                    engine.modManager().setEnabled(installedMod, !enabled);
                }
            }
        }
    }

    private void download(ModInfo mod) {
        long id = mod.id();
        downloading.add(id);
        downloadErrors.remove(id);

        ModManager modManager = engine.modManager();
        Path zip = modManager.downloadsDirectory().resolve(id + "_" + mod.modfileId() + ".zip");

        engine.modIo().downloadModFile(mod, zip)
                .thenApplyAsync(path -> {
                    try {
                        return modManager.install(mod, path);
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }, engine.background())
                .whenComplete((localMod, error) -> engine.runOnMainThread(() -> {
                    downloading.remove(id);
                    if (error != null) {
                        String message = ModIoClient.describe(error);
                        downloadErrors.put(id, message);
                        System.err.println("[ModBrowser] " + mod.name() + " failed: " + message);
                    } else {
                        // A new or updated mod may ship different artwork.
                        icons.dispose();
                        System.out.println("[ModBrowser] Installed " + localMod.name() + " -> " + localMod.folder());
                    }
                }));
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "? MB";
        if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}

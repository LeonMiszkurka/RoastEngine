package net.coffeebrewia.roastengine.states;

import net.coffeebrewia.roastengine.core.Engine;
import net.coffeebrewia.roastengine.core.GameState;
import net.coffeebrewia.roastengine.modding.ModIoClient.ConnectionStatus;
import net.coffeebrewia.roastengine.render.Renderer2D;
import net.coffeebrewia.roastengine.ui.Theme;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.lwjgl.opengl.GL11C.*;

/**
 * State 1 - Boot splash.
 *
 * <p>OpenGL and input are initialised by the engine before this state is entered (they must
 * happen on the main thread). This state then runs the slower boot tasks in the background -
 * loading config, scanning {@code mods/}, checking the mod.io connection - while animating
 * the splash screen, and switches to the main menu when everything is done.
 */
public final class LoadingState implements GameState {

    private static final float MIN_DISPLAY_SECONDS = 1.5f;
    private static final long MODIO_CHECK_TIMEOUT_SECONDS = 8;

    private final Engine engine;
    private volatile String status = "Starting...";
    private volatile ConnectionStatus connection;
    private CompletableFuture<Void> boot;
    private float elapsed;

    public LoadingState(Engine engine) {
        this.engine = engine;
    }

    @Override
    public void enter() {
        System.out.println("[Boot] OpenGL " + glGetString(GL_VERSION) + " on " + glGetString(GL_RENDERER));
        System.out.println("[Boot] Input listeners registered");

        boot = CompletableFuture.runAsync(this::runBootTasks, engine.background());
    }

    /** Runs on a worker thread. Must not touch OpenGL. */
    private void runBootTasks() {
        status = "Loading configuration...";
        engine.modConfig().load();

        status = "Scanning mods folder...";
        try {
            engine.modManager().scan();
        } catch (IOException e) {
            System.err.println("[Boot] Mod scan failed: " + e.getMessage());
        }

        status = "Connecting to mod.io...";
        connection = engine.modIo().checkConnection()
                .completeOnTimeout(new ConnectionStatus(false, "mod.io check timed out"),
                        MODIO_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .join();
        System.out.println("[Boot] mod.io: " + connection.message());

        status = "Ready";
    }

    @Override
    public void update(float deltaSeconds) {
        elapsed += deltaSeconds;
        if (boot.isDone() && elapsed >= MIN_DISPLAY_SECONDS) {
            if (boot.isCompletedExceptionally()) {
                boot.exceptionally(t -> {
                    System.err.println("[Boot] Unexpected boot failure: " + t);
                    return null;
                });
            }
            ConnectionStatus result = connection != null
                    ? connection
                    : new ConnectionStatus(false, "Boot tasks failed - see console");
            MainMenuState menu = new MainMenuState(engine, result);
            // -Proastengine.sandbox (gradle run -Psandbox) skips the menu, for quick testing.
            if (Boolean.getBoolean("roastengine.sandbox")) {
                engine.states().switchTo(new SandboxState(engine, menu));
            } else if (System.getProperty("roastengine.join") != null) {
                // -Pjoin=localhost goes straight to the multiplayer screen, which joins at once.
                engine.states().switchTo(new MultiplayerMenuState(engine, menu));
            } else {
                engine.states().switchTo(menu);
            }
        }
    }

    @Override
    public void render() {
        glClearColor(Theme.BACKGROUND.r(), Theme.BACKGROUND.g(), Theme.BACKGROUND.b(), 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        Renderer2D r = engine.renderer2D();
        float w = engine.window().width();
        float h = engine.window().height();

        r.begin();
        float titleScale = 7f;
        r.textCentered("RoastEngine", 0, h / 2f - 70, w, 60, titleScale, Theme.TEXT);
        r.textCentered("by CoffeBrewIA", 0, h / 2f - 4, w, 20, 1.75f, Theme.TEXT_MUTED);

        // Indeterminate progress bar: a highlight sliding back and forth.
        float barW = Math.min(360f, w - 80f);
        float barX = (w - barW) / 2f;
        float barY = h / 2f + 40;
        r.rect(barX, barY, barW, 4, Theme.PANEL_BORDER);
        float t = (float) (Math.sin(elapsed * 3.0) * 0.5 + 0.5);
        float segment = barW * 0.25f;
        r.rect(barX + t * (barW - segment), barY, segment, 4, Theme.ACCENT);

        r.textCentered(status, 0, barY + 20, w, 20, 1.5f, Theme.TEXT_MUTED);
        r.end();
    }
}

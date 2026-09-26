package net.coffeebrewia.roastengine.core;

import net.coffeebrewia.roastengine.audio.AudioEngine;
import net.coffeebrewia.roastengine.audio.SoundBank;
import net.coffeebrewia.roastengine.input.Gamepads;
import net.coffeebrewia.roastengine.input.Input;
import net.coffeebrewia.roastengine.input.InputActions;
import net.coffeebrewia.roastengine.modding.ModConfig;
import net.coffeebrewia.roastengine.modding.ModIoClient;
import net.coffeebrewia.roastengine.modding.ModManager;
import net.coffeebrewia.roastengine.render.Renderer2D;
import net.coffeebrewia.roastengine.render.Screenshot;
import net.coffeebrewia.roastengine.states.LoadingState;
import net.coffeebrewia.roastengine.ui.Ui;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.lwjgl.glfw.GLFW.glfwPollEvents;

/**
 * Central service hub and owner of the main loop.
 *
 * <p>The loop is a classic variable delta-time cycle:
 * <pre>
 *   poll events -> run main-thread tasks -> update(dt) -> render() -> swap
 * </pre>
 * Background work (HTTP, unzip) runs on {@link #background()} and hands results back via
 * {@link #runOnMainThread(Runnable)}, because GL and UI state must only be touched here.
 */
public final class Engine {

    /** Clamp for very long frames (debugger pauses, window drags) to keep physics sane. */
    private static final float MAX_DELTA = 0.1f;

    private final EngineConfig config;
    private final Window window;
    private final Input input = new Input();
    private final Gamepads gamepads = new Gamepads();
    /** Keyboard, mouse and every enabled InputEdit API, merged into game actions. */
    private final InputActions actions = new InputActions();
    private final StateManager states = new StateManager();
    private final Queue<Runnable> mainThreadTasks = new ConcurrentLinkedQueue<>();
    private final ExecutorService background = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "roast-worker");
        t.setDaemon(true);
        return t;
    });

    private final GameSettings settings;
    private final AudioEngine audio;
    private final ModConfig modConfig;
    /** The headset, when the game started in VR; null when playing flat. */
    private net.coffeebrewia.roastengine.vr.VrSystem vr;
    private final net.coffeebrewia.roastengine.account.CoffeeBrewAccount account;
    private final ModManager modManager;
    private final ModIoClient modIo;
    /** Produces the first state to enter once the window and renderer exist. */
    private final Function<Engine, GameState> bootState;

    private Renderer2D renderer2D;
    private Ui ui;
    private boolean running = true;
    private float fps;
    private float autoScreenshotTimer;

    /** Starts the game client (splash -> main menu -> sandbox). */
    public Engine(EngineConfig config) {
        this(config, LoadingState::new);
    }

    /** Starts with a custom first state - used by RoastEngine Creator. */
    public Engine(EngineConfig config, Function<Engine, GameState> bootState) {
        this.config = config;
        this.bootState = bootState;
        this.window = new Window(config.title(), config.width(), config.height());
        this.settings = new GameSettings(config.configDirectory().resolve("settings.properties"));
        this.settings.load();
        this.audio = new AudioEngine(settings);
        this.modConfig = new ModConfig(config.configDirectory().resolve("modio.properties"));
        this.modManager = new ModManager(config.modsDirectory());
        this.modIo = new ModIoClient(modConfig, background);
        this.account = new net.coffeebrewia.roastengine.account.CoffeeBrewAccount(
                config.configDirectory().resolve("coffeebrew.properties"), background);
    }

    public void run() {
        init();
        try {
            loop();
        } finally {
            cleanup();
        }
    }

    private void init() {
        System.out.println("[RoastEngine] Starting by CoffeBrewIA");
        window.create(config.vsync() && settings.vsync);
        input.attach(window.handle());
        renderer2D = new Renderer2D(window);
        ui = new Ui(renderer2D, input);
        audio.init();
        ui.setClickSound(() -> audio.play(SoundBank.CLICK, 0.5f, 1f));
        // VR is opt-in: -Pvr on the command line, or the VR API mod being switched on.
        if (Boolean.getBoolean("roastengine.vr") || System.getProperty("roastengine.vr") != null) {
            vr = net.coffeebrewia.roastengine.vr.VrSystem.tryStart(window.handle());
        }
        states.switchTo(bootState.apply(this));
    }

    private void loop() {
        long lastTime = System.nanoTime();
        float fpsTimer = 0f;
        int frames = 0;

        while (running && !window.shouldClose()) {
            long now = System.nanoTime();
            boolean background = !window.isFocused();
            window.setBackground(background);
            float delta = Math.min((now - lastTime) / 1_000_000_000f, MAX_DELTA);
            lastTime = now;

            glfwPollEvents();
            if (vr != null) {
                vr.pollEvents();
                if (vr.shouldQuit()) {
                    running = false;
                }
                // The runtime decides when the next frame is due; it also reports where the head
                // and hands are, which the game needs before it updates.
                vrFrameReady = vr.waitFrame();
            }
            gamepads.poll();
            // Actions are resolved before the state runs, so update() sees this frame's input
            // whether it came from the keyboard or from a controller a mod described.
            actions.update(input, gamepads, modManager, delta);
            drainMainThreadTasks();
            states.applyPendingSwitch();

            states.update(delta);
            states.render();

            handleScreenshots(delta);
            input.endFrame();
            if (vr != null && vrFrameReady) {
                vr.endFrame();
                vrFrameReady = false;
            }
            window.swapBuffers();
            if (background) {
                idleUntil(now + BACKGROUND_FRAME_NANOS);
            }

            frames++;
            fpsTimer += delta;
            if (fpsTimer >= 1f) {
                fps = frames / fpsTimer;
                frames = 0;
                fpsTimer = 0f;
            }
        }
    }

    private boolean vrFrameReady;

    /** 30 frames a second in the background: enough to stay online, easy on the battery. */
    private static final long BACKGROUND_FRAME_NANOS = 1_000_000_000L / 30;

    private static void idleUntil(long deadline) {
        long wait = deadline - System.nanoTime();
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** F2 saves a PNG; -Proastengine.autoScreenshot=<seconds> shoots once and exits (dev aid). */
    private void handleScreenshots(float delta) {
        if (input.wasKeyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_F2)) {
            Screenshot.capture(window, GameHome.dataDirectory().resolve("screenshots"));
        }
        String auto = System.getProperty("roastengine.autoScreenshot");
        if (auto != null) {
            autoScreenshotTimer += delta;
            if (autoScreenshotTimer >= Float.parseFloat(auto)) {
                Screenshot.capture(window, GameHome.dataDirectory().resolve("screenshots"));
                stop();
            }
        }
    }

    private void drainMainThreadTasks() {
        Runnable task;
        while ((task = mainThreadTasks.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException e) {
                System.err.println("[RoastEngine] Main-thread task failed: " + e);
                e.printStackTrace();
            }
        }
    }

    private void cleanup() {
        states.shutdown();
        audio.dispose();
        background.shutdownNow();
        if (renderer2D != null) {
            renderer2D.dispose();
        }
        input.detach(window.handle());
        window.destroy();
        System.out.println("[RoastEngine] Shutdown complete");
    }

    // ---------------------------------------------------------------------
    // Services
    // ---------------------------------------------------------------------

    /** Queues work to run on the main thread at the start of the next frame. Thread-safe. */
    public void runOnMainThread(Runnable task) {
        mainThreadTasks.add(task);
    }

    public void stop() {
        running = false;
    }

    public ExecutorService background() {
        return background;
    }

    public StateManager states() {
        return states;
    }

    public Window window() {
        return window;
    }

    public Input input() {
        return input;
    }

    /** Device-independent player intent: what the game reads instead of raw keys. */
    public InputActions actions() {
        return actions;
    }

    public Gamepads gamepads() {
        return gamepads;
    }

    public Renderer2D renderer2D() {
        return renderer2D;
    }

    public Ui ui() {
        return ui;
    }

    public GameSettings settings() {
        return settings;
    }

    /** Where settings live, for anything that keeps its own file beside them. */
    public java.nio.file.Path configDirectory() {
        return config.configDirectory();
    }

    public AudioEngine audio() {
        return audio;
    }

    /** The headset, or null when playing on a monitor. */
    public net.coffeebrewia.roastengine.vr.VrSystem vr() {
        return vr;
    }

    /** True while the game is drawing into a headset. */
    public boolean inVr() {
        return vr != null && vr.isRunning();
    }

    /** The player's CoffeeBrew Interactive account (needed for multiplayer). */
    public net.coffeebrewia.roastengine.account.CoffeeBrewAccount account() {
        return account;
    }

    public ModConfig modConfig() {
        return modConfig;
    }

    public ModManager modManager() {
        return modManager;
    }

    public ModIoClient modIo() {
        return modIo;
    }

    public float fps() {
        return fps;
    }
}

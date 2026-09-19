package net.coffeebrewia.roastengine.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Player-adjustable settings, saved to {@code config/settings.properties}.
 *
 * <p>Plain public fields keep the settings screen simple: it edits them directly, then calls
 * {@link #save()} when the player leaves the screen.
 */
public final class GameSettings {

    // --- General ---
    public float masterVolume = 0.8f;
    public float musicVolume = 0.55f;
    public float effectsVolume = 0.9f;
    public float mouseSensitivity = 1.0f;
    public float fieldOfView = 70f;
    public boolean showFps = true;

    // --- Graphics ---
    public boolean vsync = true;
    /** Runs the enabled shader pack (an API mod) over the scene, when one is installed. */
    public boolean shadersEnabled = false;

    // --- Multiplayer ---
    /** Shown to other players. A random default, so nobody joins as their computer's login name. */
    public String playerName = "Player" + (100 + new java.util.Random().nextInt(900));
    /** The address last joined, filled in next time. */
    public String lastServer = "";

    private final Path file;

    public GameSettings(Path file) {
        this.file = file;
    }

    public void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            System.err.println("[Settings] Could not read " + file + ": " + e.getMessage());
            return;
        }
        masterVolume = number(props, "masterVolume", masterVolume, 0f, 1f);
        musicVolume = number(props, "musicVolume", musicVolume, 0f, 1f);
        effectsVolume = number(props, "effectsVolume", effectsVolume, 0f, 1f);
        mouseSensitivity = number(props, "mouseSensitivity", mouseSensitivity, 0.1f, 3f);
        fieldOfView = number(props, "fieldOfView", fieldOfView, 50f, 110f);
        showFps = Boolean.parseBoolean(props.getProperty("showFps", String.valueOf(showFps)));
        vsync = Boolean.parseBoolean(props.getProperty("vsync", String.valueOf(vsync)));
        shadersEnabled = Boolean.parseBoolean(props.getProperty("shadersEnabled", String.valueOf(shadersEnabled)));
        playerName = props.getProperty("playerName", playerName);
        lastServer = props.getProperty("lastServer", lastServer);
    }

    public void save() {
        Properties props = new Properties();
        props.setProperty("masterVolume", String.valueOf(masterVolume));
        props.setProperty("musicVolume", String.valueOf(musicVolume));
        props.setProperty("effectsVolume", String.valueOf(effectsVolume));
        props.setProperty("mouseSensitivity", String.valueOf(mouseSensitivity));
        props.setProperty("fieldOfView", String.valueOf(fieldOfView));
        props.setProperty("showFps", String.valueOf(showFps));
        props.setProperty("vsync", String.valueOf(vsync));
        props.setProperty("shadersEnabled", String.valueOf(shadersEnabled));
        props.setProperty("playerName", playerName);
        props.setProperty("lastServer", lastServer);
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "RoastEngine settings");
            }
        } catch (IOException e) {
            System.err.println("[Settings] Could not save " + file + ": " + e.getMessage());
        }
    }

    private static float number(Properties props, String key, float fallback, float min, float max) {
        try {
            float value = Float.parseFloat(props.getProperty(key, String.valueOf(fallback)));
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

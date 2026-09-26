package net.coffeebrewia.roastengine.backrooms;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The choices made on the level's own Options window, kept between runs.
 *
 * <p>Volume and mouse sensitivity belong to the game as a whole and live in
 * {@code settings.properties} with everything else; what is here is what only means anything
 * down in the Backrooms - which level, how dangerous it is, and how dark you are willing to
 * have it. They are saved to {@code config/backrooms.properties}.
 */
public final class BackroomsOptions {

    /** How dark the level is allowed to get. 1 is as the level was built. */
    public static final float MIN_BRIGHTNESS = 0.6f;
    public static final float MAX_BRIGHTNESS = 2.0f;

    private final Path file;

    public boolean monsters = true;
    public Stalker.Difficulty difficulty = Stalker.Difficulty.NORMAL;
    public String levelId = "";
    public float brightness = 1f;

    public BackroomsOptions(Path file) {
        this.file = file;
        load();
    }

    public void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            System.err.println("[Backrooms] Could not read " + file + ": " + e.getMessage());
            return;
        }
        monsters = Boolean.parseBoolean(properties.getProperty("monsters", "true"));
        levelId = properties.getProperty("level", "");
        brightness = clampBrightness(parseFloat(properties.getProperty("brightness"), 1f));
        try {
            difficulty = Stalker.Difficulty.valueOf(
                    properties.getProperty("difficulty", "NORMAL").toUpperCase());
        } catch (IllegalArgumentException e) {
            difficulty = Stalker.Difficulty.NORMAL;   // a hand-edited file should not stop play
        }
    }

    public void save() {
        if (file == null) {
            return;
        }
        Properties properties = new Properties();
        properties.setProperty("monsters", Boolean.toString(monsters));
        properties.setProperty("difficulty", difficulty.name());
        properties.setProperty("level", levelId);
        properties.setProperty("brightness", Float.toString(brightness));
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "RoastEngine - The Backrooms");
            }
        } catch (IOException e) {
            System.err.println("[Backrooms] Could not save " + file + ": " + e.getMessage());
        }
    }

    public static float clampBrightness(float value) {
        return Math.max(MIN_BRIGHTNESS, Math.min(MAX_BRIGHTNESS, value));
    }

    private static float parseFloat(String text, float fallback) {
        if (text == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

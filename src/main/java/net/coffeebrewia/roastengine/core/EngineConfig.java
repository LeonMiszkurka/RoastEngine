package net.coffeebrewia.roastengine.core;

import java.nio.file.Path;

/**
 * Static startup configuration. Values can be overridden with JVM system properties,
 * e.g. {@code -Droastengine.modsDir=/path/to/mods}.
 */
public record EngineConfig(
        String title,
        int width,
        int height,
        boolean vsync,
        Path modsDirectory,
        Path configDirectory) {

    public static EngineConfig fromSystemProperties() {
        GameHome.prepare();
        Path home = GameHome.dataDirectory();
        return new EngineConfig(
                "RoastEngine",
                Integer.getInteger("roastengine.width", 1280),
                Integer.getInteger("roastengine.height", 720),
                Boolean.parseBoolean(System.getProperty("roastengine.vsync", "true")),
                home.resolve(System.getProperty("roastengine.modsDir", "mods")).toAbsolutePath(),
                home.resolve(System.getProperty("roastengine.configDir", "config")).toAbsolutePath());
    }
}

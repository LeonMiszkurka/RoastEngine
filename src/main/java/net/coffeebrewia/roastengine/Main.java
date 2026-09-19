package net.coffeebrewia.roastengine;

import net.coffeebrewia.roastengine.core.Engine;
import net.coffeebrewia.roastengine.core.EngineConfig;
import net.coffeebrewia.roastengine.core.GameLog;

/**
 * RoastEngine entry point.
 *
 * <p>On macOS the JVM must be started with {@code -XstartOnFirstThread}
 * (the Gradle {@code run} task adds it automatically).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        GameLog.start("game");
        EngineConfig config = EngineConfig.fromSystemProperties();
        Engine engine = new Engine(config);
        try {
            engine.run();
        } catch (Throwable t) {
            System.err.println("[RoastEngine] Fatal error: " + t);
            t.printStackTrace();
            System.exit(1);
        }
    }
}

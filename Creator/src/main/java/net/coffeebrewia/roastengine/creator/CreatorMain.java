package net.coffeebrewia.roastengine.creator;

import net.coffeebrewia.roastengine.core.Engine;
import net.coffeebrewia.roastengine.core.EngineConfig;
import net.coffeebrewia.roastengine.creator.states.LauncherState;

/**
 * RoastEngine Creator - the editor and mod authoring tool.
 *
 * <p>Runs on the same engine as the game client but boots straight into the project launcher
 * instead of the game's loading screen. Launch with {@code gradle :Creator:run}.
 */
public final class CreatorMain {

    private CreatorMain() {
    }

    /** Dev aid: -DopenProject=<path> boots straight into that project, skipping the launcher. */
    private static net.coffeebrewia.roastengine.core.GameState firstState(Engine engine) {
        String open = System.getProperty("roastengine.openProject");
        if (open != null && !open.isBlank()) {
            try {
                var project = net.coffeebrewia.roastengine.creator.project.CreatorProject
                        .open(java.nio.file.Path.of(open));
                return LauncherState.editorFor(engine, project);
            } catch (java.io.IOException e) {
                System.err.println("[Creator] Could not open " + open + ": " + e.getMessage());
            }
        }
        return new LauncherState(engine);
    }

    public static void main(String[] args) {
        net.coffeebrewia.roastengine.core.GameLog.start("creator");
        EngineConfig base = EngineConfig.fromSystemProperties();
        EngineConfig config = new EngineConfig(
                "RoastEngine Creator",
                Integer.getInteger("roastengine.width", 1500),
                Integer.getInteger("roastengine.height", 900),
                base.vsync(),
                base.modsDirectory(),
                base.configDirectory());

        Engine engine = new Engine(config, CreatorMain::firstState);
        try {
            engine.run();
        } catch (Throwable t) {
            System.err.println("[Creator] Fatal error: " + t);
            t.printStackTrace();
            System.exit(1);
        }
    }
}

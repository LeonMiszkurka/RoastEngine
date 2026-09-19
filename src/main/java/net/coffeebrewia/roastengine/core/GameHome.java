package net.coffeebrewia.roastengine.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Works out where the game's data lives ({@code mods/}, {@code config/}, {@code playermodel.glb}).
 *
 * <p>Running from Gradle, that is simply the working directory. Inside a packaged app there is no
 * meaningful working directory - a double-clicked app starts in {@code /} - so data goes to
 * {@code ~/RoastEngine} instead, which is also somewhere people can actually find and edit.
 *
 * <p>jpackage launchers set {@code jpackage.app-path} to the launcher binary, which is how the
 * packaged case is detected and how files bundled inside the app are located.
 */
public final class GameHome {

    private static final String PACKAGED_MARKER = "jpackage.app-path";
    /** Set by the Linux release's launcher scripts, which are not made by jpackage. */
    private static final String SCRIPT_MARKER = "roastengine.launcher";

    private GameHome() {
    }

    /** True when running from a packaged app rather than a development checkout. */
    public static boolean isPackaged() {
        return launcher() != null;
    }

    /** The launcher the game was started from in a packaged build, or null in development. */
    public static String launcher() {
        return System.getProperty(SCRIPT_MARKER, System.getProperty(PACKAGED_MARKER));
    }

    /** Where mods, config and the player model are read from and written to. */
    public static Path dataDirectory() {
        String override = System.getProperty("roastengine.home");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath();
        }
        if (isPackaged()) {
            return Path.of(System.getProperty("user.home"), "RoastEngine").toAbsolutePath();
        }
        return Path.of("").toAbsolutePath();
    }

    /**
     * The folder of files bundled inside the app (jpackage's {@code app} directory), used for
     * defaults shipped with the game. Null in a development checkout.
     */
    public static Path bundledDirectory() {
        String launcher = launcher();
        if (launcher == null) {
            return null;
        }
        // Linux release: RoastEngine/bin/RoastEngine                -> RoastEngine/app
        // macOS: RoastEngine.app/Contents/MacOS/RoastEngine -> RoastEngine.app/Contents/app
        // Windows/Linux: RoastEngine/RoastEngine(.exe)      -> RoastEngine/app
        Path launcherPath = Path.of(launcher).toAbsolutePath();
        Path parent = launcherPath.getParent();
        if (parent == null) {
            return null;
        }
        Path macOsLayout = parent.getParent() == null ? null : parent.getParent().resolve("app");
        if (macOsLayout != null && Files.isDirectory(macOsLayout)) {
            return macOsLayout;
        }
        Path simpleLayout = parent.resolve("app");
        return Files.isDirectory(simpleLayout) ? simpleLayout : null;
    }

    /** Folder inside the app holding worlds installed on first run, so a new player has one. */
    private static final String STARTER_MODS = "starter-mods";
    /** Starter mods already installed once, so one the player deletes is not put back. */
    private static final String STARTERS_DONE = ".starter-mods-installed";

    /**
     * Creates the data folders on first run and copies anything the app ships with
     * (a default {@code playermodel.glb}, starter mods) into place.
     */
    public static void prepare() {
        Path data = dataDirectory();
        try {
            Files.createDirectories(data.resolve("mods"));
            Files.createDirectories(data.resolve("config"));
        } catch (IOException e) {
            System.err.println("[Home] Could not create " + data + ": " + e.getMessage());
            return;
        }
        if (isPackaged()) {
            System.out.println("[Home] Game data: " + data);
        }

        Path bundled = bundledDirectory();
        if (bundled == null) {
            return;
        }
        copyIfMissing(bundled.resolve("playermodel.glb"), data.resolve("playermodel.glb"));
        installStarterMods(bundled.resolve(STARTER_MODS), data.resolve("mods"));
    }

    /**
     * Copies each bundled starter mod into {@code mods/} the first time the game sees it. A new
     * player then has a world to play and to pick on a multiplayer server; a starter they later
     * delete stays deleted.
     */
    private static void installStarterMods(Path starters, Path mods) {
        if (!Files.isDirectory(starters)) {
            return;
        }
        Path doneFile = mods.resolve(STARTERS_DONE);
        try {
            java.util.Set<String> done = Files.isRegularFile(doneFile)
                    ? new java.util.HashSet<>(Files.readAllLines(doneFile)) : new java.util.HashSet<>();
            boolean changed = false;
            try (var folders = Files.list(starters)) {
                for (Path starter : folders.filter(Files::isDirectory).toList()) {
                    String name = starter.getFileName().toString();
                    if (done.contains(name)) {
                        continue;
                    }
                    Path target = mods.resolve(name);
                    if (!Files.exists(target)) {
                        copyTree(starter, target);
                        System.out.println("[Home] Installed starter mod " + name);
                    }
                    done.add(name);
                    changed = true;
                }
            }
            if (changed) {
                Files.write(doneFile, done.stream().sorted().toList());
            }
        } catch (IOException e) {
            System.err.println("[Home] Could not install starter mods: " + e.getMessage());
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination);
                }
            }
        }
    }

    private static void copyIfMissing(Path source, Path target) {
        if (!Files.isRegularFile(source) || Files.exists(target)) {
            return;
        }
        try {
            Files.copy(source, target);
            System.out.println("[Home] Installed default " + target.getFileName());
        } catch (IOException e) {
            System.err.println("[Home] Could not copy " + source + ": " + e.getMessage());
        }
    }
}

package net.coffeebrewia.roastengine.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Starts RoastEngine Creator as a separate process.
 *
 * <p>The Creator is its own Gradle module that depends on the engine, so the game cannot
 * reference it directly without a dependency cycle. Launching it as a process keeps the two
 * apps independent (and GLFW needs its own {@code -XstartOnFirstThread} process on macOS anyway).
 *
 * <p>Order of preference:
 * <ol>
 *   <li>{@code -Droastengine.creatorCommand=...} if set</li>
 *   <li>the Gradle wrapper: {@code ./gradlew :Creator:creator} - slower to start, but always
 *       runs the current sources rather than a stale build</li>
 *   <li>an installed distribution: {@code Creator/build/install/Creator/bin/Creator}</li>
 * </ol>
 */
public final class CreatorLauncher {

    private CreatorLauncher() {
    }

    /** @return null on success, or a human-readable reason it could not start */
    public static String launch() {
        Path workingDir = Path.of("").toAbsolutePath();

        String override = System.getProperty("roastengine.creatorCommand");
        if (override != null && !override.isBlank()) {
            return start(workingDir, override.split("\\s+"));
        }

        // In a packaged app the Creator ships as a second launcher next to the game's own.
        Path sibling = packagedCreatorLauncher();
        if (sibling != null) {
            return start(sibling.getParent(), new String[]{sibling.toString()});
        }

        Path wrapper = workingDir.resolve(isWindows() ? "gradlew.bat" : "gradlew");
        if (Files.isRegularFile(wrapper)) {
            return start(workingDir, new String[]{wrapper.toString(), ":Creator:creator", "--console=plain"});
        }

        Path dist = workingDir.resolve("Creator/build/install/Creator/bin/Creator");
        if (Files.isExecutable(dist)) {
            return start(workingDir, new String[]{dist.toString()});
        }
        return isWindows()
                ? "Could not find the Creator. Open it with bin\\RoastEngine Creator.bat"
                : "Could not find the Creator. Run it with: ./gradlew :Creator:creator";
    }

    /** The "RoastEngine Creator" launcher inside the packaged app, or null when not packaged. */
    private static Path packagedCreatorLauncher() {
        String launcher = GameHome.launcher();
        if (launcher == null) {
            return null;
        }
        Path dir = Path.of(launcher).toAbsolutePath().getParent();
        if (dir == null) {
            return null;
        }
        for (String name : creatorLauncherNames()) {
            Path creator = dir.resolve(name);
            // A .bat is a regular file Windows will happily run; isExecutable() is about
            // permissions the shipped scripts do not carry there.
            if (Files.isRegularFile(creator) && (isWindows() || Files.isExecutable(creator))) {
                return creator;
            }
        }
        return null;
    }

    /** What the Creator's launcher is called next to the game's own, per system. */
    private static String[] creatorLauncherNames() {
        return isWindows()
                ? new String[]{"RoastEngine Creator.bat", "RoastEngine Creator.exe"}
                : new String[]{"RoastEngine Creator"};
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static String start(Path workingDir, String[] command) {
        try {
            new ProcessBuilder(command)
                    .directory(workingDir.toFile())
                    // Let the Creator's output go to this terminal; it is a separate window.
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectInput(new File(isWindows() ? "NUL" : "/dev/null"))
                    .start();
            System.out.println("[Creator] Launching: " + String.join(" ", command));
            return null;
        } catch (IOException e) {
            System.err.println("[Creator] Launch failed: " + e.getMessage());
            return "Could not start the Creator: " + e.getMessage();
        }
    }
}

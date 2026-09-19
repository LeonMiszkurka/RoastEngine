package net.coffeebrewia.roastengine.creator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Remembers recently opened projects in {@code ~/.roastengine/creator-recent.txt}. */
public final class RecentProjects {

    private static final int MAX_ENTRIES = 8;
    private static final Path FILE = Path.of(System.getProperty("user.home"), ".roastengine", "creator-recent.txt");

    private RecentProjects() {
    }

    /** Existing project folders, most recent first. */
    public static List<Path> load() {
        List<Path> paths = new ArrayList<>();
        if (!Files.isRegularFile(FILE)) {
            return paths;
        }
        try {
            for (String line : Files.readAllLines(FILE)) {
                if (line.isBlank()) {
                    continue;
                }
                Path path = Path.of(line.trim());
                if (Files.isDirectory(path) && !paths.contains(path)) {
                    paths.add(path);
                }
            }
        } catch (IOException e) {
            System.err.println("[Creator] Could not read recent projects: " + e.getMessage());
        }
        return paths;
    }

    public static void remember(Path projectRoot) {
        List<Path> paths = load();
        paths.remove(projectRoot);
        paths.add(0, projectRoot);
        if (paths.size() > MAX_ENTRIES) {
            paths = paths.subList(0, MAX_ENTRIES);
        }
        try {
            Files.createDirectories(FILE.getParent());
            Files.write(FILE, paths.stream().map(Path::toString).toList());
        } catch (IOException e) {
            System.err.println("[Creator] Could not save recent projects: " + e.getMessage());
        }
    }
}

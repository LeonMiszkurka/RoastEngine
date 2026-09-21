package net.coffeebrewia.roastengine.modding;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Manages the local {@code mods/} directory:
 * <pre>
 * mods/
 *   .downloads/          temporary zip downloads
 *   .staging/            extraction area (moved into place atomically when complete)
 *   my-cool-mod/
 *     mod.json           optional author manifest: {"name": "...", "version": "..."}
 *     roast-mod.json     written by RoastEngine for mods installed from mod.io
 *     ...                mod content
 * </pre>
 */
public final class ModManager {

    public static final String AUTHOR_MANIFEST = "mod.json";
    public static final String INSTALL_MANIFEST = "roast-mod.json";

    /** Guards against zip bombs: max total extracted size per mod. */
    private static final long MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024;

    private final Path modsDirectory;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final List<LocalMod> installed = new CopyOnWriteArrayList<>();

    /** Where the list of enabled APIs and mods is kept. */
    private final Path enabledFile;

    public ModManager(Path modsDirectory) {
        this.modsDirectory = modsDirectory;
        this.enabledFile = modsDirectory.resolve(".enabled-mods.txt");
    }

    public Path modsDirectory() {
        return modsDirectory;
    }

    public Path downloadsDirectory() {
        return modsDirectory.resolve(".downloads");
    }

    public void ensureDirectories() throws IOException {
        Files.createDirectories(modsDirectory);
        Files.createDirectories(downloadsDirectory());
    }

    // ---------------------------------------------------------------------
    // Scanning
    // ---------------------------------------------------------------------

    /** Scans the mods folder and refreshes {@link #installedMods()}. Thread-safe. */
    public List<LocalMod> scan() throws IOException {
        ensureDirectories();
        List<LocalMod> found = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDirectory, Files::isDirectory)) {
            for (Path dir : stream) {
                if (dir.getFileName().toString().startsWith(".")) {
                    continue; // internal folders
                }
                found.add(readLocalMod(dir));
            }
        }
        found.sort(Comparator.comparing(m -> m.name().toLowerCase()));
        installed.clear();
        installed.addAll(found);
        System.out.println("[ModManager] Found " + found.size() + " mod(s) in " + modsDirectory);
        for (LocalMod mod : found) {
            System.out.println("  - " + mod.name() + " (" + mod.version() + ")");
        }
        return List.copyOf(found);
    }

    private LocalMod readLocalMod(Path dir) {
        String name = dir.getFileName().toString();
        String version = "unknown";
        String type = "mod";
        long modIoId = 0;
        long modfileId = 0;

        // The author's own manifest supplies version and type...
        JsonObject author = readJson(dir.resolve(AUTHOR_MANIFEST));
        if (author != null) {
            if (author.has("name")) name = author.get("name").getAsString();
            if (author.has("version")) version = author.get("version").getAsString();
            // RoastEngine Creator writes the mod type into mod.json.
            if (author.has("type")) type = author.get("type").getAsString();
        }
        // ...but for a mod installed from mod.io, its listing name is what the browser shows,
        // so that name wins to keep the two views consistent.
        JsonObject install = readJson(dir.resolve(INSTALL_MANIFEST));
        if (install != null) {
            if (install.has("name")) name = install.get("name").getAsString();
            if (install.has("modIoId")) modIoId = install.get("modIoId").getAsLong();
            if (install.has("modfileId")) modfileId = install.get("modfileId").getAsLong();
        }
        return new LocalMod(dir, name, version, type, modIoId, modfileId);
    }

    private static JsonObject readJson(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            System.err.println("[ModManager] Ignoring invalid manifest " + file + ": " + e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Enabled state
    // ---------------------------------------------------------------------

    /**
     * APIs and mods do nothing until the player enables them; worlds are simply played. The
     * enabled set is stored by folder name in {@code enabled-mods.txt} beside the mods folder's
     * config, so it survives restarts and reinstalls.
     */
    public synchronized boolean isEnabled(LocalMod mod) {
        return mod.isWorld() || enabledKeys().contains(mod.key());
    }

    public synchronized void setEnabled(LocalMod mod, boolean enabled) {
        java.util.Set<String> keys = enabledKeys();
        if (enabled) {
            keys.add(mod.key());
        } else {
            keys.remove(mod.key());
        }
        try {
            Files.createDirectories(enabledFile.getParent());
            Files.write(enabledFile, keys.stream().sorted().toList());
        } catch (IOException e) {
            System.err.println("[ModManager] Could not save enabled mods: " + e.getMessage());
        }
        System.out.println("[ModManager] " + (enabled ? "Enabled " : "Disabled ") + mod.name());
    }

    private java.util.Set<String> enabledKeys() {
        java.util.Set<String> keys = new java.util.TreeSet<>();
        if (Files.isRegularFile(enabledFile)) {
            try {
                for (String line : Files.readAllLines(enabledFile)) {
                    if (!line.isBlank()) {
                        keys.add(line.trim());
                    }
                }
            } catch (IOException e) {
                System.err.println("[ModManager] Could not read enabled mods: " + e.getMessage());
            }
        }
        return keys;
    }

    /** Installed mods that are not worlds - what the in-game "mods" counter shows. */
    public List<LocalMod> addons() {
        return installed.stream().filter(mod -> !mod.isWorld()).toList();
    }

    /** Enabled API mods, in name order. */
    public List<LocalMod> enabledApis() {
        return installed.stream().filter(LocalMod::isApi).filter(this::isEnabled).toList();
    }

    public List<LocalMod> installedMods() {
        return List.copyOf(installed);
    }

    public boolean isInstalled(long modIoId) {
        return installed.stream().anyMatch(m -> m.modIoId() == modIoId);
    }

    // ---------------------------------------------------------------------
    // Installation
    // ---------------------------------------------------------------------

    /**
     * Extracts a downloaded zip into {@code mods/<nameId>/}, replacing any previous version,
     * then deletes the zip. Extraction happens in a staging folder first, so a failed
     * install never leaves a half-written mod behind.
     */
    public LocalMod install(ModInfo mod, Path zipFile) throws IOException {
        String folderName = safeFolderName(mod.nameId());
        Path target = modsDirectory.resolve(folderName);
        Path staging = modsDirectory.resolve(".staging").resolve(folderName);

        deleteRecursively(staging);
        Files.createDirectories(staging);
        try {
            extractZip(zipFile, staging);

            JsonObject manifest = new JsonObject();
            manifest.addProperty("name", mod.name());
            manifest.addProperty("nameId", mod.nameId());
            manifest.addProperty("modIoId", mod.id());
            manifest.addProperty("modfileId", mod.modfileId());
            manifest.addProperty("author", mod.author());
            manifest.addProperty("installedAt", java.time.Instant.now().toString());
            Files.writeString(staging.resolve(INSTALL_MANIFEST), gson.toJson(manifest));

            deleteRecursively(target);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            deleteRecursively(staging);
            throw e;
        } finally {
            Files.deleteIfExists(zipFile);
        }

        scan();
        return installed.stream()
                .filter(m -> m.folder().equals(target))
                .findFirst()
                .orElseThrow(() -> new IOException("Installed mod not found after scan"));
    }

    private static void extractZip(Path zipFile, Path destination) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        long totalBytes = 0;
        byte[] buffer = new byte[64 * 1024];

        try (InputStream fileIn = Files.newInputStream(zipFile);
             ZipInputStream zip = new ZipInputStream(fileIn)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path out = root.resolve(entry.getName()).normalize();
                // Zip-slip protection: refuse entries that escape the destination folder.
                if (!out.startsWith(root)) {
                    throw new IOException("Blocked unsafe zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (var os = Files.newOutputStream(out)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        totalBytes += read;
                        if (totalBytes > MAX_EXTRACTED_BYTES) {
                            throw new IOException("Mod archive exceeds size limit");
                        }
                        os.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    /** Deletes an installed mod's folder and forgets it was enabled. */
    public void uninstall(LocalMod mod) throws IOException {
        deleteRecursively(mod.folder());
        setEnabled(mod, false);
        scan();
        System.out.println("[Mods] Removed " + mod.name());
    }

    /**
     * Installs a mod straight from a zip on this computer - an <b>external mod</b>: one that is
     * not on mod.io, such as a hack client or something a friend sent. The zip is the same shape
     * the Creator exports, and the original file is left where it is.
     *
     * <p>External mods have no mod.io id, so other players can't be sent them automatically; the
     * world picker says as much when one is chosen for a multiplayer session.
     */
    public LocalMod installExternal(Path zipFile) throws IOException {
        String fileName = zipFile.getFileName().toString();
        String stem = fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")
                ? fileName.substring(0, fileName.length() - 4) : fileName;
        Path staging = modsDirectory.resolve(".staging").resolve(safeFolderName(stem));
        deleteRecursively(staging);
        Files.createDirectories(staging);
        try {
            extractZip(zipFile, staging);
            // The mod's own mod.json names it; the file name is the fallback.
            JsonObject modJson = readJson(staging.resolve(AUTHOR_MANIFEST));
            String name = modJson.has("name") ? modJson.get("name").getAsString() : stem;
            String nameId = modJson.has("nameId") ? modJson.get("nameId").getAsString() : stem;

            JsonObject manifest = new JsonObject();
            manifest.addProperty("name", name);
            manifest.addProperty("nameId", nameId);
            manifest.addProperty("modIoId", 0);
            manifest.addProperty("modfileId", 0);
            manifest.addProperty("author", modJson.has("author") ? modJson.get("author").getAsString() : "unknown");
            manifest.addProperty("external", true);
            manifest.addProperty("installedFrom", fileName);
            manifest.addProperty("installedAt", java.time.Instant.now().toString());
            Files.writeString(staging.resolve(INSTALL_MANIFEST), gson.toJson(manifest));

            Path target = modsDirectory.resolve(safeFolderName(nameId));
            deleteRecursively(target);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            scan();
            return installed.stream().filter(m -> m.folder().equals(target)).findFirst()
                    .orElseThrow(() -> new IOException("Installed mod not found after scan"));
        } catch (IOException | RuntimeException e) {
            deleteRecursively(staging);
            throw e;
        }
    }

    private static String safeFolderName(String nameId) {
        String cleaned = nameId == null ? "" : nameId.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.isBlank() || cleaned.startsWith(".")) {
            cleaned = "mod_" + cleaned;
        }
        return cleaned;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

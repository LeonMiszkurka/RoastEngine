package net.coffeebrewia.roastengine.creator.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.coffeebrewia.roastengine.creator.project.CreatorProject;
import net.coffeebrewia.roastengine.modding.scene.SceneObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs a project into a mod zip ready to upload to mod.io.
 *
 * <pre>
 * hello-world-1.0.0.zip
 *   mod.json         name, version, type, object/asset counts
 *   scene.json       the scene (omitted for API mods)
 *   assets/          only the models the scene actually uses
 *   scripts/         Python scripts referenced by objects
 *   shaders/         a shader pack, when the project has one
 * </pre>
 *
 * The layout matches what {@code ModManager} extracts into {@code mods/&lt;mod&gt;/}.
 */
public final class ModExporter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ModExporter() {
    }

    public static Path export(CreatorProject project, String fileName, ModType type, String version)
            throws IOException {
        String stem = CreatorProject.sanitize(CreatorProject.stripExtension(fileName)).replace(' ', '-');
        String safeVersion = version.isBlank() ? "1.0.0" : version.trim();
        Path target = project.exportsDir().resolve(stem + "-" + safeVersion + ".zip");
        Files.createDirectories(project.exportsDir());

        boolean includeScene = type != ModType.API && project.type().hasScene();
        Set<String> usedAssets = new HashSet<>();
        Set<String> usedScripts = new HashSet<>();
        for (SceneObject object : project.scene().objects) {
            if (!object.asset.isBlank()) {
                usedAssets.add(object.asset);
            }
            if (!object.script.isBlank()) {
                usedScripts.add(object.script);
            }
        }

        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out)) {

            writeString(zip, "mod.json", GSON.toJson(
                    manifest(project, stem, type, safeVersion, includeScene ? usedAssets.size() : 0)));

            if (includeScene) {
                writeString(zip, "scene.json", GSON.toJson(project.scene()));
                for (String asset : usedAssets) {
                    writeFile(zip, "assets/" + asset, project.assetsDir().resolve(asset));
                }
            }

            // A shader pack travels with any export; the engine finds it by shaders/composite.frag.
            copyDirectory(zip, project.shadersDir(), "shaders/");

            // API mods ship every script; other types only the ones objects reference.
            if (type == ModType.API) {
                copyDirectory(zip, project.scriptsDir(), "scripts/");
            } else {
                for (String script : usedScripts) {
                    writeFile(zip, "scripts/" + script, project.scriptsDir().resolve(script));
                }
            }
        }
        return target;
    }

    private static JsonObject manifest(CreatorProject project, String stem, ModType type,
                                       String version, int assetCount) {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("name", project.name());
        manifest.addProperty("nameId", stem);
        manifest.addProperty("version", version);
        manifest.addProperty("type", type.id());
        manifest.addProperty("engine", "RoastEngine 0.1");
        manifest.addProperty("createdWith", "RoastEngine Creator");
        manifest.addProperty("exportedAt", Instant.now().toString());
        manifest.addProperty("objectCount", project.scene().objects.size());
        manifest.addProperty("assetCount", assetCount);
        return manifest;
    }

    private static void writeString(ZipOutputStream zip, String entry, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void writeFile(ZipOutputStream zip, String entry, Path source) throws IOException {
        if (!Files.isRegularFile(source)) {
            System.err.println("[Export] Skipping missing file: " + source);
            return;
        }
        zip.putNextEntry(new ZipEntry(entry));
        Files.copy(source, zip);
        zip.closeEntry();
    }

    private static void copyDirectory(ZipOutputStream zip, Path dir, String prefix) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, Files::isRegularFile)) {
            for (Path file : stream) {
                writeFile(zip, prefix + file.getFileName(), file);
            }
        }
    }
}

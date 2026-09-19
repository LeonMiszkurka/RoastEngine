package net.coffeebrewia.roastengine.creator.project;

import com.google.gson.Gson;
import net.coffeebrewia.roastengine.modding.scene.Scene;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A Creator project on disk:
 *
 * <pre>
 * MyProject/
 *   project.json        name, type and format version
 *   scene.json          the placed objects (world and mod projects only)
 *   assets/             imported .obj / .glb models
 *   scripts/            Python scripts objects can reference
 *   exports/            zips produced by File &gt; Export as
 * </pre>
 *
 * <p>The {@link ProjectType} decides which editor opens. An API project has no scene, so it
 * never writes {@code scene.json}.
 */
public final class CreatorProject {

    public static final String PROJECT_FILE = "project.json";
    public static final String SCENE_FILE = "scene.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Contents of {@code project.json}. */
    public static final class Meta {
        public String name = "Untitled";
        /** "world", "mod" or "api"; absent in projects made before types existed. */
        public String type = ProjectType.WORLD.id();
        public int formatVersion = 2;
        public String createdWith = "RoastEngine Creator 0.1";
    }

    private final Path root;
    private final Meta meta;
    private Scene scene;
    private boolean dirty;

    private CreatorProject(Path root, Meta meta, Scene scene) {
        this.root = root;
        this.meta = meta;
        this.scene = scene;
    }

    // ---------------------------------------------------------------------
    // Create / open / save
    // ---------------------------------------------------------------------

    /** Creates {@code parent/<name>/} with the standard folder layout. */
    public static CreatorProject create(Path parent, String name, ProjectType type) throws IOException {
        String folder = sanitize(name);
        Path root = parent.resolve(folder);
        if (Files.isDirectory(root)) {
            try (DirectoryStream<Path> existing = Files.newDirectoryStream(root)) {
                if (existing.iterator().hasNext()) {
                    throw new IOException("Folder already exists and is not empty: " + root);
                }
            }
        }
        Meta meta = new Meta();
        meta.name = name.isBlank() ? folder : name.trim();
        meta.type = type.id();

        CreatorProject project = new CreatorProject(root, meta, new Scene());
        Files.createDirectories(project.assetsDir());
        Files.createDirectories(project.scriptsDir());
        Files.createDirectories(project.exportsDir());
        if (type == ProjectType.SHADERS) {
            project.seedShaderPack();
        }
        project.save();
        return project;
    }

    /** Opens an existing project folder. */
    public static CreatorProject open(Path root) throws IOException {
        Path projectFile = root.resolve(PROJECT_FILE);
        if (!Files.isRegularFile(projectFile)) {
            throw new IOException("Not a RoastEngine project (no " + PROJECT_FILE + "): " + root);
        }
        Meta meta;
        try (Reader reader = Files.newBufferedReader(projectFile)) {
            meta = GSON.fromJson(reader, Meta.class);
        }
        if (meta == null) {
            meta = new Meta();
        }

        Scene scene = new Scene();
        Path sceneFile = root.resolve(SCENE_FILE);
        if (Files.isRegularFile(sceneFile)) {
            try (Reader reader = Files.newBufferedReader(sceneFile)) {
                Scene loaded = GSON.fromJson(reader, Scene.class);
                if (loaded != null) {
                    scene = loaded;
                }
            }
        }
        scene.normalize();

        CreatorProject project = new CreatorProject(root, meta, scene);
        Files.createDirectories(project.assetsDir());
        Files.createDirectories(project.scriptsDir());
        Files.createDirectories(project.exportsDir());
        return project;
    }

    public void save() throws IOException {
        Files.createDirectories(root);
        try (Writer writer = Files.newBufferedWriter(root.resolve(PROJECT_FILE))) {
            GSON.toJson(meta, writer);
        }
        // API and shader projects have no scene, so they never leave a stray scene.json behind.
        if (type().hasScene()) {
            try (Writer writer = Files.newBufferedWriter(root.resolve(SCENE_FILE))) {
                GSON.toJson(scene, writer);
            }
        }
        dirty = false;
    }

    // ---------------------------------------------------------------------
    // Shader packs
    // ---------------------------------------------------------------------

    /** The files a new shader project starts with: a complete, working pack to change. */
    private static final String[] SHADER_TEMPLATE = {"pack.json", "bright.frag", "blur.frag", "composite.frag"};

    /**
     * Writes a working pack into {@code shaders/}, so a new shader project opens on a finished
     * look rather than a blank screen - the quickest way to learn what each file does is to
     * change one and watch the preview.
     */
    private void seedShaderPack() throws IOException {
        Files.createDirectories(shadersDir());
        for (String file : SHADER_TEMPLATE) {
            Path target = shadersDir().resolve(file);
            if (Files.exists(target)) {
                continue;
            }
            try (var in = CreatorProject.class.getResourceAsStream("/templates/shaders/" + file)) {
                if (in == null) {
                    throw new IOException("Creator is missing its shader template " + file);
                }
                String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (file.equals("pack.json")) {
                    // The pack is named after the project, not after the template it came from.
                    text = text.replaceFirst("\"name\"\\s*:\\s*\"[^\"]*\"",
                            java.util.regex.Matcher.quoteReplacement(
                                    "\"name\": \"" + meta.name.replace("\"", "") + "\""));
                }
                Files.writeString(target, text);
            }
        }
    }

    /** The GLSL files in {@code shaders/}, pack.json first, so the editor lists them in order. */
    public List<String> listShaderFiles() {
        List<String> files = listFiles(shadersDir(), ".json", ".frag", ".vert", ".glsl");
        files.sort((a, b) -> a.equals("pack.json") ? -1 : b.equals("pack.json") ? 1 : a.compareToIgnoreCase(b));
        return files;
    }

    // ---------------------------------------------------------------------
    // Assets and scripts
    // ---------------------------------------------------------------------

    /**
     * Copies a model into the project's {@code assets/} folder.
     *
     * @return the file name to store in {@link SceneObject#asset}
     */
    public String importModel(Path source) throws IOException {
        String fileName = source.getFileName().toString();
        Path target = assetsDir().resolve(fileName);
        // Don't silently overwrite a different model with the same name.
        int counter = 1;
        String stem = stripExtension(fileName);
        String ext = extension(fileName);
        while (Files.exists(target) && !sameContent(source, target)) {
            target = assetsDir().resolve(stem + "_" + counter++ + ext);
        }
        Files.createDirectories(assetsDir());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        markDirty();
        return target.getFileName().toString();
    }

    public List<String> listAssets() {
        return listFiles(assetsDir(), ".obj", ".glb", ".gltf", ".fbx", ".dae", ".stl", ".ply");
    }

    public List<String> listScripts() {
        return listFiles(scriptsDir(), ".py");
    }

    /** Creates an empty Python script with a small template. */
    public String createScript(String name) throws IOException {
        String fileName = sanitize(stripExtension(name)) + ".py";
        Path file = scriptsDir().resolve(fileName);
        if (!Files.exists(file)) {
            Files.createDirectories(scriptsDir());
            Files.writeString(file, """
                    # RoastEngine mod script
                    #
                    # Attach this file to an object in the Creator inspector.
                    # Scripting is not wired up to the runtime yet - this is a placeholder
                    # so mods can ship their scripts already.

                    def on_start(obj):
                        print("start:", obj)


                    def on_update(obj, delta):
                        pass


                    def on_touch(obj, player):
                        pass
                    """);
        }
        return fileName;
    }

    private static List<String> listFiles(Path dir, String... extensions) {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return names;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, Files::isRegularFile)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                for (String ext : extensions) {
                    if (lower.endsWith(ext)) {
                        names.add(name);
                        break;
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[Creator] Could not list " + dir + ": " + e.getMessage());
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    private static boolean sameContent(Path a, Path b) {
        try {
            return Files.size(a) == Files.size(b) && java.util.Arrays.equals(
                    Files.readAllBytes(a), Files.readAllBytes(b));
        } catch (IOException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------------

    public Path root() {
        return root;
    }

    public Path assetsDir() {
        return root.resolve("assets");
    }

    public Path scriptsDir() {
        return root.resolve("scripts");
    }

    public Path exportsDir() {
        return root.resolve("exports");
    }

    public Path shadersDir() {
        return root.resolve("shaders");
    }

    public String name() {
        return meta.name;
    }

    public ProjectType type() {
        return ProjectType.byId(meta.type);
    }

    public Scene scene() {
        return scene;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markDirty() {
        dirty = true;
    }

    public static String sanitize(String name) {
        String cleaned = name == null ? "" : name.trim().replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        return cleaned.isBlank() ? "untitled" : cleaned;
    }

    public static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    public static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot) : "";
    }
}

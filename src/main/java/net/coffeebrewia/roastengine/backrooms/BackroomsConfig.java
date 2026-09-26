package net.coffeebrewia.roastengine.backrooms;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What makes a world a Backrooms-style level set: its {@code backrooms/level.json}.
 *
 * <pre>
 * {
 *   "name": "The Backrooms",
 *   "menu": { "title": "THE BACKROOMS", "subtitle": "...", "playLabel": "Enter",
 *             "intro": "video/BK_INTRO.mp4", "music": "audio/theme.mp3" },
 *   "monster": { "name": "The Entity", "object": "Entity", "speed": 3.1, "chaseSpeed": 4.3,
 *                "sightRange": 26, "sightAngle": 75, "hearingRange": 22, "killRange": 1.1,
 *                "giveUpSeconds": 7 },
 *   "levels": [
 *     { "id": "level0", "name": "Level 0 - The Lobby", "description": "...",
 *       "originX": -400, "cellSize": 3.0, "height": 3.2,
 *       "spawn": [x, y, z], "spawnYaw": 90, "monsterSpawn": [x, y, z],
 *       "menuView": { "eye": [x, y, z], "yaw": 45 },
 *       "grid": ["###...", "#....."] }
 *   ]
 * }
 * </pre>
 *
 * <p>Each level may name its own {@code monster}: anything it leaves out is taken from the
 * world's monster block above, so a level that only wants a faster one says just that. The
 * {@code object} is the model in the scene that gets moved about as the monster walks.
 *
 * <p>A level's {@code grid} is the map the monster walks on, and {@code originX} says where in
 * the world it sits - the levels are laid out far apart in one scene, so changing level is a
 * teleport rather than a load. {@code cellSize} is the width of one square of that grid.
 *
 * <p>The intro and music paths are relative to the mod folder. Both are optional: no video means
 * the level starts the moment Play is pressed, and no music means the home screen falls back to
 * the engine's own fluorescent hum. Music may be an .mp3 or an .ogg.
 */
public record BackroomsConfig(String name, Menu menu, Monster monster, List<LevelConfig> levels) {

    /** The file whose presence makes a world a Backrooms level set. */
    public static final String FILE = "backrooms/level.json";

    /** The home screen's wording, its music, and the video Play leads into. */
    public record Menu(String title, String subtitle, String playLabel, String intro, String music) {
    }

    /**
     * The thing in the level with you, and how good it is at finding you.
     *
     * @param object the model in the scene it moves about - used as it stands, or hidden in
     *               favour of {@code model} when that loads
     * @param model  an optional rigged .glb, relative to the mod folder. With one, the creature
     *               is drawn animated, playing whichever of {@code idle}, {@code walk},
     *               {@code chase} and {@code attack} suits what it is doing.
     */
    public record Monster(String name, String object, String model, float speed, float chaseSpeed,
                          float sightRange, float sightAngleDegrees, float hearingRange,
                          float killRange, float giveUpSeconds) {
    }

    /** One level: where it is, how it is laid out, and where everything starts. */
    public record LevelConfig(String id, String name, String description, float originX,
                              float squareSize, float height, Vector3f spawn, float spawnYawDegrees,
                              Vector3f monsterSpawn, Vector3f menuEye, float menuYawDegrees,
                              Monster monster, List<String> grid) {

        /** The walkable map of this level. */
        public Maze maze() {
            return new Maze(grid, originX, squareSize);
        }
    }

    /** The world's Backrooms file, or null when none of its mods ship one. */
    public static BackroomsConfig find(List<Path> modFolders) {
        for (Path folder : modFolders) {
            Path file = folder.resolve(FILE);
            if (Files.isRegularFile(file)) {
                try {
                    return load(file);
                } catch (IOException | RuntimeException e) {
                    System.err.println("[Backrooms] Could not read " + file + ": " + e.getMessage());
                    return null;
                }
            }
        }
        return null;
    }

    public static BackroomsConfig load(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return parse(JsonParser.parseReader(reader).getAsJsonObject());
        }
    }

    static BackroomsConfig parse(JsonObject json) {
        JsonObject menu = object(json, "menu");
        Menu menuConfig = new Menu(
                text(menu, "title", text(json, "name", "THE BACKROOMS")),
                text(menu, "subtitle", ""),
                text(menu, "playLabel", "Play"),
                text(menu, "intro", ""),
                text(menu, "music", ""));

        Monster monsterConfig = monster(object(json, "monster"), DEFAULT_MONSTER);

        List<LevelConfig> levels = new ArrayList<>();
        for (JsonElement element : array(json, "levels")) {
            JsonObject level = element.getAsJsonObject();
            List<String> grid = new ArrayList<>();
            for (JsonElement row : array(level, "grid")) {
                grid.add(row.getAsString());
            }
            if (grid.isEmpty()) {
                throw new IllegalArgumentException("level '" + text(level, "id", "?") + "' has no grid");
            }
            JsonObject view = object(level, "menuView");
            String id = text(level, "id", "level" + levels.size());
            levels.add(new LevelConfig(id,
                    text(level, "name", id),
                    text(level, "description", ""),
                    number(level, "originX", 0f),
                    Math.max(0.5f, number(level, "cellSize", 3f)),
                    Math.max(1f, number(level, "height", 3.2f)),
                    vector(level, "spawn"),
                    number(level, "spawnYaw", 0f),
                    vector(level, "monsterSpawn"),
                    view.has("eye") ? vector(view, "eye") : vector(level, "spawn"),
                    number(view, "yaw", 0f),
                    // A level may keep its own creature; anything it leaves out comes from the
                    // world's monster block, so a level can change one number and no more.
                    monster(object(level, "monster"), monsterConfig),
                    List.copyOf(grid)));
        }
        if (levels.isEmpty()) {
            throw new IllegalArgumentException("a Backrooms world needs at least one level");
        }
        return new BackroomsConfig(text(json, "name", "The Backrooms"), menuConfig, monsterConfig,
                List.copyOf(levels));
    }

    /** What a world says nothing about. */
    private static final Monster DEFAULT_MONSTER = new Monster("The Entity", "Entity", "",
            3f, 4.2f, 25f, 75f, 20f, 1.1f, 7f);

    /** Reads a monster block, taking anything it leaves out from {@code fallback}. */
    private static Monster monster(JsonObject json, Monster fallback) {
        return new Monster(
                text(json, "name", fallback.name()),
                text(json, "object", fallback.object()),
                text(json, "model", fallback.model()),
                Math.max(0.5f, number(json, "speed", fallback.speed())),
                Math.max(0.5f, number(json, "chaseSpeed", fallback.chaseSpeed())),
                Math.max(1f, number(json, "sightRange", fallback.sightRange())),
                Math.max(5f, Math.min(180f, number(json, "sightAngle", fallback.sightAngleDegrees()))),
                Math.max(0f, number(json, "hearingRange", fallback.hearingRange())),
                Math.max(0.3f, number(json, "killRange", fallback.killRange())),
                Math.max(0.5f, number(json, "giveUpSeconds", fallback.giveUpSeconds())));
    }

    /** The level with this id, or the first one when it is unknown - a saved option may be stale. */
    public LevelConfig levelOr(String id, LevelConfig fallback) {
        for (LevelConfig level : levels) {
            if (level.id().equals(id)) {
                return level;
            }
        }
        return fallback;
    }

    private static JsonObject object(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonObject() ? json.getAsJsonObject(key) : new JsonObject();
    }

    private static JsonArray array(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonArray() ? json.getAsJsonArray(key) : new JsonArray();
    }

    private static String text(JsonObject json, String key, String fallback) {
        return json.has(key) ? json.get(key).getAsString() : fallback;
    }

    private static float number(JsonObject json, String key, float fallback) {
        return json.has(key) ? json.get(key).getAsFloat() : fallback;
    }

    private static Vector3f vector(JsonObject json, String key) {
        if (!json.has(key)) {
            return new Vector3f();
        }
        JsonArray values = json.getAsJsonArray(key);
        return new Vector3f(values.get(0).getAsFloat(), values.get(1).getAsFloat(),
                values.get(2).getAsFloat());
    }
}

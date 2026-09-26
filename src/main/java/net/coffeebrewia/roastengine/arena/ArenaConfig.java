package net.coffeebrewia.roastengine.arena;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.coffeebrewia.roastengine.net.ArenaRules;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What makes a world an arena: its {@code arena/arena.json}.
 *
 * <pre>
 * {
 *   "name": "Gun Arena",
 *   "scoreLimit": 10, "maxHealth": 100, "respawnSeconds": 3, "matchSeconds": 300, "minPlayers": 2,
 *   "lobby": { "spawn": [0, 1.7, 0], "yaw": 180 },
 *   "maps": [
 *     { "name": "Warehouse", "description": "...",
 *       "red":  [[x, y, z, yaw], ...],
 *       "blue": [[x, y, z, yaw], ...] }
 *   ],
 *   "guns": [
 *     { "id": "rifle", "name": "Rifle", "object": "Rifle",
 *       "damage": 26, "fireRate": 9, "automatic": true, "magazine": 30, "reloadSeconds": 2.1,
 *       "range": 90, "spread": 1.2, "pellets": 1, "recoil": 0.9, "headshot": 1.5,
 *       "pitch": 1.0, "grip": [0, 0, 0] }
 *   ]
 * }
 * </pre>
 *
 * <p>Spawn points are <b>eye</b> positions, like a scene's {@code spawn}; yaw is in degrees. A
 * gun's {@code object} is the name of a model in the level - the gun on the lobby's rack - and
 * {@code grip} is the point in that model the hand closes around. Gun models are built with the
 * barrel pointing down -Z, so they point where the player looks.
 */
public record ArenaConfig(String name, int scoreLimit, int maxHealth, float respawnSeconds,
                          int matchSeconds, int minPlayers, Spawn lobby, List<ArenaMap> maps,
                          List<Gun> guns) {

    /** The file whose presence makes a world an arena. */
    public static final String FILE = "arena/arena.json";

    /** Somewhere to put a player: where their eyes go, and which way they face. */
    public record Spawn(Vector3f eye, float yawDegrees) {
    }

    public record ArenaMap(String name, String description, List<Spawn> red, List<Spawn> blue) {

        /** The spawn points for a team; red's if that team has none. */
        public List<Spawn> spawnsFor(int team) {
            List<Spawn> spawns = team == ArenaRules.BLUE ? blue : red;
            return spawns.isEmpty() ? (red.isEmpty() ? blue : red) : spawns;
        }
    }

    /**
     * One gun, and how it behaves.
     *
     * @param fireRate      shots a second
     * @param automatic     keeps firing while the button is held
     * @param spread        how far a shot can stray from the crosshair, in degrees
     * @param pellets       shots per trigger pull: 1 for most guns, more for a shotgun
     * @param recoil        how far the view kicks up per shot, in degrees
     * @param headshot      damage multiplier for a hit to the head
     * @param pitch         the bang's pitch: under 1 is a deeper gun
     */
    public record Gun(String id, String name, String description, String object, int damage,
                      float fireRate, boolean automatic, int magazine, float reloadSeconds,
                      float range, float spread, int pellets, float recoil, float headshot,
                      float pitch, Vector3f grip) {
    }

    /** The rules for the referee, taken from this file. */
    public ArenaRules.Settings settings() {
        List<String> names = new ArrayList<>();
        for (ArenaMap map : maps) {
            names.add(map.name());
        }
        return new ArenaRules.Settings(names, scoreLimit, maxHealth, respawnSeconds, matchSeconds, minPlayers);
    }

    /** The world's arena file, or null when none of its mods ship one. */
    public static ArenaConfig find(List<Path> modFolders) {
        for (Path folder : modFolders) {
            Path file = folder.resolve(FILE);
            if (Files.isRegularFile(file)) {
                try {
                    return load(file);
                } catch (IOException | RuntimeException e) {
                    System.err.println("[Arena] Could not read " + file + ": " + e.getMessage());
                    return null;
                }
            }
        }
        return null;
    }

    public static ArenaConfig load(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return parse(JsonParser.parseReader(reader).getAsJsonObject());
        }
    }

    static ArenaConfig parse(JsonObject json) {
        List<ArenaMap> maps = new ArrayList<>();
        for (JsonElement element : array(json, "maps")) {
            JsonObject map = element.getAsJsonObject();
            maps.add(new ArenaMap(text(map, "name", "Map " + (maps.size() + 1)),
                    text(map, "description", ""), spawns(map, "red"), spawns(map, "blue")));
        }
        if (maps.isEmpty()) {
            throw new IllegalArgumentException("an arena needs at least one map");
        }
        List<Gun> guns = new ArrayList<>();
        for (JsonElement element : array(json, "guns")) {
            JsonObject gun = element.getAsJsonObject();
            String id = text(gun, "id", "gun" + guns.size());
            String name = text(gun, "name", id);
            guns.add(new Gun(id, name, text(gun, "description", ""), text(gun, "object", name),
                    Math.max(1, integer(gun, "damage", 20)),
                    Math.max(0.2f, Math.min(20f, number(gun, "fireRate", 4f))),
                    gun.has("automatic") && gun.get("automatic").getAsBoolean(),
                    Math.max(1, integer(gun, "magazine", 12)),
                    Math.max(0f, number(gun, "reloadSeconds", 1.5f)),
                    Math.max(1f, number(gun, "range", 60f)),
                    Math.max(0f, number(gun, "spread", 1f)),
                    Math.max(1, Math.min(16, integer(gun, "pellets", 1))),
                    number(gun, "recoil", 1f),
                    Math.max(1f, number(gun, "headshot", 1.5f)),
                    number(gun, "pitch", 1f),
                    vector(gun, "grip")));
        }
        if (guns.isEmpty()) {
            throw new IllegalArgumentException("an arena needs at least one gun");
        }
        JsonObject lobby = json.has("lobby") ? json.getAsJsonObject("lobby") : new JsonObject();
        Spawn lobbySpawn = new Spawn(lobby.has("spawn") ? vector(lobby, "spawn") : new Vector3f(0, 1.7f, 0),
                number(lobby, "yaw", 0f));
        return new ArenaConfig(text(json, "name", "Arena"),
                integer(json, "scoreLimit", 10), integer(json, "maxHealth", 100),
                number(json, "respawnSeconds", 3f), integer(json, "matchSeconds", 300),
                integer(json, "minPlayers", 2), lobbySpawn, List.copyOf(maps), List.copyOf(guns));
    }

    /** The gun whose model is this object, by name; -1 when it is not a gun. */
    public int gunIndexForObject(String objectName) {
        for (int i = 0; i < guns.size(); i++) {
            if (guns.get(i).object().equalsIgnoreCase(objectName)) {
                return i;
            }
        }
        return -1;
    }

    private static List<Spawn> spawns(JsonObject map, String team) {
        List<Spawn> spawns = new ArrayList<>();
        for (JsonElement element : array(map, team)) {
            JsonArray point = element.getAsJsonArray();
            spawns.add(new Spawn(new Vector3f(point.get(0).getAsFloat(), point.get(1).getAsFloat(),
                    point.get(2).getAsFloat()), point.size() > 3 ? point.get(3).getAsFloat() : 0f));
        }
        return List.copyOf(spawns);
    }

    private static JsonArray array(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonArray() ? json.getAsJsonArray(key) : new JsonArray();
    }

    private static String text(JsonObject json, String key, String fallback) {
        return json.has(key) ? json.get(key).getAsString() : fallback;
    }

    private static int integer(JsonObject json, String key, int fallback) {
        return json.has(key) ? json.get(key).getAsInt() : fallback;
    }

    private static float number(JsonObject json, String key, float fallback) {
        return json.has(key) ? json.get(key).getAsFloat() : fallback;
    }

    private static Vector3f vector(JsonObject json, String key) {
        if (!json.has(key)) {
            return new Vector3f();
        }
        JsonArray values = json.getAsJsonArray(key);
        return new Vector3f(values.get(0).getAsFloat(), values.get(1).getAsFloat(), values.get(2).getAsFloat());
    }
}

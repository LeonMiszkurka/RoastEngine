package net.coffeebrewia.roastengine.bypass;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A hack client: a mod that arranges the cheats the {@link BypassApi} unlocks into a menu.
 *
 * <pre>
 * hack/
 *   client.json
 * </pre>
 *
 * <pre>
 * {
 *   "name": "Roast Hack",
 *   "menuKey": "TAB",
 *   "tabs": [
 *     {"name": "Movement", "cheats": ["fly", "noclip", "speed"]},
 *     {"name": "View",     "cheats": ["thirdPerson", "fov"]}
 *   ],
 *   "binds":    {"fly": "F", "noclip": "N"},
 *   "defaults": {"fov": 90}
 * }
 * </pre>
 *
 * Anything the API does not allow is dropped when the client loads, so an old API and a greedy
 * client simply mean fewer buttons rather than a broken menu.
 */
public final class HackClient {

    /** The file whose presence marks a mod as a hack client. */
    public static final String MARKER = "hack/client.json";

    /** One page of the menu. */
    public record Tab(String name, List<String> cheats) {
    }

    private final String name;
    private final String version;
    private final int menuKey;
    private final List<Tab> tabs;
    private final Map<String, Integer> binds;
    private final Map<String, Float> defaults;

    private HackClient(String name, String version, int menuKey, List<Tab> tabs,
                       Map<String, Integer> binds, Map<String, Float> defaults) {
        this.name = name;
        this.version = version;
        this.menuKey = menuKey;
        this.tabs = tabs;
        this.binds = binds;
        this.defaults = defaults;
    }

    public static boolean isHackClient(Path modFolder) {
        return Files.isRegularFile(modFolder.resolve(MARKER));
    }

    /**
     * Reads a hack client, keeping only what {@code api} allows.
     *
     * @return the client, or null when the file is unreadable or nothing is left of it
     */
    public static HackClient load(Path modFolder, String fallbackName, BypassApi api) {
        Path file = modFolder.resolve(MARKER);
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            List<Tab> tabs = new ArrayList<>();
            int kept = 0;
            for (JsonElement element : array(json, "tabs")) {
                JsonObject tab = element.getAsJsonObject();
                List<String> cheats = new ArrayList<>();
                for (JsonElement id : array(tab, "cheats")) {
                    String cheat = id.getAsString();
                    if (api.allows(cheat)) {
                        cheats.add(cheat);
                    }
                }
                kept += cheats.size();
                if (!cheats.isEmpty()) {
                    tabs.add(new Tab(string(tab, "name", "Cheats"), List.copyOf(cheats)));
                }
            }
            if (tabs.isEmpty()) {
                System.err.println("[Bypass] " + fallbackName + " has no cheats this Bypass API allows");
                return null;
            }

            Map<String, Integer> binds = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : object(json, "binds").entrySet()) {
                int key = Keys.byName(entry.getValue().getAsString());
                if (key != Keys.NONE && api.allows(entry.getKey())) {
                    binds.put(entry.getKey(), key);
                }
            }
            Map<String, Float> defaults = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : object(json, "defaults").entrySet()) {
                if (api.allows(entry.getKey())) {
                    defaults.put(entry.getKey(), entry.getValue().getAsFloat());
                }
            }

            HackClient client = new HackClient(string(json, "name", fallbackName),
                    string(json, "version", "unknown"),
                    Keys.byName(string(json, "menuKey", "TAB")), List.copyOf(tabs), binds, defaults);
            System.out.println("[Bypass] Hack client " + client.name + ": " + kept + " cheat(s) on "
                    + Keys.name(client.menuKey));
            return client;
        } catch (IOException | RuntimeException e) {
            System.err.println("[Bypass] Could not read " + file + ": " + e.getMessage());
            return null;
        }
    }

    private static JsonArray array(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonArray() ? json.getAsJsonArray(key) : new JsonArray();
    }

    private static JsonObject object(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonObject() ? json.getAsJsonObject(key) : new JsonObject();
    }

    private static String string(JsonObject json, String key, String fallback) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : fallback;
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    /** The key that opens and closes the menu. */
    public int menuKey() {
        return menuKey;
    }

    public List<Tab> tabs() {
        return tabs;
    }

    /** Keys that switch a cheat on or off without opening the menu. */
    public Map<String, Integer> binds() {
        return binds;
    }

    public Map<String, Float> defaults() {
        return defaults;
    }
}

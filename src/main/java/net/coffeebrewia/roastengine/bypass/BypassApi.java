package net.coffeebrewia.roastengine.bypass;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The <b>Bypass API</b>, shipped as an API mod - the thing a hack client needs.
 *
 * <p>The engine can fly the player, walk through walls and so on ({@link Cheat}), but it will not
 * do any of it unless this API is installed and switched on. The API is a list of which of those
 * the games's mods are allowed to use:
 *
 * <pre>
 * bypass/
 *   api.json     name, version, and the cheats this version unlocks
 * </pre>
 *
 * <pre>
 * {
 *   "name": "Bypass API",
 *   "version": "1.0.0",
 *   "cheats": ["fly", "noclip", "speed", "fov"]
 * }
 * </pre>
 *
 * <p>So a new cheat reaches players by shipping a newer Bypass API, not by changing the game - and
 * a player who wants none of it simply leaves the API switched off, which turns every hack client
 * off with it. Individual <b>hack clients</b> are separate mods that run on top of this API, each
 * shipping a {@code hack/client.json}; see {@link HackClient}.
 */
public final class BypassApi {

    /** The file whose presence marks an API mod as the Bypass API. */
    public static final String MARKER = "bypass/api.json";

    private final String name;
    private final String version;
    private final Set<String> allowed;

    private BypassApi(String name, String version, Set<String> allowed) {
        this.name = name;
        this.version = version;
        this.allowed = allowed;
    }

    public static boolean isBypassApi(Path modFolder) {
        return Files.isRegularFile(modFolder.resolve(MARKER));
    }

    /** Reads the API from a mod folder, or returns null with a reason on the console. */
    public static BypassApi load(Path modFolder) {
        Path file = modFolder.resolve(MARKER);
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            Set<String> allowed = new LinkedHashSet<>();
            JsonArray cheats = json.has("cheats") && json.get("cheats").isJsonArray()
                    ? json.getAsJsonArray("cheats") : new JsonArray();
            for (JsonElement element : cheats) {
                String id = element.getAsString();
                if (Cheat.find(id) == null) {
                    // A newer API listing something this build has never heard of: skip it.
                    System.out.println("[Bypass] This version of RoastEngine has no cheat called '" + id + "'");
                } else {
                    allowed.add(id);
                }
            }
            BypassApi api = new BypassApi(
                    json.has("name") ? json.get("name").getAsString() : "Bypass API",
                    json.has("version") ? json.get("version").getAsString() : "unknown",
                    allowed);
            System.out.println("[Bypass] " + api.name + " " + api.version + " unlocks " + allowed.size()
                    + " cheat(s)");
            return api;
        } catch (IOException | RuntimeException e) {
            System.err.println("[Bypass] Could not read " + file + ": " + e.getMessage());
            return null;
        }
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    /** True when this API version lets mods use that cheat. */
    public boolean allows(String cheatId) {
        return allowed.contains(cheatId);
    }
}

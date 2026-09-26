package net.coffeebrewia.roastengine.inventory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.coffeebrewia.roastengine.modding.LocalMod;
import net.coffeebrewia.roastengine.modding.ModManager;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The <b>PlaceHolder API</b>, shipped as an API mod: the bag the player carries things in.
 *
 * <p>Picking one thing up and putting it down again is part of the engine and needs no mod. What
 * this API adds is <em>keeping</em> things: with it installed and switched on, the player has a
 * row of slots and can carry several items at once, pick between them with the number keys and
 * put the chosen one in their hand.
 *
 * <pre>
 * placeholder/
 *   api.json     name, version, and how many slots the bag has
 * </pre>
 *
 * <pre>
 * {
 *   "name": "PlaceHolder API",
 *   "version": "1.0.0",
 *   "slots": 5
 * }
 * </pre>
 *
 * <p>Like the Bypass API, the number lives in the mod rather than in the game, so a bag of eight
 * slots is a new version of this mod instead of a new build of RoastEngine. Switch the mod off and
 * the player is back to two hands and one item.
 */
public final class PlaceHolderApi {

    /** The file whose presence marks an API mod as the PlaceHolder API. */
    public static final String MARKER = "placeholder/api.json";

    /** Without the API the player still has their hands: one item, no bag. */
    public static final int HANDS_ONLY = 1;

    /** As many slots as the hotbar can sensibly show, and as the number keys can reach. */
    public static final int MAX_SLOTS = 9;

    private final String name;
    private final String version;
    private final int slots;

    private PlaceHolderApi(String name, String version, int slots) {
        this.name = name;
        this.version = version;
        this.slots = slots;
    }

    public static boolean isPlaceHolderApi(Path modFolder) {
        return Files.isRegularFile(modFolder.resolve(MARKER));
    }

    /** The API from the player's enabled mods, or null when they have not installed it. */
    public static PlaceHolderApi fromMods(ModManager mods) {
        return mods.enabledApis().stream()
                .map(LocalMod::folder)
                .filter(PlaceHolderApi::isPlaceHolderApi)
                .map(PlaceHolderApi::load)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    /** Reads the API from a mod folder, or returns null with a reason on the console. */
    public static PlaceHolderApi load(Path modFolder) {
        Path file = modFolder.resolve(MARKER);
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            int asked = json.has("slots") ? json.get("slots").getAsInt() : 5;
            int slots = Math.max(1, Math.min(MAX_SLOTS, asked));
            if (slots != asked) {
                System.out.println("[PlaceHolder] " + asked + " slots is out of range; using " + slots);
            }
            PlaceHolderApi api = new PlaceHolderApi(
                    json.has("name") ? json.get("name").getAsString() : "PlaceHolder API",
                    json.has("version") ? json.get("version").getAsString() : "unknown",
                    slots);
            System.out.println("[PlaceHolder] " + api.name + " " + api.version + ": "
                    + slots + " slot(s)");
            return api;
        } catch (IOException | RuntimeException e) {
            System.err.println("[PlaceHolder] Could not read " + file + ": " + e.getMessage());
            return null;
        }
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    /** How many things the player can carry at once. */
    public int slots() {
        return slots;
    }
}

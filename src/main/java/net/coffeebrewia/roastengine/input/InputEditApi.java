package net.coffeebrewia.roastengine.input;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The <b>InputEdit API</b>, shipped as an API mod.
 *
 * <p>The engine only ever provides the plumbing: it polls the controllers ({@link Gamepads}) and
 * it owns the actions the game asks for ({@link InputAxis}, {@link InputButton}). What it does
 * not have is any idea what a button is <em>called</em>. That vocabulary lives here, in
 * {@code inputedit/api.json} inside the API mod:
 *
 * <pre>
 * inputedit/
 *   api.json       the names a scheme may use, and the defaults it inherits
 *   mappings.txt   optional: layouts for pads GLFW does not recognise
 * </pre>
 *
 * <p>{@code mappings.txt} is how a controller GLFW has never seen is made to work. GLFW knows the
 * shared pad layout for the controllers it ships mappings for; anything else arrives as a bare list
 * of axes and buttons that no {@code "device": "gamepad"} scheme can read. A line here - in SDL's
 * game controller database format, which is what GLFW takes - fills that gap, so a new pad is
 * supported by adding a line rather than by changing the engine. The game prints the id of any pad
 * it cannot place, which is what the line starts with.
 *
 * <p>So a controller nobody has heard of is supported by editing this file - or shipping a
 * newer InputEdit API - rather than by changing the engine. With the API not installed or not
 * enabled, no scheme loads at all and the game runs on keyboard and mouse, exactly as it does
 * with no mods.
 *
 * <p>Individual devices are separate <b>mods</b> that run on top of this API, each shipping an
 * {@code input/scheme.json}; see {@link ControlScheme}.
 */
public final class InputEditApi {

    /** The file whose presence marks an API mod as the InputEdit API. */
    public static final String MARKER = "inputedit/api.json";
    /** Optional layouts for pads GLFW does not know, in SDL's controller database format. */
    public static final String MAPPINGS = "inputedit/mappings.txt";

    private final String name;
    private final String version;
    /** Name -> index, for the axes and buttons a scheme may refer to. */
    private final Map<String, Integer> axisNames;
    private final Map<String, Integer> buttonNames;
    private final float deadzone;
    private final float lookSpeed;
    private final float lookCurve;
    /** Pad layouts this API mod ships, one SDL mapping per entry. */
    private final java.util.List<String> mappings;

    private InputEditApi(String name, String version, Map<String, Integer> axisNames,
                         Map<String, Integer> buttonNames, float deadzone, float lookSpeed,
                         float lookCurve, java.util.List<String> mappings) {
        this.mappings = mappings;
        this.name = name;
        this.version = version;
        this.axisNames = axisNames;
        this.buttonNames = buttonNames;
        this.deadzone = deadzone;
        this.lookSpeed = lookSpeed;
        this.lookCurve = lookCurve;
    }

    public static boolean isInputEditApi(Path modFolder) {
        return Files.isRegularFile(modFolder.resolve(MARKER));
    }

    public static InputEditApi load(Path modFolder, String fallbackName) throws IOException {
        Path file = modFolder.resolve(MARKER);
        JsonObject manifest;
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IOException(MARKER + " must contain a JSON object");
            }
            manifest = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException(MARKER + ": " + e.getMessage(), e);
        }

        Map<String, Integer> axisNames = readNames(manifest, "axes");
        Map<String, Integer> buttonNames = readNames(manifest, "buttons");
        if (axisNames.isEmpty() && buttonNames.isEmpty()) {
            throw new IOException(MARKER + " defines no axis or button names");
        }

        JsonObject defaults = manifest.has("defaults") && manifest.get("defaults").isJsonObject()
                ? manifest.getAsJsonObject("defaults") : new JsonObject();
        return new InputEditApi(
                manifest.has("name") ? manifest.get("name").getAsString() : fallbackName,
                manifest.has("version") ? manifest.get("version").getAsString() : "unknown",
                axisNames, buttonNames,
                readFloat(defaults, "deadzone", 0.15f),
                readFloat(defaults, "lookSpeed", 900f),
                readFloat(defaults, "lookCurve", 2f),
                readMappings(modFolder));
    }

    /**
     * The pad layouts beside {@code api.json}, if it ships any. Blank lines and {@code #} comments
     * are skipped; a bad line is GLFW's to refuse, and it says so then.
     */
    private static java.util.List<String> readMappings(Path modFolder) {
        Path file = modFolder.resolve(MAPPINGS);
        if (!Files.isRegularFile(file)) {
            return java.util.List.of();
        }
        try {
            return Files.readAllLines(file).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            System.err.println("[Input] Could not read " + MAPPINGS + ": " + e.getMessage());
            return java.util.List.of();
        }
    }

    /** The pad layouts this API mod ships, for the engine to hand to GLFW. */
    public java.util.List<String> mappings() {
        return mappings;
    }

    private static Map<String, Integer> readNames(JsonObject manifest, String key) {
        Map<String, Integer> names = new LinkedHashMap<>();
        if (!manifest.has(key) || !manifest.get(key).isJsonObject()) {
            return names;
        }
        for (Map.Entry<String, JsonElement> entry : manifest.getAsJsonObject(key).entrySet()) {
            if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
                names.put(entry.getKey().toUpperCase(Locale.ROOT), entry.getValue().getAsInt());
            }
        }
        return names;
    }

    private static float readFloat(JsonObject object, String key, float fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsFloat() : fallback;
    }

    /**
     * @return the axis index for a name, or -1 when this API does not define it. The name is read
     *         whatever its case, since the table is keyed the same way however a scheme spells it
     */
    public int axisIndex(String name) {
        return lookUp(axisNames, name);
    }

    /** @return the button index for a name, or -1 when this API does not define it. */
    public int buttonIndex(String name) {
        return lookUp(buttonNames, name);
    }

    private static int lookUp(Map<String, Integer> names, String name) {
        return name == null ? -1
                : names.getOrDefault(name.trim().toUpperCase(Locale.ROOT), -1);
    }

    public float defaultDeadzone() {
        return deadzone;
    }

    public float defaultLookSpeed() {
        return lookSpeed;
    }

    public float defaultLookCurve() {
        return lookCurve;
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }
}

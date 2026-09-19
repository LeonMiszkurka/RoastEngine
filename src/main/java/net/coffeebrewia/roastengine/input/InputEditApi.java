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
 *   api.json     the names a scheme may use, and the defaults it inherits
 * </pre>
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

    private final String name;
    private final String version;
    /** Name -> index, for the axes and buttons a scheme may refer to. */
    private final Map<String, Integer> axisNames;
    private final Map<String, Integer> buttonNames;
    private final float deadzone;
    private final float lookSpeed;
    private final float lookCurve;

    private InputEditApi(String name, String version, Map<String, Integer> axisNames,
                         Map<String, Integer> buttonNames, float deadzone, float lookSpeed, float lookCurve) {
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
                readFloat(defaults, "lookCurve", 2f));
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

    /** @return the axis index for a name, or -1 when this API does not define it. */
    public int axisIndex(String name) {
        return axisNames.getOrDefault(name, -1);
    }

    /** @return the button index for a name, or -1 when this API does not define it. */
    public int buttonIndex(String name) {
        return buttonNames.getOrDefault(name, -1);
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

package net.coffeebrewia.roastengine.input;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One way to control the game: a <b>mod that runs on the InputEdit API</b>.
 *
 * <p>The engine owns the actions ({@link InputAxis}, {@link InputButton}) and the
 * {@link InputEditApi} mod owns the names, so a scheme only has to say which part of a
 * controller produces each action. Nothing about a particular device is compiled into the
 * engine, and a scheme is ignored unless the InputEdit API is installed and enabled.
 *
 * <pre>
 * input/
 *   scheme.json
 * </pre>
 *
 * <pre>{@code
 * {
 *   "name": "Xbox Controller",
 *   "device": "gamepad",          // "gamepad" = GLFW's shared layout, "joystick" = raw indices
 *   "deadzone": 0.18,             // sticks under this are treated as centred
 *   "lookSpeed": 900,             // window points per second at full stick deflection
 *   "lookCurve": 2.0,             // >1 makes small stick movements finer
 *   "axes": {
 *     "moveX": {"axis": "LEFT_X"},
 *     "moveY": {"axis": "LEFT_Y", "invert": true},
 *     "lookX": {"axis": "RIGHT_X"},
 *     "lookY": {"axis": "RIGHT_Y"}
 *   },
 *   "buttons": {
 *     "jump": "A",
 *     "sprint": ["LEFT_THUMB", "LEFT_BUMPER"],
 *     "interact": "X",
 *     "pause": "START"
 *   }
 * }
 * }</pre>
 *
 * <p>An axis can also be built from two buttons - {@code {"positive": "DPAD_RIGHT", "negative":
 * "DPAD_LEFT"}} - which is how a d-pad or a set of pedals drives a movement axis. In
 * {@code "joystick"} mode every name is a plain index instead, for a device GLFW has no
 * mapping for.
 */
public final class ControlScheme {

    /** The file whose presence marks a mod as a control scheme. */
    public static final String MARKER = "input/scheme.json";

    /** Where one action's value comes from: a stick, a trigger, or a pair of buttons. */
    private record AxisBinding(int axis, int positive, int negative, boolean invert, float scale) {

        float read(Gamepads.Device device, float deadzone) {
            float value;
            if (axis >= 0) {
                value = device.axis(axis);
                value = Math.abs(value) < deadzone ? 0f
                        // Rescale so the axis still reaches 1 after the deadzone is cut out.
                        : Math.signum(value) * (Math.abs(value) - deadzone) / (1f - deadzone);
            } else {
                value = (device.button(positive) ? 1f : 0f) - (device.button(negative) ? 1f : 0f);
            }
            return Math.max(-1f, Math.min(1f, value * scale * (invert ? -1f : 1f)));
        }
    }

    private final String name;
    private final boolean gamepadLayout;
    private final float deadzone;
    private final float lookSpeed;
    private final float lookCurve;
    private final Map<InputAxis, AxisBinding> axes;
    private final Map<InputButton, int[]> buttons;

    private ControlScheme(String name, boolean gamepadLayout, float deadzone, float lookSpeed,
                          float lookCurve, Map<InputAxis, AxisBinding> axes, Map<InputButton, int[]> buttons) {
        this.name = name;
        this.gamepadLayout = gamepadLayout;
        this.deadzone = deadzone;
        this.lookSpeed = lookSpeed;
        this.lookCurve = lookCurve;
        this.axes = axes;
        this.buttons = buttons;
    }

    public static boolean isControlScheme(Path modFolder) {
        return Files.isRegularFile(modFolder.resolve(MARKER));
    }

    /**
     * Reads the scheme, resolving every name through the installed InputEdit API.
     *
     * @throws IOException if the JSON does not describe a usable scheme
     */
    public static ControlScheme load(Path modFolder, String fallbackName, InputEditApi api) throws IOException {
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

        String device = manifest.has("device") ? manifest.get("device").getAsString() : "gamepad";
        boolean gamepadLayout = !"joystick".equalsIgnoreCase(device);

        Map<InputAxis, AxisBinding> axes = new EnumMap<>(InputAxis.class);
        if (manifest.has("axes") && manifest.get("axes").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : manifest.getAsJsonObject("axes").entrySet()) {
                InputAxis action = InputAxis.byKey(entry.getKey());
                if (action == null) {
                    System.err.println("[Input] " + fallbackName + ": unknown axis '" + entry.getKey() + "'");
                    continue;
                }
                AxisBinding binding = readAxis(entry.getValue(), gamepadLayout, fallbackName, api);
                if (binding != null) {
                    axes.put(action, binding);
                }
            }
        }

        Map<InputButton, int[]> buttons = new EnumMap<>(InputButton.class);
        if (manifest.has("buttons") && manifest.get("buttons").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : manifest.getAsJsonObject("buttons").entrySet()) {
                InputButton action = InputButton.byKey(entry.getKey());
                if (action == null) {
                    System.err.println("[Input] " + fallbackName + ": unknown button '" + entry.getKey() + "'");
                    continue;
                }
                int[] indices = readButtons(entry.getValue(), gamepadLayout, fallbackName, api);
                if (indices.length > 0) {
                    buttons.put(action, indices);
                }
            }
        }

        if (axes.isEmpty() && buttons.isEmpty()) {
            throw new IOException(MARKER + " binds nothing - check the 'axes' and 'buttons' names");
        }
        return new ControlScheme(
                manifest.has("name") ? manifest.get("name").getAsString() : fallbackName,
                gamepadLayout,
                clamp(readFloat(manifest, "deadzone", api.defaultDeadzone()), 0f, 0.9f),
                Math.max(0f, readFloat(manifest, "lookSpeed", api.defaultLookSpeed())),
                clamp(readFloat(manifest, "lookCurve", api.defaultLookCurve()), 1f, 5f),
                axes, buttons);
    }

    private static AxisBinding readAxis(JsonElement element, boolean gamepadLayout, String modName,
                                        InputEditApi api) {
        if (element.isJsonPrimitive()) {
            int index = resolveAxis(element.getAsString(), gamepadLayout, modName, api);
            return index < 0 ? null : new AxisBinding(index, -1, -1, false, 1f);
        }
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        boolean invert = object.has("invert") && object.get("invert").getAsBoolean();
        float scale = readFloat(object, "scale", 1f);
        if (object.has("axis")) {
            int index = resolveAxis(object.get("axis").getAsString(), gamepadLayout, modName, api);
            return index < 0 ? null : new AxisBinding(index, -1, -1, invert, scale);
        }
        // Two buttons standing in for an axis, e.g. a d-pad used to walk.
        int positive = object.has("positive")
                ? resolveButton(object.get("positive").getAsString(), gamepadLayout, modName, api) : -1;
        int negative = object.has("negative")
                ? resolveButton(object.get("negative").getAsString(), gamepadLayout, modName, api) : -1;
        if (positive < 0 && negative < 0) {
            System.err.println("[Input] " + modName + ": axis needs 'axis', or 'positive'/'negative'");
            return null;
        }
        return new AxisBinding(-1, positive, negative, invert, scale);
    }

    private static int[] readButtons(JsonElement element, boolean gamepadLayout, String modName,
                                     InputEditApi api) {
        List<String> names = new ArrayList<>();
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(item -> names.add(item.getAsString()));
        } else if (element.isJsonPrimitive()) {
            names.add(element.getAsString());
        }
        return names.stream()
                .mapToInt(name -> resolveButton(name, gamepadLayout, modName, api))
                .filter(index -> index >= 0)
                .toArray();
    }

    // ---------------------------------------------------------------------
    // Name lookup - the tables live in the InputEdit API mod, not in here
    // ---------------------------------------------------------------------

    private static int resolveAxis(String raw, boolean gamepadLayout, String modName, InputEditApi api) {
        return resolve(raw, gamepadLayout, modName, "axis", api::axisIndex);
    }

    private static int resolveButton(String raw, boolean gamepadLayout, String modName, InputEditApi api) {
        return resolve(raw, gamepadLayout, modName, "button", api::buttonIndex);
    }

    private static int resolve(String raw, boolean gamepadLayout, String modName, String what,
                               java.util.function.ToIntFunction<String> byName) {
        String name = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        // A raw index works in either mode, and is the only option for an unmapped joystick.
        if (name.matches("\\d+")) {
            return Integer.parseInt(name);
        }
        if (gamepadLayout) {
            int index = byName.applyAsInt(name);
            if (index >= 0) {
                return index;
            }
        }
        System.err.println("[Input] " + modName + ": unknown " + what + " '" + raw + "'"
                + (gamepadLayout ? " - the installed InputEdit API does not define that name"
                                 : " (joystick schemes must use numbers)"));
        return -1;
    }

    private static float readFloat(JsonObject object, String key, float fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsFloat() : fallback;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------

    /** True when this scheme can drive the given device. */
    public boolean matches(Gamepads.Device device) {
        return !gamepadLayout || device.isGamepad();
    }

    /**
     * Folds one controller's current state into {@code target}.
     *
     * @param dt seconds since the last frame, so look speed is frame-rate independent
     */
    public void apply(Gamepads.Device device, float dt, InputActions target) {
        for (Map.Entry<InputAxis, AxisBinding> entry : axes.entrySet()) {
            InputAxis action = entry.getKey();
            float value = entry.getValue().read(device, deadzone);
            if (value == 0f) {
                continue;
            }
            if (action.isLook()) {
                // Sticks hold a position; the camera wants a movement. The curve keeps small
                // deflections gentle so a stick can still aim precisely.
                float curved = Math.signum(value) * (float) Math.pow(Math.abs(value), lookCurve);
                target.addAxis(action, curved * lookSpeed * dt);
            } else {
                target.addAxis(action, value);
            }
        }
        for (Map.Entry<InputButton, int[]> entry : buttons.entrySet()) {
            for (int index : entry.getValue()) {
                if (device.button(index)) {
                    target.setDown(entry.getKey());
                    break;
                }
            }
        }
    }

    public String name() {
        return name;
    }

    /** "gamepad" or "joystick" - which view of the hardware this scheme reads. */
    public String deviceKind() {
        return gamepadLayout ? "gamepad" : "joystick";
    }
}

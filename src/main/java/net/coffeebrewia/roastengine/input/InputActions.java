package net.coffeebrewia.roastengine.input;

import net.coffeebrewia.roastengine.modding.LocalMod;
import net.coffeebrewia.roastengine.modding.ModManager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.glfw.GLFW.*;

/**
 * What the player is asking for this frame, with the hardware abstracted away.
 *
 * <p>The game asks for {@code MOVE_Y} and {@code JUMP} rather than for W and Space, and this
 * class decides where those came from. Two sources are merged every frame:
 *
 * <ul>
 *   <li>the built-in keyboard and mouse mapping, which is <b>always</b> live - it is part of
 *       the engine, needs no mod, and is never replaced or switched off;</li>
 *   <li>controllers, but only once the <b>InputEdit API</b> mod is installed and enabled. That
 *       API supplies the button vocabulary; each device is then a separate mod shipping a
 *       {@link ControlScheme}. With the API missing or disabled, schemes are ignored.</li>
 * </ul>
 *
 * <p>Sources add together, so a controller and the keyboard both work at once, and unplugging
 * the controller mid-game simply stops one of them contributing.
 */
public final class InputActions {

    /** Held sprint keys; either shift or control, matching the old hard-coded behaviour. */
    private static final int[] SPRINT_KEYS = {GLFW_KEY_LEFT_SHIFT, GLFW_KEY_LEFT_CONTROL};

    private final float[] axisValues = new float[InputAxis.values().length];
    private final boolean[] down = new boolean[InputButton.values().length];
    private final boolean[] previous = new boolean[InputButton.values().length];

    /** Loaded control schemes, by mod folder, in the order they were found. */
    private final Map<Path, ControlScheme> schemes = new LinkedHashMap<>();
    /** Folders whose scheme failed to load; retried only if the set of enabled mods changes. */
    private final Set<Path> broken = new LinkedHashSet<>();
    /** The enabled InputEdit API, or null when none is installed or it is switched off. */
    private InputEditApi api;
    private Path apiFolder;
    /** Scheme mods that are enabled but have nothing to run on. */
    private int waitingForApi;
    private Set<Path> lastSeenApi = Set.of();
    private Set<Path> lastSeenSchemes = Set.of();
    private String status = "Keyboard and mouse";

    /**
     * Rebuilds this frame's state.
     *
     * @param dt seconds since the last frame, so stick-driven look is frame-rate independent
     */
    public void update(Input input, Gamepads gamepads, ModManager mods, float dt) {
        syncSchemes(mods);

        System.arraycopy(down, 0, previous, 0, down.length);
        java.util.Arrays.fill(axisValues, 0f);
        java.util.Arrays.fill(down, false);

        applyKeyboardAndMouse(input);
        applyControllers(gamepads, dt);

        for (InputAxis axis : InputAxis.values()) {
            if (!axis.isLook()) {
                axisValues[axis.ordinal()] = Math.max(-1f, Math.min(1f, axisValues[axis.ordinal()]));
            }
        }
    }

    /** The mapping the engine ships with. A scheme adds to this; it never replaces it. */
    private void applyKeyboardAndMouse(Input input) {
        addAxis(InputAxis.MOVE_Y, keyAxis(input, GLFW_KEY_W, GLFW_KEY_S));
        addAxis(InputAxis.MOVE_X, keyAxis(input, GLFW_KEY_D, GLFW_KEY_A));
        addAxis(InputAxis.LOOK_X, input.mouseDeltaX());
        addAxis(InputAxis.LOOK_Y, input.mouseDeltaY());

        if (input.isKeyDown(GLFW_KEY_SPACE)) {
            setDown(InputButton.JUMP);
        }
        if (input.isKeyDown(GLFW_KEY_E)) {
            setDown(InputButton.INTERACT);
        }
        if (input.isKeyDown(GLFW_KEY_ESCAPE)) {
            setDown(InputButton.PAUSE);
        }
        for (int key : SPRINT_KEYS) {
            if (input.isKeyDown(key)) {
                setDown(InputButton.SPRINT);
                break;
            }
        }
    }

    private void applyControllers(Gamepads gamepads, float dt) {
        if (schemes.isEmpty()) {
            return;
        }
        for (Gamepads.Device device : gamepads.connected()) {
            for (ControlScheme scheme : schemes.values()) {
                if (scheme.matches(device)) {
                    scheme.apply(device, dt, this);
                }
            }
        }
    }

    private static float keyAxis(Input input, int positive, int negative) {
        return (input.isKeyDown(positive) ? 1f : 0f) - (input.isKeyDown(negative) ? 1f : 0f);
    }

    // ---------------------------------------------------------------------
    // Written to by the sources above and by ControlScheme
    // ---------------------------------------------------------------------

    void addAxis(InputAxis axis, float value) {
        axisValues[axis.ordinal()] += value;
    }

    void setDown(InputButton button) {
        down[button.ordinal()] = true;
    }

    // ---------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------

    /** Movement axes are -1..1; look axes are this frame's movement in window points. */
    public float axis(InputAxis axis) {
        return axisValues[axis.ordinal()];
    }

    public boolean isDown(InputButton button) {
        return down[button.ordinal()];
    }

    /** True only on the frame the action started. */
    public boolean wasPressed(InputButton button) {
        return down[button.ordinal()] && !previous[button.ordinal()];
    }

    /** One line for the settings panel: which schemes are live. */
    public String status() {
        return status;
    }

    public boolean hasSchemes() {
        return !schemes.isEmpty();
    }

    // ---------------------------------------------------------------------
    // Loading schemes from enabled API mods
    // ---------------------------------------------------------------------

    /**
     * Keeps the loaded schemes in step with the enabled API mods, so switching an InputEdit API
     * on or off in the menu takes effect without a restart.
     */
    private void syncSchemes(ModManager mods) {
        if (mods == null) {
            return;
        }
        Set<Path> apiFolders = new LinkedHashSet<>();
        for (LocalMod mod : mods.enabledApis()) {
            if (InputEditApi.isInputEditApi(mod.folder())) {
                apiFolders.add(mod.folder());
            }
        }
        // Any enabled mod may ship a scheme; worlds are excluded because they are not add-ons.
        Set<Path> schemeFolders = new LinkedHashSet<>();
        for (LocalMod mod : mods.addons()) {
            if (mods.isEnabled(mod) && !apiFolders.contains(mod.folder())
                    && ControlScheme.isControlScheme(mod.folder())) {
                schemeFolders.add(mod.folder());
            }
        }
        if (apiFolders.equals(lastSeenApi) && schemeFolders.equals(lastSeenSchemes)) {
            return;
        }
        lastSeenApi = apiFolders;
        lastSeenSchemes = schemeFolders;
        reload(apiFolders, schemeFolders);
    }

    /** Loads the API, then every scheme that runs on it. Both are dropped and redone together. */
    private void reload(Set<Path> apiFolders, Set<Path> schemeFolders) {
        schemes.clear();
        broken.clear();
        api = null;
        apiFolder = apiFolders.stream().findFirst().orElse(null);
        waitingForApi = 0;

        if (apiFolder != null) {
            String modName = apiFolder.getFileName().toString();
            try {
                api = InputEditApi.load(apiFolder, modName);
                System.out.println("[Input] " + api.name() + " " + api.version() + " enabled");
            } catch (IOException | RuntimeException e) {
                System.err.println("[Input] " + modName + ": " + e.getMessage());
            }
        }

        if (api == null) {
            waitingForApi = schemeFolders.size();
            if (waitingForApi > 0) {
                System.out.println("[Input] " + waitingForApi + " control scheme mod(s) installed, but the "
                        + "InputEdit API is not enabled - playing on keyboard and mouse");
            }
            status = describe();
            return;
        }

        for (Path folder : schemeFolders) {
            String modName = folder.getFileName().toString();
            try {
                ControlScheme scheme = ControlScheme.load(folder, modName, api);
                schemes.put(folder, scheme);
                System.out.println("[Input] Loaded control scheme: " + scheme.name()
                        + " (" + scheme.deviceKind() + ")");
            } catch (IOException | RuntimeException e) {
                broken.add(folder);
                System.err.println("[Input] " + modName + ": " + e.getMessage());
            }
        }
        status = describe();
    }

    private String describe() {
        // Keyboard and mouse come first on purpose: they are always there, whatever else is.
        if (!schemes.isEmpty()) {
            List<String> names = new ArrayList<>();
            schemes.values().forEach(scheme -> names.add(scheme.name()));
            return "Keyboard and mouse, " + String.join(", ", names);
        }
        if (api == null) {
            return waitingForApi > 0
                    ? "Keyboard and mouse - enable the InputEdit API"
                    : "Keyboard and mouse - install the InputEdit API";
        }
        if (!broken.isEmpty()) {
            return "Keyboard and mouse (a control scheme failed to load - see the console)";
        }
        return "Keyboard and mouse - " + api.name() + " on, no device mod enabled";
    }
}

package net.coffeebrewia.roastengine.bypass;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One thing a hack client can switch on, and the list of everything the engine knows how to do.
 *
 * <p>The engine owns this list, the way it owns the actions a controller can be bound to. The
 * <b>Bypass API</b> mod then says which of them may be used ({@code bypass/api.json}), and a hack
 * client mod arranges the ones it wants into a menu ({@code hack/client.json}). Nothing here
 * happens unless both are installed and switched on.
 *
 * @param id       the name used in api.json and client.json
 * @param label    what the menu shows
 * @param kind     a switch, a number, or something that happens when pressed
 * @param min      lowest value, for {@link Kind#SLIDER}
 * @param max      highest value
 * @param initial  value before anyone touches it (0 or 1 for a switch)
 * @param category the tab it belongs in by default
 */
public record Cheat(String id, String label, Kind kind, float min, float max, float initial, String category) {

    public enum Kind {TOGGLE, SLIDER, ACTION}

    private static Cheat toggle(String id, String label, String category) {
        return new Cheat(id, label, Kind.TOGGLE, 0, 1, 0, category);
    }

    private static Cheat slider(String id, String label, float min, float max, float initial, String category) {
        return new Cheat(id, label, Kind.SLIDER, min, max, initial, category);
    }

    private static Cheat action(String id, String label, String category) {
        return new Cheat(id, label, Kind.ACTION, 0, 1, 0, category);
    }

    /** Everything the engine can do, in menu order. */
    public static final List<Cheat> ALL = List.of(
            // Movement
            toggle("fly", "Flight", "Movement"),
            toggle("noclip", "Noclip (walk through walls)", "Movement"),
            toggle("infiniteSprint", "Infinite sprint", "Movement"),
            slider("speed", "Speed multiplier", 0.5f, 6f, 1f, "Movement"),
            slider("jump", "Jump height", 0.5f, 4f, 1f, "Movement"),
            slider("flySpeed", "Flight speed", 1f, 20f, 8f, "Movement"),
            // Teleport and view
            action("teleportToLook", "Teleport to where you look", "View"),
            action("saveSpot", "Save this spot", "View"),
            action("loadSpot", "Go to saved spot", "View"),
            toggle("thirdPerson", "Third person", "View"),
            toggle("freecam", "Freecam (body stays put)", "View"),
            slider("fov", "Field of view", 50f, 140f, 70f, "View"),
            // World and players
            toggle("neverPassOut", "Never pass out", "World"),
            toggle("instantDoors", "Doors open instantly", "World"),
            toggle("playerEsp", "See players through walls", "World"),
            toggle("noFall", "No fall damage / respawn", "World"),
            // Information
            toggle("showInfo", "Position, speed and FPS", "Info"),
            toggle("showPing", "Ping graph (online)", "Info"));

    private static final Map<String, Cheat> BY_ID = byId(ALL);

    private static Map<String, Cheat> byId(List<Cheat> cheats) {
        Map<String, Cheat> map = new LinkedHashMap<>();
        cheats.forEach(cheat -> map.put(cheat.id(), cheat));
        return map;
    }

    /** The cheat with this id, or null when the engine has no such thing. */
    public static Cheat find(String id) {
        return BY_ID.get(id);
    }
}

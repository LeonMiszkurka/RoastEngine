package net.coffeebrewia.roastengine.modding.scene;

import java.util.ArrayList;
import java.util.List;

/** A level: a list of placed objects plus world settings. Serialized to {@code scene.json}. */
public final class Scene {

    public String name = "main";
    /** Sky colour as RGB in 0..1, used by the sandbox when the mod is loaded. */
    public float[] skyColor = {0.53f, 0.78f, 0.95f};
    /** Background track: a built-in name ("music_club", "music_menu") or empty for the default. */
    public String music = "";
    /** Optional spawn point [x, y, z]; null means the sandbox picks its own. */
    public float[] spawn = null;
    /** Whether the default grid platform is present. */
    public boolean groundPlatform = true;
    public List<SceneObject> objects = new ArrayList<>();

    public void normalize() {
        if (objects == null) {
            objects = new ArrayList<>();
        }
        if (music == null) {
            music = "";
        }
        if (spawn != null && spawn.length != 3) {
            spawn = null;
        }
        if (skyColor == null || skyColor.length != 3) {
            skyColor = new float[]{0.53f, 0.78f, 0.95f};
        }
        objects.forEach(SceneObject::normalize);
    }
}

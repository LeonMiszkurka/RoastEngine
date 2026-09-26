package net.coffeebrewia.roastengine.render.anim;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the animations the Creator's animator makes.
 *
 * <p>A rig's clips live beside its model: {@code smiler.glb} is animated by
 * {@code smiler.clips.json}. The engine loads the model's own clips first and then merges these
 * over the top, so a clip here with the same name as one baked into the .glb wins - which is how
 * you retouch an animation without going back to Blender and re-exporting.
 *
 * <pre>
 * {
 *   "clips": [
 *     { "name": "idle", "seconds": 2.0,
 *       "bones": {
 *         "head":  [ { "t": 0.0, "rot": [0, 0, 0] },
 *                    { "t": 1.0, "rot": [6, 12, 0], "move": [0, 0.02, 0] } ]
 *       } }
 *   ]
 * }
 * </pre>
 *
 * <p>{@code rot} is degrees about the bone's own axes and {@code move} is metres along them,
 * both measured <b>from the rest pose</b>; {@code move} may be left out, which is the usual case.
 */
public final class PoseClipFile {

    /** What a model's clip file is called: the model's name with this in place of its suffix. */
    public static final String SUFFIX = ".clips.json";

    private PoseClipFile() {
    }

    /** The clip file that belongs to a model, whether or not it exists yet. */
    public static Path besideModel(Path model) {
        String name = model.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return model.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + SUFFIX);
    }

    public static List<PoseClip> load(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            List<PoseClip> clips = new ArrayList<>();
            if (!json.has("clips")) {
                return clips;
            }
            for (var element : json.getAsJsonArray("clips")) {
                JsonObject object = element.getAsJsonObject();
                PoseClip clip = new PoseClip(
                        object.has("name") ? object.get("name").getAsString() : "clip",
                        object.has("seconds") ? object.get("seconds").getAsFloat() : 1f);
                JsonObject bones = object.has("bones") ? object.getAsJsonObject("bones") : new JsonObject();
                for (String bone : bones.keySet()) {
                    for (var keyElement : bones.getAsJsonArray(bone)) {
                        JsonObject key = keyElement.getAsJsonObject();
                        clip.put(bone,
                                key.has("t") ? key.get("t").getAsFloat() : 0f,
                                vector(key, "rot"),
                                vector(key, "move"));
                    }
                }
                clips.add(clip);
            }
            return clips;
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Not a clip file: " + file.getFileName() + " (" + e.getMessage() + ")", e);
        }
    }

    /** Loads the clips beside a model, or nothing at all if there are none or they are broken. */
    public static List<PoseClip> loadBesideModel(Path model) {
        Path file = besideModel(model);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return load(file);
        } catch (IOException e) {
            System.err.println("[Anim] " + e.getMessage());
            return List.of();
        }
    }

    public static void save(Path file, List<PoseClip> clips) throws IOException {
        JsonObject json = new JsonObject();
        JsonArray array = new JsonArray();
        for (PoseClip clip : clips) {
            JsonObject object = new JsonObject();
            object.addProperty("name", clip.name());
            object.addProperty("seconds", round(clip.seconds()));
            JsonObject bones = new JsonObject();
            for (String bone : clip.bones()) {
                JsonArray keys = new JsonArray();
                for (PoseClip.Key key : clip.keys(bone)) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("t", round(key.seconds()));
                    entry.add("rot", array(key.eulerDegrees()));
                    if (key.offset().lengthSquared() > 1e-8f) {
                        entry.add("move", array(key.offset()));
                    }
                    keys.add(entry);
                }
                bones.add(bone, keys);
            }
            object.add("bones", bones);
            array.add(object);
        }
        json.add("clips", array);

        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer writer = Files.newBufferedWriter(file)) {
            writer.write(json.toString().replace("},{", "},\n    {"));
            writer.write("\n");
        }
    }

    /**
     * Adds the clips from a file onto a model, replacing any of its own that share a name.
     *
     * @return how many clips were added
     */
    public static int applyTo(AnimatedModel model, List<PoseClip> clips) {
        int added = 0;
        for (PoseClip clip : clips) {
            model.addClip(clip.name(), clip.toAnimationClip(model.skeleton()));
            added++;
        }
        return added;
    }

    private static JsonArray array(Vector3f value) {
        JsonArray array = new JsonArray();
        array.add(round(value.x));
        array.add(round(value.y));
        array.add(round(value.z));
        return array;
    }

    private static Vector3f vector(JsonObject key, String name) {
        if (!key.has(name)) {
            return new Vector3f();
        }
        JsonArray values = key.getAsJsonArray(name);
        return new Vector3f(values.get(0).getAsFloat(), values.get(1).getAsFloat(),
                values.get(2).getAsFloat());
    }

    /** Files are read by people as well as by the game, so the numbers stay short. */
    private static float round(float value) {
        return Math.round(value * 1000f) / 1000f;
    }
}

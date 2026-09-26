package net.coffeebrewia.roastengine.world;

import net.coffeebrewia.roastengine.render.model.ModelAsset;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.file.Path;
import java.util.List;

/**
 * World objects for tests to carry about.
 *
 * <p>A real one comes out of a model file and needs a graphics context to load; these are built
 * from a single triangle instead, which is enough for anything that only looks at names, bounds
 * and flags.
 */
public final class TestObjects {

    private TestObjects() {
    }

    /** A one-triangle object a metre tall, with its base at y=0. */
    public static WorldObject item(String name) {
        return build(name, "", null);
    }

    /** The same, carrying a script from a mod folder. */
    public static WorldObject scripted(String name, String script, Path modFolder) {
        return build(name, script, modFolder);
    }

    private static WorldObject build(String name, String script, Path modFolder) {
        float[] triangle = {0, 0, 0, 1, 0, 0, 0, 1, 0};
        ModelAsset.Part part = new ModelAsset.Part(null, null, new Vector3f(0, 0, 0),
                new Vector3f(1, 1, 0), triangle, new int[]{0, 1, 2}, 1f, new Vector3f());
        ModelAsset asset = new ModelAsset(name + ".obj", List.of(part), List.of(),
                new Vector3f(0, 0, 0), new Vector3f(1, 1, 0), 1);
        WorldObject object = new WorldObject(name, "test", asset, new Matrix4f(), true, false,
                false, script, "", "", 0f);
        object.modFolder = modFolder;
        return object;
    }
}

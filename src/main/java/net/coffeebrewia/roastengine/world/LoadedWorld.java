package net.coffeebrewia.roastengine.world;

import net.coffeebrewia.roastengine.render.model.ModelLibrary;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** Everything the sandbox got from installed mods: objects, sky and the model caches to free. */
public final class LoadedWorld {

    public static final Vector3f DEFAULT_SKY = new Vector3f(0.53f, 0.78f, 0.95f);

    private final List<WorldObject> objects = new ArrayList<>();
    private final List<ModelLibrary> libraries = new ArrayList<>();
    private final List<String> modNames = new ArrayList<>();
    private final List<java.nio.file.Path> modFolders = new ArrayList<>();
    private String music = "";
    private final Vector3f skyColor = new Vector3f(DEFAULT_SKY);
    private boolean groundPlatform = true;
    private org.joml.Vector3f spawn;
    private net.coffeebrewia.roastengine.render.model.ModelAsset liquidAsset;
    private int failedObjects;

    void add(WorldObject object) {
        objects.add(object);
    }

    void addLibrary(ModelLibrary library) {
        libraries.add(library);
    }

    void addModName(String name) {
        modNames.add(name);
    }

    void addModFolder(java.nio.file.Path folder) {
        modFolders.add(folder);
    }

    /** Folders of the mods this world came from, for loading their sound overrides. */
    public List<java.nio.file.Path> modFolders() {
        return modFolders;
    }

    void setMusic(String music) {
        this.music = music == null ? "" : music;
    }

    /** The background track the world asked for, or empty. */
    public String music() {
        return music;
    }

    void setSky(float r, float g, float b) {
        skyColor.set(r, g, b);
    }

    void setGroundPlatform(boolean enabled) {
        groundPlatform = enabled;
    }

    void setLiquidAsset(net.coffeebrewia.roastengine.render.model.ModelAsset asset) {
        liquidAsset = asset;
    }

    /**
     * Mesh a mod supplies as {@code assets/liquid.obj} (or .glb), drawn inside drink glasses and
     * scaled to how full they are. Null when no mod ships one.
     */
    public net.coffeebrewia.roastengine.render.model.ModelAsset liquidAsset() {
        return liquidAsset;
    }

    void setSpawn(float x, float y, float z) {
        spawn = new org.joml.Vector3f(x, y, z);
    }

    /** The spawn point a world mod asked for, or null to let the sandbox choose. */
    public org.joml.Vector3f spawn() {
        return spawn;
    }

    void countFailure() {
        failedObjects++;
    }

    public List<WorldObject> objects() {
        return objects;
    }

    public List<String> modNames() {
        return modNames;
    }

    public Vector3f skyColor() {
        return skyColor;
    }

    public boolean hasGroundPlatform() {
        return groundPlatform;
    }

    public int failedObjects() {
        return failedObjects;
    }

    public boolean isEmpty() {
        return objects.isEmpty();
    }

    /** Frees every GPU mesh loaded for this world. */
    public void dispose() {
        libraries.forEach(ModelLibrary::dispose);
        libraries.clear();
        objects.clear();
    }
}

package net.coffeebrewia.roastengine.world;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import net.coffeebrewia.roastengine.modding.LocalMod;
import net.coffeebrewia.roastengine.modding.scene.Scene;
import net.coffeebrewia.roastengine.modding.scene.SceneObject;
import net.coffeebrewia.roastengine.render.model.ModelLibrary;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Turns installed mods into a playable world.
 *
 * <p>Every mod folder containing a {@code scene.json} (as exported by RoastEngine Creator) is
 * loaded; its models come from that mod's own {@code assets/} folder. Multiple mods stack:
 * their objects are merged, and the first one that ships a scene decides the sky.
 *
 * <p>Must be called on the main thread - it uploads meshes to the GPU.
 */
public final class WorldLoader {

    public static final String SCENE_FILE = "scene.json";
    private static final Gson GSON = new Gson();

    private WorldLoader() {
    }

    public static LoadedWorld load(List<LocalMod> mods) {
        LoadedWorld world = new LoadedWorld();
        boolean skyTaken = false;

        for (LocalMod mod : mods) {
            Path sceneFile = mod.folder().resolve(SCENE_FILE);
            if (!Files.isRegularFile(sceneFile)) {
                continue; // not a world mod
            }
            Scene scene = readScene(sceneFile, mod);
            if (scene == null) {
                continue;
            }
            ModelLibrary library = new ModelLibrary(mod.folder().resolve("assets"));
            world.addLibrary(library);
            world.addModName(mod.name());
            world.addModFolder(mod.folder());

            if (!skyTaken) {
                skyTaken = true;
                world.setSky(scene.skyColor[0], scene.skyColor[1], scene.skyColor[2]);
                world.setGroundPlatform(scene.groundPlatform);
                world.setMusic(scene.music);
                if (scene.spawn != null) {
                    world.setSpawn(scene.spawn[0], scene.spawn[1], scene.spawn[2]);
                }
            }

            // A mod that has drinks ships a liquid mesh; load it once for the whole world.
            for (String candidate : new String[]{"liquid.obj", "liquid.glb", "liquid.gltf"}) {
                if (Files.isRegularFile(mod.folder().resolve("assets").resolve(candidate))) {
                    library.get(candidate).ifPresent(world::setLiquidAsset);
                    break;
                }
            }

            int loaded = 0;
            for (SceneObject object : scene.objects) {
                var asset = library.get(object.asset);
                if (asset.isEmpty()) {
                    world.countFailure();
                    continue;
                }
                WorldObject placed = new WorldObject(
                        object.name,
                        mod.name(),
                        asset.get(),
                        new org.joml.Matrix4f(object.modelMatrix()),
                        object.collision,
                        object.kills,
                        object.slippery,
                        object.script,
                        object.interaction,
                        object.label,
                        object.fill);
                placed.npc = object.npc;
                placed.entity = object.entity;
                placed.scriptParams = object.scriptParams == null
                        ? java.util.Map.of() : java.util.Map.copyOf(object.scriptParams);
                placed.modFolder = mod.folder();
                placed.assetPath = mod.folder().resolve("assets").resolve(object.asset);
                placed.yawDegrees = object.rotation.y;
                placed.animation = object.animation;
                world.add(placed);
                loaded++;
            }
            System.out.println("[World] " + mod.name() + ": " + loaded + " object(s) from " + SCENE_FILE);
            if (!hasNoScripts(scene)) {
                System.out.println("[World] " + mod.name() + " ships scripts (see ScriptSystem)");
            }
        }
        return world;
    }

    private static boolean hasNoScripts(Scene scene) {
        return scene.objects.stream().allMatch(o -> o.script == null || o.script.isBlank());
    }

    private static Scene readScene(Path sceneFile, LocalMod mod) {
        try (Reader reader = Files.newBufferedReader(sceneFile)) {
            Scene scene = GSON.fromJson(reader, Scene.class);
            if (scene == null) {
                return null;
            }
            scene.normalize();
            return scene;
        } catch (IOException | JsonSyntaxException e) {
            System.err.println("[World] Bad scene in " + mod.name() + ": " + e.getMessage());
            return null;
        }
    }
}

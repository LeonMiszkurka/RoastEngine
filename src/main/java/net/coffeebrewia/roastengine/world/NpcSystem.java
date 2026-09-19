package net.coffeebrewia.roastengine.world;

import net.coffeebrewia.roastengine.render.ShaderProgram;
import net.coffeebrewia.roastengine.render.anim.AnimatedModel;
import net.coffeebrewia.roastengine.render.anim.AnimatedModelLoader;
import net.coffeebrewia.roastengine.render.anim.AnimationClip;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scripted characters placed by a level.
 *
 * <ul>
 *   <li><b>doorman</b> - an animated character by a door. When the player asks to go in he turns
 *       to look at them first, and the door opens a moment later.</li>
 *   <li><b>janitor</b> - hidden until the player passes out. After the level restarts he is found
 *       where they collapsed, holding their body, and once the player comes back in he carries it
 *       up and away into the sky.</li>
 * </ul>
 *
 * Animated characters reuse the rig of whatever {@code .glb} the scene gives them, playing its
 * first clip on a loop.
 */
public final class NpcSystem {

    public static final String DOORMAN = "doorman";
    public static final String JANITOR = "janitor";

    /** How quickly characters turn, in radians per second. */
    private static final float TURN_SPEED = 4.0f;
    /** The doorman keeps watching the player while they are this close. */
    private static final float DOORMAN_WATCH_RANGE = 6f;
    /** The janitor scene starts once the player is back within this range. */
    private static final float JANITOR_TRIGGER_RANGE = 13f;
    /** Seconds the janitor stands holding the body before lifting off. */
    private static final float JANITOR_PAUSE = 2.0f;
    private static final float JANITOR_RISE_SPEED = 1.6f;
    private static final float JANITOR_RISE_LIMIT = 30f;

    private static final class Npc {
        final WorldObject object;
        final String role;
        final Vector3f home = new Vector3f();
        final float homeYaw;
        AnimatedModel animated;
        AnimationClip clip;
        float yaw;
        float time;
        boolean watching;

        Npc(WorldObject object) {
            this.object = object;
            this.role = object.npc;
            object.model.getTranslation(home);
            this.homeYaw = (float) Math.toRadians(object.yawDegrees);
            this.yaw = homeYaw;
        }
    }

    private final List<Npc> npcs = new ArrayList<>();
    private final Map<Path, AnimatedModel> animatedModels = new HashMap<>();
    private final Matrix4f transform = new Matrix4f();

    /** The player's body the janitor carries: the same rig the player walks around in. */
    private AnimatedModel bodyModel;
    private AnimationClip bodyClip;

    private boolean janitorArmed;
    private boolean liftOffEvent;
    private boolean janitorTriggered;
    private float janitorTimer;
    private float janitorRise;
    private final Vector3f janitorSpot = new Vector3f();

    /** Finds the level's characters and loads animated models for them. Main thread only. */
    public void load(LoadedWorld world) {
        dispose();
        if (world == null) {
            return;
        }
        for (WorldObject object : world.objects()) {
            if (object.npc.isEmpty()) {
                continue;
            }
            Npc npc = new Npc(object);
            npc.animated = animatedFor(object.assetPath);
            if (npc.animated != null && !npc.animated.clipNames().isEmpty()) {
                npc.clip = npc.animated.clip(npc.animated.clipNames().get(0));
            }
            npcs.add(npc);

            // Any animated character's rig doubles as the player's body for the janitor scene.
            if (bodyModel == null && npc.animated != null) {
                bodyModel = npc.animated;
                bodyClip = npc.clip;
            }
        }
    }

    private AnimatedModel animatedFor(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        String name = file.getFileName().toString().toLowerCase();
        if (!name.endsWith(".glb") && !name.endsWith(".gltf") && !name.endsWith(".fbx")) {
            return null; // static props such as .obj figures
        }
        return animatedModels.computeIfAbsent(file, path -> {
            try {
                AnimatedModel model = AnimatedModelLoader.load(path);
                System.out.println("[NPC] Loaded " + path.getFileName() + " ("
                        + model.skeleton().boneCount() + " bones, " + model.clipNames().size() + " clip)");
                return model;
            } catch (IOException | RuntimeException e) {
                System.err.println("[NPC] Could not load " + path + ": " + e.getMessage());
                return null;
            }
        });
    }

    public boolean hasDoorman() {
        return npcs.stream().anyMatch(npc -> DOORMAN.equals(npc.role));
    }

    /** Makes the doorman turn to face the player. */
    public void doormanNotices() {
        npcs.stream().filter(npc -> DOORMAN.equals(npc.role)).forEach(npc -> npc.watching = true);
    }

    /** Called when the player collapses: the janitor will be waiting at this spot. */
    public void playerPassedOut(Vector3f feetPosition) {
        janitorArmed = true;
        janitorTriggered = false;
        janitorTimer = 0f;
        janitorRise = 0f;
        janitorSpot.set(feetPosition);
    }

    public void update(float delta, Vector3f player) {
        for (Npc npc : npcs) {
            npc.time += delta;
            if (DOORMAN.equals(npc.role)) {
                float distance = distanceXZ(npc.home, player);
                if (npc.watching && distance > DOORMAN_WATCH_RANGE) {
                    npc.watching = false; // player has gone in; back to watching the hall
                }
                float target = npc.watching ? yawTowards(npc.home, player) : npc.homeYaw;
                npc.yaw = turnTowards(npc.yaw, target, TURN_SPEED * delta);
            } else if (JANITOR.equals(npc.role) && janitorArmed) {
                // Face the player while they look on.
                npc.yaw = turnTowards(npc.yaw, yawTowards(janitorSpot, player), TURN_SPEED * delta);
                if (!janitorTriggered && distanceXZ(janitorSpot, player) < JANITOR_TRIGGER_RANGE) {
                    janitorTriggered = true;
                }
            }
        }

        if (janitorArmed && janitorTriggered) {
            janitorTimer += delta;
            if (janitorTimer > JANITOR_PAUSE) {
                if (janitorRise == 0f) {
                    liftOffEvent = true;
                }
                janitorRise += JANITOR_RISE_SPEED * delta * (1f + janitorRise * 0.15f);
            }
            if (janitorRise > JANITOR_RISE_LIMIT) {
                janitorArmed = false; // gone for good, until the next time
            }
        }
    }

    /** True once, on the frame the janitor leaves the ground. */
    public boolean consumeLiftOff() {
        boolean event = liftOffEvent;
        liftOffEvent = false;
        return event;
    }

    /** True while the janitor scene is playing, for a subtitle. */
    public boolean janitorScenePlaying() {
        return janitorArmed && janitorTriggered;
    }

    /** Draws characters without a rig (the janitor figure). World shader bound. */
    public void renderStatic(ShaderProgram shader) {
        for (Npc npc : npcs) {
            if (npc.animated != null) {
                continue;
            }
            if (JANITOR.equals(npc.role)) {
                if (!janitorArmed) {
                    continue;
                }
                shader.setUniform("uModel", transform.translation(
                                janitorSpot.x, janitorSpot.y + janitorRise, janitorSpot.z)
                        .rotateY(npc.yaw));
            } else {
                shader.setUniform("uModel", transform.translation(npc.home).rotateY(npc.yaw));
            }
            npc.object.asset.draw(shader);
        }
    }

    /** Draws rigged characters, and the body the janitor is carrying. Skinning shader bound. */
    public void renderAnimated(ShaderProgram shader) {
        for (Npc npc : npcs) {
            if (npc.animated == null) {
                continue;
            }
            npc.animated.pose(npc.clip, npc.time);
            shader.setUniform("uModel", transform.translation(npc.home)
                    .rotateY(npc.yaw)
                    .translate(0f, -npc.animated.min().y, 0f));
            npc.animated.draw(shader);
        }

        if (janitorArmed && bodyModel != null) {
            Npc janitor = npcs.stream().filter(n -> JANITOR.equals(n.role)).findFirst().orElse(null);
            float yaw = janitor == null ? 0f : janitor.yaw;
            // The body lies across the janitor's arms, in front of him at chest height.
            float forwardX = (float) Math.sin(yaw) * 0.45f;
            float forwardZ = (float) Math.cos(yaw) * 0.45f;
            float halfHeight = bodyModel.height() * 0.5f;
            bodyModel.pose(bodyClip, 0f);
            shader.setUniform("uModel", transform.translation(
                            janitorSpot.x + forwardX, janitorSpot.y + janitorRise + 1.05f,
                            janitorSpot.z + forwardZ)
                    .rotateY(yaw)
                    .rotateZ((float) Math.toRadians(90))
                    .translate(0f, -bodyModel.min().y - halfHeight, 0f));
            bodyModel.draw(shader);
        }
    }

    public void reset() {
        for (Npc npc : npcs) {
            npc.watching = false;
            npc.yaw = npc.homeYaw;
        }
    }

    public void dispose() {
        animatedModels.values().forEach(model -> {
            if (model != null) {
                model.dispose();
            }
        });
        animatedModels.clear();
        npcs.clear();
        bodyModel = null;
        bodyClip = null;
    }

    private static float distanceXZ(Vector3f a, Vector3f b) {
        return (float) Math.hypot(a.x - b.x, a.z - b.z);
    }

    /** Yaw that makes a model (authored facing +Z) look from {@code from} towards {@code to}. */
    private static float yawTowards(Vector3f from, Vector3f to) {
        return (float) Math.atan2(to.x - from.x, to.z - from.z);
    }

    private static float turnTowards(float current, float target, float maxStep) {
        float difference = (float) Math.atan2(Math.sin(target - current), Math.cos(target - current));
        if (Math.abs(difference) <= maxStep) {
            return target;
        }
        return current + Math.signum(difference) * maxStep;
    }
}

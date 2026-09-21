package net.coffeebrewia.roastengine.world;

import net.coffeebrewia.roastengine.render.model.ModelAsset;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * A scene object from a mod, ready to render and collide against.
 *
 * <p>Collision uses one world-space box <em>per part of the model</em>, not one box for the whole
 * object. A level exported as a single model would otherwise collide as one enormous solid block:
 * you could not walk on its floor, only on top of the box around the entire level.
 */
public final class WorldObject {

    /** Slack used so resting on a surface is not treated as intersecting it. */
    private static final float TOUCH_EPSILON = 0.002f;

    /** An axis-aligned box in world space. */
    public record Box(Vector3f min, Vector3f max) {

        boolean overlaps(Vector3f otherMin, Vector3f otherMax) {
            return otherMin.x < max.x - TOUCH_EPSILON && otherMax.x > min.x + TOUCH_EPSILON
                    && otherMin.y < max.y - TOUCH_EPSILON && otherMax.y > min.y + TOUCH_EPSILON
                    && otherMin.z < max.z - TOUCH_EPSILON && otherMax.z > min.z + TOUCH_EPSILON;
        }

        boolean touches(Vector3f otherMin, Vector3f otherMax) {
            return otherMin.x <= max.x && otherMax.x >= min.x
                    && otherMin.y <= max.y && otherMax.y >= min.y
                    && otherMin.z <= max.z && otherMax.z >= min.z;
        }

        boolean coversColumn(float x, float z) {
            return x >= min.x && x <= max.x && z >= min.z && z <= max.z;
        }
    }

    public final String name;
    public final String sourceMod;
    public final ModelAsset asset;
    public final Matrix4f model;
    /** Bounds of the whole object, for culling and for the editor's selection outline. */
    public final Vector3f worldMin;
    public final Vector3f worldMax;
    /** One box per model part - what collision actually tests against. */
    public final List<Box> boxes;
    public final boolean collision;
    public final boolean kills;
    public final boolean slippery;
    public final String script;
    /** "door", "drink" or empty. Drives what pressing E does. */
    public final String interaction;
    /** Prompt text shown when this object is the interaction target. */
    public final String label;
    /** Drink level, 0..1. */
    public float fill;
    /** Doors swing from 0 (shut) to 1 (open); anything else leaves this at 0. */
    public float openAmount;
    /** Whether a door is heading open or shut. Doors always start shut. */
    public boolean doorWantsOpen;

    // --- Set by scripts (see ScriptSystem) --------------------------------------------
    /** How far a script has moved this object from where the level placed it. */
    public final Vector3f scriptOffset = new Vector3f();
    /**
     * Extra turn from a script or the entity system, in radians. Visual only: the collision
     * boxes keep the angle the level placed them at.
     */
    public float scriptYaw;
    public float scriptPitch;
    public float scriptRoll;
    /** Walks about and can be punched into a ragdoll. */
    public boolean entity;
    /** A script removed it: not drawn, does not block, cannot be interacted with. */
    public boolean removedByScript;
    /** Values the level gave this object's script, from the Creator's inspector. */
    public java.util.Map<String, String> scriptParams = java.util.Map.of();
    /** Which mod this object came from, so its script is looked for in the right folder. */
    public java.nio.file.Path modFolder;

    private final Vector3f probeMin = new Vector3f();
    private final Vector3f probeMax = new Vector3f();
    private final Matrix4f scripted = new Matrix4f();
    /** Scripted character role ("doorman", "janitor") or empty; NPCs are drawn by NpcSystem. */
    public String npc = "";
    /** Where the model was loaded from, so NPCs can load an animated copy of it. */
    public java.nio.file.Path assetPath;
    /** Facing authored in the scene, in degrees. */
    public float yawDegrees;
    /** Looping motion from the mod's scene.json, or null for a still object. */
    public net.coffeebrewia.roastengine.modding.scene.ObjectAnimation animation;

    private final Matrix4f animated = new Matrix4f();
    private final Vector3f animationOrigin = new Vector3f();

    /**
     * The matrix to draw with this frame: the placed transform, with any animation played
     * around the object's own origin in world space.
     *
     * <p>Collision is left on {@link #model}, the placed transform, so an animated object is
     * decoration that moves rather than a platform that carries you.
     *
     * @param time seconds since the world was loaded
     */
    public Matrix4f renderModel(float time) {
        if (scriptOffset.lengthSquared() > 0 || scriptYaw != 0f || scriptPitch != 0f || scriptRoll != 0f) {
            // Moved or turned by a script or by being punched: place it, then turn it about its
            // own middle so it tumbles where it is rather than orbiting the level's origin.
            Matrix4f placed = animation == null || !animation.isActive() ? model : animatedModel(time);
            return scripted.translation(scriptOffset).mul(placed)
                    .rotateY(scriptYaw).rotateX(scriptPitch).rotateZ(scriptRoll);
        }
        if (animation == null || !animation.isActive()) {
            return model;
        }
        return animatedModel(time);
    }

    private Matrix4f animatedModel(float time) {
        model.getTranslation(animationOrigin);
        animated.identity().translate(animationOrigin);
        animation.apply(animated, time);
        return animated.translate(-animationOrigin.x, -animationOrigin.y, -animationOrigin.z).mul(model);
    }

    public WorldObject(String name, String sourceMod, ModelAsset asset, Matrix4f model,
                       boolean collision, boolean kills, boolean slippery, String script,
                       String interaction, String label, float fill) {
        this.name = name;
        this.sourceMod = sourceMod;
        this.asset = asset;
        this.model = model;
        this.collision = collision;
        this.kills = kills;
        this.slippery = slippery;
        this.script = script;
        this.interaction = interaction == null ? "" : interaction;
        this.label = label == null ? "" : label;
        this.fill = fill;

        this.boxes = buildCollisionBoxes(asset, model);
        Vector3f overallMin = new Vector3f(Float.MAX_VALUE);
        Vector3f overallMax = new Vector3f(-Float.MAX_VALUE);
        for (ModelAsset.Part part : asset.parts()) {
            Box box = transform(part.min(), part.max(), model);
            overallMin.min(box.min());
            overallMax.max(box.max());
        }
        this.worldMin = overallMin;
        this.worldMax = overallMax;
    }

    /**
     * Builds one collision box per triangle.
     *
     * <p>Level geometry is mostly axis-aligned, and an axis-aligned face's box is an exact, paper-
     * thin slab - so walls, floors and door frames collide exactly where they are drawn. Merging
     * triangles into larger boxes (per mesh, or per grid cell) is what previously sealed off the
     * club's doorway: the frame's side post and the lintel above it became one box across the gap.
     * The player moves in small steps, so a thin slab is never skipped over.
     */
    private static List<Box> buildCollisionBoxes(ModelAsset asset, Matrix4f model) {
        List<Box> result = new ArrayList<>();
        Vector3f point = new Vector3f();

        for (ModelAsset.Part part : asset.parts()) {
            float[] positions = part.collisionPositions();
            int[] indices = part.collisionIndices();
            if (positions == null || indices == null) {
                result.add(transform(part.min(), part.max(), model));
                continue;
            }
            for (int t = 0; t + 2 < indices.length; t += 3) {
                Vector3f min = new Vector3f(Float.MAX_VALUE);
                Vector3f max = new Vector3f(-Float.MAX_VALUE);
                for (int corner = 0; corner < 3; corner++) {
                    int v = indices[t + corner] * 3;
                    model.transformPosition(point.set(positions[v], positions[v + 1], positions[v + 2]));
                    min.min(point);
                    max.max(point);
                }
                result.add(new Box(min, max));
            }
        }
        return result;
    }

    /** Transforms a local box into world space by taking the extents of its eight corners. */
    private static Box transform(Vector3f localMin, Vector3f localMax, Matrix4f model) {
        Vector3f min = new Vector3f(Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE);
        Vector3f corner = new Vector3f();
        for (int i = 0; i < 8; i++) {
            corner.set(
                    (i & 1) == 0 ? localMin.x : localMax.x,
                    (i & 2) == 0 ? localMin.y : localMax.y,
                    (i & 4) == 0 ? localMin.z : localMax.z);
            model.transformPosition(corner);
            min.min(corner);
            max.max(corner);
        }
        return new Box(min, max);
    }

    /**
     * Whether this object blocks the player right now. An open door's collision is baked at its
     * closed position, so a door that has swung open must stop being solid or the doorway stays
     * blocked even though it looks open.
     */
    public boolean isSolid() {
        return collision && !kills && !removedByScript && !(isDoor() && openAmount > 0.25f)
                && npc.isEmpty();
    }

    public boolean isInteractive() {
        return !interaction.isEmpty();
    }

    public boolean isDoor() {
        return "door".equals(interaction);
    }

    public boolean isDrink() {
        return "drink".equals(interaction);
    }

    /** Centre of the object, used for "how close am I" checks. */
    public Vector3f center(Vector3f out) {
        return out.set(worldMin).add(worldMax).mul(0.5f).add(scriptOffset);
    }

    /** Cheap rejection against the whole object before testing individual triangles. */
    private boolean nearBounds(Vector3f min, Vector3f max) {
        return min.x <= worldMax.x && max.x >= worldMin.x
                && min.y <= worldMax.y && max.y >= worldMin.y
                && min.z <= worldMax.z && max.z >= worldMin.z;
    }

    /** True when any part intersects the given box (resting on a surface does not count). */
    public boolean overlaps(Vector3f min, Vector3f max) {
        if (removedByScript) {
            return false;
        }
        // The boxes were worked out where the level placed this object, so a script that has
        // moved it is handled by moving what we test against it, not the boxes themselves.
        Vector3f testMin = min;
        Vector3f testMax = max;
        if (scriptOffset.lengthSquared() > 0) {
            testMin = probeMin.set(min).sub(scriptOffset);
            testMax = probeMax.set(max).sub(scriptOffset);
        }
        for (Box box : boxes) {
            if (box.overlaps(testMin, testMax)) {
                return true;
            }
        }
        return false;
    }

    /** Overlap test with no margin, for "what am I touching" checks such as kill volumes. */
    public boolean touches(Vector3f min, Vector3f max) {
        if (!nearBounds(min, max)) {
            return false;
        }
        for (Box box : boxes) {
            if (box.touches(min, max)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Highest surface of this object directly above or below (x, z), ignoring anything taller
     * than {@code limit}.
     *
     * @return that height, or NaN when no part covers the column
     */
    public float topInColumn(float x, float z, float limit) {
        float best = Float.NaN;
        for (Box box : boxes) {
            float top = box.max().y;
            if (box.coversColumn(x, z) && top <= limit && (Float.isNaN(best) || top > best)) {
                best = top;
            }
        }
        return best;
    }
}

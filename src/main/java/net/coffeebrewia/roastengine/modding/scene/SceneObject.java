package net.coffeebrewia.roastengine.modding.scene;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * One placed object in a scene: a model asset plus a transform and gameplay config.
 *
 * <p>Serialized to {@code scene.json} by Gson, so fields are plain and public.
 */
public final class SceneObject {

    public String id = java.util.UUID.randomUUID().toString();
    public String name = "Object";
    /** Model file name inside the project's {@code assets/} folder. */
    public String asset = "";

    public Vector3f position = new Vector3f();
    /** Euler angles in degrees. */
    public Vector3f rotation = new Vector3f();
    public float scale = 1f;

    // --- Config (what the "Configs" section of the inspector edits) ---
    /** Objects collide by default; "No Collision" turns this off so you walk through them. */
    public boolean collision = true;
    /** "Kills You": touching this object kills the player. */
    public boolean kills = false;
    /** "Is Slippery": you slide across this surface like ice instead of stopping dead. */
    public boolean slippery = false;
    /** What pressing E on this object does: "" (nothing), "door" or "drink". */
    public String interaction = "";
    /** For drinks: how full the glass is, 0..1. */
    public float fill = 1f;
    /** Scripted character role: "doorman" (turns to you at the door) or "janitor". */
    public String npc = "";
    /**
     * An entity walks around by itself and can be punched into a ragdoll (see EntitySystem).
     * Any imported model can be one.
     */
    public boolean entity = false;
    /**
     * The player can pick this up and carry it (see HoldSystem). A script can switch it on with
     * {@code hold_item("Name")} instead of setting it here.
     */
    public boolean holdable = false;
    /** Prompt shown when the player looks at it, e.g. "open the door". */
    public String label = "";
    /** Optional Python script (file name inside the project's {@code scripts/} folder). */
    public String script = "";
    /**
     * Values handed to the script, so one script can drive many objects. Edited in the Creator's
     * inspector for mod projects; plain strings, because the script decides what they mean.
     */
    public java.util.Map<String, String> scriptParams = new java.util.LinkedHashMap<>();
    /** Which script callbacks this object wants; empty means "whatever the script defines". */
    public java.util.List<String> scriptEvents = new java.util.ArrayList<>();
    /** A looping motion the engine plays on top of the placed transform. */
    public ObjectAnimation animation = new ObjectAnimation();

    private final transient Matrix4f matrix = new Matrix4f();

    public SceneObject() {
    }

    public SceneObject(String name, String asset) {
        this.name = name;
        this.asset = asset;
    }

    /** Model matrix rebuilt from the transform (translate -> rotate Y X Z -> uniform scale). */
    public Matrix4f modelMatrix() {
        return matrix.translation(position)
                .rotateY((float) Math.toRadians(rotation.y))
                .rotateX((float) Math.toRadians(rotation.x))
                .rotateZ((float) Math.toRadians(rotation.z))
                .scale(scale);
    }

    /**
     * The model matrix with the animation played on top.
     *
     * <p>Translation comes first so an orbit moves the object rather than shearing it, and the
     * scale is applied last so a pulse multiplies the placed size.
     *
     * @param time seconds since the world was loaded
     */
    public Matrix4f animatedModelMatrix(float time) {
        if (animation == null || !animation.isActive()) {
            return modelMatrix();
        }
        matrix.translation(position);
        animation.apply(matrix, time);
        return matrix.rotateY((float) Math.toRadians(rotation.y))
                .rotateX((float) Math.toRadians(rotation.x))
                .rotateZ((float) Math.toRadians(rotation.z))
                .scale(scale);
    }

    /** Defensive fix-up for hand-edited or older scene files. */
    public void normalize() {
        if (position == null) position = new Vector3f();
        if (rotation == null) rotation = new Vector3f();
        if (name == null || name.isBlank()) name = "Object";
        if (asset == null) asset = "";
        if (script == null) script = "";
        if (interaction == null) interaction = "";
        if (label == null) label = "";
        if (npc == null) npc = "";
        fill = Math.max(0f, Math.min(1f, fill));
        if (id == null || id.isBlank()) id = java.util.UUID.randomUUID().toString();
        if (scale <= 0f) scale = 1f;
        if (scriptParams == null) scriptParams = new java.util.LinkedHashMap<>();
        if (scriptEvents == null) scriptEvents = new java.util.ArrayList<>();
        if (animation == null) animation = new ObjectAnimation();
        animation.normalize();
    }
}

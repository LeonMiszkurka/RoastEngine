package net.coffeebrewia.roastengine.modding.scene;

import org.joml.Matrix4f;

/**
 * A looping animation applied to an object at runtime, on top of its placed transform.
 *
 * <p>Mods are dropped into somebody else's world, so they need to do something once they land
 * there without a script engine being involved. These are the canned motions the engine plays
 * itself: enough for a rotating sign, a bobbing pickup, a pulsing light or a patrolling prop.
 *
 * <p>Serialized inside {@code scene.json} by Gson, so fields are plain and public.
 */
public final class ObjectAnimation {

    /** Motion kinds, matching the {@code type} string. */
    public static final String NONE = "none";
    /** Turns about an axis, {@code speed} degrees per second. */
    public static final String SPIN = "spin";
    /** Slides up and down by {@code amount} metres, {@code speed} cycles per second. */
    public static final String BOB = "bob";
    /** Grows and shrinks by {@code amount} (a fraction of its size). */
    public static final String PULSE = "pulse";
    /** Circles its placed position with radius {@code amount}, in the plane across the axis. */
    public static final String ORBIT = "orbit";
    /** Rocks back and forth by {@code amount} degrees instead of turning all the way round. */
    public static final String SWING = "swing";

    public String type = NONE;
    /** Degrees per second for spin, otherwise cycles per second. */
    public float speed = 45f;
    /** Metres for bob and orbit, degrees for swing, a fraction of size for pulse. */
    public float amount = 1f;
    /** Which axis the motion is about: "x", "y" or "z". */
    public String axis = "y";
    /** Seconds to wait before starting, so several copies can move out of step. */
    public float delay = 0f;

    public boolean isActive() {
        return type != null && !NONE.equalsIgnoreCase(type) && !type.isBlank();
    }

    /**
     * Applies this frame of the animation to a model matrix that has already been built from the
     * object's placed transform.
     *
     * @param matrix the object's model matrix, modified in place
     * @param time   seconds since the world was loaded
     */
    public void apply(Matrix4f matrix, float time) {
        if (!isActive()) {
            return;
        }
        float t = time - delay;
        if (t < 0f) {
            return;
        }
        float phase = (float) (t * speed * Math.PI * 2);
        switch (type.toLowerCase(java.util.Locale.ROOT)) {
            // Spin is the one measured in degrees per second, so it does not use `phase`.
            case SPIN -> rotate(matrix, (float) Math.toRadians(speed * t));
            case SWING -> rotate(matrix, (float) Math.toRadians(amount * Math.sin(phase)));
            case BOB -> matrix.translate(0f, amount * (float) Math.sin(phase), 0f);
            case PULSE -> matrix.scale(1f + amount * (float) Math.sin(phase));
            case ORBIT -> {
                float c = amount * (float) Math.cos(phase);
                float s = amount * (float) Math.sin(phase);
                // Orbit happens in the plane the axis points out of.
                switch (axisIndex()) {
                    case 0 -> matrix.translate(0f, c, s);
                    case 2 -> matrix.translate(c, s, 0f);
                    default -> matrix.translate(c, 0f, s);
                }
            }
            default -> {
            }
        }
    }

    private void rotate(Matrix4f matrix, float radians) {
        switch (axisIndex()) {
            case 0 -> matrix.rotateX(radians);
            case 2 -> matrix.rotateZ(radians);
            default -> matrix.rotateY(radians);
        }
    }

    private int axisIndex() {
        if (axis == null) {
            return 1;
        }
        return switch (axis.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "x" -> 0;
            case "z" -> 2;
            default -> 1;
        };
    }

    /** One line for the inspector and the mod browser. */
    public String describe() {
        if (!isActive()) {
            return "None";
        }
        String kind = type.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + type.substring(1);
        return switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case SPIN -> kind + " " + trim(speed) + " deg/s about " + axisName();
            case SWING -> kind + " " + trim(amount) + " deg about " + axisName();
            case BOB -> kind + " " + trim(amount) + "m, " + trim(speed) + "/s";
            case PULSE -> kind + " " + trim(amount) + ", " + trim(speed) + "/s";
            case ORBIT -> kind + " r=" + trim(amount) + "m about " + axisName();
            default -> kind;
        };
    }

    private String axisName() {
        return axis == null ? "Y" : axis.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String trim(float value) {
        return value == Math.rint(value) ? String.valueOf((int) value) : String.format("%.2f", value);
    }

    public void normalize() {
        if (type == null || type.isBlank()) type = NONE;
        if (axis == null || axis.isBlank()) axis = "y";
        if (!Float.isFinite(speed)) speed = 0f;
        if (!Float.isFinite(amount)) amount = 0f;
        if (!Float.isFinite(delay) || delay < 0f) delay = 0f;
    }

    /** The motion kinds an editor can cycle through. */
    public static String[] kinds() {
        return new String[]{NONE, SPIN, SWING, BOB, PULSE, ORBIT};
    }
}

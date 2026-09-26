package net.coffeebrewia.roastengine.world;

import org.joml.Vector3f;

import java.util.Random;

/**
 * Where a bullet goes: rays against the level and against players.
 *
 * <p>Shots are instant - a ray from the eye along the aim, stopped by the first wall. Players
 * are upright cylinders, which is close enough to a person to feel fair and cheap enough to test
 * every shot against everyone.
 *
 * <p>It lives here rather than with the arena because a gun fires the same way wherever it is
 * held: the arena shoots with this, and so does a gun picked up in the sandbox.
 */
public final class Ballistics {

    /** Nothing was hit. */
    public static final float MISS = Float.POSITIVE_INFINITY;

    private Ballistics() {
    }

    /**
     * How far along a ray it meets a box, or {@link #MISS}. A ray that starts inside the box meets
     * it at 0. {@code direction} must be normalised.
     */
    public static float rayBox(Vector3f origin, Vector3f direction, Vector3f min, Vector3f max) {
        float near = 0f;
        float far = Float.POSITIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            float o = origin.get(axis);
            float d = direction.get(axis);
            float lo = min.get(axis);
            float hi = max.get(axis);
            if (Math.abs(d) < 1e-8f) {
                if (o < lo || o > hi) {
                    return MISS; // parallel to this pair of faces and outside them
                }
                continue;
            }
            float t1 = (lo - o) / d;
            float t2 = (hi - o) / d;
            near = Math.max(near, Math.min(t1, t2));
            far = Math.min(far, Math.max(t1, t2));
            if (near > far) {
                return MISS;
            }
        }
        return near;
    }

    /**
     * How far along a ray it meets an upright cylinder - a player - or {@link #MISS}.
     *
     * @param cx     the middle of the cylinder, across
     * @param cz     and along
     * @param bottom its feet
     * @param top    the top of its head
     */
    public static float rayCylinder(Vector3f origin, Vector3f direction, float cx, float cz, float radius,
                             float bottom, float top) {
        // Across: where the ray is within the circle.
        float px = origin.x - cx;
        float pz = origin.z - cz;
        float a = direction.x * direction.x + direction.z * direction.z;
        float enter;
        float exit;
        if (a < 1e-8f) {
            if (px * px + pz * pz > radius * radius) {
                return MISS; // straight up or down, and not over them
            }
            enter = Float.NEGATIVE_INFINITY;
            exit = Float.POSITIVE_INFINITY;
        } else {
            float b = 2f * (px * direction.x + pz * direction.z);
            float c = px * px + pz * pz - radius * radius;
            float discriminant = b * b - 4f * a * c;
            if (discriminant < 0f) {
                return MISS;
            }
            float root = (float) Math.sqrt(discriminant);
            enter = (-b - root) / (2f * a);
            exit = (-b + root) / (2f * a);
        }
        // Up and down: where the ray is between their feet and the top of their head.
        if (Math.abs(direction.y) < 1e-8f) {
            if (origin.y < bottom || origin.y > top) {
                return MISS;
            }
        } else {
            float t1 = (bottom - origin.y) / direction.y;
            float t2 = (top - origin.y) / direction.y;
            enter = Math.max(enter, Math.min(t1, t2));
            exit = Math.min(exit, Math.max(t1, t2));
        }
        enter = Math.max(enter, 0f);
        return enter <= exit ? enter : MISS;
    }

    /**
     * The first solid thing along a ray within {@code range}, or {@code range} when nothing is in
     * the way. Entities and carried things do not stop bullets - they are dealt with separately.
     */
    public static float worldHit(LoadedWorld world, Vector3f origin, Vector3f direction, float range) {
        float nearest = range;
        for (WorldObject object : world.objects()) {
            if (!object.isSolid() || object.entity) {
                continue;
            }
            // The whole object first: most of the level is nowhere near the shot.
            float whole = rayBox(origin, direction, object.worldMin, object.worldMax);
            if (whole >= nearest) {
                continue;
            }
            for (WorldObject.Box box : object.boxes) {
                float t = rayBox(origin, direction, box.min(), box.max());
                if (t < nearest) {
                    nearest = t;
                }
            }
        }
        return nearest;
    }

    /**
     * A direction up to {@code degrees} away from {@code forward}, spread evenly over that circle
     * rather than bunched in the middle.
     */
    public static Vector3f scatter(Vector3f forward, float degrees, Random random, Vector3f out) {
        out.set(forward).normalize();
        if (degrees <= 0f) {
            return out;
        }
        float angle = (float) Math.toRadians(degrees) * (float) Math.sqrt(random.nextFloat());
        float around = random.nextFloat() * (float) (Math.PI * 2);
        // Two directions at right angles to the aim, to tilt it along.
        Vector3f side = Math.abs(out.y) < 0.99f
                ? new Vector3f(out).cross(0f, 1f, 0f).normalize()
                : new Vector3f(out).cross(1f, 0f, 0f).normalize();
        Vector3f up = new Vector3f(side).cross(out).normalize();
        float tilt = (float) Math.tan(angle);
        return out.add(side.mul((float) Math.cos(around) * tilt))
                .add(up.mul((float) Math.sin(around) * tilt))
                .normalize();
    }
}

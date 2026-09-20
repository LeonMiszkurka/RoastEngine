package net.coffeebrewia.roastengine.multiplayer;

import net.coffeebrewia.roastengine.net.Protocol.Pose;
import org.joml.Vector3f;

import java.util.ArrayDeque;

/**
 * Someone else on the server, as seen from here.
 *
 * <p>Snapshots arrive about every 50 ms and never quite evenly, so drawing each one as it lands
 * would make other players stutter. Instead every pose is kept with the time it arrived, and the
 * player is drawn a little in the past ({@link #DELAY}), smoothly blended between the two poses
 * either side of that moment.
 */
public final class RemotePlayer {

    /** How far behind real time others are drawn. Two snapshots' worth absorbs most jitter. */
    static final float DELAY = 0.1f;
    /** Beyond this, a gap is treated as a teleport (a respawn) and not blended across. */
    private static final float TELEPORT_DISTANCE = 6f;
    private static final int KEEP = 32;

    private record Sample(float time, Pose pose) {
    }

    public final int id;
    public final String name;
    /** Protocol.RANK_*, for the tag over their head. */
    public final int rank;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    /** Where to draw this frame, updated by {@link #advance}. */
    public final Vector3f eye = new Vector3f();
    public float yaw;
    public float pitch;
    public boolean moving;
    /** Seconds in the current animation clip, and the blocky body's walk cycle. */
    public float animationTime;
    public float walkPhase;
    private boolean placed;

    RemotePlayer(int id, String name, int rank) {
        this.id = id;
        this.name = name;
        this.rank = rank;
    }

    /** The name as shown to others: with a rank tag in front, if they have one. */
    public String taggedName() {
        String tag = net.coffeebrewia.roastengine.net.Protocol.rankTag(rank);
        return tag.isEmpty() ? name : tag + " " + name;
    }

    /** True once at least one position has arrived; until then there is nothing to draw. */
    public boolean isPlaced() {
        return placed;
    }

    void receive(Pose pose, float now) {
        samples.addLast(new Sample(now, pose));
        while (samples.size() > KEEP) {
            samples.removeFirst();
        }
    }

    void advance(float now, float dt, float stepsPerMetre) {
        if (samples.isEmpty()) {
            return;
        }
        float renderTime = now - DELAY;
        // Drop samples that are fully in the past, keeping one before renderTime to blend from.
        while (samples.size() >= 2 && secondTime() <= renderTime) {
            samples.removeFirst();
        }
        Sample from = samples.peekFirst();
        Sample to = samples.size() >= 2 ? secondSample() : from;

        float x0 = eye.x;
        float z0 = eye.z;
        Pose a = from.pose();
        Pose b = to.pose();
        float span = to.time() - from.time();
        float t = span <= 0f ? 1f : clamp01((renderTime - from.time()) / span);
        float jump = (float) Math.hypot(b.x() - a.x(), b.z() - a.z());
        if (jump > TELEPORT_DISTANCE) {
            t = t < 0.5f ? 0f : 1f;
        }
        eye.set(lerp(a.x(), b.x(), t), lerp(a.y(), b.y(), t), lerp(a.z(), b.z(), t));
        yaw = lerpAngle(a.yaw(), b.yaw(), t);
        pitch = lerp(a.pitch(), b.pitch(), t);
        boolean wasMoving = moving;
        moving = t < 0.5f ? a.moving() : b.moving();

        if (moving != wasMoving) {
            animationTime = 0f;
        }
        animationTime += dt;
        if (placed && moving) {
            float step = (float) Math.hypot(eye.x - x0, eye.z - z0);
            if (step < TELEPORT_DISTANCE) {
                walkPhase = (walkPhase + step * stepsPerMetre) % (float) (Math.PI * 2);
            }
        } else if (!moving) {
            walkPhase = 0f;
        }
        placed = true;
    }

    private float secondTime() {
        return secondSample().time();
    }

    private Sample secondSample() {
        var iterator = samples.iterator();
        iterator.next();
        return iterator.next();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Blends two angles the short way round, so a turn through 180 degrees does not spin. */
    private static float lerpAngle(float a, float b, float t) {
        float twoPi = (float) (Math.PI * 2);
        float delta = ((b - a) % twoPi + twoPi * 1.5f) % twoPi - (float) Math.PI;
        return a + delta * t;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}

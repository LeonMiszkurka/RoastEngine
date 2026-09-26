package net.coffeebrewia.roastengine.render.anim;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * One animation: keyframes for the nodes it moves, sampled by time.
 *
 * <p>Nodes the clip does not mention keep their rest transform, which is what lets a walk clip
 * authored for part of a rig play on the whole skeleton.
 */
public final class AnimationClip {

    /** Keyframes for a single node. Each track may have its own timing. */
    public static final class Channel {
        final int node;
        final float[] positionTimes;
        final Vector3f[] positions;
        final float[] rotationTimes;
        final Quaternionf[] rotations;
        final float[] scaleTimes;
        final Vector3f[] scales;

        public Channel(int node,
                       float[] positionTimes, Vector3f[] positions,
                       float[] rotationTimes, Quaternionf[] rotations,
                       float[] scaleTimes, Vector3f[] scales) {
            this.node = node;
            this.positionTimes = positionTimes;
            this.positions = positions;
            this.rotationTimes = rotationTimes;
            this.rotations = rotations;
            this.scaleTimes = scaleTimes;
            this.scales = scales;
        }
    }

    private final String name;
    private final float durationSeconds;
    private final Channel[] channels;

    // Reused during sampling so playback allocates nothing per frame.
    private final Vector3f position = new Vector3f();
    private final Quaternionf rotation = new Quaternionf();
    private final Vector3f scale = new Vector3f(1f, 1f, 1f);

    public AnimationClip(String name, float durationSeconds, Channel[] channels) {
        this.name = name;
        this.durationSeconds = durationSeconds;
        this.channels = channels;
    }

    public String name() {
        return name;
    }

    public float durationSeconds() {
        return durationSeconds;
    }

    /**
     * Writes the pose at {@code time} into {@code outLocal}.
     *
     * @param skeleton supplies rest transforms for untouched nodes
     */
    public void sample(float time, Skeleton skeleton, Matrix4f[] outLocal) {
        for (int node = 0; node < outLocal.length; node++) {
            outLocal[node].set(skeleton.restTransform(node));
        }
        float t = durationSeconds <= 0f ? 0f : time % durationSeconds;

        for (Channel channel : channels) {
            if (channel.node < 0 || channel.node >= outLocal.length) {
                continue;
            }
            // A channel need not move all three: glTF allows a rotation-only track, and the
            // Creator's animator writes exactly that. What a channel leaves out comes from the
            // rest pose, because taking it as zero would flatten the node to nothing.
            Matrix4f rest = skeleton.restTransform(channel.node);
            if (channel.positions.length == 0) {
                rest.getTranslation(position);
            } else {
                interpolateVector(channel.positionTimes, channel.positions, t, position);
            }
            if (channel.rotations.length == 0) {
                rest.getUnnormalizedRotation(rotation).normalize();
            } else {
                interpolateQuaternion(channel.rotationTimes, channel.rotations, t, rotation);
            }
            if (channel.scales.length == 0) {
                rest.getScale(scale);
            } else {
                interpolateVector(channel.scaleTimes, channel.scales, t, scale);
            }
            outLocal[channel.node].translationRotateScale(position, rotation, scale);
        }
    }

    private static void interpolateVector(float[] times, Vector3f[] values, float t, Vector3f out) {
        if (values.length == 0) {
            out.set(0f);
            return;
        }
        if (values.length == 1 || t <= times[0]) {
            out.set(values[0]);
            return;
        }
        int index = findKey(times, t);
        if (index >= values.length - 1) {
            out.set(values[values.length - 1]);
            return;
        }
        float span = times[index + 1] - times[index];
        float alpha = span <= 0f ? 0f : (t - times[index]) / span;
        values[index].lerp(values[index + 1], alpha, out);
    }

    private static void interpolateQuaternion(float[] times, Quaternionf[] values, float t, Quaternionf out) {
        if (values.length == 0) {
            out.identity();
            return;
        }
        if (values.length == 1 || t <= times[0]) {
            out.set(values[0]);
            return;
        }
        int index = findKey(times, t);
        if (index >= values.length - 1) {
            out.set(values[values.length - 1]);
            return;
        }
        float span = times[index + 1] - times[index];
        float alpha = span <= 0f ? 0f : (t - times[index]) / span;
        values[index].slerp(values[index + 1], alpha, out);
    }

    /** Index of the last key at or before {@code t}. */
    private static int findKey(float[] times, float t) {
        int low = 0;
        int high = times.length - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (times[mid] <= t) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }
}

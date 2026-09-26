package net.coffeebrewia.roastengine.render.anim;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An animation as an animator works on it: for each bone, a few poses at points in time.
 *
 * <p>This is the editable side of {@link AnimationClip}. A clip in a .glb states each node's
 * whole local transform on every key, which is what the renderer wants and what nobody wants to
 * type. Here a key says only how far a bone has moved <b>from its rest pose</b> - "the head is
 * turned 20 degrees" rather than "the head's local matrix is this" - so the same clip can be
 * written against one rig and still mean something on another, and a bone nobody has touched
 * simply has no keys.
 *
 * <p>{@link #toAnimationClip} folds the rest pose back in and hands over something the engine
 * can play. The Creator's animator edits these; {@link PoseClipFile} stores them.
 */
public final class PoseClip {

    /** One bone, at one moment: how far it is turned and moved from where it rests. */
    public record Key(float seconds, Vector3f eulerDegrees, Vector3f offset) {

        public Key(float seconds, Vector3f eulerDegrees) {
            this(seconds, eulerDegrees, new Vector3f());
        }

        public Key copy() {
            return new Key(seconds, new Vector3f(eulerDegrees), new Vector3f(offset));
        }
    }

    private String name;
    private float seconds;
    /** Bone name -> its keys, kept in time order. Insertion order keeps files readable. */
    private final Map<String, List<Key>> bones = new LinkedHashMap<>();

    public PoseClip(String name, float seconds) {
        this.name = name;
        this.seconds = Math.max(0.05f, seconds);
    }

    public String name() {
        return name;
    }

    public void rename(String to) {
        if (to != null && !to.isBlank()) {
            name = to.trim();
        }
    }

    public float seconds() {
        return seconds;
    }

    public void setSeconds(float value) {
        seconds = Math.max(0.05f, value);
    }

    /** The bones this clip moves, in the order they were first keyed. */
    public List<String> bones() {
        return new ArrayList<>(bones.keySet());
    }

    public List<Key> keys(String bone) {
        return bones.getOrDefault(bone, List.of());
    }

    public boolean isEmpty() {
        return bones.values().stream().allMatch(List::isEmpty);
    }

    /**
     * Records a bone's pose at a moment, replacing any key already at that moment. Keys stay
     * sorted by time, so what is written is what plays.
     */
    public void put(String bone, float atSeconds, Vector3f eulerDegrees, Vector3f offset) {
        List<Key> keys = bones.computeIfAbsent(bone, ignored -> new ArrayList<>());
        Key key = new Key(Math.max(0f, atSeconds), new Vector3f(eulerDegrees), new Vector3f(offset));
        for (int i = 0; i < keys.size(); i++) {
            if (near(keys.get(i).seconds(), key.seconds())) {
                keys.set(i, key);
                return;
            }
            if (keys.get(i).seconds() > key.seconds()) {
                keys.add(i, key);
                return;
            }
        }
        keys.add(key);
    }

    /** Removes the key at that moment, and the bone itself once it has none left. */
    public boolean remove(String bone, float atSeconds) {
        List<Key> keys = bones.get(bone);
        if (keys == null) {
            return false;
        }
        boolean removed = keys.removeIf(key -> near(key.seconds(), atSeconds));
        if (keys.isEmpty()) {
            bones.remove(bone);
        }
        return removed;
    }

    /** The key on this bone at that moment, or null. */
    public Key keyAt(String bone, float atSeconds) {
        for (Key key : keys(bone)) {
            if (near(key.seconds(), atSeconds)) {
                return key;
            }
        }
        return null;
    }

    /**
     * Where a bone sits at a moment: the key there, or a blend of the ones either side. Before
     * the first key it holds the first; after the last it holds the last.
     */
    public Key sample(String bone, float atSeconds, Key out) {
        List<Key> keys = keys(bone);
        if (keys.isEmpty()) {
            out.eulerDegrees().set(0f);
            out.offset().set(0f);
            return out;
        }
        if (atSeconds <= keys.get(0).seconds()) {
            out.eulerDegrees().set(keys.get(0).eulerDegrees());
            out.offset().set(keys.get(0).offset());
            return out;
        }
        Key last = keys.get(keys.size() - 1);
        if (atSeconds >= last.seconds()) {
            out.eulerDegrees().set(last.eulerDegrees());
            out.offset().set(last.offset());
            return out;
        }
        for (int i = 1; i < keys.size(); i++) {
            Key after = keys.get(i);
            if (after.seconds() < atSeconds) {
                continue;
            }
            Key before = keys.get(i - 1);
            float span = after.seconds() - before.seconds();
            float t = span <= 0 ? 0f : (atSeconds - before.seconds()) / span;
            before.eulerDegrees().lerp(after.eulerDegrees(), t, out.eulerDegrees());
            before.offset().lerp(after.offset(), t, out.offset());
            return out;
        }
        return out;
    }

    /** A working copy, for duplicating a clip in the editor. */
    public PoseClip copy(String newName) {
        PoseClip copy = new PoseClip(newName, seconds);
        bones.forEach((bone, keys) -> copy.bones.put(bone, new ArrayList<>(keys.stream().map(Key::copy).toList())));
        return copy;
    }

    /**
     * Turns this into something the engine can play on a given skeleton.
     *
     * <p>Each key is folded into the bone's rest transform - rest first, then the pose - so a
     * clip that says "turned 20 degrees" comes out as the local transform the renderer samples.
     * Bones the rig does not have are skipped rather than failing: a clip written for one
     * creature can be tried on another.
     */
    public AnimationClip toAnimationClip(Skeleton skeleton) {
        List<AnimationClip.Channel> channels = new ArrayList<>();
        Vector3f restPosition = new Vector3f();
        Quaternionf restRotation = new Quaternionf();
        Vector3f restScale = new Vector3f();

        for (Map.Entry<String, List<Key>> entry : bones.entrySet()) {
            int node = skeleton.nodeIndex(entry.getKey());
            List<Key> keys = entry.getValue();
            if (node < 0 || keys.isEmpty()) {
                continue;
            }
            Matrix4f rest = skeleton.restTransform(node);
            rest.getTranslation(restPosition);
            rest.getUnnormalizedRotation(restRotation);
            rest.getScale(restScale);

            float[] times = new float[keys.size()];
            Vector3f[] positions = new Vector3f[keys.size()];
            Quaternionf[] rotations = new Quaternionf[keys.size()];
            for (int i = 0; i < keys.size(); i++) {
                Key key = keys.get(i);
                times[i] = key.seconds();
                // The pose turns the bone about its own origin, so it goes after the rest
                // rotation, and the offset is along the bone's rested axes.
                Quaternionf turn = new Quaternionf().rotationXYZ(
                        (float) Math.toRadians(key.eulerDegrees().x),
                        (float) Math.toRadians(key.eulerDegrees().y),
                        (float) Math.toRadians(key.eulerDegrees().z));
                rotations[i] = new Quaternionf(restRotation).normalize().mul(turn);
                positions[i] = new Vector3f(restPosition)
                        .add(new Vector3f(key.offset()).rotate(new Quaternionf(restRotation).normalize()));
            }
            channels.add(new AnimationClip.Channel(node, times, positions, times, rotations,
                    new float[0], new Vector3f[0]));
        }
        return new AnimationClip(name, seconds, channels.toArray(new AnimationClip.Channel[0]));
    }

    /**
     * Reads an animation the model already has into an editable clip.
     *
     * <p>This is how a clip made in Blender is opened for retouching: the played clip is sampled
     * frame by frame, and each bone's transform is measured back against its rest pose to give
     * the turn and shift a key holds. A bone that never moves is left out, so a walk cycle comes
     * back as keys on the legs rather than on all forty bones.
     *
     * @param framesPerSecond how finely to sample - more keys, closer to the original
     */
    public static PoseClip from(AnimationClip clip, Skeleton skeleton, int framesPerSecond) {
        PoseClip out = new PoseClip(clip.name(), clip.durationSeconds());
        int frames = Math.max(1, Math.round(clip.durationSeconds() * framesPerSecond));
        Matrix4f[] pose = new Matrix4f[skeleton.nodeCount()];
        for (int i = 0; i < pose.length; i++) {
            pose[i] = new Matrix4f();
        }
        Vector3f restPosition = new Vector3f();
        Quaternionf restRotation = new Quaternionf();
        Vector3f position = new Vector3f();
        Quaternionf rotation = new Quaternionf();

        for (int frame = 0; frame <= frames; frame++) {
            float at = Math.min(clip.durationSeconds(), frame / (float) framesPerSecond);
            // Sampling exactly on the end wraps back to the start, since a clip loops there.
            // A hair short of it is the last pose, which is what the final key should hold.
            clip.sample(Math.max(0f, Math.min(at, clip.durationSeconds() - 0.0005f)), skeleton, pose);
            for (int node = 0; node < pose.length; node++) {
                String bone = skeleton.nodeName(node);
                if (bone == null || bone.isBlank()) {
                    continue;
                }
                Matrix4f rest = skeleton.restTransform(node);
                rest.getTranslation(restPosition);
                rest.getUnnormalizedRotation(restRotation).normalize();
                pose[node].getTranslation(position);
                pose[node].getUnnormalizedRotation(rotation).normalize();

                // Undo the rest pose to leave what the animation added on top of it.
                Quaternionf turn = new Quaternionf(restRotation).conjugate().mul(rotation);
                Vector3f shift = new Vector3f(position).sub(restPosition)
                        .rotate(new Quaternionf(restRotation).conjugate());
                Vector3f euler = new Vector3f();
                turn.getEulerAnglesXYZ(euler);
                euler.set((float) Math.toDegrees(euler.x), (float) Math.toDegrees(euler.y),
                        (float) Math.toDegrees(euler.z));
                if (euler.length() < 0.05f && shift.length() < 0.0005f
                        && out.keys(bone).isEmpty()) {
                    continue;   // still at rest and never moved yet: nothing worth keying
                }
                out.put(bone, at, euler, shift);
            }
        }
        // A bone that turned out never to move drops back out again.
        out.bones.entrySet().removeIf(entry -> entry.getValue().stream().allMatch(
                key -> key.eulerDegrees().length() < 0.05f && key.offset().length() < 0.0005f));
        return out;
    }

    /** Keys land on whole frames, so "the same moment" allows for the rounding that follows. */
    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 0.0005f;
    }
}

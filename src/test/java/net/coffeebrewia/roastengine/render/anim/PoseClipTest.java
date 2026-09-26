package net.coffeebrewia.roastengine.render.anim;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoseClipTest {

    /** Two bones: a chest at the origin, and a head a metre above it. */
    private static Skeleton skeleton() {
        String[] names = {"chest", "head"};
        Matrix4f[] rest = {new Matrix4f(), new Matrix4f().translation(0f, 1f, 0f)};
        int[] parents = {-1, 0};
        int[] boneIndex = {0, 1};
        Matrix4f[] offsets = {new Matrix4f(), new Matrix4f().translation(0f, -1f, 0f)};
        return new Skeleton(names, rest, parents, boneIndex, offsets, new Matrix4f(), 2);
    }

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    @Test
    void keysStayInTimeOrderHoweverTheyGoIn() {
        PoseClip clip = new PoseClip("idle", 2f);
        clip.put("head", 1.0f, new Vector3f(10, 0, 0), new Vector3f());
        clip.put("head", 0.0f, new Vector3f(0, 0, 0), new Vector3f());
        clip.put("head", 0.5f, new Vector3f(5, 0, 0), new Vector3f());

        List<PoseClip.Key> keys = clip.keys("head");
        assertEquals(3, keys.size());
        assertEquals(0f, keys.get(0).seconds(), 0.001f);
        assertEquals(0.5f, keys.get(1).seconds(), 0.001f);
        assertEquals(1.0f, keys.get(2).seconds(), 0.001f);
    }

    @Test
    void keyingTheSameMomentTwiceReplacesIt() {
        PoseClip clip = new PoseClip("idle", 2f);
        clip.put("head", 0.5f, new Vector3f(10, 0, 0), new Vector3f());
        clip.put("head", 0.5f, new Vector3f(40, 0, 0), new Vector3f());

        assertEquals(1, clip.keys("head").size(), "a moment holds one pose, not two");
        assertEquals(40f, clip.keys("head").get(0).eulerDegrees().x, 0.001f);
    }

    @Test
    void removingTheLastKeyDropsTheBone() {
        PoseClip clip = new PoseClip("idle", 2f);
        clip.put("head", 0.5f, new Vector3f(10, 0, 0), new Vector3f());
        assertTrue(clip.remove("head", 0.5f));

        assertTrue(clip.bones().isEmpty(), "a bone with no keys is not in the clip at all");
        assertTrue(clip.isEmpty());
        assertNull(clip.keyAt("head", 0.5f));
    }

    @Test
    void samplingBlendsBetweenTheKeysEitherSide() {
        PoseClip clip = new PoseClip("turn", 2f);
        clip.put("head", 0f, new Vector3f(0, 0, 0), new Vector3f());
        clip.put("head", 1f, new Vector3f(0, 40, 0), new Vector3f(0, 0.2f, 0));

        PoseClip.Key out = new PoseClip.Key(0f, new Vector3f(), new Vector3f());
        clip.sample("head", 0.25f, out);
        assertEquals(10f, out.eulerDegrees().y, 0.01f, "a quarter of the way is a quarter turned");
        assertEquals(0.05f, out.offset().y, 0.001f);
    }

    @Test
    void samplingHoldsAtBothEnds() {
        PoseClip clip = new PoseClip("turn", 4f);
        clip.put("head", 1f, new Vector3f(0, 30, 0), new Vector3f());
        clip.put("head", 2f, new Vector3f(0, 60, 0), new Vector3f());

        PoseClip.Key out = new PoseClip.Key(0f, new Vector3f(), new Vector3f());
        assertEquals(30f, clip.sample("head", 0f, out).eulerDegrees().y, 0.01f, "before the first key");
        assertEquals(60f, clip.sample("head", 9f, out).eulerDegrees().y, 0.01f, "after the last");
        assertEquals(0f, clip.sample("elbow", 1f, out).eulerDegrees().y, 0.01f,
                "a bone with no keys sits at rest");
    }

    // ------------------------------------------------------------------
    // Turning it into something the engine plays
    // ------------------------------------------------------------------

    @Test
    void anUntouchedBoneIsLeftAtRest() {
        PoseClip clip = new PoseClip("idle", 1f);
        clip.put("head", 0f, new Vector3f(0, 0, 0), new Vector3f());

        AnimationClip played = clip.toAnimationClip(skeleton());
        Matrix4f[] pose = {new Matrix4f(), new Matrix4f()};
        played.sample(0f, skeleton(), pose);

        // The chest has no keys, so the clip must not have moved it from where it rests.
        assertEquals(new Matrix4f(), pose[0], "a bone the clip never mentions keeps its rest pose");
    }

    @Test
    void aPoseIsMeasuredFromTheRestPoseNotFromTheOrigin() {
        PoseClip clip = new PoseClip("nod", 1f);
        clip.put("head", 0f, new Vector3f(0, 0, 0), new Vector3f());

        Skeleton skeleton = skeleton();
        AnimationClip played = clip.toAnimationClip(skeleton);
        Matrix4f[] pose = {new Matrix4f(), new Matrix4f()};
        played.sample(0f, skeleton, pose);

        // A key of "no rotation" has to leave the head a metre up, where the rig puts it - not
        // collapsed onto its parent.
        Vector3f where = pose[1].getTranslation(new Vector3f());
        assertEquals(0f, where.x, 0.001f);
        assertEquals(1f, where.y, 0.001f, "the head stays where the rig rests it");
        assertEquals(0f, where.z, 0.001f);
    }

    @Test
    void turningABoneActuallyTurnsIt() {
        PoseClip clip = new PoseClip("nod", 1f);
        clip.put("head", 0f, new Vector3f(0, 90, 0), new Vector3f());

        Skeleton skeleton = skeleton();
        Matrix4f[] pose = {new Matrix4f(), new Matrix4f()};
        clip.toAnimationClip(skeleton).sample(0f, skeleton, pose);

        // Turned a quarter turn about Y, the bone's own -Z now points down -X.
        Vector3f forward = pose[1].transformDirection(new Vector3f(0, 0, -1));
        assertEquals(-1f, forward.x, 0.01f);
        assertEquals(0f, forward.z, 0.01f);
    }

    @Test
    void movingABoneOffsetsItFromRest() {
        PoseClip clip = new PoseClip("duck", 1f);
        clip.put("head", 0f, new Vector3f(), new Vector3f(0f, -0.3f, 0f));

        Skeleton skeleton = skeleton();
        Matrix4f[] pose = {new Matrix4f(), new Matrix4f()};
        clip.toAnimationClip(skeleton).sample(0f, skeleton, pose);

        assertEquals(0.7f, pose[1].getTranslation(new Vector3f()).y, 0.001f);
    }

    @Test
    void aBoneTheRigHasNotGotIsSkippedRatherThanFailing() {
        PoseClip clip = new PoseClip("idle", 1f);
        clip.put("tail", 0f, new Vector3f(0, 30, 0), new Vector3f());
        clip.put("head", 0f, new Vector3f(0, 10, 0), new Vector3f());

        AnimationClip played = clip.toAnimationClip(skeleton());
        assertNotNull(played, "a clip written for another creature still plays what it can");
        assertEquals(1f, played.durationSeconds(), 0.001f);
    }

    @Test
    void aChannelThatOnlyTurnsABoneLeavesItsSizeAlone() {
        // glTF allows a channel with rotation only, and that is what the animator writes.
        // Taken as zero rather than as the rest pose, the bone would collapse to nothing.
        Skeleton skeleton = skeleton();
        AnimationClip.Channel rotationOnly = new AnimationClip.Channel(1,
                new float[0], new org.joml.Vector3f[0],
                new float[]{0f}, new org.joml.Quaternionf[]{new org.joml.Quaternionf()},
                new float[0], new org.joml.Vector3f[0]);
        AnimationClip clip = new AnimationClip("turn", 1f, new AnimationClip.Channel[]{rotationOnly});

        Matrix4f[] pose = {new Matrix4f(), new Matrix4f()};
        clip.sample(0f, skeleton, pose);

        assertEquals(1f, pose[1].getScale(new Vector3f()).x, 0.001f, "the bone keeps its size");
        assertEquals(1f, pose[1].getTranslation(new Vector3f()).y, 0.001f,
                "and stays where the rig rests it");
    }

    @Test
    void anExistingAnimationCanBeReadBackForEditing() {
        // What the animator does when you open a clip that came out of Blender: sample it, and
        // measure each bone back against its rest pose so it can be posed and re-keyed.
        Skeleton skeleton = skeleton();
        PoseClip authored = new PoseClip("nod", 1f);
        authored.put("head", 0f, new Vector3f(0, 0, 0), new Vector3f());
        authored.put("head", 1f, new Vector3f(0, 45, 0), new Vector3f());

        PoseClip reopened = PoseClip.from(authored.toAnimationClip(skeleton), skeleton, 12);

        assertTrue(reopened.bones().contains("head"), "the bone that moves comes back");
        assertFalse(reopened.bones().contains("chest"), "the one that never moves does not");
        PoseClip.Key out = new PoseClip.Key(0f, new Vector3f(), new Vector3f());
        reopened.sample("head", 1f, out);
        assertEquals(45f, out.eulerDegrees().y, 1.0f, "and it is turned as far as it was");
    }

    // ------------------------------------------------------------------
    // The file
    // ------------------------------------------------------------------

    @Test
    void clipsSurviveBeingSavedAndRead(@TempDir Path dir) throws IOException {
        PoseClip idle = new PoseClip("idle", 2f);
        idle.put("head", 0f, new Vector3f(1, 2, 3), new Vector3f());
        idle.put("head", 1.5f, new Vector3f(4, 5, 6), new Vector3f(0f, 0.25f, 0f));
        PoseClip walk = new PoseClip("walk", 1.25f);
        walk.put("chest", 0.5f, new Vector3f(7, 8, 9), new Vector3f());

        Path file = dir.resolve("thing.clips.json");
        PoseClipFile.save(file, List.of(idle, walk));
        List<PoseClip> read = PoseClipFile.load(file);

        assertEquals(2, read.size());
        assertEquals("idle", read.get(0).name());
        assertEquals(2f, read.get(0).seconds(), 0.001f);
        assertEquals(2, read.get(0).keys("head").size());
        assertEquals(6f, read.get(0).keys("head").get(1).eulerDegrees().z, 0.001f);
        assertEquals(0.25f, read.get(0).keys("head").get(1).offset().y, 0.001f);
        assertEquals("walk", read.get(1).name());
        assertEquals(9f, read.get(1).keys("chest").get(0).eulerDegrees().z, 0.001f);
    }

    @Test
    void theClipFileSitsBesideItsModel() {
        assertEquals(Path.of("mods/backrooms/assets/smiler.clips.json"),
                PoseClipFile.besideModel(Path.of("mods/backrooms/assets/smiler.glb")));
    }

    @Test
    void aBrokenClipFileIsRefusedRatherThanCrashing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("broken.clips.json");
        Files.writeString(file, "this is not json");
        assertThrows(IOException.class, () -> PoseClipFile.load(file));
        assertTrue(PoseClipFile.loadBesideModel(dir.resolve("broken.glb")).isEmpty(),
                "a mod with a broken clip file still loads, just without its clips");
    }

    @Test
    void noClipFileIsNotAnError(@TempDir Path dir) {
        assertTrue(PoseClipFile.loadBesideModel(dir.resolve("nothing.glb")).isEmpty());
    }
}

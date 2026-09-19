package net.coffeebrewia.roastengine.render.anim;

import net.coffeebrewia.roastengine.render.ShaderProgram;
import net.coffeebrewia.roastengine.render.Texture;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A rigged, textured model plus the animations it can play.
 *
 * <p>Clips can come from other files that share the same rig - the usual export style where each
 * animation is its own {@code .glb} - because channels are matched to nodes by name.
 */
public final class AnimatedModel {

    /** One drawable chunk: geometry plus the texture of the material it came from. */
    public record Part(SkinnedMesh mesh, Texture texture) {
    }

    private final String fileName;
    private final List<Part> parts;
    private final List<Texture> textures;
    private final Skeleton skeleton;
    private final Map<String, AnimationClip> clips = new HashMap<>();
    private final Vector3f min;
    private final Vector3f max;
    private final int triangleCount;

    // Per-frame scratch, allocated once.
    private final Matrix4f[] localPose;
    private final Matrix4f[] worldPose;
    private final Matrix4f[] palette;

    public AnimatedModel(String fileName, List<Part> parts, List<Texture> textures, Skeleton skeleton,
                         Vector3f min, Vector3f max, int triangleCount) {
        this.fileName = fileName;
        this.parts = parts;
        this.textures = textures;
        this.skeleton = skeleton;
        this.min = min;
        this.max = max;
        this.triangleCount = triangleCount;

        this.localPose = newMatrixArray(skeleton.nodeCount());
        this.worldPose = newMatrixArray(skeleton.nodeCount());
        this.palette = newMatrixArray(Math.max(1, skeleton.boneCount()));
        skeleton.buildRestPalette(palette, worldPose);
    }

    private static Matrix4f[] newMatrixArray(int size) {
        Matrix4f[] array = new Matrix4f[size];
        for (int i = 0; i < size; i++) {
            array[i] = new Matrix4f();
        }
        return array;
    }

    public void addClip(String name, AnimationClip clip) {
        clips.put(name, clip);
    }

    public AnimationClip clip(String name) {
        return clips.get(name);
    }

    public List<String> clipNames() {
        return new ArrayList<>(clips.keySet());
    }

    /** Poses the skeleton at {@code time} within the clip; pass null to return to the rest pose. */
    public void pose(AnimationClip clip, float time) {
        if (clip == null) {
            skeleton.buildRestPalette(palette, worldPose);
            return;
        }
        clip.sample(time, skeleton, localPose);
        skeleton.buildPalette(localPose, palette, worldPose);

    }
    /** Uploads the current pose and draws every part. The shader must be bound already. */
    public void draw(ShaderProgram shader) {
        shader.setUniform("uBones", palette);
        shader.setUniform("uAlpha", 1f);
        for (Part part : parts) {
            if (part.texture() != null) {
                part.texture().bind(0);
                shader.setUniform("uTexture", 0);
                shader.setUniform("uUseTexture", 1);
            } else {
                Texture.white().bind(0);
                shader.setUniform("uTexture", 0);
                shader.setUniform("uUseTexture", 0);
            }
            part.mesh().draw();
        }
        shader.setUniform("uUseTexture", 0);
    }

    public Skeleton skeleton() {
        return skeleton;
    }

    public String fileName() {
        return fileName;
    }

    public Vector3f min() {
        return min;
    }

    public Vector3f max() {
        return max;
    }

    public float height() {
        return max.y - min.y;
    }

    public int triangleCount() {
        return triangleCount;
    }

    public void dispose() {
        parts.forEach(part -> part.mesh().dispose());
        textures.forEach(Texture::dispose);
        parts.clear();
        textures.clear();
        clips.clear();
    }
}

package net.coffeebrewia.roastengine.render.model;

import net.coffeebrewia.roastengine.render.Mesh;
import net.coffeebrewia.roastengine.render.ShaderProgram;
import net.coffeebrewia.roastengine.render.Texture;
import org.joml.Vector3f;

import java.util.List;

/**
 * A loaded model: one part per material (so each can have its own texture), plus the local-space
 * bounding box used for click picking and for fitting the model to the player.
 */
public record ModelAsset(String fileName, List<Part> parts, List<Texture> textures,
                         Vector3f min, Vector3f max, int triangleCount) {

    /**
     * One drawable chunk of the model: geometry, the texture its material referenced, and its own
     * bounds. Per-part bounds matter for collision - a whole level exported as a single model
     * would otherwise collide as one enormous solid box.
     */
    public record Part(Mesh mesh, Texture texture, Vector3f min, Vector3f max,
                       float[] collisionPositions, int[] collisionIndices, float alpha,
                       Vector3f emissive) {

        public boolean isTransparent() {
            return alpha < 0.999f;
        }
    }

    /**
     * Draws every part, binding its texture and telling the shader whether to sample one.
     * The shader must expose {@code uTexture} and {@code uUseTexture}.
     */
    public void draw(ShaderProgram shader) {
        // Opaque parts first, then see-through ones, so glass shows what is behind (and inside) it.
        drawParts(shader, false);
        drawParts(shader, true);
        shader.setUniform("uUseTexture", 0);
        shader.setUniform("uAlpha", 1f);
        shader.setUniform("uEmissive", NO_EMISSION);
    }

    private static final Vector3f NO_EMISSION = new Vector3f();

    private void drawParts(ShaderProgram shader, boolean transparent) {
        if (transparent) {
            org.lwjgl.opengl.GL11C.glEnable(org.lwjgl.opengl.GL11C.GL_BLEND);
            org.lwjgl.opengl.GL11C.glBlendFunc(org.lwjgl.opengl.GL11C.GL_SRC_ALPHA,
                    org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA);
            org.lwjgl.opengl.GL11C.glDepthMask(false);
        }
        for (Part part : parts) {
            if (part.isTransparent() != transparent) {
                continue;
            }
            shader.setUniform("uAlpha", part.alpha());
            shader.setUniform("uEmissive", part.emissive());
            if (part.texture() != null) {
                part.texture().bind(0);
                shader.setUniform("uTexture", 0);
                shader.setUniform("uUseTexture", 1);
            } else {
                // Keep a valid texture bound even when this part is untextured.
                Texture.white().bind(0);
                shader.setUniform("uTexture", 0);
                shader.setUniform("uUseTexture", 0);
            }
            part.mesh().draw();
        }
        if (transparent) {
            org.lwjgl.opengl.GL11C.glDepthMask(true);
            org.lwjgl.opengl.GL11C.glDisable(org.lwjgl.opengl.GL11C.GL_BLEND);
        }
    }

    /** Draws only the geometry, no textures - used for the editor's wireframe selection outline. */
    public void drawGeometry() {
        parts.forEach(part -> part.mesh().draw());
    }

    public Vector3f size() {
        return new Vector3f(max).sub(min);
    }

    /** Largest dimension, used to place new objects at a sensible height. */
    public float radius() {
        Vector3f size = size();
        return Math.max(0.001f, Math.max(size.x, Math.max(size.y, size.z)) * 0.5f);
    }

    public boolean isTextured() {
        return parts.stream().anyMatch(part -> part.texture() != null);
    }

    public void dispose() {
        parts.forEach(part -> part.mesh().dispose());
        textures.forEach(Texture::dispose);
    }
}

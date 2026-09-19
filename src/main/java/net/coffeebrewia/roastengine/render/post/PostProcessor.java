package net.coffeebrewia.roastengine.render.post;

import net.coffeebrewia.roastengine.core.Window;
import net.coffeebrewia.roastengine.render.ShaderProgram;
import org.joml.Matrix4f;

import java.util.Map;

import static org.lwjgl.opengl.GL30C.*;

/**
 * Runs a {@link ShaderPack} over the rendered scene.
 *
 * <pre>
 *   scene  -> HDR colour + depth textures        (the game draws into this)
 *   bright -> half-resolution glow buffer         (pack: bright.frag)
 *   blur   -> ping-pong between two glow buffers  (pack: blur.frag, horizontal then vertical)
 *   final  -> the screen                          (pack: composite.frag)
 * </pre>
 *
 * With no pack loaded, {@link #isActive()} is false and the game renders straight to the screen
 * as before.
 */
public final class PostProcessor {

    private final Window window;
    private ShaderPack pack;

    private int width;
    private int height;
    private int sceneFbo;
    private int sceneColor;
    private int sceneDepth;
    private final int[] bloomFbo = new int[2];
    private final int[] bloomColor = new int[2];
    private int bloomWidth;
    private int bloomHeight;
    private int emptyVao;
    private final Matrix4f inverseProjection = new Matrix4f();

    public PostProcessor(Window window) {
        this.window = window;
    }

    public boolean isActive() {
        return pack != null;
    }

    public ShaderPack pack() {
        return pack;
    }

    /** Swaps in a new pack (or null to switch post-processing off). */
    public void setPack(ShaderPack newPack) {
        if (pack != null && pack != newPack) {
            pack.dispose();
        }
        pack = newPack;
        if (pack != null && emptyVao == 0) {
            // A full-screen triangle generated from gl_VertexID needs no vertex data, only a VAO.
            emptyVao = glGenVertexArrays();
        }
    }

    /** Redirects rendering into the HDR scene buffer. Call before drawing the 3D scene. */
    public void beginScene() {
        beginScene(window.framebufferWidth(), window.framebufferHeight());
    }

    /**
     * Same, with buffers sized for a region smaller than the window - the Creator's shader
     * preview, which only fills its viewport.
     */
    public void beginScene(int targetWidth, int targetHeight) {
        ensureTargets(targetWidth, targetHeight);
        glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);
        glViewport(0, 0, width, height);
    }

    /**
     * Runs the pack and presents the result.
     *
     * @param extras per-frame values for the composite shader, e.g. how drunk the player is
     */
    public void endScene(Matrix4f projection, float near, float far, float time, Map<String, Float> extras) {
        endScene(projection, near, far, time, extras,
                0, 0, window.framebufferWidth(), window.framebufferHeight());
    }

    /**
     * Runs the pack and presents the result into one rectangle of the window, in framebuffer
     * pixels with a bottom-left origin, as {@code glViewport} takes them.
     */
    public void endScene(Matrix4f projection, float near, float far, float time, Map<String, Float> extras,
                         int outX, int outY, int outWidth, int outHeight) {
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        glBindVertexArray(emptyVao);

        // 1. Bright pass into the first glow buffer.
        ShaderProgram bright = pack.bright();
        glBindFramebuffer(GL_FRAMEBUFFER, bloomFbo[0]);
        glViewport(0, 0, bloomWidth, bloomHeight);
        bright.bind();
        bindTexture(0, sceneColor);
        bright.setUniform("uScene", 0);
        bright.setUniform("uThreshold", pack.bloomThreshold());
        glDrawArrays(GL_TRIANGLES, 0, 3);

        // 2. Separable blur, alternating direction and buffer.
        ShaderProgram blur = pack.blur();
        blur.bind();
        blur.setUniform("uImage", 0);
        int source = 0;
        for (int pass = 0; pass < pack.bloomPasses() * 2; pass++) {
            int target = 1 - source;
            glBindFramebuffer(GL_FRAMEBUFFER, bloomFbo[target]);
            bindTexture(0, bloomColor[source]);
            boolean horizontal = pass % 2 == 0;
            // Widening the step each pass grows the glow cheaply.
            float spread = 1f + (pass / 2) * 0.5f;
            blur.setUniform("uDirection",
                    horizontal ? spread / bloomWidth : 0f,
                    horizontal ? 0f : spread / bloomHeight);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            source = target;
        }

        // 3. Composite to the screen.
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(outX, outY, outWidth, outHeight);
        ShaderProgram composite = pack.composite();
        composite.bind();
        bindTexture(0, sceneColor);
        bindTexture(1, bloomColor[source]);
        bindTexture(2, sceneDepth);
        composite.setUniform("uScene", 0);
        composite.setUniform("uBloom", 1);
        composite.setUniform("uDepth", 2);
        composite.setUniform("uResolution", (float) width, (float) height);
        composite.setUniform("uNear", near);
        composite.setUniform("uFar", far);
        composite.setUniform("uTime", time);
        composite.setUniform("uProjection", projection);
        composite.setUniform("uInverseProjection", inverseProjection.set(projection).invert());
        for (Map.Entry<String, Float> entry : pack.uniforms().entrySet()) {
            composite.setUniform(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Float> entry : extras.entrySet()) {
            composite.setUniform(entry.getKey(), entry.getValue());
        }
        glDrawArrays(GL_TRIANGLES, 0, 3);

        composite.unbind();
        glBindVertexArray(0);
        glActiveTexture(GL_TEXTURE0);
    }

    private static void bindTexture(int unit, int texture) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, texture);
    }

    /** (Re)creates the render targets whenever the size they are drawn at changes. */
    private void ensureTargets(int targetWidth, int targetHeight) {
        int w = Math.max(1, targetWidth);
        int h = Math.max(1, targetHeight);
        if (sceneFbo != 0 && w == width && h == height) {
            return;
        }
        deleteTargets();
        width = w;
        height = h;

        sceneColor = colorTexture(w, h);
        sceneDepth = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, sceneDepth);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, w, h, 0, GL_DEPTH_COMPONENT, GL_FLOAT,
                (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        sceneFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, sceneColor, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, sceneDepth, 0);
        checkComplete("scene");

        bloomWidth = Math.max(1, w / 2);
        bloomHeight = Math.max(1, h / 2);
        for (int i = 0; i < 2; i++) {
            bloomColor[i] = colorTexture(bloomWidth, bloomHeight);
            bloomFbo[i] = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, bloomFbo[i]);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, bloomColor[i], 0);
            checkComplete("bloom");
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        System.out.println("[Shaders] Render targets " + w + "x" + h);
    }

    /** Half-float colour, so lights brighter than white survive until tonemapping. */
    private static int colorTexture(int w, int h) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GL_RGBA, GL_FLOAT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return texture;
    }

    private static void checkComplete(String what) {
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Framebuffer '" + what + "' incomplete: 0x" + Integer.toHexString(status));
        }
    }

    private void deleteTargets() {
        if (sceneFbo == 0) {
            return;
        }
        glDeleteFramebuffers(sceneFbo);
        glDeleteTextures(sceneColor);
        glDeleteTextures(sceneDepth);
        for (int i = 0; i < 2; i++) {
            glDeleteFramebuffers(bloomFbo[i]);
            glDeleteTextures(bloomColor[i]);
        }
        sceneFbo = 0;
    }

    public void dispose() {
        deleteTargets();
        setPack(null);
        if (emptyVao != 0) {
            glDeleteVertexArrays(emptyVao);
            emptyVao = 0;
        }
    }
}

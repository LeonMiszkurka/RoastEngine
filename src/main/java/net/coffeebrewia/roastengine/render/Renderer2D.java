package net.coffeebrewia.roastengine.render;

import net.coffeebrewia.roastengine.core.Window;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.stb.STBEasyFont.stb_easy_font_print;
import static org.lwjgl.stb.STBEasyFont.stb_easy_font_width;

/**
 * Immediate-mode batched 2D renderer for UI: solid rectangles and text.
 *
 * <p>Text uses {@code stb_easy_font}, which emits quads directly (no font texture needed), so
 * shapes and text share one batch, one draw call and a shader with no sampler in it. Images
 * (mod icons) switch to a second program with the texture bound, which costs a flush each time
 * - fine for the handful of icons a list shows. Coordinates are in window points with the
 * origin at the top-left.
 */
public final class Renderer2D {

    /** Approximate cap height of stb_easy_font at scale 1, in pixels. */
    public static final float TEXT_HEIGHT = 8f;

    private static final int FLOATS_PER_VERTEX = 8; // x, y, r, g, b, a, u, v
    private static final int MAX_VERTICES = 60_000;
    private static final int MAX_TEXT_LENGTH = 512;

    private final Window window;
    private final ShaderProgram shader;
    private final ShaderProgram texturedShader;
    private final Matrix4f projection = new Matrix4f();
    private final FloatBuffer batch = MemoryUtil.memAllocFloat(MAX_VERTICES * FLOATS_PER_VERTEX);
    /** stb_easy_font needs ~270 bytes per character in the worst case. */
    private final ByteBuffer textVertices = MemoryUtil.memAlloc(MAX_TEXT_LENGTH * 270);
    private final int vao;
    private final int vbo;
    private int vertexCount;
    /** Texture the queued geometry is drawn with, or null while drawing plain shapes. */
    private Texture boundTexture;

    public Renderer2D(Window window) {
        this.window = window;
        this.shader = ShaderProgram.fromResources("/shaders/ui");
        this.texturedShader = ShaderProgram.fromResources("/shaders/ui.vert", "/shaders/ui_textured.frag");

        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_VERTICES * FLOATS_PER_VERTEX * Float.BYTES, GL_STREAM_DRAW);
        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 4, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 2, GL_FLOAT, false, stride, 6L * Float.BYTES);
        glEnableVertexAttribArray(2);
        glBindVertexArray(0);
    }

    /** Starts a UI pass sized to the current window. */
    public void begin() {
        vertexCount = 0;
        batch.clear();
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        projection.setOrtho(0, window.width(), window.height(), 0, -1, 1);
        boundTexture = null;
        shader.bind();
        shader.setUniform("uProjection", projection);
    }

    /** Flushes remaining geometry and restores GL state. */
    public void end() {
        useTexture(null);
        flush();
        glDisable(GL_SCISSOR_TEST);
        shader.unbind();
    }

    private void flush() {
        if (vertexCount == 0) {
            return;
        }
        batch.flip();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, batch);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        glBindVertexArray(0);
        batch.clear();
        vertexCount = 0;
    }

    private void ensureCapacity(int vertices) {
        if (vertexCount + vertices > MAX_VERTICES) {
            flush();
        }
    }

    private void vertex(float x, float y, Color c) {
        vertex(x, y, c, 0f, 0f);
    }

    private void vertex(float x, float y, Color c, float u, float v) {
        batch.put(x).put(y).put(c.r()).put(c.g()).put(c.b()).put(c.a()).put(u).put(v);
        vertexCount++;
    }

    // ---------------------------------------------------------------------
    // Clipping
    // ---------------------------------------------------------------------

    /** Restricts subsequent drawing to a rectangle (window coordinates). */
    public void pushClip(float x, float y, float w, float h) {
        flush();
        float sx = (float) window.framebufferWidth() / Math.max(1, window.width());
        float sy = (float) window.framebufferHeight() / Math.max(1, window.height());
        glEnable(GL_SCISSOR_TEST);
        // GL scissor origin is bottom-left in framebuffer pixels.
        glScissor(Math.round(x * sx), Math.round((window.height() - y - h) * sy),
                Math.max(0, Math.round(w * sx)), Math.max(0, Math.round(h * sy)));
    }

    public void popClip() {
        flush();
        glDisable(GL_SCISSOR_TEST);
    }

    // ---------------------------------------------------------------------
    // Shapes
    // ---------------------------------------------------------------------

    public void rect(float x, float y, float w, float h, Color c) {
        ensureCapacity(6);
        vertex(x, y, c);
        vertex(x, y + h, c);
        vertex(x + w, y + h, c);
        vertex(x, y, c);
        vertex(x + w, y + h, c);
        vertex(x + w, y, c);
    }

    public void outline(float x, float y, float w, float h, float thickness, Color c) {
        rect(x, y, w, thickness, c);
        rect(x, y + h - thickness, w, thickness, c);
        rect(x, y, thickness, h, c);
        rect(x + w - thickness, y, thickness, h, c);
    }

    // ---------------------------------------------------------------------
    // Images
    // ---------------------------------------------------------------------

    /**
     * Draws a texture into the given box, multiplied by {@code tint}.
     *
     * <p>Textures are decoded flipped for 3D use (glTF puts its origin at the top-left), so the
     * V coordinates here run the other way to put the image back the right way up.
     */
    public void image(Texture texture, float x, float y, float w, float h, Color tint) {
        if (texture == null) {
            return;
        }
        useTexture(texture);
        ensureCapacity(6);
        vertex(x, y, tint, 0f, 1f);
        vertex(x, y + h, tint, 0f, 0f);
        vertex(x + w, y + h, tint, 1f, 0f);
        vertex(x, y, tint, 0f, 1f);
        vertex(x + w, y + h, tint, 1f, 0f);
        vertex(x + w, y, tint, 1f, 1f);
        useTexture(null);
    }

    /** Textured and untextured geometry use different programs, so switching flushes. */
    private void useTexture(Texture texture) {
        if (texture == boundTexture) {
            return;
        }
        flush();
        boundTexture = texture;
        if (texture == null) {
            shader.bind();
            shader.setUniform("uProjection", projection);
        } else {
            texturedShader.bind();
            texturedShader.setUniform("uProjection", projection);
            texturedShader.setUniform("uTexture", 0);
            texture.bind(0);
        }
    }

    // ---------------------------------------------------------------------
    // Text
    // ---------------------------------------------------------------------

    /** Draws ASCII text with its top-left corner at (x, y). */
    public void text(String text, float x, float y, float scale, Color c) {
        String safe = sanitize(text);
        if (safe.isEmpty()) {
            return;
        }
        textVertices.clear();
        int quads;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            quads = stb_easy_font_print(0, 0, stack.ASCII(safe, true), null, textVertices);
        }
        ensureCapacity(quads * 6);

        // Each stb vertex: float x, float y, float z, 4 bytes color = 16 bytes; 4 vertices per quad.
        for (int q = 0; q < quads; q++) {
            int base = q * 4 * 16;
            float x0 = x + textVertices.getFloat(base) * scale;
            float y0 = y + textVertices.getFloat(base + 4) * scale;
            float x1 = x + textVertices.getFloat(base + 2 * 16) * scale;
            float y1 = y + textVertices.getFloat(base + 2 * 16 + 4) * scale;
            vertex(x0, y0, c);
            vertex(x0, y1, c);
            vertex(x1, y1, c);
            vertex(x0, y0, c);
            vertex(x1, y1, c);
            vertex(x1, y0, c);
        }
    }

    /** Draws text centred horizontally and vertically inside a box. */
    public void textCentered(String text, float x, float y, float w, float h, float scale, Color c) {
        float tw = textWidth(text, scale);
        text(text, x + (w - tw) / 2f, y + (h - TEXT_HEIGHT * scale) / 2f, scale, c);
    }

    public float textWidth(String text, float scale) {
        String safe = sanitize(text);
        if (safe.isEmpty()) {
            return 0f;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return stb_easy_font_width(stack.ASCII(safe, true)) * scale;
        }
    }

    /** Truncates text with "..." so it fits within {@code maxWidth}. */
    public String ellipsize(String text, float maxWidth, float scale) {
        if (text == null) {
            return "";
        }
        if (textWidth(text, scale) <= maxWidth) {
            return text;
        }
        String result = text;
        while (!result.isEmpty() && textWidth(result + "...", scale) > maxWidth) {
            result = result.substring(0, result.length() - 1);
        }
        return result + "...";
    }

    /** stb_easy_font only supports printable ASCII. */
    private static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(text.length(), MAX_TEXT_LENGTH));
        for (int i = 0; i < text.length() && sb.length() < MAX_TEXT_LENGTH; i++) {
            char ch = text.charAt(i);
            sb.append(ch == '\n' || (ch >= 32 && ch < 127) ? ch : '?');
        }
        return sb.toString();
    }

    public void dispose() {
        shader.dispose();
        texturedShader.dispose();
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        MemoryUtil.memFree(batch);
        MemoryUtil.memFree(textVertices);
    }
}

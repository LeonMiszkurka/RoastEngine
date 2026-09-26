package net.coffeebrewia.roastengine.render;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30C.glGenerateMipmap;

/** A 2D OpenGL texture decoded with stb_image. Must be created on the main thread. */
public final class Texture {

    private final int id;
    private final int width;
    private final int height;

    private Texture(int id, int width, int height) {
        this.id = id;
        this.width = width;
        this.height = height;
    }

    private static Texture white;

    /**
     * A shared 1x1 white texture. Binding it for untextured geometry keeps the sampler pointing
     * at something valid; leaving the unit empty makes some drivers complain.
     */
    public static Texture white() {
        if (white == null) {
            ByteBuffer pixel = MemoryUtil.memAlloc(4);
            try {
                pixel.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).flip();
                white = fromRgba(pixel, 1, 1);
            } finally {
                MemoryUtil.memFree(pixel);
            }
        }
        return white;
    }

    /** Decodes an image file (PNG/JPG/...) from disk. */
    public static Texture fromFile(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
        try {
            buffer.put(bytes).flip();
            return fromEncodedBytes(buffer);
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    /**
     * Decodes an encoded image (PNG/JPG bytes), as found embedded inside a .glb.
     *
     * @param encoded the raw file bytes, positioned at the start
     */
    public static Texture fromEncodedBytes(ByteBuffer encoded) throws IOException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);

            // glTF UVs have their origin at the top-left; OpenGL samples from the bottom-left.
            STBImage.stbi_set_flip_vertically_on_load(true);
            ByteBuffer pixels = STBImage.stbi_load_from_memory(encoded, width, height, channels, 4);
            if (pixels == null) {
                throw new IOException("Could not decode image: " + STBImage.stbi_failure_reason());
            }
            try {
                return fromRgba(pixels, width.get(0), height.get(0));
            } finally {
                STBImage.stbi_image_free(pixels);
            }
        }
    }

    /** Uploads raw RGBA8 pixels. */
    public static Texture fromRgba(ByteBuffer rgba, int width, int height) {
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        glGenerateMipmap(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, 0);
        return new Texture(id, width, height);
    }

    /**
     * An empty texture whose pixels are replaced every frame, as a video does.
     *
     * <p>No mipmaps: they would be rebuilt on every upload for no gain, since a video is drawn at
     * roughly its own size. Clamped rather than repeated so the edge row cannot bleed around.
     */
    public static Texture streaming(int width, int height) {
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                (ByteBuffer) null);
        glBindTexture(GL_TEXTURE_2D, 0);
        return new Texture(id, width, height);
    }

    /** Replaces the pixels of a {@link #streaming} texture. The buffer must hold width*height RGBA. */
    public void updateRgba(ByteBuffer rgba) {
        glBindTexture(GL_TEXTURE_2D, id);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    public void bind(int unit) {
        org.lwjgl.opengl.GL13C.glActiveTexture(org.lwjgl.opengl.GL13C.GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, id);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public void dispose() {
        glDeleteTextures(id);
    }
}

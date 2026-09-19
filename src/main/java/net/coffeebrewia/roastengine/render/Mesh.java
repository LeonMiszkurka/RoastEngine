package net.coffeebrewia.roastengine.render;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL30C.*;

/**
 * Static indexed mesh stored in a VAO/VBO/EBO.
 *
 * <p>Vertex layout (interleaved): {@code location 0 = vec3 position}, {@code location 1 = vec3 color},
 * and optionally {@code location 2 = vec2 uv} for textured models. Meshes without UVs simply leave
 * that attribute disabled, and the shader is told not to sample a texture.
 */
public final class Mesh {

    public static final int FLOATS_PER_VERTEX = 6;
    public static final int FLOATS_PER_TEXTURED_VERTEX = 8;

    private final int vao;
    private final int vbo;
    private final int ebo;
    private final int indexCount;

    public Mesh(float[] vertices, int[] indices) {
        this(vertices, indices, false);
    }

    /** @param textured true when the vertex data carries UVs (8 floats per vertex) */
    public Mesh(float[] vertices, int[] indices, boolean textured) {
        indexCount = indices.length;

        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        FloatBuffer vertexBuffer = MemoryUtil.memAllocFloat(vertices.length);
        IntBuffer indexBuffer = MemoryUtil.memAllocInt(indices.length);
        try {
            vertexBuffer.put(vertices).flip();
            indexBuffer.put(indices).flip();

            vbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_STATIC_DRAW);

            ebo = glGenBuffers();
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL_STATIC_DRAW);
        } finally {
            MemoryUtil.memFree(vertexBuffer);
            MemoryUtil.memFree(indexBuffer);
        }

        int stride = (textured ? FLOATS_PER_TEXTURED_VERTEX : FLOATS_PER_VERTEX) * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);
        if (textured) {
            glVertexAttribPointer(2, 2, GL_FLOAT, false, stride, 6L * Float.BYTES);
            glEnableVertexAttribArray(2);
        }

        glBindVertexArray(0);
    }

    public void draw() {
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0L);
        glBindVertexArray(0);
    }

    public void dispose() {
        glDeleteBuffers(vbo);
        glDeleteBuffers(ebo);
        glDeleteVertexArrays(vao);
    }
}

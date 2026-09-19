package net.coffeebrewia.roastengine.render.anim;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL30C.*;

/**
 * A mesh with skinning data.
 *
 * <p>Vertex layout: {@code 0 = vec3 position}, {@code 1 = vec3 shade}, {@code 2 = vec2 uv},
 * {@code 3 = vec4 joints}, {@code 4 = vec4 weights}. Up to four bones influence each vertex,
 * which is the usual limit and plenty for a character.
 */
public final class SkinnedMesh {

    public static final int MAX_INFLUENCES = 4;
    public static final int FLOATS_PER_VERTEX = 3 + 3 + 2 + MAX_INFLUENCES + MAX_INFLUENCES;

    private final int vao;
    private final int vbo;
    private final int ebo;
    private final int indexCount;

    public SkinnedMesh(float[] vertices, int[] indices) {
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

        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 2, GL_FLOAT, false, stride, 6L * Float.BYTES);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(3, 4, GL_FLOAT, false, stride, 8L * Float.BYTES);
        glEnableVertexAttribArray(3);
        glVertexAttribPointer(4, 4, GL_FLOAT, false, stride, 12L * Float.BYTES);
        glEnableVertexAttribArray(4);

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

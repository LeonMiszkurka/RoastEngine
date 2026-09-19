package net.coffeebrewia.roastengine.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL20C.*;

/** A linked GLSL vertex + fragment program with cached uniform locations. */
public final class ShaderProgram {

    private final int programId;
    private final Map<String, Integer> uniformLocations = new HashMap<>();

    public ShaderProgram(String vertexSource, String fragmentSource) {
        int vertex = compile(GL_VERTEX_SHADER, vertexSource);
        int fragment = compile(GL_FRAGMENT_SHADER, fragmentSource);

        programId = glCreateProgram();
        glAttachShader(programId, vertex);
        glAttachShader(programId, fragment);
        glLinkProgram(programId);
        if (glGetProgrami(programId, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(programId);
            glDeleteProgram(programId);
            throw new IllegalStateException("Shader link failed:\n" + log);
        }
        // Shaders are no longer needed once linked into the program.
        glDetachShader(programId, vertex);
        glDetachShader(programId, fragment);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
    }

    /** Loads {@code <basePath>.vert} and {@code <basePath>.frag} from the classpath. */
    public static ShaderProgram fromResources(String basePath) {
        return fromResources(basePath + ".vert", basePath + ".frag");
    }

    /** Loads a specific vertex and fragment shader pair, so they can be mixed and matched. */
    public static ShaderProgram fromResources(String vertexPath, String fragmentPath) {
        return new ShaderProgram(readResource(vertexPath), readResource(fragmentPath));
    }

    private static int compile(int type, String source) {
        int id = glCreateShader(type);
        glShaderSource(id, source);
        glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(id);
            glDeleteShader(id);
            String kind = type == GL_VERTEX_SHADER ? "vertex" : "fragment";
            throw new IllegalStateException("Failed to compile " + kind + " shader:\n" + log);
        }
        return id;
    }

    private static String readResource(String path) {
        try (InputStream in = ShaderProgram.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("Shader resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read shader " + path, e);
        }
    }

    public void bind() {
        glUseProgram(programId);
    }

    public void unbind() {
        glUseProgram(0);
    }

    private int location(String name) {
        return uniformLocations.computeIfAbsent(name, n -> glGetUniformLocation(programId, n));
    }

    public void setUniform(String name, Matrix4f value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(location(name), false, value.get(stack.mallocFloat(16)));
        }
    }

    /** Uploads an array of matrices, such as a skinning palette ({@code uniform mat4 name[N]}). */
    public void setUniform(String name, Matrix4f[] values) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.FloatBuffer buffer = stack.mallocFloat(values.length * 16);
            for (int i = 0; i < values.length; i++) {
                values[i].get(i * 16, buffer);
            }
            glUniformMatrix4fv(location(name), false, buffer);
        }
    }

    public void setUniform(String name, Vector3f value) {
        glUniform3f(location(name), value.x, value.y, value.z);
    }

    public void setUniform(String name, float x, float y) {
        glUniform2f(location(name), x, y);
    }

    public void setUniform(String name, float value) {
        glUniform1f(location(name), value);
    }

    public void setUniform(String name, int value) {
        glUniform1i(location(name), value);
    }

    public void dispose() {
        glDeleteProgram(programId);
    }
}

package net.coffeebrewia.roastengine.render.post;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.coffeebrewia.roastengine.render.ShaderProgram;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A shader pack shipped by an API mod.
 *
 * <p>The engine supplies the plumbing - an HDR scene buffer, a bloom chain, a full-screen pass -
 * and the pack supplies the look, as GLSL under the mod's {@code shaders/} folder:
 *
 * <pre>
 * shaders/
 *   pack.json         name, bloom passes, and any tuning values ("uniforms")
 *   bright.frag       picks out what should glow
 *   blur.frag         one direction of a separable blur, run repeatedly for bloom
 *   composite.frag    the final image: lighting, bloom, tonemapping, grading
 *   fullscreen.vert   optional; the engine's own is used otherwise
 * </pre>
 *
 * Every number under {@code "uniforms"} in pack.json is passed to the composite shader by name,
 * so a pack can expose tuning without engine changes.
 */
public final class ShaderPack {

    /** The file whose presence marks an API mod as a shader pack. */
    public static final String MARKER = "shaders/composite.frag";

    private final String name;
    // Not final: the Creator's shader editor tunes these live, without recompiling.
    private int bloomPasses;
    private float bloomThreshold;
    private final Map<String, Float> uniforms;
    private final ShaderProgram bright;
    private final ShaderProgram blur;
    private final ShaderProgram composite;

    private ShaderPack(String name, int bloomPasses, float bloomThreshold, Map<String, Float> uniforms,
                       ShaderProgram bright, ShaderProgram blur, ShaderProgram composite) {
        this.name = name;
        this.bloomPasses = bloomPasses;
        this.bloomThreshold = bloomThreshold;
        this.uniforms = uniforms;
        this.bright = bright;
        this.blur = blur;
        this.composite = composite;
    }

    public static boolean isShaderPack(Path modFolder) {
        return Files.isRegularFile(modFolder.resolve(MARKER));
    }

    /** Compiles the pack. Main thread only; throws with the GLSL error if a shader is broken. */
    public static ShaderPack load(Path modFolder, String fallbackName) throws IOException {
        Path shaders = modFolder.resolve("shaders");
        JsonObject manifest = readManifest(shaders.resolve("pack.json"));

        String vertex = Files.isRegularFile(shaders.resolve("fullscreen.vert"))
                ? Files.readString(shaders.resolve("fullscreen.vert"))
                : classpath("/shaders/post/fullscreen.vert");

        Map<String, Float> uniforms = new LinkedHashMap<>();
        if (manifest.has("uniforms") && manifest.get("uniforms").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : manifest.getAsJsonObject("uniforms").entrySet()) {
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
                    uniforms.put(entry.getKey(), entry.getValue().getAsFloat());
                }
            }
        }

        ShaderProgram bright = compile(vertex, shaders.resolve("bright.frag"));
        ShaderProgram blur = compile(vertex, shaders.resolve("blur.frag"));
        ShaderProgram composite = compile(vertex, shaders.resolve("composite.frag"));

        return new ShaderPack(
                manifest.has("name") ? manifest.get("name").getAsString() : fallbackName,
                manifest.has("bloomPasses") ? Math.max(0, manifest.get("bloomPasses").getAsInt()) : 6,
                manifest.has("bloomThreshold") ? manifest.get("bloomThreshold").getAsFloat() : 1.0f,
                uniforms, bright, blur, composite);
    }

    private static ShaderProgram compile(String vertex, Path fragmentFile) throws IOException {
        if (!Files.isRegularFile(fragmentFile)) {
            throw new IOException("Shader pack is missing " + fragmentFile.getFileName());
        }
        try {
            return new ShaderProgram(vertex, Files.readString(fragmentFile));
        } catch (IllegalStateException e) {
            throw new IOException(fragmentFile.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static JsonObject readManifest(Path file) {
        if (!Files.isRegularFile(file)) {
            return new JsonObject();
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement element = JsonParser.parseReader(reader);
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (IOException | RuntimeException e) {
            System.err.println("[Shaders] Ignoring bad pack.json: " + e.getMessage());
            return new JsonObject();
        }
    }

    private static String classpath(String path) throws IOException {
        try (InputStream in = ShaderPack.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Missing engine shader " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public String name() {
        return name;
    }

    public int bloomPasses() {
        return bloomPasses;
    }

    public float bloomThreshold() {
        return bloomThreshold;
    }

    /** The tuning values from pack.json, in file order. Read-only; use the setters to change. */
    public Map<String, Float> uniforms() {
        return java.util.Collections.unmodifiableMap(uniforms);
    }

    /** Changes a tuning value for the next frame. Nothing is written back to pack.json. */
    public void setUniform(String uniform, float value) {
        uniforms.put(uniform, value);
    }

    public void setBloomPasses(int passes) {
        bloomPasses = Math.max(0, passes);
    }

    public void setBloomThreshold(float threshold) {
        bloomThreshold = threshold;
    }

    ShaderProgram bright() {
        return bright;
    }

    ShaderProgram blur() {
        return blur;
    }

    ShaderProgram composite() {
        return composite;
    }

    public void dispose() {
        bright.dispose();
        blur.dispose();
        composite.dispose();
    }
}

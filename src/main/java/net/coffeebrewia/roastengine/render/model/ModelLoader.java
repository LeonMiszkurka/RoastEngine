package net.coffeebrewia.roastengine.render.model;

import net.coffeebrewia.roastengine.render.Mesh;
import net.coffeebrewia.roastengine.render.Texture;
import org.joml.Vector3f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.AIColor4D;
import org.lwjgl.assimp.AIFace;
import org.lwjgl.assimp.AIMaterial;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIString;
import org.lwjgl.assimp.AITexture;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.assimp.Assimp.*;

/**
 * Imports model files (.obj, .glb, .gltf, .fbx, ...) with Assimp.
 *
 * <p>Textures come along for the ride: a .glb usually embeds its images, and those are decoded
 * straight from memory. Models that reference image files instead have them loaded from beside
 * the model. One {@link ModelAsset.Part} is produced per material, so a model with several
 * textures renders correctly.
 *
 * <p>The engine has no lighting model, so a fixed directional light is baked into the vertex
 * colours at import time and multiplied with the texture in the shader.
 */
public final class ModelLoader {

    private static final Vector3f LIGHT_DIRECTION = new Vector3f(0.4f, 0.9f, 0.35f).normalize();
    private static final float AMBIENT = 0.45f;
    private static final Vector3f FALLBACK_COLOR = new Vector3f(0.78f, 0.78f, 0.80f);

    private ModelLoader() {
    }

    /** Loads a model file. Must be called on the main (OpenGL) thread. */
    public static ModelAsset load(Path file) throws IOException {
        int flags = aiProcess_Triangulate
                | aiProcess_PreTransformVertices  // flatten the node hierarchy into world space
                | aiProcess_GenSmoothNormals
                | aiProcess_JoinIdenticalVertices
                | aiProcess_ImproveCacheLocality
                | aiProcess_FindDegenerates
                | aiProcess_SortByPType;

        AIScene scene = aiImportFile(file.toString(), flags);
        if (scene == null || scene.mRootNode() == null) {
            throw new IOException("Could not read model: " + aiGetErrorString());
        }
        try {
            return convert(file, scene);
        } finally {
            aiReleaseImport(scene);
        }
    }

    private static ModelAsset convert(Path file, AIScene scene) throws IOException {
        int meshCount = scene.mNumMeshes();
        if (meshCount == 0) {
            throw new IOException("Model contains no meshes");
        }
        PointerBuffer meshes = scene.mMeshes();
        PointerBuffer materials = scene.mMaterials();

        Vector3f min = new Vector3f(Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE);
        List<ModelAsset.Part> parts = new ArrayList<>();
        List<Texture> textures = new ArrayList<>();
        Map<Integer, Texture> texturesByMaterial = new HashMap<>();
        int triangles = 0;

        for (int m = 0; m < meshCount; m++) {
            AIMesh mesh = AIMesh.create(meshes.get(m));
            int materialIndex = mesh.mMaterialIndex();
            Vector3f baseColor = materialColor(materials, materialIndex);
            Texture texture = texturesByMaterial.computeIfAbsent(materialIndex,
                    index -> loadMaterialTexture(scene, materials, index, file, textures));
            boolean textured = texture != null && mesh.mTextureCoords(0) != null;

            AIVector3D.Buffer positions = mesh.mVertices();
            AIVector3D.Buffer normals = mesh.mNormals();
            AIVector3D.Buffer uvs = textured ? mesh.mTextureCoords(0) : null;
            int vertexCount = mesh.mNumVertices();
            int floatsPerVertex = textured ? Mesh.FLOATS_PER_TEXTURED_VERTEX : Mesh.FLOATS_PER_VERTEX;

            float[] vertices = new float[vertexCount * floatsPerVertex];
            Vector3f partMin = new Vector3f(Float.MAX_VALUE);
            Vector3f partMax = new Vector3f(-Float.MAX_VALUE);
            int v = 0;
            for (int i = 0; i < vertexCount; i++) {
                AIVector3D p = positions.get(i);
                vertices[v++] = p.x();
                vertices[v++] = p.y();
                vertices[v++] = p.z();
                Vector3f point = new Vector3f(p.x(), p.y(), p.z());
                min.min(point);
                max.max(point);
                partMin.min(point);
                partMax.max(point);

                float shade = 1f;
                if (normals != null) {
                    AIVector3D n = normals.get(i);
                    Vector3f normal = new Vector3f(n.x(), n.y(), n.z());
                    if (normal.lengthSquared() > 1e-8f) {
                        float ndl = Math.max(0f, normal.normalize().dot(LIGHT_DIRECTION));
                        shade = AMBIENT + (1f - AMBIENT) * ndl;
                    }
                }
                // Textured models keep the texture's own colours; only the shading is baked in.
                if (textured) {
                    vertices[v++] = shade;
                    vertices[v++] = shade;
                    vertices[v++] = shade;
                    AIVector3D uv = uvs.get(i);
                    vertices[v++] = uv.x();
                    vertices[v++] = uv.y();
                } else {
                    vertices[v++] = baseColor.x * shade;
                    vertices[v++] = baseColor.y * shade;
                    vertices[v++] = baseColor.z * shade;
                }
            }

            int[] indices = new int[mesh.mNumFaces() * 3];
            int i = 0;
            AIFace.Buffer faces = mesh.mFaces();
            for (int f = 0; f < mesh.mNumFaces(); f++) {
                AIFace face = faces.get(f);
                if (face.mNumIndices() != 3) {
                    continue; // triangulation should prevent this, but be safe
                }
                IntBuffer buffer = face.mIndices();
                indices[i++] = buffer.get(0);
                indices[i++] = buffer.get(1);
                indices[i++] = buffer.get(2);
                triangles++;
            }
            if (i == 0) {
                continue;
            }
            if (i < indices.length) {
                indices = java.util.Arrays.copyOf(indices, i);
            }
            // Positions are kept on the CPU so collision can use the triangles themselves;
            // one box per mesh would make a corridor a solid block.
            float[] collisionPositions = new float[vertexCount * 3];
            for (int c = 0; c < vertexCount; c++) {
                AIVector3D cp = positions.get(c);
                collisionPositions[c * 3] = cp.x();
                collisionPositions[c * 3 + 1] = cp.y();
                collisionPositions[c * 3 + 2] = cp.z();
            }
            parts.add(new ModelAsset.Part(new Mesh(vertices, indices, textured),
                    textured ? texture : null, partMin, partMax, collisionPositions, indices,
                    materialOpacity(materials, materialIndex),
                    materialEmission(materials, materialIndex)));
        }

        if (parts.isEmpty()) {
            throw new IOException("Model contains no triangles");
        }
        return new ModelAsset(file.getFileName().toString(), parts, textures, min, max, triangles);
    }

    // ---------------------------------------------------------------------
    // Materials and textures
    // ---------------------------------------------------------------------

    /**
     * Finds the colour texture for a material. glTF base-colour maps onto Assimp's diffuse slot;
     * a path beginning with {@code *} means the image is embedded in the file (the .glb case).
     */
    private static Texture loadMaterialTexture(AIScene scene, PointerBuffer materials, int index,
                                               Path modelFile, List<Texture> owned) {
        if (materials == null || index < 0 || index >= materials.limit()) {
            return null;
        }
        AIMaterial material = AIMaterial.create(materials.get(index));
        AIString path = AIString.calloc();
        try {
            int result = aiGetMaterialTexture(material, aiTextureType_DIFFUSE, 0, path,
                    (IntBuffer) null, null, null, null, null, null);
            if (result != aiReturn_SUCCESS) {
                result = aiGetMaterialTexture(material, aiTextureType_BASE_COLOR, 0, path,
                        (IntBuffer) null, null, null, null, null, null);
            }
            if (result != aiReturn_SUCCESS) {
                // Common cause: a Blender material built from procedural nodes. The glTF exporter
                // only writes image textures, so such a material arrives with no texture at all
                // and renders in its (usually white) base colour.
                System.out.println("[Models] " + modelFile.getFileName() + ": material '"
                        + materialName(material) + "' has no image texture - it will render untextured");
                return null;
            }
            String texturePath = path.dataString();
            if (texturePath == null || texturePath.isBlank()) {
                return null;
            }
            Texture texture = texturePath.startsWith("*")
                    ? loadEmbedded(scene, texturePath)
                    : loadFromDisk(modelFile, texturePath);
            if (texture != null) {
                owned.add(texture);
            }
            return texture;
        } catch (RuntimeException e) {
            System.err.println("[Models] Texture lookup failed: " + e.getMessage());
            return null;
        } finally {
            path.free();
        }
    }

    /** Embedded image: {@code *N} indexes the scene's texture list. */
    private static Texture loadEmbedded(AIScene scene, String reference) {
        int index;
        try {
            index = Integer.parseInt(reference.substring(1));
        } catch (NumberFormatException e) {
            return null;
        }
        PointerBuffer sceneTextures = scene.mTextures();
        if (sceneTextures == null || index < 0 || index >= scene.mNumTextures()) {
            return null;
        }
        AITexture aiTexture = AITexture.create(sceneTextures.get(index));
        try {
            if (aiTexture.mHeight() == 0) {
                // Compressed (PNG/JPG) bytes: mWidth holds the byte count.
                ByteBuffer encoded = aiTexture.pcDataCompressed();
                return Texture.fromEncodedBytes(encoded);
            }
            // Uncompressed texels (Assimp stores them as BGRA); convert to RGBA for OpenGL.
            int width = aiTexture.mWidth();
            int height = aiTexture.mHeight();
            org.lwjgl.assimp.AITexel.Buffer texels = aiTexture.pcData();
            ByteBuffer rgba = MemoryUtil.memAlloc(width * height * 4);
            try {
                for (int i = 0; i < width * height; i++) {
                    org.lwjgl.assimp.AITexel texel = texels.get(i);
                    rgba.put(texel.r()).put(texel.g()).put(texel.b()).put(texel.a());
                }
                rgba.flip();
                return Texture.fromRgba(rgba, width, height);
            } finally {
                MemoryUtil.memFree(rgba);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[Models] Could not decode embedded texture: " + e.getMessage());
            return null;
        }
    }

    /** Image file referenced by the model, resolved next to it. */
    private static Texture loadFromDisk(Path modelFile, String texturePath) {
        Path directory = modelFile.toAbsolutePath().getParent();
        if (directory == null) {
            return null;
        }
        Path candidate = directory.resolve(texturePath.replace('\\', '/')).normalize();
        if (!Files.isRegularFile(candidate)) {
            // Some exporters write absolute paths from another machine; try just the file name.
            candidate = directory.resolve(Path.of(texturePath.replace('\\', '/')).getFileName());
        }
        if (!Files.isRegularFile(candidate)) {
            System.err.println("[Models] Texture not found: " + texturePath);
            return null;
        }
        try {
            return Texture.fromFile(candidate);
        } catch (IOException e) {
            System.err.println("[Models] Could not load " + candidate + ": " + e.getMessage());
            return null;
        }
    }

    private static String materialName(AIMaterial material) {
        AIString name = AIString.calloc();
        try {
            return aiGetMaterialString(material, AI_MATKEY_NAME, 0, 0, name) == aiReturn_SUCCESS
                    ? name.dataString() : "unnamed";
        } finally {
            name.free();
        }
    }

    /**
     * Light a material gives off by itself ("Ke" in an MTL, emissiveFactor in glTF). Glowing
     * surfaces such as neon strips set this; a shader pack's bloom picks them up.
     */
    private static Vector3f materialEmission(PointerBuffer materials, int index) {
        if (materials == null || index < 0 || index >= materials.limit()) {
            return new Vector3f();
        }
        AIMaterial material = AIMaterial.create(materials.get(index));
        AIColor4D color = AIColor4D.create();
        if (aiGetMaterialColor(material, AI_MATKEY_COLOR_EMISSIVE, aiTextureType_NONE, 0, color) == aiReturn_SUCCESS) {
            return new Vector3f(color.r(), color.g(), color.b());
        }
        return new Vector3f();
    }

    /** Material opacity ("d" in an MTL, alpha in glTF); 1 when the material is fully opaque. */
    private static float materialOpacity(PointerBuffer materials, int index) {
        if (materials == null || index < 0 || index >= materials.limit()) {
            return 1f;
        }
        AIMaterial material = AIMaterial.create(materials.get(index));
        float[] opacity = new float[1];
        int[] count = new int[]{1};
        if (aiGetMaterialFloatArray(material, AI_MATKEY_OPACITY, aiTextureType_NONE, 0,
                opacity, count) == aiReturn_SUCCESS) {
            return Math.max(0.05f, Math.min(1f, opacity[0]));
        }
        return 1f;
    }

    private static Vector3f materialColor(PointerBuffer materials, int index) {
        if (materials == null || index < 0 || index >= materials.limit()) {
            return new Vector3f(FALLBACK_COLOR);
        }
        AIMaterial material = AIMaterial.create(materials.get(index));
        AIColor4D color = AIColor4D.create();
        if (aiGetMaterialColor(material, AI_MATKEY_COLOR_DIFFUSE, aiTextureType_NONE, 0, color) == aiReturn_SUCCESS) {
            Vector3f result = new Vector3f(color.r(), color.g(), color.b());
            // Pure black materials are common in exports that rely on textures only.
            return result.lengthSquared() < 1e-5f ? new Vector3f(FALLBACK_COLOR) : result;
        }
        return new Vector3f(FALLBACK_COLOR);
    }
}

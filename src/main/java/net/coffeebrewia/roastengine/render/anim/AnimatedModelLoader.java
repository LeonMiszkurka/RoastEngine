package net.coffeebrewia.roastengine.render.anim;

import net.coffeebrewia.roastengine.render.Texture;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.AIAnimation;
import org.lwjgl.assimp.AIBone;
import org.lwjgl.assimp.AIColor4D;
import org.lwjgl.assimp.AIFace;
import org.lwjgl.assimp.AIMaterial;
import org.lwjgl.assimp.AIMatrix4x4;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AINode;
import org.lwjgl.assimp.AINodeAnim;
import org.lwjgl.assimp.AIQuatKey;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIString;
import org.lwjgl.assimp.AITexture;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.assimp.AIVectorKey;
import org.lwjgl.assimp.AIVertexWeight;

import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.assimp.Assimp.*;

/**
 * Loads rigged models and their animations with Assimp.
 *
 * <p>Unlike the static loader this must keep the node hierarchy, so it does <em>not</em> use
 * {@code aiProcess_PreTransformVertices} - that flag bakes nodes into the vertices and throws the
 * skeleton away, which is why an animated model imported that way arrives frozen in its rest pose.
 */
public final class AnimatedModelLoader {

    private static final Vector3f LIGHT_DIRECTION = new Vector3f(0.4f, 0.9f, 0.35f).normalize();
    private static final float AMBIENT = 0.45f;
    /** How far a node must sit from its rest transform to count as posed. */
    private static final float POSE_EPSILON = 1e-5f;
    /** A pose does not move, so its clip is any length; a second reads sensibly in a log. */
    private static final float POSE_SECONDS = 1f;

    private AnimatedModelLoader() {
    }

    /** Loads the mesh, skeleton and any animations in the file. Main thread only. */
    public static AnimatedModel load(Path file) throws IOException {
        AIScene scene = importScene(file);
        try {
            Skeleton skeleton = buildSkeleton(scene);
            AnimatedModel model = buildMeshes(file, scene, skeleton);
            for (AnimationClip clip : readClips(scene, skeleton)) {
                model.addClip(clip.name(), clip);
            }
            return model;
        } finally {
            aiReleaseImport(scene);
        }
    }

    /**
     * Loads only the animations from a file, for the common export style where each clip lives in
     * its own {@code .glb} alongside a copy of the mesh. Channels bind to nodes by name, so the
     * clip plays on the skeleton it is given.
     */
    public static List<AnimationClip> loadClips(Path file, Skeleton skeleton) throws IOException {
        AIScene scene = importScene(file);
        try {
            return readClips(scene, skeleton);
        } finally {
            aiReleaseImport(scene);
        }
    }

    /**
     * Loads a single pose out of a file, for a rig exported standing in it rather than animated.
     *
     * <p>A pose is the usual way to export "how the body holds something": there is no movement to
     * key, so the file often arrives with no animation at all and the pose sitting in the node
     * transforms. A clip is still preferred when the file has one - this only falls back to the
     * node transforms - and the pose comes out as a one-key clip, which plays like any other.
     *
     * <p>Nodes are matched to the given skeleton by name, and one whose transform matches its rest
     * transform is left out: what is left is the difference from rest, which is the pose.
     *
     * @return the pose as a clip, or null when the file holds neither an animation nor a pose -
     *         a rig exported at rest, which has nothing in it to play
     */
    public static AnimationClip loadPose(Path file, Skeleton skeleton, String name) throws IOException {
        AIScene scene = importScene(file);
        try {
            List<AnimationClip> clips = readClips(scene, skeleton);
            return clips.isEmpty() ? readPose(scene, skeleton, name) : clips.get(0);
        } finally {
            aiReleaseImport(scene);
        }
    }

    private static AIScene importScene(Path file) throws IOException {
        int flags = aiProcess_Triangulate
                | aiProcess_GenSmoothNormals
                | aiProcess_LimitBoneWeights   // at most 4 influences per vertex
                | aiProcess_ImproveCacheLocality
                | aiProcess_FindDegenerates
                | aiProcess_SortByPType;
        AIScene scene = aiImportFile(file.toString(), flags);
        if (scene == null || scene.mRootNode() == null) {
            throw new IOException("Could not read model: " + aiGetErrorString());
        }
        return scene;
    }

    // ---------------------------------------------------------------------
    // Skeleton
    // ---------------------------------------------------------------------

    private static Skeleton buildSkeleton(AIScene scene) throws IOException {
        List<String> names = new ArrayList<>();
        List<Matrix4f> locals = new ArrayList<>();
        List<Integer> parents = new ArrayList<>();
        flatten(scene.mRootNode(), -1, names, locals, parents);

        // Bone indices and offset matrices come from the meshes that reference them.
        Map<String, Integer> boneIndexByName = new HashMap<>();
        List<Matrix4f> offsets = new ArrayList<>();
        PointerBuffer meshes = scene.mMeshes();
        for (int m = 0; m < scene.mNumMeshes(); m++) {
            AIMesh mesh = AIMesh.create(meshes.get(m));
            PointerBuffer bones = mesh.mBones();
            for (int b = 0; b < mesh.mNumBones(); b++) {
                AIBone bone = AIBone.create(bones.get(b));
                String name = bone.mName().dataString();
                if (!boneIndexByName.containsKey(name)) {
                    boneIndexByName.put(name, offsets.size());
                    offsets.add(toMatrix(bone.mOffsetMatrix()));
                }
            }
        }
        if (offsets.size() > Skeleton.MAX_BONES) {
            throw new IOException("Model has " + offsets.size() + " bones; the limit is "
                    + Skeleton.MAX_BONES);
        }

        int[] nodeBoneIndex = new int[names.size()];
        Arrays.fill(nodeBoneIndex, -1);
        for (int i = 0; i < names.size(); i++) {
            Integer bone = boneIndexByName.get(names.get(i));
            if (bone != null) {
                nodeBoneIndex[i] = bone;
            }
        }

        Matrix4f globalInverse = new Matrix4f(toMatrix(scene.mRootNode().mTransformation())).invert();
        return new Skeleton(
                names.toArray(new String[0]),
                locals.toArray(new Matrix4f[0]),
                parents.stream().mapToInt(Integer::intValue).toArray(),
                nodeBoneIndex,
                offsets.toArray(new Matrix4f[0]),
                globalInverse,
                offsets.size());
    }

    /** Depth-first walk that records parents before children. */
    private static void flatten(AINode node, int parent, List<String> names,
                                List<Matrix4f> locals, List<Integer> parents) {
        int index = names.size();
        names.add(node.mName().dataString());
        locals.add(toMatrix(node.mTransformation()));
        parents.add(parent);

        PointerBuffer children = node.mChildren();
        for (int i = 0; i < node.mNumChildren(); i++) {
            flatten(AINode.create(children.get(i)), index, names, locals, parents);
        }
    }

    // ---------------------------------------------------------------------
    // Meshes
    // ---------------------------------------------------------------------

    private static AnimatedModel buildMeshes(Path file, AIScene scene, Skeleton skeleton) throws IOException {
        PointerBuffer meshes = scene.mMeshes();
        PointerBuffer materials = scene.mMaterials();
        List<AnimatedModel.Part> parts = new ArrayList<>();
        List<Texture> textures = new ArrayList<>();
        Map<Integer, Texture> texturesByMaterial = new HashMap<>();
        Vector3f min = new Vector3f(Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE);
        int triangles = 0;

        for (int m = 0; m < scene.mNumMeshes(); m++) {
            AIMesh mesh = AIMesh.create(meshes.get(m));
            int vertexCount = mesh.mNumVertices();
            float[] vertices = new float[vertexCount * SkinnedMesh.FLOATS_PER_VERTEX];

            // Gather bone influences per vertex first; they arrive bone-by-bone.
            int[] influenceCount = new int[vertexCount];
            float[] joints = new float[vertexCount * SkinnedMesh.MAX_INFLUENCES];
            float[] weights = new float[vertexCount * SkinnedMesh.MAX_INFLUENCES];
            PointerBuffer bones = mesh.mBones();
            for (int b = 0; b < mesh.mNumBones(); b++) {
                AIBone bone = AIBone.create(bones.get(b));
                int boneIndex = boneIndexOf(skeleton, bone.mName().dataString());
                if (boneIndex < 0) {
                    continue;
                }
                AIVertexWeight.Buffer boneWeights = bone.mWeights();
                for (int w = 0; w < bone.mNumWeights(); w++) {
                    AIVertexWeight weight = boneWeights.get(w);
                    int vertex = weight.mVertexId();
                    if (vertex >= vertexCount || influenceCount[vertex] >= SkinnedMesh.MAX_INFLUENCES) {
                        continue;
                    }
                    int slot = vertex * SkinnedMesh.MAX_INFLUENCES + influenceCount[vertex]++;
                    joints[slot] = boneIndex;
                    weights[slot] = weight.mWeight();
                }
            }

            AIVector3D.Buffer positions = mesh.mVertices();
            AIVector3D.Buffer normals = mesh.mNormals();
            AIVector3D.Buffer uvs = mesh.mTextureCoords(0);
            Texture texture = texturesByMaterial.computeIfAbsent(mesh.mMaterialIndex(),
                    index -> loadTexture(scene, materials, index, file, textures));
            // An untextured rig - a character built from flat materials, which is what comes out
            // of Blender unless someone paints one - keeps its material colours. With a texture
            // the colour comes from the image, so only the shading is baked in.
            Vector3f baseColor = texture != null || uvs != null
                    ? new Vector3f(1f, 1f, 1f)
                    : materialColor(materials, mesh.mMaterialIndex());

            int v = 0;
            for (int i = 0; i < vertexCount; i++) {
                AIVector3D p = positions.get(i);
                vertices[v++] = p.x();
                vertices[v++] = p.y();
                vertices[v++] = p.z();
                min.min(new Vector3f(p.x(), p.y(), p.z()));
                max.max(new Vector3f(p.x(), p.y(), p.z()));

                float shade = 1f;
                if (normals != null) {
                    AIVector3D n = normals.get(i);
                    Vector3f normal = new Vector3f(n.x(), n.y(), n.z());
                    if (normal.lengthSquared() > 1e-8f) {
                        shade = AMBIENT + (1f - AMBIENT)
                                * Math.max(0f, normal.normalize().dot(LIGHT_DIRECTION));
                    }
                }
                vertices[v++] = baseColor.x * shade;
                vertices[v++] = baseColor.y * shade;
                vertices[v++] = baseColor.z * shade;

                if (uvs != null) {
                    AIVector3D uv = uvs.get(i);
                    vertices[v++] = uv.x();
                    vertices[v++] = uv.y();
                } else {
                    vertices[v++] = 0f;
                    vertices[v++] = 0f;
                }

                // Normalise the weights so partially-weighted vertices are not shrunk.
                float total = 0f;
                for (int k = 0; k < SkinnedMesh.MAX_INFLUENCES; k++) {
                    total += weights[i * SkinnedMesh.MAX_INFLUENCES + k];
                }
                if (total <= 0f) {
                    // Unweighted vertex: pin it to the root so it still follows the model.
                    weights[i * SkinnedMesh.MAX_INFLUENCES] = 1f;
                    joints[i * SkinnedMesh.MAX_INFLUENCES] = 0f;
                    total = 1f;
                }
                for (int k = 0; k < SkinnedMesh.MAX_INFLUENCES; k++) {
                    vertices[v + k] = joints[i * SkinnedMesh.MAX_INFLUENCES + k];
                    vertices[v + SkinnedMesh.MAX_INFLUENCES + k] =
                            weights[i * SkinnedMesh.MAX_INFLUENCES + k] / total;
                }
                v += SkinnedMesh.MAX_INFLUENCES * 2;
            }

            int[] indices = new int[mesh.mNumFaces() * 3];
            int index = 0;
            AIFace.Buffer faces = mesh.mFaces();
            for (int f = 0; f < mesh.mNumFaces(); f++) {
                AIFace face = faces.get(f);
                if (face.mNumIndices() != 3) {
                    continue;
                }
                IntBuffer buffer = face.mIndices();
                indices[index++] = buffer.get(0);
                indices[index++] = buffer.get(1);
                indices[index++] = buffer.get(2);
                triangles++;
            }
            if (index == 0) {
                continue;
            }
            if (index < indices.length) {
                indices = Arrays.copyOf(indices, index);
            }
            parts.add(new AnimatedModel.Part(new SkinnedMesh(vertices, indices), texture));
        }

        if (parts.isEmpty()) {
            throw new IOException("Model contains no triangles");
        }
        return new AnimatedModel(file.getFileName().toString(), parts, textures, skeleton,
                min, max, triangles);
    }

    /**
     * A material's diffuse colour, or white when it has none. Mirrors the static loader, so the
     * same .glb looks the same whether it is rigged or not.
     */
    private static Vector3f materialColor(PointerBuffer materials, int index) {
        if (materials == null || index < 0 || index >= materials.limit()) {
            return new Vector3f(1f, 1f, 1f);
        }
        AIMaterial material = AIMaterial.create(materials.get(index));
        AIColor4D color = AIColor4D.create();
        if (aiGetMaterialColor(material, AI_MATKEY_COLOR_DIFFUSE, aiTextureType_NONE, 0, color)
                == aiReturn_SUCCESS) {
            Vector3f result = new Vector3f(color.r(), color.g(), color.b());
            // Pure black is what an export that relies on a texture leaves behind; white is the
            // safer reading of it than a model nobody can see.
            return result.lengthSquared() < 1e-5f ? new Vector3f(1f, 1f, 1f) : result;
        }
        return new Vector3f(1f, 1f, 1f);
    }

    private static int boneIndexOf(Skeleton skeleton, String boneName) {
        int node = skeleton.nodeIndex(boneName);
        return node < 0 ? -1 : skeleton.boneIndexOfNode(node);
    }

    // ---------------------------------------------------------------------
    // Animations
    // ---------------------------------------------------------------------

    private static List<AnimationClip> readClips(AIScene scene, Skeleton skeleton) {
        List<AnimationClip> clips = new ArrayList<>();
        PointerBuffer animations = scene.mAnimations();
        for (int a = 0; a < scene.mNumAnimations(); a++) {
            AIAnimation animation = AIAnimation.create(animations.get(a));
            double ticksPerSecond = animation.mTicksPerSecond() > 0 ? animation.mTicksPerSecond() : 25.0;
            float duration = (float) (animation.mDuration() / ticksPerSecond);

            List<AnimationClip.Channel> channels = new ArrayList<>();
            PointerBuffer channelBuffer = animation.mChannels();
            for (int c = 0; c < animation.mNumChannels(); c++) {
                AINodeAnim nodeAnim = AINodeAnim.create(channelBuffer.get(c));
                int node = skeleton.nodeIndex(nodeAnim.mNodeName().dataString());
                if (node < 0) {
                    continue; // channel for a node this skeleton does not have
                }
                channels.add(readChannel(nodeAnim, node, ticksPerSecond));
            }
            String name = animation.mName().dataString();
            clips.add(new AnimationClip(name == null || name.isBlank() ? "clip" + a : name,
                    duration, channels.toArray(new AnimationClip.Channel[0])));
        }
        return clips;
    }

    /**
     * The scene's own node transforms as a one-key clip on another rig's skeleton.
     *
     * <p>Only nodes that have moved away from their rest transform are keyed, so a file that is
     * simply the rig at rest - which is what an export taken in Rest Position, or with no
     * keyframes, comes out as - yields nothing and is reported as having no pose rather than
     * playing as a pose that does not move anything.
     */
    private static AnimationClip readPose(AIScene scene, Skeleton skeleton, String name) {
        Map<String, Matrix4f> locals = new HashMap<>();
        collectLocals(scene.mRootNode(), locals);

        List<AnimationClip.Channel> channels = new ArrayList<>();
        for (Map.Entry<String, Matrix4f> entry : locals.entrySet()) {
            int node = skeleton.nodeIndex(entry.getKey());
            if (node < 0 || entry.getValue().equals(skeleton.restTransform(node), POSE_EPSILON)) {
                continue;
            }
            Matrix4f local = entry.getValue();
            float[] at = {0f};
            channels.add(new AnimationClip.Channel(node,
                    at, new Vector3f[]{local.getTranslation(new Vector3f())},
                    at, new Quaternionf[]{local.getUnnormalizedRotation(new Quaternionf()).normalize()},
                    at, new Vector3f[]{local.getScale(new Vector3f())}));
        }
        return channels.isEmpty() ? null
                : new AnimationClip(name, POSE_SECONDS, channels.toArray(new AnimationClip.Channel[0]));
    }

    /** Every node's own local transform, by name, so a pose can be read off the hierarchy. */
    private static void collectLocals(AINode node, Map<String, Matrix4f> out) {
        out.putIfAbsent(node.mName().dataString(), toMatrix(node.mTransformation()));
        PointerBuffer children = node.mChildren();
        for (int i = 0; i < node.mNumChildren(); i++) {
            collectLocals(AINode.create(children.get(i)), out);
        }
    }

    private static AnimationClip.Channel readChannel(AINodeAnim nodeAnim, int node, double ticksPerSecond) {
        int positionCount = nodeAnim.mNumPositionKeys();
        float[] positionTimes = new float[positionCount];
        Vector3f[] positions = new Vector3f[positionCount];
        AIVectorKey.Buffer positionKeys = nodeAnim.mPositionKeys();
        for (int i = 0; i < positionCount; i++) {
            AIVectorKey key = positionKeys.get(i);
            positionTimes[i] = (float) (key.mTime() / ticksPerSecond);
            positions[i] = new Vector3f(key.mValue().x(), key.mValue().y(), key.mValue().z());
        }

        int rotationCount = nodeAnim.mNumRotationKeys();
        float[] rotationTimes = new float[rotationCount];
        Quaternionf[] rotations = new Quaternionf[rotationCount];
        AIQuatKey.Buffer rotationKeys = nodeAnim.mRotationKeys();
        for (int i = 0; i < rotationCount; i++) {
            AIQuatKey key = rotationKeys.get(i);
            rotationTimes[i] = (float) (key.mTime() / ticksPerSecond);
            rotations[i] = new Quaternionf(key.mValue().x(), key.mValue().y(),
                    key.mValue().z(), key.mValue().w());
        }

        int scaleCount = nodeAnim.mNumScalingKeys();
        float[] scaleTimes = new float[scaleCount];
        Vector3f[] scales = new Vector3f[scaleCount];
        AIVectorKey.Buffer scaleKeys = nodeAnim.mScalingKeys();
        for (int i = 0; i < scaleCount; i++) {
            AIVectorKey key = scaleKeys.get(i);
            scaleTimes[i] = (float) (key.mTime() / ticksPerSecond);
            scales[i] = new Vector3f(key.mValue().x(), key.mValue().y(), key.mValue().z());
        }

        return new AnimationClip.Channel(node, positionTimes, positions,
                rotationTimes, rotations, scaleTimes, scales);
    }

    // ---------------------------------------------------------------------
    // Textures and matrices
    // ---------------------------------------------------------------------

    private static Texture loadTexture(AIScene scene, PointerBuffer materials, int index,
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
                return null;
            }
            String texturePath = path.dataString();
            Texture texture = texturePath.startsWith("*")
                    ? embedded(scene, texturePath)
                    : fromDisk(modelFile, texturePath);
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

    private static Texture embedded(AIScene scene, String reference) {
        try {
            int index = Integer.parseInt(reference.substring(1));
            PointerBuffer sceneTextures = scene.mTextures();
            if (sceneTextures == null || index < 0 || index >= scene.mNumTextures()) {
                return null;
            }
            AITexture texture = AITexture.create(sceneTextures.get(index));
            if (texture.mHeight() == 0) {
                return Texture.fromEncodedBytes(texture.pcDataCompressed());
            }
            return null;
        } catch (IOException | RuntimeException e) {
            System.err.println("[Models] Could not decode embedded texture: " + e.getMessage());
            return null;
        }
    }

    private static Texture fromDisk(Path modelFile, String texturePath) {
        Path directory = modelFile.toAbsolutePath().getParent();
        if (directory == null) {
            return null;
        }
        Path candidate = directory.resolve(texturePath.replace('\\', '/')).normalize();
        if (!Files.isRegularFile(candidate)) {
            return null;
        }
        try {
            return Texture.fromFile(candidate);
        } catch (IOException e) {
            return null;
        }
    }

    /** Assimp matrices are row-major; JOML's constructor takes columns. */
    private static Matrix4f toMatrix(AIMatrix4x4 m) {
        return new Matrix4f(
                m.a1(), m.b1(), m.c1(), m.d1(),
                m.a2(), m.b2(), m.c2(), m.d2(),
                m.a3(), m.b3(), m.c3(), m.d3(),
                m.a4(), m.b4(), m.c4(), m.d4());
    }
}

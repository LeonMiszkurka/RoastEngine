package net.coffeebrewia.roastengine.render.anim;

import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * The node hierarchy of an animated model, flattened into arrays.
 *
 * <p>Nodes are stored parents-first, so computing world transforms is a single forward pass
 * instead of a recursive walk. Only some nodes are bones that actually skin vertices; the rest
 * exist to carry transforms (control rigs, IK helpers and so on).
 */
public final class Skeleton {

    /** Upper bound on bones in one model, matching the array size in the skinning shader. */
    public static final int MAX_BONES = 180;

    private final String[] nodeNames;
    private final Matrix4f[] nodeLocalRest;
    private final int[] nodeParents;
    /** Bone index for each node, or -1 when the node does not skin anything. */
    private final int[] nodeBoneIndex;
    private final Matrix4f[] boneOffsets;
    private final Matrix4f globalInverse;
    private final int boneCount;
    private final Map<String, Integer> nodeIndexByName;

    public Skeleton(String[] nodeNames, Matrix4f[] nodeLocalRest, int[] nodeParents,
                    int[] nodeBoneIndex, Matrix4f[] boneOffsets, Matrix4f globalInverse,
                    int boneCount) {
        this.nodeNames = nodeNames;
        this.nodeLocalRest = nodeLocalRest;
        this.nodeParents = nodeParents;
        this.nodeBoneIndex = nodeBoneIndex;
        this.boneOffsets = boneOffsets;
        this.globalInverse = globalInverse;
        this.boneCount = boneCount;

        this.nodeIndexByName = new HashMap<>();
        for (int i = 0; i < nodeNames.length; i++) {
            nodeIndexByName.putIfAbsent(nodeNames[i], i);
        }
    }

    public int nodeCount() {
        return nodeNames.length;
    }

    /** The node this one hangs off, or -1 for a root. Lets a tool draw the skeleton. */
    public int nodeParent(int index) {
        return index >= 0 && index < nodeParents.length ? nodeParents[index] : -1;
    }

    public int boneCount() {
        return boneCount;
    }

    public String nodeName(int index) {
        return nodeNames[index];
    }

    public int nodeIndex(String name) {
        return nodeIndexByName.getOrDefault(name, -1);
    }

    /** Bone index for a node, or -1 when that node does not skin any vertices. */
    public int boneIndexOfNode(int node) {
        return nodeBoneIndex[node];
    }

    /** The node's transform in its rest pose, used when an animation does not touch it. */
    public Matrix4f restTransform(int node) {
        return nodeLocalRest[node];
    }

    /**
     * Turns per-node local transforms into the bone matrices the shader wants.
     *
     * @param localTransforms one entry per node, already sampled from an animation
     * @param outPalette      filled with {@code globalInverse * worldTransform * boneOffset}
     * @param scratchWorld    reusable array of world transforms, one per node
     */
    public void buildPalette(Matrix4f[] localTransforms, Matrix4f[] outPalette, Matrix4f[] scratchWorld) {
        for (int node = 0; node < nodeNames.length; node++) {
            int parent = nodeParents[node];
            if (parent < 0) {
                scratchWorld[node].set(localTransforms[node]);
            } else {
                // Parents come first, so the parent's world transform is already final.
                scratchWorld[parent].mul(localTransforms[node], scratchWorld[node]);
            }
            int bone = nodeBoneIndex[node];
            if (bone >= 0 && bone < outPalette.length) {
                globalInverse.mul(scratchWorld[node], outPalette[bone]).mul(boneOffsets[bone]);
            }
        }
    }

    /**
     * Where one node has ended up in model space, for hanging something off a bone - a held
     * item in the hand, say.
     *
     * <p>This is not the skinning matrix: that one takes a vertex from its bind pose to the
     * posed one, so it moves vertices rather than describing where the bone <em>is</em>. This is
     * the bone's own transform, which is what an attachment wants.
     *
     * @param scratchWorld the world transforms filled in by the last {@link #buildPalette} call
     * @return {@code out}, or null when the model has no node by that name
     */
    public Matrix4f nodeModelTransform(String name, Matrix4f[] scratchWorld, Matrix4f out) {
        int node = nodeIndex(name);
        if (node < 0) {
            return null;
        }
        return globalInverse.mul(scratchWorld[node], out);
    }

    /** A palette with every bone left in its rest pose, for models with no animation playing. */
    public void buildRestPalette(Matrix4f[] outPalette, Matrix4f[] scratchWorld) {
        buildPalette(nodeLocalRest, outPalette, scratchWorld);
    }
}

package net.coffeebrewia.roastengine.world;

import net.coffeebrewia.roastengine.render.Mesh;
import net.coffeebrewia.roastengine.render.model.ModelAsset;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The table and the spawn gun that stand in every world.
 *
 * <p>Wherever a world drops you, there is a table beside you with a spawn gun on it. Pick the gun
 * up with <b>E</b> and press <b>R</b> to open the item list - everything every installed mod
 * ships, and everything already in the level - and put any of it in front of you.
 *
 * <p>Both are built here out of boxes rather than loaded from a file, because they have to exist
 * in worlds that never heard of them: a level someone made last week still gets a spawn gun. That
 * also means no mod can accidentally replace them.
 */
public final class SpawnKit {

    /** What the gun is called, which is how the game recognises the one being carried. */
    public static final String GUN_NAME = "Spawn Gun";
    public static final String TABLE_NAME = "Spawn Table";
    /** Where the table stands relative to the spawn: to the right, within arm's reach. */
    private static final float TABLE_SIDE_OFFSET = 1.3f;
    private static final float TABLE_HEIGHT = 0.92f;

    private SpawnKit() {
    }

    /** Switched off with {@code -Droastengine.noSpawnGun=true}, for a clean screenshot. */
    public static boolean wanted() {
        return !Boolean.getBoolean("roastengine.noSpawnGun");
    }

    /** The table and the gun on it, so they can be moved together afterwards. */
    public record Kit(WorldObject table, WorldObject gun, Vector3f home) {

        /**
         * Moves the pair to stand beside a spot - used when a world teleports the player into a
         * level of its own, so the gun is where they are rather than where the scene starts.
         *
         * <p>They move by their script offset rather than by their matrix, because collision
         * boxes are worked out where an object was built and the offset is what collision then
         * allows for.
         */
        public void moveBeside(Vector3f spot, float groundY) {
            Vector3f to = new Vector3f(spot.x + TABLE_SIDE_OFFSET, groundY, spot.z);
            Vector3f shift = new Vector3f(to).sub(home);
            table.scriptOffset.set(shift);
            if (!gun.carried) {
                gun.scriptOffset.set(shift);
            }
        }
    }

    /**
     * Puts the table and gun into a world, beside wherever the player starts.
     *
     * @param groundY the floor height at the spawn, so the table stands on it rather than in it
     */
    public static Kit place(LoadedWorld world, Vector3f spawn, float groundY) {
        if (world == null || !wanted()) {
            return null;
        }
        float x = spawn.x + TABLE_SIDE_OFFSET;
        float z = spawn.z;

        Matrix4f tableAt = new Matrix4f().translation(x, groundY, z);
        WorldObject table = new WorldObject(TABLE_NAME, "RoastEngine", table(), tableAt,
                true, false, false, "", "", "", 0f);
        world.add(table);

        Matrix4f gunAt = new Matrix4f().translation(x, groundY + TABLE_HEIGHT + 0.04f, z);
        WorldObject gun = new WorldObject(GUN_NAME, "RoastEngine", gun(), gunAt,
                false, false, false, "", "", "Spawn Gun - E to pick up, R for the item list", 0f);
        gun.holdable = true;
        // The hand closes around the grip rather than the middle of the barrel.
        gun.grip = new Vector3f(0f, 0.03f, 0.06f);
        world.add(gun);
        return new Kit(table, gun, new Vector3f(x, groundY, z));
    }

    /** True for the gun this kit made, whoever is holding it. */
    public static boolean isSpawnGun(WorldObject object) {
        return object != null && GUN_NAME.equals(object.name);
    }

    // ------------------------------------------------------------------
    // The models
    // ------------------------------------------------------------------

    /** A plain trestle table: a top and four legs. */
    private static ModelAsset table() {
        Boxes boxes = new Boxes();
        float top = TABLE_HEIGHT;
        boxes.add(0f, top - 0.03f, 0f, 1.10f, 0.06f, 0.70f, 0.52f, 0.38f, 0.24f);
        float legX = 0.48f;
        float legZ = 0.28f;
        for (float sx : new float[]{-legX, legX}) {
            for (float sz : new float[]{-legZ, legZ}) {
                boxes.add(sx, (top - 0.06f) / 2f, sz, 0.07f, top - 0.06f, 0.07f, 0.40f, 0.29f, 0.18f);
            }
        }
        return boxes.toAsset("spawn_table");
    }

    /** A blocky pistol shape, small enough to read as something you hold. */
    private static ModelAsset gun() {
        Boxes boxes = new Boxes();
        // Built around the grip at the origin with the barrel down -Z, the way the engine
        // expects anything it puts in a hand.
        boxes.add(0f, 0.06f, -0.05f, 0.05f, 0.08f, 0.26f, 0.22f, 0.24f, 0.28f);   // body
        boxes.add(0f, 0.09f, -0.20f, 0.035f, 0.035f, 0.14f, 0.30f, 0.33f, 0.38f); // barrel
        boxes.add(0f, -0.02f, 0.03f, 0.045f, 0.12f, 0.06f, 0.18f, 0.14f, 0.12f);  // grip
        boxes.add(0f, 0.13f, -0.06f, 0.03f, 0.03f, 0.06f, 0.95f, 0.70f, 0.20f);   // the glowing bit
        return boxes.toAsset("spawn_gun");
    }

    /**
     * Gathers boxes into one mesh.
     *
     * <p>The same per-face shading the engine's cube uses, so these read as solid without a
     * lighting model. Collision is per triangle, as it is for anything loaded from a file, so a
     * table made this way is something you can actually put your back against.
     */
    private static final class Boxes {

        private static final float[][][] FACES = {
                {{-.5f, -.5f, .5f}, {.5f, -.5f, .5f}, {.5f, .5f, .5f}, {-.5f, .5f, .5f}},
                {{.5f, -.5f, -.5f}, {-.5f, -.5f, -.5f}, {-.5f, .5f, -.5f}, {.5f, .5f, -.5f}},
                {{.5f, -.5f, .5f}, {.5f, -.5f, -.5f}, {.5f, .5f, -.5f}, {.5f, .5f, .5f}},
                {{-.5f, -.5f, -.5f}, {-.5f, -.5f, .5f}, {-.5f, .5f, .5f}, {-.5f, .5f, -.5f}},
                {{-.5f, .5f, .5f}, {.5f, .5f, .5f}, {.5f, .5f, -.5f}, {-.5f, .5f, -.5f}},
                {{-.5f, -.5f, -.5f}, {.5f, -.5f, -.5f}, {.5f, -.5f, .5f}, {-.5f, -.5f, .5f}},
        };
        private static final float[] SHADE = {0.85f, 0.70f, 0.95f, 0.75f, 1.0f, 0.55f};

        private final List<Float> vertices = new ArrayList<>();
        private final List<Integer> indices = new ArrayList<>();
        private final Vector3f min = new Vector3f(Float.MAX_VALUE);
        private final Vector3f max = new Vector3f(-Float.MAX_VALUE);

        void add(float cx, float cy, float cz, float sx, float sy, float sz,
                 float r, float g, float b) {
            for (int face = 0; face < FACES.length; face++) {
                int base = vertices.size() / Mesh.FLOATS_PER_VERTEX;
                for (float[] corner : FACES[face]) {
                    float x = cx + corner[0] * sx;
                    float y = cy + corner[1] * sy;
                    float z = cz + corner[2] * sz;
                    vertices.add(x);
                    vertices.add(y);
                    vertices.add(z);
                    vertices.add(r * SHADE[face]);
                    vertices.add(g * SHADE[face]);
                    vertices.add(b * SHADE[face]);
                    min.min(new Vector3f(x, y, z));
                    max.max(new Vector3f(x, y, z));
                }
                for (int index : new int[]{0, 1, 2, 0, 2, 3}) {
                    indices.add(base + index);
                }
            }
        }

        ModelAsset toAsset(String name) {
            float[] vertexArray = new float[vertices.size()];
            for (int i = 0; i < vertexArray.length; i++) {
                vertexArray[i] = vertices.get(i);
            }
            int[] indexArray = new int[indices.size()];
            for (int i = 0; i < indexArray.length; i++) {
                indexArray[i] = indices.get(i);
            }
            // Collision wants positions on their own, without the colours interleaved.
            float[] positions = new float[vertexArray.length / Mesh.FLOATS_PER_VERTEX * 3];
            for (int vertex = 0, at = 0; vertex < vertexArray.length; vertex += Mesh.FLOATS_PER_VERTEX) {
                positions[at++] = vertexArray[vertex];
                positions[at++] = vertexArray[vertex + 1];
                positions[at++] = vertexArray[vertex + 2];
            }
            ModelAsset.Part part = new ModelAsset.Part(new Mesh(vertexArray, indexArray), null,
                    new Vector3f(min), new Vector3f(max), positions, indexArray, 1f, new Vector3f());
            return new ModelAsset(name, List.of(part), List.of(), new Vector3f(min),
                    new Vector3f(max), indexArray.length / 3);
        }
    }
}

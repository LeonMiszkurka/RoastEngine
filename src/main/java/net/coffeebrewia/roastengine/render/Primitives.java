package net.coffeebrewia.roastengine.render;

/** Procedural geometry helpers. */
public final class Primitives {

    private Primitives() {
    }

    /** A flat XZ plane centred on the origin, facing +Y. */
    public static Mesh plane(float size, float r, float g, float b) {
        float h = size / 2f;
        float[] vertices = {
                -h, 0f, -h, r, g, b,
                 h, 0f, -h, r, g, b,
                 h, 0f,  h, r, g, b,
                -h, 0f,  h, r, g, b,
        };
        int[] indices = {0, 2, 1, 0, 3, 2};
        return new Mesh(vertices, indices);
    }

    /**
     * A unit cube (edge length 1, centred on origin). Each face gets a slightly different
     * brightness so shapes read clearly without a lighting model.
     */
    public static Mesh cube(float r, float g, float b) {
        // Face definitions: 4 corners each, counter-clockwise when viewed from outside.
        float[][][] faces = {
                {{-.5f, -.5f, .5f}, {.5f, -.5f, .5f}, {.5f, .5f, .5f}, {-.5f, .5f, .5f}},     // +Z
                {{.5f, -.5f, -.5f}, {-.5f, -.5f, -.5f}, {-.5f, .5f, -.5f}, {.5f, .5f, -.5f}}, // -Z
                {{.5f, -.5f, .5f}, {.5f, -.5f, -.5f}, {.5f, .5f, -.5f}, {.5f, .5f, .5f}},     // +X
                {{-.5f, -.5f, -.5f}, {-.5f, -.5f, .5f}, {-.5f, .5f, .5f}, {-.5f, .5f, -.5f}}, // -X
                {{-.5f, .5f, .5f}, {.5f, .5f, .5f}, {.5f, .5f, -.5f}, {-.5f, .5f, -.5f}},     // +Y
                {{-.5f, -.5f, -.5f}, {.5f, -.5f, -.5f}, {.5f, -.5f, .5f}, {-.5f, -.5f, .5f}}, // -Y
        };
        float[] shade = {0.85f, 0.70f, 0.95f, 0.75f, 1.0f, 0.55f};

        float[] vertices = new float[faces.length * 4 * Mesh.FLOATS_PER_VERTEX];
        int[] indices = new int[faces.length * 6];
        int v = 0;
        int i = 0;
        for (int f = 0; f < faces.length; f++) {
            for (float[] corner : faces[f]) {
                vertices[v++] = corner[0];
                vertices[v++] = corner[1];
                vertices[v++] = corner[2];
                vertices[v++] = r * shade[f];
                vertices[v++] = g * shade[f];
                vertices[v++] = b * shade[f];
            }
            int base = f * 4;
            indices[i++] = base;
            indices[i++] = base + 1;
            indices[i++] = base + 2;
            indices[i++] = base;
            indices[i++] = base + 2;
            indices[i++] = base + 3;
        }
        return new Mesh(vertices, indices);
    }
}

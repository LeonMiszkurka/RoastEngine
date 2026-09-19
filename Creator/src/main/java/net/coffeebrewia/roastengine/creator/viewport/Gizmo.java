package net.coffeebrewia.roastengine.creator.viewport;

import net.coffeebrewia.roastengine.modding.scene.SceneObject;
import net.coffeebrewia.roastengine.render.Camera;
import net.coffeebrewia.roastengine.render.Mesh;
import net.coffeebrewia.roastengine.render.ShaderProgram;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Blender-style move and rotate handles for the selected object.
 *
 * <p>Three coloured arrows (move) or three rings (rotate) sit on the object, drawn on top of the
 * scene so they are never buried inside it. Dragging a handle constrains the transform to that
 * one axis; holding <b>Ctrl</b> snaps, as Blender does.
 *
 * <p>Handles are picked in screen space - the mouse is compared against the projected arrow or
 * ring in pixels - which stays accurate no matter how the gizmo is foreshortened. The gizmo is
 * scaled by its distance from the camera so it keeps the same size on screen at any range.
 */
public final class Gizmo {

    /** What dragging in the viewport does. */
    public enum Tool {
        SELECT("Select", "Q"),
        MOVE("Move", "G"),
        ROTATE("Rotate", "R");

        private final String label;
        private final String key;

        Tool(String label, String key) {
            this.label = label;
            this.key = key;
        }

        public String label() {
            return label;
        }

        public String key() {
            return key;
        }
    }

    /** Screen-space grab radius, in window points. */
    private static final float GRAB_PIXELS = 11f;
    /** Gizmo size as a fraction of its distance from the camera. */
    private static final float SCREEN_SCALE = 0.135f;
    private static final float SNAP_METRES = 0.25f;
    private static final float SNAP_DEGREES = 15f;
    private static final int RING_SEGMENTS = 48;

    private static final Vector3f[] AXES = {
            new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1)};
    /** X red, Y green, Z blue, as in Blender. */
    private static final Vector3f[] COLOURS = {
            new Vector3f(0.93f, 0.26f, 0.32f),
            new Vector3f(0.46f, 0.86f, 0.32f),
            new Vector3f(0.29f, 0.56f, 0.96f)};
    private static final Vector3f HOT = new Vector3f(1.0f, 0.85f, 0.25f);

    private Mesh arrow;
    private Mesh ring;

    /** Axis being dragged (0/1/2), or -1. */
    private int dragAxis = -1;
    /** Axis the mouse is over, refreshed each frame so it can be drawn highlighted. */
    private int hoverAxis = -1;
    private final Vector3f dragStartPosition = new Vector3f();
    private final Vector3f dragStartRotation = new Vector3f();
    private float dragStartValue;

    // ---------------------------------------------------------------------
    // Geometry
    // ---------------------------------------------------------------------

    /** Builds the handle meshes. Main thread only. */
    public void create() {
        if (arrow == null) {
            arrow = buildArrow();
            ring = buildRing();
        }
    }

    public void dispose() {
        if (arrow != null) {
            arrow.dispose();
            arrow = null;
        }
        if (ring != null) {
            ring.dispose();
            ring = null;
        }
    }

    /**
     * An arrow along +Y, one unit long: a slim shaft with a pyramid head.
     *
     * <p>Vertex colours are black because the shader adds the axis colour through
     * {@code uEmissive}, which lets all three axes share one mesh.
     */
    private static Mesh buildArrow() {
        List<Float> vertices = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        float shaft = 0.020f;
        float headBase = 0.062f;
        float headStart = 0.76f;
        box(vertices, indices, -shaft, 0f, -shaft, shaft, headStart, shaft);
        pyramid(vertices, indices, headBase, headStart, 1f);
        return new Mesh(toFloats(vertices), toInts(indices));
    }

    /** A ring in the XZ plane, radius one, with a square cross-section so it never vanishes. */
    private static Mesh buildRing() {
        List<Float> vertices = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        float thickness = 0.018f;
        for (int i = 0; i < RING_SEGMENTS; i++) {
            double a0 = i * 2 * Math.PI / RING_SEGMENTS;
            double a1 = (i + 1) * 2 * Math.PI / RING_SEGMENTS;
            // Four corners of the cross-section at each end of the segment.
            for (double angle : new double[]{a0, a1}) {
                float cx = (float) Math.cos(angle);
                float cz = (float) Math.sin(angle);
                addVertex(vertices, cx * (1 - thickness), -thickness, cz * (1 - thickness));
                addVertex(vertices, cx * (1 + thickness), -thickness, cz * (1 + thickness));
                addVertex(vertices, cx * (1 + thickness), thickness, cz * (1 + thickness));
                addVertex(vertices, cx * (1 - thickness), thickness, cz * (1 - thickness));
            }
            int base = i * 8;
            for (int face = 0; face < 4; face++) {
                int a = base + face;
                int b = base + (face + 1) % 4;
                quad(indices, a, b, base + 4 + (face + 1) % 4, base + 4 + face);
            }
        }
        return new Mesh(toFloats(vertices), toInts(indices));
    }

    private static void box(List<Float> v, List<Integer> i,
                            float x0, float y0, float z0, float x1, float y1, float z1) {
        int base = v.size() / Mesh.FLOATS_PER_VERTEX;
        float[][] corners = {
                {x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0},
                {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
        for (float[] c : corners) {
            addVertex(v, c[0], c[1], c[2]);
        }
        int[][] faces = {{0, 1, 2, 3}, {5, 4, 7, 6}, {4, 0, 3, 7}, {1, 5, 6, 2}, {3, 2, 6, 7}, {4, 5, 1, 0}};
        for (int[] f : faces) {
            quad(i, base + f[0], base + f[1], base + f[2], base + f[3]);
        }
    }

    private static void pyramid(List<Float> v, List<Integer> i, float half, float y0, float y1) {
        int base = v.size() / Mesh.FLOATS_PER_VERTEX;
        addVertex(v, -half, y0, -half);
        addVertex(v, half, y0, -half);
        addVertex(v, half, y0, half);
        addVertex(v, -half, y0, half);
        addVertex(v, 0f, y1, 0f);
        for (int side = 0; side < 4; side++) {
            i.add(base + side);
            i.add(base + (side + 1) % 4);
            i.add(base + 4);
        }
        quad(i, base + 3, base + 2, base + 1, base);
    }

    private static void addVertex(List<Float> v, float x, float y, float z) {
        v.add(x);
        v.add(y);
        v.add(z);
        v.add(0f);
        v.add(0f);
        v.add(0f);
    }

    private static void quad(List<Integer> i, int a, int b, int c, int d) {
        i.add(a);
        i.add(b);
        i.add(c);
        i.add(a);
        i.add(c);
        i.add(d);
    }

    private static float[] toFloats(List<Float> list) {
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    private static int[] toInts(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    /**
     * Draws the handles for the selected object.
     *
     * <p>The caller has the world shader bound and the viewport set. Depth testing is turned off
     * here so the gizmo is never hidden inside the object it belongs to, and fog is pushed out of
     * range so the colours stay flat.
     */
    public void draw(ShaderProgram shader, SceneObject selected, Camera camera, Tool tool) {
        if (arrow == null || selected == null || tool == Tool.SELECT) {
            return;
        }
        float scale = gizmoScale(selected, camera);
        org.lwjgl.opengl.GL11C.glDisable(org.lwjgl.opengl.GL11C.GL_DEPTH_TEST);
        shader.setUniform("uFogDistance", 1.0e9f);
        shader.setUniform("uEmissiveStrength", 1f);

        for (int axis = 0; axis < 3; axis++) {
            Vector3f colour = axis == dragAxis || (dragAxis < 0 && axis == hoverAxis)
                    ? HOT : COLOURS[axis];
            shader.setUniform("uEmissive", colour);
            shader.setUniform("uModel", handleMatrix(selected, axis, scale, tool));
            (tool == Tool.MOVE ? arrow : ring).draw();
        }

        shader.setUniform("uEmissive", new Vector3f());
        org.lwjgl.opengl.GL11C.glEnable(org.lwjgl.opengl.GL11C.GL_DEPTH_TEST);
    }

    /** Both meshes are authored around +Y, so each axis is that mesh turned onto its axis. */
    private Matrix4f handleMatrix(SceneObject selected, int axis, float scale, Tool tool) {
        Matrix4f matrix = new Matrix4f().translation(selected.position);
        switch (axis) {
            case 0 -> matrix.rotateZ((float) -Math.PI / 2);
            case 2 -> matrix.rotateX((float) Math.PI / 2);
            default -> {
            }
        }
        return matrix.scale(scale * (tool == Tool.ROTATE ? 0.85f : 1f));
    }

    private static float gizmoScale(SceneObject selected, Camera camera) {
        float distance = camera.position().distance(selected.position);
        return Math.max(0.35f, distance * SCREEN_SCALE);
    }

    // ---------------------------------------------------------------------
    // Picking and dragging
    // ---------------------------------------------------------------------

    /**
     * Works out which handle the mouse is over, so it can be drawn highlighted and grabbed.
     *
     * @return the axis under the cursor (0/1/2), or -1
     */
    public int hitTest(SceneObject selected, Camera camera, Tool tool, ViewportRay ray,
                       float mouseX, float mouseY) {
        hoverAxis = -1;
        if (selected == null || tool == Tool.SELECT) {
            return -1;
        }
        float scale = gizmoScale(selected, camera);
        float best = GRAB_PIXELS;
        for (int axis = 0; axis < 3; axis++) {
            float distance = tool == Tool.MOVE
                    ? distanceToArrow(selected, axis, scale, ray, mouseX, mouseY)
                    : distanceToRing(selected, axis, scale * 0.85f, ray, mouseX, mouseY);
            if (distance < best) {
                best = distance;
                hoverAxis = axis;
            }
        }
        return hoverAxis;
    }

    private float distanceToArrow(SceneObject selected, int axis, float scale, ViewportRay ray,
                                  float mouseX, float mouseY) {
        Vector3f tip = new Vector3f(AXES[axis]).mul(scale).add(selected.position);
        Vector3f from = ray.project(selected.position);
        Vector3f to = ray.project(tip);
        if (from == null || to == null) {
            return Float.MAX_VALUE;
        }
        return pointToSegment(mouseX, mouseY, from.x, from.y, to.x, to.y);
    }

    private float distanceToRing(SceneObject selected, int axis, float scale, ViewportRay ray,
                                 float mouseX, float mouseY) {
        Vector3f u1 = new Vector3f();
        Vector3f u2 = new Vector3f();
        planeBasis(AXES[axis], u1, u2);
        float best = Float.MAX_VALUE;
        Vector3f previous = null;
        // Walk the ring and measure against each projected segment; 24 samples is plenty at
        // the size a gizmo is ever drawn.
        for (int i = 0; i <= 24; i++) {
            double angle = i * 2 * Math.PI / 24;
            Vector3f point = new Vector3f(selected.position)
                    .fma((float) Math.cos(angle) * scale, u1)
                    .fma((float) Math.sin(angle) * scale, u2);
            Vector3f screen = ray.project(point);
            if (screen != null && previous != null) {
                best = Math.min(best, pointToSegment(mouseX, mouseY, previous.x, previous.y,
                        screen.x, screen.y));
            }
            previous = screen;
        }
        return best;
    }

    private static float pointToSegment(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax;
        float dy = by - ay;
        float lengthSquared = dx * dx + dy * dy;
        float t = lengthSquared <= 1e-6f ? 0f
                : Math.max(0f, Math.min(1f, ((px - ax) * dx + (py - ay) * dy) / lengthSquared));
        return (float) Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    /** Any two unit vectors across `axis`, used as the plane the rotate ring lives in. */
    private static void planeBasis(Vector3f axis, Vector3f u1, Vector3f u2) {
        Vector3f reference = Math.abs(axis.y) > 0.9f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        reference.cross(axis, u1).normalize();
        new Vector3f(axis).cross(u1, u2).normalize();
    }

    // ---------------------------------------------------------------------
    // Drag lifecycle
    // ---------------------------------------------------------------------

    public boolean isDragging() {
        return dragAxis >= 0;
    }

    public int hoverAxis() {
        return hoverAxis;
    }

    /** @return true if a handle was grabbed, meaning the viewport should not start looking */
    public boolean beginDrag(SceneObject selected, Camera camera, Tool tool, ViewportRay ray,
                             float mouseX, float mouseY) {
        int axis = hitTest(selected, camera, tool, ray, mouseX, mouseY);
        if (axis < 0) {
            return false;
        }
        dragAxis = axis;
        dragStartPosition.set(selected.position);
        dragStartRotation.set(selected.rotation);
        dragStartValue = tool == Tool.MOVE
                ? axisParameter(selected.position, AXES[axis], ray)
                : ringAngle(selected.position, AXES[axis], ray);
        return true;
    }

    /**
     * Applies the drag to the object.
     *
     * @param snap hold Ctrl to land on {@value #SNAP_METRES}m / {@value #SNAP_DEGREES} degree steps
     * @return true if the object changed
     */
    public boolean updateDrag(SceneObject selected, Tool tool, ViewportRay ray, boolean snap) {
        if (dragAxis < 0 || selected == null) {
            return false;
        }
        Vector3f axis = AXES[dragAxis];
        if (tool == Tool.MOVE) {
            float now = axisParameter(dragStartPosition, axis, ray);
            if (!Float.isFinite(now)) {
                return false;
            }
            float delta = now - dragStartValue;
            if (snap) {
                delta = Math.round(delta / SNAP_METRES) * SNAP_METRES;
            }
            Vector3f moved = new Vector3f(dragStartPosition).fma(delta, axis);
            if (moved.equals(selected.position, 1e-5f)) {
                return false;
            }
            selected.position.set(moved);
            return true;
        }

        float now = ringAngle(dragStartPosition, axis, ray);
        if (!Float.isFinite(now)) {
            return false;
        }
        float delta = (float) Math.toDegrees(wrapAngle(now - dragStartValue));
        if (snap) {
            delta = Math.round(delta / SNAP_DEGREES) * SNAP_DEGREES;
        }
        float before = component(selected.rotation, dragAxis);
        float after = component(dragStartRotation, dragAxis) - delta;
        if (Math.abs(after - before) < 1e-4f) {
            return false;
        }
        setComponent(selected.rotation, dragAxis, after);
        return true;
    }

    public void endDrag() {
        dragAxis = -1;
    }

    /** Cancels the drag and puts the object back where it started, as Escape does in Blender. */
    public void cancelDrag(SceneObject selected) {
        if (dragAxis >= 0 && selected != null) {
            selected.position.set(dragStartPosition);
            selected.rotation.set(dragStartRotation);
        }
        dragAxis = -1;
    }

    /** Where along the axis line the mouse ray currently points, in metres from `origin`. */
    private static float axisParameter(Vector3f origin, Vector3f axis, ViewportRay ray) {
        Vector3f w = new Vector3f(origin).sub(ray.origin());
        float b = axis.dot(ray.direction());
        float c = ray.direction().lengthSquared();
        float denominator = c - b * b;
        if (Math.abs(denominator) < 1e-6f) {
            return Float.NaN; // looking straight down the axis: no useful answer
        }
        return (axis.dot(w) * c - b * w.dot(ray.direction())) / denominator;
    }

    /** The angle of the mouse ray around the axis, measured in the ring's own plane. */
    private static float ringAngle(Vector3f origin, Vector3f axis, ViewportRay ray) {
        float denominator = ray.direction().dot(axis);
        if (Math.abs(denominator) < 1e-4f) {
            return Float.NaN; // ring seen exactly edge-on
        }
        float t = new Vector3f(origin).sub(ray.origin()).dot(axis) / denominator;
        Vector3f hit = new Vector3f(ray.direction()).mul(t).add(ray.origin()).sub(origin);
        Vector3f u1 = new Vector3f();
        Vector3f u2 = new Vector3f();
        planeBasis(axis, u1, u2);
        return (float) Math.atan2(hit.dot(u2), hit.dot(u1));
    }

    private static float wrapAngle(float radians) {
        while (radians > Math.PI) {
            radians -= 2 * Math.PI;
        }
        while (radians < -Math.PI) {
            radians += 2 * Math.PI;
        }
        return radians;
    }

    private static float component(Vector3f v, int axis) {
        return axis == 0 ? v.x : axis == 1 ? v.y : v.z;
    }

    private static void setComponent(Vector3f v, int axis, float value) {
        switch (axis) {
            case 0 -> v.x = value;
            case 1 -> v.y = value;
            default -> v.z = value;
        }
    }

    /** Axis letter for the status line while dragging. */
    public String axisLabel() {
        int axis = dragAxis >= 0 ? dragAxis : hoverAxis;
        return axis < 0 ? "" : axis == 0 ? "X" : axis == 1 ? "Y" : "Z";
    }

    /**
     * The mouse ray for one frame, plus the projection the other way, so the gizmo can work in
     * screen space without knowing how the editor lays its viewport out.
     */
    public static final class ViewportRay {
        private final Vector3f origin;
        private final Vector3f direction;
        private final Matrix4f viewProjection;
        private final float[] viewport;
        private final float windowHeight;

        public ViewportRay(Vector3f origin, Vector3f direction, Matrix4f viewProjection,
                           float[] viewport, float windowHeight) {
            this.origin = origin;
            this.direction = direction;
            this.viewProjection = viewProjection;
            this.viewport = viewport;
            this.windowHeight = windowHeight;
        }

        public Vector3f origin() {
            return origin;
        }

        public Vector3f direction() {
            return direction;
        }

        /** World point to window coordinates, or null when it is behind the camera. */
        public Vector3f project(Vector3f world) {
            Vector4f clip = viewProjection.transform(new Vector4f(world.x, world.y, world.z, 1f));
            if (clip.w <= 1e-4f) {
                return null;
            }
            float ndcX = clip.x / clip.w;
            float ndcY = clip.y / clip.w;
            float screenX = viewport[0] + (ndcX * 0.5f + 0.5f) * viewport[2];
            // NDC is bottom-up; window coordinates are top-down.
            float glY = viewport[1] + (ndcY * 0.5f + 0.5f) * viewport[3];
            return new Vector3f(screenX, windowHeight - glY, clip.w);
        }
    }
}

package net.coffeebrewia.roastengine.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Free-look first-person camera using yaw/pitch (radians).
 *
 * <p>Yaw 0 looks down -Z; positive yaw turns right, positive pitch looks down.
 */
public final class Camera {

    private static final float MAX_PITCH = (float) Math.toRadians(89.0);

    private final Vector3f position = new Vector3f();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private Matrix4f eyeView;
    private Matrix4f eyeProjection;

    private float yaw;
    private float pitch;
    private float fovDegrees = 70f;
    private float near = 0.05f;
    private float far = 500f;

    public Camera(float x, float y, float z) {
        position.set(x, y, z);
    }

    public void rotate(float deltaYaw, float deltaPitch) {
        yaw += deltaYaw;
        pitch = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, pitch + deltaPitch));
        // Keep yaw bounded to avoid float precision drift after long sessions.
        yaw %= (float) (Math.PI * 2);
    }

    /** Moves relative to the horizontal look direction (pitch does not affect walking). */
    public void moveHorizontal(float forwardAmount, float rightAmount) {
        float sin = (float) Math.sin(yaw);
        float cos = (float) Math.cos(yaw);
        position.x += sin * forwardAmount + cos * rightAmount;
        position.z += -cos * forwardAmount + sin * rightAmount;
    }

    public void moveVertical(float amount) {
        position.y += amount;
    }

    /**
     * The direction the camera looks, pitch included - for anything that follows the crosshair,
     * such as a ray into the world.
     */
    public Vector3f forward(Vector3f out) {
        float cosPitch = (float) Math.cos(pitch);
        return out.set((float) Math.sin(yaw) * cosPitch,
                -(float) Math.sin(pitch),
                -(float) Math.cos(yaw) * cosPitch).normalize();
    }

    /**
     * In VR the headset decides where the eyes are, so the camera hands back what the runtime
     * gave us instead of working it out from yaw and pitch. {@code null} returns to normal.
     */
    public void setEyeOverride(Matrix4f eyeView, Matrix4f eyeProjection) {
        this.eyeView = eyeView;
        this.eyeProjection = eyeProjection;
    }

    public Matrix4f viewMatrix() {
        if (eyeView != null) {
            return eyeView;
        }
        return view.identity()
                .rotateX(pitch)
                .rotateY(yaw)
                .translate(-position.x, -position.y, -position.z);
    }

    public Matrix4f projectionMatrix(float aspectRatio) {
        if (eyeProjection != null) {
            return eyeProjection;
        }
        return projection.setPerspective((float) Math.toRadians(fovDegrees), aspectRatio, near, far);
    }

    public void setFieldOfView(float degrees) {
        this.fovDegrees = degrees;
    }

    public Vector3f position() {
        return position;
    }

    /** Points the camera along a given yaw, which VR does from the headset. */
    public void setYaw(float radians) {
        this.yaw = radians;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public float near() {
        return near;
    }

    public float far() {
        return far;
    }
}

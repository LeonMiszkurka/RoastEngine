package net.coffeebrewia.roastengine.world;

import net.coffeebrewia.roastengine.render.Camera;
import org.joml.Vector3f;

/**
 * Finds what the player can interact with, and runs door and drink behaviour.
 *
 * <p>Targeting is "closest thing roughly in front of me within reach" rather than a precise ray,
 * which is far more forgiving for small props like a glass on a bar.
 */
public final class Interactions {

    /** Things worth a sound effect. */
    public interface Listener {
        default void doorMoved(WorldObject door, boolean opening) { }
        default void sipped(WorldObject drink) { }
        default void drinkPutDown(WorldObject drink) { }
    }

    private Listener listener = new Listener() { };

    public void setListener(Listener listener) {
        this.listener = listener == null ? new Listener() { } : listener;
    }

    /** How far the player can reach. */
    public static final float REACH = 2.6f;
    /** How wide the "in front of me" cone is: 1 = dead ahead, 0 = ninety degrees off. */
    private static final float MIN_FACING = 0.35f;
    /** Seconds a door takes to swing open. */
    private static final float DOOR_SPEED = 1.6f;
    /** How much of the glass one sip drinks: ten sips to a glass. */
    public static final float SIP_AMOUNT = 0.1f;

    private final Vector3f center = new Vector3f();
    private final Vector3f toObject = new Vector3f();
    private final Vector3f forward = new Vector3f();

    /** The drink currently raised to the camera, or null. */
    private WorldObject heldDrink;
    /** How far the held glass has travelled to the mouth, 0..1. */
    private float raise;
    private float sipCooldown;
    /** Sips taken since the level (re)started, across every drink. */
    private int totalSips;
    /** A door waiting to open, e.g. while the doorman looks the player over. */
    private WorldObject pendingDoor;
    private float pendingTimer;

    /** Closest interactive object in front of the player, or null. */
    public WorldObject findTarget(LoadedWorld world, Camera camera) {
        if (world == null) {
            return null;
        }
        Vector3f eye = camera.position();
        forward.set((float) Math.sin(camera.yaw()), 0f, -(float) Math.cos(camera.yaw()));

        WorldObject best = null;
        float bestScore = 0f;
        for (WorldObject object : world.objects()) {
            if (!object.isInteractive()) {
                continue;
            }
            object.center(center);
            toObject.set(center).sub(eye);
            float distance = toObject.length();
            if (distance > REACH || distance < 0.0001f) {
                continue;
            }
            toObject.div(distance);
            float facing = toObject.x * forward.x + toObject.z * forward.z;
            if (facing < MIN_FACING) {
                continue;
            }
            // Prefer things that are both close and well in front.
            float score = facing / Math.max(0.5f, distance);
            if (score > bestScore) {
                bestScore = score;
                best = object;
            }
        }
        return best;
    }

    /** The prompt to show, or null when there is nothing to interact with. */
    public String prompt(WorldObject target) {
        if (heldDrink != null) {
            return heldDrink.fill > 0f
                    ? "Hold E to drink   -   release to put it down"
                    : "Empty   -   release E to put it down";
        }
        if (target == null) {
            return null;
        }
        if (target.isDoor()) {
            return "Press E to " + (target.doorWantsOpen ? "close" : "open") + " the door";
        }
        if (target.isDrink()) {
            return target.fill > 0f
                    ? "Press E to " + (target.label.isEmpty() ? "sip the drink" : target.label)
                    : "The glass is empty";
        }
        return target.label.isEmpty() ? null : "Press E to " + target.label;
    }

    /** Opens a door after a short delay, instead of straight away. */
    public void openDoorAfter(WorldObject door, float seconds) {
        if (pendingDoor == null && !door.doorWantsOpen) {
            pendingDoor = door;
            pendingTimer = seconds;
        }
    }

    public boolean doorPending() {
        return pendingDoor != null;
    }

    public int totalSips() {
        return totalSips;
    }

    /** Handles a press of the interact key. */
    public void interact(WorldObject target) {
        if (target == null || heldDrink != null) {
            return;
        }
        if (target.isDoor()) {
            target.doorWantsOpen = !target.doorWantsOpen;
            listener.doorMoved(target, target.doorWantsOpen);
        } else if (target.isDrink() && target.fill > 0f) {
            heldDrink = target;
            raise = 0f;
        }
    }

    /** Advances doors and the drinking animation. */
    public void update(LoadedWorld world, float delta, boolean interactHeld) {
        if (pendingDoor != null) {
            pendingTimer -= delta;
            if (pendingTimer <= 0f) {
                WorldObject door = pendingDoor;
                pendingDoor = null;
                interact(door);
            }
        }
        if (world != null) {
            for (WorldObject object : world.objects()) {
                if (!object.isDoor()) {
                    continue;
                }
                float goal = object.doorWantsOpen ? 1f : 0f;
                float step = DOOR_SPEED * delta;
                object.openAmount = goal > object.openAmount
                        ? Math.min(goal, object.openAmount + step)
                        : Math.max(goal, object.openAmount - step);
            }
        }

        if (heldDrink == null) {
            return;
        }
        if (!interactHeld) {
            // Let go: lower the glass and put it back on the bar.
            raise -= delta * 3f;
            if (raise <= 0f) {
                raise = 0f;
                listener.drinkPutDown(heldDrink);
                heldDrink = null;
            }
            return;
        }
        raise = Math.min(1f, raise + delta * 3.5f);
        sipCooldown -= delta;
        // Only drink once the glass has actually reached the mouth.
        if (raise >= 1f && sipCooldown <= 0f && heldDrink.fill > 0f) {
            heldDrink.fill = Math.max(0f, heldDrink.fill - SIP_AMOUNT);
            sipCooldown = 0.7f;
            totalSips++;
            listener.sipped(heldDrink);
        }
    }

    public WorldObject heldDrink() {
        return heldDrink;
    }

    /** 0 = on the bar, 1 = at the player's mouth. */
    public float raise() {
        return raise;
    }

    public void reset() {
        heldDrink = null;
        raise = 0f;
        sipCooldown = 0f;
        totalSips = 0;
        pendingDoor = null;
        pendingTimer = 0f;
    }
}

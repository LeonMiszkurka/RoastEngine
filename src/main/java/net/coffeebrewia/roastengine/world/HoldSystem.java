package net.coffeebrewia.roastengine.world;

import net.coffeebrewia.roastengine.inventory.Inventory;
import net.coffeebrewia.roastengine.inventory.PlaceHolderApi;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Picking things up, carrying them and putting them down again.
 *
 * <p>An object is holdable when its scene says so, or when a script calls
 * {@code hold_item("Name")}. Pressing the interact key on one picks it up; the drop key puts it
 * back down in front of the player.
 *
 * <p>Where a held item is drawn: the item is hung off the player's hand bone with its
 * <b>bottom inside the fist</b>, which is how a hand holds a bottle or a torch - the grip closes
 * around the base rather than balancing the model's middle on the wrist. {@link #heldTransform}
 * does that by moving the model's own base to the origin and then putting the origin in the hand.
 *
 * <p>Carrying more than one thing needs the {@link PlaceHolderApi}; without it there is one pair
 * of hands and one item.
 */
public final class HoldSystem {

    /** How far the player can reach to pick something up, in metres. */
    public static final float REACH = 2.4f;

    /**
     * How far into the fist the item's base sits. Without this the model balances exactly on the
     * bone and looks like it is floating a centimetre off the hand.
     */
    private static final float GRIP_INSET =
            Float.parseFloat(System.getProperty("roastengine.holdInset", "0.045"));

    /** Where a dropped item lands: this far in front of the player, on the floor. */
    private static final float DROP_DISTANCE = 0.9f;

    private Inventory inventory = new Inventory(PlaceHolderApi.HANDS_ONLY);
    /** False while a game mode owns what the player carries - an arena's loadout. */
    private boolean dropAllowed = true;
    private PlaceHolderApi api;
    private LoadedWorld world;

    private final Vector3f scratch = new Vector3f();
    private final Matrix4f base = new Matrix4f();

    /** Starts a world with the bag the player's mods give them. */
    public void load(LoadedWorld world, PlaceHolderApi api) {
        this.world = world;
        this.api = api;
        this.inventory = new Inventory(api == null ? PlaceHolderApi.HANDS_ONLY : api.slots());
        for (WorldObject object : world.objects()) {
            object.carried = false;
        }
    }

    public Inventory inventory() {
        return inventory;
    }

    /** True when the player has a bag rather than just their hands. */
    public boolean hasBag() {
        return api != null && api.slots() > 1;
    }

    /** The name of the API mod giving them that bag, for the HUD. */
    public String apiName() {
        return api == null ? "" : api.name();
    }

    /** What is in the player's hand, or null. */
    public WorldObject held() {
        return inventory.held();
    }

    public boolean isCarrying(WorldObject object) {
        return object != null && object.carried;
    }

    /** Marks an object as something the player can pick up. */
    public void makeHoldable(WorldObject object) {
        if (object != null) {
            object.holdable = true;
        }
    }

    /**
     * The holdable object the player is looking at within reach - what the interact key would
     * pick up.
     */
    public WorldObject target(Vector3f eye, Vector3f forward, float reach) {
        if (world == null) {
            return null;
        }
        WorldObject best = null;
        float bestScore = 0.55f; // how directly in front it has to be
        for (WorldObject object : world.objects()) {
            if (!object.holdable || object.carried || object.removedByScript) {
                continue;
            }
            Vector3f toObject = object.center(new Vector3f()).sub(eye);
            float distance = toObject.length();
            if (distance > reach || distance < 0.01f) {
                continue;
            }
            float facing = toObject.div(distance).dot(forward);
            if (facing > bestScore) {
                bestScore = facing;
                best = object;
            }
        }
        return best;
    }

    /**
     * Picks something up.
     *
     * @return the slot it went into, or -1 when the player's hands and bag are full
     */
    public int pickUp(WorldObject object) {
        if (object == null || object.carried || object.removedByScript) {
            return -1;
        }
        int slot = inventory.add(object);
        if (slot >= 0) {
            object.carried = true;
        }
        return slot;
    }

    /**
     * Puts down what is in the player's hand, in front of them and on the floor.
     *
     * @return what was put down, or null when their hand was empty
     */
    public WorldObject dropHeld(Vector3f playerFeet, Vector3f forward) {
        if (!dropAllowed) {
            return null;
        }
        WorldObject held = inventory.removeHeld();
        if (held == null) {
            return null;
        }
        held.carried = false;
        Vector3f landing = new Vector3f(forward).normalize(DROP_DISTANCE).add(playerFeet);
        // The object keeps the matrix the level placed it with, so it is moved the way a script
        // moves things: by the offset that is added on top of that.
        Vector3f placedCenter = held.center(scratch).sub(held.scriptOffset);
        held.scriptOffset.set(
                landing.x - placedCenter.x,
                landing.y + (held.worldMax.y - held.worldMin.y) * 0.5f - placedCenter.y,
                landing.z - placedCenter.z);
        held.scriptPitch = 0f;
        held.scriptRoll = 0f;
        return held;
    }

    public boolean dropAllowed() {
        return dropAllowed;
    }

    /** Stops the player putting things down, for a game mode that hands out their loadout. */
    public void setDropAllowed(boolean allowed) {
        this.dropAllowed = allowed;
    }

    /** Drops everything, for leaving a world. */
    public void dropAll() {
        for (WorldObject object : inventory.contents()) {
            object.carried = false;
        }
        inventory.clear();
    }

    /**
     * Where to draw the held item, given where the player's hand is.
     *
     * <p>The model is taken by its base - the middle of the bottom of its box - and that point is
     * put in the hand, pressed {@value #GRIP_INSET} of a metre in so the grip closes around it.
     *
     * @param hand the hand's transform in world space
     * @return {@code out}, ready to draw with
     */
    public Matrix4f heldTransform(WorldObject item, Matrix4f hand, Matrix4f out) {
        Vector3f grip = item.grip != null
                ? item.model.transformPosition(new Vector3f(item.grip))
                : new Vector3f(
                        (item.worldMin.x + item.worldMax.x) * 0.5f,
                        item.worldMin.y,
                        (item.worldMin.z + item.worldMax.z) * 0.5f).sub(item.scriptOffset);
        // hand * (a little way into the fist) * (model, moved so its grip is at the origin)
        return out.set(hand)
                .translate(0f, -GRIP_INSET, 0f)
                .translate(-grip.x, -grip.y, -grip.z)
                .mul(item.model);
    }

    /**
     * Where to draw something that points where the player looks - a gun. The grip goes at
     * {@code hand} and the model turns with the aim, taking the model the way it was built
     * (barrel down -Z) rather than the way the level happened to lay it on a rack.
     *
     * @param yaw   the aim, as the camera has it
     * @param pitch positive looks down
     */
    public Matrix4f aimedTransform(WorldObject item, Vector3f hand, float yaw, float pitch, Matrix4f out) {
        Vector3f grip = item.grip != null ? item.grip : new Vector3f(
                (item.asset.min().x + item.asset.max().x) * 0.5f,
                item.asset.min().y,
                (item.asset.min().z + item.asset.max().z) * 0.5f);
        float scale = item.model.getScale(new Vector3f()).x;
        return out.translation(hand)
                .rotateY(-yaw)
                .rotateX(-pitch)
                .scale(scale)
                .translate(-grip.x, -grip.y, -grip.z);
    }

    /**
     * Where to draw a held item when there is no rigged player to hang it off: just in front of
     * the camera, in the right hand's corner of the view.
     */
    public Matrix4f handFallback(Vector3f eye, float yaw, Matrix4f out) {
        float sin = (float) Math.sin(yaw);
        float cos = (float) Math.cos(yaw);
        return base.translation(
                        eye.x + sin * 0.45f + cos * 0.22f,
                        eye.y - 0.35f,
                        eye.z - cos * 0.45f + sin * 0.22f)
                .rotateY(-yaw)
                .get(out);
    }
}

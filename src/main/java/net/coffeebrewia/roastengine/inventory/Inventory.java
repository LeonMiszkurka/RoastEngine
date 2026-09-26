package net.coffeebrewia.roastengine.inventory;

import net.coffeebrewia.roastengine.world.WorldObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the player is carrying: a row of slots, one of which is the hand.
 *
 * <p>With no {@link PlaceHolderApi} installed there is a single slot, which is simply the
 * player's hands. With it, there are as many slots as the API says, the number keys pick between
 * them, and only the chosen one is actually held - the rest are in the bag and not drawn.
 *
 * <p>This class is only the bookkeeping. Picking things up, putting them down and drawing them is
 * HoldSystem's job.
 */
public final class Inventory {

    private final WorldObject[] slots;
    private int selected;

    public Inventory(int slotCount) {
        this.slots = new WorldObject[Math.max(1, slotCount)];
    }

    public int size() {
        return slots.length;
    }

    /** Which slot is in the player's hand, counting from 0. */
    public int selected() {
        return selected;
    }

    /** Picks a slot, ignoring one that is not there. Returns true when the choice changed. */
    public boolean select(int slot) {
        if (slot < 0 || slot >= slots.length || slot == selected) {
            return false;
        }
        selected = slot;
        return true;
    }

    /** Moves one slot along, wrapping round - the mouse wheel, or a shoulder button. */
    public void selectNext(int direction) {
        if (direction == 0) {
            return;
        }
        int step = direction > 0 ? 1 : -1;
        selected = Math.floorMod(selected + step, slots.length);
    }

    /** What is in the player's hand, or null when that slot is empty. */
    public WorldObject held() {
        return slots[selected];
    }

    public WorldObject at(int slot) {
        return slot >= 0 && slot < slots.length ? slots[slot] : null;
    }

    public boolean isFull() {
        return firstFreeSlot() < 0;
    }

    public boolean contains(WorldObject object) {
        for (WorldObject slot : slots) {
            if (slot == object) {
                return true;
            }
        }
        return false;
    }

    /**
     * Puts something away.
     *
     * <p>It goes into the hand when the hand is empty, so picking one thing up puts it straight
     * where the player expects. Otherwise it takes the first free slot, leaving the hand alone -
     * picking up a second thing should not swap out what is already being carried.
     *
     * @return the slot it went into, or -1 when there was no room
     */
    public int add(WorldObject object) {
        if (object == null || contains(object)) {
            return -1;
        }
        if (slots[selected] == null) {
            slots[selected] = object;
            return selected;
        }
        int free = firstFreeSlot();
        if (free < 0) {
            return -1;
        }
        slots[free] = object;
        return free;
    }

    /** Takes what is in the hand out of the bag. */
    public WorldObject removeHeld() {
        WorldObject held = slots[selected];
        slots[selected] = null;
        return held;
    }

    /** Takes one thing out, wherever it is. */
    public boolean remove(WorldObject object) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == object) {
                slots[i] = null;
                return true;
            }
        }
        return false;
    }

    /** Everything being carried, in slot order, without the empty slots. */
    public List<WorldObject> contents() {
        List<WorldObject> carried = new ArrayList<>();
        for (WorldObject slot : slots) {
            if (slot != null) {
                carried.add(slot);
            }
        }
        return Collections.unmodifiableList(carried);
    }

    /** Empties every slot, for when a world is unloaded. */
    public void clear() {
        java.util.Arrays.fill(slots, null);
        selected = 0;
    }

    private int firstFreeSlot() {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) {
                return i;
            }
        }
        return -1;
    }
}

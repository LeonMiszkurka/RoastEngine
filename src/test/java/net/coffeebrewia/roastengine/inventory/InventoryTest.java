package net.coffeebrewia.roastengine.inventory;

import net.coffeebrewia.roastengine.world.TestObjects;
import net.coffeebrewia.roastengine.world.WorldObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryTest {

    @Test
    void withoutTheApiThereIsOneSlot() {
        Inventory bag = new Inventory(PlaceHolderApi.HANDS_ONLY);
        WorldObject bottle = TestObjects.item("Bottle");
        assertEquals(0, bag.add(bottle));
        assertSame(bottle, bag.held());
        assertTrue(bag.isFull(), "with one slot, one thing fills it");
        assertEquals(-1, bag.add(TestObjects.item("Torch")), "nowhere to put a second thing");
    }

    @Test
    void thingsPickedUpGoIntoTheHandFirst() {
        Inventory bag = new Inventory(5);
        WorldObject first = TestObjects.item("Bottle");
        assertEquals(0, bag.add(first));
        assertSame(first, bag.held(), "the first thing goes straight into the hand");

        // The second must not shove the first out of the hand.
        WorldObject second = TestObjects.item("Torch");
        assertEquals(1, bag.add(second));
        assertSame(first, bag.held());
        assertFalse(bag.isFull());
    }

    @Test
    void theNumberKeysPickWhichIsHeld() {
        Inventory bag = new Inventory(5);
        bag.add(TestObjects.item("Bottle"));
        WorldObject torch = TestObjects.item("Torch");
        bag.add(torch);

        assertTrue(bag.select(1));
        assertSame(torch, bag.held());
        assertFalse(bag.select(1), "choosing the slot already chosen changes nothing");
        assertFalse(bag.select(9), "there is no tenth slot");
        assertEquals(1, bag.selected());
    }

    @Test
    void cyclingWrapsRoundBothWays() {
        Inventory bag = new Inventory(3);
        bag.selectNext(-1);
        assertEquals(2, bag.selected(), "back from the first slot is the last one");
        bag.selectNext(1);
        assertEquals(0, bag.selected());
    }

    @Test
    void droppingEmptiesTheHandButKeepsTheRest() {
        Inventory bag = new Inventory(5);
        WorldObject bottle = TestObjects.item("Bottle");
        WorldObject torch = TestObjects.item("Torch");
        bag.add(bottle);
        bag.add(torch);

        assertSame(bottle, bag.removeHeld());
        assertNull(bag.held(), "the hand is empty now");
        assertEquals(List.of(torch), bag.contents(), "the bag still has the rest");
    }

    @Test
    void theSameThingIsNotPickedUpTwice() {
        Inventory bag = new Inventory(5);
        WorldObject bottle = TestObjects.item("Bottle");
        assertEquals(0, bag.add(bottle));
        assertEquals(-1, bag.add(bottle));
        assertEquals(1, bag.contents().size());
    }

}

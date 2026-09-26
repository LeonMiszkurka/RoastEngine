package net.coffeebrewia.roastengine.world;

import net.coffeebrewia.roastengine.inventory.PlaceHolderApi;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoldSystemTest {

    @Test
    void picksUpWhatIsHoldableAndPutsItDownAgain() {
        LoadedWorld world = new LoadedWorld();
        WorldObject bottle = TestObjects.item("Bottle");
        bottle.holdable = true;
        world.add(bottle);

        HoldSystem hands = new HoldSystem();
        hands.load(world, null);

        assertEquals(0, hands.pickUp(bottle));
        assertTrue(bottle.carried);
        assertSame(bottle, hands.held());
        assertFalse(bottle.isSolid(), "something being carried should not block the player");

        WorldObject dropped = hands.dropHeld(new Vector3f(4f, 0f, 4f), new Vector3f(0f, 0f, -1f));
        assertSame(bottle, dropped);
        assertFalse(bottle.carried);
        assertNull(hands.held());
        Vector3f where = bottle.center(new Vector3f());
        assertEquals(4f, where.x, 0.001f, "dropped in front of the player, not back where it was");
        assertEquals(3.1f, where.z, 0.001f);
    }

    @Test
    void onlyLooksAtHoldableThingsInReachAndInFront() {
        LoadedWorld world = new LoadedWorld();
        WorldObject bottle = TestObjects.item("Bottle");
        bottle.holdable = true;
        world.add(bottle);
        world.add(TestObjects.item("Wall")); // not holdable

        HoldSystem hands = new HoldSystem();
        hands.load(world, null);
        Vector3f atTheBottle = new Vector3f(0.5f, 0.5f, 1.5f);
        assertSame(bottle, hands.target(atTheBottle, new Vector3f(0f, 0f, -1f), HoldSystem.REACH));
        assertNull(hands.target(atTheBottle, new Vector3f(0f, 0f, 1f), HoldSystem.REACH),
                "looking the other way");
        assertNull(hands.target(new Vector3f(0.5f, 0.5f, 40f), new Vector3f(0f, 0f, -1f),
                HoldSystem.REACH), "too far away");
    }

    @Test
    void theItemsBaseEndsUpInTheFist() {
        LoadedWorld world = new LoadedWorld();
        WorldObject bottle = TestObjects.item("Bottle"); // a 1x1 model with its base at y=0
        world.add(bottle);
        HoldSystem hands = new HoldSystem();
        hands.load(world, null);

        // A hand a metre and a half up, not rotated.
        Matrix4f hand = new Matrix4f().translation(2f, 1.5f, -3f);
        Matrix4f held = hands.heldTransform(bottle, hand, new Matrix4f());

        // The middle of the model's base, put through that transform, should land at the hand -
        // a little way inside it, which is what stops it floating above the fist.
        Vector3f base = new Vector3f(0.5f, 0f, 0f).mulPosition(held);
        assertEquals(2f, base.x, 0.001f);
        assertEquals(-3f, base.z, 0.001f);
        assertTrue(base.y < 1.5f, "the base sits inside the hand, not on top of it");
        assertEquals(1.5f, base.y, 0.06f, "but only just inside it");
    }

    @Test
    void aScriptCanSayWhatIsHoldable(@TempDir Path mod) throws IOException {
        // The shape a mod's script is written in: name the item, then make it holdable.
        Path scripts = Files.createDirectories(mod.resolve("scripts"));
        Files.writeString(scripts.resolve("carry.py"), """
                item = "Bottle"
                hold_item(item)
                """);

        LoadedWorld world = new LoadedWorld();
        WorldObject bottle = TestObjects.item("Bottle");
        world.add(bottle);
        world.add(TestObjects.scripted("Sign", "carry.py", mod));

        HoldSystem hands = new HoldSystem();
        hands.load(world, null);
        ScriptSystem running = new ScriptSystem(world, quietHooks());
        running.useHands(hands);
        running.load();

        assertTrue(bottle.holdable, "the script should have made the bottle holdable");
    }

    @Test
    void withoutTheApiThereIsNoBag() {
        HoldSystem hands = new HoldSystem();
        hands.load(new LoadedWorld(), null);
        assertFalse(hands.hasBag());
        assertEquals(PlaceHolderApi.HANDS_ONLY, hands.inventory().size());
    }

    private static ScriptSystem.Hooks quietHooks() {
        return new ScriptSystem.Hooks() {
            @Override
            public void notice(String text) {
            }

            @Override
            public void chat(String text) {
            }

            @Override
            public void playSound(String name, float volume, float pitch) {
            }

            @Override
            public Vector3f playerEye() {
                return new Vector3f(0f, 1.7f, 0f);
            }

            @Override
            public void teleportPlayer(float x, float y, float z) {
            }

            @Override
            public void pushPlayer(float x, float y, float z) {
            }
        };
    }
}

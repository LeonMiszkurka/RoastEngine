package net.coffeebrewia.roastengine.world;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsablesTest {

    private static final Vector3f EYE = new Vector3f(0f, 1.7f, 0f);
    private static final Vector3f FORWARD = new Vector3f(0f, 0f, -1f);

    @Test
    void readsWhatSomethingIsFromItsName() {
        assertEquals(Usables.Kind.GUN, Usables.kindOf(TestObjects.item("gun_shotgun")));
        assertEquals(Usables.Kind.GUN, Usables.kindOf(TestObjects.item("Sniper Rifle")));
        assertEquals(Usables.Kind.SWING, Usables.kindOf(TestObjects.item("Bottle")));
        assertEquals(Usables.Kind.NONE, Usables.kindOf(TestObjects.item("Crate")));
        assertEquals(Usables.Kind.NONE, Usables.kindOf(null));
        assertEquals(Usables.Kind.NONE, Usables.kindOf(TestObjects.item(SpawnKit.GUN_NAME)),
                "the spawn gun opens the item list; it does not shoot");
    }

    @Test
    void picksTheRightGunRatherThanTheFirstOneNamedGun() {
        assertEquals("Shotgun", Usables.gunFor("gun_shotgun").label());
        assertEquals("Sniper Rifle", Usables.gunFor("gun_sniper").label());
        assertEquals("SMG", Usables.gunFor("gun_smg").label());
        assertEquals("Gun", Usables.gunFor("strange_gun").label(), "an unknown gun still fires");
        assertNull(Usables.gunFor("Crate"));
        assertEquals(8, Usables.gunFor("gun_shotgun").pellets(), "a shotgun throws a handful");
    }

    @Test
    void firingLeavesOneTracerPerPelletAndThenWaitsForTheNextShot() {
        LoadedWorld world = new LoadedWorld();
        Usables usables = new Usables();
        WorldObject shotgun = TestObjects.item("gun_shotgun");

        Usables.Fired fired = usables.pullTrigger(shotgun, world, new EntitySystem(), EYE, FORWARD,
                true, true);
        assertNotNull(fired);
        assertFalse(fired.hit(), "nothing in the world to hit");
        assertEquals(8, usables.shots().size());

        assertNull(usables.pullTrigger(shotgun, world, new EntitySystem(), EYE, FORWARD, true, true),
                "a shotgun cannot be fired again in the same instant");
        usables.update(1f / 1.2f + 0.01f);
        assertNotNull(usables.pullTrigger(shotgun, world, new EntitySystem(), EYE, FORWARD,
                true, true), "once the cooldown is up it fires again");
    }

    @Test
    void onlyAFastGunKeepsFiringWhileTheTriggerIsHeld() {
        LoadedWorld world = new LoadedWorld();
        Usables usables = new Usables();
        assertNull(usables.pullTrigger(TestObjects.item("gun_shotgun"), world, new EntitySystem(),
                EYE, FORWARD, false, true), "held, not pressed: a pump gun needs a fresh pull");

        Usables automatic = new Usables();
        assertNotNull(automatic.pullTrigger(TestObjects.item("gun_smg"), world, new EntitySystem(),
                EYE, FORWARD, false, true), "an SMG keeps going while the trigger is down");
    }

    @Test
    void tracersFadeAwayOnTheirOwn() {
        Usables usables = new Usables();
        usables.pullTrigger(TestObjects.item("gun_pistol"), new LoadedWorld(), new EntitySystem(),
                EYE, FORWARD, true, false);
        assertEquals(1, usables.shots().size());
        usables.update(Usables.TRACER_SECONDS + 0.01f);
        assertTrue(usables.shots().isEmpty());
    }

    @Test
    void swingingConnectsWithAnEntityInFrontAndOnlyOncePerPress() {
        LoadedWorld world = new LoadedWorld();
        WorldObject dummy = TestObjects.item("dummy");
        dummy.entity = true;
        world.add(dummy);
        EntitySystem entities = new EntitySystem();
        entities.load(world);

        Usables usables = new Usables();
        Vector3f atTheDummy = new Vector3f(0.5f, 0.5f, 1.5f);
        assertSame(dummy, usables.swing(TestObjects.item("Bottle"), entities, atTheDummy, FORWARD));
        assertTrue(usables.isSwinging());
        assertNull(usables.swing(TestObjects.item("Bottle"), entities, atTheDummy, FORWARD),
                "still mid-swing");
        assertNull(usables.swing(TestObjects.item("Crate"), entities, atTheDummy, FORWARD),
                "a crate is not something you swing");
    }
}

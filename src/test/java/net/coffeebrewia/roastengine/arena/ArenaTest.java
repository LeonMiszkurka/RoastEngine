package net.coffeebrewia.roastengine.arena;

import net.coffeebrewia.roastengine.world.Ballistics;
import net.coffeebrewia.roastengine.net.ArenaRules;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaTest {

    private static final Vector3f FORWARD = new Vector3f(0f, 0f, -1f);

    @Test
    void theShippedGunArenaReads() throws IOException {
        ArenaConfig config = ArenaConfig.load(Path.of("mods/gun-arena/arena/arena.json"));
        assertEquals(2, config.maps().size());
        assertEquals(6, config.guns().size());
        assertEquals(2, config.minPlayers());
        for (ArenaConfig.ArenaMap map : config.maps()) {
            assertFalse(map.spawnsFor(ArenaRules.RED).isEmpty(), map.name() + " has red spawns");
            assertFalse(map.spawnsFor(ArenaRules.BLUE).isEmpty(), map.name() + " has blue spawns");
        }
        ArenaConfig.Gun shotgun = config.guns().stream().filter(g -> g.id().equals("shotgun")).findFirst().orElseThrow();
        assertEquals(8, shotgun.pellets());
        // The referee's copy of the rules comes from the same file.
        ArenaRules.Settings settings = config.settings();
        assertEquals(List.of("Warehouse", "Courtyard"), settings.maps());
        assertEquals(10, settings.scoreLimit());
    }

    @Test
    void everyGunCanBeFoundByItsObjectsName() throws IOException {
        ArenaConfig config = ArenaConfig.load(Path.of("mods/gun-arena/arena/arena.json"));
        assertEquals(3, config.gunIndexForObject("assault rifle"), "matched without caring about case");
        assertEquals(-1, config.gunIndexForObject("Target 1"));
    }

    @Test
    void aRayMeetsABoxWhereItShould() {
        Vector3f eye = new Vector3f(0f, 1f, 0f);
        float t = Ballistics.rayBox(eye, FORWARD, new Vector3f(-1f, 0f, -6f), new Vector3f(1f, 2f, -5f));
        assertEquals(5f, t, 1e-4f);
        assertEquals(Ballistics.MISS, Ballistics.rayBox(eye, new Vector3f(0f, 0f, 1f),
                new Vector3f(-1f, 0f, -6f), new Vector3f(1f, 2f, -5f)), "it is behind us");
        assertEquals(0f, Ballistics.rayBox(eye, FORWARD, new Vector3f(-1f, 0f, -1f),
                new Vector3f(1f, 2f, 1f)), 1e-6f, "starting inside the box");
    }

    @Test
    void aPlayerIsHitBetweenTheirFeetAndTheTopOfTheirHead() {
        Vector3f eye = new Vector3f(0f, 1.7f, 0f);
        // Someone 10 m ahead, standing on the same floor.
        float t = Ballistics.rayCylinder(eye, FORWARD, 0f, -10f, 0.4f, 0f, 1.95f);
        assertEquals(9.6f, t, 1e-4f, "hits the front of them");
        // Aiming over their head misses.
        Vector3f over = new Vector3f(0f, 0.05f, -1f).normalize();
        assertEquals(Ballistics.MISS, Ballistics.rayCylinder(eye, over, 0f, -10f, 0.4f, 0f, 1.95f));
        // Half a metre to the side misses too.
        assertEquals(Ballistics.MISS, Ballistics.rayCylinder(eye, FORWARD, 0.5f, -10f, 0.4f, 0f, 1.95f));
    }

    @Test
    void spreadStaysInsideItsCone() {
        Random random = new Random(3);
        Vector3f direction = new Vector3f();
        float limit = (float) Math.cos(Math.toRadians(6.0));
        for (int i = 0; i < 500; i++) {
            Ballistics.scatter(FORWARD, 6f, random, direction);
            assertEquals(1f, direction.length(), 1e-4f);
            assertTrue(direction.dot(FORWARD) >= limit - 1e-4f, "within 6 degrees of the aim");
        }
        assertEquals(FORWARD, Ballistics.scatter(FORWARD, 0f, random, direction), "no spread, no drift");
    }
}

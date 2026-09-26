package net.coffeebrewia.roastengine.backrooms;

import com.google.gson.JsonParser;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackroomsConfigTest {

    private static BackroomsConfig parse(String json) {
        return BackroomsConfig.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void readsWhatTheModSays() {
        BackroomsConfig config = parse("""
                {
                  "name": "Somewhere Else",
                  "menu": { "title": "DOWN HERE", "subtitle": "no way out",
                            "playLabel": "Enter", "intro": "video/BK_INTRO.mp4",
                            "music": "audio/hum.ogg" },
                  "monster": { "name": "It", "object": "Thing", "speed": 2.5, "chaseSpeed": 5,
                               "sightRange": 30, "sightAngle": 90, "hearingRange": 18,
                               "killRange": 1.5, "giveUpSeconds": 4 },
                  "levels": [ { "id": "l0", "name": "Level 0", "originX": -400, "cellSize": 3,
                                "spawn": [1, 1.7, 2], "monsterSpawn": [9, 0, 9],
                                "menuView": { "eye": [0, 1.7, 0], "yaw": 45 },
                                "grid": ["###", "#.#", "###"] } ]
                }
                """);
        assertEquals("Somewhere Else", config.name());
        assertEquals("DOWN HERE", config.menu().title());
        assertEquals("Enter", config.menu().playLabel());
        assertEquals("video/BK_INTRO.mp4", config.menu().intro());
        assertEquals("It", config.monster().name());
        assertEquals(1.5f, config.monster().killRange(), 0.001f);
        assertEquals(1, config.levels().size());
        BackroomsConfig.LevelConfig level = config.levels().get(0);
        assertEquals(-400f, level.originX(), 0.001f);
        assertEquals(new Vector3f(1, 1.7f, 2), level.spawn());
        assertEquals(45f, level.menuYawDegrees(), 0.001f);
    }

    @Test
    void fillsInWhatTheModLeavesOut() {
        BackroomsConfig config = parse("""
                { "levels": [ { "grid": ["###", "#.#", "###"] } ] }
                """);
        assertEquals("The Backrooms", config.name());
        assertEquals("", config.menu().intro(), "no intro means the level simply starts");
        assertEquals("", config.menu().music());
        assertEquals("The Entity", config.monster().name());
        assertTrue(config.monster().speed() > 0);
        assertEquals("level0", config.levels().get(0).id(), "levels are numbered when unnamed");
    }

    @Test
    void nonsenseNumbersAreBroughtBackIntoRange() {
        BackroomsConfig config = parse("""
                { "monster": { "speed": -5, "sightAngle": 900, "killRange": 0 },
                  "levels": [ { "grid": ["###", "#.#", "###"], "cellSize": 0 } ] }
                """);
        assertTrue(config.monster().speed() > 0, "a monster that cannot move is a bug, not a level");
        assertTrue(config.monster().sightAngleDegrees() <= 180f);
        assertTrue(config.monster().killRange() > 0);
        assertTrue(config.levels().get(0).squareSize() > 0, "a grid of nothing-wide squares has no map");
    }

    @Test
    void aWorldWithNoLevelsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> parse("{ \"levels\": [] }"));
        assertThrows(IllegalArgumentException.class,
                () -> parse("{ \"levels\": [ { \"id\": \"l0\" } ] }"),
                "a level with no grid has nothing to walk on");
    }

    @Test
    void anUnknownSavedLevelFallsBackRatherThanFailing() {
        BackroomsConfig config = parse("""
                { "levels": [ { "id": "l0", "grid": ["###", "#.#", "###"] },
                              { "id": "l1", "grid": ["###", "#.#", "###"] } ] }
                """);
        assertEquals("l1", config.levelOr("l1", config.levels().get(0)).id());
        assertEquals("l0", config.levelOr("deleted-level", config.levels().get(0)).id(),
                "an options file naming a level that no longer exists must not stop the world");
    }

    // ------------------------------------------------------------------
    // The level set that actually ships
    // ------------------------------------------------------------------

    @Test
    void aLevelKeepsItsOwnCreatureAndInheritsTheRest() {
        BackroomsConfig config = parse("""
                { "monster": { "name": "Default", "object": "Default", "speed": 3,
                               "killRange": 1.1 },
                  "levels": [ { "id": "l0", "grid": ["###", "#.#", "###"] },
                              { "id": "l1", "grid": ["###", "#.#", "###"],
                                "monster": { "name": "Smiler", "object": "Smiler", "speed": 2.6 } } ] }
                """);
        BackroomsConfig.Monster inherited = config.levels().get(0).monster();
        assertEquals("Default", inherited.name(), "a level with no monster block uses the world's");

        BackroomsConfig.Monster own = config.levels().get(1).monster();
        assertEquals("Smiler", own.name());
        assertEquals(2.6f, own.speed(), 0.001f);
        assertEquals(1.1f, own.killRange(), 0.001f,
                "what the level does not say is taken from the world's monster");
    }

    @Test
    void everyShippedLevelHasACreatureThatExists() throws IOException {
        Path mod = Path.of("").toAbsolutePath().resolve("mods/backrooms");
        Path file = mod.resolve(BackroomsConfig.FILE);
        if (!Files.isRegularFile(file)) {
            return;
        }
        String scene = Files.readString(mod.resolve("scene.json"));
        int hunted = 0;
        for (BackroomsConfig.LevelConfig level : BackroomsConfig.load(file).levels()) {
            String object = level.monster().object();
            if (object.isBlank()) {
                continue;   // a level with nothing in it, which the Poolrooms are meant to be
            }
            hunted++;
            assertTrue(scene.contains("\"" + object + "\""),
                    level.id() + " hunts you with '" + object + "', which the scene has no model for");
        }
        assertTrue(hunted > 0, "every level ended up empty, which cannot be right");
    }

    @Test
    void theShippedLevelsDoNotStartYouFacingAWall() throws IOException {
        Path file = Path.of("").toAbsolutePath().resolve("mods/backrooms").resolve(BackroomsConfig.FILE);
        if (!Files.isRegularFile(file)) {
            return;
        }
        for (BackroomsConfig.LevelConfig level : BackroomsConfig.load(file).levels()) {
            Maze maze = level.maze();
            Maze.Square spawn = maze.squareAt(level.spawn().x, level.spawn().z);
            // Yaw 0 looks down -Z (up the grid); turning positive swings towards +X.
            int yaw = Math.round(level.spawnYawDegrees() / 90f) % 4;
            int[][] ways = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
            int ahead = ways[yaw][0];
            int alongside = ways[yaw][1];
            assertTrue(maze.isOpen(spawn.col() + ahead, spawn.row() + alongside),
                    level.id() + ": you spawn looking straight into a wall (yaw "
                            + level.spawnYawDegrees() + ")");
        }
    }

    @Test
    void theShippedBackroomsHoldsTogether() throws IOException {
        Path file = Path.of("").toAbsolutePath().resolve("mods/backrooms").resolve(BackroomsConfig.FILE);
        if (!Files.isRegularFile(file)) {
            return;   // the mod is generated; nothing to check if it has not been
        }
        BackroomsConfig config = BackroomsConfig.load(file);
        assertFalse(config.levels().isEmpty());

        for (BackroomsConfig.LevelConfig level : config.levels()) {
            Maze maze = level.maze();
            Maze.Square spawn = maze.squareAt(level.spawn().x, level.spawn().z);
            Maze.Square monster = maze.squareAt(level.monsterSpawn().x, level.monsterSpawn().z);
            Maze.Square view = maze.squareAt(level.menuEye().x, level.menuEye().z);

            assertTrue(maze.isOpen(spawn), level.id() + ": the player spawns inside a wall");
            assertTrue(maze.isOpen(monster), level.id() + ": the monster spawns inside a wall");
            assertTrue(maze.isOpen(view), level.id() + ": the home screen looks out of a wall");
            assertTrue(maze.walkingDistance(spawn, monster) > 0,
                    level.id() + ": the monster cannot reach the player at all");

            // Everywhere open is reachable: a walled-off pocket would be a room you can see on
            // the map and never get to, and somewhere the monster could be stuck for ever.
            int reachable = maze.route(spawn, monster).isEmpty() ? 0 : 1;
            assertEquals(1, reachable);
            for (Maze.Square square : maze.openSquares()) {
                assertFalse(maze.route(spawn, square).isEmpty(),
                        level.id() + ": " + square + " is walled off from the spawn");
            }
        }
    }
}

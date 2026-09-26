package net.coffeebrewia.roastengine.backrooms;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StalkerTest {

    private static final float STEP = 1f / 60f;

    /** A long open hall, so distances are easy to reason about. */
    private static Maze hall() {
        return new Maze(List.of(
                "###############",
                "#.............#",
                "#.............#",
                "#.............#",
                "###############"), 0f, 2f);
    }

    /** Two halls with a wall between them, joined right at the end. */
    private static Maze dividedHall() {
        return new Maze(List.of(
                "###############",
                "#.............#",
                "#####.#########",
                "#.............#",
                "###############"), 0f, 2f);
    }

    private static BackroomsConfig.Monster monster() {
        return new BackroomsConfig.Monster("The Entity", "Entity", "", 3f, 4.2f,
                26f, 75f, 20f, 1.1f, 7f);
    }

    private static Stalker stalkerAt(Maze maze, Maze.Square square) {
        Stalker stalker = new Stalker(maze, monster(), maze.centreOf(square, new Vector3f()), new Random(3));
        stalker.setDifficulty(Stalker.Difficulty.NORMAL);
        return stalker;
    }

    private static void run(Stalker stalker, Vector3f player, float noise, float seconds) {
        for (int i = 0; i < seconds / STEP; i++) {
            stalker.update(STEP, player, 0f, noise);
        }
    }

    @Test
    void wandersOffWhenItHasNoReasonToCare() {
        Maze maze = hall();
        Stalker stalker = stalkerAt(maze, new Maze.Square(1, 1));
        Vector3f start = new Vector3f(stalker.position());
        // Right out of the level and silent with it: nothing to see, nothing to hear.
        Vector3f player = new Vector3f(1000f, 1.7f, 1000f);
        run(stalker, player, 0f, 3f);

        assertEquals(Stalker.Mood.WANDER, stalker.mood());
        assertTrue(stalker.position().distance(start) > 1f, "it went somewhere");
        assertFalse(stalker.hasCaughtYou());
    }

    @Test
    void hearsSomeoneRunningAndComesToLook() {
        Maze maze = dividedHall();
        Stalker stalker = stalkerAt(maze, new Maze.Square(1, 1));
        // Round the corner, out of sight, but running: it cannot see this, only hear it.
        Vector3f player = maze.centreOf(new Maze.Square(1, 3), new Vector3f());
        player.y = 1.7f;
        run(stalker, player, 1f, 2f);
        assertEquals(Stalker.Mood.INVESTIGATE, stalker.mood(), "it heard that");

        // The only way through is round the far end of the dividing wall, so this takes a while.
        run(stalker, player, 1f, 12f);
        float after = stalker.position().distance(player.x, stalker.position().y, player.z);
        assertTrue(stalker.hasCaughtYou() || after < 3f,
                "it walked round and found the noise: " + after + "m away");
    }

    @Test
    void doesNotHearSomeoneStandingStill() {
        Maze maze = dividedHall();
        Stalker stalker = stalkerAt(maze, new Maze.Square(1, 1));
        Vector3f player = maze.centreOf(new Maze.Square(3, 3), new Vector3f());
        player.y = 1.7f;

        run(stalker, player, 0f, 2f);

        assertEquals(Stalker.Mood.WANDER, stalker.mood(), "silence gives nothing away");
    }

    @Test
    void walkingCarriesLessFarThanRunning() {
        // Behind the dividing wall, so only sound can give the player away.
        Maze maze = dividedHall();
        Vector3f player = maze.centreOf(new Maze.Square(7, 1), new Vector3f());
        player.y = 1.7f;

        Stalker quiet = stalkerAt(maze, new Maze.Square(1, 3));
        run(quiet, player, 0.5f, 0.5f);
        Stalker loud = stalkerAt(maze, new Maze.Square(1, 3));
        run(loud, player, 1f, 0.5f);

        assertEquals(Stalker.Mood.WANDER, quiet.mood(), "a walk 13m away is not heard");
        assertEquals(Stalker.Mood.INVESTIGATE, loud.mood(), "a run at that distance is");
    }

    @Test
    void huntsWhatItCanSee() {
        Maze maze = hall();
        Stalker stalker = stalkerAt(maze, new Maze.Square(1, 1));
        Vector3f player = maze.centreOf(new Maze.Square(9, 1), new Vector3f());
        player.y = 1.7f;
        // Give it a moment to turn that way, then check it is coming.
        run(stalker, player, 0f, 2f);

        assertEquals(Stalker.Mood.HUNT, stalker.mood(), "straight down an open hall, it sees you");
        assertTrue(stalker.position().distance(player.x, stalker.position().y, player.z) < 14f,
                "and it closed the distance");
    }

    @Test
    void cannotSeeThroughAWall() {
        Maze maze = dividedHall();
        Stalker stalker = stalkerAt(maze, new Maze.Square(1, 1));
        Vector3f player = maze.centreOf(new Maze.Square(1, 3), new Vector3f());
        player.y = 1.7f;

        run(stalker, player, 0f, 1f);

        assertFalse(stalker.mood() == Stalker.Mood.HUNT, "there is a wall in the way");
    }

    @Test
    void catchesYouWhenItReachesYou() {
        Maze maze = hall();
        Stalker stalker = stalkerAt(maze, new Maze.Square(1, 1));
        Vector3f player = maze.centreOf(new Maze.Square(7, 1), new Vector3f());
        player.y = 1.7f;

        run(stalker, player, 1f, 6f);

        assertTrue(stalker.hasCaughtYou(), "it walked right up to a player who never moved");
    }

    @Test
    void catchesAPlayerStandingAtTheEdgeOfASquare() {
        // The monster walks between the middles of squares. A player standing off-centre used to
        // be a safe metre and a half away, watching it stand in the middle of the room.
        Maze maze = hall();
        Stalker stalker = stalkerAt(maze, new Maze.Square(1, 1));
        Vector3f player = maze.centreOf(new Maze.Square(7, 1), new Vector3f());
        player.x += maze.squareSize() * 0.45f;
        player.z += maze.squareSize() * 0.45f;
        player.y = 1.7f;

        run(stalker, player, 1f, 8f);

        assertTrue(stalker.hasCaughtYou(),
                "it must close the last stretch onto the player, not onto the grid");
    }

    @Test
    void staysCaughtUntilItIsPutBack() {
        Maze maze = hall();
        Stalker stalker = stalkerAt(maze, new Maze.Square(1, 1));
        Vector3f player = maze.centreOf(new Maze.Square(5, 1), new Vector3f());
        player.y = 1.7f;
        run(stalker, player, 1f, 6f);
        assertTrue(stalker.hasCaughtYou());

        Vector3f elsewhere = maze.centreOf(new Maze.Square(13, 3), new Vector3f());
        stalker.reset(elsewhere);

        assertFalse(stalker.hasCaughtYou(), "a death puts it back and clears the catch");
        assertEquals(Stalker.Mood.WANDER, stalker.mood());
        assertTrue(stalker.position().distance(elsewhere) < 2f, "and moves it where it was told");
    }

    @Test
    void givesUpAfterLosingYou() {
        Maze maze = hall();
        Stalker stalker = stalkerAt(maze, new Maze.Square(1, 1));
        Vector3f seen = maze.centreOf(new Maze.Square(11, 1), new Vector3f());
        seen.y = 1.7f;
        run(stalker, seen, 0f, 1f);
        assertEquals(Stalker.Mood.HUNT, stalker.mood());

        // The player vanishes: far away, silent, out of sight behind the level's edge.
        Vector3f gone = new Vector3f(1000f, 1.7f, 1000f);
        run(stalker, gone, 0f, 9f);

        assertFalse(stalker.mood() == Stalker.Mood.HUNT, "it eventually stops coming");
    }

    @Test
    void neverWalksIntoAWall() {
        Maze maze = dividedHall();
        Stalker stalker = stalkerAt(maze, new Maze.Square(1, 1));
        Vector3f player = maze.centreOf(new Maze.Square(13, 3), new Vector3f());
        player.y = 1.7f;
        for (int i = 0; i < 60 * 20; i++) {
            stalker.update(STEP, player, 0f, i % 120 < 60 ? 1f : 0f);
            Maze.Square at = maze.squareAt(stalker.position().x, stalker.position().z);
            assertTrue(maze.isOpen(at), "it left the floor at " + at + " after " + i + " steps");
        }
    }

    @Test
    void nightmareIsFasterAndSharperThanCalm() {
        Maze maze = hall();
        Vector3f player = maze.centreOf(new Maze.Square(13, 1), new Vector3f());
        player.y = 1.7f;

        Stalker calm = stalkerAt(maze, new Maze.Square(1, 1));
        calm.setDifficulty(Stalker.Difficulty.CALM);
        Stalker nightmare = stalkerAt(maze, new Maze.Square(1, 1));
        nightmare.setDifficulty(Stalker.Difficulty.NIGHTMARE);

        run(calm, player, 1f, 3f);
        run(nightmare, player, 1f, 3f);

        float calmLeft = calm.position().distance(player.x, calm.position().y, player.z);
        float nightmareLeft = nightmare.position().distance(player.x, nightmare.position().y, player.z);
        assertTrue(nightmareLeft < calmLeft,
                "nightmare closes faster: " + nightmareLeft + " vs " + calmLeft);
    }

    @Test
    void difficultyCyclesRoundForTheOptionsButton() {
        assertEquals(Stalker.Difficulty.NORMAL, Stalker.Difficulty.CALM.next());
        assertEquals(Stalker.Difficulty.NIGHTMARE, Stalker.Difficulty.NORMAL.next());
        assertEquals(Stalker.Difficulty.CALM, Stalker.Difficulty.NIGHTMARE.next());
    }
}

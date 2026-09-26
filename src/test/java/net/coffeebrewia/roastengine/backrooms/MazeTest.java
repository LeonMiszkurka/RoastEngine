package net.coffeebrewia.roastengine.backrooms;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MazeTest {

    /** A small map with a wall down the middle and a gap at the bottom. */
    private static Maze maze() {
        return new Maze(List.of(
                "#######",
                "#..#..#",
                "#..#..#",
                "#..#..#",
                "#.....#",
                "#######"), 0f, 2f);
    }

    @Test
    void wallsAreSolidAndSoIsTheOutside() {
        Maze maze = maze();
        assertTrue(maze.isOpen(1, 1));
        assertFalse(maze.isOpen(3, 1), "the middle wall");
        assertFalse(maze.isOpen(0, 0), "the outer wall");
        assertFalse(maze.isOpen(-1, 3), "off the map is solid, not an exception");
        assertFalse(maze.isOpen(99, 99));
    }

    @Test
    void aRaggedGridIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new Maze(List.of("###", "#.", "###"), 0f, 2f),
                "a short row would silently shift every square after it");
    }

    @Test
    void squaresAndWorldPositionsAgree() {
        Maze maze = maze();
        Vector3f middle = maze.centreOf(new Maze.Square(3, 3), new Vector3f());
        assertEquals(new Maze.Square(3, 3), maze.squareAt(middle.x, middle.z),
                "a square's middle is in that square");
        // A quarter of a square off is still the same square.
        assertEquals(new Maze.Square(3, 3), maze.squareAt(middle.x + 0.4f, middle.z - 0.4f));
    }

    @Test
    void levelsSittingElsewhereInTheWorldStillLineUp() {
        // The shipped levels are hundreds of metres apart, so the origin has to be honoured.
        Maze far = new Maze(List.of("#####", "#...#", "#####"), 400f, 2f);
        Vector3f middle = far.centreOf(new Maze.Square(2, 1), new Vector3f());
        assertEquals(400f, middle.x, 0.001f, "the middle square sits on the level's origin");
        assertEquals(new Maze.Square(2, 1), far.squareAt(middle.x, middle.z));
    }

    @Test
    void routesGoAroundWallsRatherThanThroughThem() {
        Maze maze = maze();
        List<Maze.Square> route = maze.route(new Maze.Square(1, 1), new Maze.Square(5, 1));
        assertFalse(route.isEmpty(), "the two sides are joined at the bottom");
        for (Maze.Square square : route) {
            assertTrue(maze.isOpen(square), "the route only ever stands on open squares: " + square);
        }
        assertEquals(new Maze.Square(1, 1), route.get(0));
        assertEquals(new Maze.Square(5, 1), route.get(route.size() - 1));
        // Straight across would be 4 steps; around the wall is longer.
        assertTrue(route.size() - 1 > 4, "it had to go round: " + (route.size() - 1) + " steps");
    }

    @Test
    void eachStepOfARouteTouchesTheNext() {
        Maze maze = maze();
        List<Maze.Square> route = maze.route(new Maze.Square(1, 1), new Maze.Square(5, 3));
        for (int i = 1; i < route.size(); i++) {
            Maze.Square before = route.get(i - 1);
            Maze.Square now = route.get(i);
            int stepped = Math.abs(before.col() - now.col()) + Math.abs(before.row() - now.row());
            assertEquals(1, stepped, "no jumping or cutting corners between " + before + " and " + now);
        }
    }

    @Test
    void somewhereWalledOffHasNoRoute() {
        Maze sealed = new Maze(List.of(
                "#####",
                "#.#.#",
                "#####"), 0f, 2f);
        assertTrue(sealed.route(new Maze.Square(1, 1), new Maze.Square(3, 1)).isEmpty());
        assertEquals(-1, sealed.walkingDistance(new Maze.Square(1, 1), new Maze.Square(3, 1)));
        assertNull(sealed.stepTowards(new Maze.Square(1, 1), new Maze.Square(3, 1)),
                "nothing to step onto is null, not a crash");
    }

    @Test
    void theFirstStepIsTowardsTheDestination() {
        Maze open = new Maze(List.of(
                "#####",
                "#...#",
                "#####"), 0f, 2f);
        assertEquals(new Maze.Square(2, 1), open.stepTowards(new Maze.Square(1, 1), new Maze.Square(3, 1)));
    }

    @Test
    void sightIsBlockedByWalls() {
        Maze maze = maze();
        Vector3f left = maze.centreOf(new Maze.Square(1, 1), new Vector3f());
        Vector3f right = maze.centreOf(new Maze.Square(5, 1), new Vector3f());
        assertFalse(maze.canSee(left.x, left.z, right.x, right.z), "the middle wall is in the way");

        Vector3f alongTheHall = maze.centreOf(new Maze.Square(1, 3), new Vector3f());
        assertTrue(maze.canSee(left.x, left.z, alongTheHall.x, alongTheHall.z),
                "straight down an open hall there is nothing in the way");
    }

    @Test
    void standingInAWallFindsTheWayOut() {
        Maze maze = maze();
        Vector3f insideTheWall = maze.centreOf(new Maze.Square(3, 1), new Vector3f());
        Maze.Square out = maze.nearestOpen(insideTheWall.x, insideTheWall.z);
        assertTrue(maze.isOpen(out), "something that ends up in a wall is put back on the floor");
    }

    @Test
    void randomSquaresAreAlwaysOpen() {
        Maze maze = maze();
        Random random = new Random(7);
        for (int i = 0; i < 100; i++) {
            assertTrue(maze.isOpen(maze.randomOpen(random)));
        }
    }

    @Test
    void everyOpenSquareIsListed() {
        Maze maze = maze();
        List<Maze.Square> open = maze.openSquares();
        assertEquals(17, open.size());
        assertNotNull(open.get(0));
        open.forEach(square -> assertTrue(maze.isOpen(square)));
    }
}

package net.coffeebrewia.roastengine.backrooms;

import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * The walkable floor of a level, as the monster sees it.
 *
 * <p>A level is written out twice by the generator: once as geometry, and once as this character
 * map, where {@code '#'} is solid and {@code '.'} is open. The map is the "doubled" kind - a
 * square between two cells says whether they are joined - so one square is half a cell across,
 * about two and a half metres. That is small enough to walk a believable line and coarse enough
 * that finding a route across the biggest level costs nothing.
 *
 * <p>Knowing nothing about rendering or the engine, this is the part that can be tested on its
 * own: routes, distances and whether two places are joined at all.
 */
public final class Maze {

    /** Which way a step can go. No diagonals: a monster cutting a corner walks through it. */
    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** A square of the map. */
    public record Square(int col, int row) {
    }

    private final String[] grid;
    private final int width;
    private final int height;
    private final float originX;
    private final float squareSize;

    public Maze(List<String> rows, float originX, float squareSize) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("a level needs a grid");
        }
        this.grid = rows.toArray(new String[0]);
        this.height = grid.length;
        this.width = grid[0].length();
        for (String row : grid) {
            if (row.length() != width) {
                throw new IllegalArgumentException("the grid is ragged: " + row.length() + " != " + width);
            }
        }
        this.originX = originX;
        this.squareSize = squareSize;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** How wide one square is, in metres. */
    public float squareSize() {
        return squareSize;
    }

    /** True when this square can be stood in. Anywhere off the map counts as solid. */
    public boolean isOpen(int col, int row) {
        return row >= 0 && row < height && col >= 0 && col < width && grid[row].charAt(col) == '.';
    }

    public boolean isOpen(Square square) {
        return isOpen(square.col(), square.row());
    }

    /** The world position at the middle of a square, at floor level. */
    public Vector3f centreOf(Square square, Vector3f out) {
        int half = (width - 1) / 2;
        return out.set(originX + (square.col() - half) * squareSize, 0f,
                (square.row() - (height - 1) / 2) * squareSize);
    }

    /** The square a world position falls in, whether or not it is open. */
    public Square squareAt(float x, float z) {
        int half = (width - 1) / 2;
        return new Square(Math.round((x - originX) / squareSize) + half,
                Math.round(z / squareSize) + (height - 1) / 2);
    }

    /** The nearest open square to a world position - where to put something that fell in a wall. */
    public Square nearestOpen(float x, float z) {
        Square start = squareAt(x, z);
        if (isOpen(start)) {
            return start;
        }
        // Somewhere off the map entirely - another level, say - would otherwise ring outwards
        // for ever without touching the grid, so come back to the edge first.
        start = new Square(Math.max(0, Math.min(width - 1, start.col())),
                Math.max(0, Math.min(height - 1, start.row())));
        for (int radius = 1; radius < Math.max(width, height); radius++) {
            for (int dcol = -radius; dcol <= radius; dcol++) {
                for (int drow = -radius; drow <= radius; drow++) {
                    if (Math.abs(dcol) != radius && Math.abs(drow) != radius) {
                        continue;   // only the ring at this distance
                    }
                    Square candidate = new Square(start.col() + dcol, start.row() + drow);
                    if (isOpen(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        throw new IllegalStateException("the level has no open squares at all");
    }

    /**
     * The next square to step onto when walking from {@code from} towards {@code to}, or null
     * when there is no way through.
     *
     * <p>Breadth-first from the destination backwards, so the first square found is on a shortest
     * route. The biggest shipped level is about two thousand squares, which is nothing.
     */
    public Square stepTowards(Square from, Square to) {
        List<Square> route = route(from, to);
        return route.size() < 2 ? null : route.get(1);
    }

    /**
     * The whole route from one square to another, starting with {@code from} and ending with
     * {@code to}. Empty when they are not joined.
     */
    public List<Square> route(Square from, Square to) {
        if (!isOpen(from) || !isOpen(to)) {
            return List.of();
        }
        if (from.equals(to)) {
            return List.of(from);
        }
        int[] cameFrom = new int[width * height];
        Arrays.fill(cameFrom, -1);
        int start = index(from);
        int goal = index(to);
        cameFrom[start] = start;
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            int at = queue.poll();
            if (at == goal) {
                return rebuild(cameFrom, start, goal);
            }
            int col = at % width;
            int row = at / width;
            for (int[] step : STEPS) {
                int ncol = col + step[0];
                int nrow = row + step[1];
                if (!isOpen(ncol, nrow)) {
                    continue;
                }
                int next = nrow * width + ncol;
                if (cameFrom[next] == -1) {
                    cameFrom[next] = at;
                    queue.add(next);
                }
            }
        }
        return List.of();
    }

    /** How many steps apart two squares are by walking, or -1 when there is no way. */
    public int walkingDistance(Square from, Square to) {
        return route(from, to).size() - 1;
    }

    /**
     * True when nothing solid stands between the middles of two squares - what the monster's
     * eyes can check. Walks the line in short steps, which is exact enough on a grid this coarse.
     */
    public boolean canSee(float fromX, float fromZ, float toX, float toZ) {
        float dx = toX - fromX;
        float dz = toZ - fromZ;
        float distance = (float) Math.sqrt(dx * dx + dz * dz);
        if (distance < 0.001f) {
            return true;
        }
        int steps = (int) Math.ceil(distance / (squareSize * 0.4f));
        for (int i = 1; i < steps; i++) {
            float t = (float) i / steps;
            if (!isOpen(squareAt(fromX + dx * t, fromZ + dz * t))) {
                return false;
            }
        }
        return true;
    }

    /** A random open square, for somewhere to wander off to. */
    public Square randomOpen(Random random) {
        for (int attempt = 0; attempt < 200; attempt++) {
            Square candidate = new Square(random.nextInt(width), random.nextInt(height));
            if (isOpen(candidate)) {
                return candidate;
            }
        }
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                if (isOpen(col, row)) {
                    return new Square(col, row);
                }
            }
        }
        throw new IllegalStateException("the level has no open squares at all");
    }

    /** Every open square. */
    public List<Square> openSquares() {
        List<Square> squares = new ArrayList<>();
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                if (isOpen(col, row)) {
                    squares.add(new Square(col, row));
                }
            }
        }
        return squares;
    }

    private int index(Square square) {
        return square.row() * width + square.col();
    }

    private List<Square> rebuild(int[] cameFrom, int start, int goal) {
        List<Square> route = new ArrayList<>();
        for (int at = goal; at != start; at = cameFrom[at]) {
            route.add(new Square(at % width, at / width));
        }
        route.add(new Square(start % width, start / width));
        java.util.Collections.reverse(route);
        return route;
    }
}

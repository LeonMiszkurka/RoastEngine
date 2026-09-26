package net.coffeebrewia.roastengine.backrooms;

import org.joml.Vector3f;

import java.util.List;
import java.util.Random;

/**
 * The thing that hunts you.
 *
 * <p>It always knows roughly where it is going and never where you are, unless it works that out:
 *
 * <ul>
 *   <li><b>Wandering</b> - walking to some far corner of the level. It is not looking for you yet.</li>
 *   <li><b>Investigating</b> - it heard something. It walks to where the noise came from and has
 *       a look around. Running is loud; walking carries half as far; standing still is silent.</li>
 *   <li><b>Hunting</b> - it can see you. It comes straight for you at a speed you cannot outrun
 *       in a straight line, and keeps coming for a while after you break line of sight.</li>
 * </ul>
 *
 * <p>Everything here is plain geometry over a {@link Maze}, so how it behaves can be tested
 * without a window: what it hears, what it can see, where it walks and when it catches you.
 */
public final class Stalker {

    /** What the monster is doing, which is also what the sound and the HUD key off. */
    public enum Mood {
        /** Not looking for you. */
        WANDER,
        /** Heard something and coming to see. */
        INVESTIGATE,
        /** Has you, and is coming. */
        HUNT
    }

    /** How hard it is to get away from. Picked in the level's Options. */
    public enum Difficulty {
        /** Slow, half deaf, short sighted: for looking around the place. */
        CALM(0.75f, 0.6f, 0.6f),
        /** As the level ships. */
        NORMAL(1f, 1f, 1f),
        /** Faster than you, and it hears you breathe. */
        NIGHTMARE(1.18f, 1.5f, 1.35f);

        public final float speed;
        public final float hearing;
        public final float sight;

        Difficulty(float speed, float hearing, float sight) {
            this.speed = speed;
            this.hearing = hearing;
            this.sight = sight;
        }

        /** The next one round, for a settings button that cycles. */
        public Difficulty next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** How often the route is worked out again while hunting, in seconds. */
    private static final float REPATH_SECONDS = 0.35f;
    /** Close enough to the middle of a square to call it reached. */
    private static final float ARRIVED = 0.35f;
    /** How quickly it turns to face where it is walking, in radians per second. */
    private static final float TURN_SPEED = 5.5f;
    /** A wander target at least this many squares away, so it actually goes somewhere. */
    private static final int WANDER_MINIMUM = 6;

    private final Maze maze;
    private final BackroomsConfig.Monster config;
    private final Random random;

    private final Vector3f position = new Vector3f();
    private final Vector3f stepTarget = new Vector3f();
    private float yaw;

    /** Where the player was when last seen, and whether that is right now. */
    private final Vector3f seenAt = new Vector3f();
    private boolean seesPlayer;

    private Mood mood = Mood.WANDER;
    private Difficulty difficulty = Difficulty.NORMAL;
    private Maze.Square goal;
    private List<Maze.Square> route = List.of();
    private int routeAt;
    private float repathIn;
    /** Time left before it gives up on a player it can no longer see. */
    private float patience;
    private boolean caught;

    public Stalker(Maze maze, BackroomsConfig.Monster config, Vector3f start, Random random) {
        this.maze = maze;
        this.config = config;
        this.random = random;
        Maze.Square from = maze.nearestOpen(start.x, start.z);
        maze.centreOf(from, position);
        this.goal = from;
    }

    public Vector3f position() {
        return position;
    }

    /** Which way it is facing, in the engine's convention (0 looks down -Z). */
    public float yaw() {
        return yaw;
    }

    public Mood mood() {
        return mood;
    }

    public boolean hasCaughtYou() {
        return caught;
    }

    public void setDifficulty(Difficulty difficulty) {
        this.difficulty = difficulty;
    }

    /** Puts it back somewhere far away and forgets about you - what a death resets it to. */
    public void reset(Vector3f to) {
        maze.centreOf(maze.nearestOpen(to.x, to.z), position);
        mood = Mood.WANDER;
        route = List.of();
        routeAt = 0;
        caught = false;
        patience = 0f;
        goal = maze.nearestOpen(to.x, to.z);
    }

    /**
     * A step of the hunt.
     *
     * @param deltaSeconds time since the last step
     * @param player       where the player's eyes are
     * @param playerYaw    which way the player is looking, for nothing yet - kept for a future
     *                     "it only moves when you are not looking" level
     * @param noise        how loud the player is right now: 0 standing still, 0.5 walking,
     *                     1 running
     */
    public void update(float deltaSeconds, Vector3f player, float playerYaw, float noise) {
        if (caught) {
            return;
        }
        sense(deltaSeconds, player, noise);
        walk(deltaSeconds);
        if (position.distance(player.x, position.y, player.z) <= config.killRange()) {
            caught = true;
        }
    }

    /** Looks and listens, which is what decides the mood and where it heads next. */
    private void sense(float deltaSeconds, Vector3f player, float noise) {
        boolean sees = canSee(player);
        seesPlayer = sees;
        if (sees) {
            seenAt.set(player);
            mood = Mood.HUNT;
            patience = config.giveUpSeconds();
            headFor(maze.nearestOpen(player.x, player.z), true);
            return;
        }
        if (mood == Mood.HUNT) {
            patience -= deltaSeconds;
            if (patience > 0) {
                // Still coming: it walks to where you were, not to where you are.
                repathIn -= deltaSeconds;
                if (route.isEmpty() || routeAt >= route.size()) {
                    headFor(maze.nearestOpen(player.x, player.z), true);
                }
                return;
            }
            mood = Mood.INVESTIGATE;
        }
        if (hears(player, noise)) {
            mood = Mood.INVESTIGATE;
            headFor(maze.nearestOpen(player.x, player.z), false);
            return;
        }
        if (arrived()) {
            // Nothing here. Back to wandering.
            mood = Mood.WANDER;
            headFor(wanderTarget(), false);
        }
    }

    /** True when the player is in front of it, close enough, and not behind a wall. */
    private boolean canSee(Vector3f player) {
        float range = config.sightRange() * difficulty.sight;
        float dx = player.x - position.x;
        float dz = player.z - position.z;
        float distance = (float) Math.sqrt(dx * dx + dz * dz);
        if (distance > range) {
            return false;
        }
        // Right on top of the player counts as seen whichever way it happens to face.
        if (distance > config.killRange() * 2f) {
            float towards = (float) Math.atan2(dx, -dz);
            float off = Math.abs(wrap(towards - yaw));
            if (off > Math.toRadians(config.sightAngleDegrees()) / 2f) {
                return false;
            }
        }
        return maze.canSee(position.x, position.z, player.x, player.z);
    }

    /** Noise carries through walls, so this is distance only - loud enough, near enough. */
    private boolean hears(Vector3f player, float noise) {
        if (noise <= 0.01f) {
            return false;
        }
        float range = config.hearingRange() * difficulty.hearing * Math.min(1f, noise);
        float dx = player.x - position.x;
        float dz = player.z - position.z;
        return dx * dx + dz * dz <= range * range;
    }

    /** Somewhere across the level worth walking to. */
    private Maze.Square wanderTarget() {
        Maze.Square here = maze.nearestOpen(position.x, position.z);
        Maze.Square best = here;
        for (int attempt = 0; attempt < 12; attempt++) {
            Maze.Square candidate = maze.randomOpen(random);
            int distance = maze.walkingDistance(here, candidate);
            if (distance >= WANDER_MINIMUM) {
                return candidate;
            }
            if (distance > maze.walkingDistance(here, best)) {
                best = candidate;
            }
        }
        return best;
    }

    /** Works out a route to a square, unless it is already on one that ends there. */
    private void headFor(Maze.Square target, boolean urgent) {
        boolean sameGoal = target.equals(goal);
        if (sameGoal && !route.isEmpty() && routeAt < route.size() && (!urgent || repathIn > 0)) {
            return;
        }
        goal = target;
        route = maze.route(maze.nearestOpen(position.x, position.z), target);
        routeAt = route.isEmpty() ? 0 : 1;   // route starts with the square it is standing on
        repathIn = REPATH_SECONDS;
    }

    private boolean arrived() {
        return route.isEmpty() || routeAt >= route.size();
    }

    /** Walks along the route, turning to face the way it goes. */
    private void walk(float deltaSeconds) {
        float speed = (mood == Mood.HUNT ? config.chaseSpeed() : config.speed()) * difficulty.speed;
        // The last stretch is walked straight at the player rather than to the middle of their
        // square: someone standing at the edge of a square is otherwise a metre out of reach,
        // watching it stand in the middle of the room doing nothing.
        if (seesPlayer && mood == Mood.HUNT
                && position.distance(seenAt.x, position.y, seenAt.z) <= maze.squareSize() * 2f) {
            stepTo(seenAt.x, seenAt.z, speed, deltaSeconds);
            return;
        }
        if (arrived()) {
            return;
        }
        maze.centreOf(route.get(routeAt), stepTarget);
        if (position.distance(stepTarget.x, position.y, stepTarget.z) <= ARRIVED) {
            routeAt++;
            return;
        }
        stepTo(stepTarget.x, stepTarget.z, speed, deltaSeconds);
    }

    /** One step towards a point, turning to face the way it goes. */
    private void stepTo(float targetX, float targetZ, float speed, float deltaSeconds) {
        float dx = targetX - position.x;
        float dz = targetZ - position.z;
        float distance = (float) Math.sqrt(dx * dx + dz * dz);
        if (distance < 0.0001f) {
            return;
        }
        float step = Math.min(distance, speed * deltaSeconds);
        position.x += dx / distance * step;
        position.z += dz / distance * step;

        float wanted = (float) Math.atan2(dx, -dz);
        float turn = wrap(wanted - yaw);
        float most = TURN_SPEED * deltaSeconds;
        yaw = wrap(yaw + Math.max(-most, Math.min(most, turn)));
    }

    /** Folds an angle into -pi..pi, so "turn left a little" never becomes "spin right". */
    private static float wrap(float radians) {
        float turns = (float) (Math.PI * 2);
        float angle = radians % turns;
        if (angle > Math.PI) {
            angle -= turns;
        } else if (angle < -Math.PI) {
            angle += turns;
        }
        return angle;
    }
}

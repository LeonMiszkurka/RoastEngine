package net.coffeebrewia.roastengine.world;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Entities: any model in a level with <b>Entity</b> switched on.
 *
 * <p>They wander about near where they were placed, and they can be punched. A punched entity
 * turns into a ragdoll: it flies off, tumbles, bounces on the floor and slides to a stop.
 *
 * <p>When a tumbling entity ends up inside a wall, it does what physics engines famously do -
 * it convulses, thrashing about as the collision keeps pushing it out. That is on purpose here:
 * it is the joke, so it is shaped rather than avoided. It shakes hard, calms down, and is left
 * somewhere sensible instead of vibrating for ever or sinking through the floor.
 */
public final class EntitySystem {

    /** How fast an entity strolls, in metres a second. */
    private static final float WALK_SPEED = 1.4f;
    /** How far from home it will wander. */
    private static final float WANDER_RADIUS = 5f;
    private static final float GRAVITY = -18f;
    /** How much speed is kept after hitting the floor. */
    private static final float BOUNCE = 0.42f;
    private static final float GROUND_FRICTION = 3.5f;
    /** Below this, a ragdoll is treated as having stopped. */
    private static final float REST_SPEED = 0.35f;
    /** How long a stuck entity thrashes before the shaking is damped away. */
    private static final float CONVULSION_SECONDS = 2.5f;
    /** How far a punch throws an entity. */
    private static final float PUNCH_FORCE = 7.5f;
    private static final float PUNCH_LIFT = 4.5f;

    private enum Mode {WANDER, RAGDOLL, RESTING}

    private static final class Entity {
        final WorldObject object;
        final Vector3f home = new Vector3f();
        final Vector3f velocity = new Vector3f();
        /** How fast it is tumbling about each axis, in radians a second. */
        final Vector3f spin = new Vector3f();
        final Vector3f wanderTarget = new Vector3f();
        Mode mode = Mode.WANDER;
        float thinkTimer;
        float convulsing;
        float restingFor;
        /** The floor under where it was placed, which is where it lands back on. */
        float groundY;

        Entity(WorldObject object) {
            this.object = object;
            object.center(home);
            groundY = object.worldMin.y;
        }
    }

    private final List<Entity> entities = new ArrayList<>();
    private final Random random = new Random();
    private final Vector3f scratchMin = new Vector3f();
    private final Vector3f scratchMax = new Vector3f();
    private LoadedWorld world;

    /** Finds every object with Entity switched on. */
    public void load(LoadedWorld world) {
        this.world = world;
        entities.clear();
        for (WorldObject object : world.objects()) {
            if (object.entity) {
                entities.add(new Entity(object));
            }
        }
        if (!entities.isEmpty()) {
            System.out.println("[Entity] " + entities.size() + " entity(s) in the world");
        }
    }

    public boolean isEmpty() {
        return entities.isEmpty();
    }

    /** Every entity that is currently a ragdoll, for the HUD to count. */
    public int ragdolls() {
        return (int) entities.stream().filter(entity -> entity.mode == Mode.RAGDOLL).count();
    }

    public void update(float dt, Vector3f player) {
        for (Entity entity : entities) {
            if (entity.object.removedByScript) {
                continue;
            }
            switch (entity.mode) {
                case WANDER -> wander(entity, dt, player);
                case RAGDOLL -> ragdoll(entity, dt);
                case RESTING -> rest(entity, dt);
            }
        }
    }

    /**
     * The entity nearest the player within {@code reach}, looking roughly at it - what a punch
     * would connect with.
     */
    public WorldObject target(Vector3f eye, Vector3f forward, float reach) {
        WorldObject best = null;
        float bestScore = 0.55f; // how directly it must be in front
        Vector3f middle = new Vector3f();
        for (Entity entity : entities) {
            if (entity.object.removedByScript || entity.mode != Mode.WANDER) {
                continue;
            }
            entity.object.center(middle);
            Vector3f toEntity = new Vector3f(middle).sub(eye);
            float distance = toEntity.length();
            if (distance > reach || distance < 0.01f) {
                continue;
            }
            float facing = toEntity.div(distance).dot(forward);
            if (facing > bestScore) {
                bestScore = facing;
                best = entity.object;
            }
        }
        return best;
    }

    /** Sends an entity flying: the punch itself. */
    public void punch(WorldObject object, Vector3f direction, float strength) {
        for (Entity entity : entities) {
            if (entity.object != object) {
                continue;
            }
            entity.mode = Mode.RAGDOLL;
            entity.restingFor = 0f;
            entity.velocity.set(direction).normalize()
                    .mul(PUNCH_FORCE * strength)
                    .add(0f, PUNCH_LIFT * strength, 0f);
            // A punch never lands square, so it always sets them spinning.
            entity.spin.set(spread(6f), spread(4f), spread(6f));
            entity.convulsing = 0f;
        }
    }

    // --- The three ways an entity behaves ------------------------------------------------

    private void wander(Entity entity, float dt, Vector3f player) {
        entity.thinkTimer -= dt;
        if (entity.thinkTimer <= 0f) {
            // Somewhere new to amble towards, near home.
            entity.thinkTimer = 2.5f + random.nextFloat() * 3f;
            entity.wanderTarget.set(
                    entity.home.x + spread(WANDER_RADIUS),
                    entity.home.y,
                    entity.home.z + spread(WANDER_RADIUS));
        }
        Vector3f position = entity.object.center(new Vector3f());
        Vector3f toTarget = new Vector3f(entity.wanderTarget).sub(position);
        toTarget.y = 0f;
        if (toTarget.length() < 0.25f) {
            entity.thinkTimer = 0f;
            return;
        }
        toTarget.normalize().mul(WALK_SPEED * dt);
        if (!blocked(entity, toTarget)) {
            entity.object.scriptOffset.add(toTarget);
        } else {
            entity.thinkTimer = 0f; // walked into something: pick somewhere else
        }
        // Face the way it is going.
        entity.object.scriptYaw = (float) Math.atan2(toTarget.x, -toTarget.z);
        entity.object.scriptPitch = 0f;
        entity.object.scriptRoll = 0f;
    }

    private void ragdoll(Entity entity, float dt) {
        WorldObject object = entity.object;
        entity.velocity.y += GRAVITY * dt;

        Vector3f step = new Vector3f(entity.velocity).mul(dt);
        if (blocked(entity, step)) {
            // Inside something. This is where a physics engine loses its mind, and where the
            // entity starts convulsing: shove it back out, spin it hard, and shake it.
            entity.convulsing = CONVULSION_SECONDS;
            entity.velocity.mul(-0.35f);
            entity.velocity.y = Math.abs(entity.velocity.y) * 0.6f + 1.5f;
            entity.spin.set(spread(18f), spread(18f), spread(18f));
        } else {
            object.scriptOffset.add(step);
        }

        float bottom = object.worldMin.y + object.scriptOffset.y;
        if (bottom <= entity.groundY) {
            object.scriptOffset.y += entity.groundY - bottom;
            if (entity.velocity.y < 0) {
                entity.velocity.y = -entity.velocity.y * BOUNCE;
                // A bounce also throws the spin off, which keeps it flailing convincingly.
                entity.spin.mul(0.7f).add(spread(3f), spread(3f), spread(3f));
            }
            float friction = Math.max(0f, 1f - GROUND_FRICTION * dt);
            entity.velocity.x *= friction;
            entity.velocity.z *= friction;
        }

        if (entity.convulsing > 0f) {
            entity.convulsing -= dt;
            // The shakes themselves: small, fast and random, dying down as it calms.
            float shake = 0.05f * (entity.convulsing / CONVULSION_SECONDS);
            object.scriptOffset.add(spread(shake), spread(shake), spread(shake));
            entity.spin.mul(0.985f);
        } else {
            entity.spin.mul(Math.max(0f, 1f - 1.6f * dt));
        }

        object.scriptYaw += entity.spin.y * dt;
        object.scriptPitch += entity.spin.x * dt;
        object.scriptRoll += entity.spin.z * dt;

        boolean slow = entity.velocity.length() < REST_SPEED && entity.spin.length() < 0.6f;
        boolean onGround = object.worldMin.y + object.scriptOffset.y <= entity.groundY + 0.05f;
        if (slow && onGround && entity.convulsing <= 0f) {
            entity.mode = Mode.RESTING;
            entity.velocity.zero();
            entity.spin.zero();
        }
    }

    /** Lying on the floor, then slowly picking itself up and going back to wandering. */
    private void rest(Entity entity, float dt) {
        entity.restingFor += dt;
        if (entity.restingFor < 4f) {
            return;
        }
        // Stand back up: unroll the tumbling and carry on as if nothing happened.
        float ease = Math.min(1f, dt * 2f);
        entity.object.scriptPitch -= entity.object.scriptPitch * ease;
        entity.object.scriptRoll -= entity.object.scriptRoll * ease;
        if (Math.abs(entity.object.scriptPitch) < 0.02f && Math.abs(entity.object.scriptRoll) < 0.02f) {
            entity.object.scriptPitch = 0f;
            entity.object.scriptRoll = 0f;
            entity.mode = Mode.WANDER;
            entity.thinkTimer = 0f;
            entity.home.set(entity.object.center(new Vector3f())); // home is wherever it landed
        }
    }

    /** True when moving by {@code step} would put the entity inside something solid. */
    private boolean blocked(Entity entity, Vector3f step) {
        WorldObject object = entity.object;
        scratchMin.set(object.worldMin).add(object.scriptOffset).add(step);
        scratchMax.set(object.worldMax).add(object.scriptOffset).add(step);
        // A little slimmer than the model, so brushing a wall is not treated as being inside it.
        scratchMin.add(0.05f, 0.05f, 0.05f);
        scratchMax.sub(0.05f, 0.05f, 0.05f);
        for (WorldObject other : world.objects()) {
            if (other != object && other.isSolid() && other.overlaps(scratchMin, scratchMax)) {
                return true;
            }
        }
        return false;
    }

    /** A random number between -amount and +amount. */
    private float spread(float amount) {
        return (random.nextFloat() * 2f - 1f) * amount;
    }
}

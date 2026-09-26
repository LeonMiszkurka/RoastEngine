package net.coffeebrewia.roastengine.world;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * What happens when the trigger is pulled on whatever is in the player's hand.
 *
 * <p>The spawn list's <b>Usables</b> are things meant to be used rather than placed, and this is
 * the using of them: a gun fires, anything else swings. Guns work by name - a {@code gun_shotgun}
 * from any mod is a shotgun, wherever it was spawned - so a gun dropped into the Backrooms shoots
 * there as it would in the arena.
 *
 * <p>This is deliberately not the arena's shooting: there are no teams, no ammo and no referee
 * here, only a shot that pushes entities about. A match keeps its own rules.
 */
public final class Usables {

    /** How a held thing behaves when used. */
    public enum Kind {
        /** Fires along the aim. */
        GUN,
        /** Swung at whatever is in front, like a punch with something in hand. */
        SWING,
        /** Nothing to do with it. */
        NONE
    }

    /**
     * What one gun does. These mirror the arena's guns, so a shotgun feels the same in both.
     *
     * @param label      what to call it in the notice
     * @param fireRate   shots a second
     * @param pellets    shots per pull of the trigger: more than one is a shotgun
     * @param spread     how far off the aim a pellet may go, in degrees
     * @param range      how far a shot carries, in metres
     * @param recoil     how far the view kicks up per shot, in degrees
     * @param push       how hard a hit knocks an entity about, as a multiple of a punch
     * @param pitch      the bang's pitch: under 1 is a deeper gun
     */
    public record Gun(String label, float fireRate, int pellets, float spread, float range,
                      float recoil, float push, float pitch) {
    }

    /** One shot's streak through the air, fading as it ages. */
    public static final class Shot {
        private final Vector3f from;
        private final Vector3f to;
        private float age;

        Shot(Vector3f from, Vector3f to) {
            this.from = from;
            this.to = to;
        }

        public Vector3f from() {
            return from;
        }

        public Vector3f to() {
            return to;
        }

        public float age() {
            return age;
        }
    }

    /** What a pull of the trigger did, for the sound and the kick the caller applies. */
    public record Fired(Gun gun, boolean hit) {
    }

    /** How long a tracer stays visible. */
    public static final float TRACER_SECONDS = 0.09f;
    /** How far a swing reaches, and how hard it hits - a punch with something in hand. */
    private static final float SWING_REACH = 2.4f;
    private static final float SWING_STRENGTH = 1.4f;
    private static final float SWING_SECONDS = 0.45f;
    /** Where the shot appears to come from: a little below the eyes, where the gun is held. */
    private static final float MUZZLE_DROP = 0.12f;
    private static final float MUZZLE_FORWARD = 0.45f;

    /**
     * The guns, by the word in the object's name that gives one away. Ordered, because
     * {@code gun_sniper} must not be read as the plain {@code gun}, which comes last.
     */
    private static final Map<String, Gun> GUNS = new LinkedHashMap<>();

    static {
        GUNS.put("shotgun", new Gun("Shotgun", 1.2f, 8, 6.0f, 30f, 5.0f, 0.55f, 0.8f));
        GUNS.put("sniper", new Gun("Sniper Rifle", 0.7f, 1, 0.05f, 200f, 6.0f, 2.2f, 0.7f));
        GUNS.put("revolver", new Gun("Revolver", 1.6f, 1, 0.4f, 80f, 3.5f, 1.6f, 0.95f));
        GUNS.put("rifle", new Gun("Assault Rifle", 9.0f, 1, 1.2f, 90f, 0.8f, 0.9f, 1.05f));
        GUNS.put("smg", new Gun("SMG", 13.0f, 1, 2.4f, 45f, 0.5f, 0.6f, 1.35f));
        GUNS.put("pistol", new Gun("Pistol", 5.0f, 1, 0.8f, 60f, 1.2f, 1.0f, 1.25f));
        GUNS.put("gun", new Gun("Gun", 4.0f, 1, 1.0f, 60f, 1.5f, 1.0f, 1.1f));
    }

    /** Things that are used by swinging them rather than by firing them. */
    private static final String SWING_WORDS =
            "(?i).*(knife|bat|crowbar|axe|hammer|pipe|plank|bottle|torch|wrench|shovel).*";

    private final Random random = new Random();
    private final List<Shot> shots = new ArrayList<>();
    /** Seconds until this thing can be used again. */
    private float cooldown;
    private float swinging;

    /** What pulling the trigger on something would do. */
    public static Kind kindOf(WorldObject held) {
        if (held == null || held.removedByScript || SpawnKit.isSpawnGun(held)) {
            return Kind.NONE;   // the spawn gun opens the list instead; R already does that
        }
        if (gunFor(held.name) != null) {
            return Kind.GUN;
        }
        return held.name != null && held.name.matches(SWING_WORDS) ? Kind.SWING : Kind.NONE;
    }

    /** The gun an object's name says it is, or null when it is not a gun. */
    public static Gun gunFor(String name) {
        if (name == null) {
            return null;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Gun> entry : GUNS.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Ages the tracers and counts down to the next shot. */
    public void update(float deltaSeconds) {
        cooldown = Math.max(0f, cooldown - deltaSeconds);
        swinging = Math.max(0f, swinging - deltaSeconds);
        for (Shot shot : shots) {
            shot.age += deltaSeconds;
        }
        shots.removeIf(shot -> shot.age >= TRACER_SECONDS);
    }

    /** The shots still in the air, for drawing. */
    public List<Shot> shots() {
        return shots;
    }

    /** True while a swing is still going, so one press is one swing. */
    public boolean isSwinging() {
        return swinging > 0f;
    }

    /**
     * Fires what is in hand, if it is a gun and it is loaded.
     *
     * <p>A gun with a fire rate above a few shots a second keeps firing while the trigger is held;
     * anything slower takes a pull per shot, so a shotgun cannot be emptied by leaning on the
     * mouse.
     *
     * @param pressed     the trigger went down this frame
     * @param held        the trigger is still down
     * @return what the shot did, or null when nothing was fired
     */
    public Fired pullTrigger(WorldObject inHand, LoadedWorld world, EntitySystem entities,
                             Vector3f eye, Vector3f aim, boolean pressed, boolean held) {
        Gun gun = kindOf(inHand) == Kind.GUN ? gunFor(inHand.name) : null;
        if (gun == null || world == null) {
            return null;
        }
        boolean automatic = gun.fireRate() >= 6f;
        if (!(pressed || (automatic && held)) || cooldown > 0f) {
            return null;
        }
        cooldown = 1f / Math.max(0.1f, gun.fireRate());

        Vector3f muzzle = new Vector3f(aim).normalize().mul(MUZZLE_FORWARD).add(eye)
                .sub(0f, MUZZLE_DROP, 0f);
        boolean anyHit = false;
        for (int pellet = 0; pellet < Math.max(1, gun.pellets()); pellet++) {
            Vector3f direction = Ballistics.scatter(aim, gun.spread(), random, new Vector3f());
            float distance = Ballistics.worldHit(world, eye, direction, gun.range());

            // The nearest entity in front of that wall takes the pellet.
            WorldObject struck = null;
            for (WorldObject object : world.objects()) {
                if (!object.entity || object.removedByScript || object.carried) {
                    continue;
                }
                Vector3f min = new Vector3f(object.worldMin).add(object.scriptOffset);
                Vector3f max = new Vector3f(object.worldMax).add(object.scriptOffset);
                float t = Ballistics.rayBox(eye, direction, min, max);
                if (t < distance) {
                    distance = t;
                    struck = object;
                }
            }
            if (struck != null) {
                entities.punch(struck, new Vector3f(direction), gun.push());
                anyHit = true;
            }
            shots.add(new Shot(muzzle, new Vector3f(direction).mul(distance).add(eye)));
        }
        return new Fired(gun, anyHit);
    }

    /**
     * Swings what is in hand at whatever is in front of it.
     *
     * @return the entity it connected with, or null when it hit nothing or was not a swing
     */
    public WorldObject swing(WorldObject inHand, EntitySystem entities, Vector3f eye, Vector3f aim) {
        if (kindOf(inHand) != Kind.SWING || swinging > 0f || entities == null) {
            return null;
        }
        swinging = SWING_SECONDS;
        WorldObject target = entities.target(eye, new Vector3f(aim).normalize(), SWING_REACH);
        if (target != null) {
            entities.punch(target, new Vector3f(aim).normalize(), SWING_STRENGTH);
        }
        return target;
    }

    /** Drops every tracer, for a world change. */
    public void reset() {
        shots.clear();
        cooldown = 0f;
        swinging = 0f;
    }
}

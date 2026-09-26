package net.coffeebrewia.roastengine.arena;

import net.coffeebrewia.roastengine.audio.SoundBank;
import net.coffeebrewia.roastengine.input.Input;
import net.coffeebrewia.roastengine.multiplayer.MultiplayerSession;
import net.coffeebrewia.roastengine.multiplayer.RemotePlayer;
import net.coffeebrewia.roastengine.net.ArenaRules;
import net.coffeebrewia.roastengine.net.Protocol.ArenaAction;
import net.coffeebrewia.roastengine.net.Protocol.ArenaEvent;
import net.coffeebrewia.roastengine.net.Protocol.ArenaPlayer;
import net.coffeebrewia.roastengine.net.Protocol.ArenaState;
import net.coffeebrewia.roastengine.net.Protocol.Message;
import net.coffeebrewia.roastengine.net.Protocol.Shoot;
import net.coffeebrewia.roastengine.net.Protocol.ShotFired;
import net.coffeebrewia.roastengine.render.Camera;
import net.coffeebrewia.roastengine.world.Ballistics;
import net.coffeebrewia.roastengine.world.HoldSystem;
import net.coffeebrewia.roastengine.world.LoadedWorld;
import net.coffeebrewia.roastengine.world.WorldObject;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_L;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_R;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

/**
 * Playing an arena: the lobby, the loadout, the guns and the match.
 *
 * <p>The referee ({@link ArenaLink}) decides everything that matters - who is on which team, who
 * got hit, who won. This is the player's side of it: aiming and firing, working out what a shot
 * hit, putting the player where the match says they should be, and remembering enough to draw
 * the screens ({@link ArenaScreens}).
 *
 * <p>Guns are ordinary objects in the level - the ones on the lobby's rack - carried with the
 * hold system, so they hang in the hand with {@code player_hold.glb}'s pose like anything else
 * does. The loadout decides which of them go in the player's bag, which is why a full loadout
 * needs the PlaceHolder API.
 */
public final class ArenaMode {

    /** What the arena needs from the game around it. */
    public interface Host {
        Camera camera();

        LoadedWorld world();

        HoldSystem holding();

        float eyeHeight();

        /** Everyone else online; empty offline. */
        Collection<RemotePlayer> others();

        /** Puts the player somewhere, stopped, facing a way. */
        void teleport(Vector3f eye, float yawDegrees);

        /** A practice target was hit: knock it over. */
        void knockEntity(WorldObject entity, Vector3f direction);

        void playSound(String name, float volume, float pitch);

        void notice(String text);
    }

    /** A player is this wide, for shooting at. */
    static final float BODY_RADIUS = 0.42f;
    /** And their head starts this far below their eyes. */
    static final float HEAD_BELOW_EYES = 0.22f;
    static final float HEAD_ABOVE_EYES = 0.25f;
    /** How long a tracer hangs in the air. */
    static final float TRACER_SECONDS = 0.09f;
    static final float FEED_SECONDS = 6f;
    private static final float HEARING_RANGE = 70f;

    /** A shot's streak through the air; {@code age} counts up to {@link #TRACER_SECONDS}. */
    public record Tracer(Vector3f from, Vector3f to, float[] age, boolean mine) {
    }

    /** A line in the kill feed. */
    record FeedLine(String killer, int killerTeam, String gun, String victim, int victimTeam, float[] age) {
    }

    private final ArenaConfig config;
    private final ArenaLink link;
    private final Host host;
    /** The level's model for each gun, by gun index; null when the level does not have it. */
    private final WorldObject[] gunObjects;
    private final Random random = new Random();

    /** Which guns the player carries, in slot order, as gun indices. */
    private final List<Integer> loadout = new ArrayList<>();
    /** Rounds left in each gun's magazine. */
    private final Map<Integer, Integer> ammo = new HashMap<>();
    private float reloadLeft;
    private int reloadingGun = -1;
    private float cooldown;
    private boolean triggerWas;
    /** How far recoil has kicked the view up, in radians, still to settle back. */
    private float recoilDebt;
    /** Seconds since the last shot; the view only settles once the shooting stops. */
    private float sinceShot;
    /**
     * Set while a menu has the mouse. The click that closes a menu is still held down on the next
     * frame, and must not become a shot - so the trigger has to be let go first.
     */
    private boolean mustRelease;
    /**
     * Dev aid: -ParenaAuto=close,ready,shoot,aim,loadout,vote1,slot4 drives the lobby without hands -
     * closes the menu, readies up, holds the trigger, keeps the crosshair on the nearest enemy,
     * opens the loadout, votes for a map or picks a slot. Two game windows started with
     * {@code ready,aim,shoot} play a match against each other and should trade kills.
     */
    private final List<String> auto = List.of(System.getProperty("roastengine.arenaAuto", "")
            .toLowerCase().split(","));
    private boolean autoDone;

    private boolean panelOpen = true;
    private boolean loadoutOpen;
    private int lastPhase = -1;
    private boolean wasAlive = true;
    private int lastSentGun = -2;
    private String killedBy;

    final List<Tracer> tracers = new ArrayList<>();
    final List<FeedLine> feed = new ArrayList<>();
    /** Counts down after landing a hit; drawn as the X on the crosshair. */
    float hitmarker;
    boolean hitmarkerKill;
    /** Counts down after being hit; drawn as red at the edges of the screen. */
    float damageFlash;

    private ArenaMode(ArenaConfig config, ArenaLink link, Host host) {
        this.config = config;
        this.link = link;
        this.host = host;
        this.gunObjects = new WorldObject[config.guns().size()];
        for (int i = 0; i < gunObjects.length; i++) {
            ArenaConfig.Gun gun = config.guns().get(i);
            WorldObject object = find(host.world(), gun.object());
            if (object == null) {
                System.err.println("[Arena] No object called '" + gun.object() + "' for the "
                        + gun.name() + " - it will be missing from the loadout");
                continue;
            }
            object.grip = new Vector3f(gun.grip());
            gunObjects[i] = object;
        }
        // Start with as many guns as the player can carry, in the order the arena lists them.
        for (int i = 0; i < gunObjects.length && loadout.size() < host.holding().inventory().size(); i++) {
            if (gunObjects[i] != null) {
                loadout.add(i);
            }
        }
        host.holding().setDropAllowed(false);
        link.setup(config.settings());
    }

    /**
     * The arena for a world, or null when the world is not one.
     *
     * @param online the server connection, or null to practise alone
     */
    public static ArenaMode start(LoadedWorld world, MultiplayerSession online, Host host) {
        ArenaConfig config = ArenaConfig.find(world.modFolders());
        if (config == null) {
            return null;
        }
        // A server from before arenas cannot referee one, so the match is played here instead of
        // sending it messages it would drop. Everything else about being online carries on.
        boolean refereed = online != null && online.supportsArena();
        if (online != null && !refereed) {
            System.out.println("[Arena] This server is too old to referee a match - playing "
                    + config.name() + " as practice.");
        }
        ArenaLink link = refereed ? new ArenaLink.Online(online) : new ArenaLink.Practice();
        System.out.println("[Arena] " + config.name() + ": " + config.maps().size() + " map(s), "
                + config.guns().size() + " gun(s)" + (link.isPractice() ? " - practice" : ""));
        return new ArenaMode(config, link, host);
    }

    // --- Every frame ------------------------------------------------------------------

    /**
     * Runs the arena for a frame.
     *
     * @param inputAllowed false while chatting, paused or in another menu
     */
    public void update(float dt, Input input, boolean inputAllowed) {
        link.update(dt);
        for (Message message : link.drainEvents()) {
            handle(message);
        }
        ArenaState state = link.state();
        if (state != null) {
            followPhase(state);
            followLife(state);
            if (!autoDone) {
                autoDone = true;
                for (String step : auto) {
                    if (step.startsWith("vote") && step.length() > 4) {
                        vote(Integer.parseInt(step.substring(4)));   // vote0, vote1, ...
                    } else if (step.startsWith("slot") && step.length() > 4) {
                        host.holding().inventory().select(Integer.parseInt(step.substring(4)) - 1);
                    }
                }
                if (auto.contains("ready")) {
                    setReady(true);
                }
                if (auto.contains("close") || auto.contains("ready")) {
                    closePanel();
                }
                if (auto.contains("loadout")) {
                    loadoutOpen = true;
                }
            }
        }
        if (inputAllowed && input.wasKeyPressed(GLFW_KEY_L) && canOpenPanel()) {
            panelOpen = !panelOpen;
            loadoutOpen = false;
        }
        if (inputAllowed && !panelOpen) {
            handleGun(dt, input);
        } else {
            mustRelease = true;
        }
        tickEffects(dt);
        syncGun();
    }

    /** Reacts to the match moving on: into the lobby, into a map, onto the results. */
    private void followPhase(ArenaState state) {
        if (state.phase() == lastPhase) {
            return;
        }
        int previous = lastPhase;
        lastPhase = state.phase();
        switch (state.phase()) {
            case ArenaRules.LOBBY -> {
                if (previous != ArenaRules.COUNTDOWN) {
                    goToLobby();
                }
            }
            case ArenaRules.COUNTDOWN -> host.notice("Match starting on " + mapName(state.map()) + "!");
            case ArenaRules.PLAYING -> {
                spawnInMap(state);
                refillAll();
                panelOpen = false;
                loadoutOpen = false;
                host.notice("Fight! First to " + config.scoreLimit() + " wins");
            }
            case ArenaRules.RESULTS -> panelOpen = false;
            default -> {
                // A phase from a newer server: carry on as we are.
            }
        }
    }

    /** Dying and coming back. */
    private void followLife(ArenaState state) {
        ArenaPlayer me = me(state);
        if (me == null) {
            return;
        }
        boolean alive = me.alive();
        if (alive && !wasAlive && state.phase() == ArenaRules.PLAYING) {
            spawnInMap(state);
            refillAll();
            killedBy = null;
        }
        wasAlive = alive;
    }

    private void goToLobby() {
        ArenaConfig.Spawn lobby = config.lobby();
        host.teleport(lobby.eye(), lobby.yawDegrees());
        panelOpen = true;
        loadoutOpen = false;
        killedBy = null;
        applyLoadout();
        refillAll();
    }

    private void spawnInMap(ArenaState state) {
        ArenaConfig.ArenaMap map = config.maps().get(Math.max(0, Math.min(config.maps().size() - 1, state.map())));
        ArenaPlayer me = me(state);
        List<ArenaConfig.Spawn> spawns = map.spawnsFor(me == null ? ArenaRules.RED : me.team());
        if (spawns.isEmpty()) {
            return;
        }
        ArenaConfig.Spawn spawn = spawns.get(random.nextInt(spawns.size()));
        host.teleport(spawn.eye(), spawn.yawDegrees());
    }

    private void handle(Message message) {
        if (message instanceof ShotFired shot) {
            Vector3f from = new Vector3f(shot.ox(), shot.oy(), shot.oz());
            Vector3f direction = new Vector3f(shot.dx(), shot.dy(), shot.dz());
            if (direction.lengthSquared() > 1e-6f) {
                direction.normalize();
                tracers.add(new Tracer(from, new Vector3f(direction).mul(shot.distance()).add(from),
                        new float[]{0f}, false));
            }
            float distance = from.distance(host.camera().position());
            float volume = Math.max(0f, 1f - distance / HEARING_RANGE);
            if (volume > 0f) {
                host.playSound(SoundBank.GUNSHOT, 0.2f + volume * 0.6f, pitchOf(shot.gun()));
            }
        } else if (message instanceof ArenaEvent event) {
            handleEvent(event);
        }
    }

    private void handleEvent(ArenaEvent event) {
        ArenaState state = link.state();
        int me = link.myId();
        switch (event.kind()) {
            case ArenaEvent.KILL -> {
                String gun = event.value() >= 0 && event.value() < config.guns().size()
                        ? config.guns().get(event.value()).name() : "?";
                feed.add(new FeedLine(link.nameOf(event.a()), teamOf(state, event.a()), gun,
                        link.nameOf(event.b()), teamOf(state, event.b()), new float[]{0f}));
                while (feed.size() > 5) {
                    feed.remove(0);
                }
                if (event.b() == me) {
                    killedBy = link.nameOf(event.a());
                } else if (event.a() == me) {
                    hitmarker = 0.35f;
                    hitmarkerKill = true;
                    host.notice("You eliminated " + link.nameOf(event.b()));
                }
            }
            case ArenaEvent.HIT -> {
                if (event.b() == me) {
                    damageFlash = Math.min(1f, damageFlash + 0.35f + event.value() / 150f);
                }
            }
            case ArenaEvent.MATCH_END -> host.playSound(SoundBank.WHOOSH, 0.5f, 1.4f);
            default -> {
                // MATCH_START and RESPAWN are seen through the state instead.
            }
        }
    }

    // --- Guns -------------------------------------------------------------------------

    private void handleGun(float dt, Input input) {
        cooldown = Math.max(0f, cooldown - dt);
        settleRecoil(dt);
        int gunIndex = heldGun();
        if (reloadingGun >= 0) {
            reloadLeft -= dt;
            if (reloadingGun != gunIndex) {
                reloadingGun = -1; // switched away: the reload is abandoned
            } else if (reloadLeft <= 0f) {
                ammo.put(gunIndex, config.guns().get(gunIndex).magazine());
                reloadingGun = -1;
            }
        }
        if (auto.contains("aim")) {
            autoAim();
        }
        boolean trigger = input.isMouseDown(GLFW_MOUSE_BUTTON_LEFT) || auto.contains("shoot");
        if (mustRelease && !auto.contains("shoot")) {
            mustRelease = trigger;
            trigger = false;
        }
        boolean pressed = trigger && !triggerWas;
        triggerWas = trigger;
        if (gunIndex < 0) {
            return;
        }
        ArenaConfig.Gun gun = config.guns().get(gunIndex);
        if (input.wasKeyPressed(GLFW_KEY_R)) {
            startReload(gunIndex);
        }
        if (!canShoot() || reloadingGun >= 0 || cooldown > 0f) {
            return;
        }
        if (!(gun.automatic() ? trigger : pressed)) {
            return;
        }
        int left = ammo.getOrDefault(gunIndex, gun.magazine());
        if (left <= 0) {
            if (pressed) {
                host.playSound(SoundBank.CLICK, 0.6f, 0.7f); // dry fire
                startReload(gunIndex);
            }
            return;
        }
        ammo.put(gunIndex, left - 1);
        cooldown = 1f / gun.fireRate();
        fire(gunIndex, gun);
        if (left - 1 == 0) {
            startReload(gunIndex);
        }
    }

    private void startReload(int gunIndex) {
        ArenaConfig.Gun gun = config.guns().get(gunIndex);
        if (reloadingGun >= 0 || ammo.getOrDefault(gunIndex, gun.magazine()) >= gun.magazine()) {
            return;
        }
        reloadingGun = gunIndex;
        reloadLeft = gun.reloadSeconds();
        host.playSound(SoundBank.RELOAD, 0.7f, 1f);
    }

    /**
     * Fires a gun: every pellet is a ray from the eye, stopped by the level, tested against the
     * other players and the practice targets. The referee hears about the one shot, with the
     * damage of every pellet that hit added up.
     */
    private void fire(int gunIndex, ArenaConfig.Gun gun) {
        Camera camera = host.camera();
        Vector3f eye = new Vector3f(camera.position());
        Vector3f aim = camera.forward(new Vector3f());
        ArenaState state = link.state();
        boolean counts = state != null && state.phase() == ArenaRules.PLAYING;

        Map<Integer, Integer> damageTo = new HashMap<>();
        boolean headshot = false;
        float firstDistance = gun.range();
        Vector3f direction = new Vector3f();
        for (int pellet = 0; pellet < gun.pellets(); pellet++) {
            Ballistics.scatter(aim, gun.spread(), random, direction);
            float distance = Ballistics.worldHit(host.world(), eye, direction, gun.range());

            // The nearest enemy in front of the wall.
            RemotePlayer struck = null;
            if (counts) {
                for (RemotePlayer other : host.others()) {
                    if (!other.isPlaced() || !isEnemy(state, other.id)) {
                        continue;
                    }
                    float t = Ballistics.rayCylinder(eye, direction, other.eye.x, other.eye.z, BODY_RADIUS,
                            other.eye.y - host.eyeHeight(), other.eye.y + HEAD_ABOVE_EYES);
                    if (t < distance) {
                        distance = t;
                        struck = other;
                    }
                }
            }
            // A practice target in front of that?
            WorldObject target = null;
            for (WorldObject object : host.world().objects()) {
                if (!object.entity || object.removedByScript) {
                    continue;
                }
                Vector3f min = new Vector3f(object.worldMin).add(object.scriptOffset);
                Vector3f max = new Vector3f(object.worldMax).add(object.scriptOffset);
                float t = Ballistics.rayBox(eye, direction, min, max);
                if (t < distance) {
                    distance = t;
                    target = object;
                    struck = null;
                }
            }

            if (struck != null) {
                float hitY = eye.y + direction.y * distance;
                boolean head = hitY >= struck.eye.y - HEAD_BELOW_EYES;
                headshot |= head;
                int damage = Math.round(gun.damage() * (head ? gun.headshot() : 1f));
                damageTo.merge(struck.id, damage, Integer::sum);
            } else if (target != null) {
                host.knockEntity(target, new Vector3f(direction));
                hitmarker = 0.2f;
                hitmarkerKill = false;
            }
            if (pellet == 0) {
                firstDistance = distance;
            }
            tracers.add(new Tracer(muzzle(camera), new Vector3f(direction).mul(distance).add(eye),
                    new float[]{0f}, true));
        }

        // One shot for the referee: whoever took the most of it.
        int target = 0;
        int damage = 0;
        for (Map.Entry<Integer, Integer> entry : damageTo.entrySet()) {
            if (entry.getValue() > damage) {
                target = entry.getKey();
                damage = entry.getValue();
            }
        }
        if (target != 0) {
            hitmarker = 0.2f;
            hitmarkerKill = false;
            host.playSound(SoundBank.HITMARKER, headshot ? 0.9f : 0.6f, headshot ? 1.35f : 1f);
        }
        if (counts) {
            link.shoot(new Shoot(gunIndex, target, damage, eye.x, eye.y, eye.z,
                    aim.x, aim.y, aim.z, firstDistance));
        }
        host.playSound(SoundBank.GUNSHOT, 0.85f, gun.pitch() * (0.96f + random.nextFloat() * 0.08f));
        // Recoil: the view kicks up, and a little to one side.
        float kick = (float) Math.toRadians(gun.recoil());
        camera.rotate((random.nextFloat() - 0.5f) * kick * 0.4f, -kick);
        recoilDebt += kick;
        sinceShot = 0f;
    }

    /**
     * Once the shooting stops, the view drifts back down by most of what recoil pushed it up -
     * so a burst ends roughly where it started instead of pointing at the ceiling.
     */
    private void settleRecoil(float dt) {
        sinceShot += dt;
        if (recoilDebt <= 0f || sinceShot < 0.12f) {
            return;
        }
        float step = Math.min(recoilDebt, (float) Math.toRadians(30f) * dt);
        recoilDebt -= step;
        host.camera().rotate(0f, step * 0.85f); // not all of it: the player still has to aim
    }

    /** Where a shot leaves the gun, as drawn: low and to the right of the eye. */
    private static Vector3f muzzle(Camera camera) {
        float yaw = camera.yaw();
        Vector3f forward = camera.forward(new Vector3f());
        return new Vector3f(camera.position())
                .add((float) Math.cos(yaw) * 0.21f, -0.14f, (float) Math.sin(yaw) * 0.21f)
                .add(forward.mul(0.75f));
    }

    /** Shooting counts in a match; in the lobby it only knocks over the practice targets. */
    private boolean canShoot() {
        ArenaState state = link.state();
        if (state == null) {
            return true; // not heard from the referee yet: the range still works
        }
        if (state.phase() == ArenaRules.RESULTS) {
            return false;
        }
        ArenaPlayer me = me(state);
        return state.phase() != ArenaRules.PLAYING || (me != null && me.alive());
    }

    /** Tells everyone else which gun is out, so they can draw it. */
    private void syncGun() {
        int gun = heldGun();
        if (gun != lastSentGun) {
            lastSentGun = gun;
            link.action(ArenaAction.GUN, gun);
        }
    }

    private void refillAll() {
        for (int i = 0; i < config.guns().size(); i++) {
            ammo.put(i, config.guns().get(i).magazine());
        }
        reloadingGun = -1;
    }

    /** Puts the loadout in the player's hands and bag, first gun in the hand. */
    void applyLoadout() {
        HoldSystem holding = host.holding();
        holding.dropAll();
        for (int gun : loadout) {
            if (gunObjects[gun] != null) {
                holding.pickUp(gunObjects[gun]);
            }
        }
        holding.inventory().select(0);
    }

    private void tickEffects(float dt) {
        tracers.removeIf(tracer -> (tracer.age()[0] += dt) > TRACER_SECONDS);
        feed.removeIf(line -> (line.age()[0] += dt) > FEED_SECONDS);
        hitmarker = Math.max(0f, hitmarker - dt);
        damageFlash = Math.max(0f, damageFlash - dt * 1.6f);
    }

    // --- The lobby's choices ------------------------------------------------------------

    void joinTeam(int team) {
        link.action(ArenaAction.JOIN_TEAM, team);
    }

    void vote(int map) {
        link.action(ArenaAction.VOTE, map);
    }

    void setReady(boolean ready) {
        link.action(ArenaAction.READY, ready ? 1 : 0);
    }

    /** Adds a gun to the loadout, or takes it out if it is already in. */
    void toggleLoadout(int gun) {
        if (loadout.remove((Integer) gun)) {
            applyLoadout();
            return;
        }
        if (gunObjects[gun] == null || loadout.size() >= loadoutSize()) {
            return;
        }
        loadout.add(gun);
        applyLoadout();
    }

    /** How many guns the player can take: their bag's size. */
    int loadoutSize() {
        return host.holding().inventory().size();
    }

    boolean hasBag() {
        return host.holding().hasBag();
    }

    List<Integer> loadout() {
        return loadout;
    }

    boolean gunAvailable(int gun) {
        return gunObjects[gun] != null;
    }

    void setLoadoutOpen(boolean open) {
        loadoutOpen = open;
    }

    boolean loadoutOpen() {
        return loadoutOpen;
    }

    void closePanel() {
        panelOpen = false;
        loadoutOpen = false;
    }

    private boolean canOpenPanel() {
        ArenaState state = link.state();
        return state == null || state.phase() == ArenaRules.LOBBY || state.phase() == ArenaRules.COUNTDOWN;
    }

    // --- For the game around it ---------------------------------------------------------

    /** True while the lobby menu is up and wants the mouse. */
    public boolean panelOpen() {
        ArenaState state = link.state();
        if (panelOpen && state != null && !canOpenPanel()) {
            panelOpen = false;
        }
        return panelOpen;
    }

    /** True when the player should not move: dead, or with the lobby menu up. */
    public boolean blocksMovement() {
        ArenaState state = link.state();
        if (panelOpen()) {
            return true;
        }
        if (state == null || state.phase() != ArenaRules.PLAYING) {
            return false;
        }
        ArenaPlayer me = me(state);
        return me != null && !me.alive();
    }

    /** A dead player is not drawn until they come back. */
    public boolean isHidden(int playerId) {
        ArenaState state = link.state();
        if (state == null || state.phase() != ArenaRules.PLAYING) {
            return false;
        }
        ArenaPlayer player = player(state, playerId);
        return player != null && !player.alive();
    }

    /** RED, BLUE or NO_TEAM, for colouring a name tag. */
    public int teamOf(int playerId) {
        return teamOf(link.state(), playerId);
    }

    /** The gun another player has out, to draw in their hand; null for none. */
    public WorldObject gunOf(int playerId) {
        ArenaPlayer player = player(link.state(), playerId);
        if (player == null || player.gun() < 0 || player.gun() >= gunObjects.length) {
            return null;
        }
        return gunObjects[player.gun()];
    }

    /**
     * Where to draw the player's own gun, or null when what they hold is not a gun (so it is drawn
     * the ordinary way).
     *
     * <p>In first person it sits low and to the right of the view, pointing where they look - the
     * way every shooter draws it, so it is always in sight whatever the body is doing. Seen from
     * outside it is in their hand, still pointing along the aim.
     *
     * @param hand the hand bone this frame, or null when there is no rigged body
     */
    public Matrix4f ownGunTransform(WorldObject item, Matrix4f hand, boolean fromOutside, Matrix4f out) {
        if (item == null || config.gunIndexForObject(item.name) < 0) {
            return null;
        }
        Camera camera = host.camera();
        float kick = cooldown > 0f ? Math.min(1f, cooldown * 8f) * 0.04f : 0f;
        if (!fromOutside) {
            float yaw = camera.yaw();
            Vector3f forward = camera.forward(new Vector3f());
            // Far enough out that the back of the gun - a rifle's stock - stays clear of the eye.
            float behindGrip = item.asset.max().z - (item.grip == null ? 0f : item.grip.z);
            float reach = Math.max(0.52f, 0.3f + behindGrip);
            Vector3f at = new Vector3f(camera.position())
                    .add((float) Math.cos(yaw) * 0.21f, -0.24f, (float) Math.sin(yaw) * 0.21f)
                    .add(forward.mul(reach - kick));
            return host.holding().aimedTransform(item, at, camera.yaw(), camera.pitch(), out);
        }
        Vector3f at = hand != null ? hand.getTranslation(new Vector3f()) : new Vector3f(camera.position());
        return host.holding().aimedTransform(item, at, camera.yaw(), camera.pitch(), out);
    }

    /** Where to draw another player's gun: in their hand, along their aim. */
    public Matrix4f otherGunTransform(WorldObject gun, RemotePlayer other, Matrix4f hand, Matrix4f out) {
        Vector3f at = hand != null ? hand.getTranslation(new Vector3f())
                : new Vector3f(other.eye).add((float) Math.cos(other.yaw) * 0.25f, -0.45f,
                        (float) Math.sin(other.yaw) * 0.25f);
        return host.holding().aimedTransform(gun, at, other.yaw, other.pitch, out);
    }

    /** Every shot still streaking through the air, to draw. */
    public List<Tracer> tracers() {
        return tracers;
    }

    public float tracerSeconds() {
        return TRACER_SECONDS;
    }

    // --- For the screens --------------------------------------------------------------

    ArenaConfig config() {
        return config;
    }

    ArenaState state() {
        return link.state();
    }

    ArenaLink link() {
        return link;
    }

    ArenaPlayer me(ArenaState state) {
        return player(state, link.myId());
    }

    String killedBy() {
        return killedBy;
    }

    /** The gun in the player's hand, as a gun index; -1 for none. */
    int heldGun() {
        WorldObject held = host.holding().held();
        return held == null ? -1 : config.gunIndexForObject(held.name);
    }

    int ammoIn(int gun) {
        return ammo.getOrDefault(gun, config.guns().get(gun).magazine());
    }

    boolean reloading() {
        return reloadingGun >= 0;
    }

    /** How far through the reload, 0 to 1. */
    float reloadProgress() {
        if (reloadingGun < 0) {
            return 0f;
        }
        float total = Math.max(0.01f, config.guns().get(reloadingGun).reloadSeconds());
        return 1f - Math.max(0f, reloadLeft) / total;
    }

    String mapName(int map) {
        return map >= 0 && map < config.maps().size() ? config.maps().get(map).name() : "?";
    }

    boolean isPractice() {
        return link.isPractice();
    }

    /** Dev aid: turns the view onto the chest of the nearest living enemy, if one is placed. */
    private void autoAim() {
        Camera camera = host.camera();
        Vector3f eye = camera.position();
        RemotePlayer nearest = null;
        for (RemotePlayer other : host.others()) {
            if (other.isPlaced() && isEnemy(link.state(), other.id)
                    && (nearest == null || eye.distanceSquared(other.eye) < eye.distanceSquared(nearest.eye))) {
                nearest = other;
            }
        }
        if (nearest == null) {
            return;
        }
        float dx = nearest.eye.x - eye.x;
        float dy = nearest.eye.y - 0.4f - eye.y;
        float dz = nearest.eye.z - eye.z;
        camera.setYaw((float) Math.atan2(dx, -dz));
        camera.setPitch((float) -Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }

    private boolean isEnemy(ArenaState state, int playerId) {
        ArenaPlayer them = player(state, playerId);
        if (them == null || !them.alive()) {
            return false;
        }
        ArenaPlayer me = me(state);
        return me == null || config.minPlayers() <= 1 || them.team() != me.team();
    }

    private float pitchOf(int gun) {
        return gun >= 0 && gun < config.guns().size() ? config.guns().get(gun).pitch() : 1f;
    }

    static ArenaPlayer player(ArenaState state, int id) {
        if (state == null) {
            return null;
        }
        for (ArenaPlayer player : state.players()) {
            if (player.id() == id) {
                return player;
            }
        }
        return null;
    }

    private static int teamOf(ArenaState state, int id) {
        ArenaPlayer player = player(state, id);
        return player == null ? ArenaRules.NO_TEAM : player.team();
    }

    private static WorldObject find(LoadedWorld world, String name) {
        for (WorldObject object : world.objects()) {
            if (object.name.equalsIgnoreCase(name)) {
                return object;
            }
        }
        return null;
    }
}

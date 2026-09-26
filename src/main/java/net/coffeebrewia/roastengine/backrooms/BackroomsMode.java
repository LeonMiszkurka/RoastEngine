package net.coffeebrewia.roastengine.backrooms;

import net.coffeebrewia.roastengine.audio.AudioEngine;
import net.coffeebrewia.roastengine.audio.SoundBank;
import net.coffeebrewia.roastengine.render.Camera;
import net.coffeebrewia.roastengine.render.ShaderProgram;
import net.coffeebrewia.roastengine.render.anim.AnimatedModel;
import net.coffeebrewia.roastengine.render.anim.AnimatedModelLoader;
import net.coffeebrewia.roastengine.render.anim.AnimationClip;
import net.coffeebrewia.roastengine.render.anim.PoseClipFile;
import net.coffeebrewia.roastengine.video.VideoPlayer;
import net.coffeebrewia.roastengine.world.LoadedWorld;
import net.coffeebrewia.roastengine.world.WorldObject;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The Backrooms: a world that opens on a home screen of its own, plays an intro, and then puts
 * you in a level with something that hunts you.
 *
 * <p>A world becomes one of these by shipping {@code backrooms/level.json} - see
 * {@link BackroomsConfig}. Three things follow from that file:
 *
 * <ul>
 *   <li>the <b>home screen</b>, which is what you see instead of simply spawning: Play, Options
 *       and a way out, drawn by {@link BackroomsScreens} over the level itself;</li>
 *   <li>the <b>intro</b>, {@code video/BK_INTRO.mp4}, played full-screen when Play is pressed and
 *       skippable with any key. A missing or unplayable video is not an error - the level just
 *       starts;</li>
 *   <li>the <b>monster</b>, a {@link Stalker} walking the level's own map.</li>
 * </ul>
 *
 * <p>All three levels sit in the same scene, hundreds of metres apart, so changing level is a
 * teleport. Nothing here is networked: the monster runs on this machine, for one player.
 */
public final class BackroomsMode {

    /** What the Backrooms needs from the game around it. */
    public interface Host {
        Camera camera();

        LoadedWorld world();

        /** Puts the player somewhere, stopped, facing a way. */
        void teleport(Vector3f eye, float yawDegrees);

        void playSound(String name, float volume, float pitch);

        void notice(String text);

        AudioEngine audio();

        /** Back to the game's own main menu - what the home screen's Leave button does. */
        void quitToMainMenu();
    }

    /** Where the player is in the world's own flow. */
    public enum Phase {
        /** The home screen, over a view of the level. */
        MENU,
        /** The intro video, full screen. */
        INTRO,
        /** Down there. */
        PLAYING,
        /** It found you. */
        CAUGHT
    }

    /** How fast you have to move to be making the most noise possible. */
    private static final float LOUD_SPEED = 5.5f;
    /** The home screen's camera drifts this far round, in degrees, and back. */
    private static final float DRIFT_DEGREES = 9f;
    /** How long the caught screen sits there before it lets you do anything. */
    private static final float CAUGHT_PAUSE = 1.2f;
    /** The name the mod's own music is registered under with the audio engine. */
    private static final String MUSIC_NAME = "backrooms_music";

    private final BackroomsConfig config;
    private final BackroomsOptions options;
    private final Host host;
    private final Path modFolder;
    private final Random random = new Random();

    private Phase phase = Phase.MENU;
    private BackroomsConfig.LevelConfig level;
    private Maze maze;
    private Stalker stalker;
    /** Every level's creature model, by the name the level's monster block gives it. */
    private final Map<String, WorldObject> entities = new HashMap<>();
    /** Where the scene put each of them, at its feet - what an offset is measured from. */
    private final Map<String, Vector3f> entityHomes = new HashMap<>();
    /** The rigged version of a creature, where the mod ships one, by the same name. */
    private final Map<String, AnimatedModel> rigged = new HashMap<>();
    private final Matrix4f transform = new Matrix4f();
    private AnimationClip clip;
    private float clipTime;
    /** The attack plays once and holds on its last frame; everything else loops. */
    private boolean clipHolds;
    private final Vector3f lastEntitySpot = new Vector3f();
    /** Dev aid: where {@code pose} stood the creature, so the rig is drawn there too. */
    private Vector3f posedSpot;
    private float posedYaw;

    private VideoPlayer intro;
    private boolean introTried;
    /** The mod's music, once decoded: loaded on the first screen that wants it, then kept. */
    private boolean musicLoaded;
    private boolean musicMissing;

    /**
     * Dev aid: -PbackroomsAuto=level2,options,play,skip,hunt,pose drives the home screen without
     * hands - picks a level, opens Options, presses Play, skips the intro, and with {@code hunt}
     * starts the monster in front of you instead of across the level. {@code pose} goes further
     * and stands it still a few paces away, facing you, so a screenshot of the creature itself
     * is the same every time.
     */
    private final List<String> auto = List.of(System.getProperty("roastengine.backroomsAuto", "")
            .toLowerCase().split(","));
    private boolean autoDone;

    private float menuTime;
    private float caughtFor;
    private Stalker.Mood lastMood = Stalker.Mood.WANDER;
    private final Vector3f lastEye = new Vector3f();
    private boolean hasLastEye;
    private float noise;

    private BackroomsMode(BackroomsConfig config, BackroomsOptions options, Host host, Path modFolder) {
        this.config = config;
        this.options = options;
        this.host = host;
        this.modFolder = modFolder;
        this.level = config.levelOr(options.levelId, config.levels().get(0));
        options.levelId = level.id();
        this.maze = level.maze();
        // One creature per level, each its own model sitting in its own part of the scene.
        for (BackroomsConfig.LevelConfig each : config.levels()) {
            String wanted = each.monster().object();
            if (wanted.isBlank() || entities.containsKey(wanted)) {
                continue;
            }
            WorldObject found = host.world().objects().stream()
                    .filter(object -> object.name.equalsIgnoreCase(wanted))
                    .findFirst().orElse(null);
            if (found == null) {
                System.err.println("[Backrooms] " + each.id() + " wants an object called '"
                        + wanted + "', which this world has not got - nothing will hunt you there");
                continue;
            }
            entities.put(wanted, found);
            loadRig(each.monster());
            // Where the scene put it: the middle of its box, brought down to its feet, since
            // the model stands on its own origin.
            Vector3f home = found.center(new Vector3f());
            home.y = found.worldMin.y;
            entityHomes.put(wanted, home);
        }
        showMenu();
        StringBuilder creatures = new StringBuilder();
        for (BackroomsConfig.LevelConfig each : config.levels()) {
            creatures.append(creatures.isEmpty() ? "" : ", ").append(each.id()).append(": ")
                    .append(each.monster().object().isBlank() ? "nothing" : each.monster().name());
        }
        System.out.println("[Backrooms] " + config.name() + ": " + config.levels().size()
                + " level(s) - " + creatures);
    }

    /**
     * Loads a creature's rigged .glb, if it ships one. Without it the static model in the scene
     * is used as it stands, which is what a mod that has not been near Blender gets.
     */
    private void loadRig(BackroomsConfig.Monster monster) {
        if (monster.model().isBlank() || modFolder == null || rigged.containsKey(monster.object())) {
            return;
        }
        Path file = modFolder.resolve(monster.model());
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            AnimatedModel model = AnimatedModelLoader.load(file);
            // Clips written in the Creator's animator sit beside the model and go on over the
            // top of its own, so an animation can be retouched without returning to Blender.
            int authored = PoseClipFile.applyTo(model, PoseClipFile.loadBesideModel(file));
            rigged.put(monster.object(), model);
            System.out.println("[Backrooms] " + monster.name() + ": " + file.getFileName()
                    + " " + model.clipNames()
                    + (authored > 0 ? " (" + authored + " from the animator)" : ""));
        } catch (IOException | RuntimeException e) {
            System.err.println("[Backrooms] Could not load " + file.getFileName() + ": "
                    + e.getMessage() + " - falling back to the static model");
        }
    }

    /**
     * Starts the Backrooms for a world, or returns null when this world is not one.
     *
     * @param modFolders the folders of the mods that make up this world, for the video and the
     *                   level file
     * @param configDir  where the player's own options are kept
     */
    public static BackroomsMode start(LoadedWorld world, List<Path> modFolders, Path configDir, Host host) {
        BackroomsConfig config = BackroomsConfig.find(modFolders);
        if (config == null) {
            return null;
        }
        Path folder = modFolders.stream()
                .filter(mod -> Files.isRegularFile(mod.resolve(BackroomsConfig.FILE)))
                .findFirst().orElse(modFolders.isEmpty() ? null : modFolders.get(0));
        BackroomsOptions options = new BackroomsOptions(
                configDir == null ? null : configDir.resolve("backrooms.properties"));
        return new BackroomsMode(config, options, host, folder);
    }

    // ------------------------------------------------------------------
    // What the screens ask about
    // ------------------------------------------------------------------

    public BackroomsConfig config() {
        return config;
    }

    public BackroomsOptions options() {
        return options;
    }

    public Phase phase() {
        return phase;
    }

    public BackroomsConfig.LevelConfig level() {
        return level;
    }

    /** The creature that walks the level currently picked. */
    public BackroomsConfig.Monster monster() {
        return level.monster();
    }

    /**
     * False for a level that names no creature - the Poolrooms, where the whole point is that
     * there is nothing in there with you.
     */
    public boolean huntedHere() {
        return !level.monster().object().isBlank();
    }

    public Stalker.Mood mood() {
        return stalker == null ? Stalker.Mood.WANDER : stalker.mood();
    }

    /** True while the world's own screens have the mouse and the player should not move. */
    public boolean blocksGameplay() {
        return phase != Phase.PLAYING;
    }

    /** True while the intro is on screen, when nothing else should be drawn at all. */
    public boolean showingVideo() {
        return phase == Phase.INTRO && intro != null;
    }

    public VideoPlayer video() {
        return intro;
    }

    /** True once the caught screen will take a click. */
    public boolean caughtScreenReady() {
        return phase == Phase.CAUGHT && caughtFor >= CAUGHT_PAUSE;
    }

    /** How near the monster is, 0 (far) to 1 (on top of you) - what the screen edges redden with. */
    public float dread() {
        if (phase != Phase.PLAYING || stalker == null || !options.monsters) {
            return 0f;
        }
        float distance = stalker.position().distance(
                host.camera().position().x, stalker.position().y, host.camera().position().z);
        float near = Math.max(0f, 1f - distance / 18f);
        return stalker.mood() == Stalker.Mood.HUNT ? Math.min(1f, near + 0.25f) : near * 0.5f;
    }

    // ------------------------------------------------------------------
    // What the screens do
    // ------------------------------------------------------------------

    /** Pressing Play: the intro if there is one, straight into the level if not. */
    public void play() {
        if (!introTried) {
            introTried = true;
            intro = VideoPlayer.open(introFile(), host.audio());
        }
        if (intro != null && !intro.isFinished()) {
            phase = Phase.INTRO;
            host.audio().stopMusic();
            return;
        }
        enterLevel();
    }

    /** Skipping the intro, or reaching its end. */
    public void endIntro() {
        if (intro != null) {
            intro.close();
            intro = null;
        }
        enterLevel();
    }

    /** Picks a level. Takes effect the next time you go down. */
    public void chooseLevel(BackroomsConfig.LevelConfig chosen) {
        level = chosen;
        maze = chosen.maze();
        options.levelId = chosen.id();
        options.save();
        if (phase == Phase.MENU) {
            showMenu();   // the home screen looks into whichever level is picked
        }
    }

    /** Back to the home screen, from the caught screen or from the pause menu. */
    public void showMenu() {
        phase = Phase.MENU;
        caughtFor = 0f;
        hasLastEye = false;
        stalker = null;
        hideEntity();
        host.teleport(level.menuEye(), level.menuYawDegrees());
        if (!playModMusic()) {
            host.audio().playMusic(SoundBank.HUM);
        }
    }

    public void leave() {
        options.save();
        host.quitToMainMenu();
    }

    /** Another go at the same level, straight away. */
    public void retry() {
        enterLevel();
    }

    // ------------------------------------------------------------------
    // Playing
    // ------------------------------------------------------------------

    private void enterLevel() {
        phase = Phase.PLAYING;
        caughtFor = 0f;
        hasLastEye = false;
        noise = 0f;
        host.teleport(level.spawn(), level.spawnYawDegrees());
        if (!playModMusic()) {
            host.audio().playMusic(SoundBank.HUM);
        }
        hideEntity();   // whatever walked the last level stays out of this one
        if (options.monsters && huntedHere()) {
            Vector3f start = auto.contains("hunt") ? nearSpawn() : level.monsterSpawn();
            stalker = new Stalker(maze, level.monster(), start, random);
            stalker.setDifficulty(options.difficulty);
            lastMood = Stalker.Mood.WANDER;
        } else {
            stalker = null;
            hideEntity();
        }
        host.notice(level.name());
    }

    /**
     * Dev aid: a square a few steps in front of the player, so the hunt starts at once and the
     * monster is in shot - "in front" being wherever the level spawns them looking.
     */
    private Vector3f nearSpawn() {
        Maze.Square from = maze.nearestOpen(level.spawn().x, level.spawn().z);
        Vector3f spawn = level.spawn();
        float yaw = (float) Math.toRadians(level.spawnYawDegrees());
        float forwardX = (float) Math.sin(yaw);
        float forwardZ = -(float) Math.cos(yaw);
        Vector3f at = new Vector3f();
        Vector3f best = null;
        float bestScore = -Float.MAX_VALUE;
        for (Maze.Square square : maze.openSquares()) {
            int steps = maze.walkingDistance(from, square);
            if (steps < 2 || steps > 4) {
                continue;
            }
            maze.centreOf(square, at);
            float awayX = at.x - spawn.x;
            float awayZ = at.z - spawn.z;
            float distance = (float) Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (distance < 0.01f || !maze.canSee(spawn.x, spawn.z, at.x, at.z)) {
                continue;   // on top of them, or round a corner
            }
            // How squarely ahead it is: 1 is dead centre of the view, below 0 is behind them.
            float ahead = (awayX * forwardX + awayZ * forwardZ) / distance;
            if (ahead > bestScore) {
                bestScore = ahead;
                best = new Vector3f(at);
            }
        }
        return best != null && bestScore > 0.3f ? best : level.monsterSpawn();
    }

    /**
     * A step of the level: how loud the player is, what the monster makes of that, and whether
     * it has reached them.
     *
     * @param inputAllowed false while a menu or the chat has the keyboard
     */
    public void update(float deltaSeconds, boolean inputAllowed) {
        switch (phase) {
            case MENU -> {
                menuTime += deltaSeconds;
                driftMenuCamera();
                runAutoSteps();
            }
            case INTRO -> {
                if (intro == null) {
                    enterLevel();
                } else {
                    intro.update(deltaSeconds);
                    if (intro.isFinished()) {
                        endIntro();
                    }
                }
            }
            case PLAYING -> updateHunt(deltaSeconds);
            case CAUGHT -> {
                caughtFor += deltaSeconds;
                advanceClip(deltaSeconds);      // the lunge finishes, then holds
            }
        }
    }

    /** True when the dev flag asked for the Options window; the screens open it once. */
    public boolean wantsOptionsOpen() {
        return auto.contains("options");
    }

    /** Carries out the -PbackroomsAuto steps, once, as soon as the home screen is up. */
    private void runAutoSteps() {
        if (autoDone) {
            return;
        }
        autoDone = true;
        for (String step : auto) {
            for (BackroomsConfig.LevelConfig candidate : config.levels()) {
                if (candidate.id().equals(step)) {
                    chooseLevel(candidate);
                }
            }
        }
        if (auto.contains("nomonsters")) {
            options.monsters = false;
        }
        if (auto.contains("play")) {
            play();
            if (auto.contains("skip")) {
                endIntro();
            }
        }
    }

    private void updateHunt(float deltaSeconds) {
        if (auto.contains("pose")) {
            poseEntity();
            return;
        }
        Camera camera = host.camera();
        Vector3f eye = camera.position();
        measureNoise(deltaSeconds, eye);
        if (stalker == null) {
            return;
        }
        stalker.setDifficulty(options.difficulty);
        stalker.update(deltaSeconds, eye, camera.yaw(), noise);
        placeEntity();

        if (stalker.mood() == Stalker.Mood.HUNT && lastMood != Stalker.Mood.HUNT) {
            host.playSound(SoundBank.DREAD, 0.9f, 1f);
        }
        lastMood = stalker.mood();

        followAnimation(deltaSeconds);
        if (stalker.hasCaughtYou()) {
            phase = Phase.CAUGHT;
            playClip("attack", true);
            caughtFor = 0f;
            host.playSound(SoundBank.CAUGHT, 1f, 1f);
            host.audio().stopMusic();
        }
    }

    /**
     * How much noise the player is making, from how fast they are actually moving. Standing
     * still is silent, walking carries about half as far as running.
     */
    private void measureNoise(float deltaSeconds, Vector3f eye) {
        if (!hasLastEye) {
            lastEye.set(eye);
            hasLastEye = true;
            return;
        }
        float moved = (float) Math.sqrt((eye.x - lastEye.x) * (eye.x - lastEye.x)
                + (eye.z - lastEye.z) * (eye.z - lastEye.z));
        lastEye.set(eye);
        float speed = deltaSeconds > 0 ? moved / deltaSeconds : 0f;
        float wanted = Math.min(1f, speed / LOUD_SPEED);
        // Noise fades rather than cutting out, so stopping dead does not make you instantly
        // invisible to something that heard you a moment ago.
        noise = wanted > noise ? wanted : Math.max(wanted, noise - deltaSeconds * 1.5f);
    }

    /** The home screen's camera turns slowly, so the level behind the menu is not a photograph. */
    private void driftMenuCamera() {
        float drift = (float) Math.sin(menuTime * 0.15f) * (float) Math.toRadians(DRIFT_DEGREES);
        host.camera().setYaw((float) Math.toRadians(level.menuYawDegrees()) + drift);
        host.camera().setPitch((float) Math.toRadians(2f + Math.sin(menuTime * 0.11f) * 1.5f));
    }

    /** The rigged model for the level being played, or null when there is none. */
    private AnimatedModel rig() {
        return rigged.get(level.monster().object());
    }

    /** Picks the clip that matches what the creature is doing, and runs the clock on. */
    private void followAnimation(float deltaSeconds) {
        AnimatedModel model = rig();
        if (model == null || stalker == null) {
            return;
        }
        float moved = stalker.position().distance(lastEntitySpot);
        lastEntitySpot.set(stalker.position());
        boolean walking = deltaSeconds > 0 && moved / deltaSeconds > 0.2f;
        String wanted = !walking ? "idle"
                : stalker.mood() == Stalker.Mood.HUNT ? "chase" : "walk";
        playClip(wanted, false);
        advanceClip(deltaSeconds);
    }

    /**
     * Switches clip, if it is not already the one playing.
     *
     * <p>A clip the file does not have falls back to the first one in it, so a rig exported with
     * a single animation still moves rather than standing frozen.
     */
    private void playClip(String name, boolean holdOnLastFrame) {
        AnimatedModel model = rig();
        if (model == null) {
            return;
        }
        AnimationClip wanted = model.clip(name);
        if (wanted == null && !model.clipNames().isEmpty()) {
            wanted = model.clip(model.clipNames().get(0));
        }
        if (wanted == clip) {
            return;
        }
        clip = wanted;
        clipTime = 0f;
        clipHolds = holdOnLastFrame;
    }

    private void advanceClip(float deltaSeconds) {
        if (clip == null) {
            return;
        }
        clipTime += deltaSeconds;
        if (clipHolds) {
            // Sampling wraps on the clip's length, so stop just short of the end to hold there.
            clipTime = Math.min(clipTime, Math.max(0f, clip.durationSeconds() - 0.001f));
        }
    }

    /**
     * Draws this level's creature, when it is a rigged one. Call inside the skinned shader's
     * pass; the static models are drawn with the rest of the world as usual.
     */
    public void renderAnimated(ShaderProgram shader) {
        AnimatedModel model = rig();
        if (model == null || !options.monsters
                || (phase != Phase.PLAYING && phase != Phase.CAUGHT)) {
            return;
        }
        Vector3f at = posedSpot != null ? posedSpot : stalker == null ? null : stalker.position();
        if (at == null) {
            return;
        }
        float heading = posedSpot != null ? posedYaw : stalker.yaw();
        model.pose(clip, clipTime);
        shader.setUniform("uModel", transform
                .translation(at.x, 0f, at.z)
                .rotateY(modelYaw(heading))
                .translate(0f, -model.min().y, 0f));
        model.draw(shader);
    }

    /**
     * Dev aid: stands this level's creature a few paces in front of the player, facing them,
     * and leaves it there. Nothing hunts, nothing moves - it is a photograph of the model.
     */
    private void poseEntity() {
        WorldObject entity = entities.get(level.monster().object());
        Vector3f home = entityHomes.get(level.monster().object());
        if (rig() == null && (entity == null || home == null)) {
            return;
        }
        float yaw = (float) Math.toRadians(level.spawnYawDegrees());
        Vector3f spot = new Vector3f(level.spawn()).add(
                (float) Math.sin(yaw) * 3.2f, 0f, -(float) Math.cos(yaw) * 3.2f);
        spot.y = 0f;
        if (rig() != null) {
            // A rigged creature is drawn from here instead, holding whichever clip is asked for
            // with -PbackroomsClip=<name> (idle unless told otherwise).
            posedSpot = spot;
            posedYaw = yaw + (float) Math.PI;
            playClip(System.getProperty("roastengine.backroomsClip", "idle"), true);
            clipTime = Float.parseFloat(System.getProperty("roastengine.backroomsClipTime", "0.5"));
            return;
        }
        entity.removedByScript = false;
        entity.scriptOffset.set(spot.x - home.x, 0f, spot.z - home.z);
        entity.scriptYaw = modelYaw(yaw + (float) Math.PI);   // turned to face the player
    }

    /**
     * Turns a heading into the rotation to draw a model at.
     *
     * <p>They are not the same number. A camera yaw of Y looks along (sin Y, -cos Y), but
     * rotating a model built facing -Z by Y points it along (-sin Y, -cos Y) - mirrored in X.
     * Left unconverted, a creature walks backwards: the Hound leads with its tail and the
     * Smiler wears its grin on the back of its head.
     */
    private static float modelYaw(float heading) {
        return -heading;
    }

    /** Moves this level's creature onto the monster, and keeps the others out of sight. */
    private void placeEntity() {
        if (stalker == null) {
            return;
        }
        if (rigged.containsKey(level.monster().object())) {
            return;     // drawn as an animated model instead, which needs no scene object
        }
        WorldObject entity = entities.get(level.monster().object());
        Vector3f home = entityHomes.get(level.monster().object());
        if (entity == null || home == null) {
            return;
        }
        entity.removedByScript = false;
        entity.scriptOffset.set(stalker.position().x - home.x, 0f, stalker.position().z - home.z);
        entity.scriptYaw = modelYaw(stalker.yaw());
    }

    /**
     * Puts every creature away: for the home screen, for monsters-off, and so the two that
     * belong to other levels are never standing about in the one you are in.
     */
    private void hideEntity() {
        for (WorldObject each : entities.values()) {
            each.removedByScript = true;
        }
    }

    // ------------------------------------------------------------------
    // Files the mod ships
    // ------------------------------------------------------------------

    /** The intro video, whether or not it is there yet. */
    public Path introFile() {
        String intro = config.menu().intro();
        return intro.isBlank() || modFolder == null ? null : modFolder.resolve(intro);
    }

    /** True when the mod actually ships the intro - what the home screen mentions if it does not. */
    public boolean hasIntroFile() {
        Path file = introFile();
        return file != null && Files.isRegularFile(file);
    }

    /**
     * Plays the mod's own music if it ships any; false when there is none to play.
     *
     * <p>Decoding a few minutes of music takes a moment and tens of megabytes, so it is done
     * once and the buffer is kept - this is called again every time you go down or come back up.
     */
    private boolean playModMusic() {
        String music = config.menu().music();
        if (music.isBlank() || modFolder == null || musicMissing) {
            return false;
        }
        if (!musicLoaded) {
            Path file = modFolder.resolve(music);
            if (!Files.isRegularFile(file) || !host.audio().overrideFromFile(MUSIC_NAME, file)) {
                musicMissing = true;    // no point trying again on every screen
                return false;
            }
            musicLoaded = true;
        }
        host.audio().playMusic(MUSIC_NAME);
        return true;
    }

    /** Lets go of the video when the world is unloaded. */
    public void dispose() {
        if (intro != null) {
            intro.close();
            intro = null;
        }
        rigged.values().forEach(AnimatedModel::dispose);
        rigged.clear();
        options.save();
    }
}

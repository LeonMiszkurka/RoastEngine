package net.coffeebrewia.roastengine.states;

import net.coffeebrewia.roastengine.core.Engine;
import net.coffeebrewia.roastengine.core.GameState;
import net.coffeebrewia.roastengine.audio.AudioEngine;
import net.coffeebrewia.roastengine.audio.SoundBank;
import net.coffeebrewia.roastengine.input.Input;
import net.coffeebrewia.roastengine.input.InputActions;
import net.coffeebrewia.roastengine.input.InputAxis;
import net.coffeebrewia.roastengine.input.InputButton;
import net.coffeebrewia.roastengine.modding.LocalMod;
import net.coffeebrewia.roastengine.multiplayer.ChatBox;
import net.coffeebrewia.roastengine.multiplayer.MultiplayerSession;
import net.coffeebrewia.roastengine.multiplayer.RemotePlayer;
import net.coffeebrewia.roastengine.render.Camera;
import net.coffeebrewia.roastengine.render.Color;
import net.coffeebrewia.roastengine.render.Mesh;
import net.coffeebrewia.roastengine.render.PlayerModel;
import net.coffeebrewia.roastengine.render.Primitives;
import net.coffeebrewia.roastengine.render.Renderer2D;
import net.coffeebrewia.roastengine.render.ShaderProgram;
import net.coffeebrewia.roastengine.render.post.PostProcessor;
import net.coffeebrewia.roastengine.render.post.ShaderPack;
import net.coffeebrewia.roastengine.ui.PauseMenu;
import net.coffeebrewia.roastengine.ui.Theme;
import net.coffeebrewia.roastengine.world.Interactions;
import net.coffeebrewia.roastengine.world.LoadedWorld;
import net.coffeebrewia.roastengine.world.NpcSystem;
import net.coffeebrewia.roastengine.world.WorldLoader;
import net.coffeebrewia.roastengine.world.WorldObject;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11C.*;

/**
 * State 3 - The 3D sandbox.
 *
 * <p>Loads every installed world mod (any mod folder with a {@code scene.json}) and plays it:
 * objects are rendered, solid ones block movement, and ones flagged "Kills You" respawn the
 * player on touch. With no world mods installed it falls back to a demo scene.
 *
 * <p>Controls: mouse to look, WASD to move, Space/Shift up/down, Ctrl sprint, Esc releases
 * the mouse (Esc again returns to the menu), F toggles fly mode.
 *
 * <p>Online, the same sandbox also shows the other players on the server, with name tags, a chat
 * (T) and a player list (hold Tab). The world itself stays local to each player.
 */
public final class SandboxState implements GameState {

    /**
     * The player's eye height in metres. Everything else - body size, collision box, walking
     * speed, jump, step height - is derived from it, so a map modelled at a different scale can
     * be matched with {@code ./gradlew run -PplayerHeight=0.6} instead of rescaling the map.
     */
    private static final float EYE_HEIGHT =
            Float.parseFloat(System.getProperty("roastengine.playerHeight", "1.7"));
    /** How much bigger or smaller the player is than the 1.7m default. */
    private static final float SCALE = EYE_HEIGHT / 1.7f;
    private static final float PLAYER_HEIGHT = 1.8f * SCALE;
    private static final float PLAYER_RADIUS = 0.35f * SCALE;
    private static final float MOUSE_SENSITIVITY = 0.0022f;
    private static final float WALK_SPEED = 4.5f * SCALE;
    private static final float SPRINT_MULTIPLIER = 1.8f;
    private static final float PLATFORM_SIZE = 200f;
    /** Gravity and jump are tuned for a roughly 1.1m jump. */
    private static final float GRAVITY = -22f * SCALE;
    private static final float JUMP_VELOCITY = 7f * SCALE;
    /** How quickly the player reaches walking speed on normal ground (per second). */
    private static final float GROUND_ACCELERATION = 18f;
    /** ...and on a surface flagged "Is Slippery": slow to speed up, slow to stop.  */
    private static final float ICE_ACCELERATION = 3.2f;
    private static final float ICE_FRICTION = 1.5f;
    private static final float AIR_ACCELERATION = 3.5f;
    /** Ledges up to this height are climbed automatically instead of blocking. */
    private static final float STEP_HEIGHT = 0.55f * SCALE;
    private static final float GROUND_LEVEL = 0f;
    /** Tiny gap left after landing so resting on a surface never reads as intersecting it. */
    private static final float LANDING_GAP = 0.005f;
    /** How far below the feet to look for the surface the player is standing on. */
    private static final float GROUND_PROBE = 0.08f;
    /** Below this speed the player counts as standing still, for animation purposes. */
    private static final float WALK_ANIMATION_THRESHOLD = 0.6f;
    private static final float PASS_OUT_FADE = 2.5f;
    private static final float WAKE_FADE = 1.5f;
    /** Radians of walk cycle per metre travelled (one full cycle = two steps). */
    private static final float STEPS_PER_METRE = 5f;
    private static final float FALL_RESPAWN_Y = -25f;
    private static final Vector3f SPAWN = new Vector3f(0f, EYE_HEIGHT, 10f);
    /** Name tags further away than this are hidden, so a crowd does not become a wall of text. */
    private static final float NAME_TAG_RANGE = 40f;

    private record Prop(Mesh mesh, Matrix4f model) {
    }

    private final Engine engine;
    private final GameState returnState;
    /** The mods whose scenes are played; null for every enabled world mod. */
    private final List<LocalMod> sources;
    private final Camera camera = new Camera(SPAWN.x, SPAWN.y, SPAWN.z);
    private final Matrix4f identity = new Matrix4f();
    private final List<Prop> demoProps = new ArrayList<>();
    private final List<Mesh> demoMeshes = new ArrayList<>();
    private final Vector3f skyColor = new Vector3f(LoadedWorld.DEFAULT_SKY);
    /** Where respawns put the player; a world mod can override it via "spawn" in scene.json. */
    private final Vector3f spawn = new Vector3f(SPAWN);
    private final Vector3f playerMin = new Vector3f();
    private final Vector3f playerMax = new Vector3f();

    private ShaderProgram shader;
    /** Same lighting and fog as the world shader, with skinning in the vertex stage. */
    private ShaderProgram skinnedShader;
    private Mesh ground;
    private LoadedWorld world;
    private final Interactions interactions = new Interactions();
    private final NpcSystem npcs = new NpcSystem();
    private PauseMenu pauseMenu;
    private PostProcessor post;
    /** Folder of the shader pack currently loaded, to notice when the player swaps packs. */
    private java.nio.file.Path loadedPack;
    /** A pack that failed to compile, so it is not retried every frame. */
    private java.nio.file.Path brokenPack;
    private String shaderStatus = "";
    private float clock;
    private boolean paused;
    /** Walk-cycle half the last footstep was played in, so each step sounds once. */
    private int lastStepHalf;
    /** Sips before the player passes out; -PsipsToPassOut=3 for quick testing. */
    private final int sipsToPassOut =
            Integer.parseInt(System.getProperty("roastengine.sipsToPassOut", "20"));
    /** 0 = awake; counts up while the screen fades out after too many drinks. */
    private float passOutTimer;
    /** Counts down while the screen fades back in after the level restarts. */
    private float wakeTimer;
    private int passOuts;
    private float drunkTime;
    private WorldObject interactionTarget;
    /** Mesh used to draw the liquid inside a held glass, taken from the mod's own assets. */
    private net.coffeebrewia.roastengine.render.model.ModelAsset liquidAsset;
    private final Matrix4f heldTransform = new Matrix4f();
    private float verticalVelocity;
    /** Horizontal momentum, needed so ice can carry the player instead of stopping dead. */
    private float velocityX;
    private float velocityZ;
    private boolean onGround;
    private boolean onIce;
    private float physicsDebug;
    /** Frame time carried into render(), where the animation is advanced. */
    private float lastDelta;
    private final float autoInteractAt =
            Float.parseFloat(System.getProperty("roastengine.autoInteract", "0"));
    private boolean autoInteractDone;
    private float elapsed;
    private float autoWalkSeconds = Float.parseFloat(System.getProperty("roastengine.autoWalk", "0"));
    private PlayerModel playerModel;
    /** Advancing walk cycle, drives the leg swing and hand bob. */
    private float walkPhase;
    private boolean moving;
    private String notice = "";
    private float noticeTimer;

    /** The server connection when playing online; null offline. */
    private final MultiplayerSession online;
    private final ChatBox chatBox = new ChatBox();
    /** True while the chat has the keyboard, so typing does not also walk and jump. */
    private boolean chatting;
    private final Matrix4f viewProjection = new Matrix4f();
    private final Vector4f projected = new Vector4f();

    public SandboxState(Engine engine, GameState returnState) {
        this(engine, returnState, null);
    }

    /** Plays a single installed world mod (the browser's "Play" button). */
    public SandboxState(Engine engine, GameState returnState, LocalMod onlyMod) {
        this(engine, returnState, onlyMod == null ? null : List.of(onlyMod), null);
    }

    /**
     * Plays online: {@code sources} are the world and content mods the server's session uses,
     * already installed. Leaving returns to {@code returnState}.
     */
    public SandboxState(Engine engine, GameState returnState, List<LocalMod> sources, MultiplayerSession online) {
        this.engine = engine;
        this.returnState = returnState;
        this.sources = sources;
        this.online = online;
    }

    @Override
    public void enter() {
        shader = ShaderProgram.fromResources("/shaders/world");
        skinnedShader = ShaderProgram.fromResources("/shaders/skinned.vert", "/shaders/world.frag");

        // Worlds always load; mods only when the player has switched them on.
        List<LocalMod> source = sources != null
                ? sources
                : engine.modManager().installedMods().stream()
                        .filter(engine.modManager()::isEnabled).toList();
        world = WorldLoader.load(source);
        npcs.load(world);
        pauseMenu = new PauseMenu(engine.settings(), () -> shaderStatus,
                () -> engine.actions().status(), this::connectedControllers);
        post = new PostProcessor(engine.window());
        loadedPack = null;
        brokenPack = null;
        paused = false;
        startAudio();
        skyColor.set(world.skyColor());
        if (world.hasGroundPlatform() || world.isEmpty()) {
            ground = Primitives.plane(PLATFORM_SIZE, 0.55f, 0.58f, 0.52f);
        }
        if (world.isEmpty()) {
            buildDemoScene();
            setNotice("No world mods installed - showing the demo scene");
        } else {
            setNotice("Loaded " + world.objects().size() + " object(s) from " + world.modNames());
        }
        if (world.failedObjects() > 0) {
            System.err.println("[World] " + world.failedObjects() + " object(s) could not be loaded");
        }
        if (world.spawn() != null) {
            spawn.set(world.spawn());
        }
        String spawnAt = System.getProperty("roastengine.spawnAt");
        if (spawnAt != null) {
            String[] parts = spawnAt.split(",");
            spawn.set(Float.parseFloat(parts[0]), Float.parseFloat(parts[1]), Float.parseFloat(parts[2]));
        }
        camera.position().set(spawn);
        verticalVelocity = 0f;
        unstick();
        interactions.reset();
        liquidAsset = world.liquidAsset();
        String passOutSpot = System.getProperty("roastengine.passOutAt");
        if (passOutSpot != null) {
            String[] parts = passOutSpot.split(",");
            npcs.playerPassedOut(new Vector3f(Float.parseFloat(parts[0]),
                    Float.parseFloat(parts[1]), Float.parseFloat(parts[2])));
        }
        playerModel = new PlayerModel(EYE_HEIGHT);
        // Dev aid: -PstartPitch=60 starts the camera looking down (handy for screenshots).
        String startPitch = System.getProperty("roastengine.startPitch");
        if (startPitch != null) {
            camera.rotate(0f, (float) Math.toRadians(Float.parseFloat(startPitch)));
        }
        String startYaw = System.getProperty("roastengine.startYaw");
        if (startYaw != null) {
            camera.rotate((float) Math.toRadians(Float.parseFloat(startYaw)), 0f);
        }
        engine.input().setCursorCaptured(true);
        // Dev aid: -PstartMenu=graphics opens the pause menu on a given page, for screenshots.
        String startMenu = System.getProperty("roastengine.startMenu");
        if (startMenu != null) {
            pause();
            pauseMenu.openAt(startMenu);
        }
    }

    /** Loads any sounds the world's mods ship, starts the music and hooks up effect sounds. */
    private void startAudio() {
        AudioEngine audio = engine.audio();
        for (java.nio.file.Path folder : world.modFolders()) {
            for (String name : SoundBank.ALL) {
                audio.overrideFromFile(name, folder.resolve("assets").resolve("sounds").resolve(name + ".ogg"));
            }
        }
        String music = world.music().isEmpty() ? SoundBank.MUSIC_MENU : world.music();
        audio.playMusic(music);
        audio.setMusicPitch(1f);
        audio.applyMusicVolume(1f);

        interactions.setListener(new Interactions.Listener() {
            @Override
            public void doorMoved(WorldObject door, boolean opening) {
                audio.play(SoundBank.DOOR, 0.9f, opening ? 1f : 0.9f);
            }

            @Override
            public void sipped(WorldObject drink) {
                audio.play(SoundBank.SIP, 0.9f, 0.92f + (float) Math.random() * 0.16f);
            }

            @Override
            public void drinkPutDown(WorldObject drink) {
                audio.play(SoundBank.GLASS, 0.6f, 1f);
            }
        });
    }

    /** Fallback scene when no mod supplies a world. */
    private void buildDemoScene() {
        Mesh coffee = track(Primitives.cube(0.45f, 0.29f, 0.17f));
        Mesh cream = track(Primitives.cube(0.93f, 0.86f, 0.74f));
        Mesh accent = track(Primitives.cube(0.78f, 0.51f, 0.23f));

        demoProps.add(new Prop(coffee, new Matrix4f().translate(0, 1, 0).scale(2)));
        for (int i = 0; i < 12; i++) {
            double angle = i * Math.PI * 2 / 12;
            float px = (float) Math.cos(angle) * 20f;
            float pz = (float) Math.sin(angle) * 20f;
            float height = 2f + (i % 3) * 2f;
            demoProps.add(new Prop(i % 2 == 0 ? cream : accent,
                    new Matrix4f().translate(px, height / 2f, pz).scale(1.5f, height, 1.5f)));
        }
    }

    private Mesh track(Mesh mesh) {
        demoMeshes.add(mesh);
        return mesh;
    }

    // ---------------------------------------------------------------------
    // Update
    // ---------------------------------------------------------------------

    @Override
    public void update(float dt) {
        lastDelta = dt;
        clock += dt;
        updateShaderPack();
        Input input = engine.input();
        // Movement, looking and interacting go through the action layer, so an InputEdit API
        // mod's controller drives them exactly as the keyboard does.
        InputActions actions = engine.actions();
        if (noticeTimer > 0) {
            noticeTimer -= dt;
        }
        if (online != null && !updateOnline(dt)) {
            return; // disconnected, and already on the way back to the menu
        }

        if (!chatting && actions.wasPressed(InputButton.PAUSE)) {
            if (!paused) {
                pause();
            } else if (pauseMenu.back()) {
                resume();
            }
        }
        // Settings apply live, even while the menu is open.
        camera.setFieldOfView(engine.settings().fieldOfView);
        engine.audio().applyMusicVolume(paused ? 0.35f : musicDuck());
        if (paused) {
            return;
        }
        if (!input.isCursorCaptured()) {
            input.setCursorCaptured(true); // e.g. focus came back to the window
        }
        // While taking an automated screenshot the camera is locked, so shots are reproducible.
        if (!chatting && System.getProperty("roastengine.autoScreenshot") == null) {
            float sensitivity = MOUSE_SENSITIVITY * engine.settings().mouseSensitivity;
            camera.rotate(actions.axis(InputAxis.LOOK_X) * sensitivity,
                    actions.axis(InputAxis.LOOK_Y) * sensitivity);
        }

        float forward = chatting ? 0f : actions.axis(InputAxis.MOVE_Y);
        float right = chatting ? 0f : actions.axis(InputAxis.MOVE_X);
        // Dev aid: -PautoWalk=<seconds> holds "forward" so movement can be tested without hands.
        if (autoWalkSeconds > 0f) {
            autoWalkSeconds -= dt;
            forward = 1f;
        }
        float length = (float) Math.sqrt(forward * forward + right * right);
        if (length > 1f) {
            forward /= length;
            right /= length;
        }
        float speed = WALK_SPEED * (actions.isDown(InputButton.SPRINT) ? SPRINT_MULTIPLIER : 1f);

        // Walking follows the look direction, but never tilts with the pitch.
        float sin = (float) Math.sin(camera.yaw());
        float cos = (float) Math.cos(camera.yaw());
        float wishX = sin * forward * speed + cos * right * speed;
        float wishZ = -cos * forward * speed + sin * right * speed;

        // Momentum: normal ground snaps to the wanted speed, ice eases toward it and keeps
        // sliding when you let go, which is what makes a slippery surface feel like ice.
        onIce = onGround && standingOnSlippery();
        float acceleration = !onGround ? AIR_ACCELERATION : onIce ? ICE_ACCELERATION : GROUND_ACCELERATION;
        velocityX += (wishX - velocityX) * Math.min(1f, acceleration * dt);
        velocityZ += (wishZ - velocityZ) * Math.min(1f, acceleration * dt);
        if (onIce && forward == 0f && right == 0f) {
            float friction = Math.max(0f, 1f - ICE_FRICTION * dt);
            velocityX *= friction;
            velocityZ *= friction;
        }

        float dx = velocityX * dt;
        float dz = velocityZ * dt;

        if (onGround && !chatting && actions.wasPressed(InputButton.JUMP)) {
            verticalVelocity = JUMP_VELOCITY;
            onGround = false;
        }
        verticalVelocity += GRAVITY * dt;

        // Use actual speed, not the frame's tiny delta: residual sliding (especially on ice)
        // would otherwise flicker between walking and standing many times a second.
        moving = (float) Math.hypot(velocityX, velocityZ) > WALK_ANIMATION_THRESHOLD;
        if (moving) {
            // `speed` is the distance covered this frame, so tying the phase to it keeps the
            // steps in sync with the ground at any walking or sprinting speed.
            walkPhase = (walkPhase + (float) Math.hypot(dx, dz) * STEPS_PER_METRE)
                    % (float) (Math.PI * 2);
            int stepHalf = (int) (walkPhase / Math.PI);
            if (stepHalf != lastStepHalf) {
                lastStepHalf = stepHalf;
                engine.audio().play(SoundBank.FOOTSTEP, onIce ? 0.35f : 0.55f,
                        0.9f + (float) Math.random() * 0.2f);
            }
        } else {
            walkPhase = 0f;
        }

        moveHorizontally(dx, dz);
        moveVertically(verticalVelocity * dt);

        // Doors and drinks: E interacts, holding E keeps drinking.
        interactionTarget = interactions.findTarget(world, camera);
        boolean interactPressed = !chatting && actions.wasPressed(InputButton.INTERACT);
        boolean interactHeld = !chatting && actions.isDown(InputButton.INTERACT);
        // Dev aid: -PautoInteract=<seconds> presses E at that time and holds it afterwards.
        if (autoInteractAt > 0f) {
            elapsed += dt;
            if (elapsed >= autoInteractAt) {
                interactHeld = true;
                interactPressed = !autoInteractDone;
                autoInteractDone = true;
            }
        }
        if (interactPressed && passOutTimer <= 0f) {
            if (interactionTarget != null && interactionTarget.isDoor()
                    && !interactionTarget.doorWantsOpen && npcs.hasDoorman()) {
                // The doorman turns and sizes you up, then lets you in.
                npcs.doormanNotices();
                interactions.openDoorAfter(interactionTarget, 1.0f);
                setNotice("The doorman looks you over... and lets you in.");
            } else {
                interactions.interact(interactionTarget);
            }
        }
        interactions.update(world, dt, interactHeld && passOutTimer <= 0f);
        npcs.update(dt, camera.position());
        if (npcs.consumeLiftOff()) {
            engine.audio().play(SoundBank.WHOOSH, 1f, 1f);
        }
        updateDrunkenness(dt);
        if (System.getProperty("roastengine.debugPhysics") != null) {
            physicsDebug += dt;
            if (physicsDebug > 1f) {
                physicsDebug = 0f;
                System.out.printf("[Physics] pos=%.2f,%.2f,%.2f vel=%.2f,%.2f onGround=%s ice=%s"
                                + "%n",
                        camera.position().x, camera.position().y, camera.position().z,
                        velocityX, velocityZ, onGround, onIce);
            }
        }

        if (camera.position().y < FALL_RESPAWN_Y) {
            respawn("You fell out of the world");
        }
        checkKillVolumes();
    }

    /**
     * Trades positions and chat with the server. Runs even while paused, because the others
     * keep moving and the server needs to keep hearing from us.
     *
     * @return false once the connection has gone, after switching back to the menu
     */
    private boolean updateOnline(float dt) {
        if (!online.isConnected()) {
            String reason = online.disconnectReason();
            engine.states().switchTo(returnState instanceof MultiplayerMenuState menu
                    ? menu.withMessage(reason, true) : returnState);
            return false;
        }
        online.update(dt, camera.position(), camera.yaw(), camera.pitch(),
                moving && passOutTimer <= 0f, STEPS_PER_METRE);
        chatting = chatBox.update(engine.input(), dt, !paused, online::sendChat);
        return true;
    }

    /**
     * Shaders need two things: the Graphics switch, and an enabled API mod that ships a shader
     * pack. This keeps the loaded pack in step with both, loading or dropping it as they change.
     */
    private void updateShaderPack() {
        // Dev aid: -PforceShaders uses any installed shader pack without touching the settings.
        boolean forced = System.getProperty("roastengine.forceShaders") != null;
        java.nio.file.Path source = (forced
                        ? engine.modManager().installedMods().stream().filter(LocalMod::isApi)
                        : engine.modManager().enabledApis().stream())
                .map(LocalMod::folder)
                .filter(ShaderPack::isShaderPack)
                .findFirst().orElse(null);
        boolean wanted = forced || engine.settings().shadersEnabled;

        if (source == null) {
            shaderStatus = "No shader pack: install an API such as RoastShaders and enable it.";
        } else if (!wanted) {
            shaderStatus = "Shader pack ready: " + source.getFileName() + " (switch is off)";
        }

        if (!wanted || source == null) {
            if (post.isActive()) {
                post.setPack(null);
                loadedPack = null;
            }
            return;
        }
        if (source.equals(loadedPack) || source.equals(brokenPack)) {
            return;
        }
        try {
            ShaderPack pack = ShaderPack.load(source, source.getFileName().toString());
            post.setPack(pack);
            loadedPack = source;
            shaderStatus = "Using shader pack: " + pack.name();
            System.out.println("[Shaders] " + shaderStatus);
        } catch (java.io.IOException | RuntimeException e) {
            brokenPack = source;
            post.setPack(null);
            loadedPack = null;
            shaderStatus = "Shader pack failed to compile - see the console";
            System.err.println("[Shaders] " + source.getFileName() + ": " + e.getMessage());
            setNotice("Shader pack failed to compile");
        }
    }

    /** Listed in Settings > General, so a plugged-in pad can be confirmed at a glance. */
    private String connectedControllers() {
        List<String> names = engine.gamepads().connected().stream()
                .map(device -> device.name() + (device.isGamepad() ? "" : " (no pad mapping)"))
                .toList();
        return names.isEmpty()
                ? "No controller detected - plug one in and it is picked up straight away."
                : "Detected: " + String.join(", ", names);
    }

    private void pause() {
        paused = true;
        pauseMenu.open();
        engine.input().setCursorCaptured(false);
    }

    private void resume() {
        paused = false;
        engine.input().setCursorCaptured(true);
    }

    /** Music gets quieter and slower as the player gets drunk, and fades out as they pass out. */
    private float musicDuck() {
        if (passOutTimer > 0f) {
            return Math.max(0f, 1f - passOutTimer / PASS_OUT_FADE);
        }
        if (wakeTimer > 0f) {
            return 1f - wakeTimer / WAKE_FADE;
        }
        return 1f;
    }

    /** Moves one axis at a time so the player slides along walls instead of sticking. */
    private void moveHorizontally(float dx, float dz) {
        Vector3f position = camera.position();

        float startX = position.x;
        position.x += dx;
        if (blocked() && !tryStepUp()) {
            position.x = startX;
            velocityX = 0f;
        }

        float startZ = position.z;
        position.z += dz;
        if (blocked() && !tryStepUp()) {
            position.z = startZ;
            velocityZ = 0f;
        }
    }

    /**
     * Lets the player walk up small ledges: lift by up to {@link #STEP_HEIGHT} and keep the
     * new position if that clears the obstruction.
     */
    private boolean tryStepUp() {
        if (!onGround) {
            return false;
        }
        Vector3f position = camera.position();
        float startY = position.y;
        position.y += STEP_HEIGHT;
        if (blocked()) {
            position.y = startY;
            return false;
        }
        return true;
    }

    /** Applies vertical motion, landing on top of solid objects and on the ground platform. */
    private void moveVertically(float dy) {
        Vector3f position = camera.position();
        float startY = position.y;
        position.y += dy;
        onGround = false;

        if (blocked()) {
            if (dy < 0) {
                // Falling onto something: stand exactly on its surface rather than reverting,
                // so the player rests at a predictable height instead of a random hair above it.
                float surface = surfaceTopUnder(startY);
                position.y = Float.isNaN(surface) ? startY : surface + EYE_HEIGHT + LANDING_GAP;
                onGround = true;
            } else {
                position.y = startY; // bumped our head
            }
            verticalVelocity = 0f;
        }

        float floorY = GROUND_LEVEL + EYE_HEIGHT;
        if (hasGround() && position.y <= floorY) {
            position.y = floorY;
            verticalVelocity = 0f;
            onGround = true;
        }
        if (!onGround && verticalVelocity <= 0f && supportUnderFeet() != null) {
            // Resting on something a hair below: still grounded.
            onGround = true;
            verticalVelocity = 0f;
        }
    }

    /**
     * The solid object immediately beneath the player's feet, or null when there is none.
     * A small probe depth keeps standing stable instead of alternating grounded/airborne each
     * frame as gravity nudges the player into the surface.
     */
    private WorldObject supportUnderFeet() {
        if (world == null) {
            return null;
        }
        Vector3f p = camera.position();
        float feet = p.y - EYE_HEIGHT;
        Vector3f min = new Vector3f(p.x - PLAYER_RADIUS, feet - GROUND_PROBE, p.z - PLAYER_RADIUS);
        Vector3f max = new Vector3f(p.x + PLAYER_RADIUS, feet + 0.01f, p.z + PLAYER_RADIUS);
        for (WorldObject object : world.objects()) {
            if (object.isSolid() && object.touches(min, max)) {
                return object;
            }
        }
        return null;
    }

    /**
     * There is always a floor at y=0, even for a world that hides the grid platform
     * ({@code "groundPlatform": false}) - otherwise the player would fall forever and
     * respawn in a loop. The platform mesh only controls whether that floor is drawn.
     */
    private boolean hasGround() {
        return true;
    }

    /** True when the player's box overlaps any solid object. */
    private boolean blocked() {
        if (world == null) {
            return false;
        }
        updatePlayerBox();
        for (WorldObject object : world.objects()) {
            if (object.isSolid() && object.overlaps(playerMin, playerMax)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Highest solid surface under the player, searching from the height they fell from.
     *
     * @return the world Y of that surface, or NaN when nothing suitable is below
     */
    private float surfaceTopUnder(float previousEyeY) {
        if (world == null) {
            return Float.NaN;
        }
        Vector3f p = camera.position();
        float previousFeet = previousEyeY - EYE_HEIGHT;
        Vector3f min = new Vector3f(p.x - PLAYER_RADIUS, -1e9f, p.z - PLAYER_RADIUS);
        Vector3f max = new Vector3f(p.x + PLAYER_RADIUS, previousFeet + STEP_HEIGHT, p.z + PLAYER_RADIUS);

        float best = Float.NaN;
        for (WorldObject object : world.objects()) {
            if (!object.collision || object.kills || !object.touches(min, max)) {
                continue;
            }
            // Per-part tops: a level exported as one model still has a floor to stand on.
            float top = object.topInColumn(p.x, p.z, previousFeet + STEP_HEIGHT);
            if (!Float.isNaN(top) && (Float.isNaN(best) || top > best)) {
                best = top;
            }
        }
        return best;
    }

    /**
     * Frees a player who begins inside geometry - a spawn point buried in a mod's level, say.
     *
     * <p>It stands the player <em>on the surface</em> at the spawn, then in rings around it,
     * ignoring anything that would put them on a rooftop. Lifting straight up (the obvious fix)
     * drops the player onto whatever they were stuck inside, which is why a normal-sized player
     * ends up looking down on the level like a giant.
     */
    private void unstick() {
        if (world == null || !blocked()) {
            return;
        }
        Vector3f position = camera.position();
        float startX = position.x;
        float startY = position.y;
        float startZ = position.z;
        // Anything higher than this counts as a roof rather than ground.
        float roofLimit = startY - EYE_HEIGHT + 2f * SCALE;

        if (standAt(startX, startZ, roofLimit)) {
            setNotice("Spawned on the surface");
            return;
        }
        for (float radius = 1.5f; radius <= 24f; radius += 1.5f) {
            for (int step = 0; step < 16; step++) {
                double angle = step * Math.PI * 2 / 16;
                float x = startX + (float) Math.cos(angle) * radius;
                float z = startZ + (float) Math.sin(angle) * radius;
                if (standAt(x, z, roofLimit)) {
                    setNotice(String.format("Spawn was inside the level - moved %.0fm clear", radius));
                    return;
                }
            }
        }

        // Nowhere on the ground worked: last resort, lift until clear.
        position.set(startX, startY, startZ);
        for (int step = 1; step <= 80; step++) {
            position.y = startY + step * 0.25f;
            if (!blocked()) {
                verticalVelocity = 0f;
                setNotice("Spawn was inside the level - moved above it");
                return;
            }
        }
        position.set(startX, startY, startZ);
    }

    /**
     * Tries to stand the player on whatever surface is at (x, z).
     *
     * @param roofLimit surfaces above this height are skipped, so the player is never parked
     *                  on top of a building they were merely standing inside
     * @return true when a clear standing position was found and applied
     */
    private boolean standAt(float x, float z, float roofLimit) {
        Vector3f position = camera.position();
        float previousX = position.x;
        float previousY = position.y;
        float previousZ = position.z;

        float top = GROUND_LEVEL;
        for (WorldObject object : world.objects()) {
            if (!object.collision || object.kills) {
                continue;
            }
            float candidate = object.topInColumn(x, z, roofLimit);
            if (!Float.isNaN(candidate) && candidate > top) {
                top = candidate;
            }
        }

        position.set(x, top + EYE_HEIGHT + LANDING_GAP, z);
        if (!blocked()) {
            verticalVelocity = 0f;
            onGround = true;
            return true;
        }
        position.set(previousX, previousY, previousZ);
        return false;
    }

    /** True when the surface holding the player up is flagged "Is Slippery". */
    private boolean standingOnSlippery() {
        WorldObject support = supportUnderFeet();
        return support != null && support.slippery;
    }

    private void respawn(String reason) {
        camera.position().set(spawn);
        verticalVelocity = 0f;
        velocityX = 0f;
        velocityZ = 0f;
        onGround = false;
        setNotice(reason);
        unstick();
    }

    private void checkKillVolumes() {
        if (world == null) {
            return;
        }
        updatePlayerBox();
        for (WorldObject object : world.objects()) {
            if (object.kills && object.touches(playerMin, playerMax)) {
                respawn("You died - killed by " + object.name);
                return;
            }
        }
    }

    private void updatePlayerBox() {
        Vector3f p = camera.position();
        float feet = p.y - EYE_HEIGHT;
        playerMin.set(p.x - PLAYER_RADIUS, feet, p.z - PLAYER_RADIUS);
        playerMax.set(p.x + PLAYER_RADIUS, feet + PLAYER_HEIGHT, p.z + PLAYER_RADIUS);
    }

    private void setNotice(String text) {
        notice = text;
        noticeTimer = 4f;
        System.out.println("[Sandbox] " + text);
    }

    // ---------------------------------------------------------------------
    // Render
    // ---------------------------------------------------------------------

    @Override
    public void render() {
        boolean shaders = post.isActive();
        if (shaders) {
            post.beginScene();
        }
        glClearColor(skyColor.x, skyColor.y, skyColor.z, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);

        shader.bind();
        shader.setUniform("uProjection", camera.projectionMatrix(engine.window().aspectRatio()));
        shader.setUniform("uView", camera.viewMatrix());
        shader.setUniform("uCameraPos", camera.position());
        shader.setUniform("uSkyColor", skyColor);
        shader.setUniform("uFogDistance", PLATFORM_SIZE * 0.6f);
        shader.setUniform("uHighlight", 0);
        shader.setUniform("uHeadRadius", PlayerModel.NO_HEAD_CLIP);
        // Glowing materials only really shine through a shader pack's bloom.
        shader.setUniform("uEmissiveStrength", shaders ? 1f : 0.35f);
        shader.setUniform("uUseTexture", 0);
        shader.setUniform("uAlpha", 1f);

        if (ground != null) {
            shader.setUniform("uGrid", 1);
            shader.setUniform("uModel", identity);
            ground.draw();
        }
        shader.setUniform("uGrid", 0);

        for (Prop prop : demoProps) {
            shader.setUniform("uModel", prop.model());
            prop.mesh().draw();
        }
        for (WorldObject object : world.objects()) {
            if (object == interactions.heldDrink()) {
                continue; // it is in the player's hand this frame
            }
            if (!object.npc.isEmpty()) {
                continue; // characters are drawn by the NPC system
            }
            // renderModel plays any animation the mod gave the object; a still object gets
            // its placed matrix back unchanged.
            Matrix4f model = object.renderModel(clock);
            if (object.isDoor()) {
                // Swing around the hinge: the door mesh is modelled with its pivot on that edge.
                shader.setUniform("uModel", new Matrix4f(model)
                        .rotateY(-object.openAmount * (float) Math.toRadians(95)));
            } else {
                shader.setUniform("uModel", model);
            }
            object.asset.draw(shader);
            if (object.isDrink() && object.fill > 0f) {
                drawLiquid(shader, new Matrix4f(model), object.fill);
            }
        }
        drawHeldDrink(shader);
        npcs.renderStatic(shader);

        // The player's own body and hands, drawn last so they sit on top of the world.
        playerModel.renderBody(shader, camera, EYE_HEIGHT, walkPhase, moving);
        playerModel.renderHands(shader, camera, walkPhase, moving);
        if (online != null && !playerModel.isAnimated()) {
            for (RemotePlayer other : online.others()) {
                if (other.isPlaced()) {
                    playerModel.renderOther(shader, other.eye, other.yaw, EYE_HEIGHT,
                            other.walkPhase, other.moving);
                }
            }
        }
        shader.unbind();

        // Rigged characters and, if animated, the player's own body share the skinning shader.
        {
            skinnedShader.bind();
            skinnedShader.setUniform("uProjection", camera.projectionMatrix(engine.window().aspectRatio()));
            skinnedShader.setUniform("uView", camera.viewMatrix());
            skinnedShader.setUniform("uCameraPos", camera.position());
            skinnedShader.setUniform("uSkyColor", skyColor);
            skinnedShader.setUniform("uFogDistance", PLATFORM_SIZE * 0.6f);
            skinnedShader.setUniform("uHighlight", 0);
            skinnedShader.setUniform("uGrid", 0);
            skinnedShader.setUniform("uEmissiveStrength", shaders ? 1f : 0.35f);
            if (playerModel.isAnimated() && passOutTimer <= 0f) {
                playerModel.renderAnimatedBody(skinnedShader, camera, EYE_HEIGHT, lastDelta, moving);
            }
            if (online != null && playerModel.isAnimated()) {
                for (RemotePlayer other : online.others()) {
                    if (other.isPlaced()) {
                        playerModel.renderOtherAnimated(skinnedShader, other.eye, other.yaw, EYE_HEIGHT,
                                other.animationTime, other.moving);
                    }
                }
            }
            npcs.renderAnimated(skinnedShader);
            skinnedShader.unbind();
        }

        if (shaders) {
            float drunk = Math.min(1f, interactions.totalSips() / (float) Math.max(1, sipsToPassOut));
            post.endScene(camera.projectionMatrix(engine.window().aspectRatio()),
                    camera.near(), camera.far(), clock,
                    java.util.Map.of("uDrunk", drunk));
        }
        drawHud();
    }

    /**
     * The more you drink the more the view sways; at the limit the screen fades out and the level
     * restarts. The janitor will be waiting where you fell.
     */
    private void updateDrunkenness(float dt) {
        if (wakeTimer > 0f) {
            wakeTimer = Math.max(0f, wakeTimer - dt);
        }
        int sips = interactions.totalSips();
        float drunk = Math.min(1f, sips / (float) Math.max(1, sipsToPassOut));
        if (drunk > 0.25f) {
            drunkTime += dt;
            float sway = (drunk - 0.25f) * 0.9f;
            camera.rotate((float) Math.sin(drunkTime * 0.9f) * sway * dt,
                    (float) Math.cos(drunkTime * 1.3f) * sway * 0.5f * dt);
        }

        engine.audio().setMusicPitch(1f - drunk * 0.18f);
        if (passOutTimer <= 0f && sips >= sipsToPassOut) {
            passOutTimer = 0.0001f;
            setNotice("You drank too much...");
            engine.audio().play(SoundBank.PASS_OUT, 1f, 1f);
        }
        if (passOutTimer > 0f) {
            passOutTimer += dt;
            // Slump towards the floor while the lights go out.
            camera.rotate(0f, dt * 0.6f);
            if (passOutTimer >= PASS_OUT_FADE) {
                restartAfterPassOut();
            }
        }
    }

    private void restartAfterPassOut() {
        Vector3f p = camera.position();
        npcs.playerPassedOut(new Vector3f(p.x, p.y - EYE_HEIGHT, p.z));
        passOuts++;

        // Put the level back the way it started.
        for (WorldObject object : world.objects()) {
            if (object.isDoor()) {
                object.openAmount = 0f;
                object.doorWantsOpen = false;
            } else if (object.isDrink()) {
                object.fill = 1f;
            }
        }
        interactions.reset();
        npcs.reset();
        passOutTimer = 0f;
        wakeTimer = WAKE_FADE;
        drunkTime = 0f;
        camera.position().set(spawn);
        camera.rotate(0f, -camera.pitch());
        velocityX = 0f;
        velocityZ = 0f;
        verticalVelocity = 0f;
        unstick();
        setNotice("You wake up at the entrance. Something is going on inside...");
    }

    /** Draws the liquid inside a glass, scaled to how full it is. */
    private void drawLiquid(ShaderProgram shader, Matrix4f glassTransform, float fill) {
        if (liquidAsset == null || fill <= 0f) {
            return;
        }
        shader.setUniform("uModel", glassTransform.translate(0f, 0.012f, 0f)
                .scale(1f, 0.14f * fill, 1f));
        liquidAsset.draw(shader);
    }

    /** Raises the held glass towards the camera as the player drinks from it. */
    private void drawHeldDrink(ShaderProgram shader) {
        WorldObject drink = interactions.heldDrink();
        if (drink == null) {
            return;
        }
        float raise = interactions.raise();
        // Blend from where the glass sits to a spot just below the camera, then tilt it.
        Vector3f resting = drink.center(new Vector3f());
        Vector3f held = new Vector3f(camera.position())
                .add((float) Math.sin(camera.yaw()) * 0.45f, -0.30f, -(float) Math.cos(camera.yaw()) * 0.45f);
        Vector3f position = new Vector3f(resting).lerp(held, raise);

        heldTransform.translation(position)
                .rotateY(-camera.yaw())
                .rotateX(raise * (float) Math.toRadians(35))
                .translate(0f, -0.08f, 0f);
        shader.setUniform("uModel", heldTransform);
        drink.asset.draw(shader);
        drawLiquid(shader, new Matrix4f(heldTransform), drink.fill);
    }

    private void drawHud() {
        Renderer2D r = engine.renderer2D();
        float w = engine.window().width();
        float h = engine.window().height();
        Color hudText = Theme.TEXT;
        Color hudShadow = Color.rgb(0x000000).withAlpha(0.6f);

        r.begin();
        Vector3f p = camera.position();
        String fps = engine.settings().showFps ? Math.round(engine.fps()) + " FPS  |  " : "";
        String status = String.format("RoastEngine Sandbox  |  %spos %.1f %.1f %.1f  |  %s  |  mods: %d",
                fps, p.x, p.y, p.z,
                onIce ? "ICE" : onGround ? "on ground" : "in air",
                engine.modManager().addons().stream().filter(engine.modManager()::isEnabled).count());
        shadowedText(r, status, 12, 12, 1.5f, hudText, hudShadow);
        String hints = "WASD walk  |  Mouse look  |  Space jump  |  Shift/Ctrl sprint  |  E interact  |  Esc menu";
        if (online != null) {
            hints += "  |  T chat  |  Tab players";
            drawOnlineHud(r, w, h, hudText, hudShadow);
        }
        if (engine.actions().hasSchemes() && engine.gamepads().anyConnected()) {
            hints += "  |  controller ready";
        }
        shadowedText(r, hints, 12, h - 24, 1.5f, hudText, hudShadow);

        if (noticeTimer > 0) {
            r.textCentered(notice, 1, 45, w, 20, 1.75f, hudShadow);
            r.textCentered(notice, 0, 44, w, 20, 1.75f, hudText);
        }

        String prompt = interactions.prompt(interactionTarget);
        if (prompt != null && engine.input().isCursorCaptured()) {
            float boxWidth = r.textWidth(prompt, 2f) + 28;
            r.rect((w - boxWidth) / 2f, h * 0.62f, boxWidth, 34, Theme.BACKGROUND.withAlpha(0.72f));
            r.textCentered(prompt, (w - boxWidth) / 2f, h * 0.62f, boxWidth, 34, 2f, Theme.TEXT);
        }
        int sips = interactions.totalSips();
        if (sips > 0) {
            r.text("Sips: " + sips + " / " + sipsToPassOut, 12, 36, 1.5f, Theme.TEXT);
        }
        if (npcs.janitorScenePlaying()) {
            r.textCentered("The janitor is taking care of what is left of you...",
                    0, h * 0.82f, w, 20, 1.75f, Theme.TEXT);
        }
        if (passOutTimer > 0f) {
            float alpha = Math.min(1f, passOutTimer / PASS_OUT_FADE);
            r.rect(0, 0, w, h, Color.rgb(0x000000).withAlpha(alpha));
            r.textCentered("You passed out", 0, h / 2f - 20, w, 40, 4f, Theme.TEXT.withAlpha(alpha));
        } else if (wakeTimer > 0f) {
            r.rect(0, 0, w, h, Color.rgb(0x000000).withAlpha(wakeTimer / WAKE_FADE));
        }
        WorldObject held = interactions.heldDrink();
        if (held != null) {
            String level = Math.round(held.fill * 100) + "%";
            r.textCentered(held.name + "   " + level, 0, h * 0.70f, w, 20, 1.75f, Theme.TEXT);
        }

        if (!paused) {
            r.rect(w / 2f - 8, h / 2f - 1, 16, 2, Color.rgb(0xFFFFFF).withAlpha(0.8f));
            r.rect(w / 2f - 1, h / 2f - 8, 2, 16, Color.rgb(0xFFFFFF).withAlpha(0.8f));
        }
        r.end();

        if (paused) {
            drawPauseMenu(w, h);
        }
    }

    /** Name tags over the other players, the chat, the server line and, while Tab is held, the list. */
    private void drawOnlineHud(Renderer2D r, float w, float h, Color text, Color shadow) {
        viewProjection.set(camera.projectionMatrix(engine.window().aspectRatio())).mul(camera.viewMatrix());
        for (RemotePlayer other : online.others()) {
            if (!other.isPlaced() || other.eye.distance(camera.position()) > NAME_TAG_RANGE) {
                continue;
            }
            projected.set(other.eye.x, other.eye.y + 0.45f * SCALE, other.eye.z, 1f).mul(viewProjection);
            if (projected.w < 0.05f) {
                continue; // behind the camera
            }
            float sx = (projected.x / projected.w * 0.5f + 0.5f) * w;
            float sy = (0.5f - projected.y / projected.w * 0.5f) * h;
            float tw = r.textWidth(other.name, 1.5f);
            r.rect(sx - tw / 2f - 5, sy - 4, tw + 10, 20, Color.rgb(0x000000).withAlpha(0.45f));
            r.text(other.name, sx - tw / 2f, sy, 1.5f, text);
        }

        int ping = online.pingMillis();
        String server = online.serverName() + "  |  " + (online.others().size() + 1) + "/" + online.maxPlayers()
                + " online" + (ping >= 0 ? "  |  " + ping + " ms" : "");
        shadowedText(r, server, w - r.textWidth(server, 1.5f) - 12, 12, 1.5f, text, shadow);

        chatBox.draw(r, online.chat(), online.clock(), w, h);

        if (!chatting && !paused && engine.input().isKeyDown(GLFW_KEY_TAB)) {
            List<String> names = new ArrayList<>();
            names.add(online.myName() + " (you)");
            online.others().stream().map(other -> other.name).sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(names::add);
            float bw = 320;
            float bh = 52 + names.size() * 22;
            float bx = (w - bw) / 2f;
            float by = 70;
            r.rect(bx, by, bw, bh, Theme.BACKGROUND.withAlpha(0.85f));
            r.outline(bx, by, bw, bh, 1f, Theme.PANEL_BORDER);
            r.textCentered(online.serverName(), bx, by + 8, bw, 24, 2f, Theme.TEXT);
            float ly = by + 44;
            for (String name : names) {
                r.text(name, bx + 20, ly, 1.5f, name.endsWith("(you)") ? Theme.ACCENT : Theme.TEXT);
                ly += 22;
            }
        }
    }

    private static void shadowedText(Renderer2D r, String text, float x, float y, float scale,
                                     Color color, Color shadow) {
        r.text(text, x + 1, y + 1, scale, shadow);
        r.text(text, x, y, scale, color);
    }

    private void drawPauseMenu(float w, float h) {
        engine.ui().begin(lastDelta);
        PauseMenu.Action action = pauseMenu.draw(engine.ui(), w, h);
        engine.ui().end();
        if (action == PauseMenu.Action.RESUME) {
            resume();
        } else if (action == PauseMenu.Action.MAIN_MENU) {
            engine.states().switchTo(returnState);
        }
    }

    @Override
    public void exit() {
        if (online != null) {
            online.close();
        }
        if (post != null) {
            post.dispose();
            post = null;
        }
        engine.audio().stopEffects();
        engine.audio().setMusicPitch(1f);
        engine.audio().applyMusicVolume(1f);
        engine.input().setCursorCaptured(false);
        glDisable(GL_DEPTH_TEST);
        if (world != null) {
            world.dispose();
            world = null;
        }
        npcs.dispose();
        if (playerModel != null) {
            playerModel.dispose();
            playerModel = null;
        }
        demoMeshes.forEach(Mesh::dispose);
        demoMeshes.clear();
        demoProps.clear();
        if (ground != null) {
            ground.dispose();
            ground = null;
        }
        if (shader != null) {
            shader.dispose();
            shader = null;
        }
        if (skinnedShader != null) {
            skinnedShader.dispose();
            skinnedShader = null;
        }
    }
}

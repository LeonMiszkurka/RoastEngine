package net.coffeebrewia.roastengine.render;

import net.coffeebrewia.roastengine.render.anim.AnimatedModel;
import net.coffeebrewia.roastengine.render.anim.AnimatedModelLoader;
import net.coffeebrewia.roastengine.render.anim.AnimationClip;
import net.coffeebrewia.roastengine.render.model.ModelAsset;
import net.coffeebrewia.roastengine.render.model.ModelLoader;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The player's own body, drawn in first person - and, in multiplayer, everyone else's.
 *
 * <p>Two parts:
 * <ul>
 *   <li><b>Body</b> - torso and legs placed in the world at the player's feet, rotated by yaw
 *       only (never pitch), so looking down shows your own chest and legs.</li>
 *   <li><b>Hands</b> - held in view space in front of the camera, so they stay on screen
 *       wherever you look, and bob while walking.</li>
 * </ul>
 *
 * <p>Everything is built from unit cubes; the engine has no skeletal animation, so the walk
 * cycle is a simple sine swing of the legs around the hips.
 */
public final class PlayerModel {

    private static final float LEG_LENGTH = 0.85f;
    private static final float MAX_LEG_SWING = (float) Math.toRadians(35);
    /** The body sits slightly behind the eyes, where a real head would be. */
    private static final float BODY_OFFSET_Z = 0.06f;
    /**
     * The camera sits on the model's head, so looking down shows your own chest and legs. Only a
     * small offset is needed - a real head sits slightly ahead of the torso - because the head
     * itself is removed by the shader clip rather than by pushing the body away.
     * Tune with {@code -Droastengine.playerModelOffset}.
     */
    private static final float CUSTOM_BODY_OFFSET_Z = 0.12f;
    /** Radius of the sphere around the eyes that hides the player's own head. */
    private static final float HEAD_HIDE_RADIUS =
            Float.parseFloat(System.getProperty("roastengine.headHideRadius", "0.34"));
    /** Passed as the radius to disable head hiding for everything that is not the player. */
    public static final float NO_HEAD_CLIP = 0f;
    /** Custom models are scaled to about this standing height (the player's eye height). */
    private static final float DEFAULT_PLAYER_HEIGHT = 1.7f;
    /** How far the top of the head rises above eye level. */
    private static final float HEAD_ABOVE_EYES = 0.14f;
    /** How far in front of the eyes the hands sit (metres). */
    private static final float HAND_DISTANCE = 0.62f;

    /** Where a custom player model is looked for, in order. */
    private static final String[] MODEL_CANDIDATES = {
            "player.glb", "player.gltf", "player.obj", "player.fbx",
            "playermodel.glb", "playermodel.gltf", "playermodel.obj", "playermodel.fbx",
            "assets/player.glb", "assets/playermodel.glb",
            "models/player.glb", "models/playermodel.glb",
    };

    private final Mesh shirt;
    private final Mesh trousers;
    private final Mesh skin;
    private final Matrix4f scratch = new Matrix4f();
    private final Matrix4f viewInverse = new Matrix4f();

    /** Animation files loaded alongside the player model, one clip each. */
    private static final String IDLE_FILE = "player_idle.glb";
    private static final String WALK_FILE = "player_walk.glb";
    /** Optional: played once when the player throws a punch. */
    private static final String PUNCH_FILE = "player_punch.glb";
    /**
     * Which slice of player_punch.glb is the punch, in seconds, and how long it lasts.
     *
     * <p>Exporters often write the whole scene timeline into the file rather than just the swing,
     * so the punch may sit anywhere inside a much longer clip. These play the part that matters at
     * its real speed: {@code -PpunchStart=3.5 -PpunchSeconds=0.9}. With a file that holds only the
     * punch, the defaults play it from the beginning.
     */
    private static final float PUNCH_START =
            Float.parseFloat(System.getProperty("roastengine.punchStart", "0"));
    private static final float PUNCH_SECONDS =
            Float.parseFloat(System.getProperty("roastengine.punchSeconds", "0.8"));

    /** Custom model from player.glb, or null to draw the built-in blocky body. */
    private final ModelAsset custom;
    /** Rigged model, used instead of {@link #custom} when animation files are present. */
    private AnimatedModel animated;
    private AnimationClip idleClip;
    private AnimationClip walkClip;
    private AnimationClip punchClip;
    /** Counts up while a punch plays; 0 when not punching. */
    private float punchTime;
    private float animationTime;
    private float idleTime;
    private float walkTime;
    private boolean wasWalking;
    private float customScale = 1f;
    private float customYawOffset;
    private float customOffsetZ = CUSTOM_BODY_OFFSET_Z;
    private boolean drawHands = true;

    private final float playerHeight;

    public PlayerModel() {
        this(DEFAULT_PLAYER_HEIGHT);
    }

    /** @param playerHeight eye height in metres; the body and any custom model are fitted to it */
    public PlayerModel(float playerHeight) {
        this.playerHeight = playerHeight;
        this.animated = loadAnimatedModel();
        shirt = Primitives.cube(0.76f, 0.38f, 0.16f);   // roast orange
        trousers = Primitives.cube(0.24f, 0.26f, 0.32f); // dark denim
        skin = Primitives.cube(0.85f, 0.66f, 0.50f);
        custom = animated != null ? null : loadCustomModel();

        if (animated != null) {
            customScale = fitScaleFor(animated.height());
            customYawOffset = (float) Math.toRadians(
                    Float.parseFloat(System.getProperty("roastengine.playerModelYaw", "180")));
            customOffsetZ = Float.parseFloat(System.getProperty("roastengine.playerModelOffset",
                    String.valueOf(CUSTOM_BODY_OFFSET_Z)));
            drawHands = "always".equalsIgnoreCase(System.getProperty("roastengine.hands", "auto"));
        } else if (custom != null) {
            customScale = fitScale(custom);
            customScale *= Float.parseFloat(System.getProperty("roastengine.playerModelScale", "1"));
            System.out.printf("[PlayerModel] bounds min(%.2f %.2f %.2f) max(%.2f %.2f %.2f) -> scale %.3f%n",
                    custom.min().x, custom.min().y, custom.min().z,
                    custom.max().x, custom.max().y, custom.max().z, customScale);
            // glTF models are authored facing +Z, while the engine's forward is -Z, so the
            // default half-turn makes an exported character face the way the player walks.
            customYawOffset = (float) Math.toRadians(
                    Float.parseFloat(System.getProperty("roastengine.playerModelYaw", "180")));
            customOffsetZ = Float.parseFloat(System.getProperty("roastengine.playerModelOffset",
                    String.valueOf(CUSTOM_BODY_OFFSET_Z)));
            // A custom model usually has its own arms, so the cube hands are off by default.
            drawHands = "always".equalsIgnoreCase(System.getProperty("roastengine.hands", "auto"));
        } else {
            drawHands = !"never".equalsIgnoreCase(System.getProperty("roastengine.hands", "auto"));
        }
    }

    /**
     * Scales the model so its head lands at the camera.
     *
     * <p>Fitting by height alone is what matters here: the eyes sit a little below the top of the
     * head, so the model is scaled until its top reaches {@code eye height + HEAD_ABOVE_EYES}.
     * Scaling by width instead (to stop a stocky model looming) leaves the head below the camera
     * and the body appears to hang in mid-air, which is wrong for a first-person body.
     * Use {@code -Droastengine.playerModelScale} to adjust a model that still looks off.
     */
    private float fitScale(ModelAsset model) {
        return fitScaleFor(model.max().y - model.min().y);
    }

    private float fitScaleFor(float modelHeight) {
        if (modelHeight < 0.001f) {
            return 1f;
        }
        return (playerHeight + HEAD_ABOVE_EYES * (playerHeight / DEFAULT_PLAYER_HEIGHT)) / modelHeight;
    }

    /**
     * Loads the rigged player and its animation clips.
     *
     * <p>Each clip ships as its own file next to the game - {@code player_idle.glb} carries the
     * mesh and the idle animation, {@code player_walk.glb} contributes only its clip, bound to the
     * same skeleton by bone name.
     */
    private AnimatedModel loadAnimatedModel() {
        Path idleFile = findFile(IDLE_FILE);
        if (idleFile == null) {
            return null;
        }
        try {
            AnimatedModel model = AnimatedModelLoader.load(idleFile);
            idleClip = model.clip(model.clipNames().isEmpty() ? "" : model.clipNames().get(0));
            System.out.println("[PlayerModel] Animated: " + idleFile.getFileName() + " ("
                    + model.triangleCount() + " triangles, " + model.skeleton().boneCount() + " bones)");

            Path walkFile = findFile(WALK_FILE);
            if (walkFile != null) {
                List<AnimationClip> clips = AnimatedModelLoader.loadClips(walkFile, model.skeleton());
                if (!clips.isEmpty()) {
                    walkClip = clips.get(0);
                    model.addClip("walk", walkClip);
                }
            }
            // The punch is optional: without the file, the hands swing instead.
            Path punchFile = findFile(PUNCH_FILE);
            if (punchFile != null) {
                List<AnimationClip> clips = AnimatedModelLoader.loadClips(punchFile, model.skeleton());
                if (!clips.isEmpty()) {
                    punchClip = clips.get(0);
                    model.addClip("punch", punchClip);
                    System.out.printf("[PlayerModel] Punch: %s (%.1fs long; playing %.2fs from %.2fs)%n",
                            punchFile.getFileName(), punchClip.durationSeconds(), PUNCH_SECONDS, PUNCH_START);
                    if (punchClip.durationSeconds() > 3f && PUNCH_START == 0f) {
                        System.out.println("[PlayerModel] That clip holds a whole timeline, not just a "
                                + "punch. Export only the punch, or pick the moment it happens with "
                                + "-PpunchStart=<seconds>.");
                    }
                }
            }
            System.out.println("[PlayerModel] Clips - idle: "
                    + (idleClip == null ? "none" : String.format("%.1fs", idleClip.durationSeconds()))
                    + ", walk: "
                    + (walkClip == null ? "none" : String.format("%.1fs", walkClip.durationSeconds())));
            return model;
        } catch (IOException | RuntimeException e) {
            System.err.println("[PlayerModel] Could not load " + idleFile + ": " + e.getMessage());
            return null;
        }
    }

    /** Looks for a file in the game's data folder, then among the files shipped in the app. */
    private static Path findFile(String name) {
        Path data = net.coffeebrewia.roastengine.core.GameHome.dataDirectory().resolve(name);
        if (Files.isRegularFile(data)) {
            return data;
        }
        Path bundled = net.coffeebrewia.roastengine.core.GameHome.bundledDirectory();
        if (bundled != null && Files.isRegularFile(bundled.resolve(name))) {
            return bundled.resolve(name);
        }
        return null;
    }

    /**
     * Looks for a custom player model. Drop a {@code playermodel.glb} next to the game (or in
     * {@code assets/}) and it replaces the built-in blocky body; override the location with
     * {@code -Droastengine.playerModel=/path/to/file.glb}.
     */
    private static ModelAsset loadCustomModel() {
        Path chosen = null;
        String override = System.getProperty("roastengine.playerModel");
        if (override != null && !override.isBlank()) {
            chosen = Path.of(override);
            if (!Files.isRegularFile(chosen)) {
                System.err.println("[PlayerModel] Not found: " + chosen);
                return null;
            }
        } else {
            // Search the game's data folder first, then files shipped inside the app.
            java.util.List<Path> roots = new java.util.ArrayList<>();
            roots.add(net.coffeebrewia.roastengine.core.GameHome.dataDirectory());
            Path bundled = net.coffeebrewia.roastengine.core.GameHome.bundledDirectory();
            if (bundled != null) {
                roots.add(bundled);
            }
            outer:
            for (Path root : roots) {
                for (String candidate : MODEL_CANDIDATES) {
                    Path path = root.resolve(candidate);
                    if (Files.isRegularFile(path)) {
                        chosen = path;
                        break outer;
                    }
                }
            }
        }
        if (chosen == null) {
            System.out.println("[PlayerModel] No playermodel.glb found - using the built-in body");
            return null;
        }
        try {
            ModelAsset asset = ModelLoader.load(chosen);
            System.out.println("[PlayerModel] Using " + chosen + " (" + asset.triangleCount() + " triangles)");
            return asset;
        } catch (IOException | RuntimeException e) {
            System.err.println("[PlayerModel] Could not load " + chosen + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Draws the torso and legs at the player's position.
     *
     * @param walkPhase advancing walk cycle in radians
     * @param moving    whether the legs should swing
     */
    public void renderBody(ShaderProgram shader, Camera camera, float eyeHeight,
                           float walkPhase, boolean moving) {
        renderBody(shader, camera.position(), camera.yaw(), eyeHeight, walkPhase, moving, true);
    }

    /**
     * Draws another player's body, standing with their eyes at {@code eye} and facing {@code yaw}.
     * Unlike the local body, the head is kept. Does nothing for an animated model, which is drawn
     * by {@link #renderOtherAnimated} with the skinning shader.
     */
    public void renderOther(ShaderProgram shader, Vector3f eye, float yaw, float eyeHeight,
                            float walkPhase, boolean moving) {
        renderBody(shader, eye, yaw, eyeHeight, walkPhase, moving, false);
    }

    private void renderBody(ShaderProgram shader, Vector3f position, float yaw, float eyeHeight,
                            float walkPhase, boolean moving, boolean hideHead) {
        float feetY = position.y - eyeHeight;
        float swing = moving ? (float) Math.sin(walkPhase) * MAX_LEG_SWING : 0f;

        if (animated != null) {
            return; // drawn by renderAnimatedBody with its own shader
        }
        if (custom != null) {
            renderCustomBody(shader, position, yaw, feetY, hideHead);
            return;
        }

        // Torso: top stops below the eyes so it never clips through the near plane, and it
        // sits slightly behind the eye line so the legs stay visible when looking down.
        float unit = eyeHeight / DEFAULT_PLAYER_HEIGHT; // proportions follow the player's height
        shader.setUniform("uModel", scratch.translation(position.x, feetY, position.z)
                .rotateY(-yaw)
                .translate(0f, 0.92f * unit, BODY_OFFSET_Z * unit)
                .scale(0.38f * unit, 0.56f * unit, 0.22f * unit));
        shirt.draw();

        drawLeg(shader, yaw, position, feetY, 0.12f, swing);
        drawLeg(shader, yaw, position, feetY, -0.12f, -swing);
        if (!hideHead) {
            // Someone else's body gets the head that the first-person view leaves off.
            shader.setUniform("uModel", scratch.translation(position.x, feetY, position.z)
                    .rotateY(-yaw)
                    .translate(0f, eyeHeight - 0.02f * unit, BODY_OFFSET_Z * unit)
                    .scale(0.26f * unit, 0.28f * unit, 0.26f * unit));
            skin.draw();
        }
    }

    /** One leg, swung around the hip joint. */
    private void drawLeg(ShaderProgram shader, float yaw, Vector3f position,
                         float feetY, float sideOffset, float swing) {
        shader.setUniform("uModel", scratch.translation(position.x, feetY, position.z)
                .rotateY(-yaw)
                .translate(sideOffset, LEG_LENGTH, BODY_OFFSET_Z) // hip, directly under the chest
                .rotateX(swing)
                .translate(0f, -LEG_LENGTH / 2f, 0f)      // centre of the leg
                .scale(0.17f, LEG_LENGTH, 0.19f));
        trousers.draw();
    }

    /**
     * Draws a custom model standing at the player's feet. It is pushed back from the eyes so
     * the head sits behind the camera instead of filling the screen.
     */
    private void renderCustomBody(ShaderProgram shader, Vector3f position, float yaw, float feetY,
                                  boolean hideHead) {
        // Hide just the head: a sphere around the eyes, so the shoulders and arms stay whole.
        shader.setUniform("uHeadCenter", position);
        shader.setUniform("uHeadRadius",
                hideHead ? HEAD_HIDE_RADIUS * (playerHeight / DEFAULT_PLAYER_HEIGHT) : NO_HEAD_CLIP);
        // Order matters: the standoff is applied in the player's own frame (before the model's
        // facing correction), otherwise a model turned 180 degrees gets pushed in front of the
        // camera instead of behind it, and its head blocks the view.
        shader.setUniform("uModel", scratch.translation(position.x, feetY, position.z)
                .rotateY(-yaw)
                .translate(0f, 0f, customOffsetZ)
                .rotateY(customYawOffset)
                .scale(customScale)
                // The model's own origin may not be at its feet; line its base up with the ground.
                .translate(0f, -custom.min().y, 0f));
        custom.draw(shader);
        shader.setUniform("uHeadRadius", NO_HEAD_CLIP);
    }

    public boolean isAnimated() {
        return animated != null;
    }

    /** Advances the punch. Call once a frame from the game's update. */
    public void advance(float deltaSeconds) {
        advancePunch(deltaSeconds);
    }

    /** Starts a punch: the clip from player_punch.glb, or a swing of the hands without it. */
    public void punch() {
        punchTime = 0.0001f;
    }

    /** True while a punch is playing, so the sandbox knows not to start another. */
    public boolean isPunching() {
        return punchTime > 0f;
    }

    /** How far through the punch, 0 to 1 - what the hands swing along. */
    private float punchProgress() {
        return Math.min(1f, punchTime / PUNCH_SECONDS);
    }

    private void advancePunch(float deltaSeconds) {
        if (punchTime <= 0f) {
            return;
        }
        punchTime += deltaSeconds;
        if (punchProgress() >= 1f) {
            punchTime = 0f;
        }
    }

    /**
     * Advances and draws the rigged body.
     *
     * <p>Idle loops while standing still and the walk clip plays while moving - running reuses the
     * walk clip, as sprinting only changes speed. Switching clips restarts the timer so a cycle
     * always begins at its first frame.
     *
     * @param shader the skinning shader, already bound
     * @param moving whether the player is moving under their own power
     */
    public void renderAnimatedBody(ShaderProgram shader, Camera camera, float eyeHeight,
                                   float deltaSeconds, boolean moving) {
        renderAnimatedBody(shader, camera.position(), camera.yaw(), eyeHeight, deltaSeconds, moving, true);
    }

    /**
     * The same body seen from outside - third person, or the freecam - so it punches and walks
     * there too, head and all.
     */
    public void renderAnimatedBody(ShaderProgram shader, Vector3f eye, float yaw, float eyeHeight,
                                   float deltaSeconds, boolean moving, boolean hideHead) {
        if (animated == null) {
            return;
        }
        // Each clip keeps its own running time, so switching between them does not restart the
        // cycle - a walk that reset to frame zero on every step would look frozen.
        if (moving != wasWalking) {
            wasWalking = moving;
        }
        if (punchTime > 0f && punchClip != null) {
            // A punch takes over the whole body until it finishes. The clip plays at its own
            // speed from PUNCH_START, so the swing looks the way it was animated.
            animated.pose(punchClip, PUNCH_START + punchProgress() * PUNCH_SECONDS);
            drawAnimated(shader, eye, yaw, eyeHeight, hideHead);
            return;
        }
        AnimationClip clip = moving && walkClip != null ? walkClip : idleClip;
        if (moving) {
            walkTime += deltaSeconds;
            animationTime = walkTime;
        } else {
            idleTime += deltaSeconds;
            animationTime = idleTime;
        }
        animated.pose(clip, animationTime);

        drawAnimated(shader, eye, yaw, eyeHeight, hideHead);
    }

    /** Draws the posed rigged model at someone's feet. */
    private void drawAnimated(ShaderProgram shader, Vector3f eye, float yaw, float eyeHeight,
                              boolean hideHead) {
        float feetY = eye.y - eyeHeight;
        shader.setUniform("uHeadCenter", eye);
        shader.setUniform("uHeadRadius",
                hideHead ? HEAD_HIDE_RADIUS * (eyeHeight / DEFAULT_PLAYER_HEIGHT) : NO_HEAD_CLIP);
        shader.setUniform("uModel", scratch.translation(eye.x, feetY, eye.z)
                .rotateY(-yaw)
                .translate(0f, 0f, customOffsetZ)
                .rotateY(customYawOffset)
                .scale(customScale)
                .translate(0f, -animated.min().y, 0f));
        animated.draw(shader);
        shader.setUniform("uHeadRadius", NO_HEAD_CLIP);
    }

    /**
     * Poses and draws the rigged model for another player. They keep their head, and their clip
     * time is their own, so two players walking side by side are not in lockstep.
     *
     * @param shader the skinning shader, already bound
     * @param time   seconds this player has spent in the current clip
     */
    public void renderOtherAnimated(ShaderProgram shader, Vector3f eye, float yaw, float eyeHeight,
                                    float time, boolean moving) {
        if (animated == null) {
            return;
        }
        animated.pose(moving && walkClip != null ? walkClip : idleClip, time);
        shader.setUniform("uHeadRadius", NO_HEAD_CLIP);
        shader.setUniform("uModel", scratch.translation(eye.x, eye.y - eyeHeight, eye.z)
                .rotateY(-yaw)
                .translate(0f, 0f, customOffsetZ)
                .rotateY(customYawOffset)
                .scale(customScale)
                .translate(0f, -animated.min().y, 0f));
        animated.draw(shader);
    }

    /**
     * Draws both hands in front of the camera. They are positioned in view space and then
     * transformed into the world by the inverted view matrix, so they follow the look direction.
     */
    public void renderHands(ShaderProgram shader, Camera camera, float walkPhase, boolean moving) {
        if (!drawHands) {
            return;
        }
        viewInverse.set(camera.viewMatrix()).invert();
        float bob = moving ? (float) Math.sin(walkPhase * 2f) * 0.02f : 0f;

        if (punchTime > 0f) {
            // Out and back: the right hand throws the punch, the left stays put.
            float progress = punchProgress();
            float reach = (float) Math.sin(progress * Math.PI);
            drawHand(shader, 0.30f - reach * 0.22f, bob + reach * 0.10f, reach * 0.55f);
            drawHand(shader, -0.30f, -bob, 0f);
            return;
        }
        drawHand(shader, 0.30f, bob, 0f);
        drawHand(shader, -0.30f, -bob, 0f);
    }

    /**
     * The VR hands: one cube per controller, where the controller really is.
     *
     * <p>In VR nothing about the hands is animated - they are wherever the player is holding
     * them, which is the whole point of playing in a headset.
     *
     * @param roomToWorld turns a position in the player's room into one in the game world
     */
    public void renderVrHands(ShaderProgram shader, net.coffeebrewia.roastengine.vr.VrSystem.Hand left,
                              net.coffeebrewia.roastengine.vr.VrSystem.Hand right, Matrix4f roomToWorld) {
        drawVrHand(shader, left, roomToWorld);
        drawVrHand(shader, right, roomToWorld);
    }

    private void drawVrHand(ShaderProgram shader, net.coffeebrewia.roastengine.vr.VrSystem.Hand hand,
                            Matrix4f roomToWorld) {
        if (!hand.tracked) {
            return; // controller asleep or out of sight: better no hand than a hand on the floor
        }
        shader.setUniform("uModel", scratch.set(roomToWorld)
                .translate(hand.position)
                .rotate(hand.rotation)
                .scale(0.09f, 0.09f, 0.16f));
        skin.draw();
    }

    /** @param thrust how far the hand is pushed forward, for a punch */
    private void drawHand(ShaderProgram shader, float sideOffset, float bob, float thrust) {
        // View space: +X right, +Y up, -Z forward.
        shader.setUniform("uModel", scratch.set(viewInverse)
                .translate(sideOffset, -0.30f + bob, -HAND_DISTANCE - thrust)
                .rotateX((float) Math.toRadians(-14))
                .scale(0.11f, 0.11f, 0.40f + thrust * 0.3f));
        skin.draw();
    }

    public void dispose() {
        if (animated != null) {
            animated.dispose();
            animated = null;
        }
        if (custom != null) {
            custom.dispose();
        }
        shirt.dispose();
        trousers.dispose();
        skin.dispose();
    }
}

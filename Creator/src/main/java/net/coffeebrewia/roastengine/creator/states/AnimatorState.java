package net.coffeebrewia.roastengine.creator.states;

import net.coffeebrewia.roastengine.core.Engine;
import net.coffeebrewia.roastengine.core.GameState;
import net.coffeebrewia.roastengine.creator.project.CreatorProject;
import net.coffeebrewia.roastengine.input.Input;
import net.coffeebrewia.roastengine.render.Camera;
import net.coffeebrewia.roastengine.render.Color;
import net.coffeebrewia.roastengine.render.Mesh;
import net.coffeebrewia.roastengine.render.Primitives;
import net.coffeebrewia.roastengine.render.Renderer2D;
import net.coffeebrewia.roastengine.render.ShaderProgram;
import net.coffeebrewia.roastengine.render.anim.AnimatedModel;
import net.coffeebrewia.roastengine.render.anim.AnimatedModelLoader;
import net.coffeebrewia.roastengine.render.anim.AnimationClip;
import net.coffeebrewia.roastengine.render.anim.PoseClip;
import net.coffeebrewia.roastengine.render.anim.PoseClipFile;
import net.coffeebrewia.roastengine.render.anim.Skeleton;
import net.coffeebrewia.roastengine.ui.TextField;
import net.coffeebrewia.roastengine.ui.Theme;
import net.coffeebrewia.roastengine.ui.Ui;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_K;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;
import static org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11C.glClear;
import static org.lwjgl.opengl.GL11C.glClearColor;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glScissor;
import static org.lwjgl.opengl.GL11C.glViewport;

/**
 * The animator: opens a rigged model, poses its bones, and keys those poses over time.
 *
 * <p>The rig itself comes from a {@code .glb} in the project's assets - built in Blender, or
 * anywhere else that exports a skinned glTF. What is authored here is not a new rig but new
 * <b>clips</b> for it, saved beside the model as {@code <model>.clips.json} (see
 * {@link PoseClipFile}), which the game loads over the model's own animations. So a creature can
 * ship with a walk from Blender and have its death written here.
 *
 * <p>A key holds a bone's turn and shift <em>from its rest pose</em>, so the numbers on screen
 * read the way an animator thinks: "the head is turned 20 degrees", not "the head's matrix is".
 *
 * <p>What is here is straight FK: pick a bone, turn it, key it. Control shapes and IK handles -
 * the Rigify way of working, where you drag a foot and the leg follows - are the next thing to
 * build on top; the format and the bone picking are already shaped to take them.
 */
public final class AnimatorState implements GameState {

    private static final float MENU_HEIGHT = 30f;
    private static final float SIDE_WIDTH = 340f;
    /** How much of the side panel the selected bone's controls keep, pinned to the bottom. */
    private static final float POSE_HEIGHT = 250f;
    /** A text field draws its label above itself, so a row with one needs this much. */
    private static final float FIELD_ROW = 46f;
    private static final float TIMELINE_HEIGHT = 132f;
    private static final float ORBIT_SENSITIVITY = 0.008f;
    /** Keys land on a frame, and the timeline counts in these. */
    private static final int FRAMES_PER_SECOND = 24;
    private static final Vector3f SKY = new Vector3f(0.16f, 0.17f, 0.20f);
    private static final Color BONE = Color.rgb(0x6FA8DC);
    private static final Color BONE_SELECTED = Color.rgb(0xF0B429);

    private final Engine engine;
    private final Ui ui;
    private final Renderer2D r;
    private final CreatorProject project;
    private final Camera camera = new Camera(0f, 1.2f, 3.4f);

    private final TextField clipName = new TextField("Clip", false, 32);
    private final TextField clipLength = new TextField("Seconds", false, 8);

    private ShaderProgram shader;
    private ShaderProgram skinnedShader;
    private Mesh boneMesh;
    private Mesh ground;

    private List<String> rigs = List.of();
    private String rigFile = "";
    private AnimatedModel model;
    private final List<PoseClip> clips = new ArrayList<>();
    private int clipIndex = -1;
    private String selectedBone = "";

    /** Where the playhead is, in seconds. */
    private float time;
    private boolean playing;
    private boolean dirty;

    // The pose being edited, which is the selected bone's key at the playhead (or the blend
    // between the keys either side of it, until it is keyed).
    private final Vector3f pose = new Vector3f();
    private final Vector3f offset = new Vector3f();

    private float orbitYaw = 0.4f;
    private float orbitPitch = 0.25f;
    private float orbitDistance = 3.4f;
    private final Vector3f orbitTarget = new Vector3f(0f, 1.0f, 0f);
    private boolean orbiting;
    private float boneScroll;

    private String status = "";
    private float statusTimer;
    private float deltaSeconds;

    public AnimatorState(Engine engine, CreatorProject project) {
        this.engine = engine;
        this.ui = engine.ui();
        this.r = engine.renderer2D();
        this.project = project;
    }

    @Override
    public void enter() {
        engine.input().setCursorCaptured(false);
        shader = ShaderProgram.fromResources("/shaders/world");
        skinnedShader = ShaderProgram.fromResources("/shaders/skinned.vert", "/shaders/world.frag");
        boneMesh = Primitives.cube(0.55f, 0.72f, 0.95f);
        ground = Primitives.plane(80f, 0.22f, 0.23f, 0.26f);
        clipLength.setText("2.0");
        refreshRigs();
        if (!rigs.isEmpty()) {
            openRig(rigs.get(0));
        }
    }

    // ------------------------------------------------------------------
    // The rig and its clips
    // ------------------------------------------------------------------

    private void refreshRigs() {
        Path assets = project.assetsDir();
        if (!Files.isDirectory(assets)) {
            rigs = List.of();
            return;
        }
        try (Stream<Path> files = Files.list(assets)) {
            rigs = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase().endsWith(".glb")
                            || name.toLowerCase().endsWith(".gltf"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            rigs = List.of();
            setStatus("Could not read the assets folder: " + e.getMessage());
        }
    }

    /** Loads a model and whatever clips are already saved beside it. */
    private void openRig(String fileName) {
        disposeModel();
        clips.clear();
        clipIndex = -1;
        selectedBone = "";
        time = 0f;
        playing = false;
        dirty = false;
        rigFile = fileName;

        Path file = project.assetsDir().resolve(fileName);
        try {
            model = AnimatedModelLoader.load(file);
        } catch (IOException | RuntimeException e) {
            setStatus("Could not open " + fileName + ": " + e.getMessage());
            return;
        }
        clips.addAll(PoseClipFile.loadBesideModel(file));
        if (clips.isEmpty()) {
            // Nothing authored yet, so read in whatever the model already has. Its clips are
            // sampled back into keys, which is what makes a Blender animation editable here
            // rather than just playable.
            for (String name : model.clipNames()) {
                AnimationClip existing = model.clip(name);
                if (existing != null) {
                    clips.add(PoseClip.from(existing, model.skeleton(), FRAMES_PER_SECOND / 2));
                }
            }
            if (clips.isEmpty()) {
                clips.add(new PoseClip("idle", 2f));
            }
        }
        clipIndex = 0;
        syncClipFields();
        float height = Math.max(0.5f, model.max().y - model.min().y);
        orbitTarget.set(0f, height * 0.55f, 0f);
        orbitDistance = height * 2.2f;
        setStatus(fileName + ": " + model.skeleton().nodeCount() + " bones, " + clips.size() + " clip(s)");
    }

    private PoseClip clip() {
        return clipIndex >= 0 && clipIndex < clips.size() ? clips.get(clipIndex) : null;
    }

    private void syncClipFields() {
        PoseClip clip = clip();
        clipName.setText(clip == null ? "" : clip.name());
        clipLength.setText(clip == null ? "2.0" : String.format("%.2f", clip.seconds()));
    }

    private void save() {
        if (model == null || rigFile.isEmpty()) {
            return;
        }
        Path file = PoseClipFile.besideModel(project.assetsDir().resolve(rigFile));
        try {
            PoseClipFile.save(file, clips);
            dirty = false;
            setStatus("Saved " + file.getFileName());
        } catch (IOException e) {
            setStatus("Could not save: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Every frame
    // ------------------------------------------------------------------

    @Override
    public void update(float dt) {
        deltaSeconds = dt;
        statusTimer = Math.max(0f, statusTimer - dt);
        Input input = engine.input();
        boolean typing = ui.hasFocusedField();

        PoseClip clip = clip();
        if (playing && clip != null) {
            time += dt;
            if (time > clip.seconds()) {
                time = 0f;
            }
            readPoseFromClip();
        }

        if (!typing) {
            handleKeys(input);
        }
        updateOrbit(input);
        applyOrbit();
    }

    private void handleKeys(Input input) {
        if (input.wasKeyPressed(GLFW_KEY_SPACE)) {
            playing = !playing;
        }
        if (input.wasKeyPressed(GLFW_KEY_K)) {
            keyHere();
        }
        if (input.wasKeyPressed(GLFW_KEY_DELETE)) {
            deleteKeyHere();
        }
        float step = 1f / FRAMES_PER_SECOND;
        if (input.wasKeyPressed(GLFW_KEY_LEFT)) {
            scrub(time - step);
        }
        if (input.wasKeyPressed(GLFW_KEY_RIGHT)) {
            scrub(time + step);
        }
    }

    /** Right-drag orbits, the wheel zooms - the way a model viewer is expected to behave. */
    private void updateOrbit(Input input) {
        boolean overViewport = input.mouseX() < engine.window().width() - SIDE_WIDTH
                && input.mouseY() > MENU_HEIGHT
                && input.mouseY() < engine.window().height() - TIMELINE_HEIGHT;
        if (input.wasMousePressed(GLFW_MOUSE_BUTTON_RIGHT) && overViewport) {
            orbiting = true;
        }
        if (!input.isMouseDown(GLFW_MOUSE_BUTTON_RIGHT)) {
            orbiting = false;
        }
        if (orbiting) {
            orbitYaw -= input.mouseDeltaX() * ORBIT_SENSITIVITY;
            orbitPitch = Math.max(-1.4f, Math.min(1.4f,
                    orbitPitch + input.mouseDeltaY() * ORBIT_SENSITIVITY));
        }
        if (overViewport && input.scrollY() != 0) {
            orbitDistance = Math.max(0.6f, Math.min(20f, orbitDistance - input.scrollY() * 0.4f));
        }
    }

    private void applyOrbit() {
        float cosPitch = (float) Math.cos(orbitPitch);
        camera.position().set(
                orbitTarget.x + (float) Math.sin(orbitYaw) * cosPitch * orbitDistance,
                orbitTarget.y + (float) Math.sin(orbitPitch) * orbitDistance,
                orbitTarget.z + (float) Math.cos(orbitYaw) * cosPitch * orbitDistance);
        Vector3f toTarget = new Vector3f(orbitTarget).sub(camera.position());
        camera.setYaw((float) Math.atan2(toTarget.x, -toTarget.z));
        camera.setPitch((float) -Math.atan2(toTarget.y,
                Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z)));
    }

    // ------------------------------------------------------------------
    // Posing
    // ------------------------------------------------------------------

    /** Moves the playhead, keeping the editable pose in step with what is on screen. */
    private void scrub(float seconds) {
        PoseClip clip = clip();
        if (clip == null) {
            return;
        }
        time = Math.max(0f, Math.min(clip.seconds(), snap(seconds)));
        readPoseFromClip();
    }

    /** Rounds a moment onto the nearest frame, so keys line up with the timeline's ticks. */
    private static float snap(float seconds) {
        return Math.round(seconds * FRAMES_PER_SECOND) / (float) FRAMES_PER_SECOND;
    }

    private void readPoseFromClip() {
        PoseClip clip = clip();
        if (clip == null || selectedBone.isEmpty()) {
            pose.set(0f);
            offset.set(0f);
            return;
        }
        PoseClip.Key sampled = clip.sample(selectedBone, time,
                new PoseClip.Key(0f, new Vector3f(), new Vector3f()));
        pose.set(sampled.eulerDegrees());
        offset.set(sampled.offset());
    }

    private void keyHere() {
        PoseClip clip = clip();
        if (clip == null || selectedBone.isEmpty()) {
            setStatus("Pick a bone first");
            return;
        }
        clip.put(selectedBone, snap(time), pose, offset);
        dirty = true;
        setStatus("Keyed " + selectedBone + " at " + String.format("%.2fs", snap(time)));
    }

    private void deleteKeyHere() {
        PoseClip clip = clip();
        if (clip == null || selectedBone.isEmpty()) {
            return;
        }
        if (clip.remove(selectedBone, snap(time))) {
            dirty = true;
            readPoseFromClip();
            setStatus("Removed the key on " + selectedBone);
        }
    }

    /** The clip as the engine would play it, rebuilt each frame so edits show at once. */
    private AnimationClip previewClip() {
        PoseClip clip = clip();
        return clip == null || model == null ? null : clip.toAnimationClip(model.skeleton());
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Override
    public void render() {
        glClearColor(Theme.BACKGROUND.r(), Theme.BACKGROUND.g(), Theme.BACKGROUND.b(), 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        renderViewport();

        ui.begin(deltaSeconds);
        drawMenuBar();
        drawSidePanel();
        drawTimeline();
        ui.end();
    }

    private float[] viewportRect() {
        float w = engine.window().width() - SIDE_WIDTH;
        float h = engine.window().height() - MENU_HEIGHT - TIMELINE_HEIGHT;
        return new float[]{0f, MENU_HEIGHT, Math.max(1f, w), Math.max(1f, h)};
    }

    private void renderViewport() {
        float[] vp = viewportRect();
        float scaleX = (float) engine.window().framebufferWidth() / Math.max(1, engine.window().width());
        float scaleY = (float) engine.window().framebufferHeight() / Math.max(1, engine.window().height());
        int x = Math.round(vp[0] * scaleX);
        int y = Math.round((engine.window().height() - (vp[1] + vp[3])) * scaleY);
        int w = Math.round(vp[2] * scaleX);
        int h = Math.round(vp[3] * scaleY);
        if (w <= 0 || h <= 0) {
            return;
        }
        glViewport(x, y, w, h);
        glEnable(GL_SCISSOR_TEST);
        glScissor(x, y, w, h);
        glClearColor(SKY.x, SKY.y, SKY.z, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);

        float aspect = vp[3] <= 0 ? 1f : vp[2] / vp[3];
        shader.bind();
        shader.setUniform("uProjection", camera.projectionMatrix(aspect));
        shader.setUniform("uView", camera.viewMatrix());
        shader.setUniform("uCameraPos", camera.position());
        shader.setUniform("uSkyColor", SKY);
        shader.setUniform("uFogDistance", 90f);
        shader.setUniform("uHighlight", 0);
        shader.setUniform("uUseTexture", 0);
        shader.setUniform("uHeadRadius", net.coffeebrewia.roastengine.render.PlayerModel.NO_HEAD_CLIP);
        shader.setUniform("uGrid", 1);
        shader.setUniform("uModel", new Matrix4f());
        ground.draw();
        shader.setUniform("uGrid", 0);
        shader.unbind();

        if (model != null) {
            model.pose(previewClip(), time);

            skinnedShader.bind();
            skinnedShader.setUniform("uProjection", camera.projectionMatrix(aspect));
            skinnedShader.setUniform("uView", camera.viewMatrix());
            skinnedShader.setUniform("uCameraPos", camera.position());
            skinnedShader.setUniform("uSkyColor", SKY);
            skinnedShader.setUniform("uFogDistance", 90f);
            skinnedShader.setUniform("uHighlight", 0);
            skinnedShader.setUniform("uGrid", 0);
            skinnedShader.setUniform("uEmissiveStrength", 0.35f);
            skinnedShader.setUniform("uBrightness", 1f);
            skinnedShader.setUniform("uHeadRadius", net.coffeebrewia.roastengine.render.PlayerModel.NO_HEAD_CLIP);
            skinnedShader.setUniform("uModel", new Matrix4f());
            model.draw(skinnedShader);
            skinnedShader.unbind();

            drawSkeleton(aspect);
        }

        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_DEPTH_TEST);
        glViewport(0, 0, engine.window().framebufferWidth(), engine.window().framebufferHeight());
    }

    /**
     * The skeleton over the top of the model: a bar along every bone, the selected one lit.
     *
     * <p>Drawn without depth testing so a bone inside the body can still be seen and picked,
     * which is most of them on a solid character.
     */
    private void drawSkeleton(float aspect) {
        glDisable(GL_DEPTH_TEST);
        shader.bind();
        shader.setUniform("uProjection", camera.projectionMatrix(aspect));
        shader.setUniform("uView", camera.viewMatrix());
        shader.setUniform("uGrid", 0);
        shader.setUniform("uUseTexture", 0);

        Skeleton skeleton = model.skeleton();
        Matrix4f here = new Matrix4f();
        Matrix4f parent = new Matrix4f();
        for (int node = 0; node < skeleton.nodeCount(); node++) {
            int parentNode = skeleton.nodeParent(node);
            if (parentNode < 0) {
                continue;
            }
            if (model.boneTransform(skeleton.nodeName(node), here) == null
                    || model.boneTransform(skeleton.nodeName(parentNode), parent) == null) {
                continue;
            }
            Vector3f from = parent.getTranslation(new Vector3f());
            Vector3f to = here.getTranslation(new Vector3f());
            float length = from.distance(to);
            if (length < 0.001f) {
                continue;
            }
            boolean chosen = skeleton.nodeName(node).equals(selectedBone);
            shader.setUniform("uHighlight", chosen ? 1 : 0);
            float thickness = chosen ? 0.035f : 0.02f;
            shader.setUniform("uModel", new Matrix4f()
                    .translation(new Vector3f(from).add(to).mul(0.5f))
                    .rotateTowards(new Vector3f(to).sub(from).normalize(), new Vector3f(0, 1, 0))
                    .scale(thickness, thickness, length));
            boneMesh.draw();
        }
        shader.setUniform("uHighlight", 0);
        shader.unbind();
        glEnable(GL_DEPTH_TEST);
    }

    // ------------------------------------------------------------------
    // Panels
    // ------------------------------------------------------------------

    private void drawMenuBar() {
        float width = engine.window().width();
        r.rect(0, 0, width, MENU_HEIGHT, Theme.PANEL);
        r.rect(0, MENU_HEIGHT - 1, width, 1, Theme.PANEL_BORDER);
        if (ui.flatButton("Back", 8, 2, 70, MENU_HEIGHT - 6, false)) {
            engine.states().switchTo(new LauncherState(engine));
        }
        if (ui.flatButton(dirty ? "Save *" : "Save", 84, 2, 80, MENU_HEIGHT - 6, false)) {
            save();
        }
        r.text("Animator - " + project.name() + (rigFile.isEmpty() ? "" : "  /  " + rigFile),
                180, 9, 1.4f, Theme.TEXT_MUTED);
        if (statusTimer > 0) {
            r.text(status, width - r.textWidth(status, 1.4f) - 14, 9, 1.4f, Theme.ACCENT);
        }
    }

    private void drawSidePanel() {
        float x = engine.window().width() - SIDE_WIDTH;
        float height = engine.window().height();
        r.rect(x, MENU_HEIGHT, SIDE_WIDTH, height - MENU_HEIGHT, Theme.PANEL);
        r.rect(x, MENU_HEIGHT, 1, height - MENU_HEIGHT, Theme.PANEL_BORDER);

        float left = x + 12;
        float width = SIDE_WIDTH - 24;
        float row = MENU_HEIGHT + 12;

        // --- the rig
        r.text("RIG", left, row, 1.3f, Theme.ACCENT);
        row += 20;
        if (rigs.isEmpty()) {
            r.text("No .glb in this project's assets.", left, row, 1.2f, Theme.TEXT_MUTED);
            row += 24;
        } else {
            for (String rig : rigs) {
                if (ui.selectable(rig, left, row, width, 22, rig.equals(rigFile))) {
                    openRig(rig);
                }
                row += 23;
            }
        }
        row += 10;

        // --- clips
        r.text("CLIPS", left, row, 1.3f, Theme.ACCENT);
        row += 20;
        for (int i = 0; i < clips.size(); i++) {
            PoseClip clip = clips.get(i);
            String label = clip.name() + "   " + String.format("%.2fs", clip.seconds())
                    + "   " + clip.bones().size() + " bones";
            if (ui.selectable(label, left, row, width, 22, i == clipIndex)) {
                clipIndex = i;
                time = 0f;
                syncClipFields();
                readPoseFromClip();
            }
            row += 23;
        }
        row += 4;
        float third = (width - 16) / 3f;
        if (ui.button("New", left, row, third, 26)) {
            clips.add(new PoseClip("clip" + (clips.size() + 1), 2f));
            clipIndex = clips.size() - 1;
            syncClipFields();
            dirty = true;
        }
        if (ui.button("Copy", left + third + 8, row, third, 26, clip() != null)) {
            clips.add(clip().copy(clip().name() + "_copy"));
            clipIndex = clips.size() - 1;
            syncClipFields();
            dirty = true;
        }
        if (ui.button("Delete", left + (third + 8) * 2, row, third, 26, clips.size() > 1)) {
            clips.remove(clipIndex);
            clipIndex = Math.max(0, clipIndex - 1);
            syncClipFields();
            dirty = true;
        }
        row += 34;

        if (clip() != null) {
            float buttonWidth = 72;
            ui.textField(clipName, left, row, width - buttonWidth - 8);
            if (ui.button("Rename", left + width - buttonWidth, row + 18, buttonWidth, 26)) {
                clip().rename(clipName.text());
                dirty = true;
            }
            row += FIELD_ROW;
            ui.textField(clipLength, left, row, width - buttonWidth - 8);
            if (ui.button("Length", left + width - buttonWidth, row + 18, buttonWidth, 26)) {
                try {
                    clip().setSeconds(Float.parseFloat(clipLength.text().trim()));
                    dirty = true;
                } catch (NumberFormatException e) {
                    setStatus("That is not a number of seconds");
                }
                syncClipFields();
            }
            row += FIELD_ROW + 4;
        }

        // --- bones, in whatever room is left between here and the pose controls
        float poseTop = height - POSE_HEIGHT;
        r.text("BONES", left, row, 1.3f, Theme.ACCENT);
        r.text("wheel scrolls", left + 62, row + 2, 1.1f, Theme.TEXT_MUTED);
        row += 18;
        drawBoneList(left, row, width, poseTop - row - 6);

        drawPoseControls(left, poseTop, width);
    }

    /** The selected bone's turn and shift, pinned to the bottom of the panel. */
    private void drawPoseControls(float left, float top, float width) {
        r.rect(left - 12, top - 6, SIDE_WIDTH, 1, Theme.PANEL_BORDER);
        if (selectedBone.isEmpty()) {
            r.text("Pick a bone to pose it.", left, top + 8, 1.3f, Theme.TEXT_MUTED);
            return;
        }
        float row = top + 6;
        r.text(selectedBone.toUpperCase(), left, row, 1.4f, Theme.ACCENT);
        PoseClip clip = clip();
        boolean keyed = clip != null && clip.keyAt(selectedBone, snap(time)) != null;
        r.text(keyed ? "keyed here" : "not keyed here",
                left + width - r.textWidth(keyed ? "keyed here" : "not keyed here", 1.2f), row + 2,
                1.2f, keyed ? BONE_SELECTED : Theme.TEXT_MUTED);
        row += 22;
        pose.x = ui.slider("Turn X", left, row, width, pose.x, -180f, 180f,
                String.format("%.0f deg", pose.x));
        row += 40;
        pose.y = ui.slider("Turn Y", left, row, width, pose.y, -180f, 180f,
                String.format("%.0f deg", pose.y));
        row += 40;
        pose.z = ui.slider("Turn Z", left, row, width, pose.z, -180f, 180f,
                String.format("%.0f deg", pose.z));
        row += 40;
        offset.y = ui.slider("Shift up", left, row, width, offset.y, -0.6f, 0.6f,
                String.format("%.2f m", offset.y));
        row += 42;
        float half = (width - 8) / 2f;
        if (ui.button("Key (K)", left, row, half, 28)) {
            keyHere();
        }
        if (ui.button(keyed ? "Remove key" : "Zero pose", left + half + 8, row, half, 28)) {
            if (keyed) {
                deleteKeyHere();
            } else {
                pose.set(0f);
                offset.set(0f);
            }
        }
    }

    /**
     * The bone list, indented by depth so the hierarchy reads, and scrolled with the wheel
     * because a rig has more bones than fit.
     */
    private void drawBoneList(float left, float top, float width, float height) {
        if (model == null || height < 20) {
            return;
        }
        Skeleton skeleton = model.skeleton();
        PoseClip clip = clip();
        Input input = engine.input();
        boolean over = input.mouseX() > left - 12 && input.mouseY() > top
                && input.mouseY() < top + height;
        if (over && input.scrollY() != 0) {
            boneScroll -= input.scrollY() * 42f;
        }

        List<Integer> shown = new ArrayList<>();
        for (int node = 0; node < skeleton.nodeCount(); node++) {
            String name = skeleton.nodeName(node);
            if (name != null && !name.isBlank()) {
                shown.add(node);
            }
        }
        float rowHeight = 21f;
        float total = shown.size() * rowHeight;
        boneScroll = Math.max(0f, Math.min(boneScroll, Math.max(0f, total - height)));

        r.pushClip(left - 12, top, SIDE_WIDTH, height);
        float row = top - boneScroll;
        for (int node : shown) {
            if (row + rowHeight >= top && row <= top + height) {
                String name = skeleton.nodeName(node);
                int depth = 0;
                for (int parent = skeleton.nodeParent(node); parent >= 0 && depth < 6;
                     parent = skeleton.nodeParent(parent)) {
                    depth++;
                }
                float indent = depth * 9f;
                boolean keyed = clip != null && !clip.keys(name).isEmpty();
                if (ui.selectable((keyed ? "* " : "   ") + name, left + indent, row,
                        width - indent, 20, name.equals(selectedBone))) {
                    selectedBone = name;
                    readPoseFromClip();
                }
            }
            row += rowHeight;
        }
        r.popClip();
    }

    /**
     * The timeline: the clip's length as a bar, the keys on it, and the playhead.
     *
     * <p>Two rows of keys - the selected bone's, and every bone's - so it is clear both what
     * this bone does and where anything happens at all.
     */
    private void drawTimeline() {
        float top = engine.window().height() - TIMELINE_HEIGHT;
        float width = engine.window().width() - SIDE_WIDTH;
        r.rect(0, top, width, TIMELINE_HEIGHT, Theme.PANEL);
        r.rect(0, top, width, 1, Theme.PANEL_BORDER);

        PoseClip clip = clip();
        if (ui.button(playing ? "Pause" : "Play", 12, top + 10, 90, 28)) {
            playing = !playing;
        }
        if (ui.button("Start", 110, top + 10, 70, 28)) {
            scrub(0f);
            playing = false;
        }
        if (clip != null) {
            r.text(String.format("%.2f / %.2f s   frame %d", time, clip.seconds(),
                            Math.round(time * FRAMES_PER_SECOND)),
                    190, top + 18, 1.4f, Theme.TEXT_MUTED);
            r.text("Space play   K key   Del remove   arrows step   right-drag orbits",
                    width - r.textWidth("Space play   K key   Del remove   arrows step   right-drag orbits", 1.2f) - 14,
                    top + 18, 1.2f, Theme.TEXT_MUTED);
        }
        if (clip == null) {
            return;
        }

        float trackX = 16;
        float trackWidth = width - 32;
        float trackY = top + 58;
        float trackHeight = 22;
        r.rect(trackX, trackY, trackWidth, trackHeight, Theme.INPUT);
        r.rect(trackX, trackY + trackHeight + 10, trackWidth, trackHeight, Theme.INPUT);
        r.text("this bone", trackX, trackY - 14, 1.2f, Theme.TEXT_MUTED);
        r.text("all bones", trackX, trackY + trackHeight - 4 + 10 + 18, 1.2f, Theme.TEXT_MUTED);

        // Second marks along the bar.
        for (int second = 0; second <= (int) Math.ceil(clip.seconds()); second++) {
            float at = trackX + trackWidth * (second / clip.seconds());
            r.rect(at, trackY, 1, trackHeight, Theme.PANEL_BORDER);
        }

        // The keys: the selected bone on the top row, everything else below.
        for (String bone : clip.bones()) {
            boolean mine = bone.equals(selectedBone);
            float rowY = mine ? trackY : trackY + trackHeight + 10;
            for (PoseClip.Key key : clip.keys(bone)) {
                float at = trackX + trackWidth * (key.seconds() / clip.seconds());
                r.rect(at - 3, rowY + 4, 6, trackHeight - 8, mine ? BONE_SELECTED : BONE);
            }
        }

        // The playhead, and scrubbing by dragging anywhere on the bar.
        float head = trackX + trackWidth * (time / clip.seconds());
        r.rect(head - 1, trackY - 6, 2, trackHeight * 2 + 22, Theme.ACCENT);

        Input input = engine.input();
        boolean overBar = input.mouseX() >= trackX && input.mouseX() <= trackX + trackWidth
                && input.mouseY() >= trackY - 8 && input.mouseY() <= trackY + trackHeight * 2 + 14;
        if (overBar && input.isMouseDown(GLFW_MOUSE_BUTTON_LEFT)) {
            scrub(clip.seconds() * (input.mouseX() - trackX) / trackWidth);
            playing = false;
        }
    }

    private void setStatus(String text) {
        status = text;
        statusTimer = 4f;
        System.out.println("[Animator] " + text);
    }

    // ------------------------------------------------------------------

    private void disposeModel() {
        if (model != null) {
            model.dispose();
            model = null;
        }
    }

    @Override
    public void exit() {
        disposeModel();
        if (shader != null) {
            shader.dispose();
            shader = null;
        }
        if (skinnedShader != null) {
            skinnedShader.dispose();
            skinnedShader = null;
        }
        if (boneMesh != null) {
            boneMesh.dispose();
            boneMesh = null;
        }
        if (ground != null) {
            ground.dispose();
            ground = null;
        }
    }

    /** Screen position of a point in the world, for picking bones later. */
    @SuppressWarnings("unused")
    private Vector4f project(Vector3f point, float aspect) {
        Vector4f clip = new Vector4f(point, 1f)
                .mul(camera.viewMatrix())
                .mul(camera.projectionMatrix(aspect));
        return clip;
    }
}

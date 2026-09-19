package net.coffeebrewia.roastengine.creator.states;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.coffeebrewia.roastengine.core.Engine;
import net.coffeebrewia.roastengine.core.GameState;
import net.coffeebrewia.roastengine.creator.Dialogs;
import net.coffeebrewia.roastengine.creator.export.ModExporter;
import net.coffeebrewia.roastengine.creator.export.ModType;
import net.coffeebrewia.roastengine.creator.project.CreatorProject;
import net.coffeebrewia.roastengine.input.Input;
import net.coffeebrewia.roastengine.render.Camera;
import net.coffeebrewia.roastengine.render.Color;
import net.coffeebrewia.roastengine.render.Mesh;
import net.coffeebrewia.roastengine.render.PlayerModel;
import net.coffeebrewia.roastengine.render.Primitives;
import net.coffeebrewia.roastengine.render.Renderer2D;
import net.coffeebrewia.roastengine.render.ShaderProgram;
import net.coffeebrewia.roastengine.render.post.PostProcessor;
import net.coffeebrewia.roastengine.render.post.ShaderPack;
import net.coffeebrewia.roastengine.ui.TextField;
import net.coffeebrewia.roastengine.ui.Theme;
import net.coffeebrewia.roastengine.ui.Ui;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11C.*;

/**
 * The editor for a shader project: a live preview seen through the pack being written.
 *
 * <pre>
 * +---------------------------------------------------+
 * | File  Shaders                      project  status |
 * +-------------------------------------+-------------+
 * |                                     |  FILES      |
 * |   preview scene, rendered through   |  TUNING     |
 * |   this project's shaders/ folder    |  sliders    |
 * |                                     |  On / Off   |
 * +-------------------------------------+-------------+
 * |  the selected file, or the compile error          |
 * +---------------------------------------------------+
 * </pre>
 *
 * <p>The preview uses the same {@link PostProcessor} and {@link ShaderPack} as the game, so what
 * shows here is what players will see. The GLSL is edited in the author's own editor; every file
 * in {@code shaders/} is watched and the pack recompiles the moment one is saved. A pack that
 * fails to compile keeps the last good one on screen and shows the error underneath, so a typo
 * never blanks the view.
 *
 * <p>The numbers under {@code "uniforms"} in pack.json become sliders. They change the picture
 * immediately, without a recompile, and are written back to pack.json on save.
 */
public final class ShaderEditorState implements GameState {

    private static final float MENU_HEIGHT = 30f;
    private static final float PANEL_WIDTH = 340f;
    private static final float CODE_HEIGHT = 220f;
    private static final float LINE_HEIGHT = 15f;
    private static final float MOUSE_SENSITIVITY = 0.0022f;
    private static final float MOVE_SPEED = 6f;
    /** How often the shaders folder is checked for edits made in another editor. */
    private static final float WATCH_INTERVAL = 0.4f;
    private static final Vector3f SKY = new Vector3f(0.10f, 0.09f, 0.14f);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private enum Menu {NONE, FILE, SHADERS}

    /** One object in the fixed preview scene. */
    private record PreviewObject(Mesh mesh, Matrix4f model, Vector3f emissive) {
    }

    private final Engine engine;
    private final Ui ui;
    private final Renderer2D r;
    private final CreatorProject project;
    private final Camera camera = new Camera(0f, 2.4f, 9.5f);
    private final PostProcessor post;

    private ShaderProgram worldShader;
    private Mesh ground;
    private final List<Mesh> meshes = new ArrayList<>();
    private final List<PreviewObject> scene = new ArrayList<>();

    /** Last pack that compiled; kept on screen while a newer edit is broken. */
    private ShaderPack pack;
    private String compileError;
    private final Map<String, Long> stamps = new HashMap<>();
    private float watchTimer;
    private boolean shadersOn = true;

    /** Slider range per tuning value, fixed the first time it is seen so dragging stays stable. */
    private final Map<String, float[]> ranges = new HashMap<>();
    private boolean tuningDirty;

    private List<String> files = List.of();
    private String selectedFile;
    private List<String> codeLines = List.of();
    private float codeScroll;
    private float panelScroll;
    private float panelOverflow;

    private final TextField newUniformName = new TextField("New value", false, 32);
    private final TextField exportName = new TextField("File name", false, 48);
    private final TextField exportVersion = new TextField("Version", false, 16);

    private Menu openMenu = Menu.NONE;
    private boolean exportDialogOpen;
    private boolean looking;
    private float clock;
    private String status = "";
    private float statusTimer;
    private float deltaSeconds;

    public ShaderEditorState(Engine engine, CreatorProject project) {
        this.engine = engine;
        this.ui = engine.ui();
        this.r = engine.renderer2D();
        this.project = project;
        this.post = new PostProcessor(engine.window());
    }

    @Override
    public void enter() {
        engine.input().setCursorCaptured(false);
        worldShader = ShaderProgram.fromResources("/shaders/world");
        buildPreviewScene();
        camera.rotate(0f, 0.14f);
        exportName.setText(CreatorProject.sanitize(project.name()).replace(' ', '-').toLowerCase());
        exportVersion.setText("1.0.0");
        refreshFiles();
        recompile();
        setStatus("Opened " + project.name() + "  (shader project)");
    }

    // ---------------------------------------------------------------------
    // Preview scene
    // ---------------------------------------------------------------------

    /**
     * A small fixed room that exercises every stage of a pack: plain lit surfaces for the grade,
     * a wall and pillars close together for ambient occlusion, and glowing blocks for bloom.
     */
    private void buildPreviewScene() {
        ground = Primitives.plane(80f, 0.42f, 0.42f, 0.46f);

        Mesh wall = keep(Primitives.cube(0.46f, 0.44f, 0.50f));
        Mesh stone = keep(Primitives.cube(0.62f, 0.58f, 0.52f));
        Mesh red = keep(Primitives.cube(0.78f, 0.24f, 0.22f));
        Mesh blue = keep(Primitives.cube(0.22f, 0.40f, 0.78f));
        Mesh dark = keep(Primitives.cube(0.08f, 0.08f, 0.10f));

        add(wall, new Matrix4f().translation(0f, 2.5f, -4f).scale(16f, 5f, 0.5f), null);
        add(stone, new Matrix4f().translation(-4.5f, 1.5f, -2.8f).scale(1f, 3f, 1f), null);
        add(stone, new Matrix4f().translation(4.5f, 1.5f, -2.8f).scale(1f, 3f, 1f), null);
        add(red, new Matrix4f().translation(-2f, 0.5f, 0f), null);
        add(blue, new Matrix4f().translation(2f, 0.6f, 0.5f).rotateY(0.6f).scale(1.2f), null);
        add(stone, new Matrix4f().translation(0f, 0.35f, 1.8f).scale(3f, 0.7f, 0.7f), null);

        // Emissive strips: what the bright pass should pick out and the blur should spread.
        add(dark, new Matrix4f().translation(-3f, 3.4f, -3.7f).scale(3.2f, 0.18f, 0.1f),
                new Vector3f(2.4f, 0.5f, 2.8f));
        add(dark, new Matrix4f().translation(3f, 3.4f, -3.7f).scale(3.2f, 0.18f, 0.1f),
                new Vector3f(0.4f, 2.2f, 2.8f));
        add(dark, new Matrix4f().translation(0f, 1.1f, 0f).scale(0.5f),
                new Vector3f(3.0f, 1.5f, 0.3f));
    }

    private Mesh keep(Mesh mesh) {
        meshes.add(mesh);
        return mesh;
    }

    private void add(Mesh mesh, Matrix4f model, Vector3f emissive) {
        scene.add(new PreviewObject(mesh, model, emissive == null ? new Vector3f() : emissive));
    }

    // ---------------------------------------------------------------------
    // Compiling and watching
    // ---------------------------------------------------------------------

    /** Loads the pack from disk. On failure the previous pack stays, and the error is shown. */
    private void recompile() {
        rememberStamps();
        if (!ShaderPack.isShaderPack(project.root())) {
            compileError = "shaders/composite.frag is missing - a pack needs bright.frag, blur.frag "
                    + "and composite.frag.";
            return;
        }
        try {
            ShaderPack loaded = ShaderPack.load(project.root(), project.name());
            post.setPack(loaded);
            pack = loaded;
            compileError = null;
            tuningDirty = false;
            for (Map.Entry<String, Float> entry : loaded.uniforms().entrySet()) {
                ranges.computeIfAbsent(entry.getKey(), key -> rangeFor(entry.getValue()));
            }
            setStatus("Compiled " + loaded.name());
        } catch (IOException | RuntimeException e) {
            compileError = String.valueOf(e.getMessage());
            setStatus("Compile failed - see below");
            System.err.println("[Shaders] " + compileError);
        }
    }

    /**
     * A slider range that comfortably covers the starting value, scaled to it so a small value
     * like a grain amount of 0.018 still gets a slider that can be moved finely.
     */
    private static float[] rangeFor(float value) {
        float span = value == 0f ? 1f : Math.abs(value) * 3f;
        return new float[]{value < 0 ? -span : 0f, span};
    }

    private void rememberStamps() {
        stamps.clear();
        for (String file : project.listShaderFiles()) {
            stamps.put(file, stamp(project.shadersDir().resolve(file)));
        }
    }

    /** Recompiles when any file in shaders/ is saved, added or removed elsewhere. */
    private void watchFiles(float dt) {
        watchTimer -= dt;
        if (watchTimer > 0) {
            return;
        }
        watchTimer = WATCH_INTERVAL;
        List<String> now = project.listShaderFiles();
        boolean changed = !new java.util.HashSet<>(now).equals(stamps.keySet());
        for (String file : now) {
            if (changed) {
                break;
            }
            changed = stamp(project.shadersDir().resolve(file)) != stamps.getOrDefault(file, -1L);
        }
        if (changed) {
            refreshFiles();
            recompile();
        }
    }

    private static long stamp(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return -1L;
        }
    }

    // ---------------------------------------------------------------------
    // Update
    // ---------------------------------------------------------------------

    @Override
    public void update(float dt) {
        deltaSeconds = dt;
        clock += dt;
        if (statusTimer > 0) {
            statusTimer -= dt;
        }
        Input input = engine.input();
        if (input.wasKeyPressed(GLFW_KEY_ESCAPE)) {
            if (exportDialogOpen) {
                exportDialogOpen = false;
            } else if (openMenu != Menu.NONE) {
                openMenu = Menu.NONE;
            }
        }
        boolean typing = ui.hasFocusedField();
        if (!typing && !exportDialogOpen && input.isShortcutDown() && input.wasKeyPressed(GLFW_KEY_S)) {
            saveTuning();
        }
        watchFiles(dt);
        updateCamera(dt, typing);
    }

    /** Hold the left mouse button in the preview to look around; WASD moves. */
    private void updateCamera(float dt, boolean typing) {
        Input input = engine.input();
        float[] vp = viewportRect();
        boolean blocked = exportDialogOpen || openMenu != Menu.NONE;
        if (!looking && !blocked && input.wasMousePressed(GLFW_MOUSE_BUTTON_LEFT)
                && ui.isHovered(vp[0], vp[1] + 36, vp[2], vp[3] - 36)) {
            looking = true;
            input.setCursorCaptured(true);
        }
        if (!looking) {
            return;
        }
        if (!input.isMouseDown(GLFW_MOUSE_BUTTON_LEFT)) {
            looking = false;
            input.setCursorCaptured(false);
            return;
        }
        camera.rotate(input.mouseDeltaX() * MOUSE_SENSITIVITY, input.mouseDeltaY() * MOUSE_SENSITIVITY);
        if (typing) {
            return;
        }
        float forward = (input.isKeyDown(GLFW_KEY_W) ? 1f : 0f) - (input.isKeyDown(GLFW_KEY_S) ? 1f : 0f);
        float right = (input.isKeyDown(GLFW_KEY_D) ? 1f : 0f) - (input.isKeyDown(GLFW_KEY_A) ? 1f : 0f);
        float up = (input.isKeyDown(GLFW_KEY_SPACE) ? 1f : 0f) - (input.isKeyDown(GLFW_KEY_LEFT_SHIFT) ? 1f : 0f);
        float speed = MOVE_SPEED * dt;
        camera.moveHorizontal(forward * speed, right * speed);
        camera.moveVertical(up * speed);
    }

    // ---------------------------------------------------------------------
    // Render
    // ---------------------------------------------------------------------

    @Override
    public void render() {
        glClearColor(Theme.BACKGROUND.r(), Theme.BACKGROUND.g(), Theme.BACKGROUND.b(), 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        renderPreview();

        ui.begin(deltaSeconds);
        drawMenuBar();
        drawViewportOverlay();
        drawSidePanel();
        drawCodePanel();
        if (exportDialogOpen) {
            drawExportDialog();
        }
        ui.end();
    }

    /** The preview, through the pack when it is on and compiled, straight to the screen otherwise. */
    private void renderPreview() {
        float[] vp = viewportRect();
        float sx = (float) engine.window().framebufferWidth() / Math.max(1, engine.window().width());
        float sy = (float) engine.window().framebufferHeight() / Math.max(1, engine.window().height());
        int x = Math.round(vp[0] * sx);
        int y = Math.round((engine.window().height() - (vp[1] + vp[3])) * sy);
        int w = Math.round(vp[2] * sx);
        int h = Math.round(vp[3] * sy);
        if (w <= 0 || h <= 0) {
            return;
        }
        Matrix4f projection = camera.projectionMatrix(vp[2] / vp[3]);
        boolean shaded = shadersOn && pack != null;

        if (shaded) {
            post.beginScene(w, h);
        } else {
            glViewport(x, y, w, h);
            glEnable(GL_SCISSOR_TEST);
            glScissor(x, y, w, h);
        }
        glClearColor(SKY.x, SKY.y, SKY.z, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);

        worldShader.bind();
        worldShader.setUniform("uProjection", projection);
        worldShader.setUniform("uView", camera.viewMatrix());
        worldShader.setUniform("uCameraPos", camera.position());
        worldShader.setUniform("uSkyColor", SKY);
        worldShader.setUniform("uFogDistance", 60f);
        worldShader.setUniform("uHighlight", 0);
        worldShader.setUniform("uHeadRadius", PlayerModel.NO_HEAD_CLIP);
        // The same rule as the game: emission only really shines once a pack blooms it.
        worldShader.setUniform("uEmissiveStrength", shaded ? 1f : 0.35f);
        worldShader.setUniform("uUseTexture", 0);
        worldShader.setUniform("uAlpha", 1f);

        worldShader.setUniform("uGrid", 1);
        worldShader.setUniform("uEmissive", new Vector3f());
        worldShader.setUniform("uModel", new Matrix4f());
        ground.draw();
        worldShader.setUniform("uGrid", 0);
        for (PreviewObject object : scene) {
            worldShader.setUniform("uEmissive", object.emissive());
            worldShader.setUniform("uModel", object.model());
            object.mesh().draw();
        }
        worldShader.unbind();

        if (shaded) {
            post.endScene(projection, camera.near(), camera.far(), clock, Map.of("uDrunk", 0f), x, y, w, h);
        } else {
            glDisable(GL_SCISSOR_TEST);
        }
        glDisable(GL_DEPTH_TEST);
        glViewport(0, 0, engine.window().framebufferWidth(), engine.window().framebufferHeight());
    }

    private float[] viewportRect() {
        float w = engine.window().width() - PANEL_WIDTH;
        float h = engine.window().height() - MENU_HEIGHT - CODE_HEIGHT;
        return new float[]{0f, MENU_HEIGHT, Math.max(1f, w), Math.max(1f, h)};
    }

    private void drawViewportOverlay() {
        float[] vp = viewportRect();
        // The on/off switch sits on the picture, because comparing is what it is for.
        String label = shadersOn ? "Shaders: On" : "Shaders: Off";
        if (ui.flatButton(label, vp[0] + 12, vp[1] + 10, 150, 26, shadersOn)) {
            shadersOn = !shadersOn;
        }
        r.outline(vp[0] + 12, vp[1] + 10, 150, 26, 1f, shadersOn ? Theme.ACCENT : Theme.PANEL_BORDER);
        if (compileError != null) {
            r.text(pack == null ? "Not compiled - see the error below"
                            : "Showing the last pack that compiled - see the error below",
                    vp[0] + 176, vp[1] + 18, 1.5f, Theme.ERROR);
        }
        Color ink = Color.rgb(0xC8C0D8);
        r.text(looking ? "Looking - WASD move, Space/Shift up/down, release to stop"
                        : "Hold left mouse to look around  |  save a .frag and it recompiles",
                vp[0] + 12, vp[1] + vp[3] - 22, 1.5f, ink);
    }

    // ---------------------------------------------------------------------
    // Side panel: files and tuning
    // ---------------------------------------------------------------------

    private void drawSidePanel() {
        float x = engine.window().width() - PANEL_WIDTH;
        float y = MENU_HEIGHT;
        float w = PANEL_WIDTH;
        float h = engine.window().height() - MENU_HEIGHT;
        r.rect(x, y, w, h, Theme.PANEL);
        r.rect(x, y, 1, h, Theme.PANEL_BORDER);

        if (ui.isHovered(x, y, w, h)) {
            panelScroll -= engine.input().scrollY() * 40f;
        }
        panelScroll = Math.max(0f, Math.min(panelScroll, panelOverflow));
        r.pushClip(x, y, w, h);

        float fx = x + 14;
        float fw = w - 28;
        float fy = y + 12 - panelScroll;

        r.text("FILES", fx, fy, 1.5f, Theme.TEXT_MUTED);
        fy += 20;
        for (String file : files) {
            if (ui.selectable(file, fx, fy, fw, 26, file.equals(selectedFile))) {
                selectFile(file);
            }
            fy += 28;
        }
        float half = (fw - 8) / 2f;
        if (ui.button("Open in Editor", fx, fy + 4, half, 28, selectedFile != null)) {
            openInSystemEditor(selectedFile);
        }
        if (ui.button("Recompile", fx + half + 8, fy + 4, half, 28)) {
            recompile();
        }
        fy += 48;

        r.text("TUNING" + (tuningDirty ? "  (unsaved)" : ""), fx, fy, 1.5f, Theme.TEXT_MUTED);
        fy += 22;
        if (pack == null) {
            r.text("Compile the pack to tune it.", fx, fy, 1.25f, Theme.TEXT_MUTED);
            fy += 24;
        } else {
            float threshold = ui.slider("bloomThreshold", fx, fy, fw, pack.bloomThreshold(), 0f, 3f,
                    String.format("%.2f", pack.bloomThreshold()));
            if (threshold != pack.bloomThreshold()) {
                pack.setBloomThreshold(threshold);
                tuningDirty = true;
            }
            fy += 50;
            float passes = ui.slider("bloomPasses", fx, fy, fw, pack.bloomPasses(), 0f, 12f,
                    String.valueOf(pack.bloomPasses()));
            if (Math.round(passes) != pack.bloomPasses()) {
                pack.setBloomPasses(Math.round(passes));
                tuningDirty = true;
            }
            fy += 50;
            for (Map.Entry<String, Float> entry : new ArrayList<>(pack.uniforms().entrySet())) {
                float[] range = ranges.computeIfAbsent(entry.getKey(), key -> rangeFor(entry.getValue()));
                float value = ui.slider(entry.getKey(), fx, fy, fw, entry.getValue(), range[0], range[1],
                        String.format("%.3f", entry.getValue()));
                if (value != entry.getValue()) {
                    pack.setUniform(entry.getKey(), value);
                    tuningDirty = true;
                }
                fy += 50;
            }

            // A new tuning value: added to pack.json, and the composite shader reads it by name.
            fy += ui.textField(newUniformName, fx, fy, fw) + 6;
            if (ui.button("Add Tuning Value", fx, fy, fw, 28, !newUniformName.text().isBlank())) {
                addUniform(newUniformName.text().trim());
            }
            fy += 36;
            r.text("Then declare 'uniform float <name>;' in", fx, fy, 1.25f, Theme.TEXT_MUTED);
            r.text("composite.frag to use it.", fx, fy + 16, 1.25f, Theme.TEXT_MUTED);
            fy += 40;

            if (ui.button("Save Tuning  (Ctrl+S)", fx, fy, fw, 32, tuningDirty)) {
                saveTuning();
            }
            fy += 40;
            if (ui.button("Revert to pack.json", fx, fy, fw, 28, tuningDirty)) {
                recompile();
            }
            fy += 40;
        }

        r.popClip();
        panelOverflow = Math.max(0f, (fy + panelScroll) - (y + h) + 12);
    }

    // ---------------------------------------------------------------------
    // Code panel
    // ---------------------------------------------------------------------

    private void drawCodePanel() {
        float x = 0;
        float y = engine.window().height() - CODE_HEIGHT;
        float w = engine.window().width() - PANEL_WIDTH;
        float h = CODE_HEIGHT;
        r.rect(x, y, w, h, Theme.BACKGROUND);
        r.rect(x, y, w, 1, Theme.PANEL_BORDER);

        float top = y + 10;
        if (compileError != null) {
            // The error comes first: it is the one thing that needs doing.
            r.text("COMPILE ERROR", x + 14, top, 1.5f, Theme.ERROR);
            top += 20;
            for (String line : compileError.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                r.text(r.ellipsize(line.trim(), w - 28, 1.25f), x + 14, top, 1.25f, Theme.ERROR);
                top += LINE_HEIGHT;
                if (top > y + h * 0.45f) {
                    break;
                }
            }
            top += 8;
        }

        if (selectedFile == null) {
            r.text("Select a file on the right.", x + 14, top, 1.5f, Theme.TEXT_MUTED);
            return;
        }
        r.text(selectedFile + "   (read-only - Open in Editor to change it)", x + 14, top, 1.25f,
                Theme.TEXT_MUTED);
        top += 18;
        float viewHeight = y + h - top - 6;
        if (ui.isHovered(x, top, w, viewHeight)) {
            codeScroll -= engine.input().scrollY() * 3 * LINE_HEIGHT;
        }
        codeScroll = Math.max(0, Math.min(codeScroll, Math.max(0, codeLines.size() * LINE_HEIGHT - viewHeight)));

        r.pushClip(x, top, w, viewHeight);
        int first = (int) (codeScroll / LINE_HEIGHT);
        int visible = (int) (viewHeight / LINE_HEIGHT) + 2;
        for (int i = first; i < Math.min(codeLines.size(), first + visible); i++) {
            float lineY = top + i * LINE_HEIGHT - codeScroll;
            r.text(String.format("%4d", i + 1), x + 12, lineY, 1.25f, Theme.PANEL_BORDER);
            String line = codeLines.get(i);
            boolean comment = line.stripLeading().startsWith("//");
            r.text(line.replace("\t", "    "), x + 56, lineY, 1.25f, comment ? Theme.TEXT_MUTED : Theme.TEXT);
        }
        r.popClip();
    }

    // ---------------------------------------------------------------------
    // Menu bar and export
    // ---------------------------------------------------------------------

    private void drawMenuBar() {
        float w = engine.window().width();
        r.rect(0, 0, w, MENU_HEIGHT, Theme.PANEL);
        r.rect(0, MENU_HEIGHT - 1, w, 1, Theme.PANEL_BORDER);
        if (ui.flatButton("File", 8, 0, 60, MENU_HEIGHT, openMenu == Menu.FILE)) {
            openMenu = openMenu == Menu.FILE ? Menu.NONE : Menu.FILE;
        }
        if (ui.flatButton("Shaders", 72, 0, 90, MENU_HEIGHT, openMenu == Menu.SHADERS)) {
            openMenu = openMenu == Menu.SHADERS ? Menu.NONE : Menu.SHADERS;
        }
        r.text(project.name() + "   [Shaders]" + (tuningDirty ? " *" : ""), 180, 10, 1.75f, Theme.TEXT_MUTED);
        if (statusTimer > 0) {
            float tw = r.textWidth(status, 1.5f);
            r.text(status, w - tw - 16, 11, 1.5f, compileError != null ? Theme.ERROR : Theme.SUCCESS);
        }

        if (openMenu == Menu.FILE) {
            int clicked = drawDropdown(8, 220, new String[]{
                    "Save Tuning   (Ctrl+S)", "Export as...", "Open Project...", "Close Project"});
            switch (clicked) {
                case 0 -> saveTuning();
                case 1 -> {
                    openMenu = Menu.NONE;
                    exportDialogOpen = true;
                    ui.clearFocus();
                }
                case 2 -> {
                    openMenu = Menu.NONE;
                    Dialogs.selectFolder("Open RoastEngine project", project.root().getParent())
                            .ifPresent(this::openProject);
                }
                case 3 -> {
                    openMenu = Menu.NONE;
                    closeProject();
                }
                default -> {
                }
            }
        } else if (openMenu == Menu.SHADERS) {
            int clicked = drawDropdown(72, 240, new String[]{
                    "Recompile", shadersOn ? "Turn Preview Off" : "Turn Preview On", "Open Shaders Folder"});
            switch (clicked) {
                case 0 -> {
                    openMenu = Menu.NONE;
                    recompile();
                }
                case 1 -> {
                    openMenu = Menu.NONE;
                    shadersOn = !shadersOn;
                }
                case 2 -> {
                    openMenu = Menu.NONE;
                    openInSystemEditor(null);
                }
                default -> {
                }
            }
        }
    }

    private int drawDropdown(float x, float width, String[] items) {
        float y = MENU_HEIGHT;
        float itemHeight = 28;
        float height = items.length * itemHeight + 8;
        r.rect(x, y, width, height, Theme.ROW);
        r.outline(x, y, width, height, 1f, Theme.PANEL_BORDER);
        int clicked = -1;
        for (int i = 0; i < items.length; i++) {
            if (ui.selectable(items[i], x + 4, y + 4 + i * itemHeight, width - 8, itemHeight, false)) {
                clicked = i;
            }
        }
        if (clicked < 0 && engine.input().wasMousePressed(GLFW_MOUSE_BUTTON_LEFT)
                && !ui.isHovered(x, 0, width, height + MENU_HEIGHT)) {
            openMenu = Menu.NONE;
        }
        return clicked;
    }

    private void drawExportDialog() {
        float w = engine.window().width();
        float h = engine.window().height();
        ui.modalBackdrop(w, h);
        float dw = 440;
        float dh = 270;
        float x = (w - dw) / 2f;
        float y = (h - dh) / 2f;
        ui.panel("Export Shader Pack", x, y, dw, dh);

        float fx = x + 20;
        float fw = dw - 40;
        float fy = y + 56;
        fy += ui.textField(exportName, fx, fy, fw) + 10;
        fy += ui.textField(exportVersion, fx, fy, fw) + 14;
        r.text("Exports as an API mod with shaders/ inside. Players enable it,", fx, fy, 1.25f, Theme.TEXT_MUTED);
        r.text("then switch on Settings > Graphics > Enable Shaders.", fx, fy + 16, 1.25f, Theme.TEXT_MUTED);
        fy += 42;
        float buttonW = (fw - 12) / 2f;
        if (ui.button("Export", fx, fy, buttonW, 36, compileError == null)) {
            runExport();
        }
        if (ui.button("Cancel", fx + buttonW + 12, fy, buttonW, 36)) {
            exportDialogOpen = false;
        }
    }

    // ---------------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------------

    private void refreshFiles() {
        files = project.listShaderFiles();
        if (selectedFile == null || !files.contains(selectedFile)) {
            selectedFile = files.contains("composite.frag") ? "composite.frag"
                    : files.isEmpty() ? null : files.get(0);
        }
        loadCode();
    }

    private void selectFile(String file) {
        selectedFile = file;
        codeScroll = 0;
        loadCode();
    }

    private void loadCode() {
        if (selectedFile == null) {
            codeLines = List.of();
            return;
        }
        try {
            codeLines = Files.readAllLines(project.shadersDir().resolve(selectedFile));
        } catch (IOException e) {
            codeLines = List.of("Could not read " + selectedFile + ": " + e.getMessage());
        }
    }

    /** Writes the slider values back into pack.json, keeping anything else the file holds. */
    private void saveTuning() {
        openMenu = Menu.NONE;
        if (pack == null) {
            return;
        }
        Path file = project.shadersDir().resolve("pack.json");
        try {
            JsonObject manifest = readPackJson(file);
            manifest.addProperty("bloomPasses", pack.bloomPasses());
            manifest.addProperty("bloomThreshold", round(pack.bloomThreshold()));
            JsonObject uniforms = new JsonObject();
            for (Map.Entry<String, Float> entry : pack.uniforms().entrySet()) {
                uniforms.addProperty(entry.getKey(), round(entry.getValue()));
            }
            manifest.add("uniforms", uniforms);
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(manifest, writer);
            }
            tuningDirty = false;
            // The watcher would otherwise see our own write and recompile for nothing.
            stamps.put("pack.json", stamp(file));
            if ("pack.json".equals(selectedFile)) {
                loadCode();
            }
            setStatus("Saved tuning to pack.json");
        } catch (IOException | RuntimeException e) {
            Dialogs.error("Could not save pack.json", String.valueOf(e.getMessage()));
        }
    }

    private void addUniform(String name) {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            Dialogs.error("Not a valid name", "'" + name + "' cannot be a GLSL uniform name.");
            return;
        }
        if (pack.uniforms().containsKey(name)) {
            setStatus(name + " already exists");
            return;
        }
        pack.setUniform(name, 1f);
        ranges.put(name, rangeFor(1f));
        newUniformName.setText("");
        saveTuning();
        setStatus("Added " + name + " - declare it in composite.frag");
    }

    private static JsonObject readPackJson(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new JsonObject();
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        }
    }

    private static float round(float value) {
        return Math.round(value * 1000f) / 1000f;
    }

    /** Opens one file, or the whole shaders folder when {@code file} is null. */
    private void openInSystemEditor(String file) {
        Path target = file == null ? project.shadersDir() : project.shadersDir().resolve(file);
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(target.toFile());
                setStatus("Opened " + target.getFileName());
                return;
            }
        } catch (IOException | UnsupportedOperationException e) {
            // Fall through to telling the author where it is.
        }
        Dialogs.message("Location", target.toString());
    }

    private void runExport() {
        try {
            if (tuningDirty) {
                saveTuning();
            }
            project.save();
            Path zip = ModExporter.export(project, exportName.text(), ModType.API, exportVersion.text());
            exportDialogOpen = false;
            setStatus("Exported " + zip.getFileName());
            Dialogs.message("Export complete", "Shader pack exported to:\n" + zip
                    + "\n\nUpload this zip as a mod file on mod.io.");
        } catch (IOException e) {
            Dialogs.error("Export failed", String.valueOf(e.getMessage()));
        }
    }

    private boolean confirmLeave() {
        if (tuningDirty && Dialogs.confirm("Unsaved tuning", "Save the slider values to pack.json first?")) {
            saveTuning();
        }
        return true;
    }

    private void openProject(Path root) {
        try {
            CreatorProject opened = CreatorProject.open(root);
            confirmLeave();
            net.coffeebrewia.roastengine.creator.RecentProjects.remember(opened.root());
            engine.states().switchTo(LauncherState.editorFor(engine, opened));
        } catch (IOException e) {
            Dialogs.error("Could not open project", String.valueOf(e.getMessage()));
        }
    }

    private void closeProject() {
        confirmLeave();
        engine.states().switchTo(new LauncherState(engine));
    }

    private void setStatus(String text) {
        status = text;
        statusTimer = 4f;
        System.out.println("[Creator] " + text);
    }

    @Override
    public void exit() {
        engine.input().setCursorCaptured(false);
        post.dispose();
        pack = null;
        meshes.forEach(Mesh::dispose);
        meshes.clear();
        scene.clear();
        if (ground != null) {
            ground.dispose();
            ground = null;
        }
        if (worldShader != null) {
            worldShader.dispose();
            worldShader = null;
        }
    }
}

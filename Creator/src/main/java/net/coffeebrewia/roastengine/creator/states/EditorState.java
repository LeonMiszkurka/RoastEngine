package net.coffeebrewia.roastengine.creator.states;

import net.coffeebrewia.roastengine.core.Engine;
import net.coffeebrewia.roastengine.core.GameState;
import net.coffeebrewia.roastengine.creator.Dialogs;
import net.coffeebrewia.roastengine.render.model.ModelAsset;
import net.coffeebrewia.roastengine.render.model.ModelLibrary;
import net.coffeebrewia.roastengine.creator.export.ModExporter;
import net.coffeebrewia.roastengine.creator.export.ModType;
import net.coffeebrewia.roastengine.creator.project.CreatorProject;
import net.coffeebrewia.roastengine.creator.project.ProjectType;
import net.coffeebrewia.roastengine.creator.viewport.Gizmo;
import net.coffeebrewia.roastengine.modding.scene.ObjectAnimation;
import net.coffeebrewia.roastengine.modding.scene.SceneObject;
import net.coffeebrewia.roastengine.input.Input;
import net.coffeebrewia.roastengine.render.Camera;
import net.coffeebrewia.roastengine.render.Color;
import net.coffeebrewia.roastengine.render.Mesh;
import net.coffeebrewia.roastengine.render.Primitives;
import net.coffeebrewia.roastengine.render.Renderer2D;
import net.coffeebrewia.roastengine.render.ShaderProgram;
import net.coffeebrewia.roastengine.ui.TextField;
import net.coffeebrewia.roastengine.ui.Theme;
import net.coffeebrewia.roastengine.ui.Ui;
import org.joml.Intersectionf;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11C.*;

/**
 * Creator screen 2 - the editor.
 *
 * <pre>
 * +---------------------------------------------------+
 * | File  Scripting                    project  status |  menu bar
 * +-------------------------------------+-------------+
 * |                                     |  Inspector  |
 * |            3D viewport              |  (objects,  |
 * |   hold left mouse = look + WASD     |   transform,|
 * |   quick click = select object       |   configs)  |
 * |   G / R = move and rotate handles   |             |
 * +-------------------------------------+-------------+
 * | Assets: import models, add to scene               |
 * +---------------------------------------------------+
 * </pre>
 */
public final class EditorState implements GameState {

    private static final float MENU_HEIGHT = 30f;
    private static final float ASSETS_HEIGHT = 190f;
    private static final float INSPECTOR_WIDTH = 330f;
    private static final float MOUSE_SENSITIVITY = 0.0022f;
    private static final float MOVE_SPEED = 8f;
    private static final float CLICK_MAX_SECONDS = 0.25f;
    private static final Vector3f SKY = new Vector3f(0.53f, 0.78f, 0.95f);

    private enum Menu {NONE, FILE, SCRIPTING}

    private final Engine engine;
    private final Ui ui;
    private final Renderer2D r;
    private final CreatorProject project;
    private final ModelLibrary library;
    private final Camera camera = new Camera(0f, 4f, 14f);

    // Inspector fields
    private final TextField nameField = new TextField("Name", false, 48);
    private final TextField posX = new TextField("X", false, 12);
    private final TextField posY = new TextField("Y", false, 12);
    private final TextField posZ = new TextField("Z", false, 12);
    // All three rotations, because the rotate gizmo can turn about any axis.
    private final TextField rotX = new TextField("Rot X", false, 12);
    private final TextField rotY = new TextField("Rot Y", false, 12);
    private final TextField rotZ = new TextField("Rot Z", false, 12);
    private final TextField scaleField = new TextField("Scale", false, 12);
    // Animation fields, shown for mod projects
    private final TextField animSpeed = new TextField("Speed", false, 12);
    private final TextField animAmount = new TextField("Amount", false, 12);
    private final TextField animDelay = new TextField("Delay", false, 12);
    // Script parameters, shown for mod projects
    private final TextField paramKey = new TextField("Name", false, 32);
    private final TextField paramValue = new TextField("Value", false, 48);
    /** The inspector scrolls, because a mod's object has more to configure than a world's. */
    private float inspectorScroll;
    // Export dialog fields
    private final TextField exportName = new TextField("File name", false, 48);
    private final TextField exportVersion = new TextField("Version", false, 16);

    private ShaderProgram shader;
    private Mesh ground;
    private final Gizmo gizmo = new Gizmo();
    private Gizmo.Tool tool = Gizmo.Tool.SELECT;

    private List<String> assets = List.of();
    private String selectedAsset;
    private SceneObject selected;
    private String syncedSelectionId = "";

    private Menu openMenu = Menu.NONE;
    private boolean exportDialogOpen;
    private ModType exportType = ModType.WORLD;

    private boolean looking;
    private float lookSeconds;
    private float pressX;
    private float pressY;

    private float lastInspectorOverflow;
    private String status = "";
    private float statusTimer;
    private float deltaSeconds;

    public EditorState(Engine engine, CreatorProject project) {
        this.engine = engine;
        this.ui = engine.ui();
        this.r = engine.renderer2D();
        this.project = project;
        this.library = new ModelLibrary(project.assetsDir());
    }

    @Override
    public void enter() {
        engine.input().setCursorCaptured(false);
        shader = ShaderProgram.fromResources("/shaders/world");
        ground = Primitives.plane(400f, 0.55f, 0.58f, 0.52f);
        gizmo.create();
        exportType = project.type().defaultModType();
        refreshAssets();
        exportName.setText(CreatorProject.sanitize(project.name()).replace(' ', '-').toLowerCase());
        exportVersion.setText("1.0.0");
        // Dev aid: -PstartTool=move|rotate picks a tool and the first object, for screenshots.
        String startTool = System.getProperty("roastengine.startTool");
        if (startTool != null && !project.scene().objects.isEmpty()) {
            select(project.scene().objects.get(0));
            tool = "rotate".equalsIgnoreCase(startTool) ? Gizmo.Tool.ROTATE : Gizmo.Tool.MOVE;
        }
        setStatus("Opened " + project.name());
    }

    // ---------------------------------------------------------------------
    // Update
    // ---------------------------------------------------------------------

    @Override
    public void update(float dt) {
        this.deltaSeconds = dt;
        if (statusTimer > 0) {
            statusTimer -= dt;
        }
        Input input = engine.input();

        if (input.wasKeyPressed(GLFW_KEY_ESCAPE)) {
            if (gizmo.isDragging()) {
                gizmo.cancelDrag(selected);   // as in Blender: Escape puts it back
                setStatus("Cancelled");
            } else if (exportDialogOpen) {
                exportDialogOpen = false;
            } else if (openMenu != Menu.NONE) {
                openMenu = Menu.NONE;
            }
        }
        // Typing in a text field must not trigger editor shortcuts.
        boolean typing = ui.hasFocusedField();
        if (!typing && !exportDialogOpen) {
            if (input.isShortcutDown() && input.wasKeyPressed(GLFW_KEY_S)) {
                save();
            }
            if (selected != null
                    && (input.wasKeyPressed(GLFW_KEY_DELETE) || input.wasKeyPressed(GLFW_KEY_BACKSPACE))) {
                deleteSelected();
            }
            // Blender's keys: G grabs, R rotates, Q (or Escape once nothing is dragging) goes back
            // to plain selection.
            if (input.wasKeyPressed(GLFW_KEY_G)) {
                setTool(Gizmo.Tool.MOVE);
            }
            if (input.wasKeyPressed(GLFW_KEY_R)) {
                setTool(Gizmo.Tool.ROTATE);
            }
            if (input.wasKeyPressed(GLFW_KEY_Q)) {
                setTool(Gizmo.Tool.SELECT);
            }
        }

        updateViewportCamera(dt, typing);
    }

    /**
     * Viewport interaction, in priority order: dragging a transform handle beats looking around,
     * and looking around beats selecting, so grabbing an arrow never swings the camera.
     */
    private void updateViewportCamera(float dt, boolean typing) {
        Input input = engine.input();
        boolean blocked = exportDialogOpen || openMenu != Menu.NONE;
        float[] viewport = viewportRect();
        boolean inViewport = ui.isHovered(viewport[0], viewport[1], viewport[2], viewport[3]);

        // A live handle drag owns the mouse until the button comes up.
        if (gizmo.isDragging()) {
            if (!input.isMouseDown(GLFW_MOUSE_BUTTON_LEFT)) {
                gizmo.endDrag();
                setStatus(tool == Gizmo.Tool.MOVE ? "Moved " + selected.name : "Rotated " + selected.name);
            } else if (gizmo.updateDrag(selected, tool, ray(input.mouseX(), input.mouseY()),
                    input.isKeyDown(GLFW_KEY_LEFT_CONTROL) || input.isKeyDown(GLFW_KEY_RIGHT_CONTROL))) {
                project.markDirty();
                syncedSelectionId = "";   // pull the new numbers into the inspector fields
            }
            return;
        }

        // Highlight whichever handle the cursor is over, so it is obvious what a click grabs.
        if (!looking && !blocked && inViewport && selected != null) {
            gizmo.hitTest(selected, camera, tool, ray(input.mouseX(), input.mouseY()),
                    input.mouseX(), input.mouseY());
        }

        if (!looking && !blocked && input.wasMousePressed(GLFW_MOUSE_BUTTON_LEFT) && inViewport) {
            if (selected != null && gizmo.beginDrag(selected, camera, tool,
                    ray(input.mouseX(), input.mouseY()), input.mouseX(), input.mouseY())) {
                return;
            }
            looking = true;
            lookSeconds = 0f;
            pressX = input.mouseX();
            pressY = input.mouseY();
            input.setCursorCaptured(true);
        }

        if (!looking) {
            return;
        }
        lookSeconds += dt;

        if (!input.isMouseDown(GLFW_MOUSE_BUTTON_LEFT)) {
            looking = false;
            input.setCursorCaptured(false);
            // A quick click (rather than a drag) selects whatever is under the cursor.
            if (lookSeconds < CLICK_MAX_SECONDS) {
                pick(pressX, pressY);
            }
            return;
        }

        camera.rotate(input.mouseDeltaX() * MOUSE_SENSITIVITY, input.mouseDeltaY() * MOUSE_SENSITIVITY);
        if (typing) {
            return;
        }
        float forward = axis(input, GLFW_KEY_W, GLFW_KEY_S);
        float right = axis(input, GLFW_KEY_D, GLFW_KEY_A);
        float up = axis(input, GLFW_KEY_SPACE, GLFW_KEY_LEFT_SHIFT);
        float length = (float) Math.sqrt(forward * forward + right * right);
        if (length > 1f) {
            forward /= length;
            right /= length;
        }
        float speed = MOVE_SPEED * dt * (input.isKeyDown(GLFW_KEY_LEFT_CONTROL) ? 3f : 1f);
        camera.moveHorizontal(forward * speed, right * speed);
        camera.moveVertical(up * speed);
    }

    private static float axis(Input input, int positive, int negative) {
        return (input.isKeyDown(positive) ? 1f : 0f) - (input.isKeyDown(negative) ? 1f : 0f);
    }

    /**
     * The ray under the cursor, with the projection back the other way for the gizmo.
     *
     * <p>GL viewports have a bottom-left origin while the UI works top-down, which is the only
     * reason for the Y flips.
     */
    private Gizmo.ViewportRay ray(float mouseX, float mouseY) {
        float[] vp = viewportRect();
        float windowHeight = engine.window().height();
        Matrix4f viewProjection = new Matrix4f(camera.projectionMatrix(viewportAspect()))
                .mul(camera.viewMatrix());
        float[] glViewport = {vp[0], windowHeight - (vp[1] + vp[3]), vp[2], vp[3]};
        int[] rounded = {
                Math.round(glViewport[0]), Math.round(glViewport[1]),
                Math.round(glViewport[2]), Math.round(glViewport[3])};

        Vector3f origin = new Vector3f();
        Vector3f direction = new Vector3f();
        viewProjection.unprojectRay(mouseX, windowHeight - mouseY, rounded, origin, direction);
        return new Gizmo.ViewportRay(origin, direction, viewProjection, glViewport, windowHeight);
    }

    /** Picks the closest object whose bounding box the click ray hits. */
    private void pick(float mouseX, float mouseY) {
        Gizmo.ViewportRay cursor = ray(mouseX, mouseY);
        Vector3f origin = cursor.origin();
        Vector3f direction = cursor.direction();

        SceneObject best = null;
        float bestDistance = Float.MAX_VALUE;
        Vector2f hit = new Vector2f();
        for (SceneObject object : project.scene().objects) {
            Optional<ModelAsset> asset = library.get(object.asset);
            if (asset.isEmpty()) {
                continue;
            }
            // Test in the object's local space so rotation and scale are handled by the inverse.
            Matrix4f inverse = new Matrix4f(object.modelMatrix()).invert();
            Vector3f localOrigin = inverse.transformPosition(new Vector3f(origin));
            Vector3f localDirection = inverse.transformDirection(new Vector3f(direction));
            if (Intersectionf.intersectRayAab(localOrigin, localDirection,
                    asset.get().min(), asset.get().max(), hit) && hit.x < bestDistance) {
                bestDistance = hit.x;
                best = object;
            }
        }
        select(best);
    }

    private void setTool(Gizmo.Tool next) {
        if (tool == next) {
            return;
        }
        tool = next;
        setStatus(next == Gizmo.Tool.SELECT ? "Select tool" : next.label() + " tool  (" + next.key() + ")");
    }

    private void select(SceneObject object) {
        selected = object;
        if (object == null) {
            syncedSelectionId = "";
        }
    }

    // ---------------------------------------------------------------------
    // Render
    // ---------------------------------------------------------------------

    @Override
    public void render() {
        glClearColor(Theme.BACKGROUND.r(), Theme.BACKGROUND.g(), Theme.BACKGROUND.b(), 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        renderViewport();

        ui.begin(deltaSeconds);
        drawMenuBar();
        drawInspector();
        drawAssetsPanel();
        drawViewportOverlay();
        if (exportDialogOpen) {
            drawExportDialog();
        }
        ui.end();
    }

    /** The 3D scene, drawn into the viewport rectangle only. */
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
        glDisable(GL_BLEND);

        shader.bind();
        shader.setUniform("uProjection", camera.projectionMatrix(viewportAspect()));
        shader.setUniform("uView", camera.viewMatrix());
        shader.setUniform("uCameraPos", camera.position());
        shader.setUniform("uSkyColor", SKY);
        shader.setUniform("uFogDistance", 220f);
        shader.setUniform("uHighlight", 0);
        shader.setUniform("uHeadRadius", net.coffeebrewia.roastengine.render.PlayerModel.NO_HEAD_CLIP);
        shader.setUniform("uUseTexture", 0);

        shader.setUniform("uGrid", 1);
        shader.setUniform("uModel", new Matrix4f());
        ground.draw();
        shader.setUniform("uGrid", 0);

        for (SceneObject object : project.scene().objects) {
            library.get(object.asset).ifPresent(asset -> {
                shader.setUniform("uModel", object.modelMatrix());
                asset.draw(shader);
            });
        }

        // Selection highlight: redraw the selected object as a wireframe.
        if (selected != null) {
            library.get(selected.asset).ifPresent(asset -> {
                shader.setUniform("uHighlight", 1);
                shader.setUniform("uModel", new Matrix4f(selected.modelMatrix()).scale(1.02f));
                glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
                shader.setUniform("uUseTexture", 0);
                asset.drawGeometry();
                glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
                shader.setUniform("uHighlight", 0);
            });
            // Handles go last and ignore depth, so they sit on top of whatever they belong to.
            gizmo.draw(shader, selected, camera, tool);
        }
        shader.unbind();

        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_DEPTH_TEST);
        glViewport(0, 0, engine.window().framebufferWidth(), engine.window().framebufferHeight());
    }

    private float[] viewportRect() {
        float w = engine.window().width() - INSPECTOR_WIDTH;
        float h = engine.window().height() - MENU_HEIGHT - ASSETS_HEIGHT;
        return new float[]{0f, MENU_HEIGHT, Math.max(1f, w), Math.max(1f, h)};
    }

    private float viewportAspect() {
        float[] vp = viewportRect();
        return vp[3] <= 0 ? 1f : vp[2] / vp[3];
    }

    private void drawViewportOverlay() {
        float[] vp = viewportRect();
        Color ink = Color.rgb(0x10202C);

        // Tool bar, top-left of the viewport.
        float tx = vp[0] + 12;
        float ty = vp[1] + 10;
        for (Gizmo.Tool option : Gizmo.Tool.values()) {
            String label = option.label() + "  " + option.key();
            float bw = r.textWidth(label, 1.5f) + 22;
            if (ui.flatButton(label, tx, ty, bw, 26, tool == option)) {
                setTool(option);
            }
            r.outline(tx, ty, bw, 26, 1f, Theme.PANEL_BORDER);
            tx += bw + 6;
        }

        String hint;
        if (gizmo.isDragging()) {
            hint = tool.label() + " along " + gizmo.axisLabel() + "  |  Ctrl snaps  |  Esc cancels";
        } else if (looking) {
            hint = "Looking - WASD move, Space/Shift up/down, Ctrl sprint, release to stop";
        } else if (tool == Gizmo.Tool.SELECT) {
            hint = "Hold left mouse to look and move  |  quick click selects  |  G move, R rotate";
        } else {
            hint = "Drag a coloured handle to " + tool.label().toLowerCase(java.util.Locale.ROOT)
                    + " along that axis  |  Q back to select";
        }
        r.text(hint, vp[0] + 12, vp[1] + vp[3] - 22, 1.5f, ink);
        r.text(project.scene().objects.size() + " objects  |  " + project.type().displayName()
                        + " project  |  " + Math.round(engine.fps()) + " FPS",
                vp[0] + 12, vp[1] + vp[3] - 40, 1.5f, ink);
    }

    // ---------------------------------------------------------------------
    // Menu bar
    // ---------------------------------------------------------------------

    private void drawMenuBar() {
        float w = engine.window().width();
        r.rect(0, 0, w, MENU_HEIGHT, Theme.PANEL);
        r.rect(0, MENU_HEIGHT - 1, w, 1, Theme.PANEL_BORDER);

        if (ui.flatButton("File", 8, 0, 60, MENU_HEIGHT, openMenu == Menu.FILE)) {
            openMenu = openMenu == Menu.FILE ? Menu.NONE : Menu.FILE;
        }
        if (ui.flatButton("Scripting", 72, 0, 90, MENU_HEIGHT, openMenu == Menu.SCRIPTING)) {
            openMenu = openMenu == Menu.SCRIPTING ? Menu.NONE : Menu.SCRIPTING;
        }

        String title = project.name() + (project.isDirty() ? " *" : "");
        r.text(title, 180, 10, 1.75f, Theme.TEXT_MUTED);
        if (statusTimer > 0) {
            float tw = r.textWidth(status, 1.5f);
            r.text(status, w - tw - 16, 11, 1.5f, Theme.SUCCESS);
        }

        if (openMenu == Menu.FILE) {
            drawFileMenu(8, MENU_HEIGHT);
        } else if (openMenu == Menu.SCRIPTING) {
            drawScriptingMenu(72, MENU_HEIGHT);
        }
    }

    private void drawFileMenu(float x, float y) {
        String[] items = {"Save   (Ctrl+S)", "Export as...", "Open Project...", "Close Project"};
        int clicked = drawDropdown(x, y, 220, items);
        switch (clicked) {
            case 0 -> save();
            case 1 -> openExportDialog();
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
    }

    private void drawScriptingMenu(float x, float y) {
        String[] items = {"New Script...", "Refresh Scripts", "Open Scripts Folder"};
        int clicked = drawDropdown(x, y, 220, items);
        switch (clicked) {
            case 0 -> createScript();
            case 1 -> {
                openMenu = Menu.NONE;
                setStatus(project.listScripts().size() + " script(s) in project");
            }
            case 2 -> {
                openMenu = Menu.NONE;
                Dialogs.message("Scripts folder", project.scriptsDir().toString());
            }
            default -> {
            }
        }
    }

    /** @return index of the clicked item, or -1 */
    private int drawDropdown(float x, float y, float width, String[] items) {
        float itemHeight = 28;
        float height = items.length * itemHeight + 8;
        r.rect(x, y, width, height, Theme.ROW);
        r.outline(x, y, width, height, 1f, Theme.PANEL_BORDER);

        int clicked = -1;
        for (int i = 0; i < items.length; i++) {
            float itemY = y + 4 + i * itemHeight;
            if (ui.selectable(items[i], x + 4, itemY, width - 8, itemHeight, false)) {
                clicked = i;
            }
        }
        // Clicking anywhere else closes the menu.
        if (clicked < 0 && engine.input().wasMousePressed(GLFW_MOUSE_BUTTON_LEFT)
                && !ui.isHovered(x, y - MENU_HEIGHT, width, height + MENU_HEIGHT)) {
            openMenu = Menu.NONE;
        }
        return clicked;
    }

    // ---------------------------------------------------------------------
    // Inspector
    // ---------------------------------------------------------------------

    private void drawInspector() {
        float x = engine.window().width() - INSPECTOR_WIDTH;
        float y = MENU_HEIGHT;
        float w = INSPECTOR_WIDTH;
        float h = engine.window().height() - MENU_HEIGHT;
        r.rect(x, y, w, h, Theme.PANEL);
        r.rect(x, y, 1, h, Theme.PANEL_BORDER);

        if (ui.isHovered(x, y, w, h)) {
            inspectorScroll -= engine.input().scrollY() * 40f;
        }
        inspectorScroll = Math.max(0f, Math.min(inspectorScroll, lastInspectorOverflow));
        r.pushClip(x, y, w, h);

        float fx = x + 14;
        float fw = w - 28;
        float fy = y + 12 - inspectorScroll;

        // --- Scene object list ---
        r.text("SCENE", fx, fy, 1.5f, Theme.TEXT_MUTED);
        fy += 20;
        List<SceneObject> objects = project.scene().objects;
        float listHeight = Math.min(140, Math.max(28, objects.size() * 26));
        r.rect(fx, fy, fw, listHeight, Theme.INPUT);
        if (objects.isEmpty()) {
            r.text("No objects yet - add one from Assets", fx + 8, fy + 9, 1.25f, Theme.TEXT_MUTED);
        }
        for (int i = 0; i < objects.size() && i * 26 + 26 <= listHeight; i++) {
            SceneObject object = objects.get(i);
            if (ui.selectable(object.name, fx, fy + i * 26, fw, 26, object == selected)) {
                select(object);
            }
        }
        fy += listHeight + 16;

        if (selected == null) {
            r.text("Select an object in the viewport", fx, fy, 1.5f, Theme.TEXT_MUTED);
            r.text("or in the list above.", fx, fy + 18, 1.5f, Theme.TEXT_MUTED);
            r.popClip();
            lastInspectorOverflow = 0f;
            return;
        }
        syncFieldsToSelection();

        // --- Transform ---
        r.text("TRANSFORM", fx, fy, 1.5f, Theme.TEXT_MUTED);
        fy += 22;
        fy += ui.textField(nameField, fx, fy, fw) + 8;

        float third = (fw - 16) / 3f;
        ui.textField(posX, fx, fy, third);
        ui.textField(posY, fx + third + 8, fy, third);
        fy += ui.textField(posZ, fx + (third + 8) * 2, fy, third) + 8;

        ui.textField(rotX, fx, fy, third);
        ui.textField(rotY, fx + third + 8, fy, third);
        fy += ui.textField(rotZ, fx + (third + 8) * 2, fy, third) + 8;
        fy += ui.textField(scaleField, fx, fy, (fw - 8) / 2f) + 16;

        applyFieldsToSelection();

        // --- Configs ---
        r.text("CONFIGS", fx, fy, 1.5f, Theme.TEXT_MUTED);
        fy += 22;
        if (ui.checkbox("No Collision  (walk through)", fx, fy, !selected.collision)) {
            selected.collision = !selected.collision;
            project.markDirty();
        }
        fy += 26;
        if (ui.checkbox("Kills You  (touch = death)", fx, fy, selected.kills)) {
            selected.kills = !selected.kills;
            project.markDirty();
        }
        fy += 26;
        if (ui.checkbox("Is Slippery  (ice)", fx, fy, selected.slippery)) {
            selected.slippery = !selected.slippery;
            project.markDirty();
        }
        fy += 26;
        // An entity walks around on its own and can be punched into a ragdoll.
        if (ui.checkbox("Entity  (walks about, can be punched)", fx, fy, selected.entity)) {
            selected.entity = !selected.entity;
            project.markDirty();
        }
        fy += 32;

        // --- Script ---
        r.text("SCRIPT (.py)", fx, fy, 1.5f, Theme.TEXT_MUTED);
        fy += 20;
        String scriptLabel = selected.script.isBlank() ? "(none)" : selected.script;
        r.rect(fx, fy, fw, 28, Theme.INPUT);
        r.outline(fx, fy, fw, 28, 1f, Theme.PANEL_BORDER);
        r.text(r.ellipsize(scriptLabel, fw - 16, 1.5f), fx + 8, fy + 9, 1.5f, Theme.TEXT);
        fy += 34;
        float buttonW = (fw - 8) / 2f;
        if (ui.button("Next Script", fx, fy, buttonW, 28)) {
            cycleScript();
        }
        if (ui.button("New Script", fx + buttonW + 8, fy, buttonW, 28)) {
            createScript();
        }
        fy += 40;

        // A mod is dropped into somebody else's world, so its objects get to do things there.
        if (project.type().hasBehaviour()) {
            fy = drawAnimationSection(fx, fy, fw);
            fy = drawScriptParameters(fx, fy, fw);
        }

        if (ui.button("Delete Object", fx, fy, fw, 30)) {
            deleteSelected();
        }
        fy += 40;

        r.popClip();
        // How far past the panel the content ran, which is how far it may scroll.
        lastInspectorOverflow = Math.max(0f, (fy + inspectorScroll) - (y + h) + 12);
    }

    /** ANIMATION: the canned motions the engine plays, for mod projects. */
    private float drawAnimationSection(float fx, float fy, float fw) {
        ObjectAnimation animation = selected.animation;
        r.text("ANIMATION", fx, fy, 1.5f, Theme.TEXT_MUTED);
        fy += 22;

        float half = (fw - 8) / 2f;
        String kind = animation.type == null ? ObjectAnimation.NONE : animation.type;
        if (ui.button(capitalise(kind), fx, fy, half, 28)) {
            String[] kinds = ObjectAnimation.kinds();
            int index = 0;
            for (int i = 0; i < kinds.length; i++) {
                if (kinds[i].equalsIgnoreCase(kind)) {
                    index = i;
                }
            }
            animation.type = kinds[(index + 1) % kinds.length];
            project.markDirty();
        }
        if (ui.button("Axis " + animation.axis.toUpperCase(java.util.Locale.ROOT),
                fx + half + 8, fy, half, 28, animation.isActive())) {
            animation.axis = switch (animation.axis.toLowerCase(java.util.Locale.ROOT)) {
                case "x" -> "y";
                case "y" -> "z";
                default -> "x";
            };
            project.markDirty();
        }
        fy += 36;

        if (!animation.isActive()) {
            r.text("No motion - tap the button to cycle.", fx, fy, 1.25f, Theme.TEXT_MUTED);
            return fy + 28;
        }

        float third = (fw - 16) / 3f;
        ui.textField(animSpeed, fx, fy, third);
        ui.textField(animAmount, fx + third + 8, fy, third);
        fy += ui.textField(animDelay, fx + (third + 8) * 2, fy, third) + 6;

        float speed = parse(animSpeed.text(), animation.speed);
        float amount = parse(animAmount.text(), animation.amount);
        float delay = Math.max(0f, parse(animDelay.text(), animation.delay));
        if (speed != animation.speed || amount != animation.amount || delay != animation.delay) {
            animation.speed = speed;
            animation.amount = amount;
            animation.delay = delay;
            project.markDirty();
        }
        r.text(r.ellipsize(animation.describe(), fw, 1.25f), fx, fy, 1.25f, Theme.ACCENT);
        fy += 16;
        r.text(r.ellipsize("Plays in game. Collision stays where you placed it.", fw, 1.25f),
                fx, fy, 1.25f, Theme.TEXT_MUTED);
        return fy + 26;
    }

    /** ADVANCED SCRIPTING: values handed to the script, so one script can drive many objects. */
    private float drawScriptParameters(float fx, float fy, float fw) {
        r.text("SCRIPT PARAMETERS", fx, fy, 1.5f, Theme.TEXT_MUTED);
        fy += 20;
        if (selected.script.isBlank()) {
            r.text("Attach a script above to give it values.", fx, fy, 1.25f, Theme.TEXT_MUTED);
            return fy + 28;
        }

        for (java.util.Map.Entry<String, String> entry
                : new java.util.ArrayList<>(selected.scriptParams.entrySet())) {
            r.rect(fx, fy, fw - 32, 24, Theme.INPUT);
            r.text(r.ellipsize(entry.getKey() + " = " + entry.getValue(), fw - 44, 1.25f),
                    fx + 6, fy + 8, 1.25f, Theme.TEXT);
            if (ui.button("X", fx + fw - 28, fy, 28, 24)) {
                selected.scriptParams.remove(entry.getKey());
                project.markDirty();
            }
            fy += 28;
        }

        float half = (fw - 8) / 2f;
        ui.textField(paramKey, fx, fy, half);
        fy += ui.textField(paramValue, fx + half + 8, fy, half) + 6;
        if (ui.button("Add Parameter", fx, fy, fw, 26, !paramKey.text().isBlank())) {
            selected.scriptParams.put(paramKey.text().trim(), paramValue.text().trim());
            paramKey.setText("");
            paramValue.setText("");
            project.markDirty();
        }
        return fy + 36;
    }

    private static String capitalise(String value) {
        return value.isEmpty() ? value
                : value.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + value.substring(1);
    }

    /** Copies the selected object's values into the text fields when the selection changes. */
    private void syncFieldsToSelection() {
        if (selected.id.equals(syncedSelectionId)) {
            return;
        }
        syncedSelectionId = selected.id;
        nameField.setText(selected.name);
        posX.setText(format(selected.position.x));
        posY.setText(format(selected.position.y));
        posZ.setText(format(selected.position.z));
        rotX.setText(format(selected.rotation.x));
        rotY.setText(format(selected.rotation.y));
        rotZ.setText(format(selected.rotation.z));
        scaleField.setText(format(selected.scale));
        animSpeed.setText(format(selected.animation.speed));
        animAmount.setText(format(selected.animation.amount));
        animDelay.setText(format(selected.animation.delay));
    }

    /** Parses the text fields back into the object; invalid text is simply ignored. */
    private void applyFieldsToSelection() {
        if (!nameField.text().isBlank() && !nameField.text().equals(selected.name)) {
            selected.name = nameField.text();
            project.markDirty();
        }
        float newX = parse(posX.text(), selected.position.x);
        float newY = parse(posY.text(), selected.position.y);
        float newZ = parse(posZ.text(), selected.position.z);
        float newRotX = parse(rotX.text(), selected.rotation.x);
        float newRotY = parse(rotY.text(), selected.rotation.y);
        float newRotZ = parse(rotZ.text(), selected.rotation.z);
        float newScale = Math.max(0.001f, parse(scaleField.text(), selected.scale));
        if (newX != selected.position.x || newY != selected.position.y || newZ != selected.position.z
                || newRotX != selected.rotation.x || newRotY != selected.rotation.y
                || newRotZ != selected.rotation.z || newScale != selected.scale) {
            selected.position.set(newX, newY, newZ);
            selected.rotation.set(newRotX, newRotY, newRotZ);
            selected.scale = newScale;
            project.markDirty();
        }
    }

    private static float parse(String text, float fallback) {
        try {
            return text.isBlank() || text.equals("-") ? fallback : Float.parseFloat(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String format(float value) {
        return value == Math.rint(value) ? String.valueOf((int) value) : String.format("%.2f", value);
    }

    // ---------------------------------------------------------------------
    // Assets panel
    // ---------------------------------------------------------------------

    private void drawAssetsPanel() {
        float y = engine.window().height() - ASSETS_HEIGHT;
        float w = engine.window().width() - INSPECTOR_WIDTH;
        r.rect(0, y, w, ASSETS_HEIGHT, Theme.PANEL);
        r.rect(0, y, w, 1, Theme.PANEL_BORDER);

        r.text("ASSETS", 14, y + 12, 1.75f, Theme.TEXT);
        if (ui.button("Import Model", 100, y + 6, 130, 26)) {
            importModel();
        }
        if (ui.button("Add to Scene", 238, y + 6, 130, 26, selectedAsset != null)) {
            addSelectedAssetToScene();
        }
        if (ui.button("Refresh", 376, y + 6, 90, 26)) {
            refreshAssets();
        }
        r.text("Supported: .obj  .glb  .gltf  .fbx", 480, y + 13, 1.25f, Theme.TEXT_MUTED);

        float listY = y + 40;
        float listH = ASSETS_HEIGHT - 48;
        if (assets.isEmpty()) {
            r.text("No models yet. Click \"Import Model\" to add an .obj or .glb file.",
                    14, listY + 12, 1.5f, Theme.TEXT_MUTED);
            return;
        }
        // Simple column layout so many assets stay visible.
        float columnW = 240;
        int rows = Math.max(1, (int) (listH / 26));
        for (int i = 0; i < assets.size(); i++) {
            float ax = 14 + (i / rows) * columnW;
            float ay = listY + (i % rows) * 26;
            if (ax + columnW > w) {
                break;
            }
            String asset = assets.get(i);
            if (ui.selectable(asset, ax, ay, columnW - 10, 24, asset.equals(selectedAsset))) {
                selectedAsset = asset;
            }
        }
    }

    private void refreshAssets() {
        assets = project.listAssets();
        if (selectedAsset != null && !assets.contains(selectedAsset)) {
            selectedAsset = null;
        }
        if (selectedAsset == null && !assets.isEmpty()) {
            selectedAsset = assets.get(0);
        }
    }

    private void importModel() {
        Optional<Path> chosen = Dialogs.openModel(Path.of(System.getProperty("user.home")));
        if (chosen.isEmpty()) {
            return;
        }
        try {
            String fileName = project.importModel(chosen.get());
            refreshAssets();
            selectedAsset = fileName;
            // Load straight away so import errors surface immediately.
            if (library.get(fileName).isPresent()) {
                setStatus("Imported " + fileName);
            } else {
                setStatus("Imported " + fileName + " but it could not be read");
            }
        } catch (IOException e) {
            Dialogs.error("Import failed", e.getMessage());
        }
    }

    private void addSelectedAssetToScene() {
        if (selectedAsset == null) {
            return;
        }
        Optional<ModelAsset> asset = library.get(selectedAsset);
        if (asset.isEmpty()) {
            Dialogs.error("Cannot add model", "Could not load " + selectedAsset);
            return;
        }
        // Drop it a few metres in front of the camera, resting on the ground.
        Vector3f position = new Vector3f(
                (float) Math.sin(camera.yaw()) * 6f + camera.position().x,
                0f,
                -(float) Math.cos(camera.yaw()) * 6f + camera.position().z);

        SceneObject object = new SceneObject(CreatorProject.stripExtension(selectedAsset), selectedAsset);
        object.position.set(position);
        project.scene().objects.add(object);
        project.markDirty();
        select(object);
        setStatus("Added " + object.name);
    }

    // ---------------------------------------------------------------------
    // Export dialog
    // ---------------------------------------------------------------------

    private void openExportDialog() {
        openMenu = Menu.NONE;
        exportDialogOpen = true;
        ui.clearFocus();
    }

    private void drawExportDialog() {
        float w = engine.window().width();
        float h = engine.window().height();
        ui.modalBackdrop(w, h);

        float dw = 420;
        float dh = 330;
        float x = (w - dw) / 2f;
        float y = (h - dh) / 2f;
        ui.panel("Export Mod", x, y, dw, dh);

        float fx = x + 20;
        float fw = dw - 40;
        float fy = y + 56;

        fy += ui.textField(exportName, fx, fy, fw) + 10;

        r.text("Mod type", fx, fy, 1.5f, Theme.TEXT_MUTED);
        fy += 18;
        if (ui.button(exportType.displayName(), fx, fy, 120, 30)) {
            exportType = exportType.next();
        }
        r.text(r.ellipsize(exportType.description(), fw - 132, 1.25f), fx + 132, fy + 10, 1.25f, Theme.TEXT_MUTED);
        fy += 42;

        fy += ui.textField(exportVersion, fx, fy, fw) + 18;

        r.text(r.ellipsize("Saves to " + project.exportsDir(), fw, 1.25f), fx, fy, 1.25f, Theme.TEXT_MUTED);
        fy += 26;

        float buttonW = (fw - 12) / 2f;
        if (ui.button("Export", fx, fy, buttonW, 36)) {
            runExport();
        }
        if (ui.button("Cancel", fx + buttonW + 12, fy, buttonW, 36)) {
            exportDialogOpen = false;
        }
    }

    private void runExport() {
        try {
            save();
            Path zip = ModExporter.export(project, exportName.text(), exportType, exportVersion.text());
            exportDialogOpen = false;
            setStatus("Exported " + zip.getFileName());
            Dialogs.message("Export complete", "Mod exported to:\n" + zip
                    + "\n\nUpload this zip as a mod file on mod.io.");
        } catch (IOException e) {
            Dialogs.error("Export failed", String.valueOf(e.getMessage()));
        }
    }

    // ---------------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------------

    private void save() {
        openMenu = Menu.NONE;
        try {
            project.save();
            setStatus("Saved");
        } catch (IOException e) {
            Dialogs.error("Save failed", String.valueOf(e.getMessage()));
        }
    }

    private void createScript() {
        openMenu = Menu.NONE;
        String name = TinyFileDialogs.tinyfd_inputBox("New script", "Script file name:", "my_script");
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            String fileName = project.createScript(name);
            if (selected != null) {
                selected.script = fileName;
                project.markDirty();
            }
            setStatus("Created " + fileName);
        } catch (IOException e) {
            Dialogs.error("Could not create script", String.valueOf(e.getMessage()));
        }
    }

    private void cycleScript() {
        List<String> scripts = project.listScripts();
        if (scripts.isEmpty()) {
            setStatus("No scripts yet - use New Script");
            return;
        }
        int index = scripts.indexOf(selected.script);
        // (none) -> first -> ... -> last -> (none)
        if (index < 0) {
            selected.script = scripts.get(0);
        } else if (index + 1 < scripts.size()) {
            selected.script = scripts.get(index + 1);
        } else {
            selected.script = "";
        }
        project.markDirty();
    }

    private void deleteSelected() {
        if (selected == null) {
            return;
        }
        project.scene().objects.remove(selected);
        project.markDirty();
        setStatus("Deleted " + selected.name);
        select(null);
    }

    private void openProject(Path root) {
        try {
            CreatorProject opened = CreatorProject.open(root);
            net.coffeebrewia.roastengine.creator.RecentProjects.remember(opened.root());
            engine.states().switchTo(new EditorState(engine, opened));
        } catch (IOException e) {
            Dialogs.error("Could not open project", String.valueOf(e.getMessage()));
        }
    }

    private void closeProject() {
        if (project.isDirty() && Dialogs.confirm("Unsaved changes", "Save before closing?")) {
            save();
        }
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
        gizmo.dispose();
        library.dispose();
        if (ground != null) {
            ground.dispose();
            ground = null;
        }
        if (shader != null) {
            shader.dispose();
            shader = null;
        }
    }
}

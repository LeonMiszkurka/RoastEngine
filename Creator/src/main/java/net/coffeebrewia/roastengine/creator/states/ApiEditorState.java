package net.coffeebrewia.roastengine.creator.states;

import net.coffeebrewia.roastengine.core.Engine;
import net.coffeebrewia.roastengine.core.GameState;
import net.coffeebrewia.roastengine.creator.Dialogs;
import net.coffeebrewia.roastengine.creator.export.ModExporter;
import net.coffeebrewia.roastengine.creator.export.ModType;
import net.coffeebrewia.roastengine.creator.project.CreatorProject;
import net.coffeebrewia.roastengine.input.Input;
import net.coffeebrewia.roastengine.render.Renderer2D;
import net.coffeebrewia.roastengine.ui.TextField;
import net.coffeebrewia.roastengine.ui.Theme;
import net.coffeebrewia.roastengine.ui.Ui;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11C.*;

/**
 * The editor for an API project: scripting and UI, with no 3D viewport.
 *
 * <p>An API ships behaviour other mods build on, so there is no scene to place objects in and
 * nothing to look at. What matters instead is the scripts, and what each of them offers to the
 * mods that will depend on it - which is what this screen shows.
 *
 * <pre>
 * +---------------------------------------------------+
 * | File  Scripting                    project  status |
 * +------------------+--------------------------------+
 * |  SCRIPTS         |  the script, read-only          |
 * |  my_api.py       |                                 |
 * |  helpers.py      |                                 |
 * |                  +--------------------------------+
 * |  New / Delete    |  ENTRY POINTS found in it       |
 * +------------------+--------------------------------+
 * </pre>
 *
 * <p>Editing happens in whatever editor the author already uses - <b>Open in Editor</b> hands the
 * file to the system - and the preview here reloads whenever the file changes on disk.
 */
public final class ApiEditorState implements GameState {

    private static final float MENU_HEIGHT = 30f;
    private static final float LIST_WIDTH = 300f;
    private static final float DETAIL_HEIGHT = 210f;
    private static final float LINE_HEIGHT = 15f;
    /** Preview cap: an API script long enough to exceed this wants a real editor anyway. */
    private static final int MAX_PREVIEW_LINES = 4000;
    private static final Pattern DEF = Pattern.compile("^\\s*def\\s+(\\w+)\\s*\\(([^)]*)\\)");

    private enum Menu {NONE, FILE, SCRIPTING}

    private final Engine engine;
    private final Ui ui;
    private final Renderer2D r;
    private final CreatorProject project;

    private final TextField exportName = new TextField("File name", false, 48);
    private final TextField exportVersion = new TextField("Version", false, 16);

    private List<String> scripts = List.of();
    private String selectedScript;
    /** Contents of the selected script, split into lines for drawing. */
    private List<String> previewLines = List.of();
    private List<String> entryPoints = List.of();
    private long previewStamp;
    private float scroll;

    private Menu openMenu = Menu.NONE;
    private boolean exportDialogOpen;
    private String status = "";
    private float statusTimer;
    private float deltaSeconds;

    public ApiEditorState(Engine engine, CreatorProject project) {
        this.engine = engine;
        this.ui = engine.ui();
        this.r = engine.renderer2D();
        this.project = project;
    }

    @Override
    public void enter() {
        engine.input().setCursorCaptured(false);
        exportName.setText(CreatorProject.sanitize(project.name()).replace(' ', '-').toLowerCase());
        exportVersion.setText("1.0.0");
        refreshScripts();
        setStatus("Opened " + project.name() + "  (API project)");
    }

    @Override
    public void update(float dt) {
        this.deltaSeconds = dt;
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
        if (!ui.hasFocusedField() && !exportDialogOpen
                && input.isShortcutDown() && input.wasKeyPressed(GLFW_KEY_S)) {
            save();
        }
        reloadPreviewIfChanged();
    }

    @Override
    public void render() {
        glClearColor(Theme.BACKGROUND.r(), Theme.BACKGROUND.g(), Theme.BACKGROUND.b(), 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        ui.begin(deltaSeconds);
        drawMenuBar();
        drawScriptList();
        drawPreview();
        drawDetails();
        if (exportDialogOpen) {
            drawExportDialog();
        }
        ui.end();
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

        r.text(project.name() + "   [API]" + (project.isDirty() ? " *" : ""), 180, 10, 1.75f, Theme.TEXT_MUTED);
        if (statusTimer > 0) {
            float tw = r.textWidth(status, 1.5f);
            r.text(status, w - tw - 16, 11, 1.5f, Theme.SUCCESS);
        }

        if (openMenu == Menu.FILE) {
            int clicked = drawDropdown(8, MENU_HEIGHT, 220,
                    new String[]{"Save   (Ctrl+S)", "Export as...", "Open Project...", "Close Project"});
            switch (clicked) {
                case 0 -> save();
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
        } else if (openMenu == Menu.SCRIPTING) {
            int clicked = drawDropdown(72, MENU_HEIGHT, 220,
                    new String[]{"New Script...", "Refresh", "Open Scripts Folder"});
            switch (clicked) {
                case 0 -> createScript();
                case 1 -> {
                    openMenu = Menu.NONE;
                    refreshScripts();
                    setStatus(scripts.size() + " script(s)");
                }
                case 2 -> {
                    openMenu = Menu.NONE;
                    Dialogs.message("Scripts folder", project.scriptsDir().toString());
                }
                default -> {
                }
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
            if (ui.selectable(items[i], x + 4, y + 4 + i * itemHeight, width - 8, itemHeight, false)) {
                clicked = i;
            }
        }
        if (clicked < 0 && engine.input().wasMousePressed(GLFW_MOUSE_BUTTON_LEFT)
                && !ui.isHovered(x, y - MENU_HEIGHT, width, height + MENU_HEIGHT)) {
            openMenu = Menu.NONE;
        }
        return clicked;
    }

    // ---------------------------------------------------------------------
    // Panels
    // ---------------------------------------------------------------------

    private void drawScriptList() {
        float y = MENU_HEIGHT;
        float h = engine.window().height() - MENU_HEIGHT;
        r.rect(0, y, LIST_WIDTH, h, Theme.PANEL);
        r.rect(LIST_WIDTH - 1, y, 1, h, Theme.PANEL_BORDER);

        float fx = 14;
        float fw = LIST_WIDTH - 28;
        float fy = y + 14;
        r.text("SCRIPTS", fx, fy, 1.5f, Theme.TEXT_MUTED);
        fy += 22;

        float buttonW = (fw - 8) / 2f;
        if (ui.button("New", fx, fy, buttonW, 28)) {
            createScript();
        }
        if (ui.button("Refresh", fx + buttonW + 8, fy, buttonW, 28)) {
            refreshScripts();
        }
        fy += 38;

        if (scripts.isEmpty()) {
            r.text("No scripts yet.", fx, fy + 4, 1.5f, Theme.TEXT_MUTED);
            r.text("An API is its scripts - start", fx, fy + 24, 1.25f, Theme.TEXT_MUTED);
            r.text("with New.", fx, fy + 40, 1.25f, Theme.TEXT_MUTED);
            return;
        }
        float listBottom = y + h - 90;
        for (String script : scripts) {
            if (fy + 28 > listBottom) {
                break;
            }
            if (ui.selectable(script, fx, fy, fw, 28, script.equals(selectedScript))) {
                selectScript(script);
            }
            fy += 30;
        }

        float actionsY = y + h - 78;
        if (ui.button("Open in Editor", fx, actionsY, fw, 30, selectedScript != null)) {
            openInSystemEditor();
        }
        if (ui.button("Delete Script", fx, actionsY + 36, fw, 30, selectedScript != null)) {
            deleteScript();
        }
    }

    private void drawPreview() {
        float x = LIST_WIDTH;
        float y = MENU_HEIGHT;
        float w = engine.window().width() - LIST_WIDTH;
        float h = engine.window().height() - MENU_HEIGHT - DETAIL_HEIGHT;
        r.rect(x, y, w, h, Theme.BACKGROUND);

        if (selectedScript == null) {
            r.textCentered("Select a script on the left.", x, y, w, h, 1.75f, Theme.TEXT_MUTED);
            return;
        }
        r.text(selectedScript + "   (read-only preview)", x + 16, y + 12, 1.5f, Theme.TEXT_MUTED);
        float top = y + 34;
        float viewHeight = h - 44;

        if (ui.isHovered(x, top, w, viewHeight)) {
            scroll -= engine.input().scrollY() * 3 * LINE_HEIGHT;
        }
        float maxScroll = Math.max(0, previewLines.size() * LINE_HEIGHT - viewHeight);
        scroll = Math.max(0, Math.min(maxScroll, scroll));

        r.pushClip(x, top, w, viewHeight);
        int first = (int) (scroll / LINE_HEIGHT);
        int visible = (int) (viewHeight / LINE_HEIGHT) + 2;
        for (int i = first; i < Math.min(previewLines.size(), first + visible); i++) {
            float lineY = top + i * LINE_HEIGHT - scroll;
            r.text(String.format("%4d", i + 1), x + 12, lineY, 1.25f, Theme.PANEL_BORDER);
            String line = previewLines.get(i);
            // Comments dimmed, everything else plain: enough to read the shape of a file.
            boolean comment = line.stripLeading().startsWith("#");
            r.text(line, x + 56, lineY, 1.25f, comment ? Theme.TEXT_MUTED : Theme.TEXT);
        }
        r.popClip();
    }

    /** What this API offers the mods that depend on it, read straight out of the file. */
    private void drawDetails() {
        float x = LIST_WIDTH;
        float y = engine.window().height() - DETAIL_HEIGHT;
        float w = engine.window().width() - LIST_WIDTH;
        r.rect(x, y, w, DETAIL_HEIGHT, Theme.PANEL);
        r.rect(x, y, w, 1, Theme.PANEL_BORDER);

        float fx = x + 16;
        float fy = y + 14;
        r.text("ENTRY POINTS", fx, fy, 1.5f, Theme.TEXT_MUTED);
        fy += 22;

        if (selectedScript == null) {
            r.text("No script selected.", fx, fy, 1.5f, Theme.TEXT_MUTED);
        } else if (entryPoints.isEmpty()) {
            r.text("No 'def' found in " + selectedScript + ".", fx, fy, 1.5f, Theme.TEXT_MUTED);
            r.text("A mod calls into an API through its functions, so give it at least one.",
                    fx, fy + 20, 1.25f, Theme.TEXT_MUTED);
        } else {
            float columnW = 320;
            for (int i = 0; i < entryPoints.size(); i++) {
                float ex = fx + (i / 5) * columnW;
                float ey = fy + (i % 5) * 20;
                if (ex + columnW > x + w - 220) {
                    break;
                }
                r.text(r.ellipsize(entryPoints.get(i), columnW - 12, 1.5f), ex, ey, 1.5f, Theme.TEXT);
            }
        }

        // Right-hand side: what the export will contain.
        float px = x + w - 210;
        r.text("EXPORT", px, y + 14, 1.5f, Theme.TEXT_MUTED);
        r.text(scripts.size() + " script(s), no scene", px, y + 36, 1.25f, Theme.TEXT_MUTED);
        r.text("Type: API", px, y + 52, 1.25f, Theme.TEXT_MUTED);
        if (ui.button("Export as...", px, y + 72, 180, 32)) {
            exportDialogOpen = true;
            ui.clearFocus();
        }
        if (ui.button("Save", px, y + 110, 180, 30)) {
            save();
        }
    }

    private void drawExportDialog() {
        float w = engine.window().width();
        float h = engine.window().height();
        ui.modalBackdrop(w, h);

        float dw = 420;
        float dh = 260;
        float x = (w - dw) / 2f;
        float y = (h - dh) / 2f;
        ui.panel("Export API", x, y, dw, dh);

        float fx = x + 20;
        float fw = dw - 40;
        float fy = y + 56;
        fy += ui.textField(exportName, fx, fy, fw) + 10;
        fy += ui.textField(exportVersion, fx, fy, fw) + 14;
        r.text("Ships every script in the project, and no scene.", fx, fy, 1.25f, Theme.TEXT_MUTED);
        fy += 18;
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

    // ---------------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------------

    private void refreshScripts() {
        scripts = project.listScripts();
        if (selectedScript != null && !scripts.contains(selectedScript)) {
            selectedScript = null;
        }
        if (selectedScript == null && !scripts.isEmpty()) {
            selectScript(scripts.get(0));
        } else if (selectedScript != null) {
            loadPreview();
        }
    }

    private void selectScript(String script) {
        selectedScript = script;
        scroll = 0;
        loadPreview();
    }

    /** Re-reads the file when its timestamp moves, so edits made elsewhere show up here. */
    private void reloadPreviewIfChanged() {
        if (selectedScript == null) {
            return;
        }
        Path file = project.scriptsDir().resolve(selectedScript);
        try {
            long stamp = Files.getLastModifiedTime(file).toMillis();
            if (stamp != previewStamp) {
                loadPreview();
            }
        } catch (IOException e) {
            // The file went away; the next refresh will drop it from the list.
        }
    }

    private void loadPreview() {
        Path file = project.scriptsDir().resolve(selectedScript);
        try {
            previewStamp = Files.getLastModifiedTime(file).toMillis();
            List<String> lines = Files.readAllLines(file);
            previewLines = lines.size() > MAX_PREVIEW_LINES
                    ? new ArrayList<>(lines.subList(0, MAX_PREVIEW_LINES)) : lines;
            entryPoints = findEntryPoints(previewLines);
        } catch (IOException e) {
            previewLines = List.of("Could not read " + selectedScript + ": " + e.getMessage());
            entryPoints = List.of();
        }
    }

    private static List<String> findEntryPoints(List<String> lines) {
        List<String> found = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = DEF.matcher(line);
            if (matcher.find()) {
                String args = matcher.group(2).trim();
                found.add(matcher.group(1) + "(" + args + ")");
            }
        }
        return found;
    }

    private void createScript() {
        openMenu = Menu.NONE;
        String name = TinyFileDialogs.tinyfd_inputBox("New script", "Script file name:", "my_api");
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            String fileName = project.createScript(name);
            refreshScripts();
            selectScript(fileName);
            setStatus("Created " + fileName);
        } catch (IOException e) {
            Dialogs.error("Could not create script", String.valueOf(e.getMessage()));
        }
    }

    private void deleteScript() {
        if (selectedScript == null
                || !Dialogs.confirm("Delete script", "Delete " + selectedScript + " from disk?")) {
            return;
        }
        try {
            Files.deleteIfExists(project.scriptsDir().resolve(selectedScript));
            setStatus("Deleted " + selectedScript);
            selectedScript = null;
            refreshScripts();
        } catch (IOException e) {
            Dialogs.error("Could not delete script", String.valueOf(e.getMessage()));
        }
    }

    /** Hands the file to whatever the author's system opens .py with. */
    private void openInSystemEditor() {
        Path file = project.scriptsDir().resolve(selectedScript);
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(file.toFile());
                setStatus("Opened " + selectedScript);
                return;
            }
        } catch (IOException | UnsupportedOperationException e) {
            // Fall through to simply telling the author where the file is.
        }
        Dialogs.message("Script location", file.toString());
    }

    private void save() {
        openMenu = Menu.NONE;
        try {
            project.save();
            setStatus("Saved");
        } catch (IOException e) {
            Dialogs.error("Save failed", String.valueOf(e.getMessage()));
        }
    }

    private void runExport() {
        try {
            save();
            Path zip = ModExporter.export(project, exportName.text(), ModType.API, exportVersion.text());
            exportDialogOpen = false;
            setStatus("Exported " + zip.getFileName());
            Dialogs.message("Export complete", "API exported to:\n" + zip
                    + "\n\nUpload this zip as a mod file on mod.io.");
        } catch (IOException e) {
            Dialogs.error("Export failed", String.valueOf(e.getMessage()));
        }
    }

    private void openProject(Path root) {
        try {
            CreatorProject opened = CreatorProject.open(root);
            net.coffeebrewia.roastengine.creator.RecentProjects.remember(opened.root());
            engine.states().switchTo(LauncherState.editorFor(engine, opened));
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
}

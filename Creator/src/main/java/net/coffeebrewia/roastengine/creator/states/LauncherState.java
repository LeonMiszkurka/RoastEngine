package net.coffeebrewia.roastengine.creator.states;

import net.coffeebrewia.roastengine.core.Engine;
import net.coffeebrewia.roastengine.core.GameState;
import net.coffeebrewia.roastengine.creator.Dialogs;
import net.coffeebrewia.roastengine.creator.RecentProjects;
import net.coffeebrewia.roastengine.creator.project.CreatorProject;
import net.coffeebrewia.roastengine.creator.project.ProjectType;
import net.coffeebrewia.roastengine.render.Renderer2D;
import net.coffeebrewia.roastengine.ui.TextField;
import net.coffeebrewia.roastengine.ui.Theme;
import net.coffeebrewia.roastengine.ui.Ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.lwjgl.opengl.GL11C.*;

/** Creator screen 1 - create a new project, open an existing one, or pick a recent one. */
public final class LauncherState implements GameState {

    private final Engine engine;
    private final Ui ui;
    private final Renderer2D r;

    private final TextField nameField = new TextField("Project name", false, 48);
    private Path location = Path.of(System.getProperty("user.home"), "RoastEngineProjects");
    private ProjectType projectType = ProjectType.WORLD;
    private List<Path> recent = List.of();
    private String message = "";
    private boolean messageIsError;
    private float deltaSeconds;

    public LauncherState(Engine engine) {
        this.engine = engine;
        this.ui = engine.ui();
        this.r = engine.renderer2D();
    }

    @Override
    public void enter() {
        engine.input().setCursorCaptured(false);
        recent = RecentProjects.load();
        if (nameField.text().isBlank()) {
            nameField.setText("My Mod Project");
        }
    }

    @Override
    public void update(float deltaSeconds) {
        this.deltaSeconds = deltaSeconds;
    }

    @Override
    public void render() {
        glClearColor(Theme.BACKGROUND.r(), Theme.BACKGROUND.g(), Theme.BACKGROUND.b(), 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        float w = engine.window().width();
        float h = engine.window().height();
        ui.begin(deltaSeconds);

        r.text("RoastEngine Creator", 40, 30, 4f, Theme.TEXT);
        r.text("Build worlds and mods for RoastEngine  |  CoffeBrewIA", 40, 72, 1.5f, Theme.TEXT_MUTED);

        float top = 120;
        float columnW = Math.min(440f, (w - 120) / 2f);
        float newHeight = 420;
        drawNewProject(40, top, columnW, newHeight);
        drawOpenProject(40 + columnW + 40, top, w - (40 + columnW + 40) - 40, h - top - 40);

        if (!message.isBlank()) {
            r.text(r.ellipsize(message, columnW, 1.5f), 40, top + newHeight + 20, 1.5f,
                    messageIsError ? Theme.ERROR : Theme.SUCCESS);
        }

        ui.end();
    }

    private void drawNewProject(float x, float y, float w, float h) {
        ui.panel("New Project", x, y, w, h);
        float fx = x + 16;
        float fw = w - 32;
        float fy = y + 54;

        fy += ui.textField(nameField, fx, fy, fw) + 14;

        // Project type: what you are building decides which editor opens.
        r.text("Project type", fx, fy, 1.5f, Theme.TEXT_MUTED);
        fy += 20;
        int typeCount = ProjectType.values().length;
        float buttonW = (fw - 8 * (typeCount - 1)) / typeCount;
        for (int i = 0; i < ProjectType.values().length; i++) {
            ProjectType type = ProjectType.values()[i];
            if (ui.flatButton(type.displayName(), fx + i * (buttonW + 8), fy, buttonW, 30,
                    projectType == type)) {
                projectType = type;
            }
            r.outline(fx + i * (buttonW + 8), fy, buttonW, 30, 1f,
                    projectType == type ? Theme.ACCENT : Theme.PANEL_BORDER);
        }
        fy += 38;
        for (String line : wrap(projectType.description(), fw, 1.25f)) {
            r.text(line, fx, fy, 1.25f, Theme.TEXT_MUTED);
            fy += 16;
        }
        fy += 10;

        r.text("Location", fx, fy, 1.5f, Theme.TEXT_MUTED);
        fy += 18;
        r.rect(fx, fy, fw, 30, Theme.INPUT);
        r.outline(fx, fy, fw, 30, 1f, Theme.PANEL_BORDER);
        r.text(r.ellipsize(location.toString(), fw - 100, 1.5f), fx + 8, fy + 10, 1.5f, Theme.TEXT);
        if (ui.button("Browse", fx + fw - 86, fy + 2, 82, 26)) {
            Dialogs.selectFolder("Choose where to create the project", location).ifPresent(p -> location = p);
        }
        fy += 46;

        if (ui.button("Create Project", fx, fy, fw, 40)) {
            createProject();
        }
    }

    private void drawOpenProject(float x, float y, float w, float h) {
        ui.panel("Open Project", x, y, w, h);
        float fx = x + 16;
        float fw = w - 32;

        if (ui.button("Open Project Folder...", fx, y + 54, fw, 40)) {
            Dialogs.selectFolder("Open RoastEngine project", location).ifPresent(this::openProject);
        }

        r.text("Recent projects", fx, y + 112, 1.5f, Theme.TEXT_MUTED);
        float listY = y + 134;
        if (recent.isEmpty()) {
            r.text("Nothing yet - create a project to get started.", fx, listY + 8, 1.5f, Theme.TEXT_MUTED);
            return;
        }
        for (int i = 0; i < recent.size(); i++) {
            Path path = recent.get(i);
            float rowY = listY + i * 32;
            if (rowY + 30 > y + h - 8) {
                break;
            }
            if (ui.selectable(path.getFileName() + "   -   " + path.getParent(), fx, rowY, fw, 30, false)) {
                openProject(path);
            }
        }
    }

    // ---------------------------------------------------------------------

    /** Wraps text to a width, since the renderer only draws single lines. */
    private List<String> wrap(String text, float maxWidth, float scale) {
        List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (r.textWidth(candidate, scale) > maxWidth && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private void createProject() {
        try {
            CreatorProject project = CreatorProject.create(location, nameField.text(), projectType);
            RecentProjects.remember(project.root());
            engine.states().switchTo(editorFor(project));
        } catch (IOException e) {
            setMessage("Could not create project: " + e.getMessage(), true);
        }
    }

    private void openProject(Path root) {
        try {
            CreatorProject project = CreatorProject.open(root);
            RecentProjects.remember(project.root());
            engine.states().switchTo(editorFor(project));
        } catch (IOException e) {
            setMessage("Could not open project: " + e.getMessage(), true);
        }
    }

    /** Each project type has its own editor: scene, scripting, or shader preview. */
    public static GameState editorFor(net.coffeebrewia.roastengine.core.Engine engine, CreatorProject project) {
        return switch (project.type()) {
            case API -> new ApiEditorState(engine, project);
            case SHADERS -> new ShaderEditorState(engine, project);
            case RIG -> new AnimatorState(engine, project);
            default -> new EditorState(engine, project);
        };
    }

    private GameState editorFor(CreatorProject project) {
        return editorFor(engine, project);
    }

    private void setMessage(String text, boolean error) {
        message = text;
        messageIsError = error;
        System.out.println("[Creator] " + text);
    }
}

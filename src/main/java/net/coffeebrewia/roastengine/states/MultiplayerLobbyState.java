package net.coffeebrewia.roastengine.states;

import net.coffeebrewia.roastengine.core.Engine;
import net.coffeebrewia.roastengine.core.GameState;
import net.coffeebrewia.roastengine.modding.LocalMod;
import net.coffeebrewia.roastengine.modding.ModIoClient;
import net.coffeebrewia.roastengine.modding.ModManager;
import net.coffeebrewia.roastengine.multiplayer.MultiplayerSession;
import net.coffeebrewia.roastengine.multiplayer.RemotePlayer;
import net.coffeebrewia.roastengine.net.Protocol.ModRef;
import net.coffeebrewia.roastengine.net.Protocol.SessionUpdate;
import net.coffeebrewia.roastengine.render.Color;
import net.coffeebrewia.roastengine.render.Renderer2D;
import net.coffeebrewia.roastengine.ui.Theme;
import net.coffeebrewia.roastengine.ui.Ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11C.*;

/**
 * Between joining a server and playing on it.
 *
 * <ul>
 *   <li><b>Picking</b> - the first player in chooses the world and any content mods.</li>
 *   <li><b>Waiting</b> - everyone else waits while they choose.</li>
 *   <li><b>Getting ready</b> - once chosen, anything this player is missing is downloaded from
 *       mod.io, then the sandbox starts with exactly those mods.</li>
 * </ul>
 *
 * Shaders and controllers are API/device mods: they stay each player's own and are not part of
 * the choice.
 */
public final class MultiplayerLobbyState implements GameState {

    private static final float ROW = 30f;

    private enum Phase {PICKING, SENT, WAITING, PREPARING, FAILED}

    private final Engine engine;
    private final MultiplayerMenuState menu;
    private final MultiplayerSession session;
    private final Ui ui;
    private final Renderer2D r;

    private Phase phase = Phase.WAITING;
    private LocalMod pickedWorld;
    private final Set<LocalMod> pickedMods = new LinkedHashSet<>();
    private String progress = "";
    private String failure = "";
    private float deltaSeconds;
    private float worldScroll;
    private float modScroll;
    /** Set on leaving, so downloads still running stop instead of starting the game. */
    private boolean left;

    public MultiplayerLobbyState(Engine engine, MultiplayerMenuState menu, MultiplayerSession session) {
        this.engine = engine;
        this.menu = menu;
        this.session = session;
        this.ui = engine.ui();
        this.r = engine.renderer2D();
    }

    @Override
    public void enter() {
        left = false;
        engine.input().setCursorCaptured(false);
        List<LocalMod> worlds = worlds();
        pickedWorld = worlds.isEmpty() ? null : worlds.get(0);
    }

    @Override
    public void exit() {
        left = true;
    }

    @Override
    public void update(float dt) {
        deltaSeconds = dt;
        session.updateLobby(dt);
        if (!session.isConnected()) {
            engine.states().switchTo(menu.withMessage(session.disconnectReason(), true));
            return;
        }
        SessionUpdate current = session.session();
        if (current != null && phase != Phase.PREPARING && phase != Phase.FAILED) {
            switch (current.state()) {
                case SessionUpdate.READY -> prepare(current);
                case SessionUpdate.CHOOSE -> {
                    if (phase != Phase.SENT) {
                        phase = Phase.PICKING;
                    }
                }
                default -> phase = Phase.WAITING;
            }
        }
        if (phase == Phase.PICKING) {
            autoPick();
        }
        if (engine.input().wasKeyPressed(GLFW_KEY_ESCAPE)) {
            leave();
        }
    }

    /** Dev aid: -PautoPick=<world folder> picks that world as soon as it is this player's turn. */
    private void autoPick() {
        String wanted = System.getProperty("roastengine.autoPick");
        if (wanted == null) {
            return;
        }
        worlds().stream().filter(world -> world.key().equalsIgnoreCase(wanted)).findFirst().ifPresent(world -> {
            session.choose(refOf(world), List.of());
            phase = Phase.SENT;
        });
    }

    private void leave() {
        session.close();
        engine.states().switchTo(menu);
    }

    // ---------------------------------------------------------------------
    // What can be picked
    // ---------------------------------------------------------------------

    private List<LocalMod> worlds() {
        return engine.modManager().installedMods().stream()
                .filter(mod -> LocalMod.TYPE_WORLD.equalsIgnoreCase(mod.type()))
                .toList();
    }

    /** Content mods: scenes added on top of a world. API and device mods are personal. */
    private List<LocalMod> contentMods() {
        return engine.modManager().installedMods().stream()
                .filter(mod -> "mod".equalsIgnoreCase(mod.type()))
                .filter(mod -> Files.isRegularFile(mod.folder().resolve("scene.json")))
                .toList();
    }

    private static ModRef refOf(LocalMod mod) {
        return new ModRef(mod.modIoId(), mod.key(), mod.name());
    }

    private Optional<LocalMod> findInstalled(ModRef ref) {
        return engine.modManager().installedMods().stream()
                .filter(mod -> ref.modIoId() > 0 && mod.modIoId() == ref.modIoId()
                        || mod.key().equalsIgnoreCase(ref.key())
                        || mod.name().equalsIgnoreCase(ref.name()))
                .findFirst();
    }

    // ---------------------------------------------------------------------
    // Getting ready: download what is missing, then play
    // ---------------------------------------------------------------------

    private void prepare(SessionUpdate ready) {
        phase = Phase.PREPARING;
        List<ModRef> wanted = new ArrayList<>();
        wanted.add(ready.world());
        wanted.addAll(ready.mods());

        List<ModRef> missing = wanted.stream().filter(ref -> findInstalled(ref).isEmpty()).toList();
        for (ModRef ref : missing) {
            if (ref.modIoId() <= 0) {
                fail("This server is playing '" + ref.name() + "', which isn't on mod.io, so it can't be "
                        + "downloaded. Ask whoever picked it to upload it, or to pick another world.");
                return;
            }
        }
        downloadNext(wanted, missing, 0);
    }

    /** Downloads the missing mods one after another, then starts playing. */
    private void downloadNext(List<ModRef> wanted, List<ModRef> missing, int index) {
        if (phase != Phase.PREPARING || left) {
            return; // left the lobby meanwhile
        }
        if (index >= missing.size()) {
            play(wanted);
            return;
        }
        ModRef ref = missing.get(index);
        progress = "Downloading " + ref.name() + " from mod.io (" + (index + 1) + " of " + missing.size() + ")...";
        ModManager mods = engine.modManager();
        engine.modIo().fetchMod(ref.modIoId())
                .thenCompose(info -> {
                    Path zip = mods.downloadsDirectory().resolve(info.id() + "_" + info.modfileId() + ".zip");
                    return engine.modIo().downloadModFile(info, zip)
                            .thenApplyAsync(path -> {
                                try {
                                    return mods.install(info, path);
                                } catch (IOException e) {
                                    throw new CompletionException(e);
                                }
                            }, engine.background());
                })
                .whenComplete((installed, error) -> engine.runOnMainThread(() -> {
                    if (error != null) {
                        fail("Could not download " + ref.name() + ": " + ModIoClient.describe(error));
                    } else {
                        System.out.println("[Multiplayer] Installed " + installed.name() + " for this server");
                        downloadNext(wanted, missing, index + 1);
                    }
                }));
    }

    private void play(List<ModRef> wanted) {
        List<LocalMod> sources = new ArrayList<>();
        for (ModRef ref : wanted) {
            Optional<LocalMod> mod = findInstalled(ref);
            if (mod.isEmpty()) {
                fail(ref.name() + " was downloaded but could not be found afterwards.");
                return;
            }
            sources.add(mod.get());
        }
        engine.states().switchTo(new SandboxState(engine, menu, sources, session));
    }

    private void fail(String reason) {
        phase = Phase.FAILED;
        failure = reason;
        System.err.println("[Multiplayer] " + reason);
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    @Override
    public void render() {
        glClearColor(Theme.BACKGROUND.r(), Theme.BACKGROUND.g(), Theme.BACKGROUND.b(), 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        float w = engine.window().width();
        float h = engine.window().height();

        ui.begin(deltaSeconds);
        r.text(session.serverName(), 40, 26, 4f, Theme.TEXT);
        r.text(playersLine(), 40, 66, 1.5f, Theme.TEXT_MUTED);

        float pw = Math.min(760f, w - 80);
        float px = (w - pw) / 2f;
        float py = 110;
        float ph = h - py - 40;

        switch (phase) {
            case PICKING, SENT -> drawPicker(px, py, pw, ph);
            case WAITING -> drawMessage(px, py, pw, "Waiting",
                    whoIsPicking() + " is picking the world...", Theme.TEXT);
            case PREPARING -> drawMessage(px, py, pw, "Getting ready",
                    progress.isEmpty() ? "Loading " + worldName() + "..." : progress, Theme.TEXT);
            case FAILED -> drawMessage(px, py, pw, "Can't join", failure, Theme.ERROR);
        }
        ui.end();
    }

    private String playersLine() {
        List<String> names = new ArrayList<>();
        names.add(session.myName() + " (you)");
        for (RemotePlayer other : session.others()) {
            names.add(other.name);
        }
        return names.size() + " here: " + String.join(", ", names);
    }

    private String whoIsPicking() {
        SessionUpdate current = session.session();
        return current == null || current.chooser().isEmpty() ? "Someone" : current.chooser();
    }

    private String worldName() {
        SessionUpdate current = session.session();
        return current == null || current.world() == null ? "the world" : current.world().name();
    }

    private void drawMessage(float x, float y, float w, String title, String text, Color color) {
        float h = 220;
        ui.panel(title, x, y, w, h);
        drawWrapped(text, x + 20, y + 60, w - 40, color);
        if (ui.button("Leave", x + 20, y + h - 56, 160, 36)) {
            leave();
        }
    }

    private void drawPicker(float x, float y, float w, float h) {
        ui.panel("You're first - pick what everyone plays", x, y, w, h);
        float colW = (w - 60) / 2f;
        float listTop = y + 84;
        float listH = h - 84 - 110;

        // Worlds: pick one.
        float wx = x + 20;
        r.text("WORLD", wx, y + 60, 1.5f, Theme.TEXT_MUTED);
        List<LocalMod> worlds = worlds();
        if (worlds.isEmpty()) {
            drawWrapped("No worlds installed. Install one from the mod browser first.", wx, listTop, colW, Theme.ERROR);
        }
        worldScroll = scrollList(wx, listTop, colW, listH, worlds.size(), worldScroll);
        r.pushClip(wx, listTop, colW, listH);
        for (int i = 0; i < worlds.size(); i++) {
            LocalMod world = worlds.get(i);
            float ry = listTop + i * (ROW + 16) - worldScroll;
            if (ui.selectable(world.name(), wx, ry, colW, ROW, world.equals(pickedWorld)) && phase == Phase.PICKING) {
                pickedWorld = world;
            }
            r.text(availability(world), wx + 8, ry + ROW, 1.1f, world.modIoId() > 0 ? Theme.TEXT_MUTED : Theme.ERROR);
        }
        r.popClip();

        // Content mods: any number.
        float mx = wx + colW + 20;
        r.text("MODS (optional)", mx, y + 60, 1.5f, Theme.TEXT_MUTED);
        List<LocalMod> mods = contentMods();
        if (mods.isEmpty()) {
            drawWrapped("No content mods installed. That's fine - the world is enough.", mx, listTop, colW,
                    Theme.TEXT_MUTED);
        }
        modScroll = scrollList(mx, listTop, colW, listH, mods.size(), modScroll);
        r.pushClip(mx, listTop, colW, listH);
        for (int i = 0; i < mods.size(); i++) {
            LocalMod mod = mods.get(i);
            float ry = listTop + i * (ROW + 16) - modScroll;
            boolean on = pickedMods.contains(mod);
            if (ui.checkbox(mod.name(), mx, ry + 4, on) != on && phase == Phase.PICKING) {
                if (on) {
                    pickedMods.remove(mod);
                } else if (pickedMods.size() < net.coffeebrewia.roastengine.net.Protocol.MAX_SESSION_MODS) {
                    pickedMods.add(mod);
                }
            }
            r.text(availability(mod), mx + 8, ry + ROW, 1.1f, mod.modIoId() > 0 ? Theme.TEXT_MUTED : Theme.ERROR);
        }
        r.popClip();

        float by = y + h - 96;
        r.text("Your shaders and controllers stay as they are - they're not part of this.",
                wx, by, 1.25f, Theme.TEXT_MUTED);
        boolean canStart = phase == Phase.PICKING && pickedWorld != null;
        if (ui.button(phase == Phase.SENT ? "Starting..." : "Play " + (pickedWorld == null ? "" : pickedWorld.name()),
                wx, by + 24, colW, 40, canStart)) {
            session.choose(refOf(pickedWorld), pickedMods.stream().map(MultiplayerLobbyState::refOf).toList());
            phase = Phase.SENT;
        }
        if (ui.button("Leave", mx, by + 24, colW, 40)) {
            leave();
        }
    }

    private static String availability(LocalMod mod) {
        return mod.modIoId() > 0 ? "on mod.io - others get it automatically"
                : "only on your computer - others can't download it";
    }

    private float scrollList(float x, float y, float w, float h, int count, float scroll) {
        if (ui.isHovered(x, y, w, h)) {
            scroll -= engine.input().scrollY() * 40f;
        }
        float overflow = Math.max(0f, count * (ROW + 16) - h);
        return Math.max(0f, Math.min(scroll, overflow));
    }

    private void drawWrapped(String text, float x, float y, float width, Color color) {
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String tryLine = line.isEmpty() ? word : line + " " + word;
            if (r.textWidth(tryLine, 1.5f) > width && !line.isEmpty()) {
                r.text(line.toString(), x, y, 1.5f, color);
                y += 18;
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(tryLine);
            }
        }
        r.text(line.toString(), x, y, 1.5f, color);
    }
}

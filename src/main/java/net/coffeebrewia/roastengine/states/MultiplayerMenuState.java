package net.coffeebrewia.roastengine.states;

import net.coffeebrewia.roastengine.core.Engine;
import net.coffeebrewia.roastengine.core.GameSettings;
import net.coffeebrewia.roastengine.core.GameState;
import net.coffeebrewia.roastengine.multiplayer.MultiplayerSession;
import net.coffeebrewia.roastengine.multiplayer.NetClient;
import net.coffeebrewia.roastengine.multiplayer.ServerList;
import net.coffeebrewia.roastengine.multiplayer.ServerList.Entry;
import net.coffeebrewia.roastengine.multiplayer.ServerList.State;
import net.coffeebrewia.roastengine.multiplayer.ServerList.Status;
import net.coffeebrewia.roastengine.net.Protocol;
import net.coffeebrewia.roastengine.render.Color;
import net.coffeebrewia.roastengine.render.Renderer2D;
import net.coffeebrewia.roastengine.ui.TextField;
import net.coffeebrewia.roastengine.ui.Theme;
import net.coffeebrewia.roastengine.ui.Ui;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11C.*;

/**
 * The Multiplayer screen: the server list.
 *
 * <p>Opening it checks each server in the background - a sleeping server is only asked how it
 * is, never woken, so looking costs nothing. <b>Join</b> wakes it if needed (about a minute),
 * connects, and hands over to the {@link MultiplayerLobbyState}, where the world is picked.
 * Servers come from {@code multiplayer-defaults.properties} (see {@link ServerList}); a direct
 * address field below the list is there for local testing.
 */
public final class MultiplayerMenuState implements GameState {

    /** How often the list re-checks while it is open. */
    private static final float REFRESH_SECONDS = 20f;
    private static final float ROW_HEIGHT = 64f;

    private final Engine engine;
    private final GameState returnState;
    private final Ui ui;
    private final Renderer2D r;
    private final TextField nameField = new TextField("Your name", false, Protocol.MAX_NAME);
    private final TextField addressField = new TextField("Address  (host or host:port)", false, 128);

    private final List<Entry> servers = ServerList.load();
    private final Map<Entry, Status> statuses = new HashMap<>();
    private float refreshTimer;

    /** The server being joined, or null. Only one join at a time. */
    private Entry joining;
    private boolean joiningDirect;
    /** Bumped on every attempt and on leaving, so a late answer to an old attempt is dropped. */
    private volatile int attempt;
    /** Bumped on every refresh, so a slow answer never overwrites a newer one. */
    private int refreshRound;
    private String message = "";
    private boolean messageIsError;
    private float deltaSeconds;
    private boolean autoJoinDone;

    public MultiplayerMenuState(Engine engine, GameState returnState) {
        this.engine = engine;
        this.returnState = returnState;
        this.ui = engine.ui();
        this.r = engine.renderer2D();
    }

    /** Shown when the player is sent back here, e.g. after being disconnected. */
    public MultiplayerMenuState withMessage(String text, boolean error) {
        message = text;
        messageIsError = error;
        return this;
    }

    @Override
    public void enter() {
        engine.input().setCursorCaptured(false);
        GameSettings settings = engine.settings();
        nameField.setText(System.getProperty("roastengine.playerName", settings.playerName));
        addressField.setText(settings.lastServer);
        joining = null;
        joiningDirect = false;
        refresh();

        // Dev aid: -Pjoin=server_1 joins that server from the list; -Pjoin=localhost joins an address.
        String join = System.getProperty("roastengine.join");
        if (join != null && !autoJoinDone) {
            autoJoinDone = true;
            servers.stream().filter(entry -> entry.name().equalsIgnoreCase(join)).findFirst()
                    .ifPresentOrElse(this::join, () -> {
                        addressField.setText(join);
                        joinDirect(join);
                    });
        }
    }

    @Override
    public void exit() {
        attempt++; // anything still connecting is no longer wanted
    }

    @Override
    public void update(float dt) {
        deltaSeconds = dt;
        refreshTimer -= dt;
        if (refreshTimer <= 0f && joining == null) {
            refresh();
        }
        if (engine.input().wasKeyPressed(GLFW_KEY_ESCAPE) && !ui.hasFocusedField()) {
            engine.states().switchTo(returnState);
        }
    }

    /** Checks every server in the background. Never wakes one. */
    private void refresh() {
        refreshTimer = REFRESH_SECONDS;
        int round = ++refreshRound;
        for (Entry entry : servers) {
            statuses.putIfAbsent(entry, new Status(State.CHECKING, -1, 0, "", ""));
            engine.background().submit(() -> {
                Status status = entry.check();
                engine.runOnMainThread(() -> {
                    if (round == refreshRound && joining != entry) {
                        statuses.put(entry, status);
                    }
                });
            });
        }
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    @Override
    public void render() {
        glClearColor(Theme.BACKGROUND.r(), Theme.BACKGROUND.g(), Theme.BACKGROUND.b(), 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        float w = engine.window().width();

        ui.begin(deltaSeconds);
        r.text("Multiplayer", 40, 26, 4f, Theme.TEXT);
        r.text("Pick a server. The first one in picks the world.", 40, 66, 1.5f, Theme.TEXT_MUTED);

        float pw = Math.min(640f, w - 80);
        float px = (w - pw) / 2f;
        float fx = px;
        float fy = 110;

        float nameW = Math.min(300f, pw);
        fy += ui.textField(nameField, fx, fy, nameW) + 20;
        boolean nameOk = Protocol.cleanName(nameField.text()).length() >= 2;
        if (!nameOk) {
            r.text("Pick a name of at least 2 letters or numbers.", fx + nameW + 16, fy - 40, 1.25f, Theme.ERROR);
        }

        // The list.
        float listH = Math.max(1, servers.size()) * (ROW_HEIGHT + 8) + 56;
        ui.panel("Servers", px, fy, pw, listH);
        if (ui.button("Refresh", px + pw - 100, fy + 10, 84, 26, joining == null)) {
            refresh();
        }
        float ry = fy + 50;
        if (servers.isEmpty()) {
            r.text("No servers are listed in multiplayer-defaults.properties.", px + 16, ry + 8, 1.5f, Theme.TEXT_MUTED);
        }
        for (Entry entry : servers) {
            drawRow(entry, px + 12, ry, pw - 24, nameOk);
            ry += ROW_HEIGHT + 8;
        }
        fy += listH + 20;

        // Direct connect, for testing or someone else's server.
        r.text("OR CONNECT BY ADDRESS", fx, fy, 1.25f, Theme.TEXT_MUTED);
        fy += 18;
        float joinW = 100;
        ui.textField(addressField, fx, fy, pw - joinW - 10);
        boolean canDirect = joining == null && !joiningDirect && nameOk && !addressField.text().isBlank();
        if (ui.button(joiningDirect ? "..." : "Join", fx + pw - joinW, fy + 18, joinW, 30, canDirect)) {
            joinDirect(addressField.text());
        }
        fy += 64;

        boolean busy = joining != null || joiningDirect;
        if (ui.button(busy ? "Cancel" : "Back", fx, fy, 160, 34)) {
            if (busy) {
                cancelJoin();
            } else {
                engine.states().switchTo(returnState);
            }
        }
        if (!message.isEmpty()) {
            drawWrapped(message, fx + 180, fy + 4, pw - 180, messageIsError ? Theme.ERROR : Theme.TEXT_MUTED);
        }
        ui.end();
    }

    private void drawRow(Entry entry, float x, float y, float w, boolean nameOk) {
        r.rect(x, y, w, ROW_HEIGHT, Theme.ROW);
        Status status = statuses.getOrDefault(entry, new Status(State.CHECKING, -1, 0, "", ""));
        Color dot = switch (status.state()) {
            case ONLINE -> Theme.SUCCESS;
            case ASLEEP, STARTING, CHECKING -> Theme.ACCENT;
            default -> Theme.ERROR;
        };
        r.rect(x + 14, y + 16, 10, 10, dot);
        r.text(entry.name(), x + 34, y + 12, 2f, Theme.TEXT);
        String line = joining == entry ? message : describe(status);
        r.text(r.ellipsize(line, w - 200, 1.25f), x + 34, y + 38, 1.25f,
                status.state() == State.ERROR && joining != entry ? Theme.ERROR : Theme.TEXT_MUTED);

        boolean canJoin = joining == null && !joiningDirect && nameOk && entry.isSetUp();
        String label = joining == entry ? "Joining..." : status.state() == State.ASLEEP ? "Wake & Join" : "Join";
        if (ui.button(label, x + w - 160, y + 14, 148, 36, canJoin)) {
            join(entry);
        }
    }

    private static String describe(Status status) {
        return switch (status.state()) {
            case CHECKING -> "Checking...";
            case ASLEEP -> "Asleep - joining wakes it up (about a minute)";
            case STARTING -> "Starting up...";
            case ONLINE -> {
                String who = status.players() + "/" + status.maxPlayers() + " playing";
                yield status.world().isEmpty() ? "Online  |  " + who + "  |  you'd pick the world"
                        : "Online  |  " + who + "  |  " + status.world();
            }
            case OFFLINE -> "Offline";
            case NOT_SET_UP -> "Not set up yet - its wake-up link isn't in multiplayer-defaults.properties";
            case ERROR -> status.message();
        };
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

    // ---------------------------------------------------------------------
    // Joining
    // ---------------------------------------------------------------------

    private String playerName() {
        String name = Protocol.cleanName(nameField.text());
        engine.settings().playerName = name;
        engine.settings().save();
        return name;
    }

    /** Joins a listed server, waking it first if it is asleep. */
    private void join(Entry entry) {
        String name = playerName();
        joining = entry;
        message = "Contacting " + entry.name() + "...";
        messageIsError = false;
        int thisAttempt = ++attempt;
        engine.background().submit(() -> {
            try {
                NetClient client = entry.join(name,
                        status -> engine.runOnMainThread(() -> progress(thisAttempt, status)),
                        () -> thisAttempt != attempt);
                engine.runOnMainThread(() -> connected(thisAttempt, client, entry.name()));
            } catch (IOException | RuntimeException e) {
                String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                engine.runOnMainThread(() -> failed(thisAttempt, reason));
            }
        });
    }

    /** Joins by address, for testing or a server that isn't in the list. */
    private void joinDirect(String address) {
        String name = playerName();
        joiningDirect = true;
        message = "Connecting to " + address.trim() + "...";
        messageIsError = false;
        int thisAttempt = ++attempt;
        engine.settings().lastServer = address.trim();
        engine.settings().save();
        engine.background().submit(() -> {
            try {
                NetClient client = NetClient.connect(address, name);
                engine.runOnMainThread(() -> connected(thisAttempt, client, address.trim()));
            } catch (IOException | RuntimeException e) {
                String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                engine.runOnMainThread(() -> failed(thisAttempt, reason));
            }
        });
    }

    private void cancelJoin() {
        attempt++;
        joining = null;
        joiningDirect = false;
        message = "Cancelled.";
        messageIsError = false;
        refresh();
    }

    private void progress(int thisAttempt, String status) {
        if (thisAttempt == attempt && !status.equals(message)) {
            message = status;
            messageIsError = false;
            System.out.println("[Multiplayer] " + status);
        }
    }

    private void connected(int thisAttempt, NetClient client, String displayName) {
        if (thisAttempt != attempt) {
            client.close(); // the player gave up meanwhile
            return;
        }
        joining = null;
        joiningDirect = false;
        message = "";
        engine.states().switchTo(new MultiplayerLobbyState(engine, this, new MultiplayerSession(client, displayName)));
    }

    private void failed(int thisAttempt, String reason) {
        if (thisAttempt != attempt) {
            return;
        }
        joining = null;
        joiningDirect = false;
        message = reason;
        messageIsError = true;
        System.err.println("[Multiplayer] " + reason);
        refresh();
    }
}

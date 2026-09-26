package net.coffeebrewia.roastengine.arena;

import net.coffeebrewia.roastengine.input.Input;
import net.coffeebrewia.roastengine.net.ArenaRules;
import net.coffeebrewia.roastengine.net.Protocol.ArenaPlayer;
import net.coffeebrewia.roastengine.net.Protocol.ArenaState;
import net.coffeebrewia.roastengine.render.Color;
import net.coffeebrewia.roastengine.render.Renderer2D;
import net.coffeebrewia.roastengine.ui.Theme;
import net.coffeebrewia.roastengine.ui.Ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

/**
 * What the arena puts on screen: the lobby with its map vote, teams and ready button; the
 * loadout; and during a match the score, health, ammo, kill feed, and the results at the end.
 */
public final class ArenaScreens {

    public static final Color RED = Color.rgb(0xE0584F);
    public static final Color BLUE = Color.rgb(0x4F8FE0);

    private static final float PANEL_W = 780f;
    private static final float PANEL_H = 520f;

    private final ArenaMode arena;

    public ArenaScreens(ArenaMode arena) {
        this.arena = arena;
    }

    /** A team's colour, or null for no team. */
    public static Color teamColor(int team) {
        return team == ArenaRules.RED ? RED : team == ArenaRules.BLUE ? BLUE : null;
    }

    // --- Drawn with the rest of the HUD -------------------------------------------------

    /** The score, health, ammo, kill feed and flashes. Call inside the HUD's renderer batch. */
    public void drawHud(Renderer2D r, float w, float h) {
        ArenaState state = arena.state();
        ArenaPlayer me = state == null ? null : arena.me(state);

        if (arena.damageFlash > 0f) {
            Color flash = RED.withAlpha(0.45f * arena.damageFlash);
            float edge = Math.min(w, h) * 0.08f;
            r.rect(0, 0, w, edge, flash);
            r.rect(0, h - edge, w, edge, flash);
            r.rect(0, edge, edge, h - edge * 2, flash);
            r.rect(w - edge, edge, edge, h - edge * 2, flash);
        }

        drawTopBar(r, w, state);
        drawFeed(r, w);
        drawHitmarker(r, w, h);

        boolean playing = state != null && state.phase() == ArenaRules.PLAYING;
        if (playing && me != null) {
            drawHealth(r, h, me);
        }
        drawAmmo(r, w, h);

        if (playing && me != null && !me.alive()) {
            r.rect(0, 0, w, h, Color.rgb(0x000000).withAlpha(0.55f));
            String by = arena.killedBy() == null ? "You were eliminated" : "Eliminated by " + arena.killedBy();
            r.textCentered(by, 0, h * 0.40f, w, 40, 3.5f, Theme.TEXT);
            r.textCentered(String.format("Back in %.1f", me.respawnIn()), 0, h * 0.40f + 52, w, 24, 2f,
                    Theme.TEXT_MUTED);
        }
    }

    private void drawTopBar(Renderer2D r, float w, ArenaState state) {
        String text;
        if (state == null) {
            text = "Waiting for the arena...";
        } else {
            text = switch (state.phase()) {
                case ArenaRules.LOBBY -> arena.panelOpen() ? null : "LOBBY  -  L opens the menu";
                case ArenaRules.COUNTDOWN -> "Starting on " + arena.mapName(state.map()) + " in "
                        + (int) Math.ceil(state.timeLeft());
                case ArenaRules.PLAYING -> null; // the score box below
                default -> null;
            };
        }
        if (state != null && state.phase() == ArenaRules.PLAYING) {
            String clock = clock(state.timeLeft());
            String red = String.valueOf(state.redScore());
            String blue = String.valueOf(state.blueScore());
            float boxW = 260;
            float x = (w - boxW) / 2f;
            r.rect(x, 10, boxW, 40, Theme.BACKGROUND.withAlpha(0.8f));
            r.rect(x, 10, 70, 40, RED.withAlpha(0.85f));
            r.rect(x + boxW - 70, 10, 70, 40, BLUE.withAlpha(0.85f));
            r.textCentered(red, x, 10, 70, 40, 2.5f, Theme.TEXT);
            r.textCentered(blue, x + boxW - 70, 10, 70, 40, 2.5f, Theme.TEXT);
            r.textCentered(clock, x + 70, 10, boxW - 140, 26, 2f, Theme.TEXT);
            r.textCentered("first to " + arena.config().scoreLimit(), x + 70, 32, boxW - 140, 14, 1.1f,
                    Theme.TEXT_MUTED);
            return;
        }
        if (text != null) {
            float tw = r.textWidth(text, 1.75f) + 28;
            r.rect((w - tw) / 2f, 10, tw, 32, Theme.BACKGROUND.withAlpha(0.8f));
            r.textCentered(text, (w - tw) / 2f, 10, tw, 32, 1.75f, Theme.TEXT);
        }
    }

    private void drawFeed(Renderer2D r, float w) {
        float y = 40;
        for (ArenaMode.FeedLine line : arena.feed) {
            float fade = Math.min(1f, (ArenaMode.FEED_SECONDS - line.age()[0]) / 0.6f);
            String middle = "  [" + line.gun() + "]  ";
            float scale = 1.4f;
            float total = r.textWidth(line.killer(), scale) + r.textWidth(middle, scale)
                    + r.textWidth(line.victim(), scale);
            float x = w - total - 20;
            r.rect(x - 8, y - 4, total + 16, 22, Theme.BACKGROUND.withAlpha(0.65f * fade));
            r.text(line.killer(), x, y, scale, colour(line.killerTeam()).withAlpha(fade));
            x += r.textWidth(line.killer(), scale);
            r.text(middle, x, y, scale, Theme.TEXT_MUTED.withAlpha(fade));
            x += r.textWidth(middle, scale);
            r.text(line.victim(), x, y, scale, colour(line.victimTeam()).withAlpha(fade));
            y += 26;
        }
    }

    /** An X on the crosshair when a shot lands - bigger and red for a kill. */
    private void drawHitmarker(Renderer2D r, float w, float h) {
        if (arena.hitmarker <= 0f) {
            return;
        }
        Color color = (arena.hitmarkerKill ? RED : Color.rgb(0xFFFFFF)).withAlpha(Math.min(1f, arena.hitmarker * 6f));
        float cx = w / 2f;
        float cy = h / 2f;
        float reach = arena.hitmarkerKill ? 16f : 12f;
        // The renderer draws squares, so each arm of the X is a run of small ones.
        for (float d = 6f; d <= reach; d += 1.5f) {
            r.rect(cx + d - 1, cy + d - 1, 2.5f, 2.5f, color);
            r.rect(cx - d - 1, cy + d - 1, 2.5f, 2.5f, color);
            r.rect(cx + d - 1, cy - d - 1, 2.5f, 2.5f, color);
            r.rect(cx - d - 1, cy - d - 1, 2.5f, 2.5f, color);
        }
    }

    private void drawHealth(Renderer2D r, float h, ArenaPlayer me) {
        float max = arena.config().maxHealth();
        float fraction = Math.max(0f, Math.min(1f, me.health() / max));
        float x = 20;
        float y = h - 92;
        r.rect(x, y, 240, 24, Theme.BACKGROUND.withAlpha(0.8f));
        Color bar = fraction > 0.5f ? Theme.SUCCESS : fraction > 0.25f ? Color.rgb(0xE0B24F) : RED;
        r.rect(x + 3, y + 3, 234 * fraction, 18, bar);
        r.textCentered(me.health() + " HP", x, y, 240, 24, 1.5f, Theme.TEXT);
        Color team = teamColor(me.team());
        if (team != null) {
            r.text(me.team() == ArenaRules.RED ? "RED TEAM" : "BLUE TEAM", x, y - 20, 1.4f, team);
        }
    }

    private void drawAmmo(Renderer2D r, float w, float h) {
        int gun = arena.heldGun();
        if (gun < 0) {
            return;
        }
        ArenaConfig.Gun stats = arena.config().guns().get(gun);
        float boxW = 230;
        float x = w - boxW - 20;
        float y = h - 100;
        r.rect(x, y, boxW, 56, Theme.BACKGROUND.withAlpha(0.8f));
        r.text(stats.name(), x + 12, y + 8, 1.6f, Theme.TEXT_MUTED);
        if (arena.reloading()) {
            r.text("Reloading", x + 12, y + 28, 2f, Theme.TEXT);
            r.rect(x + 12, y + 50, (boxW - 24) * arena.reloadProgress(), 3, Theme.ACCENT);
        } else {
            int left = arena.ammoIn(gun);
            Color count = left == 0 ? RED : left <= stats.magazine() / 4 ? Color.rgb(0xE0B24F) : Theme.TEXT;
            String rounds = left + " / " + stats.magazine();
            r.text(rounds, x + 12, y + 26, 2.5f, count);
            if (left == 0) {
                r.text("R to reload", x + boxW - r.textWidth("R to reload", 1.25f) - 12, y + 34, 1.25f,
                        Theme.TEXT_MUTED);
            }
        }
    }

    // --- Menus, drawn after the HUD with the UI -----------------------------------------

    /** The lobby, the loadout or the results. Call between {@code ui.begin} and {@code ui.end}. */
    public void drawMenus(Ui ui, Renderer2D r, Input input, float w, float h) {
        ArenaState state = arena.state();
        if (state != null && state.phase() == ArenaRules.RESULTS) {
            drawResults(ui, r, w, h, state);
            return;
        }
        if (!arena.panelOpen()) {
            return;
        }
        ui.modalBackdrop(w, h);
        if (arena.loadoutOpen()) {
            drawLoadout(ui, r, input, w, h);
        } else {
            drawLobby(ui, r, input, w, h, state);
        }
    }

    private void drawLobby(Ui ui, Renderer2D r, Input input, float w, float h, ArenaState state) {
        float x = (w - PANEL_W) / 2f;
        float y = (h - PANEL_H) / 2f;
        ArenaConfig config = arena.config();
        ui.panel(config.name().toUpperCase() + (arena.isPractice() ? "   -   PRACTICE" : ""), x, y, PANEL_W, PANEL_H);
        ArenaPlayer me = state == null ? null : arena.me(state);

        r.text(status(state), x + 16, y + 48, 1.5f, state != null && state.phase() == ArenaRules.COUNTDOWN
                ? Theme.ACCENT : Theme.TEXT_MUTED);

        // The map vote.
        float top = y + 78;
        r.text("VOTE FOR A MAP", x + 16, top, 1.4f, Theme.TEXT_MUTED);
        int maps = config.maps().size();
        float cardW = (PANEL_W - 32 - (maps - 1) * 10) / maps;
        for (int i = 0; i < maps; i++) {
            ArenaConfig.ArenaMap map = config.maps().get(i);
            float cx = x + 16 + i * (cardW + 10);
            float cy = top + 20;
            boolean mine = me != null && me.vote() == i;
            boolean chosen = state != null && state.phase() == ArenaRules.COUNTDOWN && state.map() == i;
            boolean hovered = ui.isHovered(cx, cy, cardW, 70);
            r.rect(cx, cy, cardW, 70, hovered ? Theme.ROW_HOVER : Theme.ROW);
            if (mine || chosen) {
                r.outline(cx, cy, cardW, 70, 2f, chosen ? Theme.SUCCESS : Theme.ACCENT);
            }
            r.text(map.name(), cx + 12, cy + 10, 2f, Theme.TEXT);
            r.text(r.ellipsize(map.description(), cardW - 24, 1.25f), cx + 12, cy + 36, 1.25f, Theme.TEXT_MUTED);
            int votes = state == null || i >= state.votes().size() ? 0 : state.votes().get(i);
            String count = votes + (votes == 1 ? " vote" : " votes");
            r.text(count, cx + cardW - r.textWidth(count, 1.25f) - 12, cy + 12, 1.25f,
                    votes > 0 ? Theme.ACCENT : Theme.TEXT_MUTED);
            if (hovered && input.wasMousePressed(GLFW_MOUSE_BUTTON_LEFT)) {
                arena.vote(i);
            }
        }

        // The two teams.
        top += 110;
        float colW = (PANEL_W - 42) / 2f;
        drawTeam(ui, r, x + 16, top, colW, state, ArenaRules.RED, me);
        drawTeam(ui, r, x + 26 + colW, top, colW, state, ArenaRules.BLUE, me);

        // Loadout, ready, close.
        float by = y + PANEL_H - 60;
        String loadout = "LOADOUT (" + arena.loadout().size() + "/" + arena.loadoutSize() + ")";
        if (ui.button(loadout, x + 16, by, 240, 44)) {
            arena.setLoadoutOpen(true);
        }
        boolean ready = me != null && me.ready();
        boolean canReady = state != null && (state.phase() == ArenaRules.LOBBY || state.phase() == ArenaRules.COUNTDOWN);
        String readyText = arena.isPractice() ? (ready ? "CANCEL" : "START PRACTICE") : ready ? "NOT READY" : "READY";
        if (ui.button(readyText, x + 270, by, 240, 44, canReady)) {
            arena.setReady(!ready);
        }
        if (ui.button("CLOSE (L)", x + PANEL_W - 180, by, 164, 44)) {
            arena.closePanel();
        }
        r.text("Close this to walk the lobby and try your guns on the range.", x + 16, by - 22, 1.2f,
                Theme.TEXT_MUTED);
    }

    private void drawTeam(Ui ui, Renderer2D r, float x, float y, float w, ArenaState state, int team,
                          ArenaPlayer me) {
        Color color = teamColor(team);
        r.rect(x, y, w, 30, color.withAlpha(0.8f));
        r.text(team == ArenaRules.RED ? "RED TEAM" : "BLUE TEAM", x + 10, y + 7, 1.75f, Theme.TEXT);
        float row = y + 36;
        if (state != null) {
            for (ArenaPlayer player : state.players()) {
                if (player.team() != team) {
                    continue;
                }
                boolean isMe = player.id() == arena.link().myId();
                r.rect(x, row, w, 26, Theme.ROW);
                String name = arena.link().nameOf(player.id()) + (isMe && !arena.isPractice() ? " (you)" : "");
                r.text(name, x + 10, row + 6, 1.5f,
                        isMe ? Theme.TEXT : Theme.TEXT_MUTED);
                String tag = player.ready() ? "READY" : "...";
                r.text(tag, x + w - r.textWidth(tag, 1.3f) - 10, row + 7, 1.3f,
                        player.ready() ? Theme.SUCCESS : Theme.TEXT_MUTED);
                row += 30;
            }
        }
        boolean onIt = me != null && me.team() == team;
        if (ui.button(onIt ? "YOUR TEAM" : "JOIN", x, y + 170, w, 34, !onIt)) {
            arena.joinTeam(team);
        }
    }

    private void drawLoadout(Ui ui, Renderer2D r, Input input, float w, float h) {
        float x = (w - PANEL_W) / 2f;
        float y = (h - PANEL_H) / 2f;
        ui.panel("LOADOUT", x, y, PANEL_W, PANEL_H);
        ArenaConfig config = arena.config();
        int size = arena.loadoutSize();
        r.text("Pick up to " + size + (size == 1 ? " gun" : " guns") + ". 1-" + size
                        + " switch between them in a match.", x + 16, y + 48, 1.5f, Theme.TEXT_MUTED);
        if (!arena.hasBag()) {
            r.text("Only one gun without the PlaceHolder API - install it and switch it on to carry five.",
                    x + 16, y + 68, 1.3f, Theme.ERROR);
        }

        int columns = 3;
        float gap = 10;
        float cardW = (PANEL_W - 32 - gap * (columns - 1)) / columns;
        float cardH = 120;
        float top = y + 92;
        for (int i = 0; i < config.guns().size(); i++) {
            ArenaConfig.Gun gun = config.guns().get(i);
            float cx = x + 16 + (i % columns) * (cardW + gap);
            float cy = top + (i / columns) * (cardH + gap);
            int slot = arena.loadout().indexOf(i);
            boolean available = arena.gunAvailable(i);
            boolean hovered = available && ui.isHovered(cx, cy, cardW, cardH);
            r.rect(cx, cy, cardW, cardH, hovered ? Theme.ROW_HOVER : Theme.ROW);
            if (slot >= 0) {
                r.outline(cx, cy, cardW, cardH, 2f, Theme.ACCENT);
                r.rect(cx + cardW - 30, cy + 8, 22, 22, Theme.ACCENT);
                r.textCentered(String.valueOf(slot + 1), cx + cardW - 30, cy + 8, 22, 22, 1.4f, Theme.TEXT_ON_ACCENT);
            }
            r.text(gun.name(), cx + 10, cy + 8, 1.9f, available ? Theme.TEXT : Theme.TEXT_MUTED);
            r.text(r.ellipsize(gun.description(), cardW - 20, 1.1f), cx + 10, cy + 30, 1.1f, Theme.TEXT_MUTED);
            float by = cy + 50;
            by = bar(r, cx + 10, by, cardW - 20, "Damage", gun.damage() * gun.pellets() / 100f);
            by = bar(r, cx + 10, by, cardW - 20, "Fire rate", gun.fireRate() / 12f);
            by = bar(r, cx + 10, by, cardW - 20, "Magazine", gun.magazine() / 40f);
            bar(r, cx + 10, by, cardW - 20, "Range", gun.range() / 150f);
            if (hovered && input.wasMousePressed(GLFW_MOUSE_BUTTON_LEFT)) {
                arena.toggleLoadout(i);
            }
        }
        if (ui.button("DONE", x + PANEL_W - 180, y + PANEL_H - 60, 164, 44)) {
            arena.setLoadoutOpen(false);
        }
    }

    /** One stat as a labelled bar; returns where the next one goes. */
    private static float bar(Renderer2D r, float x, float y, float w, String label, float fraction) {
        float labelW = 70;
        r.text(label, x, y, 1.05f, Theme.TEXT_MUTED);
        r.rect(x + labelW, y + 3, w - labelW, 6, Theme.INPUT);
        r.rect(x + labelW, y + 3, (w - labelW) * Math.max(0.03f, Math.min(1f, fraction)), 6, Theme.ACCENT);
        return y + 15;
    }

    private void drawResults(Ui ui, Renderer2D r, float w, float h, ArenaState state) {
        ui.modalBackdrop(w, h);
        float x = (w - 560) / 2f;
        float y = (h - 400) / 2f;
        ui.panel(null, x, y, 560, 400);
        String headline = state.winner() == ArenaRules.RED ? "RED WINS"
                : state.winner() == ArenaRules.BLUE ? "BLUE WINS" : "DRAW";
        Color color = state.winner() == ArenaRules.NO_TEAM ? Theme.TEXT : teamColor(state.winner());
        r.textCentered(headline, x, y + 18, 560, 50, 4f, color);
        r.textCentered(state.redScore() + "  -  " + state.blueScore(), x, y + 72, 560, 24, 2f, Theme.TEXT);

        float row = y + 120;
        r.text("PLAYER", x + 24, row, 1.3f, Theme.TEXT_MUTED);
        r.text("KILLS", x + 380, row, 1.3f, Theme.TEXT_MUTED);
        r.text("DEATHS", x + 460, row, 1.3f, Theme.TEXT_MUTED);
        row += 24;
        List<ArenaPlayer> players = new ArrayList<>(state.players());
        players.sort(Comparator.comparingInt(ArenaPlayer::kills).reversed()
                .thenComparingInt(ArenaPlayer::deaths));
        for (ArenaPlayer player : players) {
            if (row > y + 340) {
                break;
            }
            r.rect(x + 16, row - 4, 528, 26, Theme.ROW);
            r.rect(x + 16, row - 4, 4, 26, colour(player.team()));
            r.text(arena.link().nameOf(player.id()), x + 28, row, 1.5f, Theme.TEXT);
            r.text(String.valueOf(player.kills()), x + 380, row, 1.5f, Theme.TEXT);
            r.text(String.valueOf(player.deaths()), x + 460, row, 1.5f, Theme.TEXT);
            row += 30;
        }
        r.textCentered("Back to the lobby in " + (int) Math.ceil(state.timeLeft()), x, y + 360, 560, 20, 1.4f,
                Theme.TEXT_MUTED);
    }

    private String status(ArenaState state) {
        if (state == null) {
            return "Talking to the server...";
        }
        if (state.phase() == ArenaRules.COUNTDOWN) {
            return "Everyone's ready - starting on " + arena.mapName(state.map()) + " in "
                    + (int) Math.ceil(state.timeLeft());
        }
        int needed = arena.isPractice() ? 1 : arena.config().minPlayers();
        long ready = state.players().stream().filter(ArenaPlayer::ready).count();
        if (state.players().size() < needed) {
            return "Waiting for players (" + state.players().size() + "/" + needed + ")";
        }
        return ready + " of " + state.players().size() + " ready - everyone readies up to start";
    }

    private static Color colour(int team) {
        Color color = teamColor(team);
        return color == null ? Theme.TEXT : color;
    }

    private static String clock(float seconds) {
        int whole = Math.max(0, (int) Math.ceil(seconds));
        return String.format("%d:%02d", whole / 60, whole % 60);
    }
}

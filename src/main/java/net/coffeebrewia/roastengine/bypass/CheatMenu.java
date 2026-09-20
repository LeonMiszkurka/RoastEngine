package net.coffeebrewia.roastengine.bypass;

import net.coffeebrewia.roastengine.input.Input;
import net.coffeebrewia.roastengine.render.Color;
import net.coffeebrewia.roastengine.render.Renderer2D;
import net.coffeebrewia.roastengine.ui.Theme;
import net.coffeebrewia.roastengine.ui.Ui;

import java.util.List;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;

/**
 * The hack client's menu: tabs down the left, its cheats on the right.
 *
 * <p>Opened with the key the client asks for (usually Tab). While it is open the mouse is free,
 * so nothing in the game moves underneath, and the sandbox stops reading movement keys.
 */
public final class CheatMenu {

    private static final float WIDTH = 520f;
    private static final float HEIGHT = 420f;
    private static final float TAB_WIDTH = 150f;
    private static final float ROW = 34f;

    private final Cheats cheats;
    private boolean open;
    private int client;
    private int tab;

    public CheatMenu(Cheats cheats) {
        this.cheats = cheats;
    }

    public boolean isOpen() {
        return open;
    }

    public void close() {
        open = false;
    }

    /** Opens the menu without a key press (the Creator's preview and the dev aids use this). */
    public void open() {
        if (cheats.isActive()) {
            open = true;
        }
    }

    /**
     * Opens or closes the menu.
     *
     * @param allowOpen false while paused or typing, so those keep their keys
     * @return true while the menu has the mouse and keyboard
     */
    public boolean update(Input input, boolean allowOpen) {
        if (!cheats.isActive()) {
            return false;
        }
        int key = cheats.menuKey();
        if (key >= 0 && (allowOpen || open) && input.wasKeyPressed(key)) {
            open = !open;
            return true;
        }
        if (open && input.wasKeyPressed(GLFW_KEY_ESCAPE)) {
            open = false;
            return true;
        }
        return open;
    }

    /** Draws the menu. The caller has already called {@link Ui#begin}. */
    public void draw(Ui ui, Renderer2D r, float windowWidth, float windowHeight) {
        if (!open) {
            return;
        }
        List<HackClient> clients = cheats.clients();
        client = Math.min(client, clients.size() - 1);
        HackClient hack = clients.get(client);
        tab = Math.min(tab, hack.tabs().size() - 1);

        float x = (windowWidth - WIDTH) / 2f;
        float y = (windowHeight - HEIGHT) / 2f;
        r.rect(x, y, WIDTH, HEIGHT, Theme.BACKGROUND.withAlpha(0.94f));
        r.outline(x, y, WIDTH, HEIGHT, 2f, Theme.ACCENT);
        r.text(hack.name(), x + 16, y + 14, 2.25f, Theme.ACCENT);
        r.text(hack.version() + "  |  " + cheats.apiName() + "  |  " + Keys.name(cheats.menuKey())
                + " closes", x + 16, y + 42, 1.25f, Theme.TEXT_MUTED);

        // More than one hack client installed: a row to switch between them.
        float top = y + 66;
        if (clients.size() > 1) {
            float buttonW = Math.min(150f, (WIDTH - 32) / clients.size());
            for (int i = 0; i < clients.size(); i++) {
                if (ui.flatButton(clients.get(i).name(), x + 16 + i * buttonW, top, buttonW, 24, i == client)) {
                    client = i;
                    tab = 0;
                }
            }
            top += 30;
        }

        float listX = x + 16;
        for (int i = 0; i < hack.tabs().size(); i++) {
            if (ui.selectable(hack.tabs().get(i).name(), listX, top + i * 32, TAB_WIDTH, 28, i == tab)) {
                tab = i;
            }
        }

        float cx = listX + TAB_WIDTH + 16;
        float cw = WIDTH - TAB_WIDTH - 48;
        float cy = top;
        for (String id : hack.tabs().get(tab).cheats()) {
            Cheat cheat = Cheat.find(id);
            if (cheat == null) {
                continue;
            }
            switch (cheat.kind()) {
                case TOGGLE -> {
                    if (ui.toggle(cheat.label(), cx, cy, cw, cheats.on(id))) {
                        cheats.toggle(id);
                    }
                    cy += ROW;
                }
                case SLIDER -> {
                    float value = cheats.value(id, cheat.initial());
                    float changed = ui.slider(cheat.label(), cx, cy, cw, value, cheat.min(), cheat.max(),
                            String.format("%.1f", value));
                    if (changed != value) {
                        cheats.set(id, changed);
                    }
                    cy += ROW + 18;
                }
                case ACTION -> {
                    if (ui.button(cheat.label(), cx, cy, cw, 28)) {
                        cheats.trigger(id);
                    }
                    cy += ROW;
                }
            }
            String bind = bindFor(hack, id);
            if (!bind.isEmpty()) {
                r.text(bind, cx + cw + 6, cy - ROW + 8, 1.1f, Theme.TEXT_MUTED);
            }
        }

        r.text("Cheats are yours alone - other players see you move, not the menu.",
                x + 16, y + HEIGHT - 26, 1.1f, Color.rgb(0x9A8F82));
    }

    private static String bindFor(HackClient hack, String id) {
        Integer key = hack.binds().get(id);
        return key == null ? "" : Keys.name(key);
    }
}

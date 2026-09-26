package net.coffeebrewia.roastengine.ui;

import net.coffeebrewia.roastengine.render.Renderer2D;
import net.coffeebrewia.roastengine.world.ItemCatalog;

import java.util.List;

/**
 * The spawn gun's item list: everything installed, with a box to search it and a row to click.
 *
 * <p>Opened with <b>R</b> while the gun is in hand. Picking something puts it on the floor a
 * couple of paces ahead, which is near enough to walk into and far enough not to land on your
 * head.
 */
public final class SpawnMenu {

    private static final float WIDTH = 560f;
    private static final float ROW = 26f;

    private final TextField search = new TextField("Search", false, 32);
    /** Which sort is being shown, or null for all of them. */
    private ItemCatalog.Category tab;
    private boolean open;
    private int highlighted;
    private float scroll;

    public boolean isOpen() {
        return open;
    }

    public void toggle() {
        open = !open;
        if (open) {
            highlighted = 0;
            scroll = 0f;
            tab = ItemCatalog.Category.ITEM;   // the useful one to land on
        }
    }

    public void close() {
        open = false;
    }

    /**
     * Draws the list and says what was picked.
     *
     * @return the item to spawn this frame, or null
     */
    public ItemCatalog.Item draw(Ui ui, Renderer2D r, ItemCatalog catalog, float width, float height) {
        if (!open) {
            return null;
        }
        if (catalog == null) {
            return null;
        }
        List<ItemCatalog.Item> shown = catalog.search(search.text(), tab);
        float panelHeight = Math.min(height - 80, 520);
        float x = (width - WIDTH) / 2f;
        float y = (height - panelHeight) / 2f;
        ui.modalBackdrop(width, height);
        ui.panel("Spawn", x, y, WIDTH, panelHeight);

        ui.textField(search, x + 20, y + 44, WIDTH - 130);
        if (ui.button("Close", x + WIDTH - 96, y + 62, 76, 26)) {
            close();
            return null;
        }

        // The sorts, as tabs. "All" is there because the guess about what something is will
        // sometimes be wrong, and searching everything is the way out of that.
        float tabY = y + 96;
        float tabWidth = (WIDTH - 40 - 4 * 6) / 5f;
        float tabX = x + 20;
        if (ui.flatButton("All", tabX, tabY, tabWidth, 26, tab == null)) {
            tab = null;
            scroll = 0f;
        }
        tabX += tabWidth + 6;
        for (ItemCatalog.Category category : ItemCatalog.Category.values()) {
            String label = category.label() + " " + catalog.count(category);
            if (ui.flatButton(label, tabX, tabY, tabWidth, 26, tab == category)) {
                tab = category;
                scroll = 0f;
            }
            tabX += tabWidth + 6;
        }

        float listTop = y + 130;
        float listHeight = panelHeight - 130 - 46;
        r.text(shown.size() + " shown - click one to put it in front of you.",
                x + 20, y + panelHeight - 34, 1.25f, Theme.TEXT_MUTED);

        if (shown.isEmpty()) {
            r.text("Nothing here matches that.", x + 24, listTop + 8, 1.4f, Theme.TEXT_MUTED);
            return null;
        }

        // The list is longer than the panel on any real install, so it scrolls; the wheel
        // comes in through scroll(), since the state owns the input.
        float total = shown.size() * ROW;
        scroll = Math.max(0f, Math.min(scroll, Math.max(0f, total - listHeight)));

        ItemCatalog.Item picked = null;
        r.pushClip(x, listTop, WIDTH, listHeight);
        float row = listTop - scroll;
        for (int i = 0; i < shown.size(); i++) {
            ItemCatalog.Item item = shown.get(i);
            if (row + ROW >= listTop && row <= listTop + listHeight) {
                boolean chosen = i == highlighted;
                if (ui.selectable(item.name(), x + 16, row, WIDTH - 180, ROW - 2, chosen)) {
                    highlighted = i;
                    picked = item;
                }
                r.text(item.group(), x + WIDTH - 158, row + 6, 1.2f,
                        item.isReady() ? Theme.TEXT_MUTED : Theme.TEXT_MUTED.withAlpha(0.7f));
            }
            row += ROW;
        }
        r.popClip();
        return picked;
    }

    /** Scrolls the list. The state owns the input, so it passes the wheel in. */
    public void scroll(float amount) {
        scroll -= amount * 48f;
    }
}

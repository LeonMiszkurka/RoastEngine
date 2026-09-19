package net.coffeebrewia.roastengine.ui;

import net.coffeebrewia.roastengine.modding.LocalMod;
import net.coffeebrewia.roastengine.render.Color;
import net.coffeebrewia.roastengine.render.Renderer2D;
import net.coffeebrewia.roastengine.render.Texture;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads and caches the {@code icon.png} a mod ships, and draws it.
 *
 * <p>A mod with no icon, or with one that will not decode, gets a lettered tile instead, so
 * every row in the browser is the same shape whether or not the author supplied artwork. Both
 * outcomes are remembered, so a broken file is not retried every frame.
 *
 * <p>Textures are GL objects: create and draw from the main thread only.
 */
public final class ModIcons {

    private static final Color WHITE = new Color(1f, 1f, 1f, 1f);

    /** Cache keyed by mod folder; a null value means "no icon, draw the fallback". */
    private final Map<Path, Texture> cache = new HashMap<>();

    /** Loads the mod's icon, or returns null when it has none. */
    public Texture iconFor(LocalMod mod) {
        if (mod == null) {
            return null;
        }
        Path folder = mod.folder();
        if (cache.containsKey(folder)) {
            return cache.get(folder);
        }
        Texture texture = null;
        Path file = mod.iconFile();
        if (file != null) {
            try {
                texture = Texture.fromFile(file);
            } catch (IOException | RuntimeException e) {
                System.err.println("[ModIcons] " + mod.name() + ": could not read icon.png: " + e.getMessage());
            }
        }
        cache.put(folder, texture);
        return texture;
    }

    /**
     * Draws a mod's icon in a square box, falling back to a tile with the mod's initial.
     *
     * @param mod   the installed mod, or null when only the remote listing is known
     * @param label name to take the fallback letter from
     */
    public void draw(Renderer2D r, LocalMod mod, String label, float x, float y, float size) {
        Texture texture = iconFor(mod);
        if (texture != null) {
            // Tint white, so the icon's own colours come through unchanged.
            r.image(texture, x, y, size, size, WHITE);
        } else {
            r.rect(x, y, size, size, Theme.ROW_HOVER);
            String initial = label == null || label.isBlank()
                    ? "?" : label.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
            r.textCentered(initial, x, y, size, size, 2.5f, Theme.TEXT_MUTED);
        }
        r.outline(x, y, size, size, 1f, Theme.PANEL_BORDER);
    }

    /** Drops every texture. Call from the main thread when the owning state exits. */
    public void dispose() {
        cache.values().forEach(texture -> {
            if (texture != null) {
                texture.dispose();
            }
        });
        cache.clear();
    }
}

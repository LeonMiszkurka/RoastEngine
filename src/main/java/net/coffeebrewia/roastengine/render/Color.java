package net.coffeebrewia.roastengine.render;

/** Immutable RGBA color with components in [0, 1]. */
public record Color(float r, float g, float b, float a) {

    public static Color rgb(int hex) {
        return new Color(((hex >> 16) & 0xFF) / 255f, ((hex >> 8) & 0xFF) / 255f, (hex & 0xFF) / 255f, 1f);
    }

    public Color withAlpha(float alpha) {
        return new Color(r, g, b, alpha);
    }
}

package net.coffeebrewia.roastengine.ui;

import net.coffeebrewia.roastengine.render.Color;

/** Dark "roasted coffee" palette shared by all UI screens. */
public final class Theme {

    private Theme() {
    }

    public static final Color BACKGROUND = Color.rgb(0x16120F);
    public static final Color PANEL = Color.rgb(0x221C18);
    public static final Color PANEL_BORDER = Color.rgb(0x3A302A);
    public static final Color ROW = Color.rgb(0x2B241F);
    public static final Color ROW_HOVER = Color.rgb(0x352C26);

    public static final Color ACCENT = Color.rgb(0xC8813A);
    public static final Color ACCENT_HOVER = Color.rgb(0xE09A50);
    public static final Color ACCENT_DISABLED = Color.rgb(0x5A4636);

    public static final Color TEXT = Color.rgb(0xF2E8DF);
    public static final Color TEXT_MUTED = Color.rgb(0x9C8F85);
    public static final Color TEXT_ON_ACCENT = Color.rgb(0x1A120B);
    public static final Color SUCCESS = Color.rgb(0x7FC47A);
    public static final Color ERROR = Color.rgb(0xE0675A);

    public static final Color INPUT = Color.rgb(0x15110E);
    public static final Color INPUT_FOCUS = Color.rgb(0xC8813A);
}

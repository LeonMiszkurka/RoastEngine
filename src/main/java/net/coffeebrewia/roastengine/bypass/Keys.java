package net.coffeebrewia.roastengine.bypass;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;

/** Turns key names in a hack client's {@code client.json} ("TAB", "F", "F6") into GLFW keys. */
final class Keys {

    static final int NONE = -1;

    private static final Map<String, Integer> BY_NAME = new LinkedHashMap<>();

    static {
        for (char letter = 'A'; letter <= 'Z'; letter++) {
            BY_NAME.put(String.valueOf(letter), GLFW_KEY_A + (letter - 'A'));
        }
        for (int digit = 0; digit <= 9; digit++) {
            BY_NAME.put(String.valueOf(digit), GLFW_KEY_0 + digit);
        }
        for (int f = 1; f <= 12; f++) {
            BY_NAME.put("F" + f, GLFW_KEY_F1 + (f - 1));
        }
        BY_NAME.put("TAB", GLFW_KEY_TAB);
        BY_NAME.put("INSERT", GLFW_KEY_INSERT);
        BY_NAME.put("DELETE", GLFW_KEY_DELETE);
        BY_NAME.put("HOME", GLFW_KEY_HOME);
        BY_NAME.put("END", GLFW_KEY_END);
        BY_NAME.put("PAGEUP", GLFW_KEY_PAGE_UP);
        BY_NAME.put("PAGEDOWN", GLFW_KEY_PAGE_DOWN);
        BY_NAME.put("RIGHTSHIFT", GLFW_KEY_RIGHT_SHIFT);
        BY_NAME.put("LEFTALT", GLFW_KEY_LEFT_ALT);
        BY_NAME.put("RIGHTALT", GLFW_KEY_RIGHT_ALT);
        BY_NAME.put("RIGHTCONTROL", GLFW_KEY_RIGHT_CONTROL);
        BY_NAME.put("UP", GLFW_KEY_UP);
        BY_NAME.put("DOWN", GLFW_KEY_DOWN);
        BY_NAME.put("LEFT", GLFW_KEY_LEFT);
        BY_NAME.put("RIGHT", GLFW_KEY_RIGHT);
        BY_NAME.put("BACKSLASH", GLFW_KEY_BACKSLASH);
        BY_NAME.put("SEMICOLON", GLFW_KEY_SEMICOLON);
        BY_NAME.put("APOSTROPHE", GLFW_KEY_APOSTROPHE);
        BY_NAME.put("COMMA", GLFW_KEY_COMMA);
        BY_NAME.put("PERIOD", GLFW_KEY_PERIOD);
        BY_NAME.put("SLASH", GLFW_KEY_SLASH);
        BY_NAME.put("GRAVE", GLFW_KEY_GRAVE_ACCENT);
        BY_NAME.put("MINUS", GLFW_KEY_MINUS);
        BY_NAME.put("EQUAL", GLFW_KEY_EQUAL);
    }

    private Keys() {
    }

    /** The GLFW key, or {@link #NONE} when the name means nothing. */
    static int byName(String name) {
        if (name == null) {
            return NONE;
        }
        String cleaned = name.trim().toUpperCase(Locale.ROOT).replace("_", "").replace(" ", "");
        return BY_NAME.getOrDefault(cleaned, NONE);
    }

    /** The name a key is written with, for showing in the menu. */
    static String name(int key) {
        return BY_NAME.entrySet().stream().filter(entry -> entry.getValue() == key)
                .map(Map.Entry::getKey).findFirst().orElse("(unbound)");
    }
}

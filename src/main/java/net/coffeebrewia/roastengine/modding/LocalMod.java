package net.coffeebrewia.roastengine.modding;

import java.nio.file.Path;

/**
 * A mod installed in the local {@code mods/} folder.
 *
 * @param folder     directory containing the extracted mod
 * @param name       display name (from manifest, or folder name)
 * @param version    version string from manifest, or "unknown"
 * @param type       "world", "api" or "mod" (from the Creator's mod.json); "mod" if unknown
 * @param modIoId    mod.io id when installed via the browser, otherwise 0
 * @param modfileId  mod.io file id that was installed, otherwise 0
 */
public record LocalMod(Path folder, String name, String version, String type,
                       long modIoId, long modfileId) {

    public static final String TYPE_WORLD = "world";
    public static final String TYPE_API = "api";

    /** Artwork a mod may ship for the browser to show, at the root of its folder. */
    public static final String ICON = "icon.png";

    /** An API mod provides engine features (such as a shader pack) other content builds on. */
    public boolean isApi() {
        return TYPE_API.equalsIgnoreCase(type);
    }

    /** The mod's own artwork, shown in the browser. Optional; null when the mod ships none. */
    public Path iconFile() {
        Path icon = folder.resolve(ICON);
        return java.nio.file.Files.isRegularFile(icon) ? icon : null;
    }

    /** Folder name, used as the stable key for the enabled list. */
    public String key() {
        return folder.getFileName().toString();
    }

    /** A world mod ships a scene the sandbox can play. */
    public boolean isWorld() {
        return TYPE_WORLD.equalsIgnoreCase(type)
                || java.nio.file.Files.isRegularFile(folder.resolve("scene.json"));
    }
}

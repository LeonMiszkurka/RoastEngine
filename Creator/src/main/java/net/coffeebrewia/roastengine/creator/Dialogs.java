package net.coffeebrewia.roastengine.creator;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Native file/folder pickers via tinyfd.
 *
 * <p>These block the render thread while the OS dialog is open, which is fine for an editor.
 * Must be called on the main thread.
 */
public final class Dialogs {

    private static final String[] MODEL_FILTERS = {"*.obj", "*.glb", "*.gltf", "*.fbx", "*.dae", "*.stl", "*.ply"};

    private Dialogs() {
    }

    public static Optional<Path> openModel(Path startDir) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(MODEL_FILTERS.length);
            for (String filter : MODEL_FILTERS) {
                filters.put(stack.UTF8(filter));
            }
            filters.flip();
            String path = TinyFileDialogs.tinyfd_openFileDialog(
                    "Import 3D model", startDir == null ? "" : startDir + "/", filters,
                    "3D models (obj, glb, gltf, fbx)", false);
            return Optional.ofNullable(path).map(Path::of);
        }
    }

    public static Optional<Path> selectFolder(String title, Path startDir) {
        String path = TinyFileDialogs.tinyfd_selectFolderDialog(
                title, startDir == null ? "" : startDir.toString());
        return Optional.ofNullable(path).map(Path::of);
    }

    public static void message(String title, String message) {
        TinyFileDialogs.tinyfd_messageBox(title, message, "ok", "info", true);
    }

    public static void error(String title, String message) {
        TinyFileDialogs.tinyfd_messageBox(title, message, "ok", "error", true);
    }

    public static boolean confirm(String title, String message) {
        return TinyFileDialogs.tinyfd_messageBox(title, message, "yesno", "question", false);
    }
}

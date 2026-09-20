package net.coffeebrewia.roastengine.ui;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Files;
import java.nio.file.Path;

/** The system's "open a file" window, used for installing a mod from a zip. */
public final class FilePicker {

    private FilePicker() {
    }

    /** @return the chosen zip, or null when the player cancels or picks something else */
    public static Path openZip(String title) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*.zip"));
            filters.flip();
            String chosen = TinyFileDialogs.tinyfd_openFileDialog(title, "", filters, "Mod zip files", false);
            if (chosen == null || chosen.isBlank()) {
                return null;
            }
            Path path = Path.of(chosen);
            return Files.isRegularFile(path) ? path : null;
        } catch (RuntimeException e) {
            System.err.println("[Mods] Could not open the file picker: " + e.getMessage());
            return null;
        }
    }
}

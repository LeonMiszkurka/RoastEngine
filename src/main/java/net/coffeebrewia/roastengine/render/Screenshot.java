package net.coffeebrewia.roastengine.render;

import net.coffeebrewia.roastengine.core.Window;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Saves the current framebuffer as a PNG. Must be called on the main thread after rendering. */
public final class Screenshot {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private Screenshot() {
    }

    /** @return the file written, or null if it failed */
    public static Path capture(Window window, Path directory) {
        int width = window.framebufferWidth();
        int height = window.framebufferHeight();
        int channels = 4;
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * channels);
        try {
            org.lwjgl.opengl.GL11C.glReadPixels(0, 0, width, height,
                    org.lwjgl.opengl.GL11C.GL_RGBA, org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE, pixels);

            Files.createDirectories(directory);
            Path file = directory.resolve("roastengine-" + LocalDateTime.now().format(STAMP) + ".png");
            // OpenGL reads bottom-up; tell stb to flip so the PNG is the right way up.
            STBImageWrite.stbi_flip_vertically_on_write(true);
            if (!STBImageWrite.stbi_write_png(file.toString(), width, height, channels,
                    pixels, width * channels)) {
                System.err.println("[Screenshot] stb failed to write " + file);
                return null;
            }
            System.out.println("[Screenshot] Saved " + file);
            return file;
        } catch (IOException e) {
            System.err.println("[Screenshot] Failed: " + e.getMessage());
            return null;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }
}

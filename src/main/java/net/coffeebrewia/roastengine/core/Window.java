package net.coffeebrewia.roastengine.core;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11C.glViewport;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Owns the GLFW window and the OpenGL 3.3 core context.
 *
 * <p>Two sizes are tracked: the <em>window</em> size (logical points, used for UI layout and
 * mouse coordinates) and the <em>framebuffer</em> size (physical pixels, used for the GL
 * viewport). They differ on HiDPI / Retina displays.
 */
public final class Window {

    private final String title;
    private long handle = NULL;

    private int width;
    private int height;
    private int framebufferWidth;
    private int framebufferHeight;

    private boolean vsync;
    private boolean inBackground;

    public Window(String title, int width, int height) {
        this.title = title;
        this.width = width;
        this.height = height;
    }

    /** Creates the window and makes its OpenGL context current on the calling thread. */
    public void create(boolean vsync) {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE); // required on macOS
        glfwWindowHint(GLFW_SAMPLES, 4);

        handle = glfwCreateWindow(width, height, title, NULL, NULL);
        if (handle == NULL) {
            // By far the likeliest reason: graphics without OpenGL 3.3 (older Chromebooks, virtual
            // machines). Said plainly, since this lands in the log a player sends in.
            throw new IllegalStateException("Could not open the game window. RoastEngine needs graphics "
                    + "that support OpenGL 3.3 - on a Chromebook or virtual machine, check that GPU "
                    + "acceleration is on, and on a PC, update the graphics driver.");
        }

        centerOnPrimaryMonitor();
        glfwSetWindowSizeCallback(handle, (win, w, h) -> {
            width = w;
            height = h;
        });
        glfwSetFramebufferSizeCallback(handle, (win, w, h) -> {
            framebufferWidth = w;
            framebufferHeight = h;
            glViewport(0, 0, w, h);
        });

        glfwMakeContextCurrent(handle);
        this.vsync = vsync;
        glfwSwapInterval(vsync ? 1 : 0);
        GL.createCapabilities();

        refreshFramebufferSize();
        glViewport(0, 0, framebufferWidth, framebufferHeight);
        glfwShowWindow(handle);
    }

    private void centerOnPrimaryMonitor() {
        GLFWVidMode mode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        if (mode != null) {
            glfwSetWindowPos(handle, (mode.width() - width) / 2, (mode.height() - height) / 2);
        }
    }

    private void refreshFramebufferSize() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            glfwGetFramebufferSize(handle, w, h);
            framebufferWidth = w.get(0);
            framebufferHeight = h.get(0);
        }
    }

    public boolean shouldClose() {
        return glfwWindowShouldClose(handle);
    }

    public void requestClose() {
        glfwSetWindowShouldClose(handle, true);
    }

    public void swapBuffers() {
        glfwSwapBuffers(handle);
    }

    /** True while the window has the keyboard, i.e. the player is looking at it. */
    public boolean isFocused() {
        return glfwGetWindowAttrib(handle, GLFW_FOCUSED) == GLFW_TRUE;
    }

    /**
     * Switches vsync off while in the background. On macOS a vsync'd swap can wait a very long
     * time while the window is covered or minimised, which would slow the whole game - online,
     * the player would stop sending updates and drop out. The engine caps the frame rate itself
     * while in the background instead.
     */
    public void setBackground(boolean background) {
        if (background != inBackground) {
            inBackground = background;
            glfwSwapInterval(vsync && !background ? 1 : 0);
        }
    }

    public void destroy() {
        if (handle != NULL) {
            glfwFreeCallbacks(handle);
            glfwDestroyWindow(handle);
            handle = NULL;
        }
        glfwTerminate();
        GLFWErrorCallback previous = glfwSetErrorCallback(null);
        if (previous != null) {
            previous.free();
        }
    }

    public long handle() {
        return handle;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int framebufferWidth() {
        return framebufferWidth;
    }

    public int framebufferHeight() {
        return framebufferHeight;
    }

    public float aspectRatio() {
        return height == 0 ? 1f : (float) width / height;
    }
}

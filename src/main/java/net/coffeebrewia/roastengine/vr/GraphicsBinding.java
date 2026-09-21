package net.coffeebrewia.roastengine.vr;

import org.lwjgl.glfw.GLFWNativeGLX;
import org.lwjgl.glfw.GLFWNativeWGL;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.glfw.GLFWNativeX11;
import org.lwjgl.openxr.XrGraphicsBindingOpenGLWin32KHR;
import org.lwjgl.openxr.XrGraphicsBindingOpenGLXlibKHR;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.windows.User32;

/**
 * Hands OpenXR the game's own OpenGL context, which is how the headset ends up drawing the same
 * world the window does. Every system says this differently:
 *
 * <ul>
 *   <li><b>Windows</b> - the window's device context and the GL context (Quest Link, SteamVR).</li>
 *   <li><b>Linux/X11</b> - the display, visual and GLX context (SteamVR, Monado).</li>
 *   <li><b>macOS</b> - nothing: there is no OpenXR runtime, so VR never starts there.</li>
 * </ul>
 */
final class GraphicsBinding {

    private GraphicsBinding() {
    }

    /**
     * The binding for this system, to hang off the session's {@code next} chain.
     *
     * @return the struct's address
     * @throws IllegalStateException when this system cannot do VR
     */
    static long forThisSystem(MemoryStack stack, long windowHandle) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            long window = GLFWNativeWin32.glfwGetWin32Window(windowHandle);
            long deviceContext = User32.GetDC(window);
            long glContext = GLFWNativeWGL.glfwGetWGLContext(windowHandle);
            if (deviceContext == 0 || glContext == 0) {
                throw new IllegalStateException("could not reach the window's OpenGL context");
            }
            return XrGraphicsBindingOpenGLWin32KHR.calloc(stack)
                    .type$Default()
                    .hDC(deviceContext)
                    .hGLRC(glContext)
                    .address();
        }
        if (os.contains("linux") || os.contains("unix")) {
            long display = GLFWNativeX11.glfwGetX11Display();
            long glxContext = GLFWNativeGLX.glfwGetGLXContext(windowHandle);
            long drawable = GLFWNativeX11.glfwGetX11Window(windowHandle);
            if (display == 0 || glxContext == 0) {
                throw new IllegalStateException("VR needs X11; Wayland is not supported yet");
            }
            return XrGraphicsBindingOpenGLXlibKHR.calloc(stack)
                    .type$Default()
                    .xDisplay(display)
                    .glxDrawable(drawable)
                    .glxContext(glxContext)
                    .address();
        }
        throw new IllegalStateException("VR is not available on " + System.getProperty("os.name"));
    }
}

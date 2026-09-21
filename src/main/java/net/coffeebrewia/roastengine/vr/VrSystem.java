package net.coffeebrewia.roastengine.vr;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.openxr.*;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.openxr.XR10.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * The headset: OpenXR from start-up to the last frame.
 *
 * <p>This is what makes RoastEngine a VR game rather than a game shown on a screen. It opens an
 * OpenXR session against whatever runtime is installed (Meta's, for a Quest over Link; SteamVR;
 * Monado on Linux), makes one texture chain per eye, and each frame asks the runtime where the
 * player's head is, draws the world once for each eye, and hands the pictures back.
 *
 * <pre>
 * every frame:
 *   waitFrame()      -&gt; the runtime says when this frame should be shown
 *   beginFrame()     -&gt; where the head and both hands are now
 *   for each eye:    -&gt; the engine draws the world with that eye's view and projection
 *   endFrame()       -&gt; the eyes go to the headset
 * </pre>
 *
 * <p>Nothing here runs on macOS: there is no OpenXR runtime for it, and LWJGL ships no macOS
 * natives, so {@link #isSupported()} is false and the game stays on the monitor. Everything is
 * written so that a missing runtime, a headset that is asleep, or a player taking the headset off
 * leaves the game running rather than crashing.
 */
public final class VrSystem implements AutoCloseable {

    /** One eye's texture chain and the size of its pictures. */
    private static final class Eye {
        XrSwapchain swapchain;
        int width;
        int height;
        int[] images;
        int framebuffer;
        int depth;
    }

    /** Where a hand is, and whether the runtime actually knows. */
    public static final class Hand {
        public final Vector3f position = new Vector3f();
        public final Quaternionf rotation = new Quaternionf();
        public boolean tracked;
    }

    private XrInstance instance;
    private long systemId;
    private XrSession session;
    private XrSpace playSpace;
    private final List<Eye> eyes = new ArrayList<>();
    private XrView.Buffer views;
    private VrInput input;

    private int sessionState = XR_SESSION_STATE_UNKNOWN;
    private boolean running;
    private boolean shouldQuit;

    // This frame, from the runtime.
    private long predictedTime;
    private boolean shouldRender;
    private final Vector3f headPosition = new Vector3f();
    private final Quaternionf headRotation = new Quaternionf();

    private VrSystem() {
    }

    /** False on macOS, and anywhere the OpenXR loader is missing. */
    public static boolean isSupported() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return false;
        }
        try {
            // The OpenXR natives ship for Windows and Linux only; loading the class proves they
            // are in this build.
            Class.forName("org.lwjgl.openxr.XR10");
            return true;
        } catch (Throwable missing) {
            return false;
        }
    }

    /**
     * Starts VR, or returns null when there is no headset to talk to.
     *
     * <p>Never throws: no runtime, no headset, or a runtime that refuses simply means the game
     * carries on flat, with the reason in the log.
     */
    public static VrSystem tryStart(long windowHandle) {
        if (!isSupported()) {
            System.out.println("[VR] Not supported on this system (no OpenXR runtime)");
            return null;
        }
        VrSystem vr = new VrSystem();
        try {
            vr.createInstance();
            vr.findHeadset();
            vr.createSession(windowHandle);
            vr.createSwapchains();
            vr.input = new VrInput(vr.instance, vr.session);
            System.out.println("[VR] Ready");
            return vr;
        } catch (RuntimeException e) {
            System.out.println("[VR] Not starting in VR: " + e.getMessage());
            vr.close();
            return null;
        }
    }

    // --- Setting up ---------------------------------------------------------------------

    private void createInstance() {
        try (MemoryStack stack = stackPush()) {
            // The OpenGL extension is the only one we need; without it there is no headset for us.
            IntBuffer count = stack.mallocInt(1);
            check(xrEnumerateInstanceExtensionProperties((ByteBuffer) null, count, null),
                    "listing OpenXR extensions");
            XrExtensionProperties.Buffer properties = XrExtensionProperties.calloc(count.get(0), stack);
            properties.forEach(property -> property.type$Default());
            check(xrEnumerateInstanceExtensionProperties((ByteBuffer) null, count, properties),
                    "listing OpenXR extensions");
            boolean hasOpenGl = false;
            for (XrExtensionProperties property : properties) {
                if (KHROpenGLEnable.XR_KHR_OPENGL_ENABLE_EXTENSION_NAME.equals(property.extensionNameString())) {
                    hasOpenGl = true;
                }
            }
            if (!hasOpenGl) {
                throw new IllegalStateException("the OpenXR runtime cannot show OpenGL games");
            }

            PointerBuffer wanted = stack.pointers(stack.UTF8(KHROpenGLEnable.XR_KHR_OPENGL_ENABLE_EXTENSION_NAME));
            XrApplicationInfo application = XrApplicationInfo.calloc(stack)
                    .applicationName(stack.UTF8("RoastEngine"))
                    .applicationVersion(1)
                    .engineName(stack.UTF8("RoastEngine"))
                    .engineVersion(1)
                    .apiVersion(XR_CURRENT_API_VERSION);
            XrInstanceCreateInfo create = XrInstanceCreateInfo.calloc(stack)
                    .type$Default()
                    .applicationInfo(application)
                    .enabledExtensionNames(wanted);

            PointerBuffer handle = stack.mallocPointer(1);
            int result = xrCreateInstance(create, handle);
            if (result == XR_ERROR_RUNTIME_UNAVAILABLE) {
                throw new IllegalStateException("no VR runtime is running (start the Meta or SteamVR app)");
            }
            check(result, "opening OpenXR");
            instance = new XrInstance(handle.get(0), create);
        }
    }

    private void findHeadset() {
        try (MemoryStack stack = stackPush()) {
            XrSystemGetInfo get = XrSystemGetInfo.calloc(stack)
                    .type$Default()
                    .formFactor(XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY);
            LongBuffer id = stack.mallocLong(1);
            int result = xrGetSystem(instance, get, id);
            if (result == XR_ERROR_FORM_FACTOR_UNAVAILABLE) {
                throw new IllegalStateException("no headset is connected or it is asleep");
            }
            check(result, "finding the headset");
            systemId = id.get(0);

            XrSystemProperties properties = XrSystemProperties.calloc(stack).type$Default();
            check(xrGetSystemProperties(instance, systemId, properties), "asking about the headset");
            System.out.println("[VR] Headset: " + properties.systemNameString());
        }
    }

    private void createSession(long windowHandle) {
        try (MemoryStack stack = stackPush()) {
            // The runtime insists we check this before it will talk to OpenGL.
            XrGraphicsRequirementsOpenGLKHR requirements = XrGraphicsRequirementsOpenGLKHR.calloc(stack)
                    .type$Default();
            check(KHROpenGLEnable.xrGetOpenGLGraphicsRequirementsKHR(instance, systemId, requirements),
                    "checking the graphics requirements");

            XrSessionCreateInfo create = XrSessionCreateInfo.calloc(stack)
                    .type$Default()
                    .systemId(systemId)
                    .next(GraphicsBinding.forThisSystem(stack, windowHandle));

            PointerBuffer handle = stack.mallocPointer(1);
            check(xrCreateSession(instance, create, handle), "opening the headset session");
            session = new XrSession(handle.get(0), instance);

            // "Stage" is the room the player set up; "local" is wherever they were looking when
            // the game started. Stage is nicer, so it is tried first.
            playSpace = createSpace(stack, XR_REFERENCE_SPACE_TYPE_STAGE);
            if (playSpace == null) {
                playSpace = createSpace(stack, XR_REFERENCE_SPACE_TYPE_LOCAL);
            }
            if (playSpace == null) {
                throw new IllegalStateException("the runtime gave us nowhere to stand");
            }
        }
    }

    private XrSpace createSpace(MemoryStack stack, int type) {
        XrPosef identity = XrPosef.calloc(stack);
        identity.orientation().w(1f);
        XrReferenceSpaceCreateInfo create = XrReferenceSpaceCreateInfo.calloc(stack)
                .type$Default()
                .referenceSpaceType(type)
                .poseInReferenceSpace(identity);
        PointerBuffer handle = stack.mallocPointer(1);
        if (xrCreateReferenceSpace(session, create, handle) != XR_SUCCESS) {
            return null;
        }
        return new XrSpace(handle.get(0), session);
    }

    private void createSwapchains() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            check(xrEnumerateViewConfigurationViews(instance, systemId,
                    XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, count, null), "asking about the eyes");
            XrViewConfigurationView.Buffer configuration =
                    XrViewConfigurationView.calloc(count.get(0), stack);
            configuration.forEach(view -> view.type$Default());
            check(xrEnumerateViewConfigurationViews(instance, systemId,
                    XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, count, configuration), "asking about the eyes");

            long format = pickColourFormat(stack);
            for (XrViewConfigurationView view : configuration) {
                Eye eye = new Eye();
                eye.width = view.recommendedImageRectWidth();
                eye.height = view.recommendedImageRectHeight();

                XrSwapchainCreateInfo create = XrSwapchainCreateInfo.calloc(stack)
                        .type$Default()
                        .usageFlags(XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT)
                        .format(format)
                        .sampleCount(1)
                        .width(eye.width)
                        .height(eye.height)
                        .faceCount(1)
                        .arraySize(1)
                        .mipCount(1);
                PointerBuffer handle = stack.mallocPointer(1);
                check(xrCreateSwapchain(session, create, handle), "making the eye textures");
                eye.swapchain = new XrSwapchain(handle.get(0), session);

                check(xrEnumerateSwapchainImages(eye.swapchain, count, null), "counting eye textures");
                XrSwapchainImageOpenGLKHR.Buffer images =
                        XrSwapchainImageOpenGLKHR.calloc(count.get(0), stack);
                images.forEach(image -> image.type(KHROpenGLEnable.XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_KHR));
                check(xrEnumerateSwapchainImages(eye.swapchain, count,
                        XrSwapchainImageBaseHeader.create(images.address(), images.capacity())),
                        "listing eye textures");
                eye.images = new int[count.get(0)];
                for (int i = 0; i < eye.images.length; i++) {
                    eye.images[i] = images.get(i).image();
                }

                eye.framebuffer = glGenFramebuffers();
                eye.depth = glGenRenderbuffers();
                glBindRenderbuffer(GL_RENDERBUFFER, eye.depth);
                glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, eye.width, eye.height);
                glBindRenderbuffer(GL_RENDERBUFFER, 0);
                eyes.add(eye);
            }
            views = XrView.calloc(eyes.size());
            views.forEach(view -> view.type$Default());
            System.out.println("[VR] " + eyes.size() + " eyes at " + eyes.get(0).width + "x"
                    + eyes.get(0).height + " each");
        }
    }

    private long pickColourFormat(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        check(xrEnumerateSwapchainFormats(session, count, null), "listing texture formats");
        LongBuffer formats = stack.mallocLong(count.get(0));
        check(xrEnumerateSwapchainFormats(session, count, formats), "listing texture formats");
        // Prefer sRGB so the picture is not washed out; fall back to whatever is first.
        for (int i = 0; i < formats.capacity(); i++) {
            if (formats.get(i) == GL_SRGB8_ALPHA8) {
                return GL_SRGB8_ALPHA8;
            }
        }
        for (int i = 0; i < formats.capacity(); i++) {
            if (formats.get(i) == GL_RGBA8) {
                return GL_RGBA8;
            }
        }
        return formats.get(0);
    }

    // --- Each frame ---------------------------------------------------------------------

    /** True once the runtime has told us to start drawing. */
    public boolean isRunning() {
        return running;
    }

    /** True when the player has quit VR from inside the headset. */
    public boolean shouldQuit() {
        return shouldQuit;
    }

    public int eyeCount() {
        return eyes.size();
    }

    public VrInput input() {
        return input;
    }

    public Vector3f headPosition() {
        return headPosition;
    }

    public Quaternionf headRotation() {
        return headRotation;
    }

    /** Which way the head is facing, as an angle the game can walk along. */
    public float headYaw() {
        Vector3f forward = headRotation.transform(new Vector3f(0f, 0f, -1f));
        return (float) Math.atan2(forward.x, -forward.z);
    }

    /**
     * Handles anything the runtime wants to tell us: the session starting, the headset being
     * taken off, the player quitting. Call once a frame, before {@link #waitFrame()}.
     */
    public void pollEvents() {
        try (MemoryStack stack = stackPush()) {
            XrEventDataBuffer event = XrEventDataBuffer.calloc(stack).type$Default();
            while (xrPollEvent(instance, event) == XR_SUCCESS) {
                if (event.type() == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                    XrEventDataSessionStateChanged changed =
                            XrEventDataSessionStateChanged.create(event.address());
                    sessionState = changed.state();
                    handleSessionState(stack);
                } else if (event.type() == XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING) {
                    shouldQuit = true;
                }
                event.clear();
                event.type$Default();
            }
        }
    }

    private void handleSessionState(MemoryStack stack) {
        switch (sessionState) {
            case XR_SESSION_STATE_READY -> {
                XrSessionBeginInfo begin = XrSessionBeginInfo.calloc(stack)
                        .type$Default()
                        .primaryViewConfigurationType(XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO);
                if (xrBeginSession(session, begin) == XR_SUCCESS) {
                    running = true;
                    System.out.println("[VR] Session started - put the headset on");
                }
            }
            case XR_SESSION_STATE_STOPPING -> {
                running = false;
                xrEndSession(session);
            }
            case XR_SESSION_STATE_EXITING, XR_SESSION_STATE_LOSS_PENDING -> {
                running = false;
                shouldQuit = true;
            }
            default -> {
                // IDLE, SYNCHRONIZED, VISIBLE and FOCUSED need nothing from us.
            }
        }
    }

    /** Waits until the runtime wants the next frame. Returns false when there is nothing to draw. */
    public boolean waitFrame() {
        if (!running) {
            return false;
        }
        try (MemoryStack stack = stackPush()) {
            XrFrameState state = XrFrameState.calloc(stack).type$Default();
            if (xrWaitFrame(session, XrFrameWaitInfo.calloc(stack).type$Default(), state) != XR_SUCCESS) {
                return false;
            }
            predictedTime = state.predictedDisplayTime();
            shouldRender = state.shouldRender();
            check(xrBeginFrame(session, XrFrameBeginInfo.calloc(stack).type$Default()), "starting the frame");
            locateHeadAndHands(stack);
            return true;
        }
    }

    private void locateHeadAndHands(MemoryStack stack) {
        XrViewLocateInfo locate = XrViewLocateInfo.calloc(stack)
                .type$Default()
                .viewConfigurationType(XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO)
                .displayTime(predictedTime)
                .space(playSpace);
        XrViewState viewState = XrViewState.calloc(stack).type$Default();
        IntBuffer count = stack.mallocInt(1);
        if (xrLocateViews(session, locate, viewState, count, views) == XR_SUCCESS && count.get(0) > 0) {
            // The head is halfway between the eyes, which is what the game's camera follows.
            XrPosef left = views.get(0).pose();
            XrPosef right = views.get(Math.min(1, count.get(0) - 1)).pose();
            headPosition.set(
                    (left.position$().x() + right.position$().x()) * 0.5f,
                    (left.position$().y() + right.position$().y()) * 0.5f,
                    (left.position$().z() + right.position$().z()) * 0.5f);
            headRotation.set(left.orientation().x(), left.orientation().y(),
                    left.orientation().z(), left.orientation().w());
        }
        if (input != null) {
            input.update(session, playSpace, predictedTime);
        }
    }

    /** Where this eye is looking, as the engine's camera wants it. */
    public void eyeView(int eye, Matrix4f out, Vector3f playerFeet, float playerYaw) {
        XrPosef pose = views.get(eye).pose();
        Vector3f position = new Vector3f(pose.position$().x(), pose.position$().y(), pose.position$().z());
        Quaternionf rotation = new Quaternionf(pose.orientation().x(), pose.orientation().y(),
                pose.orientation().z(), pose.orientation().w());
        // The room's floor is put where the player is standing in the world, and turned to face
        // the way they have turned with the stick.
        Matrix4f room = new Matrix4f().translation(playerFeet).rotateY(-playerYaw);
        Matrix4f head = new Matrix4f().translationRotateScale(position, rotation, 1f);
        out.set(room).mul(head).invert();
    }

    /** This eye's projection, from the angles the runtime reports. */
    public void eyeProjection(int eye, Matrix4f out, float near, float far) {
        XrFovf fov = views.get(eye).fov();
        float left = (float) Math.tan(fov.angleLeft());
        float right = (float) Math.tan(fov.angleRight());
        float down = (float) Math.tan(fov.angleDown());
        float up = (float) Math.tan(fov.angleUp());
        out.setFrustum(left * near, right * near, down * near, up * near, near, far);
    }

    /** Points the engine at this eye's texture, ready to be drawn into. */
    public boolean beginEye(int index) {
        if (!shouldRender) {
            return false;
        }
        Eye eye = eyes.get(index);
        try (MemoryStack stack = stackPush()) {
            IntBuffer image = stack.mallocInt(1);
            if (xrAcquireSwapchainImage(eye.swapchain,
                    XrSwapchainImageAcquireInfo.calloc(stack).type$Default(), image) != XR_SUCCESS) {
                return false;
            }
            XrSwapchainImageWaitInfo wait = XrSwapchainImageWaitInfo.calloc(stack)
                    .type$Default()
                    .timeout(XR_INFINITE_DURATION);
            if (xrWaitSwapchainImage(eye.swapchain, wait) != XR_SUCCESS) {
                return false;
            }
            glBindFramebuffer(GL_FRAMEBUFFER, eye.framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                    eye.images[image.get(0)], 0);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, eye.depth);
            glViewport(0, 0, eye.width, eye.height);
            return true;
        }
    }

    /** Hands this eye's picture back to the runtime. */
    public void endEye(int index) {
        try (MemoryStack stack = stackPush()) {
            xrReleaseSwapchainImage(eyes.get(index).swapchain,
                    XrSwapchainImageReleaseInfo.calloc(stack).type$Default());
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /** Sends the finished frame to the headset. */
    public void endFrame() {
        if (!running) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            XrFrameEndInfo end = XrFrameEndInfo.calloc(stack)
                    .type$Default()
                    .displayTime(predictedTime)
                    .environmentBlendMode(XR_ENVIRONMENT_BLEND_MODE_OPAQUE);
            if (shouldRender) {
                XrCompositionLayerProjectionView.Buffer projectionViews =
                        XrCompositionLayerProjectionView.calloc(eyes.size(), stack);
                for (int i = 0; i < eyes.size(); i++) {
                    Eye eye = eyes.get(i);
                    projectionViews.get(i)
                            .type$Default()
                            .pose(views.get(i).pose())
                            .fov(views.get(i).fov())
                            .subImage(subImage -> subImage
                                    .swapchain(eye.swapchain)
                                    .imageRect(rect -> rect
                                            .offset(offset -> offset.x(0).y(0))
                                            .extent(extent -> extent.width(eye.width).height(eye.height)))
                                    .imageArrayIndex(0));
                }
                XrCompositionLayerProjection layer = XrCompositionLayerProjection.calloc(stack)
                        .type$Default()
                        .space(playSpace)
                        .views(projectionViews);
                end.layers(stack.pointers(layer.address()));
            }
            xrEndFrame(session, end);
        }
    }

    /** The size of one eye's picture, for the window mirror. */
    public int eyeWidth() {
        return eyes.isEmpty() ? 0 : eyes.get(0).width;
    }

    public int eyeHeight() {
        return eyes.isEmpty() ? 0 : eyes.get(0).height;
    }

    private static void check(int result, String what) {
        if (result < 0) {
            throw new IllegalStateException(what + " failed (OpenXR error " + result + ")");
        }
    }

    @Override
    public void close() {
        if (views != null) {
            views.free();
            views = null;
        }
        for (Eye eye : eyes) {
            if (eye.framebuffer != 0) {
                glDeleteFramebuffers(eye.framebuffer);
                glDeleteRenderbuffers(eye.depth);
            }
            if (eye.swapchain != null) {
                xrDestroySwapchain(eye.swapchain);
            }
        }
        eyes.clear();
        if (input != null) {
            input.close();
            input = null;
        }
        if (playSpace != null) {
            xrDestroySpace(playSpace);
            playSpace = null;
        }
        if (session != null) {
            xrDestroySession(session);
            session = null;
        }
        if (instance != null) {
            xrDestroyInstance(instance);
            instance = null;
        }
        running = false;
    }
}

package net.coffeebrewia.roastengine.vr;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.openxr.*;
import org.lwjgl.system.MemoryStack;

import java.nio.LongBuffer;

import static org.lwjgl.openxr.XR10.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * The Touch controllers: where the hands are, and what the buttons are doing.
 *
 * <p>OpenXR does not deal in buttons but in <i>actions</i> - "move", "punch", "jump" - which the
 * runtime maps onto whatever controllers the player actually holds. The bindings here are written
 * for the Quest's Touch controllers and also fit any other headset the runtime knows:
 *
 * <pre>
 * left stick      walk                right stick     turn
 * right trigger   punch / interact    left trigger    grab
 * A               jump                Y / menu        pause
 * </pre>
 *
 * <p>Hand poses come from the grip pose, which is where the controller sits in the fist - the
 * right place to hang a hand model from.
 */
public final class VrInput implements AutoCloseable {

    private final XrInstance instance;
    private XrActionSet actionSet;

    private XrAction moveAction;
    private XrAction turnAction;
    private XrAction punchAction;
    private XrAction grabAction;
    private XrAction jumpAction;
    private XrAction menuAction;
    private XrAction handPoseAction;

    private final long[] handPaths = new long[2];
    private final XrSpace[] handSpaces = new XrSpace[2];

    // What the sticks and buttons are doing this frame.
    final float[] move = new float[2];
    final float[] turn = new float[2];
    boolean punch;
    boolean punchPressed;
    boolean grab;
    boolean jump;
    boolean jumpPressed;
    boolean menuPressed;
    private boolean punchWas;
    private boolean jumpWas;
    private boolean menuWas;

    final VrSystem.Hand leftHand = new VrSystem.Hand();
    final VrSystem.Hand rightHand = new VrSystem.Hand();

    /** The walking stick, as x (sideways) and y (forward). */
    public float[] move() {
        return move;
    }

    /** The turning stick. */
    public float[] turn() {
        return turn;
    }

    /** True the frame the trigger went down: a punch, or using something. */
    public boolean punchPressed() {
        return punchPressed;
    }

    public boolean grabbing() {
        return grab;
    }

    /** True the frame the jump button went down. */
    public boolean jumpPressed() {
        return jumpPressed;
    }

    /** True the frame the menu button went down. */
    public boolean menuPressed() {
        return menuPressed;
    }

    public VrSystem.Hand leftHand() {
        return leftHand;
    }

    public VrSystem.Hand rightHand() {
        return rightHand;
    }

    VrInput(XrInstance instance, XrSession session) {
        this.instance = instance;
        try (MemoryStack stack = stackPush()) {
            createActions(stack);
            suggestTouchBindings(stack);
            attach(stack, session);
            createHandSpaces(session);
        }
    }

    private void createActions(MemoryStack stack) {
        XrActionSetCreateInfo setInfo = XrActionSetCreateInfo.calloc(stack)
                .type$Default()
                .actionSetName(stack.UTF8("gameplay"))
                .localizedActionSetName(stack.UTF8("Gameplay"))
                .priority(0);
        PointerBuffer handle = stack.mallocPointer(1);
        check(xrCreateActionSet(instance, setInfo, handle), "making the action set");
        actionSet = new XrActionSet(handle.get(0), instance);

        handPaths[0] = path("/user/hand/left");
        handPaths[1] = path("/user/hand/right");
        LongBuffer bothHands = stack.longs(handPaths[0], handPaths[1]);

        moveAction = action(stack, "move", "Walk", XR_ACTION_TYPE_VECTOR2F_INPUT, null);
        turnAction = action(stack, "turn", "Turn", XR_ACTION_TYPE_VECTOR2F_INPUT, null);
        punchAction = action(stack, "punch", "Punch or use", XR_ACTION_TYPE_BOOLEAN_INPUT, null);
        grabAction = action(stack, "grab", "Grab", XR_ACTION_TYPE_BOOLEAN_INPUT, null);
        jumpAction = action(stack, "jump", "Jump", XR_ACTION_TYPE_BOOLEAN_INPUT, null);
        menuAction = action(stack, "menu", "Menu", XR_ACTION_TYPE_BOOLEAN_INPUT, null);
        handPoseAction = action(stack, "hand_pose", "Hand position", XR_ACTION_TYPE_POSE_INPUT, bothHands);
    }

    private XrAction action(MemoryStack stack, String name, String shownName, int type,
                            LongBuffer subactionPaths) {
        XrActionCreateInfo info = XrActionCreateInfo.calloc(stack)
                .type$Default()
                .actionName(stack.UTF8(name))
                .localizedActionName(stack.UTF8(shownName))
                .actionType(type);
        if (subactionPaths != null) {
            info.subactionPaths(subactionPaths);
        }
        PointerBuffer handle = stack.mallocPointer(1);
        check(xrCreateAction(actionSet, info, handle), "making the '" + name + "' action");
        return new XrAction(handle.get(0), actionSet);
    }

    /** Which physical controls those actions sit on, for Touch controllers. */
    private void suggestTouchBindings(MemoryStack stack) {
        XrActionSuggestedBinding.Buffer bindings = XrActionSuggestedBinding.calloc(8, stack);
        bindings.get(0).action(moveAction).binding(path("/user/hand/left/input/thumbstick"));
        bindings.get(1).action(turnAction).binding(path("/user/hand/right/input/thumbstick"));
        bindings.get(2).action(punchAction).binding(path("/user/hand/right/input/trigger/value"));
        bindings.get(3).action(grabAction).binding(path("/user/hand/left/input/squeeze/value"));
        bindings.get(4).action(jumpAction).binding(path("/user/hand/right/input/a/click"));
        bindings.get(5).action(menuAction).binding(path("/user/hand/left/input/menu/click"));
        // Both hands are suggested in one go: a second call for the same profile would replace
        // everything suggested here rather than adding to it.
        bindings.get(6).action(handPoseAction).binding(path("/user/hand/left/input/grip/pose"));
        bindings.get(7).action(handPoseAction).binding(path("/user/hand/right/input/grip/pose"));

        XrInteractionProfileSuggestedBinding suggested = XrInteractionProfileSuggestedBinding.calloc(stack)
                .type$Default()
                .interactionProfile(path("/interaction_profiles/oculus/touch_controller"))
                .suggestedBindings(bindings);
        int result = xrSuggestInteractionProfileBindings(instance, suggested);
        if (result != XR_SUCCESS) {
            System.out.println("[VR] The runtime did not accept the Touch bindings (error " + result + ")");
        }
    }

    private void attach(MemoryStack stack, XrSession session) {
        XrSessionActionSetsAttachInfo attach = XrSessionActionSetsAttachInfo.calloc(stack)
                .type$Default()
                .actionSets(stack.pointers(actionSet.address()));
        check(xrAttachSessionActionSets(session, attach), "attaching the controls");
    }

    private void createHandSpaces(XrSession session) {
        for (int hand = 0; hand < 2; hand++) {
            try (MemoryStack stack = stackPush()) {
                XrPosef identity = XrPosef.calloc(stack);
                identity.orientation().w(1f);
                XrActionSpaceCreateInfo create = XrActionSpaceCreateInfo.calloc(stack)
                        .type$Default()
                        .action(handPoseAction)
                        .subactionPath(handPaths[hand])
                        .poseInActionSpace(identity);
                PointerBuffer handle = stack.mallocPointer(1);
                if (xrCreateActionSpace(session, create, handle) == XR_SUCCESS) {
                    handSpaces[hand] = new XrSpace(handle.get(0), session);
                }
            }
        }
    }

    /** Reads the sticks, buttons and hand positions for this frame. */
    void update(XrSession session, XrSpace playSpace, long predictedTime) {
        try (MemoryStack stack = stackPush()) {
            XrActiveActionSet.Buffer active = XrActiveActionSet.calloc(1, stack);
            active.get(0).actionSet(actionSet).subactionPath(XR_NULL_PATH);
            XrActionsSyncInfo sync = XrActionsSyncInfo.calloc(stack)
                    .type$Default()
                    .activeActionSets(active);
            if (xrSyncActions(session, sync) != XR_SUCCESS) {
                return; // the headset is not in focus: leave everything as it was
            }

            readVector(stack, session, moveAction, move);
            readVector(stack, session, turnAction, turn);
            boolean punchNow = readBoolean(stack, session, punchAction);
            punchPressed = punchNow && !punchWas;
            punchWas = punchNow;
            punch = punchNow;
            grab = readBoolean(stack, session, grabAction);
            boolean jumpNow = readBoolean(stack, session, jumpAction);
            jumpPressed = jumpNow && !jumpWas;
            jumpWas = jumpNow;
            jump = jumpNow;
            boolean menuNow = readBoolean(stack, session, menuAction);
            menuPressed = menuNow && !menuWas;
            menuWas = menuNow;

            locateHand(stack, 0, playSpace, predictedTime, leftHand);
            locateHand(stack, 1, playSpace, predictedTime, rightHand);
        }
    }

    private void locateHand(MemoryStack stack, int hand, XrSpace playSpace, long time,
                            VrSystem.Hand out) {
        out.tracked = false;
        if (handSpaces[hand] == null) {
            return;
        }
        XrSpaceLocation location = XrSpaceLocation.calloc(stack).type$Default();
        if (xrLocateSpace(handSpaces[hand], playSpace, time, location) != XR_SUCCESS) {
            return;
        }
        long flags = location.locationFlags();
        if ((flags & XR_SPACE_LOCATION_POSITION_VALID_BIT) == 0
                || (flags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) == 0) {
            return; // the controller is switched off, out of view, or between batteries
        }
        XrPosef pose = location.pose();
        out.position.set(pose.position$().x(), pose.position$().y(), pose.position$().z());
        out.rotation.set(pose.orientation().x(), pose.orientation().y(),
                pose.orientation().z(), pose.orientation().w());
        out.tracked = true;
    }

    private void readVector(MemoryStack stack, XrSession session, XrAction action, float[] out) {
        XrActionStateGetInfo get = XrActionStateGetInfo.calloc(stack).type$Default().action(action);
        XrActionStateVector2f state = XrActionStateVector2f.calloc(stack).type$Default();
        if (xrGetActionStateVector2f(session, get, state) == XR_SUCCESS && state.isActive()) {
            out[0] = state.currentState().x();
            out[1] = state.currentState().y();
        } else {
            out[0] = 0f;
            out[1] = 0f;
        }
    }

    private boolean readBoolean(MemoryStack stack, XrSession session, XrAction action) {
        XrActionStateGetInfo get = XrActionStateGetInfo.calloc(stack).type$Default().action(action);
        XrActionStateBoolean state = XrActionStateBoolean.calloc(stack).type$Default();
        return xrGetActionStateBoolean(session, get, state) == XR_SUCCESS
                && state.isActive() && state.currentState();
    }

    private long path(String text) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer result = stack.mallocLong(1);
            check(xrStringToPath(instance, text, result), "reading the path " + text);
            return result.get(0);
        }
    }

    private static void check(int result, String what) {
        if (result < 0) {
            throw new IllegalStateException(what + " failed (OpenXR error " + result + ")");
        }
    }

    @Override
    public void close() {
        for (XrSpace space : handSpaces) {
            if (space != null) {
                xrDestroySpace(space);
            }
        }
        if (actionSet != null) {
            xrDestroyActionSet(actionSet);
            actionSet = null;
        }
    }
}

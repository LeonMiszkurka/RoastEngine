package net.coffeebrewia.roastengine.input;

import org.lwjgl.glfw.GLFWGamepadState;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * A once-a-frame snapshot of every connected controller.
 *
 * <p>GLFW exposes two views of the same hardware. A device it recognises is a <b>gamepad</b>:
 * its sticks and buttons are remapped to a fixed Xbox-style layout, so {@code A} is the same
 * index on every pad. Anything else is a plain <b>joystick</b>, a bare list of axes and buttons
 * whose meaning only the device knows. Both are snapshotted here, and
 * {@link ControlScheme} decides which view it wants.
 *
 * <p>Nothing in the engine reads this directly - the game reads {@link InputActions}, which is
 * what lets a controller be described by a mod instead of compiled in.
 */
public final class Gamepads {

    private static final int SLOTS = GLFW_JOYSTICK_LAST + 1;

    /** One controller as it looked when {@link #poll()} last ran. */
    public static final class Device {
        private final int slot;
        private String name = "";
        private String guid = "";
        private boolean present;
        private boolean gamepad;
        private float[] axes = new float[0];
        private boolean[] buttons = new boolean[0];
        private boolean[] previous = new boolean[0];

        private Device(int slot) {
            this.slot = slot;
        }

        public int slot() {
            return slot;
        }

        public String name() {
            return name;
        }

        /**
         * The device's hardware id, the same one SDL's controller database is keyed by. It is what
         * a mapping for an unrecognised pad has to start with, so it is worth printing.
         */
        public String guid() {
            return guid;
        }

        /** True when GLFW has a button mapping for this device, so the layout is known. */
        public boolean isGamepad() {
            return gamepad;
        }

        public float axis(int index) {
            return index >= 0 && index < axes.length ? axes[index] : 0f;
        }

        public boolean button(int index) {
            return index >= 0 && index < buttons.length && buttons[index];
        }

        /** True only on the frame the button went down. */
        public boolean wasPressed(int index) {
            return button(index) && !(index < previous.length && previous[index]);
        }

        public int axisCount() {
            return axes.length;
        }

        public int buttonCount() {
            return buttons.length;
        }

        /**
         * True when the device is there but offers nothing to read: no axes, no buttons.
         *
         * <p>This is a pad talking its maker's own protocol rather than the standard one - an Xbox
         * pad on a USB cable does exactly this, exposing only vendor-defined reports. No mapping
         * can help, because a mapping names axes and buttons and there are none. The same pad over
         * Bluetooth speaks the standard layout and works.
         */
        public boolean hasNothingToRead() {
            return present && axes.length == 0 && buttons.length == 0;
        }
    }

    private final Device[] devices = new Device[SLOTS];
    /** Reused scratch struct; allocated through BufferUtils, so the GC owns it. */
    private final GLFWGamepadState gamepadState = GLFWGamepadState.create();

    public Gamepads() {
        for (int slot = 0; slot < SLOTS; slot++) {
            devices[slot] = new Device(slot);
        }
    }

    /** Refreshes every slot. Call once per frame, after {@code glfwPollEvents()}. */
    public void poll() {
        for (Device device : devices) {
            boolean wasPresent = device.present;
            device.present = glfwJoystickPresent(device.slot);
            if (!device.present) {
                if (wasPresent) {
                    System.out.println("[Input] Controller disconnected from slot " + device.slot);
                    device.name = "";
                    device.guid = "";
                    device.gamepad = false;
                    device.axes = new float[0];
                    device.buttons = new boolean[0];
                    device.previous = new boolean[0];
                }
                continue;
            }

            device.previous = device.buttons.clone();
            device.gamepad = glfwJoystickIsGamepad(device.slot);
            if (device.gamepad && glfwGetGamepadState(device.slot, gamepadState)) {
                readGamepad(device);
            } else {
                readJoystick(device);
            }

            String name = device.gamepad ? glfwGetGamepadName(device.slot) : glfwGetJoystickName(device.slot);
            device.name = name == null ? "Controller " + device.slot : name;
            String guid = glfwGetJoystickGUID(device.slot);
            device.guid = guid == null ? "" : guid;
            if (!wasPresent) {
                System.out.println("[Input] Controller connected: " + device.name
                        + (device.gamepad ? " (gamepad layout)" : " (raw joystick, "
                        + device.axisCount() + " axes, " + device.buttonCount() + " buttons)")
                        + " id " + device.guid);
                if (!device.gamepad) {
                    System.out.println("[Input] " + advice(device));
                }
            }
        }
    }

    /**
     * What to do about a controller the game cannot read. The two causes need opposite answers, and
     * telling them apart is the difference between a fix and an afternoon wasted.
     */
    public static String advice(Device device) {
        if (device.hasNothingToRead()) {
            return device.name() + " is connected but offers no axes or buttons at all. A pad on a "
                    + "USB cable often talks its maker's own protocol, which cannot be read this "
                    + "way - connect it over Bluetooth instead and it speaks the standard layout. "
                    + "(id " + device.guid() + ")";
        }
        return "No pad layout is known for " + device.name() + " (" + device.axisCount()
                + " axes, " + device.buttonCount() + " buttons). Add a line for " + device.guid()
                + " to the InputEdit API's inputedit/mappings.txt to play with it.";
    }

    private void readGamepad(Device device) {
        FloatBuffer axes = gamepadState.axes();
        ByteBuffer buttons = gamepadState.buttons();
        device.axes = new float[axes.limit()];
        for (int i = 0; i < device.axes.length; i++) {
            device.axes[i] = axes.get(i);
        }
        device.buttons = new boolean[buttons.limit()];
        for (int i = 0; i < device.buttons.length; i++) {
            device.buttons[i] = buttons.get(i) == GLFW_PRESS;
        }
    }

    private void readJoystick(Device device) {
        FloatBuffer axes = glfwGetJoystickAxes(device.slot);
        device.axes = new float[axes == null ? 0 : axes.limit()];
        for (int i = 0; i < device.axes.length; i++) {
            device.axes[i] = axes.get(i);
        }
        ByteBuffer buttons = glfwGetJoystickButtons(device.slot);
        device.buttons = new boolean[buttons == null ? 0 : buttons.limit()];
        for (int i = 0; i < device.buttons.length; i++) {
            device.buttons[i] = buttons.get(i) == GLFW_PRESS;
        }
    }

    /** Connected controllers, in slot order. */
    public List<Device> connected() {
        List<Device> result = new ArrayList<>();
        for (Device device : devices) {
            if (device.present) {
                result.add(device);
            }
        }
        return result;
    }

    public boolean anyConnected() {
        for (Device device : devices) {
            if (device.present) {
                return true;
            }
        }
        return false;
    }
}

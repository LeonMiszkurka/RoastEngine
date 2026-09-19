package net.coffeebrewia.roastengine.core;

/**
 * Holds the active {@link GameState}. Switches are deferred to the start of the next
 * frame so a state can safely request a transition from inside its own update/render.
 */
public final class StateManager {

    private GameState current;
    private GameState pending;

    public void switchTo(GameState next) {
        pending = next;
    }

    /** Applies a pending switch, if any. Called by the engine at the top of each frame. */
    void applyPendingSwitch() {
        if (pending == null) {
            return;
        }
        if (current != null) {
            current.exit();
        }
        current = pending;
        pending = null;
        current.enter();
    }

    void update(float deltaSeconds) {
        if (current != null) {
            current.update(deltaSeconds);
        }
    }

    void render() {
        if (current != null) {
            current.render();
        }
    }

    void shutdown() {
        if (current != null) {
            current.exit();
            current = null;
        }
    }
}

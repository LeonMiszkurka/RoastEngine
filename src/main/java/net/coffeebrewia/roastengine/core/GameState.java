package net.coffeebrewia.roastengine.core;

/**
 * A screen / mode of the application (loading, main menu, sandbox, ...).
 *
 * <p>All methods are called on the main (OpenGL) thread.
 */
public interface GameState {

    /** Called once when the state becomes active. Allocate GL resources here. */
    default void enter() {
    }

    /** Advances simulation by {@code deltaSeconds}. */
    void update(float deltaSeconds);

    /** Draws the current frame. */
    void render();

    /** Called once when the state is replaced. Release GL resources here. */
    default void exit() {
    }
}

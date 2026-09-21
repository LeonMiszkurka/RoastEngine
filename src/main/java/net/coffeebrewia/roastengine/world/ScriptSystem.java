package net.coffeebrewia.roastengine.world;

import net.coffeebrewia.roastengine.script.Interpreter;
import net.coffeebrewia.roastengine.script.Script;
import net.coffeebrewia.roastengine.script.ScriptError;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the scripts a world's objects carry.
 *
 * <p>An object in a mod names a script in its {@code scripts/} folder; that script gets its own
 * interpreter, its own variables, and the object as {@code self}. The engine then calls the
 * functions the script chose to write:
 *
 * <pre>
 * on_start()            once, when the world loads
 * on_update(dt)         every frame, with the seconds since the last one
 * on_interact()         the player pressed E on this object
 * on_touch()            the player walked into it
 * on_player_near()      the player came within near_distance (3 m unless the script changes it)
 * on_player_leave()     ...and went away again
 * </pre>
 *
 * <p>A script that goes wrong is switched off with its mistake written to the log and shown
 * once on screen, rather than being allowed to take the game down with it. Scripts run on each
 * player's own computer, so in multiplayer everyone runs them separately.
 */
public final class ScriptSystem {

    /** What a script can do to the rest of the game, which the sandbox provides. */
    public interface Hooks {
        void notice(String text);

        void chat(String text);

        void playSound(String name, float volume, float pitch);

        /** The player's eyes. */
        Vector3f playerEye();

        void teleportPlayer(float x, float y, float z);

        void pushPlayer(float x, float y, float z);
    }

    /** One object's running script. */
    private static final class Running {
        final WorldObject object;
        final String file;
        final Interpreter interpreter = new Interpreter();
        final List<Timer> timers = new ArrayList<>();
        boolean near;
        boolean touching;
        boolean broken;
        float nearDistance = 3f;
        /** Where a move_to() is taking this object, and how long is left. */
        Vector3f moveTarget;
        Vector3f moveStart;
        float moveTime;
        float moveLeft;
        float spin;

        Running(WorldObject object, String file) {
            this.object = object;
            this.file = file;
        }
    }

    private static final class Timer {
        final Interpreter.Callable function;
        final float interval;
        final boolean repeating;
        float left;

        Timer(Interpreter.Callable function, float interval, boolean repeating) {
            this.function = function;
            this.interval = interval;
            this.repeating = repeating;
            this.left = interval;
        }
    }

    private final List<Running> scripts = new ArrayList<>();
    private final Hooks hooks;
    private final LoadedWorld world;
    private float clock;
    /** Mistakes already shown, so a broken script says so once rather than every frame. */
    private final Map<String, Boolean> reported = new HashMap<>();

    public ScriptSystem(LoadedWorld world, Hooks hooks) {
        this.world = world;
        this.hooks = hooks;
    }

    /** Reads and starts every script the world's objects name. Never throws. */
    public void load() {
        Map<Path, String> sources = new HashMap<>();
        for (WorldObject object : world.objects()) {
            if (object.script == null || object.script.isBlank() || object.modFolder == null) {
                continue;
            }
            Path file = object.modFolder.resolve("scripts").resolve(object.script);
            String source = sources.computeIfAbsent(file, path -> {
                try {
                    return Files.readString(path);
                } catch (IOException e) {
                    report(object.script, "could not be read: " + e.getMessage());
                    return null;
                }
            });
            if (source == null) {
                continue;
            }
            Running running = new Running(object, object.script);
            bind(running);
            try {
                running.interpreter.run(Script.parse(source));
                scripts.add(running);
                call(running, "on_start", List.of());
            } catch (ScriptError error) {
                fail(running, error);
            }
        }
        if (!scripts.isEmpty()) {
            System.out.println("[Script] Running " + scripts.size() + " script(s)");
        }
    }

    /** Runs every script's timers, movement and {@code on_update}. */
    public void update(float dt) {
        clock += dt;
        Vector3f player = hooks.playerEye();
        for (Running running : scripts) {
            if (running.broken || running.object.removedByScript) {
                continue;
            }
            advanceMovement(running, dt);
            advanceTimers(running, dt);
            call(running, "on_update", List.of((Object) (double) dt));

            // Coming and going: called once each way, not every frame the player is close.
            float distance = running.object.center(new Vector3f()).distance(player);
            boolean nearNow = distance <= running.nearDistance;
            if (nearNow != running.near) {
                running.near = nearNow;
                call(running, nearNow ? "on_player_near" : "on_player_leave", List.of());
            }
        }
    }

    /** Called by the sandbox when the player presses E on an object. */
    public void interact(WorldObject object) {
        forObject(object, running -> call(running, "on_interact", List.of()));
    }

    /** Called by the sandbox when the player's body overlaps an object. */
    public void touch(WorldObject object) {
        forObject(object, running -> {
            if (!running.touching) {
                running.touching = true;
                call(running, "on_touch", List.of());
            }
        });
    }

    /** Called when the player is no longer touching anything, to reset the touch flags. */
    public void endTouches() {
        scripts.forEach(running -> running.touching = false);
    }

    private void forObject(WorldObject object, java.util.function.Consumer<Running> action) {
        for (Running running : scripts) {
            if (running.object == object && !running.broken) {
                action.accept(running);
            }
        }
    }

    // --- Moving things ------------------------------------------------------------------

    private void advanceMovement(Running running, float dt) {
        if (running.spin != 0f) {
            running.object.scriptYaw += (float) Math.toRadians(running.spin) * dt;
        }
        if (running.moveTarget == null) {
            return;
        }
        running.moveLeft -= dt;
        if (running.moveLeft <= 0f) {
            running.object.scriptOffset.set(running.moveTarget);
            running.moveTarget = null;
            return;
        }
        float t = 1f - running.moveLeft / running.moveTime;
        running.object.scriptOffset.set(running.moveStart).lerp(running.moveTarget, t);
    }

    private void advanceTimers(Running running, float dt) {
        for (Timer timer : new ArrayList<>(running.timers)) {
            timer.left -= dt;
            if (timer.left > 0f) {
                continue;
            }
            if (timer.repeating) {
                timer.left += timer.interval;
            } else {
                running.timers.remove(timer);
            }
            try {
                timer.function.call(List.of());
            } catch (ScriptError error) {
                fail(running, error);
                return;
            }
        }
    }

    // --- What a script can see ----------------------------------------------------------

    private void bind(Running running) {
        Interpreter interpreter = running.interpreter;
        WorldObject object = running.object;

        interpreter.define("self", selfObject(running));
        interpreter.define("player", playerObject());
        interpreter.define("notice", Script.function("notice", 1, args -> {
            hooks.notice(Script.text(args.get(0)));
            return null;
        }));
        interpreter.define("say", Script.function("say", 1, args -> {
            hooks.chat(Script.text(args.get(0)));
            return null;
        }));
        interpreter.define("play_sound", Script.function("play_sound", 1, 3, args ->
                sound(args)));
        interpreter.define("time", Script.function("time", 0, args -> (double) clock));
        interpreter.define("after", Script.function("after", 2, args -> {
            running.timers.add(new Timer(callable(args.get(1), "after"),
                    (float) Script.number(args.get(0)), false));
            return null;
        }));
        interpreter.define("every", Script.function("every", 2, args -> {
            float interval = (float) Script.number(args.get(0));
            if (interval <= 0.01f) {
                throw new ScriptError("every() needs more than a hundredth of a second", 0);
            }
            running.timers.add(new Timer(callable(args.get(1), "every"), interval, true));
            return null;
        }));
        interpreter.define("find", Script.function("find", 1, args -> {
            String name = Script.text(args.get(0));
            for (WorldObject other : world.objects()) {
                if (other.name.equalsIgnoreCase(name) && !other.removedByScript) {
                    return objectHandle(other);
                }
            }
            return null;
        }));
        interpreter.define("param", Script.function("param", 1, 2, args -> {
            String value = object.scriptParams.get(Script.text(args.get(0)));
            if (value == null) {
                return args.size() > 1 ? args.get(1) : null;
            }
            try {
                return Double.parseDouble(value.trim());
            } catch (NumberFormatException e) {
                return value; // the value is text, which is fine
            }
        }));
        interpreter.define("distance_to_player", Script.function("distance_to_player", 0, args ->
                (double) object.center(new Vector3f()).distance(hooks.playerEye())));
    }

    private Object sound(List<Object> args) {
        float volume = args.size() > 1 ? (float) Script.number(args.get(1)) : 1f;
        float pitch = args.size() > 2 ? (float) Script.number(args.get(2)) : 1f;
        hooks.playSound(Script.text(args.get(0)), volume, pitch);
        return null;
    }

    private static Interpreter.Callable callable(Object value, String where) {
        if (value instanceof Interpreter.Callable callable) {
            return callable;
        }
        throw new ScriptError(where + "() needs a function to call, such as the name of one you "
                + "wrote with def", 0);
    }

    /** {@code self}: the object the script belongs to. */
    private Interpreter.ScriptObject selfObject(Running running) {
        WorldObject object = running.object;
        return new Interpreter.ScriptObject() {
            @Override
            public Object property(String name) {
                return switch (name) {
                    case "name" -> object.name;
                    case "x" -> (double) position(object).x;
                    case "y" -> (double) position(object).y;
                    case "z" -> (double) position(object).z;
                    case "is_door" -> object.isDoor();
                    case "is_open" -> object.doorWantsOpen;
                    case "near_distance" -> (double) running.nearDistance;
                    case "move" -> Script.function("move", 3, args -> {
                        object.scriptOffset.add(f(args.get(0)), f(args.get(1)), f(args.get(2)));
                        running.moveTarget = null;
                        return null;
                    });
                    case "move_to" -> Script.function("move_to", 3, 4, args -> {
                        Vector3f placed = placedPosition(object);
                        Vector3f target = new Vector3f(f(args.get(0)), f(args.get(1)), f(args.get(2)))
                                .sub(placed);
                        float seconds = args.size() > 3 ? f(args.get(3)) : 0f;
                        if (seconds <= 0f) {
                            object.scriptOffset.set(target);
                            running.moveTarget = null;
                        } else {
                            running.moveStart = new Vector3f(object.scriptOffset);
                            running.moveTarget = target;
                            running.moveTime = seconds;
                            running.moveLeft = seconds;
                        }
                        return null;
                    });
                    case "set_pos" -> Script.function("set_pos", 3, args -> {
                        object.scriptOffset.set(f(args.get(0)), f(args.get(1)), f(args.get(2)))
                                .sub(placedPosition(object));
                        running.moveTarget = null;
                        return null;
                    });
                    case "turn" -> Script.function("turn", 1, args -> {
                        object.scriptYaw += (float) Math.toRadians(f(args.get(0)));
                        return null;
                    });
                    case "spin" -> Script.function("spin", 1, args -> {
                        running.spin = f(args.get(0));
                        return null;
                    });
                    case "open" -> Script.function("open", 0, args -> {
                        object.doorWantsOpen = true;
                        return null;
                    });
                    case "close" -> Script.function("close", 0, args -> {
                        object.doorWantsOpen = false;
                        return null;
                    });
                    case "remove" -> Script.function("remove", 0, args -> {
                        object.removedByScript = true;
                        return null;
                    });
                    default -> Script.MISSING;
                };
            }

            @Override
            public void setProperty(String name, Object value) {
                if (name.equals("near_distance")) {
                    running.nearDistance = Math.max(0.5f, f(value));
                    return;
                }
                throw new ScriptError("'" + name + "' cannot be changed", 0);
            }
        };
    }

    /** Another object, from {@code find()}: the same as self, minus the private bits. */
    private Interpreter.ScriptObject objectHandle(WorldObject object) {
        return name -> switch (name) {
            case "name" -> object.name;
            case "x" -> (double) position(object).x;
            case "y" -> (double) position(object).y;
            case "z" -> (double) position(object).z;
            case "is_door" -> object.isDoor();
            case "is_open" -> object.doorWantsOpen;
            case "move" -> Script.function("move", 3, args -> {
                object.scriptOffset.add(f(args.get(0)), f(args.get(1)), f(args.get(2)));
                return null;
            });
            case "open" -> Script.function("open", 0, args -> {
                object.doorWantsOpen = true;
                return null;
            });
            case "close" -> Script.function("close", 0, args -> {
                object.doorWantsOpen = false;
                return null;
            });
            case "remove" -> Script.function("remove", 0, args -> {
                object.removedByScript = true;
                return null;
            });
            default -> Script.MISSING;
        };
    }

    /** {@code player}: where they are, and the two ways a script may move them. */
    private Interpreter.ScriptObject playerObject() {
        return name -> switch (name) {
            case "x" -> (double) hooks.playerEye().x;
            case "y" -> (double) hooks.playerEye().y;
            case "z" -> (double) hooks.playerEye().z;
            case "teleport" -> Script.function("teleport", 3, args -> {
                hooks.teleportPlayer(f(args.get(0)), f(args.get(1)), f(args.get(2)));
                return null;
            });
            case "push" -> Script.function("push", 3, args -> {
                hooks.pushPlayer(f(args.get(0)), f(args.get(1)), f(args.get(2)));
                return null;
            });
            default -> Script.MISSING;
        };
    }

    private static float f(Object value) {
        return (float) Script.number(value);
    }

    /** Where the object is now, including anything a script has done to it. */
    private static Vector3f position(WorldObject object) {
        return object.center(new Vector3f());
    }

    /** Where the level put it, before any script moved it. */
    private static Vector3f placedPosition(WorldObject object) {
        return object.center(new Vector3f()).sub(object.scriptOffset);
    }

    // --- Running them safely ------------------------------------------------------------

    private void call(Running running, String event, List<Object> arguments) {
        if (running.broken || !running.interpreter.hasFunction(event)) {
            return;
        }
        try {
            running.interpreter.callFunction(event, arguments);
        } catch (ScriptError error) {
            fail(running, error);
        } catch (RuntimeException error) {
            fail(running, new ScriptError("something went wrong: " + error, 0));
        }
    }

    /** A broken script stops running, says why once, and leaves the rest of the world alone. */
    private void fail(Running running, ScriptError error) {
        running.broken = true;
        report(running.file, error.describe() + "  (on " + running.object.name + ")");
    }

    private void report(String file, String message) {
        String text = file + ": " + message;
        if (reported.putIfAbsent(text, true) == null) {
            System.err.println("[Script] " + text);
            hooks.notice("Script " + text);
        }
    }
}

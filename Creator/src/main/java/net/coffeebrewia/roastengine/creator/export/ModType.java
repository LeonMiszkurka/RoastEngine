package net.coffeebrewia.roastengine.creator.export;

/** What kind of mod an export produces (chosen in the Export dialog). */
public enum ModType {

    /** A playable level: the scene replaces the sandbox world. */
    WORLD("world", "A level - objects, ground and sky replace the sandbox"),
    /** Code/behaviour that other mods build on; no scene of its own. */
    API("api", "Scripts and shared behaviour for other mods to use"),
    /** Plain content added on top of the current world. */
    MOD("mod", "Content added to whatever world is loaded");

    private final String id;
    private final String description;

    ModType(String id, String description) {
        this.id = id;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public String displayName() {
        return switch (this) {
            case WORLD -> "World";
            case API -> "API";
            case MOD -> "Mod";
        };
    }

    public ModType next() {
        ModType[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}

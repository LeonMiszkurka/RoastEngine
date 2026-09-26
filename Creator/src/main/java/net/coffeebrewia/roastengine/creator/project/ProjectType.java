package net.coffeebrewia.roastengine.creator.project;

import net.coffeebrewia.roastengine.creator.export.ModType;

/**
 * What a project is for. Chosen when the project is created and stored in {@code project.json};
 * it decides which editor opens and what that editor offers.
 *
 * <p>A rig project animates a model rather than placing any: it opens the animator, whose clips
 * are saved beside the .glb they were written for.
 *
 * <p>A world and a mod both place objects, so both get the 3D viewport and the transform tools.
 * A mod goes further: its objects can carry animations and scripted behaviour, because a mod is
 * dropped into somebody else's world and has to do something once it lands there. An API ships
 * no scene at all, so it opens a scripting editor instead of a viewport. A shader pack has a
 * viewport too, but it is a fixed preview scene seen through the pack being written, with the
 * GLSL and its tuning values alongside.
 */
public enum ProjectType {

    WORLD("world", "World", "A level you play. Place objects, set the ground and sky."),
    MOD("mod", "Mod", "Content added on top of a world - with animations and scripted behaviour."),
    API("api", "API", "Scripts other mods build on. No scene: a scripting editor, not a viewport."),
    SHADERS("shaders", "Shaders", "A shader pack. A live preview seen through your GLSL, with its tuning sliders."),
    RIG("rig", "Rig", "Animate a rigged model. Pose its bones, key them over time, save the clips beside it.");

    private final String id;
    private final String displayName;
    private final String description;

    ProjectType(String id, String displayName, String description) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    /** True for the types with a 3D view: the scene editors, the shader preview, the animator. */
    public boolean hasWorldView() {
        return this != API;
    }

    /** True for the types that place objects and so keep a {@code scene.json}. */
    public boolean hasScene() {
        return this == WORLD || this == MOD;
    }

    /** True where objects may carry animations and per-object script settings. */
    public boolean hasBehaviour() {
        return this == MOD;
    }

    /** The mod type an export of this project defaults to. */
    public ModType defaultModType() {
        return switch (this) {
            case WORLD -> ModType.WORLD;
            case MOD -> ModType.MOD;
            // A shader pack is an API mod: the engine looks for shaders/ in enabled APIs.
            // A rig ships as part of a mod's assets rather than on its own.
            case API, SHADERS, RIG -> ModType.API;
        };
    }

    public static ProjectType byId(String id) {
        if (id != null) {
            for (ProjectType type : values()) {
                if (type.id.equalsIgnoreCase(id)) {
                    return type;
                }
            }
        }
        return WORLD;
    }
}

package net.coffeebrewia.roastengine.world;

import net.coffeebrewia.roastengine.render.model.ModelAsset;
import net.coffeebrewia.roastengine.render.model.ModelLibrary;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Everything the spawn gun can put in front of you.
 *
 * <p>Two sorts of thing end up in the list:
 *
 * <ul>
 *   <li><b>What is in the level already</b> - every distinct object the world loaded. Spawning
 *       one of these is free: the model is in memory, so it is the same asset drawn again.</li>
 *   <li><b>What the installed mods ship</b> - every model file in every mod's {@code assets/},
 *       whether or not this world uses it. A gun from the arena can be dropped into the
 *       Backrooms. These load the first time they are asked for and are then kept.</li>
 * </ul>
 *
 * <p>The list is built once when a world loads. Nothing here touches OpenGL until something is
 * actually spawned, so building it is cheap.
 */
public final class ItemCatalog {

    /**
     * What sort of thing something is, which is how the list is split up.
     *
     * <p>The guess is made from what the level says about an object - whether it is an entity,
     * whether it can be picked up - and failing that from its name. It will not be right about
     * everything a mod ships, so the list can be searched as well as filtered.
     */
    public enum Category {
        /** Props and scenery you would actually place: crates, chairs, signs. */
        ITEM("Items"),
        /** Things meant to be carried or fired: guns, bottles, torches. */
        USABLE("Usables"),
        /** Creatures. */
        ENTITY("Entities"),
        /** The level itself - walls, floors, shells, chunks. Rarely what you want by hand. */
        DEBUG("Debug");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Words in a name that give away what something is, when the level does not say. */
    private static final String ENTITY_WORDS =
            "(?i).*(faceling|hound|smiler|scrawler|entity|monster|creature|dummy|npc|doorman"
                    + "|janitor|guest|player|zombie|enemy).*";
    private static final String USABLE_WORDS =
            "(?i).*(gun|pistol|revolver|smg|rifle|shotgun|sniper|weapon|ammo|bottle|drink|glass"
                    + "|torch|flashlight|lamp_hand|key|knife|tool|radio).*";
    private static final String DEBUG_WORDS =
            "(?i).*(wall|shell|floor|ceiling|roof|room|corridor|hall|stairs|pillar|platform"
                    + "|ground|water|chunk|map|scene|collision|trigger|level\\d|warehouse"
                    + "|courtyard|lobby|arena|club|terrain|skybox).*";

    /** One thing that can be spawned. */
    public record Item(String name, String group, String assetFile, ModelAsset loaded,
                       Category category) {

        /** True when the model is already in memory - an object the level itself uses. */
        public boolean isReady() {
            return loaded != null;
        }
    }

    private static final String[] MODEL_SUFFIXES = {".obj", ".glb", ".gltf", ".fbx"};

    private final List<Item> items = new ArrayList<>();
    /** One library per mod folder, made on demand, so a model is loaded once and then kept. */
    private final Map<String, ModelLibrary> libraries = new LinkedHashMap<>();
    private final Map<String, Path> assetFolders = new LinkedHashMap<>();

    /**
     * Builds the list for a world.
     *
     * @param modFolders the folders of every enabled mod, not only the ones this world came from
     */
    public ItemCatalog(LoadedWorld world, List<Path> modFolders) {
        if (world != null) {
            for (WorldObject object : world.objects()) {
                if (object.name == null || object.name.isBlank() || SpawnKit.isSpawnGun(object)
                        || SpawnKit.TABLE_NAME.equals(object.name)) {
                    continue;
                }
                if (items.stream().noneMatch(item -> item.name().equals(object.name))) {
                    items.add(new Item(object.name, "In this level", "", object.asset,
                            categoryOf(object)));
                }
            }
        }
        for (Path folder : modFolders == null ? List.<Path>of() : modFolders) {
            addMod(folder);
        }
        items.sort((a, b) -> {
            int category = Integer.compare(a.category().ordinal(), b.category().ordinal());
            return category != 0 ? category : a.name().compareToIgnoreCase(b.name());
        });
    }

    private void addMod(Path folder) {
        Path assets = folder.resolve("assets");
        if (!Files.isDirectory(assets)) {
            return;
        }
        String mod = folder.getFileName().toString();
        assetFolders.put(mod, assets);
        try (Stream<Path> files = Files.list(assets)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                String fileName = file.getFileName().toString();
                String lower = fileName.toLowerCase(Locale.ROOT);
                boolean model = false;
                for (String suffix : MODEL_SUFFIXES) {
                    model |= lower.endsWith(suffix);
                }
                if (model) {
                    String name = stripSuffix(fileName);
                    items.add(new Item(name, mod, fileName, null, categoryOf(name)));
                }
            });
        } catch (IOException e) {
            System.err.println("[Spawn] Could not read " + assets + ": " + e.getMessage());
        }
    }

    public List<Item> items() {
        return items;
    }

    /** How many of a sort there are, for the tab labels. */
    public long count(Category category) {
        return items.stream().filter(item -> item.category() == category).count();
    }

    /**
     * What an object in the level is: the level itself says whether it is an entity or can be
     * picked up, which beats guessing from the name.
     */
    private static Category categoryOf(WorldObject object) {
        if (object.entity) {
            return Category.ENTITY;
        }
        if (object.holdable || object.isDrink()) {
            return Category.USABLE;
        }
        return categoryOf(object.name);
    }

    /** What something with only a name is, going by what the name says. */
    static Category categoryOf(String name) {
        String text = name == null ? "" : name;
        if (text.matches(ENTITY_WORDS)) {
            return Category.ENTITY;
        }
        if (text.matches(USABLE_WORDS)) {
            return Category.USABLE;
        }
        if (text.matches(DEBUG_WORDS)) {
            return Category.DEBUG;
        }
        return Category.ITEM;
    }

    /**
     * The items of a sort whose name or mod matches what has been typed.
     *
     * @param category null for every sort at once
     */
    public List<Item> search(String text, Category category) {
        String wanted = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        return items.stream()
                .filter(item -> category == null || item.category() == category)
                .filter(item -> wanted.isEmpty()
                        || item.name().toLowerCase(Locale.ROOT).contains(wanted)
                        || item.group().toLowerCase(Locale.ROOT).contains(wanted))
                .toList();
    }

    /**
     * Puts an item into the world.
     *
     * <p>Loading happens here, on the thread that owns the OpenGL context, the first time a
     * model from a mod folder is asked for.
     *
     * @return the object placed, or null when the model could not be loaded
     */
    public WorldObject spawn(LoadedWorld world, Item item, Vector3f at, float yaw) {
        ModelAsset asset = item.loaded();
        if (asset == null) {
            ModelLibrary library = libraries.computeIfAbsent(item.group(),
                    mod -> new ModelLibrary(assetFolders.get(mod)));
            asset = library.get(item.assetFile()).orElse(null);
        }
        if (asset == null) {
            System.err.println("[Spawn] Could not load " + item.assetFile() + " from " + item.group());
            return null;
        }
        WorldObject object = new WorldObject(item.name(), item.group(), asset,
                new Matrix4f().translation(at).rotateY(yaw),
                true, false, false, "", "", "", 0f);
        object.entity = item.category() == Category.ENTITY;
        // Anything spawned can be picked up again, so a mistake is carried off rather than
        // left standing in the level for ever.
        object.holdable = true;
        world.add(object);
        return object;
    }

    /** Frees the models this catalog loaded. The world owns the ones it loaded itself. */
    public void dispose() {
        libraries.values().forEach(ModelLibrary::dispose);
        libraries.clear();
    }

    private static String stripSuffix(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}

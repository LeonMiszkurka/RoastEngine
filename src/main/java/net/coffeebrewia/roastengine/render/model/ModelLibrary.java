package net.coffeebrewia.roastengine.render.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Caches loaded {@link ModelAsset}s per project, keyed by asset file name. */
public final class ModelLibrary {

    private final Path assetsDir;
    private final Map<String, ModelAsset> loaded = new HashMap<>();
    private final Set<String> failed = new HashSet<>();

    public ModelLibrary(Path assetsDir) {
        this.assetsDir = assetsDir;
    }

    /** Loads on first use; a file that fails once is not retried until {@link #reload}. */
    public Optional<ModelAsset> get(String fileName) {
        if (fileName == null || fileName.isBlank() || failed.contains(fileName)) {
            return Optional.empty();
        }
        ModelAsset cached = loaded.get(fileName);
        if (cached != null) {
            return Optional.of(cached);
        }
        Path file = assetsDir.resolve(fileName);
        if (!Files.isRegularFile(file)) {
            failed.add(fileName);
            return Optional.empty();
        }
        try {
            ModelAsset asset = ModelLoader.load(file);
            loaded.put(fileName, asset);
            System.out.println("[Models] Loaded " + fileName + " (" + asset.triangleCount() + " triangles)");
            return Optional.of(asset);
        } catch (IOException | RuntimeException e) {
            System.err.println("[Models] Failed to load " + fileName + ": " + e.getMessage());
            failed.add(fileName);
            return Optional.empty();
        }
    }

    public void reload(String fileName) {
        ModelAsset asset = loaded.remove(fileName);
        if (asset != null) {
            asset.dispose();
        }
        failed.remove(fileName);
    }

    public void dispose() {
        loaded.values().forEach(ModelAsset::dispose);
        loaded.clear();
        failed.clear();
    }
}

package net.coffeebrewia.roastengine.script;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every script that ships with the engine has to parse.
 *
 * <p>Scripts in a mod are read at world load and a broken one is switched off with its mistake in
 * the log - which is right for a mod someone downloaded, and quite wrong for the levels we ship,
 * where the player would simply find a disco ball that does not turn.
 */
class ShippedScriptsTest {

    @Test
    void everyShippedScriptParses() throws IOException {
        List<Path> scripts = shippedScripts();
        assertTrue(!scripts.isEmpty(), "found no shipped scripts to check");
        for (Path script : scripts) {
            ScriptError error = Script.check(Files.readString(script));
            assertNull(error, () -> script + " does not parse: " + (error == null ? "" : error.getMessage()));
        }
    }

    /** Every {@code scripts/} folder under mods/ and packs/. */
    private static List<Path> shippedScripts() throws IOException {
        Path project = Path.of("").toAbsolutePath();
        List<Path> roots = Stream.of("mods", "packs").map(project::resolve)
                .filter(Files::isDirectory).toList();
        List<Path> found = new java.util.ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(path -> path.getFileName().toString().endsWith(".py"))
                        .filter(path -> path.getParent().getFileName().toString().equals("scripts"))
                        .sorted()
                        .forEach(found::add);
            }
        }
        return found;
    }
}

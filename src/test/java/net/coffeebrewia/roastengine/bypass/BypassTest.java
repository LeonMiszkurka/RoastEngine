package net.coffeebrewia.roastengine.bypass;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Bypass API and hack clients, as mod authors will write them. */
class BypassTest {

    private static Path write(Path folder, String relative, String text) throws IOException {
        Path file = folder.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return folder;
    }

    private static Path api(Path root, String cheats) throws IOException {
        return write(root.resolve("bypass-api"), BypassApi.MARKER,
                "{\"name\": \"Bypass API\", \"version\": \"1.0.0\", \"cheats\": [" + cheats + "]}");
    }

    @Test
    void anApiUnlocksOnlyWhatItLists(@TempDir Path root) throws IOException {
        BypassApi api = BypassApi.load(api(root, "\"fly\", \"speed\""));
        assertNotNull(api);
        assertTrue(api.allows("fly"));
        assertFalse(api.allows("noclip"));
        // A newer API naming something this build has never heard of is simply skipped.
        assertFalse(BypassApi.load(api(root, "\"fly\", \"timeTravel\"")).allows("timeTravel"));
    }

    @Test
    void aHackClientKeepsOnlyTheCheatsTheApiAllows(@TempDir Path root) throws IOException {
        BypassApi api = BypassApi.load(api(root, "\"fly\", \"fov\""));
        Path mod = write(root.resolve("hack"), HackClient.MARKER, """
                {
                  "name": "Test Hack",
                  "menuKey": "F6",
                  "tabs": [
                    {"name": "Movement", "cheats": ["fly", "noclip"]},
                    {"name": "Nothing",  "cheats": ["noclip"]},
                    {"name": "View",     "cheats": ["fov"]}
                  ],
                  "binds": {"fly": "F", "noclip": "N"},
                  "defaults": {"fov": 110, "noclip": 1}
                }
                """);
        HackClient client = HackClient.load(mod, "Test Hack", api);
        assertNotNull(client);
        // The tab with nothing left in it is dropped, and so are the cheats behind the API.
        assertEquals(2, client.tabs().size());
        assertEquals(java.util.List.of("fly"), client.tabs().get(0).cheats());
        assertEquals(java.util.Set.of("fly"), client.binds().keySet());
        assertEquals(java.util.Set.of("fov"), client.defaults().keySet());
    }

    @Test
    void aClientWithNothingLeftIsRefused(@TempDir Path root) throws IOException {
        BypassApi api = BypassApi.load(api(root, "\"fly\""));
        Path mod = write(root.resolve("hack"), HackClient.MARKER,
                "{\"tabs\": [{\"name\": \"X\", \"cheats\": [\"noclip\"]}]}");
        assertNull(HackClient.load(mod, "Greedy Hack", api));
        // And so is a file that isn't JSON at all.
        Path broken = write(root.resolve("broken"), HackClient.MARKER, "not json");
        assertNull(HackClient.load(broken, "Broken", api));
    }

    @Test
    void withoutAnApiNothingIsOn() {
        Cheats none = Cheats.load(new net.coffeebrewia.roastengine.modding.ModManager(Path.of("no-such-mods")));
        assertFalse(none.isActive());
        assertFalse(none.on("fly"));
        assertEquals(70f, none.value("fov", 70f));
        none.set("fly", 1f);
        assertFalse(none.on("fly"), "a cheat no API allows can never be switched on");
    }

    @Test
    void valuesStayInsideTheirLimits(@TempDir Path root) throws IOException {
        BypassApi api = BypassApi.load(api(root, "\"fov\", \"fly\""));
        Path mod = write(root.resolve("limits"), HackClient.MARKER,
                "{\"tabs\": [{\"name\": \"All\", \"cheats\": [\"fov\", \"fly\"]}]}");
        Cheats cheats = CheatsTestAccess.of(api, HackClient.load(mod, "Limits", api));
        assertTrue(cheats.isActive());
        cheats.set("fov", 5000f);
        assertEquals(140f, cheats.value("fov", 0f));
        cheats.set("fov", -20f);
        assertEquals(50f, cheats.value("fov", 0f));
        cheats.set("fly", 0.4f);
        assertFalse(cheats.on("fly"));
        cheats.set("fly", 1f);
        assertTrue(cheats.on("fly"));
    }
}

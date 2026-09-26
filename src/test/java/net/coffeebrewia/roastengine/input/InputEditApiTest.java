package net.coffeebrewia.roastengine.input;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputEditApiTest {

    private static final String API_JSON = """
            {
              "name": "InputEdit API",
              "version": "1.0.0",
              "defaults": {"deadzone": 0.15, "lookSpeed": 900, "lookCurve": 2.0},
              "axes": {"LEFT_X": 0, "LEFT_Y": 1},
              "buttons": {"A": 0, "START": 7}
            }
            """;

    private static Path apiMod(Path folder, String mappings) throws IOException {
        Files.createDirectories(folder.resolve("inputedit"));
        Files.writeString(folder.resolve(InputEditApi.MARKER), API_JSON);
        if (mappings != null) {
            Files.writeString(folder.resolve(InputEditApi.MAPPINGS), mappings);
        }
        return folder;
    }

    @Test
    void readsTheNamesAndTheDefaults(@TempDir Path temp) throws IOException {
        InputEditApi api = InputEditApi.load(apiMod(temp, null), "inputedit-api");
        assertEquals("InputEdit API", api.name());
        assertEquals(0, api.buttonIndex("A"));
        assertEquals(7, api.buttonIndex("start"), "names are matched whatever their case");
        assertEquals(1, api.axisIndex("LEFT_Y"));
        assertTrue(api.mappings().isEmpty(), "no mappings file: nothing to hand to GLFW");
    }

    @Test
    void takesPadLayoutsFromTheMappingsFileAndSkipsCommentsAndBlanks(@TempDir Path temp)
            throws IOException {
        String file = """
                # Pads GLFW does not know.
                # Another comment.

                03000000000000000000504944564944,Probe Pad,a:b0,b:b1,platform:Mac OS X
                   0300000000000000000050494456494a,Spaced Pad,a:b0,platform:Mac OS X
                """;
        InputEditApi api = InputEditApi.load(apiMod(temp, file), "inputedit-api");
        assertEquals(2, api.mappings().size(), "two real lines, the comments and the blank dropped");
        assertTrue(api.mappings().get(0).startsWith("03000000000000000000504944564944"));
        assertTrue(api.mappings().get(1).startsWith("0300000000000000000050494456494a"),
                "a line is trimmed, so leading spaces do not stop GLFW reading the id");
    }

    @Test
    void theApiModWeShipParsesAndItsMappingsFileIsHarmlessAsItStands() throws IOException {
        // The file ships as comments only: it must load, and add nothing, until someone edits it.
        Path shipped = Path.of("mods/inputedit-api");
        if (!Files.isDirectory(shipped)) {
            return;     // not a checkout with the mods folder beside it
        }
        InputEditApi api = InputEditApi.load(shipped, "inputedit-api");
        assertEquals(0, api.buttonIndex("A"));
        assertEquals(9, api.buttonIndex("LEFT_THUMB"), "what the Xbox scheme binds sprint to");
        assertEquals(2, api.axisIndex("RIGHT_X"));
        assertTrue(api.mappings().isEmpty(), "shipped as instructions, not as layouts");
    }
}

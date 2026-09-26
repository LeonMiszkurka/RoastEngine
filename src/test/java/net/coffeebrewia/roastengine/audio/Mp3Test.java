package net.coffeebrewia.roastengine.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mp3Test {

    @Test
    void somethingThatIsNotAnMp3IsRefusedNotCrashed(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("notmusic.mp3");
        Files.writeString(file, "this is not an mp3, it is a sentence");
        assertThrows(IOException.class, () -> Mp3.decode(file),
                "a mod shipping a broken track must not take the game down with it");
    }

    @Test
    void aMissingFileIsRefused(@TempDir Path dir) {
        assertThrows(IOException.class, () -> Mp3.decode(dir.resolve("gone.mp3")));
    }

    @Test
    void theBackroomsThemeDecodes() throws IOException {
        Path file = Path.of("").toAbsolutePath().resolve("mods/backrooms/audio/theme.mp3");
        if (!Files.isRegularFile(file)) {
            return;   // the mod ships the track; nothing to check without it
        }
        Mp3.Pcm pcm = Mp3.decode(file);

        assertTrue(pcm.sampleRate() >= 8000 && pcm.sampleRate() <= 48_000,
                "odd sample rate: " + pcm.sampleRate());
        assertTrue(pcm.channels() == 1 || pcm.channels() == 2, "odd channel count: " + pcm.channels());
        assertEquals(0, pcm.samples().length % pcm.channels(),
                "interleaved samples must divide evenly between the channels");
        assertTrue(pcm.seconds() > 30, "the theme is a song, not a blip: " + pcm.seconds() + "s");

        // Something has to actually be audible in there: silence would mean a decode that
        // "worked" and produced nothing.
        long loudest = 0;
        for (short sample : pcm.samples()) {
            loudest = Math.max(loudest, Math.abs(sample));
        }
        assertTrue(loudest > 1000, "the decoded track is silent (peak " + loudest + ")");
    }
}

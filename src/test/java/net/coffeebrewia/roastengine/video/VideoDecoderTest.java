package net.coffeebrewia.roastengine.video;

import org.jcodec.api.SequenceEncoder;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trips a video: write an mp4, read it back, and check the frames that come out are the
 * ones that went in. The same encoder makes the stand-in intro, so a mod always has something to
 * play before the real video is dropped in.
 */
class VideoDecoderTest {

    /** 16x16 blocks keep the clip tiny; H.264 works in macroblocks of that size. */
    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;

    @Test
    void readsBackTheFramesItWasGiven(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("clip.mp4");
        writeClip(file, 10);

        List<VideoDecoder.Frame> frames = new ArrayList<>();
        try (VideoDecoder decoder = VideoDecoder.open(file)) {
            assertEquals(WIDTH, decoder.width());
            assertEquals(HEIGHT, decoder.height());
            VideoDecoder.Frame frame;
            while ((frame = decoder.next()) != null) {
                frames.add(frame);
            }
        }

        assertEquals(10, frames.size(), "every frame comes back");
        for (VideoDecoder.Frame frame : frames) {
            assertEquals(WIDTH * HEIGHT * 4, frame.rgba().length, "RGBA, four bytes a pixel");
        }
    }

    @Test
    void framesCarryRisingTimestamps(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("clip.mp4");
        writeClip(file, 6);

        double previous = -1;
        try (VideoDecoder decoder = VideoDecoder.open(file)) {
            VideoDecoder.Frame frame;
            while ((frame = decoder.next()) != null) {
                assertTrue(frame.seconds() > previous,
                        "each frame is later than the one before: " + frame.seconds());
                previous = frame.seconds();
            }
        }
        // 6 frames at 25fps is a quarter of a second.
        assertTrue(previous >= 0.15 && previous <= 0.35, "the clip is about as long as it should be: " + previous);
    }

    @Test
    void keepsTheColoursItWasGiven(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("red.mp4");
        // A flat colour survives H.264 nearly untouched, so it can be checked against.
        writeFlatClip(file, 200, 30, 40);

        try (VideoDecoder decoder = VideoDecoder.open(file)) {
            VideoDecoder.Frame frame = decoder.next();
            assertNotNull(frame);
            int middle = ((HEIGHT / 2) * WIDTH + WIDTH / 2) * 4;
            int red = frame.rgba()[middle] & 0xFF;
            int green = frame.rgba()[middle + 1] & 0xFF;
            int blue = frame.rgba()[middle + 2] & 0xFF;
            int alpha = frame.rgba()[middle + 3] & 0xFF;
            assertTrue(Math.abs(red - 200) < 20, "red came back as " + red);
            assertTrue(Math.abs(green - 30) < 20, "green came back as " + green);
            assertTrue(Math.abs(blue - 40) < 20, "blue came back as " + blue);
            assertEquals(255, alpha, "frames are opaque");
        }
    }

    @Test
    void endOfTheVideoIsNullRatherThanAnError(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("clip.mp4");
        writeClip(file, 3);
        try (VideoDecoder decoder = VideoDecoder.open(file)) {
            for (int i = 0; i < 3; i++) {
                assertNotNull(decoder.next());
            }
            assertNull(decoder.next(), "past the end there is simply nothing");
        }
    }

    @Test
    void somethingThatIsNotAVideoIsRefusedNotCrashed(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("notavideo.mp4");
        Files.writeString(file, "this is not an mp4, it is a sentence");
        assertThrows(IOException.class, () -> VideoDecoder.open(file),
                "a mod shipping a broken video must not take the game down with it");
    }

    // ------------------------------------------------------------------
    // Making clips to read back
    // ------------------------------------------------------------------

    private static void writeClip(Path file, int frames) throws IOException {
        SequenceEncoder encoder = SequenceEncoder.create25Fps(file.toFile());
        for (int i = 0; i < frames; i++) {
            // A moving bright band, so the frames differ from each other.
            Picture picture = Picture.create(WIDTH, HEIGHT, ColorSpace.RGB);
            byte[] rgb = picture.getPlaneData(0);
            java.util.Arrays.fill(rgb, (byte) (40 - 128));
            int band = (i * 4) % HEIGHT;
            for (int x = 0; x < WIDTH; x++) {
                int at = (band * WIDTH + x) * 3;
                rgb[at] = (byte) (220 - 128);
                rgb[at + 1] = (byte) (220 - 128);
                rgb[at + 2] = (byte) (220 - 128);
            }
            encoder.encodeNativeFrame(picture);
        }
        encoder.finish();
    }

    private static void writeFlatClip(Path file, int red, int green, int blue) throws IOException {
        SequenceEncoder encoder = SequenceEncoder.create25Fps(file.toFile());
        Picture picture = Picture.create(WIDTH, HEIGHT, ColorSpace.RGB);
        byte[] rgb = picture.getPlaneData(0);
        for (int i = 0; i < WIDTH * HEIGHT; i++) {
            rgb[i * 3] = (byte) (red - 128);
            rgb[i * 3 + 1] = (byte) (green - 128);
            rgb[i * 3 + 2] = (byte) (blue - 128);
        }
        for (int i = 0; i < 3; i++) {
            encoder.encodeNativeFrame(picture);
        }
        encoder.finish();
    }
}

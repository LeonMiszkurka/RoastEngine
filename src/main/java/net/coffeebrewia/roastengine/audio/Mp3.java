package net.coffeebrewia.roastengine.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decodes an .mp3 into raw samples, so a mod can ship its music as one.
 *
 * <p>Ogg Vorbis is what the engine reads natively (stb_vorbis comes with LWJGL), but most music
 * anyone has to hand is an mp3, and converting it means finding a tool. This is pure Java
 * (JLayer), so an mp3 in a mod folder plays on every platform with nothing installed.
 *
 * <p>The whole file is decoded into memory at once, the same as an Ogg - a few minutes of music
 * is tens of megabytes, which is the price of being able to loop it seamlessly from one buffer.
 */
public final class Mp3 {

    /** Decoded audio: interleaved 16-bit samples, however many channels the file had. */
    public record Pcm(short[] samples, int channels, int sampleRate) {

        /** How long the music runs, in seconds. */
        public float seconds() {
            return channels <= 0 || sampleRate <= 0 ? 0f
                    : samples.length / (float) channels / sampleRate;
        }
    }

    /** Refuses a file that would decode to more than this, rather than running out of memory. */
    private static final int MAX_SAMPLES = 120 * 60 * 44_100;   // two hours of mono at 44.1kHz

    private Mp3() {
    }

    /**
     * Decodes an mp3.
     *
     * @throws IOException if the file is missing, is not an mp3, or is too long to hold in memory
     */
    public static Pcm decode(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("No such file: " + file);
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            Bitstream bitstream = new Bitstream(in);
            Decoder decoder = new Decoder();
            // Frames are small and there are thousands of them, so the samples are gathered into
            // a growing array rather than a list of boxed values.
            short[] samples = new short[1 << 20];
            int count = 0;
            int channels = 0;
            int sampleRate = 0;

            Header header;
            while ((header = bitstream.readFrame()) != null) {
                SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                if (channels == 0) {
                    channels = output.getChannelCount();
                    sampleRate = output.getSampleFrequency();
                }
                int length = output.getBufferLength();
                if (count + length > MAX_SAMPLES) {
                    throw new IOException("That mp3 is far too long to load: " + file.getFileName());
                }
                if (count + length > samples.length) {
                    int wanted = Math.max(samples.length * 2, count + length);
                    short[] bigger = new short[Math.min(wanted, MAX_SAMPLES)];
                    System.arraycopy(samples, 0, bigger, 0, count);
                    samples = bigger;
                }
                System.arraycopy(output.getBuffer(), 0, samples, count, length);
                count += length;
                bitstream.closeFrame();
            }
            bitstream.close();

            if (count == 0 || channels == 0) {
                throw new IOException("Nothing to play in " + file.getFileName());
            }
            short[] exact = new short[count];
            System.arraycopy(samples, 0, exact, 0, count);
            return new Pcm(exact, channels, sampleRate);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // JLayer throws its own BitstreamException/DecoderException for a file it cannot read.
            throw new IOException("Could not decode " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }
}

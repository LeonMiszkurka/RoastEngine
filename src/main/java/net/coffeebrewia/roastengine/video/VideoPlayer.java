package net.coffeebrewia.roastengine.video;

import net.coffeebrewia.roastengine.audio.AudioEngine;
import net.coffeebrewia.roastengine.render.Color;
import net.coffeebrewia.roastengine.render.Renderer2D;
import net.coffeebrewia.roastengine.render.Texture;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plays a video full-screen: an intro before a level starts.
 *
 * <p>Decoding happens on its own thread and finished frames wait in a short queue, so a heavy
 * frame slows the video rather than the game. Playback follows a wall clock: frames that are
 * already late are thrown away instead of shown, so a video that cannot be decoded at full speed
 * still ends when it should.
 *
 * <p><b>Sound.</b> An mp4's audio is nearly always AAC, which the pure-Java decoder cannot read.
 * If an .ogg sits next to the video with the same name ({@code BK_INTRO.mp4} ->
 * {@code BK_INTRO.ogg}) it is played alongside, which is how a mod gives its intro sound.
 *
 * <p>Nothing here is fatal: a missing, broken or unreadable video simply reports itself finished,
 * and whatever was waiting on it carries on.
 */
public final class VideoPlayer implements AutoCloseable {

    /** Frames decoded ahead of time. Enough to ride out a slow frame, small enough to stay live. */
    private static final int QUEUE_SIZE = 8;
    /** A frame this far behind the clock is skipped rather than drawn. */
    private static final double LATE_BY = 0.08;
    /** The name the intro's soundtrack is registered under with the audio engine. */
    private static final String SOUND_NAME = "video_intro";

    private final Thread decodeThread;
    private final BlockingQueue<VideoDecoder.Frame> queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean decoderDone = new AtomicBoolean();

    private final int width;
    private final int height;
    private final AudioEngine audio;
    private final boolean hasSound;

    private Texture texture;
    private ByteBuffer upload;
    private double clock;
    private boolean finished;
    private boolean started;
    /** The frame pulled from the queue but not yet due to be shown. */
    private VideoDecoder.Frame pending;

    private VideoPlayer(VideoDecoder decoder, AudioEngine audio, boolean hasSound) {
        this.width = decoder.width();
        this.height = decoder.height();
        this.audio = audio;
        this.hasSound = hasSound;
        this.decodeThread = new Thread(() -> decodeLoop(decoder), "video-decode");
        this.decodeThread.setDaemon(true);
        this.decodeThread.start();
    }

    /**
     * Opens a video, or returns null if it cannot be played - missing file, unreadable codec,
     * anything. A null player means "there is no intro", which every caller must cope with.
     *
     * @param audio may be null, in which case the video plays silently
     */
    public static VideoPlayer open(Path file, AudioEngine audio) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        VideoDecoder decoder;
        try {
            decoder = VideoDecoder.open(file);
        } catch (IOException e) {
            System.err.println("[Video] Cannot play " + file.getFileName() + ": " + e.getMessage());
            return null;
        }
        boolean sound = loadSound(file, audio);
        System.out.println("[Video] " + file.getFileName() + ": " + decoder.width() + "x" + decoder.height()
                + (sound ? " with sound" : " (no .ogg beside it, so it plays silent)"));
        return new VideoPlayer(decoder, audio, sound);
    }

    /** Looks for the .ogg beside the video and hands it to the audio engine. */
    private static boolean loadSound(Path video, AudioEngine audio) {
        if (audio == null || !audio.isAvailable()) {
            return false;
        }
        String name = video.getFileName().toString();
        int dot = name.lastIndexOf('.');
        Path ogg = video.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + ".ogg");
        return Files.isRegularFile(ogg) && audio.overrideFromFile(SOUND_NAME, ogg);
    }

    private void decodeLoop(VideoDecoder decoder) {
        try (decoder) {
            VideoDecoder.Frame frame;
            while (!stopping.get() && (frame = decoder.next()) != null) {
                // Waits when the queue is full, which is how decoding stays just ahead of playing.
                while (!stopping.get() && !queue.offer(frame, 50, TimeUnit.MILLISECONDS)) {
                    // keep trying until there is room, or until the player is closed
                }
            }
        } catch (IOException e) {
            System.err.println("[Video] Stopped early: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            decoderDone.set(true);
        }
    }

    /** True once the video has played out, or has nothing left to show. */
    public boolean isFinished() {
        return finished;
    }

    /**
     * Advances the video by a frame's worth of time and uploads whatever should be on screen.
     * Must be called on the thread that owns the OpenGL context.
     */
    public void update(float deltaSeconds) {
        if (finished) {
            return;
        }
        if (!started) {
            started = true;
            if (hasSound) {
                audio.play(SOUND_NAME);
            }
        }
        clock += deltaSeconds;

        VideoDecoder.Frame show = null;
        while (true) {
            if (pending == null) {
                pending = queue.poll();
            }
            if (pending == null) {
                break;
            }
            if (pending.seconds() > clock) {
                break;      // not due yet: keep it for a later frame
            }
            show = pending; // due, and possibly already late - keep taking until caught up
            pending = null;
            if (show.seconds() > clock - LATE_BY) {
                break;      // near enough to now to be worth drawing
            }
        }
        if (show != null) {
            uploadFrame(show);
        }
        if (decoderDone.get() && queue.isEmpty() && pending == null) {
            finish();
        }
    }

    /**
     * Copies a frame into the GL texture.
     *
     * <p>Frames arrive top row first; {@link Renderer2D#image} expects textures stored the way
     * the 3D loader leaves them, bottom row first, so the rows go up in reverse.
     */
    private void uploadFrame(VideoDecoder.Frame frame) {
        // The header's size is what the file claims; a frame's own size is the truth, and the
        // two can differ by a row or two of block padding.
        if (texture == null || texture.width() != frame.width() || texture.height() != frame.height()) {
            if (texture != null) {
                texture.dispose();
                MemoryUtil.memFree(upload);
            }
            texture = Texture.streaming(frame.width(), frame.height());
            upload = MemoryUtil.memAlloc(frame.width() * frame.height() * 4);
        }
        int stride = frame.width() * 4;
        upload.clear();
        for (int row = frame.height() - 1; row >= 0; row--) {
            upload.put(frame.rgba(), row * stride, stride);
        }
        upload.flip();
        texture.updateRgba(upload);
    }

    /** Stops the video where it is - what the skip key does. */
    public void skip() {
        finish();
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        stopping.set(true);
        if (hasSound) {
            audio.stopEffects();
        }
    }

    /**
     * Draws the video to fill the screen, keeping its shape: black bars rather than stretching.
     * Before the first frame arrives the screen is simply black, which is what a cut to video
     * should look like anyway.
     */
    public void draw(Renderer2D r, float screenWidth, float screenHeight) {
        r.rect(0, 0, screenWidth, screenHeight, Color.rgb(0x000000));
        if (texture == null) {
            return;
        }
        float scale = Math.min(screenWidth / texture.width(), screenHeight / texture.height());
        float drawWidth = texture.width() * scale;
        float drawHeight = texture.height() * scale;
        r.image(texture, (screenWidth - drawWidth) / 2f, (screenHeight - drawHeight) / 2f,
                drawWidth, drawHeight, Color.rgb(0xFFFFFF));
    }

    @Override
    public void close() {
        finish();
        decodeThread.interrupt();
        if (texture != null) {
            texture.dispose();
            texture = null;
        }
        if (upload != null) {
            MemoryUtil.memFree(upload);
            upload = null;
        }
    }
}

package net.coffeebrewia.roastengine.audio;

import net.coffeebrewia.roastengine.core.GameSettings;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.ALC10.*;

/**
 * Plays sound effects and a looping music track through OpenAL.
 *
 * <p>If no audio device is available the engine runs silently rather than failing - every call
 * becomes a no-op.
 */
public final class AudioEngine {

    /** Simultaneous effects before the oldest is cut off. */
    private static final int EFFECT_VOICES = 16;

    private final GameSettings settings;
    private final Map<String, Integer> buffers = new HashMap<>();
    private final int[] effectSources = new int[EFFECT_VOICES];
    private int nextVoice;
    private int musicSource;
    private String currentMusic = "";

    private long device;
    private long context;
    private boolean available;

    public AudioEngine(GameSettings settings) {
        this.settings = settings;
    }

    public void init() {
        try {
            device = alcOpenDevice((ByteBuffer) null);
            if (device == 0L) {
                System.err.println("[Audio] No audio device - running silent");
                return;
            }
            ALCCapabilities deviceCapabilities = ALC.createCapabilities(device);
            context = alcCreateContext(device, (IntBuffer) null);
            alcMakeContextCurrent(context);
            AL.createCapabilities(deviceCapabilities);

            for (int i = 0; i < EFFECT_VOICES; i++) {
                effectSources[i] = alGenSources();
            }
            musicSource = alGenSources();
            alSourcei(musicSource, AL_LOOPING, AL_TRUE);
            available = true;

            SoundBank.registerDefaults(this);
            System.out.println("[Audio] OpenAL ready (" + buffers.size() + " sounds)");
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            System.err.println("[Audio] Could not start OpenAL - running silent: " + e.getMessage());
            available = false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    // ---------------------------------------------------------------------
    // Sound registration
    // ---------------------------------------------------------------------

    /** Registers mono 16-bit PCM under a name, replacing any sound already using it. */
    public void register(String name, short[] samples, int sampleRate) {
        if (!available) {
            return;
        }
        ShortBuffer data = MemoryUtil.memAllocShort(samples.length);
        try {
            data.put(samples).flip();
            upload(name, data, 1, sampleRate);
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    /**
     * Replaces a built-in sound with an Ogg Vorbis file, if it exists. This is how a mod ships
     * its own music or effects: {@code assets/sounds/<name>.ogg}.
     *
     * @return true when the file was loaded
     */
    public boolean overrideFromFile(String name, Path oggFile) {
        if (!available || !Files.isRegularFile(oggFile)) {
            return false;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer channels = stack.mallocInt(1);
            IntBuffer sampleRate = stack.mallocInt(1);
            ShortBuffer pcm = STBVorbis.stb_vorbis_decode_filename(oggFile.toString(), channels, sampleRate);
            if (pcm == null) {
                System.err.println("[Audio] Could not decode " + oggFile);
                return false;
            }
            try {
                upload(name, pcm, channels.get(0), sampleRate.get(0));
                System.out.println("[Audio] " + name + " <- " + oggFile.getFileName());
                return true;
            } finally {
                MemoryUtil.memFree(pcm);
            }
        }
    }

    private void upload(String name, ShortBuffer pcm, int channels, int sampleRate) {
        Integer old = buffers.get(name);
        if (old != null) {
            // A playing source must let go of the buffer before it can be deleted.
            detach(old);
            alDeleteBuffers(old);
        }
        int buffer = alGenBuffers();
        alBufferData(buffer, channels == 2 ? AL_FORMAT_STEREO16 : AL_FORMAT_MONO16, pcm, sampleRate);
        buffers.put(name, buffer);
    }

    private void detach(int buffer) {
        for (int source : effectSources) {
            if (alGetSourcei(source, AL_BUFFER) == buffer) {
                alSourceStop(source);
                alSourcei(source, AL_BUFFER, 0);
            }
        }
        if (alGetSourcei(musicSource, AL_BUFFER) == buffer) {
            alSourceStop(musicSource);
            alSourcei(musicSource, AL_BUFFER, 0);
            currentMusic = "";
        }
    }

    // ---------------------------------------------------------------------
    // Playback
    // ---------------------------------------------------------------------

    public void play(String name) {
        play(name, 1f, 1f);
    }

    /**
     * Plays a one-shot effect.
     *
     * @param volume 0..1, before the player's volume settings are applied
     * @param pitch  1 = normal; small random variation keeps repeated sounds from droning
     */
    public void play(String name, float volume, float pitch) {
        if (!available) {
            return;
        }
        Integer buffer = buffers.get(name);
        if (buffer == null) {
            return;
        }
        int source = freeVoice();
        alSourceStop(source);
        alSourcei(source, AL_BUFFER, buffer);
        alSourcef(source, AL_GAIN, volume * settings.effectsVolume * settings.masterVolume);
        alSourcef(source, AL_PITCH, pitch);
        alSourcePlay(source);
    }

    /** Starts a looping music track; does nothing if it is already playing. */
    public void playMusic(String name) {
        if (!available || name.equals(currentMusic)) {
            return;
        }
        Integer buffer = buffers.get(name);
        alSourceStop(musicSource);
        if (buffer == null) {
            currentMusic = "";
            return;
        }
        alSourcei(musicSource, AL_BUFFER, buffer);
        currentMusic = name;
        applyMusicVolume(1f);
        alSourcePlay(musicSource);
    }

    public void stopMusic() {
        if (available) {
            alSourceStop(musicSource);
            currentMusic = "";
        }
    }

    /**
     * Updates the music volume from the settings, optionally ducked (e.g. while paused or as the
     * player passes out).
     */
    public void applyMusicVolume(float duck) {
        if (available) {
            alSourcef(musicSource, AL_GAIN, settings.musicVolume * settings.masterVolume * duck);
        }
    }

    /** Slows the music down, for the pass-out effect; 1 = normal speed. */
    public void setMusicPitch(float pitch) {
        if (available) {
            alSourcef(musicSource, AL_PITCH, Math.max(0.3f, pitch));
        }
    }

    /** Stops every playing effect, e.g. when leaving a level. */
    public void stopEffects() {
        if (available) {
            for (int source : effectSources) {
                alSourceStop(source);
            }
        }
    }

    private int freeVoice() {
        for (int i = 0; i < EFFECT_VOICES; i++) {
            int source = effectSources[(nextVoice + i) % EFFECT_VOICES];
            if (alGetSourcei(source, AL_SOURCE_STATE) != AL_PLAYING) {
                nextVoice = (nextVoice + i + 1) % EFFECT_VOICES;
                return source;
            }
        }
        // All busy: steal the next one in rotation.
        int source = effectSources[nextVoice];
        nextVoice = (nextVoice + 1) % EFFECT_VOICES;
        return source;
    }

    public void dispose() {
        if (!available) {
            return;
        }
        alSourceStop(musicSource);
        alDeleteSources(musicSource);
        for (int source : effectSources) {
            alSourceStop(source);
            alDeleteSources(source);
        }
        buffers.values().forEach(buffer -> alDeleteBuffers(buffer));
        buffers.clear();
        alcMakeContextCurrent(0L);
        alcDestroyContext(context);
        alcCloseDevice(device);
        available = false;
    }
}

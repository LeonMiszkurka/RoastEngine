package net.coffeebrewia.roastengine.audio;

import java.util.Random;

/**
 * The engine's built-in sounds, synthesised at startup so the game needs no audio files.
 *
 * <p>Every sound here can be replaced by a mod shipping {@code assets/sounds/<name>.ogg}.
 */
public final class SoundBank {

    public static final String FOOTSTEP = "footstep";
    public static final String DOOR = "door";
    public static final String SIP = "sip";
    public static final String GLASS = "glass";
    public static final String PASS_OUT = "pass_out";
    public static final String WHOOSH = "whoosh";
    public static final String CLICK = "click";
    public static final String MUSIC_CLUB = "music_club";
    public static final String MUSIC_MENU = "music_menu";

    /** Every name a mod may override. */
    public static final String[] ALL = {
            FOOTSTEP, DOOR, SIP, GLASS, PASS_OUT, WHOOSH, CLICK, MUSIC_CLUB, MUSIC_MENU
    };

    private static final int RATE = 44100;

    private SoundBank() {
    }

    static void registerDefaults(AudioEngine audio) {
        audio.register(FOOTSTEP, footstep(), RATE);
        audio.register(DOOR, door(), RATE);
        audio.register(SIP, sip(), RATE);
        audio.register(GLASS, glass(), RATE);
        audio.register(PASS_OUT, passOut(), RATE);
        audio.register(WHOOSH, whoosh(), RATE);
        audio.register(CLICK, click(), RATE);
        audio.register(MUSIC_CLUB, clubMusic(), RATE);
        audio.register(MUSIC_MENU, menuMusic(), RATE);
    }

    // ---------------------------------------------------------------------
    // Effects
    // ---------------------------------------------------------------------

    /** A soft thud: a low sine with a fast decay and a little grit. */
    static short[] footstep() {
        float[] out = buffer(0.14f);
        Random noise = new Random(7);
        for (int i = 0; i < out.length; i++) {
            float t = time(i);
            float envelope = (float) Math.exp(-t * 38);
            float thump = (float) Math.sin(2 * Math.PI * 85 * t) * envelope;
            float grit = (noise.nextFloat() * 2 - 1) * (float) Math.exp(-t * 70) * 0.35f;
            out[i] = (thump + grit) * 0.8f;
        }
        return toPcm(out);
    }

    /** A creaking hinge that wobbles in pitch, ending in a solid knock. */
    static short[] door() {
        float[] out = buffer(1.1f);
        double phase = 0;
        Random noise = new Random(3);
        for (int i = 0; i < out.length; i++) {
            float t = time(i);
            float creakPitch = 180 - 70 * t + 25 * (float) Math.sin(t * 17);
            phase += 2 * Math.PI * creakPitch / RATE;
            // A narrow pulse sounds rough and "wooden" compared with a clean sine.
            float pulse = Math.sin(phase) > 0.75 ? 1f : -0.15f;
            float creakEnvelope = t < 0.85f ? (float) Math.sin(Math.PI * t / 0.85f) : 0f;
            float creak = pulse * creakEnvelope * 0.35f;

            float knock = 0f;
            if (t > 0.9f) {
                float k = t - 0.9f;
                knock = ((float) Math.sin(2 * Math.PI * 70 * k) * 0.9f
                        + (noise.nextFloat() * 2 - 1) * 0.4f) * (float) Math.exp(-k * 30);
            }
            out[i] = creak + knock;
        }
        return toPcm(lowPass(out, 0.35f));
    }

    /** A gulp: a falling pitch sweep with a wet bubble on top. */
    static short[] sip() {
        float[] out = buffer(0.42f);
        double phase = 0;
        Random noise = new Random(11);
        for (int i = 0; i < out.length; i++) {
            float t = time(i);
            float pitch = 320 - 380 * t;
            phase += 2 * Math.PI * Math.max(90, pitch) / RATE;
            float envelope = (float) (Math.sin(Math.PI * Math.min(1, t / 0.42)) * Math.exp(-t * 2));
            float bubble = (float) Math.sin(2 * Math.PI * 900 * t) * (float) Math.exp(-Math.pow((t - 0.12) * 40, 2));
            float wet = (noise.nextFloat() * 2 - 1) * 0.08f;
            out[i] = ((float) Math.sin(phase) * 0.8f + bubble * 0.5f + wet) * envelope;
        }
        return toPcm(lowPass(out, 0.5f));
    }

    /** Glass set down on the bar: a couple of bright, decaying partials. */
    static short[] glass() {
        float[] out = buffer(0.5f);
        for (int i = 0; i < out.length; i++) {
            float t = time(i);
            float envelope = (float) Math.exp(-t * 9);
            out[i] = ((float) Math.sin(2 * Math.PI * 2350 * t) * 0.5f
                    + (float) Math.sin(2 * Math.PI * 3480 * t) * 0.3f
                    + (float) Math.sin(2 * Math.PI * 5120 * t) * 0.15f) * envelope * 0.6f;
        }
        return toPcm(out);
    }

    /** The world sliding away: a woozy falling tone over a deep rumble. */
    static short[] passOut() {
        float[] out = buffer(2.6f);
        double phase = 0;
        Random noise = new Random(5);
        float rumble = 0f;
        for (int i = 0; i < out.length; i++) {
            float t = time(i);
            float pitch = (float) (440 * Math.pow(0.12, t / 2.6)) * (1 + 0.03f * (float) Math.sin(t * 22));
            phase += 2 * Math.PI * pitch / RATE;
            float envelope = Math.min(1f, t * 4) * (1 - t / 2.6f);
            rumble += ((noise.nextFloat() * 2 - 1) - rumble) * 0.02f; // very low-passed noise
            out[i] = ((float) Math.sin(phase) * 0.55f + rumble * 3f) * envelope;
        }
        return toPcm(out);
    }

    /** Rising wind, for the janitor's exit. */
    static short[] whoosh() {
        float[] out = buffer(3.5f);
        Random noise = new Random(9);
        float filtered = 0f;
        for (int i = 0; i < out.length; i++) {
            float t = time(i);
            // Opening the filter over time makes the noise rise in pitch.
            float cutoff = 0.02f + 0.25f * (t / 3.5f);
            filtered += ((noise.nextFloat() * 2 - 1) - filtered) * cutoff;
            float envelope = (float) Math.sin(Math.PI * t / 3.5f);
            out[i] = filtered * envelope * 2.2f;
        }
        return toPcm(out);
    }

    /** A crisp UI tick. */
    static short[] click() {
        float[] out = buffer(0.05f);
        for (int i = 0; i < out.length; i++) {
            float t = time(i);
            out[i] = (float) Math.sin(2 * Math.PI * 1700 * t) * (float) Math.exp(-t * 120) * 0.6f;
        }
        return toPcm(out);
    }

    // ---------------------------------------------------------------------
    // Music
    // ---------------------------------------------------------------------

    /**
     * An eight-second, four-bar club loop at 120 BPM: four-on-the-floor kick, off-beat hats, a
     * walking bassline and a soft pad - with a murmuring crowd underneath.
     */
    static short[] clubMusic() {
        float bpm = 120f;
        float beat = 60f / bpm;
        float[] out = buffer(beat * 16);
        Random noise = new Random(21);
        float[] bassNotes = {55.00f, 55.00f, 65.41f, 49.00f};        // A1 A1 C2 G1, one per bar
        float[][] chords = {{220f, 261.63f, 329.63f}, {220f, 261.63f, 329.63f},
                {261.63f, 329.63f, 392f}, {196f, 246.94f, 293.66f}};  // Am Am C G
        float crowd = 0f;
        float crowdLevel = 0f;

        for (int i = 0; i < out.length; i++) {
            float t = time(i);
            int bar = (int) (t / (beat * 4)) % 4;
            float inBeat = t % beat;
            float inEighth = t % (beat / 2);
            float sample = 0f;

            // Kick: a sine that drops in pitch, on every beat.
            float kickPitch = 50 + 90 * (float) Math.exp(-inBeat * 30);
            sample += (float) Math.sin(2 * Math.PI * kickPitch * inBeat) * (float) Math.exp(-inBeat * 9) * 0.9f;

            // Hi-hat: short noise burst on the off-beat.
            float offBeat = (t + beat / 2) % beat;
            float hiss = noise.nextFloat() * 2 - 1;
            sample += hiss * (float) Math.exp(-offBeat * 60) * 0.18f;

            // Bass: a square-ish tone plucked on every eighth note, root and octave.
            float bassFreq = bassNotes[bar] * (((int) (t / (beat / 2))) % 2 == 0 ? 1f : 2f);
            float bass = Math.sin(2 * Math.PI * bassFreq * t) > 0 ? 1f : -1f;
            sample += bass * (float) Math.exp(-inEighth * 6) * 0.14f;

            // Pad: soft triangle-ish chord, swelling each bar.
            float barPosition = (t % (beat * 4)) / (beat * 4);
            float padEnvelope = 0.5f + 0.5f * (float) Math.sin(Math.PI * barPosition);
            for (float note : chords[bar]) {
                sample += triangle(note * t) * 0.045f * padEnvelope;
            }

            // Crowd: very low-passed noise whose loudness drifts, like chatter.
            crowd += (hiss - crowd) * 0.05f;
            crowdLevel += ((0.6f + 0.4f * (float) Math.sin(t * 1.7 + Math.sin(t * 0.6))) - crowdLevel) * 0.001f;
            sample += crowd * crowdLevel * 0.9f;

            out[i] = softClip(sample * 0.8f);
        }
        smoothLoop(out);
        return toPcm(out);
    }

    /** A slow, calm pad for the menus. */
    static short[] menuMusic() {
        float[] out = buffer(12f);
        float[][] chords = {{220f, 277.18f, 329.63f}, {196f, 246.94f, 293.66f},
                {174.61f, 220f, 261.63f}, {196f, 246.94f, 329.63f}};
        for (int i = 0; i < out.length; i++) {
            float t = time(i);
            int chord = (int) (t / 3f) % chords.length;
            float position = (t % 3f) / 3f;
            float envelope = (float) Math.sin(Math.PI * position);
            float sample = 0f;
            for (float note : chords[chord]) {
                sample += (float) Math.sin(2 * Math.PI * note * t) * 0.08f
                        + (float) Math.sin(2 * Math.PI * note * 2.003f * t) * 0.02f;
            }
            out[i] = sample * envelope * 2.2f;
        }
        smoothLoop(out);
        return toPcm(out);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static float[] buffer(float seconds) {
        return new float[(int) (seconds * RATE)];
    }

    private static float time(int sample) {
        return sample / (float) RATE;
    }

    private static float triangle(float cycles) {
        float fraction = cycles - (float) Math.floor(cycles);
        return 4f * Math.abs(fraction - 0.5f) - 1f;
    }

    private static float softClip(float value) {
        return (float) Math.tanh(value);
    }

    /** One-pole low-pass filter; smaller amounts cut more treble. */
    private static float[] lowPass(float[] in, float amount) {
        float[] out = new float[in.length];
        float state = 0f;
        for (int i = 0; i < in.length; i++) {
            state += (in[i] - state) * amount;
            out[i] = state;
        }
        return out;
    }

    /** Fades the last few milliseconds so a looping track does not click at the seam. */
    private static void smoothLoop(float[] samples) {
        int fade = Math.min(samples.length / 4, RATE / 100);
        for (int i = 0; i < fade; i++) {
            float weight = i / (float) fade;
            samples[samples.length - 1 - i] *= weight;
            samples[i] *= weight;
        }
    }

    /** Normalises to a safe peak and converts to signed 16-bit samples. */
    static short[] toPcm(float[] samples) {
        float peak = 0f;
        for (float sample : samples) {
            peak = Math.max(peak, Math.abs(sample));
        }
        float gain = peak > 0.89f ? 0.89f / peak : 1f;
        short[] pcm = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            pcm[i] = (short) Math.round(Math.max(-1f, Math.min(1f, samples[i] * gain)) * 32767);
        }
        return pcm;
    }
}

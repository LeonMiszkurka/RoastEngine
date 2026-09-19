package net.coffeebrewia.roastengine.core;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;

/**
 * Copies everything the game prints into {@code <data>/logs/<name>-latest.log}.
 *
 * <p>A packaged game has no console, so without this a crash leaves nothing behind. With it,
 * a player can send the file along with a bug report. The previous run is kept as
 * {@code <name>-previous.log}, since a crash is often only noticed after starting again.
 */
public final class GameLog {

    private GameLog() {
    }

    /** Starts logging when packaged (or with {@code -Droastengine.log=true}). Safe to call once, early. */
    public static void start(String name) {
        if (!GameHome.isPackaged() && !Boolean.getBoolean("roastengine.log")) {
            return;
        }
        Path logs = GameHome.dataDirectory().resolve("logs");
        Path latest = logs.resolve(name + "-latest.log");
        try {
            Files.createDirectories(logs);
            if (Files.exists(latest)) {
                Files.move(latest, logs.resolve(name + "-previous.log"), StandardCopyOption.REPLACE_EXISTING);
            }
            OutputStream file = Files.newOutputStream(latest);
            System.setOut(new PrintStream(new Tee(System.out, file), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new Tee(System.err, file), true, StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[Log] Could not write " + latest + ": " + e.getMessage());
            return;
        }
        System.out.println("[Log] " + ZonedDateTime.now() + "  " + name
                + "  |  " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")  |  Java " + System.getProperty("java.version"));
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            System.err.println("[Crash] Uncaught in thread " + thread.getName() + ":");
            error.printStackTrace();
        });
    }

    /** Writes to the console and the log file at once. */
    private static final class Tee extends OutputStream {
        private final OutputStream console;
        private final OutputStream file;

        Tee(OutputStream console, OutputStream file) {
            this.console = console;
            this.file = file;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            console.write(b);
            file.write(b);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
            console.write(bytes, offset, length);
            file.write(bytes, offset, length);
        }

        @Override
        public synchronized void flush() throws IOException {
            console.flush();
            file.flush();
        }
    }
}

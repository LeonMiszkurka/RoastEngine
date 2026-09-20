package net.coffeebrewia.roastengine.server;

import net.coffeebrewia.roastengine.net.Protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Settings from {@code server.properties} in the server's working folder. A missing file is
 * written out with the defaults on first start, and settings missing from an older file are
 * added, so there is always a complete one to edit.
 */
final class ServerConfig {

    int port = Protocol.DEFAULT_PORT;
    String name = "RoastEngine Server";
    String motd = "Welcome! Press T to chat, hold Tab to see who is here.";
    /** World mod everyone plays, by name or folder; empty lets each player use their own. */
    String world = "";
    int maxPlayers = 16;
    /**
     * Minutes with nobody online before the server exits with {@link RoastServer#EXIT_IDLE}, which
     * the cloud setup turns into powering the machine off. 0 keeps it running forever.
     */
    double idleShutdownMinutes = 0;
    /**
     * The CoffeeBrew Interactive account service. Set, every player must sign in, and ranks and
     * bans come from it. Empty runs without accounts, for testing: players pick any name.
     */
    String accountsUrl = "";
    /** Proves to the account service that this is a real server. Secret: keep this file private. */
    String serverKey = "";
    /** Without accounts only: ranks for testing, e.g. Leon=owner,Ana=moderator. */
    String localRanks = "";

    static ServerConfig load(Path file) throws IOException {
        ServerConfig config = new ServerConfig();
        if (!Files.isRegularFile(file)) {
            config.save(file);
            System.out.println("[Server] Wrote default settings to " + file.toAbsolutePath());
            return config;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        }
        config.port = integer(props, "port", config.port, 1, 65535);
        config.name = props.getProperty("name", config.name).trim();
        config.motd = props.getProperty("motd", config.motd).trim();
        config.world = props.getProperty("world", config.world).trim();
        config.maxPlayers = integer(props, "maxPlayers", config.maxPlayers, 1, 256);
        config.idleShutdownMinutes = decimal(props, "idleShutdownMinutes", config.idleShutdownMinutes);
        config.accountsUrl = props.getProperty("accountsUrl", config.accountsUrl).trim();
        config.serverKey = props.getProperty("serverKey", config.serverKey).trim();
        config.localRanks = props.getProperty("localRanks", config.localRanks).trim();
        if (!props.stringPropertyNames().containsAll(KEYS)) {
            config.save(file); // an older file: fill in the new settings, keeping the values
        }
        return config;
    }

    private static final java.util.List<String> KEYS =
            java.util.List.of("port", "name", "motd", "world", "maxPlayers", "idleShutdownMinutes",
                    "accountsUrl", "serverKey", "localRanks");

    private void save(Path file) throws IOException {
        Properties props = new Properties();
        props.setProperty("port", String.valueOf(port));
        props.setProperty("name", name);
        props.setProperty("motd", motd);
        props.setProperty("world", world);
        props.setProperty("maxPlayers", String.valueOf(maxPlayers));
        props.setProperty("idleShutdownMinutes", idleShutdownMinutes == Math.rint(idleShutdownMinutes)
                ? String.valueOf((long) idleShutdownMinutes) : String.valueOf(idleShutdownMinutes));
        props.setProperty("accountsUrl", accountsUrl);
        props.setProperty("serverKey", serverKey);
        props.setProperty("localRanks", localRanks);
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "RoastEngine server. world = the world mod everyone plays "
                    + "(name or folder), or empty to let each player use their own. "
                    + "idleShutdownMinutes = turn off after this long with nobody on (0 = never). "
                    + "accountsUrl + serverKey = the CoffeeBrew account service (empty: no accounts, "
                    + "for testing; then localRanks gives test ranks, e.g. Leon=owner,Ana=moderator).");
        }
    }

    private static double decimal(Properties props, String key, double fallback) {
        try {
            return Math.max(0, Double.parseDouble(props.getProperty(key, String.valueOf(fallback)).trim()));
        } catch (NumberFormatException e) {
            System.err.println("[Server] Ignoring bad " + key + " in server.properties");
            return fallback;
        }
    }

    private static int integer(Properties props, String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(props.getProperty(key, String.valueOf(fallback)).trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            System.err.println("[Server] Ignoring bad " + key + " in server.properties");
            return fallback;
        }
    }
}

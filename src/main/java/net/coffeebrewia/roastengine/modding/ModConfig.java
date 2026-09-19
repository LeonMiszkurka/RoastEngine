package net.coffeebrewia.roastengine.modding;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * mod.io credentials and settings, persisted to {@code config/modio.properties}.
 *
 * <p>Environment variables ({@code MODIO_API_KEY}, {@code MODIO_OAUTH_TOKEN}, {@code MODIO_GAME_ID},
 * {@code MODIO_USER_ID}) override the file so secrets can be kept out of disk in CI or dev setups.
 *
 * <p><b>Note:</b> the file is stored in plain text. Don't commit it (it is git-ignored).
 */
public final class ModConfig {

    /**
     * Every game now has its own API host, {@code https://g-<gameId>.modapi.io/v1}, shown on the
     * game's dashboard. It is derived from the Game ID unless {@code apiUrl} is set explicitly.
     */
    public static final String PER_GAME_API_URL = "https://g-%s.modapi.io/v1";
    /** The old shared host. It now answers every request with HTTP 404 "deprecated". */
    public static final String LEGACY_API_URL = "https://api.mod.io/v1";

    private final Path file;

    /** Blank means "derive from the Game ID"; set it only for a non-standard host. */
    private String apiUrl = "";
    private String gameId = "";
    private String apiKey = "";
    private String oauthToken = "";
    private String userId = "";
    private String username = "";

    public ModConfig(Path file) {
        this.file = file;
    }

    /**
     * Loads the identity shipped with the game from {@code /modio-defaults.properties}, so players
     * never type a Game ID or API key - they just sign in with their email address.
     */
    private void loadShippedDefaults() {
        try (InputStream in = ModConfig.class.getResourceAsStream("/modio-defaults.properties")) {
            if (in == null) {
                return;
            }
            Properties props = new Properties();
            props.load(in);
            gameId = props.getProperty("gameId", "").trim();
            apiKey = props.getProperty("apiKey", "").trim();
        } catch (IOException e) {
            System.err.println("[ModConfig] Could not read shipped defaults: " + e.getMessage());
        }
    }

    public synchronized void load() {
        loadShippedDefaults();
        if (Files.isRegularFile(file)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
                // Only override what the player's own file actually contains.
                apiUrl = props.getProperty("apiUrl", apiUrl);
                gameId = props.getProperty("gameId", gameId);
                apiKey = props.getProperty("apiKey", apiKey);
                oauthToken = props.getProperty("oauthToken", oauthToken);
                userId = props.getProperty("userId", userId);
                username = props.getProperty("username", username);
            } catch (IOException e) {
                System.err.println("[ModConfig] Could not read " + file + ": " + e.getMessage());
            }
        }
        apiKey = envOr("MODIO_API_KEY", apiKey);
        oauthToken = envOr("MODIO_OAUTH_TOKEN", oauthToken);
        gameId = envOr("MODIO_GAME_ID", gameId);
        userId = envOr("MODIO_USER_ID", userId);
    }

    public synchronized void save() throws IOException {
        Properties props = new Properties();
        props.setProperty("apiUrl", apiUrl);
        props.setProperty("gameId", gameId);
        props.setProperty("apiKey", apiKey);
        props.setProperty("oauthToken", oauthToken);
        props.setProperty("userId", userId);
        props.setProperty("username", username);
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "RoastEngine mod.io configuration - keep this file private");
        }
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** Minimum needed to browse mods: a game id plus either an API key or an OAuth token. */
    public synchronized boolean isConfigured() {
        return !gameId.isBlank() && (!apiKey.isBlank() || !oauthToken.isBlank());
    }

    /** True once the player has signed in with their mod.io account. */
    public synchronized boolean isSignedIn() {
        return !oauthToken.isBlank();
    }

    public synchronized void setOauthToken(String token) {
        this.oauthToken = token == null ? "" : token.trim();
    }

    public synchronized void setUser(String id, String name) {
        this.userId = id == null ? "" : id.trim();
        this.username = name == null ? "" : name.trim();
    }

    public synchronized String username() {
        return username;
    }

    /** Forgets the signed-in account, keeping the game's own shipped identity. */
    public synchronized void signOut() {
        oauthToken = "";
        userId = "";
        username = "";
    }

    public synchronized void update(String gameId, String apiKey, String oauthToken, String userId) {
        this.gameId = gameId.trim();
        this.apiKey = apiKey.trim();
        this.oauthToken = oauthToken.trim();
        this.userId = userId.trim();
    }

    /**
     * The API base URL: the configured one if set, otherwise this game's own host.
     * A stored legacy {@code api.mod.io} URL is ignored, since that host is retired.
     */
    public synchronized String apiUrl() {
        String url = apiUrl.trim();
        if (url.isBlank() || url.startsWith(LEGACY_API_URL)) {
            url = gameId.isBlank() ? LEGACY_API_URL : String.format(PER_GAME_API_URL, gameId);
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public synchronized String gameId() {
        return gameId;
    }

    public synchronized String apiKey() {
        return apiKey;
    }

    public synchronized String oauthToken() {
        return oauthToken;
    }

    public synchronized String userId() {
        return userId;
    }
}

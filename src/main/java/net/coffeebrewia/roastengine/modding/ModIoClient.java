package net.coffeebrewia.roastengine.modding;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * Thin asynchronous client for the mod.io REST API (v1), built on {@link HttpClient} and Gson.
 *
 * <p>mod.io has no official Java SDK, so the REST API is used directly:
 * <ul>
 *   <li>Read-only requests authenticate with {@code ?api_key=...}.</li>
 *   <li>User requests (e.g. {@code GET /me}) use {@code Authorization: Bearer <oauth token>}.</li>
 * </ul>
 * All methods return {@link CompletableFuture}s completed off the main thread; callers
 * must marshal results back to the render thread before touching UI state.
 *
 * @see <a href="https://docs.mod.io/restapiref/">mod.io REST API reference</a>
 */
public final class ModIoClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final ModConfig config;
    private final Executor executor;
    private final HttpClient http;

    public ModIoClient(ModConfig config, Executor executor) {
        this.config = config;
        this.executor = executor;
        this.http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Result of {@link #checkConnection()}. */
    public record ConnectionStatus(boolean ok, String message) {
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * Verifies credentials: fetches the configured game, and if an OAuth token is set also
     * fetches {@code /me} and compares the returned id against the configured User ID.
     */
    public CompletableFuture<ConnectionStatus> checkConnection() {
        if (!config.isConfigured()) {
            return CompletableFuture.completedFuture(
                    new ConnectionStatus(false, "Not configured (Game ID + API key or token required)"));
        }

        CompletableFuture<String> game = getJson("/games/" + encode(config.gameId()), "")
                .thenApply(json -> string(json, "name", "game " + config.gameId()));

        return game.thenCompose(gameName -> {
            if (config.oauthToken().isBlank()) {
                return CompletableFuture.completedFuture(
                        new ConnectionStatus(true, "Connected to mod.io - " + gameName));
            }
            return getJson("/me", "").thenApply(me -> {
                String username = string(me, "username", "?");
                String remoteId = String.valueOf(me.has("id") ? me.get("id").getAsLong() : 0);
                if (!config.userId().isBlank() && !config.userId().equals(remoteId)) {
                    return new ConnectionStatus(false,
                            "Token belongs to user " + remoteId + ", not " + config.userId());
                }
                return new ConnectionStatus(true, "Connected - " + gameName + " as " + username);
            });
        }).exceptionally(t -> new ConnectionStatus(false, describe(t)));
    }

    // ---------------------------------------------------------------------
    // Email sign-in (mod.io "email exchange"): the same flow console games use -
    // the player types an email, mod.io sends a 5-digit code, the code becomes a token.
    // ---------------------------------------------------------------------

    /** Asks mod.io to email a 5-digit security code to this address. */
    public CompletableFuture<Void> requestEmailCode(String email) {
        return postForm("/oauth/emailrequest", "email=" + encode(email.trim()))
                .thenApply(response -> null);
    }

    /**
     * Exchanges the emailed code for an OAuth access token and stores it, then loads the
     * account name so the menu can show who is signed in.
     */
    public CompletableFuture<String> signInWithCode(String code) {
        return postForm("/oauth/emailexchange", "security_code=" + encode(code.trim()))
                .thenCompose(response -> {
                    String token = string(response, "access_token", "");
                    if (token.isBlank()) {
                        return CompletableFuture.failedFuture(
                                new IOException("mod.io did not return an access token"));
                    }
                    config.setOauthToken(token);
                    return getJson("/me", "").thenApply(me -> {
                        String name = string(me, "username", "mod.io user");
                        config.setUser(String.valueOf(me.has("id") ? me.get("id").getAsLong() : 0), name);
                        return name;
                    });
                });
    }

    /** POSTs a form-encoded body; auth endpoints take the API key as a query parameter. */
    private CompletableFuture<JsonObject> postForm(String path, String body) {
        String url = config.apiUrl() + path + "?api_key=" + encode(config.apiKey());
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "RoastEngine/0.1 (CoffeBrewIA)")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            JsonObject json = parseObject(response.body());
            if (response.statusCode() / 100 != 2) {
                String message = json.has("error")
                        ? string(json.getAsJsonObject("error"), "message", "Unknown error")
                        : "HTTP " + response.statusCode();
                throw new CompletionException(new IOException(message));
            }
            return json;
        });
    }

    /** Lists mods for the configured game (one page). */
    public CompletableFuture<List<ModInfo>> fetchMods(int offset, int limit) {
        String query = "_offset=" + offset + "&_limit=" + limit;
        return getJson("/games/" + encode(config.gameId()) + "/mods", query).thenApply(json -> {
            List<ModInfo> mods = new ArrayList<>();
            if (json.has("data") && json.get("data").isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray("data")) {
                    mods.add(parseMod(element.getAsJsonObject()));
                }
            }
            return mods;
        });
    }

    /** Looks up one mod by its mod.io id, e.g. one a multiplayer server asks for. */
    public CompletableFuture<ModInfo> fetchMod(long modId) {
        return getJson("/games/" + encode(config.gameId()) + "/mods/" + modId, "")
                .thenApply(ModIoClient::parseMod);
    }

    /**
     * Downloads the mod's current file to {@code destination}, verifying the MD5 hash when
     * mod.io provides one. The partially-written file is deleted on failure.
     */
    public CompletableFuture<Path> downloadModFile(ModInfo mod, Path destination) {
        if (!mod.isDownloadable()) {
            return CompletableFuture.failedFuture(new IOException(mod.name() + " has no downloadable file"));
        }
        HttpRequest request = authorized(HttpRequest.newBuilder(URI.create(mod.downloadUrl())))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();

        return CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(destination.getParent());
                Path partial = destination.resolveSibling(destination.getFileName() + ".part");
                HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(partial));
                if (response.statusCode() / 100 != 2) {
                    Files.deleteIfExists(partial);
                    throw new IOException("Download failed with HTTP " + response.statusCode());
                }
                verifyMd5(partial, mod.md5());
                Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
                return destination;
            } catch (IOException e) {
                throw new CompletionException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
        }, executor);
    }

    // ---------------------------------------------------------------------
    // HTTP helpers
    // ---------------------------------------------------------------------

    private CompletableFuture<JsonObject> getJson(String path, String query) {
        StringBuilder url = new StringBuilder(config.apiUrl()).append(path).append('?');
        if (!query.isEmpty()) {
            url.append(query).append('&');
        }
        // Read endpoints accept api_key; /me requires the OAuth token only.
        if (config.oauthToken().isBlank() && !config.apiKey().isBlank()) {
            url.append("api_key=").append(encode(config.apiKey()));
        }

        HttpRequest request = authorized(HttpRequest.newBuilder(URI.create(url.toString())))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            JsonObject body = parseObject(response.body());
            if (response.statusCode() / 100 != 2) {
                String message = body.has("error")
                        ? string(body.getAsJsonObject("error"), "message", "Unknown error")
                        : "HTTP " + response.statusCode();
                throw new CompletionException(new IOException(
                        "mod.io " + response.statusCode() + ": " + message));
            }
            return body;
        });
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
        if (!config.oauthToken().isBlank()) {
            builder.header("Authorization", "Bearer " + config.oauthToken());
        }
        return builder.header("User-Agent", "RoastEngine/0.1 (CoffeBrewIA)");
    }

    private static JsonObject parseObject(String text) {
        try {
            JsonElement element = JsonParser.parseString(text == null ? "" : text);
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    // ---------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------

    private static ModInfo parseMod(JsonObject o) {
        long modfileId = 0;
        long fileSize = 0;
        String md5 = "";
        String downloadUrl = "";
        if (o.has("modfile") && o.get("modfile").isJsonObject()) {
            JsonObject file = o.getAsJsonObject("modfile");
            modfileId = number(file, "id");
            fileSize = number(file, "filesize");
            if (file.has("filehash") && file.get("filehash").isJsonObject()) {
                md5 = string(file.getAsJsonObject("filehash"), "md5", "");
            }
            if (file.has("download") && file.get("download").isJsonObject()) {
                downloadUrl = string(file.getAsJsonObject("download"), "binary_url", "");
            }
        }
        String author = o.has("submitted_by") && o.get("submitted_by").isJsonObject()
                ? string(o.getAsJsonObject("submitted_by"), "username", "unknown")
                : "unknown";
        long downloads = o.has("stats") && o.get("stats").isJsonObject()
                ? number(o.getAsJsonObject("stats"), "downloads_total")
                : 0;

        return new ModInfo(
                number(o, "id"),
                string(o, "name_id", "mod-" + number(o, "id")),
                string(o, "name", "Unnamed mod"),
                string(o, "summary", ""),
                author,
                downloads,
                modfileId,
                fileSize,
                md5,
                downloadUrl);
    }

    private static String string(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
    }

    private static long number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsLong() : 0L;
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static void verifyMd5(Path file, String expected) throws IOException {
        if (expected == null || expected.isBlank()) {
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(expected)) {
                Files.deleteIfExists(file);
                throw new IOException("Checksum mismatch (expected " + expected + ", got " + actual + ")");
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Unwraps CompletionExceptions into a short user-facing message. */
    public static String describe(Throwable t) {
        Throwable cause = t;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}

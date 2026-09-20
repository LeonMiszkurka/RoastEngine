package net.coffeebrewia.roastengine.account;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.coffeebrewia.roastengine.net.Protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * The player's CoffeeBrew Interactive account - one account for every CoffeeBrew game.
 *
 * <p>Signing in swaps a username and password for a session token, which is kept in
 * {@code config/coffeebrew.properties} so the player stays signed in; the password itself is
 * never saved. Joining a server swaps the token for a one-time join ticket (see
 * {@code Server/aws/accounts_lambda.py}), so the token never crosses the unencrypted game
 * connection.
 *
 * <p>The service's address comes from {@code /coffeebrew-defaults.properties}, or
 * {@code -Droastengine.accountsUrl=...} for testing against a local stand-in.
 */
public final class CoffeeBrewAccount {

    private static final Duration TIMEOUT = Duration.ofSeconds(12);

    private final Path file;
    private final Executor executor;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final String url;

    // Written on the background thread that completes a call, read on the main thread.
    private volatile String token = "";
    private volatile String username = "";
    private volatile int rank = Protocol.RANK_NORMAL;

    public CoffeeBrewAccount(Path file, Executor executor) {
        this.file = file;
        this.executor = executor;
        Properties defaults = new Properties();
        try (InputStream in = CoffeeBrewAccount.class.getResourceAsStream("/coffeebrew-defaults.properties")) {
            if (in != null) {
                defaults.load(in);
            }
        } catch (IOException e) {
            System.err.println("[Account] Could not read coffeebrew-defaults.properties: " + e.getMessage());
        }
        this.url = System.getProperty("roastengine.accountsUrl", defaults.getProperty("accountsUrl", "")).trim();
        load();
    }

    /** False until the account service's address is filled in; the menu then says so. */
    public boolean isAvailable() {
        return !url.isEmpty();
    }

    public boolean isSignedIn() {
        return !token.isEmpty();
    }

    public String username() {
        return username;
    }

    public int rank() {
        return rank;
    }

    // --- Signing in and out -------------------------------------------------------------

    public CompletableFuture<Void> register(String name, String password) {
        return signIn("register", name, password);
    }

    public CompletableFuture<Void> login(String name, String password) {
        return signIn("login", name, password);
    }

    private CompletableFuture<Void> signIn(String action, String name, String password) {
        JsonObject body = new JsonObject();
        body.addProperty("username", name.trim());
        body.addProperty("password", password);
        return call(action, body).thenAccept(reply -> {
            token = reply.get("token").getAsString();
            remember(reply.getAsJsonObject("account"));
            save();
        });
    }

    /**
     * Checks the saved session is still good and picks up rank changes. A session the service no
     * longer accepts signs the player out; being offline keeps them signed in.
     */
    public CompletableFuture<Void> refresh() {
        if (!isSignedIn()) {
            return CompletableFuture.completedFuture(null);
        }
        JsonObject body = new JsonObject();
        body.addProperty("token", token);
        return call("me", body).handle((reply, error) -> {
            if (error == null) {
                remember(reply.getAsJsonObject("account"));
                save();
            } else if (cause(error) instanceof Refused) {
                forget();
            }
            return null;
        });
    }

    public void signOut() {
        if (isSignedIn()) {
            JsonObject body = new JsonObject();
            body.addProperty("token", token);
            call("logout", body); // best effort; the local sign-out happens regardless
        }
        forget();
    }

    /**
     * A one-time ticket for joining a server, valid for a minute. Blocks, so call it off the main
     * thread - right before connecting.
     *
     * @throws IOException with a message fit to show the player
     */
    public String joinTicket() throws IOException {
        if (!isSignedIn()) {
            throw new IOException("Sign in to a CoffeeBrew Interactive account (main menu) to play online.");
        }
        JsonObject body = new JsonObject();
        body.addProperty("token", token);
        try {
            // Runs right here: this is already a background thread, and waiting on the shared
            // pool from inside it could stall the game.
            return callNow("ticket", body).get("ticket").getAsString();
        } catch (Refused e) {
            forget(); // the session ran out: signing in again fixes it
            throw e;
        }
    }

    private void remember(JsonObject account) {
        username = account.get("username").getAsString();
        rank = Protocol.rankFromName(account.get("rank").getAsString());
    }

    private void forget() {
        token = "";
        username = "";
        rank = Protocol.RANK_NORMAL;
        save();
    }

    // --- The service --------------------------------------------------------------------

    /** The service said no, with a message for the player (as opposed to being unreachable). */
    public static final class Refused extends IOException {
        Refused(String message) {
            super(message);
        }
    }

    private CompletableFuture<JsonObject> call(String action, JsonObject body) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return callNow(action, body);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    /** One request to the service, on the calling thread. */
    private JsonObject callNow(String action, JsonObject body) throws IOException {
        if (!isAvailable()) {
            throw new IOException("Accounts aren't set up in this build yet.");
        }
        body.addProperty("action", action);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IOException("Can't reach CoffeeBrew Interactive. Check your internet connection.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.", e);
        }
        JsonObject reply;
        try {
            reply = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("CoffeeBrew Interactive sent something unexpected.", e);
        }
        if (!reply.has("ok") || !reply.get("ok").getAsBoolean()) {
            throw new Refused(reply.has("error") ? reply.get("error").getAsString() : "Something went wrong.");
        }
        return reply;
    }

    /** The real reason behind a CompletionException, for showing the player. */
    public static Throwable cause(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    // --- Remembering the session ----------------------------------------------------------

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            System.err.println("[Account] Could not read " + file + ": " + e.getMessage());
            return;
        }
        token = props.getProperty("token", "");
        username = props.getProperty("username", "");
        rank = Protocol.rankFromName(props.getProperty("rank", ""));
    }

    private synchronized void save() {
        Properties props = new Properties();
        props.setProperty("token", token);
        props.setProperty("username", username);
        props.setProperty("rank", switch (rank) {
            case Protocol.RANK_OWNER -> "owner";
            case Protocol.RANK_MODERATOR -> "moderator";
            default -> "normal";
        });
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "CoffeeBrew Interactive sign-in. Keep private: this signs you in.");
            }
        } catch (IOException e) {
            System.err.println("[Account] Could not save " + file + ": " + e.getMessage());
        }
    }
}

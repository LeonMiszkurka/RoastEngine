package net.coffeebrewia.roastengine.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.coffeebrewia.roastengine.net.Protocol;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The server's side of the CoffeeBrew Interactive account service (see
 * {@code Server/aws/accounts_lambda.py}): turning a player's one-time join ticket into who they
 * are, and banning or unbanning. Every call proves it comes from a real server with the
 * {@code serverKey} from server.properties.
 *
 * <p>Calls block for up to a few seconds, so they run on a connection's own thread, never the tick.
 */
final class AccountService {

    /** Who a join ticket belongs to. */
    record Account(String accountId, String username, int rank, boolean banned, String banReason) {
    }

    /** A refusal with a message fit to show the player. */
    static final class Refused extends Exception {
        Refused(String message) {
            super(message);
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    private final String url;
    private final String serverKey;

    AccountService(String url, String serverKey) {
        this.url = url;
        this.serverKey = serverKey;
    }

    Account redeem(String ticket) throws Refused {
        JsonObject body = new JsonObject();
        body.addProperty("ticket", ticket);
        JsonObject account = call("redeem", body).getAsJsonObject("account");
        return new Account(account.get("accountId").getAsString(), account.get("username").getAsString(),
                Protocol.rankFromName(account.get("rank").getAsString()),
                account.get("banned").getAsBoolean(), account.get("banReason").getAsString());
    }

    void ban(String by, String target, String reason) throws Refused {
        JsonObject body = new JsonObject();
        body.addProperty("by", by);
        body.addProperty("target", target);
        body.addProperty("reason", reason);
        call("ban", body);
    }

    void unban(String by, String target) throws Refused {
        JsonObject body = new JsonObject();
        body.addProperty("by", by);
        body.addProperty("target", target);
        call("unban", body);
    }

    private JsonObject call(String action, JsonObject body) throws Refused {
        body.addProperty("action", action);
        body.addProperty("serverKey", serverKey);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject reply = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!reply.has("ok") || !reply.get("ok").getAsBoolean()) {
                throw new Refused(reply.has("error") ? reply.get("error").getAsString()
                        : "The account service refused (" + response.statusCode() + ").");
            }
            return reply;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Refused("Interrupted.");
        } catch (IOException | RuntimeException e) {
            RoastServer.log("Account service unreachable: " + e);
            throw new Refused("The account service can't be reached right now. Try again soon.");
        }
    }
}

package net.coffeebrewia.roastengine.multiplayer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.coffeebrewia.roastengine.net.Protocol.StatusReply;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The servers on the Multiplayer screen, from {@code /multiplayer-defaults.properties}:
 *
 * <pre>
 *   server.1.name=server_1
 *   server.1.wakeUrl=https://....lambda-url.us-west-2.on.aws/   (a server that sleeps)
 *   server.2.name=server_2
 *   server.2.address=host:port                                  (a server that is always on)
 * </pre>
 *
 * Any of these can be overridden with a system property of the same name prefixed by
 * {@code roastengine.}, e.g. {@code -Droastengine.server.1.wakeUrl=http://127.0.0.1:8765/}.
 *
 * <p>A sleeping server is reached through its wake-up link (see {@code Server/aws/wake_lambda.py}):
 * asking with {@code ?action=status} only reports, asking without it starts the machine. Its
 * address changes every time it starts, and the link always reports the current one - so nobody
 * types an address.
 */
public final class ServerList {

    private static final int MAX_SERVERS = 20;
    /** How long to keep waiting for a sleeping server before giving up. */
    private static final Duration GIVE_UP_AFTER = Duration.ofMinutes(3);
    private static final long POLL_MILLIS = 3_000;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    private ServerList() {
    }

    /** What the list shows for a server. */
    public enum State {
        CHECKING, ASLEEP, STARTING, ONLINE, OFFLINE, NOT_SET_UP, ERROR
    }

    /**
     * @param players  -1 when not known
     * @param world    what is being played, empty when nobody has picked yet
     * @param message  detail for ERROR / OFFLINE
     */
    public record Status(State state, int players, int maxPlayers, String world, String message) {
        static Status of(State state) {
            return new Status(state, -1, 0, "", "");
        }

        static Status error(String message) {
            return new Status(State.ERROR, -1, 0, "", message);
        }
    }

    /** One server in the list. */
    public record Entry(String name, String wakeUrl, String address) {

        public boolean isSetUp() {
            return !wakeUrl.isEmpty() || !address.isEmpty();
        }

        /** Looks the server up without starting it. Blocks for a few seconds at most. */
        public Status check() {
            if (!isSetUp()) {
                return Status.of(State.NOT_SET_UP);
            }
            try {
                String current = address;
                if (!wakeUrl.isEmpty()) {
                    JsonObject answer = ask(true);
                    switch (text(answer, "status")) {
                        case "asleep" -> {
                            return Status.of(State.ASLEEP);
                        }
                        case "starting", "stopping" -> {
                            return Status.of(State.STARTING);
                        }
                        case "ready" -> current = addressOf(answer);
                        case "error" -> {
                            return Status.error(text(answer, "message"));
                        }
                        default -> {
                            return Status.error("Unexpected answer from the wake-up link.");
                        }
                    }
                }
                StatusReply reply = NetClient.queryStatus(current);
                return new Status(State.ONLINE, reply.players(), reply.maxPlayers(), reply.world(), "");
            } catch (NetClient.NotReachableException e) {
                // Awake but not answering: a sleeping server that is still booting, or one that is down.
                return wakeUrl.isEmpty() ? new Status(State.OFFLINE, -1, 0, "", e.getMessage())
                        : Status.of(State.STARTING);
            } catch (IOException e) {
                return Status.error(e.getMessage());
            }
        }

        /**
         * Joins, waking the server first when it is asleep. Blocks - possibly for a minute or two -
         * so call it off the main thread.
         *
         * @param progress  receives short status lines to show the player
         * @param cancelled polled between attempts; true stops waiting
         * @throws IOException with a message fit to show the player
         */
        public NetClient join(String playerName, Consumer<String> progress, BooleanSupplier cancelled)
                throws IOException {
            if (wakeUrl.isEmpty()) {
                return NetClient.connect(address, playerName);
            }
            long deadline = System.nanoTime() + GIVE_UP_AFTER.toNanos();
            while (!cancelled.getAsBoolean()) {
                if (System.nanoTime() > deadline) {
                    throw new IOException(name + " did not wake up in time. Try again in a minute.");
                }
                JsonObject answer = ask(false);
                switch (text(answer, "status")) {
                    case "ready" -> {
                        try {
                            return NetClient.connect(addressOf(answer), playerName);
                        } catch (NetClient.NotReachableException e) {
                            progress.accept("Almost there - " + name + " is starting up...");
                        }
                    }
                    case "starting" -> progress.accept("Waking " + name + " up... (about a minute)");
                    case "stopping" -> progress.accept(name + " was just going to sleep - waking it again...");
                    case "error" -> throw new IOException(text(answer, "message"));
                    default -> throw new IOException("Unexpected answer from " + name + "'s wake-up link.");
                }
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            throw new IOException("Cancelled.");
        }

        private JsonObject ask(boolean statusOnly) throws IOException {
            String url = statusOnly ? wakeUrl + (wakeUrl.contains("?") ? "&" : "?") + "action=status" : wakeUrl;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response;
            try {
                response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Cancelled.");
            } catch (IOException e) {
                throw new IOException("Could not reach " + name + "'s wake-up link. Check your internet connection.", e);
            }
            try {
                return JsonParser.parseString(response.body()).getAsJsonObject();
            } catch (RuntimeException e) {
                throw new IOException(name + "'s wake-up link answered " + response.statusCode()
                        + " with something unexpected.", e);
            }
        }

        private static String addressOf(JsonObject answer) {
            return text(answer, "address") + ":" + answer.get("port").getAsInt();
        }

        private static String text(JsonObject object, String key) {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
        }
    }

    /** The configured servers, in order. */
    public static List<Entry> load() {
        Properties props = new Properties();
        try (InputStream in = ServerList.class.getResourceAsStream("/multiplayer-defaults.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            System.err.println("[Multiplayer] Could not read multiplayer-defaults.properties: " + e.getMessage());
        }
        List<Entry> servers = new ArrayList<>();
        for (int i = 1; i <= MAX_SERVERS; i++) {
            String prefix = "server." + i + ".";
            String name = setting(props, prefix + "name");
            if (!name.isEmpty()) {
                servers.add(new Entry(name, setting(props, prefix + "wakeUrl"), setting(props, prefix + "address")));
            }
        }
        return servers;
    }

    private static String setting(Properties props, String key) {
        return System.getProperty("roastengine." + key, props.getProperty(key, "")).trim();
    }
}

package net.coffeebrewia.roastengine.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import net.coffeebrewia.roastengine.net.Protocol;
import net.coffeebrewia.roastengine.net.Protocol.Chat;
import net.coffeebrewia.roastengine.net.Protocol.ChatSend;
import net.coffeebrewia.roastengine.net.Protocol.Hello;
import net.coffeebrewia.roastengine.net.Protocol.Message;
import net.coffeebrewia.roastengine.net.Protocol.Rejected;
import net.coffeebrewia.roastengine.net.Protocol.Welcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server with accounts switched on, against a fake account service that knows three tickets.
 * (The real service's own rules are tested in Server/aws/test_accounts_lambda.py.)
 */
class AccountsIntegrationTest {

    private static final String KEY = "server-key-for-tests";

    private HttpServer fakeService;
    private RoastServer server;
    /** username -> [rank, banned] as the fake service sees them. */
    private final Map<String, String[]> people = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws IOException {
        people.put("leon", new String[]{"owner", "false"});
        people.put("ann", new String[]{"normal", "false"});
        people.put("troll", new String[]{"normal", "true"});
        fakeService = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeService.createContext("/", exchange -> {
            JsonObject in = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject out = new JsonObject();
            if (!KEY.equals(in.get("serverKey").getAsString())) {
                out.addProperty("ok", false);
                out.addProperty("error", "Unknown server.");
            } else if (in.get("action").getAsString().equals("redeem")) {
                String who = in.get("ticket").getAsString().replace("ticket-", "");
                String[] person = people.get(who);
                out.addProperty("ok", person != null);
                if (person == null) {
                    out.addProperty("error", "That join ticket has expired.");
                } else {
                    JsonObject account = new JsonObject();
                    account.addProperty("accountId", "id-" + who);
                    account.addProperty("username", who.substring(0, 1).toUpperCase() + who.substring(1));
                    account.addProperty("rank", person[0]);
                    account.addProperty("banned", Boolean.parseBoolean(person[1]));
                    account.addProperty("banReason", "trolling");
                    out.add("account", account);
                }
            } else if (in.get("action").getAsString().equals("ban")) {
                people.get(in.get("target").getAsString().toLowerCase())[1] = "true";
                out.addProperty("ok", true);
            }
            byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        fakeService.start();

        ServerConfig config = new ServerConfig();
        config.port = 0;
        config.accountsUrl = "http://127.0.0.1:" + fakeService.getAddress().getPort() + "/";
        config.serverKey = KEY;
        server = new RoastServer(config);
        server.bind();
        Thread accept = new Thread(() -> {
            try {
                server.acceptLoop();
            } catch (IOException ignored) {
                // Stopped.
            }
        });
        accept.setDaemon(true);
        accept.start();
    }

    @AfterEach
    void stop() {
        server.shutdown();
        fakeService.stop(0);
    }

    private final class Client implements AutoCloseable {
        final Socket socket = new Socket("localhost", server.port());
        final DataInputStream in;

        Client(String ticket) throws IOException {
            socket.setSoTimeout(5000);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            socket.getOutputStream().write(Protocol.encode(new Hello(Protocol.VERSION, ticket, "ignored")));
        }

        <T extends Message> T await(Class<T> type) throws IOException {
            for (int i = 0; i < 500; i++) {
                Message message = Protocol.read(in);
                if (type.isInstance(message)) {
                    return type.cast(message);
                }
            }
            throw new AssertionError("Never received " + type.getSimpleName());
        }

        void say(String text) throws IOException {
            socket.getOutputStream().write(Protocol.encode(new ChatSend(text)));
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Test
    void theAccountDecidesNameAndRank() throws IOException {
        try (Client leon = new Client("ticket-leon")) {
            Welcome welcome = leon.await(Welcome.class);
            assertEquals("Leon", welcome.name()); // not the "ignored" name in the hello
            assertEquals(Protocol.RANK_OWNER, welcome.rank());
        }
    }

    @Test
    void noTicketBadTicketAndBannedAreTurnedAway() throws IOException {
        try (Client none = new Client("")) {
            assertTrue(none.await(Rejected.class).reason().contains("Sign in"));
        }
        try (Client bad = new Client("ticket-nobody")) {
            assertTrue(bad.await(Rejected.class).reason().contains("expired"));
        }
        try (Client troll = new Client("ticket-troll")) {
            assertTrue(troll.await(Rejected.class).reason().contains("banned from multiplayer: trolling"));
        }
    }

    @Test
    void anOwnerBansAndTheBanSticks() throws IOException {
        try (Client leon = new Client("ticket-leon"); Client ann = new Client("ticket-ann")) {
            leon.await(Welcome.class);
            ann.await(Welcome.class);
            leon.say("/ban Ann spamming");
            assertTrue(ann.await(Rejected.class).reason().contains("banned by Leon: spamming"));
        }
        try (Client again = new Client("ticket-ann")) {
            assertInstanceOf(Rejected.class, again.await(Rejected.class));
        }
    }

    @Test
    void joiningTwiceReplacesTheOlderConnection() throws IOException {
        try (Client first = new Client("ticket-ann")) {
            first.await(Welcome.class);
            try (Client second = new Client("ticket-ann")) {
                second.await(Welcome.class);
                assertTrue(first.await(Rejected.class).reason().contains("somewhere else"));
                second.say("/list");
                Chat list = second.await(Chat.class);
                while (!list.text().contains("online")) {
                    list = second.await(Chat.class);
                }
                assertTrue(list.text().startsWith("1/"), list.text());
            }
        }
    }
}

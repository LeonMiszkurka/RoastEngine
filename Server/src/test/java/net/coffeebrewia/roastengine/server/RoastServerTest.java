package net.coffeebrewia.roastengine.server;

import net.coffeebrewia.roastengine.net.Protocol;
import net.coffeebrewia.roastengine.net.Protocol.Chat;
import net.coffeebrewia.roastengine.net.Protocol.ChatSend;
import net.coffeebrewia.roastengine.net.Protocol.ChooseSession;
import net.coffeebrewia.roastengine.net.Protocol.ModRef;
import net.coffeebrewia.roastengine.net.Protocol.SessionUpdate;
import net.coffeebrewia.roastengine.net.Protocol.StatusReply;
import net.coffeebrewia.roastengine.net.Protocol.StatusRequest;
import net.coffeebrewia.roastengine.net.Protocol.Hello;
import net.coffeebrewia.roastengine.net.Protocol.Message;
import net.coffeebrewia.roastengine.net.Protocol.Move;
import net.coffeebrewia.roastengine.net.Protocol.PlayerJoined;
import net.coffeebrewia.roastengine.net.Protocol.PlayerLeft;
import net.coffeebrewia.roastengine.net.Protocol.Pose;
import net.coffeebrewia.roastengine.net.Protocol.Rejected;
import net.coffeebrewia.roastengine.net.Protocol.Snapshot;
import net.coffeebrewia.roastengine.net.Protocol.Welcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs a real server on a free port and talks to it over real sockets. */
class RoastServerTest {

    private RoastServer server;

    @BeforeEach
    void start() throws IOException {
        ServerConfig config = new ServerConfig();
        config.port = 0;
        config.maxPlayers = 3;
        config.localRanks = "Boss=owner, Ad=admin, Mo=moderator";
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
    }

    /** A bare-bones client straight on the protocol. */
    private final class TestClient implements AutoCloseable {
        final Socket socket;
        final DataInputStream in;

        TestClient() throws IOException {
            socket = new Socket("localhost", server.port());
            socket.setSoTimeout(3000);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        }

        void send(Message message) throws IOException {
            socket.getOutputStream().write(Protocol.encode(message));
        }

        Message read() throws IOException {
            return Protocol.read(in);
        }

        /** Reads until a message matches, skipping snapshots and anything else on the way. */
        @SuppressWarnings("unchecked")
        <T extends Message> T await(Class<T> type, Predicate<T> test) throws IOException {
            for (int i = 0; i < 500; i++) {
                Message message = read();
                if (type.isInstance(message) && test.test((T) message)) {
                    return (T) message;
                }
            }
            throw new AssertionError("Never received the expected " + type.getSimpleName());
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private TestClient join(String name) throws IOException {
        TestClient client = new TestClient();
        client.send(new Hello(Protocol.VERSION, "", name));
        return client;
    }

    @Test
    void twoPlayersSeeEachOtherMoveAndChat() throws IOException {
        try (TestClient ana = join("Ana"); TestClient ben = join("Ben")) {
            Welcome anaWelcome = assertInstanceOf(Welcome.class, ana.read());
            Welcome benWelcome = ben.await(Welcome.class, w -> true);

            // Ben hears about Ana, who was there first, and Ana about Ben.
            ben.await(PlayerJoined.class, j -> j.id() == anaWelcome.yourId() && j.name().equals("Ana"));
            ana.await(PlayerJoined.class, j -> j.id() == benWelcome.yourId());

            ana.send(new Move(4f, 1.7f, -2f, 1.2f, 0f, true));
            Snapshot snapshot = ben.await(Snapshot.class,
                    s -> s.poses().stream().anyMatch(p -> p.id() == anaWelcome.yourId()));
            Pose pose = snapshot.poses().stream().filter(p -> p.id() == anaWelcome.yourId()).findFirst().orElseThrow();
            assertEquals(4f, pose.x());
            assertTrue(pose.moving());

            ben.send(new ChatSend("  hi ana  "));
            Chat chat = ana.await(Chat.class, c -> c.from().equals("Ben"));
            assertEquals("hi ana", chat.text());

            ben.close();
            ana.await(PlayerLeft.class, left -> left.id() == benWelcome.yourId());
        }
    }

    @Test
    void duplicateNamesGetANumber() throws IOException {
        try (TestClient first = join("Leon"); TestClient second = join("leon")) {
            assertEquals("Leon", first.await(Welcome.class, w -> true).name());
            assertEquals("leon2", second.await(Welcome.class, w -> true).name());
        }
    }

    @Test
    void refusesWrongVersionFullServerAndBadNames() throws IOException {
        try (TestClient old = new TestClient()) {
            old.send(new Hello(Protocol.VERSION - 1, "", "Old"));
            assertTrue(assertInstanceOf(Rejected.class, old.read()).reason().contains("out of date"));
        }
        try (TestClient blank = join("!!")) {
            assertTrue(assertInstanceOf(Rejected.class, blank.read()).reason().contains("name"));
        }
        try (TestClient a = join("One"); TestClient b = join("Two"); TestClient c = join("Three")) {
            a.await(Welcome.class, w -> true);
            b.await(Welcome.class, w -> true);
            c.await(Welcome.class, w -> true);
            try (TestClient third = join("Four")) {
                assertTrue(third.await(Rejected.class, r -> true).reason().contains("full"));
            }
        }
    }

    @Test
    void ignoresGarbagePositions() throws IOException {
        try (TestClient ana = join("Ana"); TestClient ben = join("Ben")) {
            int anaId = ana.await(Welcome.class, w -> true).yourId();
            ben.await(Welcome.class, w -> true);
            ana.send(new Move(Float.NaN, 0, 0, 0, 0, false));
            ana.send(new Move(1f, 2f, 3f, 0, 0, false));
            Snapshot snapshot = ben.await(Snapshot.class,
                    s -> s.poses().stream().anyMatch(p -> p.id() == anaId));
            Pose pose = snapshot.poses().stream().filter(p -> p.id() == anaId).findFirst().orElseThrow();
            assertEquals(1f, pose.x());
        }
    }

    @Test
    void goesIdleOnlyAfterEveryoneHasLeftForTheSetTime() throws Exception {
        server.shutdown();
        ServerConfig config = new ServerConfig();
        config.port = 0;
        config.idleShutdownMinutes = 0.5 / 60; // half a second
        java.util.concurrent.CountDownLatch idle = new java.util.concurrent.CountDownLatch(1);
        server = new RoastServer(config, idle::countDown);
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

        try (TestClient ana = join("Ana")) {
            ana.await(Welcome.class, w -> true);
            // Someone is on: no shutdown, however long they stay.
            assertTrue(!idle.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS));
        }
        // Everyone gone: shuts down after the idle time.
        assertTrue(idle.await(3, java.util.concurrent.TimeUnit.SECONDS));
    }

    private static final ModRef CLUB = new ModRef(6386524, "the-club-map", "The Club");

    @Test
    void firstPlayerPicksTheWorldAndLaterPlayersGetIt() throws IOException {
        try (TestClient ana = join("Ana")) {
            ana.await(Welcome.class, w -> true);
            assertEquals(SessionUpdate.CHOOSE, ana.await(SessionUpdate.class, u -> true).state());

            try (TestClient ben = join("Ben")) {
                SessionUpdate waiting = ben.await(SessionUpdate.class, u -> true);
                assertEquals(SessionUpdate.WAITING, waiting.state());
                assertEquals("Ana", waiting.chooser());

                // Ben cannot pick; Ana can.
                ben.send(new ChooseSession(new ModRef(0, "sneaky", "Sneaky"), List.of()));
                ana.send(new ChooseSession(CLUB, List.of(new ModRef(0, "extra", "Extra"))));
                SessionUpdate ready = ben.await(SessionUpdate.class, u -> u.state() == SessionUpdate.READY);
                assertEquals(CLUB, ready.world());
                assertEquals(List.of(new ModRef(0, "extra", "Extra")), ready.mods());
            }
            try (TestClient cara = join("Cara")) {
                SessionUpdate joined = cara.await(SessionUpdate.class, u -> true);
                assertEquals(SessionUpdate.READY, joined.state());
                assertEquals(CLUB, joined.world());
            }
        }
    }

    @Test
    void pickingPassesOnWhenThePickerLeavesAndResetsWhenEmpty() throws IOException {
        TestClient ana = join("Ana");
        ana.await(SessionUpdate.class, u -> u.state() == SessionUpdate.CHOOSE);
        try (TestClient ben = join("Ben")) {
            ben.await(SessionUpdate.class, u -> u.state() == SessionUpdate.WAITING);
            ana.close();
            ben.await(SessionUpdate.class, u -> u.state() == SessionUpdate.CHOOSE);
            ben.send(new ChooseSession(CLUB, List.of()));
            ben.await(SessionUpdate.class, u -> u.state() == SessionUpdate.READY);
        }
        // Everyone gone: the next player picks again.
        try (TestClient dan = join("Dan")) {
            assertEquals(SessionUpdate.CHOOSE, dan.await(SessionUpdate.class, u -> true).state());
        }
    }

    @Test
    void answersStatusWithoutJoining() throws IOException {
        try (TestClient ana = join("Ana")) {
            ana.await(SessionUpdate.class, u -> true);
            ana.send(new ChooseSession(CLUB, List.of()));
            ana.await(SessionUpdate.class, u -> u.state() == SessionUpdate.READY);
            try (TestClient list = new TestClient()) {
                list.send(new StatusRequest());
                StatusReply reply = assertInstanceOf(StatusReply.class, list.read());
                assertEquals(1, reply.players());
                assertEquals("The Club", reply.world());
            }
        }
    }

    @Test
    void privateMessagesReachOnlyTheirTargetAndCanBeAnswered() throws IOException {
        try (TestClient ana = join("Ana"); TestClient ben = join("Ben"); TestClient cara = join("Cara")) {
            for (TestClient c : List.of(ana, ben, cara)) {
                c.await(SessionUpdate.class, u -> true);
            }
            ana.send(new ChatSend("/msg ben meet at the door"));
            Chat got = ben.await(Chat.class, c -> c.kind() == Chat.WHISPER_FROM);
            assertEquals("Ana", got.from());
            assertEquals("meet at the door", got.text());
            assertEquals("Ben", ana.await(Chat.class, c -> c.kind() == Chat.WHISPER_TO).from());

            ben.send(new ChatSend("/r ok"));
            assertEquals("ok", ana.await(Chat.class, c -> c.kind() == Chat.WHISPER_FROM).text());

            // Cara saw none of it: the next thing she gets is the public line.
            ana.send(new ChatSend("hello all"));
            Chat next = cara.await(Chat.class, c -> c.kind() != Chat.SYSTEM);
            assertEquals(Chat.PUBLIC, next.kind());
            assertEquals("hello all", next.text());
        }
    }

    @Test
    void onlyHigherRanksCanKick() throws IOException {
        try (TestClient boss = join("Boss"); TestClient mo = join("Mo"); TestClient ann = join("Ann")) {
            assertEquals(Protocol.RANK_OWNER, boss.await(Welcome.class, w -> true).rank());
            mo.await(Welcome.class, w -> true);
            ann.await(Welcome.class, w -> true);

            ann.send(new ChatSend("/kick Mo"));
            ann.await(Chat.class, c -> c.text().contains("Only moderators"));
            mo.send(new ChatSend("/kick Boss"));
            mo.await(Chat.class, c -> c.text().contains("can't do that"));

            // Unbanning is for admins and owners, not moderators.
            mo.send(new ChatSend("/unban Someone"));
            mo.await(Chat.class, c -> c.text().contains("Only admins and owners can unban"));

            mo.send(new ChatSend("/kick Ann being rude"));
            Rejected kicked = ann.await(Rejected.class, r -> true);
            assertTrue(kicked.reason().contains("kicked by Mo: being rude"));
            boss.await(Chat.class, c -> c.text().contains("Ann was kicked by Mo"));
        }
    }

    @Test
    void floodedChatIsThrottled() throws IOException {
        try (TestClient ana = join("Ana")) {
            ana.await(Welcome.class, w -> true);
            for (int i = 0; i < 8; i++) {
                ana.send(new ChatSend("spam " + i));
            }
            ana.await(Chat.class, c -> c.kind() == Chat.SYSTEM && c.text().contains("Slow down"));
        }
    }
}

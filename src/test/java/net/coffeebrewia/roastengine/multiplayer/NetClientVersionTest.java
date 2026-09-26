package net.coffeebrewia.roastengine.multiplayer;

import net.coffeebrewia.roastengine.net.Protocol;
import net.coffeebrewia.roastengine.net.Protocol.Hello;
import net.coffeebrewia.roastengine.net.Protocol.Rejected;
import net.coffeebrewia.roastengine.net.Protocol.Welcome;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Joining a server that is not the same version as the game.
 *
 * <p>The awkward case is a server already running somewhere that has never heard of getting along
 * with other versions - it turns away anything but its own number. The game has to work that out
 * from the refusal, which is what these check, against a stand-in that behaves exactly as an old
 * server does.
 */
class NetClientVersionTest {

    /** A server that only admits one version, and says so the way an old build does. */
    private static final class OldServer implements AutoCloseable {
        private final ServerSocket socket = new ServerSocket(0);
        private final List<Integer> versionsTried = new CopyOnWriteArrayList<>();
        private final int speaks;
        private final String refusal;
        private final Thread thread;

        OldServer(int speaks, String refusal) throws IOException {
            this.speaks = speaks;
            this.refusal = refusal;
            this.thread = new Thread(this::run, "old-server");
            thread.setDaemon(true);
            thread.start();
        }

        private void run() {
            while (!socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    DataInputStream in = new DataInputStream(client.getInputStream());
                    OutputStream out = client.getOutputStream();
                    if (!(Protocol.read(in) instanceof Hello hello)) {
                        continue;
                    }
                    versionsTried.add(hello.version());
                    if (hello.version() != speaks) {
                        out.write(Protocol.encode(new Rejected(refusal)));
                    } else {
                        out.write(Protocol.encode(new Welcome(7, hello.name(), Protocol.RANK_NORMAL,
                                "Old Server", "welcome", 8)));
                    }
                    out.flush();
                } catch (IOException e) {
                    return;     // closed, or the client hung up
                }
            }
        }

        String address() {
            return "127.0.0.1:" + socket.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Test
    void stepsDownToAServerThatOnlySpeaksAnOlderVersion() throws IOException {
        try (OldServer server = new OldServer(Protocol.MIN_VERSION,
                "This server is out of date. It runs an older version of RoastEngine.")) {
            NetClient client = NetClient.connect(server.address(), "", "Leon");
            try {
                assertEquals(Protocol.MIN_VERSION, client.protocolVersion(),
                        "settled on the only version this server will take");
                assertEquals("Old Server", client.welcome().serverName());
                assertFalse(Protocol.supportsArena(client.protocolVersion()),
                        "and knows not to send it anything about a match");
            } finally {
                client.close();
            }
            // Our own version first, then down: no version below the one that worked is offered.
            assertEquals(List.of(Protocol.VERSION, Protocol.MIN_VERSION), server.versionsTried);
        }
    }

    @Test
    void joinsAtOnceWhenTheServerIsTheSameVersion() throws IOException {
        try (OldServer server = new OldServer(Protocol.VERSION, "nope")) {
            NetClient client = NetClient.connect(server.address(), "", "Leon");
            try {
                assertEquals(Protocol.VERSION, client.protocolVersion());
                assertTrue(Protocol.supportsArena(client.protocolVersion()));
            } finally {
                client.close();
            }
            assertEquals(List.of(Protocol.VERSION), server.versionsTried, "asked once, got in");
        }
    }

    @Test
    void aRefusalThatIsNotAboutTheVersionIsShownAsItIsAndNotRetried() throws IOException {
        try (OldServer server = new OldServer(-1, "You're banned from multiplayer: cheating")) {
            IOException thrown = assertThrows(IOException.class,
                    () -> NetClient.connect(server.address(), "", "Leon"));
            assertEquals("You're banned from multiplayer: cheating", thrown.getMessage());
            assertEquals(List.of(Protocol.VERSION), server.versionsTried,
                    "a ban is final: no point offering an older version");
        }
    }

    @Test
    void reportsTheFirstRefusalWhenNoVersionIsAccepted() throws IOException {
        try (OldServer server = new OldServer(-1, "Your game is out of date. Please update.")) {
            IOException thrown = assertThrows(IOException.class,
                    () -> NetClient.connect(server.address(), "", "Leon"));
            assertEquals("Your game is out of date. Please update.", thrown.getMessage());
            assertEquals(Protocol.VERSION - Protocol.MIN_VERSION + 1, server.versionsTried.size(),
                    "every version we speak was offered, and then it gave up");
        }
    }
}

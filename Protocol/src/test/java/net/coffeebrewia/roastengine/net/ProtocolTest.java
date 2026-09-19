package net.coffeebrewia.roastengine.net;

import net.coffeebrewia.roastengine.net.Protocol.Chat;
import net.coffeebrewia.roastengine.net.Protocol.ChooseSession;
import net.coffeebrewia.roastengine.net.Protocol.ModRef;
import net.coffeebrewia.roastengine.net.Protocol.SessionUpdate;
import net.coffeebrewia.roastengine.net.Protocol.StatusReply;
import net.coffeebrewia.roastengine.net.Protocol.StatusRequest;
import net.coffeebrewia.roastengine.net.Protocol.ChatSend;
import net.coffeebrewia.roastengine.net.Protocol.Hello;
import net.coffeebrewia.roastengine.net.Protocol.Message;
import net.coffeebrewia.roastengine.net.Protocol.Move;
import net.coffeebrewia.roastengine.net.Protocol.Ping;
import net.coffeebrewia.roastengine.net.Protocol.PlayerJoined;
import net.coffeebrewia.roastengine.net.Protocol.PlayerLeft;
import net.coffeebrewia.roastengine.net.Protocol.Pong;
import net.coffeebrewia.roastengine.net.Protocol.Pose;
import net.coffeebrewia.roastengine.net.Protocol.Rejected;
import net.coffeebrewia.roastengine.net.Protocol.Snapshot;
import net.coffeebrewia.roastengine.net.Protocol.Welcome;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolTest {

    private static Message roundTrip(Message message) throws IOException {
        byte[] frame = Protocol.encode(message);
        return Protocol.read(new DataInputStream(new ByteArrayInputStream(frame)));
    }

    @Test
    void everyMessageSurvivesARoundTrip() throws IOException {
        List<Message> messages = List.of(
                new Hello(Protocol.VERSION, "Leon"),
                new Welcome(7, "Leon2", "Server", "Hi there", 16),
                new SessionUpdate(SessionUpdate.WAITING, "Ana", null, List.of()),
                new SessionUpdate(SessionUpdate.READY, "",
                        new ModRef(6386524, "creator-map", "Creator Map"),
                        List.of(new ModRef(0, "my-mod", "My Mod"))),
                new ChooseSession(new ModRef(1, "a", "A"), List.of()),
                new StatusRequest(),
                new StatusReply("server_1", "Hi", 3, 16, "The Club"),
                new Rejected("Full"),
                new PlayerJoined(3, "Ana"),
                new PlayerLeft(3),
                new Move(1.5f, 1.7f, -20f, 3.1f, -0.4f, true),
                new Snapshot(List.of(new Pose(1, 0, 1.7f, 2, 0.5f, 0, false),
                        new Pose(2, -4, 1.7f, 9, 1f, 0.2f, true))),
                new Snapshot(List.of()),
                new ChatSend("hello world"),
                new Chat("", "Ana joined the game"),
                new Ping(123456789L),
                new Pong(-1L));
        for (Message message : messages) {
            assertEquals(message, roundTrip(message));
        }
    }

    @Test
    void framesReadBackToBackFromOneStream() throws IOException {
        byte[] a = Protocol.encode(new Chat("a", "one"));
        byte[] b = Protocol.encode(new PlayerLeft(9));
        byte[] both = new byte[a.length + b.length];
        System.arraycopy(a, 0, both, 0, a.length);
        System.arraycopy(b, 0, both, a.length, b.length);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(both));
        assertEquals(new Chat("a", "one"), Protocol.read(in));
        assertEquals(new PlayerLeft(9), Protocol.read(in));
    }

    @Test
    void rejectsBadFrames() {
        // Unknown type.
        assertThrows(IOException.class, () -> Protocol.read(stream(0, 1, 99)));
        // Zero length.
        assertThrows(IOException.class, () -> Protocol.read(stream(0, 0)));
        // Longer than allowed, refused before anything is allocated.
        assertThrows(IOException.class, () -> Protocol.read(stream(0xFF, 0xFF)));
        // A move frame cut short inside its own length.
        assertThrows(IOException.class, () -> Protocol.read(stream(0, 3, 6, 0, 0)));
    }

    @Test
    void cleansNamesAndChat() {
        assertEquals("Leon", Protocol.cleanName("  Leon  "));
        assertEquals("abcdefghijklmnop", Protocol.cleanName("abcdefghijklmnopqrstuvwxyz"));
        assertEquals("ab", Protocol.cleanName("a<>b"));
        assertEquals("", Protocol.cleanName("ééé"));
        assertEquals("hi", Protocol.cleanChat((char) 0 + "hi\n"));
        assertEquals(Protocol.MAX_CHAT, Protocol.cleanChat("x".repeat(1000)).length());
    }

    private static DataInputStream stream(int... bytes) {
        byte[] data = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            data[i] = (byte) bytes[i];
        }
        return new DataInputStream(new ByteArrayInputStream(data));
    }
}

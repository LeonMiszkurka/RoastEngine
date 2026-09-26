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
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolTest {

    private static Message roundTrip(Message message) throws IOException {
        byte[] frame = Protocol.encode(message);
        return Protocol.read(new DataInputStream(new ByteArrayInputStream(frame)));
    }

    @Test
    void everyMessageSurvivesARoundTrip() throws IOException {
        List<Message> messages = List.of(
                new Hello(Protocol.VERSION, "ticket-abc", "Leon"),
                new Welcome(7, "Leon2", Protocol.RANK_OWNER, "Server", "Hi there", 16),
                new SessionUpdate(SessionUpdate.WAITING, "Ana", null, List.of()),
                new SessionUpdate(SessionUpdate.READY, "",
                        new ModRef(6386524, "creator-map", "Creator Map"),
                        List.of(new ModRef(0, "my-mod", "My Mod"))),
                new ChooseSession(new ModRef(1, "a", "A"), List.of()),
                new StatusRequest(),
                new StatusReply("server_1", "Hi", 3, 16, "The Club"),
                new Rejected("Full"),
                new PlayerJoined(3, "Ana", Protocol.RANK_MODERATOR),
                new PlayerLeft(3),
                new Move(1.5f, 1.7f, -20f, 3.1f, -0.4f, true),
                new Snapshot(List.of(new Pose(1, 0, 1.7f, 2, 0.5f, 0, false),
                        new Pose(2, -4, 1.7f, 9, 1f, 0.2f, true))),
                new Snapshot(List.of()),
                new ChatSend("hello world"),
                Chat.system("Ana joined the game"),
                new Chat(Chat.WHISPER_FROM, "Ana", Protocol.RANK_MODERATOR, "psst"),
                new Ping(123456789L),
                new Pong(-1L));
        for (Message message : messages) {
            assertEquals(message, roundTrip(message));
        }
    }

    @Test
    void framesReadBackToBackFromOneStream() throws IOException {
        byte[] a = Protocol.encode(new Chat(Chat.PUBLIC, "a", 0, "one"));
        byte[] b = Protocol.encode(new PlayerLeft(9));
        byte[] both = new byte[a.length + b.length];
        System.arraycopy(a, 0, both, 0, a.length);
        System.arraycopy(b, 0, both, a.length, b.length);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(both));
        assertEquals(new Chat(Chat.PUBLIC, "a", 0, "one"), Protocol.read(in));
        assertEquals(new PlayerLeft(9), Protocol.read(in));
    }

    @Test
    void rejectsBadFrames() {
        // Zero length.
        assertThrows(IOException.class, () -> Protocol.read(stream(0, 0)));
        // Longer than allowed, refused before anything is allocated.
        assertThrows(IOException.class, () -> Protocol.read(stream(0xFF, 0xFF)));
        // A move frame cut short inside its own length.
        assertThrows(IOException.class, () -> Protocol.read(stream(0, 3, 6, 0, 0)));
    }

    @Test
    void readsPastAMessageFromANewerBuildInsteadOfBreakingTheStream() throws IOException {
        // A frame carries its length, so a type this build has never heard of can be dropped.
        // This is what lets an older game and a newer server stay on the same connection.
        assertEquals(new Protocol.Unknown(99), Protocol.read(stream(0, 1, 99)));

        // And the message after it still reads, which is the part that matters.
        java.io.ByteArrayOutputStream both = new java.io.ByteArrayOutputStream();
        both.write(new byte[]{0, 3, 99, 7, 7});                 // something from the future
        both.write(Protocol.encode(new Protocol.Ping(1234L)));  // something we know
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(both.toByteArray()));
        assertEquals(new Protocol.Unknown(99), Protocol.read(in));
        assertEquals(new Protocol.Ping(1234L), Protocol.read(in));
    }

    @Test
    void anUnknownMessageIsNeverSent() {
        assertThrows(IllegalArgumentException.class,
                () -> Protocol.encode(new Protocol.Unknown(99)));
    }

    @Test
    void versionsGetAlongFromTheFloorUp() {
        assertTrue(Protocol.canTalkTo(Protocol.VERSION));
        assertTrue(Protocol.canTalkTo(Protocol.MIN_VERSION));
        assertTrue(Protocol.canTalkTo(Protocol.VERSION + 1), "a newer game is welcome too");
        assertFalse(Protocol.canTalkTo(Protocol.MIN_VERSION - 1), "older than the floor is not");
        assertTrue(Protocol.supportsArena(Protocol.VERSION));
        assertFalse(Protocol.supportsArena(Protocol.ARENA_VERSION - 1),
                "a server from before arenas cannot referee one");
    }

    @Test
    void aHelloFromAnUnknownShapeStillGivesUpItsVersion() throws IOException {
        // The version is written first for exactly this reason: whatever follows it, the number
        // that decides whether the conversation can go on is always readable.
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(body);
        out.writeByte(1);          // HELLO
        out.writeInt(99);          // a version from far in the future
        out.writeByte(0xAB);       // and then something that is not two strings
        byte[] frame = body.toByteArray();
        java.io.ByteArrayOutputStream whole = new java.io.ByteArrayOutputStream();
        whole.write(0);
        whole.write(frame.length);
        whole.write(frame);

        Protocol.Message read = Protocol.read(
                new DataInputStream(new java.io.ByteArrayInputStream(whole.toByteArray())));
        assertEquals(99, ((Protocol.Hello) read).version());
    }

    @Test
    void ranksHaveTagsAndNamesBothWays() {
        assertEquals("[Owner]", Protocol.rankTag(Protocol.RANK_OWNER));
        assertEquals("[Admin]", Protocol.rankTag(Protocol.RANK_ADMIN));
        assertEquals("[Mod]", Protocol.rankTag(Protocol.RANK_MODERATOR));
        assertEquals("", Protocol.rankTag(Protocol.RANK_NORMAL));
        assertEquals(Protocol.RANK_ADMIN, Protocol.rankFromName("admin"));
        assertEquals(Protocol.RANK_OWNER, Protocol.rankFromName("owner"));
        // Anything unknown is an ordinary player - never a higher rank.
        assertEquals(Protocol.RANK_NORMAL, Protocol.rankFromName("emperor"));
        assertEquals(Protocol.RANK_NORMAL, Protocol.rankFromName(null));
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

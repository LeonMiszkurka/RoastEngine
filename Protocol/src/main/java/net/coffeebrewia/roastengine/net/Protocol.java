package net.coffeebrewia.roastengine.net;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The multiplayer wire format, shared by the game and the dedicated server.
 *
 * <p>Everything travels over one TCP connection as length-prefixed frames:
 *
 * <pre>
 *   u16 length   bytes that follow (type + payload)
 *   u8  type     one of the constants below
 *   ...          payload, written with DataOutputStream
 * </pre>
 *
 * <p>A session goes: the client sends {@link Hello}; the server answers {@link Welcome} (or
 * {@link Rejected} and hangs up), a {@link PlayerJoined} for everyone already there, and a
 * {@link SessionUpdate} saying which world is being played. If nobody has picked one yet, the
 * first player is asked to ({@link SessionUpdate#CHOOSE}) and answers with {@link ChooseSession};
 * everyone else waits. From then on each client streams its {@link Move} about 20 times a second
 * and the server broadcasts one {@link Snapshot} of every player at the same rate.
 *
 * <p>A server list can also ask a server how it is doing without joining: a connection that opens
 * with {@link StatusRequest} gets one {@link StatusReply} and is closed.
 *
 * <p>Bump {@link #VERSION} whenever a message changes shape; the server turns away clients whose
 * version differs, with a message telling the player to update.
 */
public final class Protocol {

    public static final int VERSION = 2;
    public static final int DEFAULT_PORT = 25570;
    /** Snapshots per second from the server, and moves per second from each client. */
    public static final int TICK_RATE = 20;
    /** Frames larger than this are refused, so a bad client cannot make the other side allocate. */
    public static final int MAX_FRAME = 16 * 1024;
    public static final int MAX_NAME = 16;
    public static final int MAX_CHAT = 200;
    /** Most content mods a session can use alongside its world. */
    public static final int MAX_SESSION_MODS = 32;

    private static final int HELLO = 1;
    private static final int WELCOME = 2;
    private static final int REJECTED = 3;
    private static final int PLAYER_JOINED = 4;
    private static final int PLAYER_LEFT = 5;
    private static final int MOVE = 6;
    private static final int SNAPSHOT = 7;
    private static final int CHAT_SEND = 8;
    private static final int CHAT = 9;
    private static final int PING = 10;
    private static final int PONG = 11;
    private static final int SESSION_UPDATE = 12;
    private static final int CHOOSE_SESSION = 13;
    private static final int STATUS_REQUEST = 14;
    private static final int STATUS_REPLY = 15;

    private Protocol() {
    }

    /** Anything that can be sent in either direction. */
    public sealed interface Message permits Hello, Welcome, Rejected, PlayerJoined, PlayerLeft,
            Move, Snapshot, ChatSend, Chat, Ping, Pong, SessionUpdate, ChooseSession,
            StatusRequest, StatusReply {
    }

    /**
     * A mod as another computer can find it: by mod.io id when it came from mod.io (0 otherwise),
     * else by its folder name ({@code key}) or display name.
     */
    public record ModRef(long modIoId, String key, String name) {
    }

    // --- Client to server ---------------------------------------------------------------

    /** First message on a new connection. */
    public record Hello(int version, String name) implements Message {
    }

    /**
     * Where the sender is. {@code x, y, z} is the eye position, the same point the camera sits
     * at; yaw and pitch are in radians.
     */
    public record Move(float x, float y, float z, float yaw, float pitch, boolean moving) implements Message {
    }

    public record ChatSend(String text) implements Message {
    }

    /** Sent by either side; the other answers with a {@link Pong} carrying the same value. */
    public record Ping(long stamp) implements Message {
    }

    public record Pong(long stamp) implements Message {
    }

    /** The world and content mods the player picked, in answer to {@link SessionUpdate#CHOOSE}. */
    public record ChooseSession(ModRef world, List<ModRef> mods) implements Message {
    }

    /** Opens a connection that only wants a {@link StatusReply}, for the server list. */
    public record StatusRequest() implements Message {
    }

    // --- Server to client ---------------------------------------------------------------

    /**
     * Accepted. {@code name} is the name actually given, which differs from the one asked for
     * when someone on the server already has it.
     */
    public record Welcome(int yourId, String name, String serverName, String motd,
                          int maxPlayers) implements Message {
    }

    /**
     * What is being played. Sent after {@link Welcome}, and again whenever it changes.
     *
     * @param state   {@link #WAITING}, {@link #CHOOSE} or {@link #READY}
     * @param chooser who is picking, while WAITING
     * @param world   the world, once READY; null before
     * @param mods    content mods played with it, once READY
     */
    public record SessionUpdate(int state, String chooser, ModRef world, List<ModRef> mods) implements Message {
        /** Someone else is picking the world. */
        public static final int WAITING = 0;
        /** Nobody has picked yet: this player should. */
        public static final int CHOOSE = 1;
        /** Picked: load {@code world} and {@code mods} and play. */
        public static final int READY = 2;
    }

    /** How a server is doing, for the server list. {@code world} is empty until one is picked. */
    public record StatusReply(String serverName, String motd, int players, int maxPlayers,
                              String world) implements Message {
    }

    public record Rejected(String reason) implements Message {
    }

    public record PlayerJoined(int id, String name) implements Message {
    }

    public record PlayerLeft(int id) implements Message {
    }

    /** One player's position within a {@link Snapshot}. */
    public record Pose(int id, float x, float y, float z, float yaw, float pitch, boolean moving) {
    }

    public record Snapshot(List<Pose> poses) implements Message {
    }

    /** A line of chat. {@code from} is empty for messages from the server itself. */
    public record Chat(String from, String text) implements Message {
    }

    // --- Encoding -----------------------------------------------------------------------

    /** Encodes one message as a complete frame, ready to write to the socket. */
    public static byte[] encode(Message message) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeShort(0); // length, patched below
            writeBody(out, message);
        } catch (IOException e) {
            throw new IllegalStateException("Writing to memory cannot fail", e);
        }
        byte[] frame = bytes.toByteArray();
        int length = frame.length - 2;
        if (length > MAX_FRAME) {
            throw new IllegalArgumentException("Message too large: " + length + " bytes");
        }
        frame[0] = (byte) (length >>> 8);
        frame[1] = (byte) length;
        return frame;
    }

    private static void writeBody(DataOutputStream out, Message message) throws IOException {
        if (message instanceof Hello m) {
            out.writeByte(HELLO);
            out.writeInt(m.version());
            out.writeUTF(m.name());
        } else if (message instanceof Welcome m) {
            out.writeByte(WELCOME);
            out.writeInt(m.yourId());
            out.writeUTF(m.name());
            out.writeUTF(m.serverName());
            out.writeUTF(m.motd());
            out.writeShort(m.maxPlayers());
        } else if (message instanceof Rejected m) {
            out.writeByte(REJECTED);
            out.writeUTF(m.reason());
        } else if (message instanceof PlayerJoined m) {
            out.writeByte(PLAYER_JOINED);
            out.writeInt(m.id());
            out.writeUTF(m.name());
        } else if (message instanceof PlayerLeft m) {
            out.writeByte(PLAYER_LEFT);
            out.writeInt(m.id());
        } else if (message instanceof Move m) {
            out.writeByte(MOVE);
            out.writeFloat(m.x());
            out.writeFloat(m.y());
            out.writeFloat(m.z());
            out.writeFloat(m.yaw());
            out.writeFloat(m.pitch());
            out.writeBoolean(m.moving());
        } else if (message instanceof Snapshot m) {
            out.writeByte(SNAPSHOT);
            out.writeShort(m.poses().size());
            for (Pose pose : m.poses()) {
                out.writeInt(pose.id());
                out.writeFloat(pose.x());
                out.writeFloat(pose.y());
                out.writeFloat(pose.z());
                out.writeFloat(pose.yaw());
                out.writeFloat(pose.pitch());
                out.writeBoolean(pose.moving());
            }
        } else if (message instanceof ChatSend m) {
            out.writeByte(CHAT_SEND);
            out.writeUTF(m.text());
        } else if (message instanceof Chat m) {
            out.writeByte(CHAT);
            out.writeUTF(m.from());
            out.writeUTF(m.text());
        } else if (message instanceof Ping m) {
            out.writeByte(PING);
            out.writeLong(m.stamp());
        } else if (message instanceof Pong m) {
            out.writeByte(PONG);
            out.writeLong(m.stamp());
        } else if (message instanceof SessionUpdate m) {
            out.writeByte(SESSION_UPDATE);
            out.writeByte(m.state());
            out.writeUTF(m.chooser());
            writeModRef(out, m.world());
            writeModRefs(out, m.mods());
        } else if (message instanceof ChooseSession m) {
            out.writeByte(CHOOSE_SESSION);
            writeModRef(out, m.world());
            writeModRefs(out, m.mods());
        } else if (message instanceof StatusRequest) {
            out.writeByte(STATUS_REQUEST);
        } else if (message instanceof StatusReply m) {
            out.writeByte(STATUS_REPLY);
            out.writeUTF(m.serverName());
            out.writeUTF(m.motd());
            out.writeShort(m.players());
            out.writeShort(m.maxPlayers());
            out.writeUTF(m.world());
        } else {
            throw new IllegalArgumentException("Unknown message " + message);
        }
    }

    /**
     * Reads one frame. Blocks until a whole frame has arrived.
     *
     * @throws EOFException when the other side closed the connection
     * @throws IOException  on a malformed or oversized frame
     */
    public static Message read(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        if (length < 1 || length > MAX_FRAME) {
            throw new IOException("Bad frame length " + length);
        }
        byte[] body = new byte[length];
        in.readFully(body);
        DataInputStream frame = new DataInputStream(new java.io.ByteArrayInputStream(body));
        try {
            return readBody(frame);
        } catch (EOFException e) {
            // Inside a complete frame, running short means the frame itself is malformed.
            throw new IOException("Truncated message", e);
        }
    }

    private static Message readBody(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        return switch (type) {
            case HELLO -> new Hello(in.readInt(), in.readUTF());
            case WELCOME -> new Welcome(in.readInt(), in.readUTF(), in.readUTF(), in.readUTF(),
                    in.readUnsignedShort());
            case REJECTED -> new Rejected(in.readUTF());
            case PLAYER_JOINED -> new PlayerJoined(in.readInt(), in.readUTF());
            case PLAYER_LEFT -> new PlayerLeft(in.readInt());
            case MOVE -> new Move(in.readFloat(), in.readFloat(), in.readFloat(),
                    in.readFloat(), in.readFloat(), in.readBoolean());
            case SNAPSHOT -> {
                int count = in.readUnsignedShort();
                List<Pose> poses = new ArrayList<>(Math.min(count, 256));
                for (int i = 0; i < count; i++) {
                    poses.add(new Pose(in.readInt(), in.readFloat(), in.readFloat(), in.readFloat(),
                            in.readFloat(), in.readFloat(), in.readBoolean()));
                }
                yield new Snapshot(poses);
            }
            case CHAT_SEND -> new ChatSend(in.readUTF());
            case CHAT -> new Chat(in.readUTF(), in.readUTF());
            case PING -> new Ping(in.readLong());
            case PONG -> new Pong(in.readLong());
            case SESSION_UPDATE -> new SessionUpdate(in.readUnsignedByte(), in.readUTF(),
                    readModRef(in), readModRefs(in));
            case CHOOSE_SESSION -> new ChooseSession(readModRef(in), readModRefs(in));
            case STATUS_REQUEST -> new StatusRequest();
            case STATUS_REPLY -> new StatusReply(in.readUTF(), in.readUTF(), in.readUnsignedShort(),
                    in.readUnsignedShort(), in.readUTF());
            default -> throw new IOException("Unknown message type " + type);
        };
    }

    /** A null reference is written as a single false. */
    private static void writeModRef(DataOutputStream out, ModRef ref) throws IOException {
        out.writeBoolean(ref != null);
        if (ref != null) {
            out.writeLong(ref.modIoId());
            out.writeUTF(ref.key());
            out.writeUTF(ref.name());
        }
    }

    private static ModRef readModRef(DataInputStream in) throws IOException {
        return in.readBoolean() ? new ModRef(in.readLong(), in.readUTF(), in.readUTF()) : null;
    }

    private static void writeModRefs(DataOutputStream out, List<ModRef> refs) throws IOException {
        if (refs.size() > MAX_SESSION_MODS) {
            throw new IllegalArgumentException("At most " + MAX_SESSION_MODS + " mods");
        }
        out.writeByte(refs.size());
        for (ModRef ref : refs) {
            writeModRef(out, ref);
        }
    }

    private static List<ModRef> readModRefs(DataInputStream in) throws IOException {
        int count = in.readUnsignedByte();
        if (count > MAX_SESSION_MODS) {
            throw new IOException("Too many mods: " + count);
        }
        List<ModRef> refs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ModRef ref = readModRef(in);
            if (ref == null) {
                throw new IOException("Empty mod reference");
            }
            refs.add(ref);
        }
        return List.copyOf(refs);
    }

    // --- Validation shared by both sides ------------------------------------------------

    /**
     * Cleans a player name: printable ASCII letters, digits, space, '_' and '-', trimmed and cut
     * to {@link #MAX_NAME}. Returns an empty string when nothing usable is left.
     */
    public static String cleanName(String name) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length() && out.length() < MAX_NAME; i++) {
            char ch = name.charAt(i);
            if (Character.isLetterOrDigit(ch) && ch < 128 || ch == ' ' || ch == '_' || ch == '-') {
                out.append(ch);
            }
        }
        return out.toString().trim().replaceAll(" {2,}", " ");
    }

    /** Cleans a chat line: printable ASCII only, trimmed and cut to {@link #MAX_CHAT}. */
    public static String cleanChat(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length() && out.length() < MAX_CHAT; i++) {
            char ch = text.charAt(i);
            if (ch >= 32 && ch < 127) {
                out.append(ch);
            }
        }
        return out.toString().trim();
    }
}

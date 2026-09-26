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
 * <p><b>Arenas.</b> A world that is a game mode - teams, guns, a score - says so by sending
 * {@link ArenaSetup} once it has loaded. From then on the server runs the match ({@link ArenaRules})
 * and it is the referee: clients ask ({@link ArenaAction}, {@link Shoot}), the server decides and
 * tells everyone ({@link ArenaState}, {@link ShotFired}, {@link ArenaEvent}).
 *
 * <p><b>Versions.</b> {@link #VERSION} goes up whenever anything is added or changed here, but a
 * mismatch is not the end of a conversation: any version from {@link #MIN_VERSION} upwards may join
 * any other, and each side simply keeps to what both understand. Two rules make that safe, and both
 * must be kept to when adding to this file:
 *
 * <ul>
 *   <li><b>Add, never reshape.</b> A new message gets a new type id; an existing one keeps its
 *       fields in their order for ever. Change a message's shape and the old side reads rubbish,
 *       which no version check on a deployed server can save you from.</li>
 *   <li><b>Ignore what you do not know.</b> Every frame carries its length, so a message from a
 *       newer build can be read past and dropped - it arrives as {@link Unknown} - instead of
 *       breaking the stream.</li>
 * </ul>
 *
 * <p>So a feature added in a later version must ask before using it: {@link #supportsArena} is how
 * the arena checks that the other side can referee a match before it starts sending about one.
 */
public final class Protocol {

    public static final int VERSION = 5;
    /**
     * The oldest version still spoken here. A server welcomes anything from this up, a client will
     * step down to it, and only something older than this is turned away - by then the messages
     * really have changed shape.
     */
    public static final int MIN_VERSION = 4;
    /** Arenas arrived in 5: an older server cannot referee a match. */
    public static final int ARENA_VERSION = 5;
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
    private static final int ARENA_SETUP = 16;
    private static final int ARENA_ACTION = 17;
    private static final int SHOOT = 18;
    private static final int ARENA_STATE = 19;
    private static final int SHOT_FIRED = 20;
    private static final int ARENA_EVENT = 21;
    /** Most maps one arena can offer to vote on. */
    public static final int MAX_ARENA_MAPS = 8;

    private Protocol() {
    }

    /** Anything that can be sent in either direction. */
    public sealed interface Message permits Hello, Welcome, Rejected, PlayerJoined, PlayerLeft,
            Move, Snapshot, ChatSend, Chat, Ping, Pong, SessionUpdate, ChooseSession,
            StatusRequest, StatusReply, ArenaSetup, ArenaAction, Shoot, ArenaState, ShotFired,
            ArenaEvent, Unknown {
    }

    /**
     * A message from a build that knows something this one does not.
     *
     * <p>Frames carry their length, so one of these is read past and handed over as this instead of
     * breaking the stream. Both sides drop it: whatever it was about is a feature this build has no
     * part in. It is never sent - {@link #encode} refuses it.
     */
    public record Unknown(int type) implements Message {
    }

    /** True when a build of this version can referee or play an arena match. */
    public static boolean supportsArena(int version) {
        return version >= ARENA_VERSION;
    }

    /** True when the two ends can talk at all: anything at or above the floor. */
    public static boolean canTalkTo(int version) {
        return version >= MIN_VERSION;
    }

    /** Ranks, lowest first. A higher rank can moderate every rank below it. */
    public static final int RANK_NORMAL = 0;
    public static final int RANK_MODERATOR = 1;
    public static final int RANK_ADMIN = 2;
    public static final int RANK_OWNER = 3;

    /** The tag shown before a name: nothing for normal players. */
    public static String rankTag(int rank) {
        return switch (rank) {
            case RANK_OWNER -> "[Owner]";
            case RANK_ADMIN -> "[Admin]";
            case RANK_MODERATOR -> "[Mod]";
            default -> "";
        };
    }

    /** "owner" / "moderator" / anything else, as the account service spells them. */
    public static int rankFromName(String name) {
        return switch (name == null ? "" : name) {
            case "owner" -> RANK_OWNER;
            case "admin" -> RANK_ADMIN;
            case "moderator" -> RANK_MODERATOR;
            default -> RANK_NORMAL;
        };
    }

    /**
     * A mod as another computer can find it: by mod.io id when it came from mod.io (0 otherwise),
     * else by its folder name ({@code key}) or display name.
     */
    public record ModRef(long modIoId, String key, String name) {
    }

    // --- Client to server ---------------------------------------------------------------

    /**
     * First message on a new connection. {@code ticket} is a one-time join ticket from the
     * CoffeeBrew account service; the server redeems it to learn who this is. {@code name} is only
     * used by a server running without accounts, for testing.
     */
    public record Hello(int version, String ticket, String name) implements Message {
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

    /**
     * "This world is an arena, and these are its rules." Sent by every player once the world has
     * loaded; the first one of a session sets the match up and the rest are ignored.
     *
     * @param maps       the names of the maps to vote between
     * @param minPlayers how many must be in and ready before a match starts
     */
    public record ArenaSetup(List<String> maps, int scoreLimit, int maxHealth, float respawnSeconds,
                             int matchSeconds, int minPlayers) implements Message {
    }

    /** Something a player chooses in an arena's lobby. */
    public record ArenaAction(int action, int value) implements Message {
        /** value: {@link ArenaRules#RED} or {@link ArenaRules#BLUE}. */
        public static final int JOIN_TEAM = 0;
        /** value: which map, counting from 0. */
        public static final int VOTE = 1;
        /** value: 1 for ready, 0 for not. */
        public static final int READY = 2;
        /** value: the gun now in their hand, as an index into the arena's gun list; -1 for none. */
        public static final int GUN = 3;
    }

    /**
     * A shot. The shooter's game works out what it hit - it is the one that can see the level -
     * and the server checks that the shot was possible before believing it.
     *
     * @param target   the player hit, or 0 for a miss
     * @param damage   how much, all pellets together
     * @param distance how far the shot went before it hit something, for drawing it
     */
    public record Shoot(int gun, int target, int damage, float ox, float oy, float oz,
                        float dx, float dy, float dz, float distance) implements Message {
    }

    // --- Server to client ---------------------------------------------------------------

    /**
     * Accepted. {@code name} is the name actually given, which differs from the one asked for
     * when someone on the server already has it.
     */
    public record Welcome(int yourId, String name, int rank, String serverName, String motd,
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

    /** One player as the arena sees them. */
    public record ArenaPlayer(int id, int team, boolean ready, int vote, int health, int kills,
                              int deaths, boolean alive, int gun, float respawnIn) {
    }

    /**
     * The whole match, sent whenever it changes and once a second besides so timers stay in step.
     *
     * @param phase    {@link ArenaRules#LOBBY}, {@link ArenaRules#COUNTDOWN},
     *                 {@link ArenaRules#PLAYING} or {@link ArenaRules#RESULTS}
     * @param timeLeft seconds left of the countdown, the match or the results
     * @param map      the map being played, once chosen; -1 before
     * @param winner   once a match ends: {@link ArenaRules#RED}, {@link ArenaRules#BLUE}, or
     *                 {@link ArenaRules#NO_TEAM} for a draw
     * @param votes    how many votes each map has
     */
    public record ArenaState(int phase, float timeLeft, int map, int redScore, int blueScore,
                             int winner, List<Integer> votes, List<ArenaPlayer> players) implements Message {
    }

    /** Someone else fired: for the tracer and the bang. */
    public record ShotFired(int shooter, int gun, int target, float ox, float oy, float oz,
                            float dx, float dy, float dz, float distance) implements Message {
    }

    /** Something that happened in a match, for the kill feed and the screen flashes. */
    public record ArenaEvent(int kind, int a, int b, int value) implements Message {
        /** a killed b with gun {@code value}. */
        public static final int KILL = 0;
        /** a hit b for {@code value} damage. */
        public static final int HIT = 1;
        /** The match began, on map {@code value}. */
        public static final int MATCH_START = 2;
        /** The match ended; {@code value} is the winning team, or NO_TEAM for a draw. */
        public static final int MATCH_END = 3;
        /** Player a is back in, with full health. */
        public static final int RESPAWN = 4;
    }

    public record PlayerJoined(int id, String name, int rank) implements Message {
    }

    public record PlayerLeft(int id) implements Message {
    }

    /** One player's position within a {@link Snapshot}. */
    public record Pose(int id, float x, float y, float z, float yaw, float pitch, boolean moving) {
    }

    public record Snapshot(List<Pose> poses) implements Message {
    }

    /**
     * A line of chat.
     *
     * @param kind {@link #PUBLIC}, {@link #SYSTEM} (from the server; {@code from} is empty),
     *             {@link #WHISPER_FROM} (a private message {@code from} someone) or
     *             {@link #WHISPER_TO} (your own private message, echoed back; {@code from} is who
     *             it went to)
     * @param rank the rank of {@code from}, for its tag
     */
    public record Chat(int kind, String from, int rank, String text) implements Message {
        public static final int PUBLIC = 0;
        public static final int SYSTEM = 1;
        public static final int WHISPER_FROM = 2;
        public static final int WHISPER_TO = 3;

        /** A message from the server itself. */
        public static Chat system(String text) {
            return new Chat(SYSTEM, "", RANK_NORMAL, text);
        }
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
            out.writeUTF(m.ticket());
            out.writeUTF(m.name());
        } else if (message instanceof Welcome m) {
            out.writeByte(WELCOME);
            out.writeInt(m.yourId());
            out.writeUTF(m.name());
            out.writeByte(m.rank());
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
            out.writeByte(m.rank());
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
            out.writeByte(m.kind());
            out.writeUTF(m.from());
            out.writeByte(m.rank());
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
        } else if (message instanceof ArenaSetup m) {
            out.writeByte(ARENA_SETUP);
            writeStrings(out, m.maps(), MAX_ARENA_MAPS);
            out.writeShort(m.scoreLimit());
            out.writeShort(m.maxHealth());
            out.writeFloat(m.respawnSeconds());
            out.writeInt(m.matchSeconds());
            out.writeByte(m.minPlayers());
        } else if (message instanceof ArenaAction m) {
            out.writeByte(ARENA_ACTION);
            out.writeByte(m.action());
            out.writeInt(m.value());
        } else if (message instanceof Shoot m) {
            out.writeByte(SHOOT);
            out.writeShort(m.gun());
            out.writeInt(m.target());
            out.writeShort(m.damage());
            writeRay(out, m.ox(), m.oy(), m.oz(), m.dx(), m.dy(), m.dz(), m.distance());
        } else if (message instanceof ArenaState m) {
            out.writeByte(ARENA_STATE);
            out.writeByte(m.phase());
            out.writeFloat(m.timeLeft());
            out.writeByte(m.map());
            out.writeShort(m.redScore());
            out.writeShort(m.blueScore());
            out.writeByte(m.winner());
            out.writeByte(m.votes().size());
            for (int votes : m.votes()) {
                out.writeShort(votes);
            }
            out.writeShort(m.players().size());
            for (ArenaPlayer player : m.players()) {
                out.writeInt(player.id());
                out.writeByte(player.team());
                out.writeBoolean(player.ready());
                out.writeByte(player.vote());
                out.writeShort(player.health());
                out.writeShort(player.kills());
                out.writeShort(player.deaths());
                out.writeBoolean(player.alive());
                out.writeShort(player.gun());
                out.writeFloat(player.respawnIn());
            }
        } else if (message instanceof ShotFired m) {
            out.writeByte(SHOT_FIRED);
            out.writeInt(m.shooter());
            out.writeShort(m.gun());
            out.writeInt(m.target());
            writeRay(out, m.ox(), m.oy(), m.oz(), m.dx(), m.dy(), m.dz(), m.distance());
        } else if (message instanceof ArenaEvent m) {
            out.writeByte(ARENA_EVENT);
            out.writeByte(m.kind());
            out.writeInt(m.a());
            out.writeInt(m.b());
            out.writeInt(m.value());
        } else {
            throw new IllegalArgumentException("Unknown message " + message);
        }
    }

    private static void writeRay(DataOutputStream out, float ox, float oy, float oz,
                                 float dx, float dy, float dz, float distance) throws IOException {
        out.writeFloat(ox);
        out.writeFloat(oy);
        out.writeFloat(oz);
        out.writeFloat(dx);
        out.writeFloat(dy);
        out.writeFloat(dz);
        out.writeFloat(distance);
    }

    private static void writeStrings(DataOutputStream out, List<String> values, int max) throws IOException {
        if (values.size() > max) {
            throw new IllegalArgumentException("At most " + max + " entries");
        }
        out.writeByte(values.size());
        for (String value : values) {
            out.writeUTF(value);
        }
    }

    private static List<String> readStrings(DataInputStream in, int max) throws IOException {
        int count = in.readUnsignedByte();
        if (count > max) {
            throw new IOException("Too many entries: " + count);
        }
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(in.readUTF());
        }
        return List.copyOf(values);
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
            case HELLO -> readHello(in);
            case WELCOME -> new Welcome(in.readInt(), in.readUTF(), in.readUnsignedByte(), in.readUTF(),
                    in.readUTF(), in.readUnsignedShort());
            case REJECTED -> new Rejected(in.readUTF());
            case PLAYER_JOINED -> new PlayerJoined(in.readInt(), in.readUTF(), in.readUnsignedByte());
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
            case CHAT -> new Chat(in.readUnsignedByte(), in.readUTF(), in.readUnsignedByte(), in.readUTF());
            case PING -> new Ping(in.readLong());
            case PONG -> new Pong(in.readLong());
            case SESSION_UPDATE -> new SessionUpdate(in.readUnsignedByte(), in.readUTF(),
                    readModRef(in), readModRefs(in));
            case CHOOSE_SESSION -> new ChooseSession(readModRef(in), readModRefs(in));
            case STATUS_REQUEST -> new StatusRequest();
            case STATUS_REPLY -> new StatusReply(in.readUTF(), in.readUTF(), in.readUnsignedShort(),
                    in.readUnsignedShort(), in.readUTF());
            case ARENA_SETUP -> new ArenaSetup(readStrings(in, MAX_ARENA_MAPS), in.readUnsignedShort(),
                    in.readUnsignedShort(), in.readFloat(), in.readInt(), in.readUnsignedByte());
            case ARENA_ACTION -> new ArenaAction(in.readUnsignedByte(), in.readInt());
            case SHOOT -> new Shoot(in.readShort(), in.readInt(), in.readUnsignedShort(),
                    in.readFloat(), in.readFloat(), in.readFloat(),
                    in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat());
            case ARENA_STATE -> readArenaState(in);
            case SHOT_FIRED -> new ShotFired(in.readInt(), in.readShort(), in.readInt(),
                    in.readFloat(), in.readFloat(), in.readFloat(),
                    in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat());
            case ARENA_EVENT -> new ArenaEvent(in.readUnsignedByte(), in.readInt(), in.readInt(), in.readInt());
            // Not a type this build knows: the frame has been read in full, so it can simply be
            // dropped. That is what lets an older build talk to a newer one.
            default -> new Unknown(type);
        };
    }

    private static ArenaState readArenaState(DataInputStream in) throws IOException {
        int phase = in.readUnsignedByte();
        float timeLeft = in.readFloat();
        int map = in.readByte();
        int red = in.readUnsignedShort();
        int blue = in.readUnsignedShort();
        int winner = in.readUnsignedByte();
        int mapCount = in.readUnsignedByte();
        if (mapCount > MAX_ARENA_MAPS) {
            throw new IOException("Too many maps: " + mapCount);
        }
        List<Integer> votes = new ArrayList<>(mapCount);
        for (int i = 0; i < mapCount; i++) {
            votes.add(in.readUnsignedShort());
        }
        int count = in.readUnsignedShort();
        List<ArenaPlayer> players = new ArrayList<>(Math.min(count, 256));
        for (int i = 0; i < count; i++) {
            players.add(new ArenaPlayer(in.readInt(), in.readUnsignedByte(), in.readBoolean(),
                    in.readByte(), in.readUnsignedShort(), in.readUnsignedShort(),
                    in.readUnsignedShort(), in.readBoolean(), in.readShort(), in.readFloat()));
        }
        return new ArenaState(phase, timeLeft, map, red, blue, winner, List.copyOf(votes), List.copyOf(players));
    }

    /**
     * The version comes first so a server can turn away an older game with a clear message
     * instead of failing to read the rest of a hello that has changed shape.
     */
    private static Hello readHello(DataInputStream in) throws IOException {
        int version = in.readInt();
        try {
            return new Hello(version, in.readUTF(), in.readUTF());
        } catch (IOException e) {
            // A hello from a version whose shape we do not know. The version is the part that
            // matters - it is what decides whether this connection can go on at all.
            return new Hello(version, "", "");
        }
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

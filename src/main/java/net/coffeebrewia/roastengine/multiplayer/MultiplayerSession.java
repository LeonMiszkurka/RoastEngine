package net.coffeebrewia.roastengine.multiplayer;

import net.coffeebrewia.roastengine.net.Protocol;
import net.coffeebrewia.roastengine.net.Protocol.Chat;
import net.coffeebrewia.roastengine.net.Protocol.ChatSend;
import net.coffeebrewia.roastengine.net.Protocol.Message;
import net.coffeebrewia.roastengine.net.Protocol.Move;
import net.coffeebrewia.roastengine.net.Protocol.Ping;
import net.coffeebrewia.roastengine.net.Protocol.PlayerJoined;
import net.coffeebrewia.roastengine.net.Protocol.PlayerLeft;
import net.coffeebrewia.roastengine.net.Protocol.Pong;
import net.coffeebrewia.roastengine.net.Protocol.Pose;
import net.coffeebrewia.roastengine.net.Protocol.SessionUpdate;
import net.coffeebrewia.roastengine.net.Protocol.Snapshot;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the game needs to be online: the connection, the other players, the chat log,
 * the ping and what is being played. It starts in the lobby, which calls {@link #updateLobby}
 * while the world is picked and downloaded, and then the sandbox calls {@link #update} once a
 * frame and reads the rest for drawing. Main thread only.
 */
public final class MultiplayerSession {

    private static final float SEND_INTERVAL = 1f / Protocol.TICK_RATE;
    private static final float PING_INTERVAL = 2f;
    private static final int CHAT_HISTORY = 50;

    /** One line in the chat log; {@code kind} is one of the Chat.* kinds. */
    public record ChatLine(int kind, String from, int rank, String text, float time) {
    }

    private final NetClient client;
    /** What the player calls this server - its name in the list, such as server_1. */
    private final String displayName;
    private final Map<Integer, RemotePlayer> others = new LinkedHashMap<>();
    private final List<ChatLine> chat = new ArrayList<>();
    private float clock;
    private float sendTimer;
    private float pingTimer;
    private int pingMillis = -1;
    /** What is being played; null until the server says. */
    private SessionUpdate session;

    public MultiplayerSession(NetClient client, String displayName) {
        this.client = client;
        this.displayName = displayName;
        String motd = client.welcome().motd();
        if (!motd.isBlank()) {
            chat.add(new ChatLine(Chat.SYSTEM, "", Protocol.RANK_NORMAL, motd, 0f));
        }
    }

    /**
     * Handles what arrived and, at the tick rate, sends where the player is.
     *
     * @param eye    the camera position
     * @param moving whether the player is walking, for others to animate
     */
    public void update(float dt, Vector3f eye, float yaw, float pitch, boolean moving, float stepsPerMetre) {
        receive(dt);
        for (RemotePlayer other : others.values()) {
            other.advance(clock, dt, stepsPerMetre);
        }

        sendTimer -= dt;
        if (sendTimer <= 0f) {
            // Sent even when standing still: it doubles as the keep-alive.
            sendTimer += SEND_INTERVAL;
            if (sendTimer < 0f) {
                sendTimer = SEND_INTERVAL; // after a long hitch, do not send a burst to catch up
            }
            client.send(new Move(eye.x, eye.y, eye.z, yaw, pitch, moving));
        }
        ping(dt);
    }

    /**
     * Keeps the connection alive and up to date before playing: the server hangs up on anyone
     * silent for 15 seconds, and picking a world or downloading mods can take longer than that.
     */
    public void updateLobby(float dt) {
        receive(dt);
        ping(dt);
    }

    private void receive(float dt) {
        clock += dt;
        for (Message message : client.poll()) {
            handle(message);
        }
    }

    private void ping(float dt) {
        pingTimer -= dt;
        if (pingTimer <= 0f) {
            pingTimer = PING_INTERVAL;
            client.send(new Ping(System.nanoTime()));
        }
    }

    private void handle(Message message) {
        if (message instanceof Snapshot snapshot) {
            int me = client.welcome().yourId();
            for (Pose pose : snapshot.poses()) {
                RemotePlayer other = others.get(pose.id());
                if (other != null && pose.id() != me) {
                    other.receive(pose, clock);
                }
            }
        } else if (message instanceof PlayerJoined joined) {
            if (joined.id() != client.welcome().yourId()) {
                others.put(joined.id(), new RemotePlayer(joined.id(), joined.name(), joined.rank()));
            }
        } else if (message instanceof PlayerLeft left) {
            others.remove(left.id());
        } else if (message instanceof Chat line) {
            chat.add(new ChatLine(line.kind(), line.from(), line.rank(), line.text(), clock));
            while (chat.size() > CHAT_HISTORY) {
                chat.remove(0);
            }
            if (line.kind() == Chat.PUBLIC || line.kind() == Chat.SYSTEM) {
                // Private messages stay out of the log file.
                System.out.println("[Chat] " + (line.from().isEmpty() ? "" : "<" + line.from() + "> ") + line.text());
            }
        } else if (message instanceof SessionUpdate update) {
            session = update;
        } else if (message instanceof Pong pong) {
            pingMillis = (int) ((System.nanoTime() - pong.stamp()) / 1_000_000L);
        } else if (message instanceof Ping ping) {
            client.send(new Pong(ping.stamp()));
        }
    }

    /** What is being played, or null until the server has said. */
    public SessionUpdate session() {
        return session;
    }

    /** Sends the world and mods this player picked, when it is their turn to pick. */
    public void choose(Protocol.ModRef world, List<Protocol.ModRef> mods) {
        client.send(new Protocol.ChooseSession(world, mods));
    }

    public void sendChat(String text) {
        String clean = Protocol.cleanChat(text);
        if (!clean.isEmpty()) {
            client.send(new ChatSend(clean));
        }
    }

    public Collection<RemotePlayer> others() {
        return others.values();
    }

    public List<ChatLine> chat() {
        return chat;
    }

    /** Seconds since joining, the clock chat lines are stamped with. */
    public float clock() {
        return clock;
    }

    /** Round trip in milliseconds, or -1 before the first answer. */
    public int pingMillis() {
        return pingMillis;
    }

    public String myName() {
        return client.welcome().name();
    }

    public int myRank() {
        return client.welcome().rank();
    }

    public String serverName() {
        return displayName.isBlank() ? client.welcome().serverName() : displayName;
    }

    public int maxPlayers() {
        return client.welcome().maxPlayers();
    }

    public boolean isConnected() {
        return client.isConnected();
    }

    public String disconnectReason() {
        String reason = client.disconnectReason();
        return reason == null ? "Disconnected from the server." : reason;
    }

    public void close() {
        client.close();
    }
}

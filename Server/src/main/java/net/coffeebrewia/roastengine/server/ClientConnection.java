package net.coffeebrewia.roastengine.server;

import net.coffeebrewia.roastengine.net.Protocol;
import net.coffeebrewia.roastengine.net.Protocol.ArenaAction;
import net.coffeebrewia.roastengine.net.Protocol.ArenaSetup;
import net.coffeebrewia.roastengine.net.Protocol.ChatSend;
import net.coffeebrewia.roastengine.net.Protocol.ChooseSession;
import net.coffeebrewia.roastengine.net.Protocol.StatusRequest;
import net.coffeebrewia.roastengine.net.Protocol.Hello;
import net.coffeebrewia.roastengine.net.Protocol.Message;
import net.coffeebrewia.roastengine.net.Protocol.Move;
import net.coffeebrewia.roastengine.net.Protocol.Ping;
import net.coffeebrewia.roastengine.net.Protocol.Pong;
import net.coffeebrewia.roastengine.net.Protocol.Rejected;
import net.coffeebrewia.roastengine.net.Protocol.Shoot;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One connected player.
 *
 * <p>Two threads each: a reader that blocks on the socket, and a writer that drains a queue of
 * ready-made frames. The queue means a player on a slow connection never holds up the broadcast
 * to everyone else - if they fall so far behind that it fills, they are dropped instead.
 */
final class ClientConnection {

    /** How long a new connection gets to say hello. */
    private static final int HELLO_TIMEOUT_MS = 5_000;
    /** Clients send a move 20 times a second even when idle, so this much silence means gone. */
    private static final int IDLE_TIMEOUT_MS = 15_000;
    private static final int QUEUE_SIZE = 512;
    /** Chat flood limit: at most this many lines per window. */
    private static final int CHAT_BURST = 5;
    private static final long CHAT_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(5);
    /** Positions outside this are rejected as garbage rather than stored. */
    private static final float WORLD_LIMIT = 1_000_000f;
    /** Queued last, to tell the writer to flush and hang up. Compared by identity. */
    private static final byte[] END = new byte[0];

    final int id;
    private final Socket socket;
    private final RoastServer server;
    private final BlockingQueue<byte[]> outgoing = new ArrayBlockingQueue<>(QUEUE_SIZE);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long[] chatTimes = new long[CHAT_BURST];
    private int chatIndex;

    /** Set once the server accepts the hello; null until then. */
    volatile String name;
    /**
     * Which version of the protocol this player's game speaks, from their hello. What they are sent
     * is held to what they can understand, so an older game plays on rather than being turned away.
     */
    volatile int protocolVersion = Protocol.MIN_VERSION;
    /** Protocol.RANK_*, from the player's account. */
    volatile int rank;
    /** The account's id, or empty when the server runs without accounts. */
    volatile String accountId = "";
    /** Who last sent this player a private message, for /r. */
    volatile String lastWhisperFrom;
    /** Latest position, or null until the first move arrives (not shown in snapshots until then). */
    volatile Move pose;

    ClientConnection(int id, Socket socket, RoastServer server) {
        this.id = id;
        this.socket = socket;
        this.server = server;
    }

    String address() {
        return socket.getRemoteSocketAddress().toString();
    }

    void start() {
        Thread reader = new Thread(this::readLoop, "client-" + id + "-read");
        Thread writer = new Thread(this::writeLoop, "client-" + id + "-write");
        reader.setDaemon(true);
        writer.setDaemon(true);
        reader.start();
        writer.start();
    }

    /** Queues a frame for this player. Never blocks. */
    void send(byte[] frame) {
        if (closed.get()) {
            return;
        }
        if (!outgoing.offer(frame)) {
            close("Connection too slow");
        }
    }

    void send(Message message) {
        send(Protocol.encode(message));
    }

    /** Tells the player why, then hangs up. Safe to call more than once, from any thread. */
    void close(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // The reason goes through the writer like everything else, so it can never land in the
        // middle of a frame that is already being written. The empty frame tells it to stop.
        // Without a reason, whatever is already queued (such as a status reply) still goes out.
        if (reason != null) {
            outgoing.clear();
            outgoing.offer(Protocol.encode(new Rejected(reason)));
        }
        outgoing.offer(END);
        // A writer stuck on a dead connection would never reach END, so hang up regardless.
        server.later(this::closeSocket, 2, TimeUnit.SECONDS);
        server.leave(this, reason);
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing more to do.
        }
    }

    private void readLoop() {
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(HELLO_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            Message first = Protocol.read(in);
            if (first instanceof StatusRequest) {
                // A server list asking how we are: answer and hang up.
                send(server.status());
                close(null);
                return;
            }
            if (!(first instanceof Hello hello)) {
                close("Expected a hello");
                return;
            }
            if (!server.admit(this, hello)) {
                return; // the server already said why
            }
            socket.setSoTimeout(IDLE_TIMEOUT_MS);
            while (!closed.get()) {
                handle(Protocol.read(in));
            }
        } catch (SocketTimeoutException e) {
            close("Timed out");
        } catch (EOFException e) {
            close(null);
        } catch (IOException e) {
            close(closed.get() ? null : "Connection error: " + e.getMessage());
        }
    }

    private void handle(Message message) {
        if (message instanceof Move move) {
            if (valid(move)) {
                pose = move;
            }
        } else if (message instanceof ChatSend chat) {
            String text = Protocol.cleanChat(chat.text());
            if (text.isEmpty()) {
                return;
            }
            if (!allowChat()) {
                send(Protocol.Chat.system("Slow down - you are sending messages too quickly."));
                return;
            }
            server.chat(this, text);
        } else if (message instanceof ChooseSession choice) {
            server.chooseSession(this, choice);
        } else if (message instanceof Shoot shot) {
            server.shoot(this, shot);
        } else if (message instanceof ArenaAction action) {
            server.arenaAction(this, action);
        } else if (message instanceof ArenaSetup setup) {
            server.arenaSetup(this, setup);
        } else if (message instanceof Ping ping) {
            send(new Pong(ping.stamp()));
        }
        // Anything else is a server-to-client message and is ignored if a client sends it.
    }

    private static boolean valid(Move move) {
        float[] values = {move.x(), move.y(), move.z(), move.yaw(), move.pitch()};
        for (float value : values) {
            if (!Float.isFinite(value) || Math.abs(value) > WORLD_LIMIT) {
                return false;
            }
        }
        return true;
    }

    /** True if this line fits within the flood limit; records it if so. */
    private boolean allowChat() {
        long now = System.nanoTime();
        long oldest = chatTimes[chatIndex];
        if (oldest != 0 && now - oldest < CHAT_WINDOW_NANOS) {
            return false;
        }
        chatTimes[chatIndex] = now;
        chatIndex = (chatIndex + 1) % CHAT_BURST;
        return true;
    }

    private void writeLoop() {
        try (OutputStream out = new BufferedOutputStream(socket.getOutputStream())) {
            while (true) {
                byte[] frame = outgoing.take();
                if (frame == END) {
                    out.flush();
                    break;
                }
                out.write(frame);
                if (outgoing.isEmpty()) {
                    out.flush(); // one flush per burst rather than per frame
                }
            }
        } catch (IOException e) {
            close(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeSocket();
        }
    }
}

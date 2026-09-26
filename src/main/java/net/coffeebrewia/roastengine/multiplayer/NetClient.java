package net.coffeebrewia.roastengine.multiplayer;

import net.coffeebrewia.roastengine.net.Protocol;
import net.coffeebrewia.roastengine.net.Protocol.Hello;
import net.coffeebrewia.roastengine.net.Protocol.Message;
import net.coffeebrewia.roastengine.net.Protocol.Rejected;
import net.coffeebrewia.roastengine.net.Protocol.StatusReply;
import net.coffeebrewia.roastengine.net.Protocol.StatusRequest;
import net.coffeebrewia.roastengine.net.Protocol.Welcome;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The game's connection to a server.
 *
 * <p>The socket lives on two background threads - one reading, one writing - so the game loop
 * never waits on the network. Incoming messages pile up in a queue that the main thread empties
 * once a frame with {@link #poll()}.
 */
public final class NetClient {

    private static final int CONNECT_TIMEOUT_MS = 6_000;
    private static final int STATUS_TIMEOUT_MS = 4_000;
    /** The server sends 20 snapshots a second, so this much silence means the link is gone. */
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final byte[] END = new byte[0];

    private final Socket socket;
    private final DataInputStream in;
    private final Welcome welcome;
    private final String address;
    private final ConcurrentLinkedQueue<Message> incoming = new ConcurrentLinkedQueue<>();
    private final LinkedBlockingQueue<byte[]> outgoing = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile String disconnectReason;
    private final int protocolVersion;

    /**
     * Nobody is listening at the address yet - as opposed to a server that answered and said no.
     * A server that is still starting up looks like this, so it is worth trying again.
     */
    public static final class NotReachableException extends IOException {
        NotReachableException(String message) {
            super(message);
        }
    }

    private NetClient(Socket socket, DataInputStream in, Welcome welcome, String address,
                      int protocolVersion) {
        this.socket = socket;
        this.in = in;
        this.welcome = welcome;
        this.address = address;
        this.protocolVersion = protocolVersion;
    }

    /**
     * The version both ends settled on: our own, or the older one the server would take.
     *
     * <p>Anything added after that version has to stay unused on this connection - see
     * {@link Protocol#supportsArena}.
     */
    public int protocolVersion() {
        return protocolVersion;
    }

    /**
     * Connects and says hello. Blocks for up to a few seconds, so call it off the main thread.
     *
     * @param address {@code host} or {@code host:port}
     * @param ticket  a one-time join ticket from the player's account, or empty when signed out
     * @param name    only used by test servers that run without accounts
     * @throws IOException with a message fit to show the player
     */
    public static NetClient connect(String address, String ticket, String name) throws IOException {
        // Say hello as the version we are, and if an older server turns us away for it, offer an
        // older version until one is taken. A server running a build from before the versions were
        // made to get along has no idea it could simply talk to us, so the asking is done here.
        String firstRefusal = null;
        for (int version = Protocol.VERSION; version >= Protocol.MIN_VERSION; version--) {
            try {
                return connectAs(address, ticket, name, version);
            } catch (VersionRefusedException e) {
                if (firstRefusal == null) {
                    firstRefusal = e.getMessage();
                }
                System.out.println("[Net] The server would not take version " + version
                        + (version > Protocol.MIN_VERSION ? "; trying " + (version - 1) : ""));
            }
        }
        throw new IOException(firstRefusal);
    }

    /** A rejection that sounds like it was about the version, and so is worth another try. */
    private static final class VersionRefusedException extends IOException {
        VersionRefusedException(String message) {
            super(message);
        }
    }

    /**
     * True when a server's refusal was about which version we are, rather than about us - a ban or
     * a missing sign-in is final and must be shown to the player as it is.
     */
    private static boolean soundsLikeVersion(String reason) {
        String text = reason == null ? "" : reason.toLowerCase(java.util.Locale.ROOT);
        return text.isBlank() || text.contains("out of date") || text.contains("version")
                || text.contains("update");
    }

    private static NetClient connectAs(String address, String ticket, String name, int version)
            throws IOException {
        InetSocketAddress target = parse(address);
        String host = target.getHostString();
        int port = target.getPort();

        Socket socket = new Socket();
        try {
            socket.connect(target, CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(CONNECT_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            out.write(Protocol.encode(new Hello(version, ticket, name)));
            out.flush();

            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            Message reply = Protocol.read(in);
            if (reply instanceof Rejected rejected) {
                closeQuietly(socket);
                throw soundsLikeVersion(rejected.reason())
                        ? new VersionRefusedException(rejected.reason())
                        : new IOException(rejected.reason());
            }
            if (!(reply instanceof Welcome welcome)) {
                throw new IOException("That does not look like a RoastEngine server.");
            }
            if (version != Protocol.VERSION) {
                System.out.println("[Net] Joined " + host + ":" + port + " as version " + version
                        + " (this build is " + Protocol.VERSION + ")"
                        + (Protocol.supportsArena(version) ? "" : "; arena matches are off here"));
            }
            socket.setSoTimeout(READ_TIMEOUT_MS);
            NetClient client = new NetClient(socket, in, welcome, host + ":" + port, version);
            client.start();
            return client;
        } catch (UnknownHostException e) {
            closeQuietly(socket);
            throw new IOException("Could not find a server called '" + host + "'.");
        } catch (ConnectException e) {
            closeQuietly(socket);
            throw new NotReachableException("Nobody answered at " + host + ":" + port
                    + ". Is the server running, and is the port open?");
        } catch (SocketTimeoutException e) {
            closeQuietly(socket);
            throw new NotReachableException("The server at " + host + ":" + port + " did not answer in time.");
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    /**
     * Asks a server how it is doing without joining it, for the server list.
     *
     * @throws NotReachableException when nothing answers (not running, or still starting)
     */
    public static StatusReply queryStatus(String address) throws IOException {
        InetSocketAddress target = parse(address);
        try (Socket socket = new Socket()) {
            socket.connect(target, STATUS_TIMEOUT_MS);
            socket.setSoTimeout(STATUS_TIMEOUT_MS);
            socket.getOutputStream().write(Protocol.encode(new StatusRequest()));
            Message reply = Protocol.read(new DataInputStream(new BufferedInputStream(socket.getInputStream())));
            if (reply instanceof StatusReply status) {
                return status;
            }
            if (reply instanceof Rejected rejected) {
                throw new IOException(rejected.reason());
            }
            throw new IOException("That does not look like a RoastEngine server.");
        } catch (UnknownHostException e) {
            throw new IOException("Could not find a server called '" + target.getHostString() + "'.");
        } catch (ConnectException | SocketTimeoutException e) {
            throw new NotReachableException("Nobody answered at " + address.trim() + ".");
        } catch (EOFException e) {
            // An older server that does not know status requests hangs up.
            throw new IOException("This server runs an older version of RoastEngine.");
        }
    }

    /** {@code host} or {@code host:port}, with the default port when none is given. */
    private static InetSocketAddress parse(String address) throws IOException {
        String host = address.trim();
        int port = Protocol.DEFAULT_PORT;
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) { // one colon: host:port (not IPv6)
            try {
                port = Integer.parseInt(host.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                throw new IOException("'" + host.substring(colon + 1) + "' is not a port number.");
            }
            host = host.substring(0, colon).trim();
        }
        if (host.isEmpty()) {
            throw new IOException("Type the server's address first.");
        }
        if (port < 1 || port > 65535) {
            throw new IOException(port + " is not a port number.");
        }
        return new InetSocketAddress(host, port); // an unknown host fails later, at connect
    }

    private void start() {
        Thread reader = new Thread(this::readLoop, "net-read");
        Thread writer = new Thread(this::writeLoop, "net-write");
        reader.setDaemon(true);
        writer.setDaemon(true);
        reader.start();
        writer.start();
    }

    public Welcome welcome() {
        return welcome;
    }

    public String address() {
        return address;
    }

    /** Everything that has arrived since the last call, oldest first. Main thread only. */
    public List<Message> poll() {
        List<Message> messages = new ArrayList<>();
        Message message;
        while ((message = incoming.poll()) != null) {
            messages.add(message);
        }
        return messages;
    }

    /** Queues a message. Never blocks. */
    public void send(Message message) {
        if (!closed.get()) {
            outgoing.offer(Protocol.encode(message));
        }
    }

    public boolean isConnected() {
        return !closed.get();
    }

    /** Why the connection ended, once it has; null while connected or after a plain close. */
    public String disconnectReason() {
        return disconnectReason;
    }

    public void close() {
        shutDown(null);
    }

    private void shutDown(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        disconnectReason = reason;
        outgoing.offer(END);
        closeQuietly(socket);
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                Message message = Protocol.read(in);
                if (message instanceof Rejected rejected) {
                    shutDown(rejected.reason()); // kicked, or the server is stopping
                    return;
                }
                incoming.add(message);
            }
        } catch (SocketTimeoutException e) {
            shutDown("Lost connection to the server (timed out).");
        } catch (EOFException e) {
            shutDown("The server closed the connection.");
        } catch (IOException e) {
            shutDown(closed.get() ? null : "Lost connection to the server.");
        }
    }

    private void writeLoop() {
        try (OutputStream out = new BufferedOutputStream(socket.getOutputStream())) {
            while (true) {
                byte[] frame = outgoing.take();
                if (frame == END) {
                    break;
                }
                out.write(frame);
                if (outgoing.isEmpty()) {
                    out.flush();
                }
            }
        } catch (IOException e) {
            shutDown(closed.get() ? null : "Lost connection to the server.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }
}

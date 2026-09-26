package net.coffeebrewia.roastengine.server;

import net.coffeebrewia.roastengine.net.ArenaRules;
import net.coffeebrewia.roastengine.net.Protocol;
import net.coffeebrewia.roastengine.net.Protocol.ArenaAction;
import net.coffeebrewia.roastengine.net.Protocol.ArenaEvent;
import net.coffeebrewia.roastengine.net.Protocol.ArenaSetup;
import net.coffeebrewia.roastengine.net.Protocol.Chat;
import net.coffeebrewia.roastengine.net.Protocol.ChooseSession;
import net.coffeebrewia.roastengine.net.Protocol.ModRef;
import net.coffeebrewia.roastengine.net.Protocol.SessionUpdate;
import net.coffeebrewia.roastengine.net.Protocol.StatusReply;
import net.coffeebrewia.roastengine.net.Protocol.Hello;
import net.coffeebrewia.roastengine.net.Protocol.Move;
import net.coffeebrewia.roastengine.net.Protocol.PlayerJoined;
import net.coffeebrewia.roastengine.net.Protocol.PlayerLeft;
import net.coffeebrewia.roastengine.net.Protocol.Pose;
import net.coffeebrewia.roastengine.net.Protocol.Shoot;
import net.coffeebrewia.roastengine.net.Protocol.ShotFired;
import net.coffeebrewia.roastengine.net.Protocol.Snapshot;
import net.coffeebrewia.roastengine.net.Protocol.Welcome;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The RoastEngine dedicated server.
 *
 * <p>It relays: players send where they are, and 20 times a second the server sends everyone
 * a snapshot of where everybody is, plus chat and join/leave notices. It never loads a world or
 * runs physics, so it needs no graphics and very little CPU - the smallest cloud machine is
 * plenty for 16 players.
 *
 * <pre>
 *   ./gradlew :Server:server           run locally (settings in Server/run/server.properties)
 *   ./gradlew :Server:distZip          Server/build/distributions/roastengine-server.zip
 * </pre>
 *
 * Type {@code help} in the console for the admin commands.
 *
 * <p><b>Sessions.</b> The first player in picks the world (and any content mods); everyone after
 * plays that. Whoever joins while it is being picked waits, and if the picker leaves first the
 * choice passes to the next player. When the last player leaves, the session ends and the next
 * person in picks again. Setting {@code world} in server.properties fixes the world instead.
 *
 * <p><b>Arenas.</b> When the world is a game mode, the first player to load it sends its rules
 * ({@link ArenaSetup}) and the server starts refereeing: it keeps the teams, votes, health and
 * score in an {@link ArenaRules} and tells everyone what happened. It still never loads the
 * world - the rules are all it needs.
 *
 * <p>With {@code idleShutdownMinutes} set, the server exits with {@link #EXIT_IDLE} once nobody
 * has been on for that long. On the cloud machine the service turns that exit into powering
 * the machine off, so it only costs money while people play.
 */
public final class RoastServer {

    /** Exit code meaning "nobody is playing, the machine can be switched off". */
    public static final int EXIT_IDLE = 42;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ServerConfig config;
    /** Everyone who has been welcomed, by id. Connections still saying hello are not in here. */
    private final Map<Integer, ClientConnection> players = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "server-tick");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean running = true;
    private ServerSocket listener;
    /** When the server last had someone on (or started). Only touched on the tick thread. */
    private long lastOccupied = System.nanoTime();
    /** What happens when the idle limit is reached; the tests swap out the real exit. */
    private final Runnable onIdle;
    /** Null when running without accounts (a test server). */
    private final AccountService accounts;
    private final ChatCommands commands = new ChatCommands(this);

    // The session. Guarded by this server's lock.
    private ModRef world;
    private List<ModRef> sessionMods = List.of();
    /** Who is picking the world while none is set; null when nobody is. */
    private ClientConnection chooser;
    /** The match, when the world is an arena; null otherwise. */
    private ArenaRules arena;
    /** Counts down to the next full arena update, sent even when nothing changed. */
    private float arenaResync;

    RoastServer(ServerConfig config) {
        this(config, () -> System.exit(EXIT_IDLE));
    }

    RoastServer(ServerConfig config, Runnable onIdle) {
        this.config = config;
        this.onIdle = onIdle;
        this.accounts = config.accountsUrl.isEmpty() ? null : new AccountService(config.accountsUrl, config.serverKey);
        resetSession();
    }

    /** Back to "nobody has picked", or to the fixed world from server.properties. */
    private synchronized void resetSession() {
        world = config.world.isEmpty() ? null : new ModRef(0, config.world, config.world);
        sessionMods = List.of();
        chooser = null;
        arena = null;
    }

    public static void main(String[] args) throws IOException {
        Path settings = Path.of(args.length > 0 ? args[0] : "server.properties");
        RoastServer server = new RoastServer(ServerConfig.load(settings));
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "shutdown"));
        server.run();
    }

    private void run() throws IOException {
        bind();
        startConsole();
        acceptLoop();
    }

    /** Opens the port and starts ticking. Port 0 picks any free port (used by the tests). */
    void bind() throws IOException {
        listener = new ServerSocket(config.port);
        log("'" + config.name + "' listening on port " + port() + " (max " + config.maxPlayers
                + " players" + (config.world.isEmpty() ? "" : ", world: " + config.world) + ")");
        log(accounts == null ? "No account service (accountsUrl is empty): anyone can join with any name"
                : "Players sign in through " + config.accountsUrl);
        if (config.idleShutdownMinutes > 0) {
            log("Will shut down after " + config.idleShutdownMinutes + " minute(s) with nobody on");
        }
        scheduler.scheduleAtFixedRate(this::tick, 0, 1000 / Protocol.TICK_RATE, TimeUnit.MILLISECONDS);
    }

    int port() {
        return listener.getLocalPort();
    }

    void acceptLoop() throws IOException {
        while (running) {
            Socket socket;
            try {
                socket = listener.accept();
            } catch (SocketException e) {
                if (!running) {
                    break; // closed by shutdown()
                }
                throw e;
            }
            new ClientConnection(nextId.getAndIncrement(), socket, this).start();
        }
    }

    // ---------------------------------------------------------------------
    // Called from connection threads
    // ---------------------------------------------------------------------

    /** Who a player is, once their hello has been checked. */
    private record Identity(String name, int rank, String accountId) {
    }

    /**
     * Checks a new player's hello - version, then account - and joins them. Runs on the player's
     * own connection thread, since asking the account service takes a moment.
     *
     * @return true when welcomed
     */
    boolean admit(ClientConnection client, Hello hello) {
        if (!Protocol.canTalkTo(hello.version())) {
            client.close("Your game is too old to play here (it speaks version " + hello.version()
                    + "; this server needs " + Protocol.MIN_VERSION + " or newer). Please update "
                    + "RoastEngine.");
            return false;
        }
        // Anything from the floor up is welcome, newer builds included: messages are only ever
        // added, and one this server has never heard of is read past and dropped. What each player
        // can take part in is decided by their version, not by turning them away.
        client.protocolVersion = hello.version();
        if (hello.version() != Protocol.VERSION) {
            log((hello.name().isBlank() ? "A player" : hello.name()) + " speaks version "
                    + hello.version() + "; this server speaks " + Protocol.VERSION
                    + (Protocol.supportsArena(hello.version()) ? "" : " - no arena matches for them"));
        }
        Identity identity;
        if (accounts != null) {
            if (hello.ticket().isEmpty()) {
                client.close("Sign in to a CoffeeBrew Interactive account (main menu) to play online.");
                return false;
            }
            try {
                AccountService.Account account = accounts.redeem(hello.ticket());
                if (account.banned()) {
                    client.close("You're banned from multiplayer: " + account.banReason());
                    log(account.username() + " was turned away (banned)");
                    return false;
                }
                identity = new Identity(account.username(), account.rank(), account.accountId());
            } catch (AccountService.Refused e) {
                client.close(e.getMessage());
                return false;
            }
        } else {
            String wanted = Protocol.cleanName(hello.name());
            if (wanted.length() < 2) {
                client.close("Pick a name of at least 2 letters or numbers.");
                return false;
            }
            identity = new Identity(wanted, localRank(wanted), "");
        }
        return join(client, identity);
    }

    /** Without accounts: the test rank from localRanks in server.properties. */
    private int localRank(String name) {
        for (String pair : config.localRanks.split(",")) {
            String[] parts = pair.trim().split("=", 2);
            if (parts.length == 2 && parts[0].trim().equalsIgnoreCase(name)) {
                return Protocol.rankFromName(parts[1].trim().toLowerCase());
            }
        }
        return Protocol.RANK_NORMAL;
    }

    /** Synchronized so two people asking for the same name at the same moment cannot both get it. */
    private synchronized boolean join(ClientConnection client, Identity identity) {
        if (!identity.accountId().isEmpty()) {
            // The same account again (a second computer, or a reconnect before the old one timed
            // out): the newer connection wins.
            players.values().stream().filter(p -> p.accountId.equals(identity.accountId())).findFirst()
                    .ifPresent(old -> old.close("You joined from somewhere else."));
        }
        if (players.size() >= config.maxPlayers) {
            client.close("The server is full (" + config.maxPlayers + " players).");
            return false;
        }
        // Account names are unique already; test names get a number when someone has them.
        String name = identity.accountId().isEmpty() ? uniqueName(identity.name()) : identity.name();
        client.name = name;
        client.rank = identity.rank();
        client.accountId = identity.accountId();

        client.send(new Welcome(client.id, name, client.rank, config.name, config.motd, config.maxPlayers));
        for (ClientConnection other : players.values()) {
            client.send(new PlayerJoined(other.id, other.name, other.rank));
        }
        players.put(client.id, client);
        if (arena != null) {
            arena.addPlayer(client.id);
        }
        if (world == null && chooser == null) {
            chooser = client; // first in: they pick
        }
        client.send(sessionFor(client));
        broadcast(Protocol.encode(new PlayerJoined(client.id, name, client.rank)), client);
        broadcast(Protocol.encode(Chat.system(name + " joined the game")), null);
        log(name + joinedAs(client.rank) + " joined from " + client.address() + " (" + players.size() + " online)");
        return true;
    }

    private static String joinedAs(int rank) {
        String tag = Protocol.rankTag(rank);
        return tag.isEmpty() ? "" : " " + tag;
    }

    /** The session as this player should see it: ready, their turn to pick, or waiting. */
    private SessionUpdate sessionFor(ClientConnection client) {
        if (world != null) {
            return new SessionUpdate(SessionUpdate.READY, "", world, sessionMods);
        }
        if (chooser == client) {
            return new SessionUpdate(SessionUpdate.CHOOSE, client.name, null, List.of());
        }
        return new SessionUpdate(SessionUpdate.WAITING, chooser == null ? "" : chooser.name, null, List.of());
    }

    private void sendSessionToAll() {
        for (ClientConnection player : players.values()) {
            player.send(sessionFor(player));
        }
    }

    /** The picker's answer. Anyone else's, or a second answer, just gets the current session back. */
    synchronized void chooseSession(ClientConnection client, ChooseSession choice) {
        if (world != null || chooser != client) {
            client.send(sessionFor(client));
            return;
        }
        ModRef picked = cleanRef(choice.world());
        if (picked == null) {
            client.send(Chat.system("That world could not be used - pick another."));
            client.send(sessionFor(client));
            return;
        }
        List<ModRef> mods = new ArrayList<>();
        for (ModRef mod : choice.mods()) {
            ModRef clean = cleanRef(mod);
            if (clean != null && mods.size() < Protocol.MAX_SESSION_MODS) {
                mods.add(clean);
            }
        }
        world = picked;
        sessionMods = List.copyOf(mods);
        chooser = null;
        sendSessionToAll();
        String withMods = mods.isEmpty() ? "" : " with " + mods.size() + " mod(s)";
        broadcast(Protocol.encode(Chat.system(client.name + " picked " + picked.name() + withMods)), null);
        log(client.name + " picked " + picked.name() + withMods);
    }

    // --- Arenas --------------------------------------------------------------------------

    /** The world's rules, from a player who has loaded it. The first one of a session counts. */
    synchronized void arenaSetup(ClientConnection client, ArenaSetup setup) {
        if (world == null || arena != null || !Protocol.supportsArena(client.protocolVersion)) {
            return; // nothing is being played yet, or the match is already set up
        }
        arena = new ArenaRules(ArenaRules.Settings.from(setup));
        for (ClientConnection player : players.values()) {
            arena.addPlayer(player.id);
        }
        ArenaRules.Settings rules = arena.settings();
        log(world.name() + " is an arena: " + rules.maps().size() + " map(s), first to "
                + rules.scoreLimit() + ", " + rules.minPlayers() + "+ players");
    }

    synchronized void arenaAction(ClientConnection client, ArenaAction action) {
        if (arena == null) {
            return;
        }
        switch (action.action()) {
            case ArenaAction.JOIN_TEAM -> arena.joinTeam(client.id, action.value());
            case ArenaAction.VOTE -> arena.vote(client.id, action.value());
            case ArenaAction.READY -> arena.setReady(client.id, action.value() != 0);
            case ArenaAction.GUN -> arena.selectGun(client.id, action.value());
            default -> {
                // An action from a newer game: nothing this server knows how to do.
            }
        }
    }

    /**
     * A shot. The rules decide whether it could have happened; if so, everyone else hears it
     * (the shooter has already drawn their own).
     */
    synchronized void shoot(ClientConnection client, Shoot shot) {
        if (arena == null || !finite(shot)) {
            return;
        }
        ArenaRules.ShotResult result = arena.shoot(client.id, shot.target(), shot.damage(), shot.gun(),
                System.nanoTime());
        if (result == ArenaRules.ShotResult.IGNORED) {
            return;
        }
        int landed = result == ArenaRules.ShotResult.MISS ? 0 : shot.target();
        broadcastArena(Protocol.encode(new ShotFired(client.id, shot.gun(), landed, shot.ox(), shot.oy(), shot.oz(),
                shot.dx(), shot.dy(), shot.dz(), Math.min(shot.distance(), 1000f))), client);
    }

    private static boolean finite(Shoot shot) {
        float[] values = {shot.ox(), shot.oy(), shot.oz(), shot.dx(), shot.dy(), shot.dz(), shot.distance()};
        for (float value : values) {
            if (!Float.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    /** Runs the match clock and tells everyone what changed. Called from the tick. */
    private synchronized void tickArena(float dt) {
        if (arena == null) {
            return;
        }
        arena.tick(dt);
        for (ArenaEvent event : arena.drainEvents()) {
            broadcastArena(Protocol.encode(event), null);
            if (event.kind() == ArenaEvent.MATCH_START) {
                log("Match started on " + arena.settings().maps().get(event.value()));
            } else if (event.kind() == ArenaEvent.MATCH_END) {
                log("Match over: " + (event.value() == ArenaRules.RED ? "red wins"
                        : event.value() == ArenaRules.BLUE ? "blue wins" : "a draw"));
            }
        }
        arenaResync -= dt;
        if (arena.isDirty() || arenaResync <= 0f) {
            arenaResync = 1f;
            arena.clearDirty();
            broadcastArena(Protocol.encode(arena.state()), null);
        }
    }

    /** Keeps names printable and short; a reference with nothing to find it by is dropped. */
    private static ModRef cleanRef(ModRef ref) {
        if (ref == null) {
            return null;
        }
        String key = Protocol.cleanChat(ref.key());
        String name = Protocol.cleanChat(ref.name());
        if (key.isEmpty() && ref.modIoId() <= 0) {
            return null;
        }
        return new ModRef(Math.max(0, ref.modIoId()), key, name.isEmpty() ? key : name);
    }

    /** The world name for the server list, or empty. */
    synchronized StatusReply status() {
        return new StatusReply(config.name, config.motd, players.size(), config.maxPlayers,
                world == null ? "" : world.name());
    }

    /** The name, or the name with a number after it when someone is already using it. */
    private String uniqueName(String wanted) {
        String name = wanted;
        for (int n = 2; nameTaken(name); n++) {
            String suffix = String.valueOf(n);
            name = wanted.substring(0, Math.min(wanted.length(), Protocol.MAX_NAME - suffix.length())) + suffix;
        }
        return name;
    }

    private boolean nameTaken(String name) {
        return players.values().stream().anyMatch(p -> p.name.equalsIgnoreCase(name));
    }

    synchronized void leave(ClientConnection client, String reason) {
        if (players.remove(client.id) == null) {
            return; // never got in
        }
        if (arena != null) {
            arena.removePlayer(client.id);
        }
        broadcast(Protocol.encode(new PlayerLeft(client.id)), null);
        broadcast(Protocol.encode(Chat.system(client.name + " left the game")), null);
        log(client.name + " left" + (reason == null ? "" : " (" + reason + ")")
                + " (" + players.size() + " online)");

        if (players.isEmpty()) {
            if (world != null && config.world.isEmpty()) {
                log("Everyone left - the next player picks the world");
            }
            resetSession();
        } else if (chooser == client) {
            // The picker left before picking: the longest-waiting player picks instead.
            chooser = players.values().stream().min(java.util.Comparator.comparingInt(p -> p.id)).orElse(null);
            sendSessionToAll();
        }
    }

    /** A line from a player: a /command, or chat for everyone. Runs on that player's thread. */
    void chat(ClientConnection client, String text) {
        if (text.startsWith("/")) {
            commands.run(client, text);
            return;
        }
        log("<" + client.name + "> " + text);
        broadcast(Protocol.encode(new Chat(Chat.PUBLIC, client.name, client.rank, text)), null);
    }

    // --- For ChatCommands ---------------------------------------------------------------

    /** The online player with this name (any case), or null. */
    ClientConnection player(String name) {
        return players.values().stream().filter(p -> p.name.equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    void broadcastSystem(String text) {
        broadcast(Protocol.encode(Chat.system(text)), null);
    }

    /** Null when the server runs without accounts. */
    AccountService accounts() {
        return accounts;
    }

    String playerList() {
        return players.size() + "/" + config.maxPlayers + " online: " + playerNames();
    }

    /** Runs a task on the server's scheduler thread after a delay. */
    void later(Runnable task, long delay, TimeUnit unit) {
        if (!scheduler.isShutdown()) {
            scheduler.schedule(task, delay, unit);
        }
    }

    // ---------------------------------------------------------------------
    // Tick
    // ---------------------------------------------------------------------

    /** Sends everyone the same snapshot, encoded once. */
    private void tick() {
        try {
            if (players.isEmpty()) {
                checkIdle();
                return;
            }
            lastOccupied = System.nanoTime();
            List<Pose> poses = new ArrayList<>(players.size());
            for (ClientConnection player : players.values()) {
                Move move = player.pose;
                if (move != null) {
                    poses.add(new Pose(player.id, move.x(), move.y(), move.z(),
                            move.yaw(), move.pitch(), move.moving()));
                }
            }
            broadcast(Protocol.encode(new Snapshot(poses)), null);
            tickArena(1f / Protocol.TICK_RATE);
        } catch (RuntimeException e) {
            // An exception would silently cancel the repeating task, freezing everyone.
            log("Tick failed: " + e);
        }
    }

    private void checkIdle() {
        if (config.idleShutdownMinutes <= 0 || !running) {
            return;
        }
        long idleNanos = System.nanoTime() - lastOccupied;
        if (idleNanos >= (long) (config.idleShutdownMinutes * 60_000_000_000L)) {
            log("Nobody on for " + config.idleShutdownMinutes + " minute(s) - shutting down");
            // Once only: the tick keeps firing until the process has exited.
            config.idleShutdownMinutes = 0;
            onIdle.run();
        }
    }

    private void broadcast(byte[] frame, ClientConnection except) {
        for (ClientConnection player : players.values()) {
            if (player != except) {
                player.send(frame);
            }
        }
    }

    /**
     * The same, for a message only some builds know about. A player on an older game hears nothing
     * of the match rather than being sent something they would drop anyway.
     */
    private void broadcastArena(byte[] frame, ClientConnection except) {
        for (ClientConnection player : players.values()) {
            if (player != except && Protocol.supportsArena(player.protocolVersion)) {
                player.send(frame);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Console
    // ---------------------------------------------------------------------

    /**
     * Admin commands typed into the server window. When run as a background service there is no
     * console, and the thread simply ends.
     */
    private void startConsole() {
        Thread console = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while (running && (line = in.readLine()) != null) {
                    command(line.trim());
                }
            } catch (IOException e) {
                // No console; nothing to read.
            }
        }, "console");
        console.setDaemon(true);
        console.start();
    }

    private void command(String line) {
        if (line.isEmpty()) {
            return;
        }
        String[] parts = line.split("\\s+", 2);
        String argument = parts.length > 1 ? parts[1] : "";
        switch (parts[0].toLowerCase()) {
            case "list" -> log(players.size() + "/" + config.maxPlayers + " online: " + playerNames());
            case "say" -> {
                String text = Protocol.cleanChat(argument);
                if (!text.isEmpty()) {
                    broadcastSystem("[Server] " + text);
                    log("[Server] " + text);
                }
            }
            case "kick" -> {
                ClientConnection target = players.values().stream()
                        .filter(p -> p.name.equalsIgnoreCase(argument.trim())).findFirst().orElse(null);
                if (target == null) {
                    log("No player called '" + argument + "'");
                } else {
                    target.close("You were kicked from the server.");
                }
            }
            case "stop" -> System.exit(0); // runs the shutdown hook
            default -> log("Commands: list, say <message>, kick <name>, stop");
        }
    }

    private String playerNames() {
        List<String> names = players.values().stream()
                .map(p -> (Protocol.rankTag(p.rank) + " " + p.name).trim()).sorted().toList();
        return names.isEmpty() ? "nobody" : String.join(", ", names);
    }

    void shutdown() {
        if (!running) {
            return;
        }
        running = false;
        log("Shutting down");
        for (ClientConnection player : players.values()) {
            player.close("The server is shutting down.");
        }
        try {
            // Give the writers a moment to deliver the goodbye before the process ends.
            Thread.sleep(300);
            if (listener != null) {
                listener.close();
            }
        } catch (IOException | InterruptedException ignored) {
            // Exiting anyway.
        }
        scheduler.shutdownNow();
    }

    static void log(String text) {
        System.out.println("[" + LocalTime.now().format(CLOCK) + "] " + text);
    }
}

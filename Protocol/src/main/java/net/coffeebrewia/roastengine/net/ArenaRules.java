package net.coffeebrewia.roastengine.net;

import net.coffeebrewia.roastengine.net.Protocol.ArenaEvent;
import net.coffeebrewia.roastengine.net.Protocol.ArenaPlayer;
import net.coffeebrewia.roastengine.net.Protocol.ArenaSetup;
import net.coffeebrewia.roastengine.net.Protocol.ArenaState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The rules of an arena match: teams, the map vote, health, kills and the score.
 *
 * <p>This is the referee. It lives on the dedicated server for online matches, and inside the
 * game itself for offline practice, so there is exactly one set of rules and both play by it.
 * It knows nothing about sockets or graphics - it is told what players want to do, decides what
 * actually happens, and hands back the result as {@link ArenaState} and {@link ArenaEvent}s.
 *
 * <pre>
 *   LOBBY ──everyone ready──▶ COUNTDOWN ──5 s──▶ PLAYING ──score limit or time──▶ RESULTS ──8 s──▶ LOBBY
 *                                 │
 *                                 └──someone un-readies or leaves──▶ LOBBY
 * </pre>
 *
 * <p>Not thread-safe: the caller holds one lock around every call.
 */
public final class ArenaRules {

    public static final int NO_TEAM = 0;
    public static final int RED = 1;
    public static final int BLUE = 2;

    public static final int LOBBY = 0;
    public static final int COUNTDOWN = 1;
    public static final int PLAYING = 2;
    public static final int RESULTS = 3;

    public static final float COUNTDOWN_SECONDS = 5f;
    public static final float RESULTS_SECONDS = 8f;
    /**
     * The fastest anyone may fire, whatever gun they claim to have: 20 shots a second. The server
     * cannot see the mod's gun list, so this is the one limit it can hold everyone to.
     */
    static final long MIN_SHOT_GAP_NANOS = 50_000_000L;

    /** What a shot came to. */
    public enum ShotResult {
        /** Not allowed: wrong phase, dead, too fast, or at a teammate. Nothing happened. */
        IGNORED,
        MISS,
        HIT,
        KILL
    }

    /** The rules of one arena, cleaned up so a bad setup cannot break the match. */
    public record Settings(List<String> maps, int scoreLimit, int maxHealth, float respawnSeconds,
                           int matchSeconds, int minPlayers) {

        public Settings {
            List<String> cleaned = new ArrayList<>();
            for (String map : maps) {
                String name = Protocol.cleanChat(map == null ? "" : map);
                if (!name.isEmpty() && cleaned.size() < Protocol.MAX_ARENA_MAPS) {
                    cleaned.add(name);
                }
            }
            if (cleaned.isEmpty()) {
                cleaned.add("Arena");
            }
            maps = List.copyOf(cleaned);
            scoreLimit = clamp(scoreLimit, 1, 100);
            maxHealth = clamp(maxHealth, 1, 1000);
            respawnSeconds = Float.isFinite(respawnSeconds) ? Math.max(0f, Math.min(30f, respawnSeconds)) : 3f;
            matchSeconds = clamp(matchSeconds, 30, 3600);
            minPlayers = clamp(minPlayers, 1, 16);
        }

        public static Settings from(ArenaSetup setup) {
            return new Settings(setup.maps(), setup.scoreLimit(), setup.maxHealth(),
                    setup.respawnSeconds(), setup.matchSeconds(), setup.minPlayers());
        }

        public ArenaSetup toMessage() {
            return new ArenaSetup(maps, scoreLimit, maxHealth, respawnSeconds, matchSeconds, minPlayers);
        }
    }

    private static final class Player {
        final int id;
        int team;
        boolean ready;
        int vote = -1;
        int health;
        int kills;
        int deaths;
        boolean alive = true;
        int gun = -1;
        float respawnIn;
        long lastShot;

        Player(int id, int team, int health) {
            this.id = id;
            this.team = team;
            this.health = health;
        }
    }

    private final Settings settings;
    private final Random random;
    private final Map<Integer, Player> players = new LinkedHashMap<>();
    private final List<ArenaEvent> events = new ArrayList<>();
    private int phase = LOBBY;
    private float timer;
    private int map = -1;
    private int redScore;
    private int blueScore;
    private int winner = NO_TEAM;
    private boolean dirty = true;

    public ArenaRules(Settings settings) {
        this(settings, new Random());
    }

    /** With a seeded random, for tests that need the tie-break to be predictable. */
    public ArenaRules(Settings settings, Random random) {
        this.settings = settings;
        this.random = random;
    }

    public Settings settings() {
        return settings;
    }

    public int phase() {
        return phase;
    }

    // --- Who is playing ---------------------------------------------------------------

    /** Someone arrived. They go on whichever team is shorter, so two players end up opposed. */
    public void addPlayer(int id) {
        if (players.containsKey(id)) {
            return;
        }
        Player player = new Player(id, shorterTeam(), settings.maxHealth());
        players.put(id, player);
        dirty = true;
    }

    public void removePlayer(int id) {
        Player gone = players.remove(id);
        if (gone == null) {
            return;
        }
        dirty = true;
        if (phase == COUNTDOWN && !canStart()) {
            backToLobby(false);
        } else if (phase == PLAYING && settings.minPlayers() > 1 && teamSize(gone.team) == 0) {
            // Nobody left on their side: the other team wins by default.
            endMatch(gone.team == RED ? BLUE : RED);
        }
    }

    public boolean hasPlayer(int id) {
        return players.containsKey(id);
    }

    // --- The lobby --------------------------------------------------------------------

    public void joinTeam(int id, int team) {
        Player player = players.get(id);
        if (player == null || (team != RED && team != BLUE) || player.team == team
                || (phase != LOBBY && phase != COUNTDOWN)) {
            return;
        }
        player.team = team;
        player.ready = false; // a new team is a new decision
        dirty = true;
        if (phase == COUNTDOWN) {
            backToLobby(false);
        }
    }

    public void vote(int id, int mapIndex) {
        Player player = players.get(id);
        if (player == null || mapIndex < 0 || mapIndex >= settings.maps().size()
                || (phase != LOBBY && phase != COUNTDOWN) || player.vote == mapIndex) {
            return;
        }
        player.vote = mapIndex;
        dirty = true;
        if (phase == COUNTDOWN) {
            map = chooseMap(); // a late vote can still swing it
        }
    }

    public void setReady(int id, boolean ready) {
        Player player = players.get(id);
        if (player == null || player.ready == ready || (phase != LOBBY && phase != COUNTDOWN)) {
            return;
        }
        player.ready = ready;
        dirty = true;
        if (phase == LOBBY && canStart()) {
            phase = COUNTDOWN;
            timer = COUNTDOWN_SECONDS;
            map = chooseMap();
        } else if (phase == COUNTDOWN && !canStart()) {
            backToLobby(false);
        }
    }

    /** Which gun a player has out, so everyone else can draw it. Any phase. */
    public void selectGun(int id, int gun) {
        Player player = players.get(id);
        if (player == null) {
            return;
        }
        int clamped = Math.max(-1, Math.min(255, gun));
        if (player.gun != clamped) {
            player.gun = clamped;
            dirty = true;
        }
    }

    /**
     * Everyone is here and ready: enough players, all of them ready, and - for a match meant for
     * more than one - somebody on each side.
     */
    boolean canStart() {
        if (players.size() < settings.minPlayers()) {
            return false;
        }
        for (Player player : players.values()) {
            if (!player.ready) {
                return false;
            }
        }
        return settings.minPlayers() <= 1 || (teamSize(RED) > 0 && teamSize(BLUE) > 0);
    }

    /** The map with the most votes; a tie, or no votes at all, is settled at random. */
    private int chooseMap() {
        int[] tally = votes();
        int best = 0;
        for (int count : tally) {
            best = Math.max(best, count);
        }
        List<Integer> leaders = new ArrayList<>();
        for (int i = 0; i < tally.length; i++) {
            if (tally[i] == best) {
                leaders.add(i);
            }
        }
        return leaders.get(random.nextInt(leaders.size()));
    }

    private int[] votes() {
        int[] tally = new int[settings.maps().size()];
        for (Player player : players.values()) {
            if (player.vote >= 0 && player.vote < tally.length) {
                tally[player.vote]++;
            }
        }
        return tally;
    }

    // --- The match --------------------------------------------------------------------

    /**
     * A shot, as the shooter's game saw it.
     *
     * @param target  who it hit, or 0 for a miss
     * @param damage  how much it did, before the rules have their say
     * @param nowNanos {@link System#nanoTime()}, passed in so tests can control the clock
     */
    public ShotResult shoot(int shooterId, int target, int damage, int gun, long nowNanos) {
        Player shooter = players.get(shooterId);
        if (phase != PLAYING || shooter == null || !shooter.alive) {
            return ShotResult.IGNORED;
        }
        if (shooter.lastShot != 0 && nowNanos - shooter.lastShot < MIN_SHOT_GAP_NANOS) {
            return ShotResult.IGNORED; // faster than any gun can fire
        }
        shooter.lastShot = nowNanos;
        if (target == 0) {
            return ShotResult.MISS;
        }
        Player victim = players.get(target);
        if (victim == null || !victim.alive || victim == shooter
                || (settings.minPlayers() > 1 && victim.team == shooter.team)) {
            // Friendly fire is off. A miss as far as anyone is concerned.
            return ShotResult.MISS;
        }
        int dealt = Math.max(1, Math.min(settings.maxHealth(), damage));
        victim.health = Math.max(0, victim.health - dealt);
        events.add(new ArenaEvent(ArenaEvent.HIT, shooterId, target, dealt));
        dirty = true;
        if (victim.health > 0) {
            return ShotResult.HIT;
        }
        victim.alive = false;
        victim.deaths++;
        victim.respawnIn = settings.respawnSeconds();
        shooter.kills++;
        if (shooter.team == RED) {
            redScore++;
        } else if (shooter.team == BLUE) {
            blueScore++;
        }
        events.add(new ArenaEvent(ArenaEvent.KILL, shooterId, target, gun));
        if (redScore >= settings.scoreLimit()) {
            endMatch(RED);
        } else if (blueScore >= settings.scoreLimit()) {
            endMatch(BLUE);
        }
        return ShotResult.KILL;
    }

    /** Runs the clocks: the countdown, respawns, the match timer and the results screen. */
    public void tick(float dt) {
        switch (phase) {
            case COUNTDOWN -> {
                timer -= dt;
                if (timer <= 0f) {
                    startMatch();
                }
            }
            case PLAYING -> {
                timer -= dt;
                for (Player player : players.values()) {
                    if (!player.alive) {
                        player.respawnIn -= dt;
                        if (player.respawnIn <= 0f) {
                            player.alive = true;
                            player.health = settings.maxHealth();
                            player.respawnIn = 0f;
                            events.add(new ArenaEvent(ArenaEvent.RESPAWN, player.id, 0, 0));
                            dirty = true;
                        }
                    }
                }
                if (phase == PLAYING && timer <= 0f) {
                    endMatch(redScore > blueScore ? RED : blueScore > redScore ? BLUE : NO_TEAM);
                }
            }
            case RESULTS -> {
                timer -= dt;
                if (timer <= 0f) {
                    backToLobby(true);
                }
            }
            default -> {
                // The lobby has no clock: it waits for everyone to be ready.
            }
        }
    }

    private void startMatch() {
        phase = PLAYING;
        timer = settings.matchSeconds();
        redScore = 0;
        blueScore = 0;
        winner = NO_TEAM;
        for (Player player : players.values()) {
            player.alive = true;
            player.health = settings.maxHealth();
            player.kills = 0;
            player.deaths = 0;
            player.respawnIn = 0f;
            player.lastShot = 0;
        }
        events.add(new ArenaEvent(ArenaEvent.MATCH_START, 0, 0, map));
        dirty = true;
    }

    private void endMatch(int winningTeam) {
        phase = RESULTS;
        timer = RESULTS_SECONDS;
        winner = winningTeam;
        events.add(new ArenaEvent(ArenaEvent.MATCH_END, 0, 0, winningTeam));
        dirty = true;
    }

    /** Back to picking teams. After a match, everyone has to ready up and vote again. */
    private void backToLobby(boolean afterMatch) {
        phase = LOBBY;
        timer = 0f;
        map = -1;
        if (afterMatch) {
            for (Player player : players.values()) {
                player.ready = false;
                player.vote = -1;
                player.alive = true;
                player.health = settings.maxHealth();
                player.respawnIn = 0f;
            }
        }
        dirty = true;
    }

    private int shorterTeam() {
        return teamSize(BLUE) < teamSize(RED) ? BLUE : RED;
    }

    private int teamSize(int team) {
        int count = 0;
        for (Player player : players.values()) {
            if (player.team == team) {
                count++;
            }
        }
        return count;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // --- What everyone gets told ------------------------------------------------------

    /** The match as it stands. */
    public ArenaState state() {
        List<Integer> tally = new ArrayList<>();
        for (int count : votes()) {
            tally.add(count);
        }
        List<ArenaPlayer> list = new ArrayList<>(players.size());
        for (Player p : players.values()) {
            list.add(new ArenaPlayer(p.id, p.team, p.ready, p.vote, p.health, p.kills, p.deaths,
                    p.alive, p.gun, Math.max(0f, p.respawnIn)));
        }
        return new ArenaState(phase, Math.max(0f, timer), map, redScore, blueScore, winner,
                List.copyOf(tally), List.copyOf(list));
    }

    /** True when something changed since the last {@link #clearDirty()}, and everyone should hear. */
    public boolean isDirty() {
        return dirty;
    }

    public void clearDirty() {
        dirty = false;
    }

    /** What happened since the last call, oldest first. */
    public List<ArenaEvent> drainEvents() {
        List<ArenaEvent> drained = List.copyOf(events);
        events.clear();
        return drained;
    }
}

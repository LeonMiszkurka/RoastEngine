package net.coffeebrewia.roastengine.arena;

import net.coffeebrewia.roastengine.net.ArenaRules;
import net.coffeebrewia.roastengine.net.Protocol.ArenaAction;
import net.coffeebrewia.roastengine.net.Protocol.ArenaState;
import net.coffeebrewia.roastengine.net.Protocol.Message;
import net.coffeebrewia.roastengine.net.Protocol.Shoot;

import java.util.List;

/**
 * The arena's line to its referee.
 *
 * <p>Online that is the server; offline it is an {@link ArenaRules} running right here, so a
 * player can try the maps and guns on their own. The arena plays the same either way.
 */
interface ArenaLink {

    /** Who we are, as the referee knows us. */
    int myId();

    /** Someone's name by id, for the kill feed and the lobby. */
    String nameOf(int id);

    /** The world's rules. Online, the first player's copy is the one the server keeps. */
    void setup(ArenaRules.Settings settings);

    void action(int action, int value);

    void shoot(Shoot shot);

    /** The match as it stands, or null before the referee has said anything. */
    ArenaState state();

    /** Shots and events since the last call: {@code ShotFired} and {@code ArenaEvent}. */
    List<Message> drainEvents();

    /** Runs anything that needs the frame's time; the local referee's clocks. */
    void update(float dt);

    /** True when there is nobody else to play: practice. */
    boolean isPractice();

    /** Talks to the dedicated server through the multiplayer session. */
    final class Online implements ArenaLink {
        private final net.coffeebrewia.roastengine.multiplayer.MultiplayerSession session;

        Online(net.coffeebrewia.roastengine.multiplayer.MultiplayerSession session) {
            this.session = session;
        }

        @Override
        public int myId() {
            return session.myId();
        }

        @Override
        public String nameOf(int id) {
            return session.nameOf(id);
        }

        @Override
        public void setup(ArenaRules.Settings settings) {
            session.sendArena(settings.toMessage());
        }

        @Override
        public void action(int action, int value) {
            session.sendArena(new ArenaAction(action, value));
        }

        @Override
        public void shoot(Shoot shot) {
            session.sendArena(shot);
        }

        @Override
        public ArenaState state() {
            return session.arenaState();
        }

        @Override
        public List<Message> drainEvents() {
            return session.drainArenaEvents();
        }

        @Override
        public void update(float dt) {
            // The session is pumped by the sandbox; the server keeps the clocks.
        }

        @Override
        public boolean isPractice() {
            return false;
        }
    }

    /** A referee of our own, for playing the arena offline. */
    final class Practice implements ArenaLink {
        private static final int ME = 1;
        private ArenaRules rules;

        @Override
        public int myId() {
            return ME;
        }

        @Override
        public String nameOf(int id) {
            return id == ME ? "You" : "someone";
        }

        @Override
        public void setup(ArenaRules.Settings settings) {
            // One player: the match starts as soon as they are ready.
            rules = new ArenaRules(new ArenaRules.Settings(settings.maps(), settings.scoreLimit(),
                    settings.maxHealth(), settings.respawnSeconds(), settings.matchSeconds(), 1));
            rules.addPlayer(ME);
        }

        @Override
        public void action(int action, int value) {
            if (rules == null) {
                return;
            }
            switch (action) {
                case ArenaAction.JOIN_TEAM -> rules.joinTeam(ME, value);
                case ArenaAction.VOTE -> rules.vote(ME, value);
                case ArenaAction.READY -> rules.setReady(ME, value != 0);
                case ArenaAction.GUN -> rules.selectGun(ME, value);
                default -> {
                    // Nothing else to do alone.
                }
            }
        }

        @Override
        public void shoot(Shoot shot) {
            if (rules != null) {
                rules.shoot(ME, shot.target(), shot.damage(), shot.gun(), System.nanoTime());
            }
        }

        @Override
        public ArenaState state() {
            return rules == null ? null : rules.state();
        }

        @Override
        public List<Message> drainEvents() {
            return rules == null ? List.of() : List.copyOf(rules.drainEvents());
        }

        @Override
        public void update(float dt) {
            if (rules != null) {
                rules.tick(dt);
            }
        }

        @Override
        public boolean isPractice() {
            return true;
        }
    }
}

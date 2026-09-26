package net.coffeebrewia.roastengine.net;

import net.coffeebrewia.roastengine.net.ArenaRules.ShotResult;
import net.coffeebrewia.roastengine.net.Protocol.ArenaEvent;
import net.coffeebrewia.roastengine.net.Protocol.ArenaPlayer;
import net.coffeebrewia.roastengine.net.Protocol.ArenaState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaRulesTest {

    private static final int ANA = 1;
    private static final int BEN = 2;
    /** A second apart: slower than any gun, so the fire-rate limit never gets in the way. */
    private long clock = 1_000_000_000L;

    private static ArenaRules twoPlayerArena() {
        ArenaRules rules = new ArenaRules(new ArenaRules.Settings(List.of("Warehouse", "Courtyard"),
                3, 100, 2f, 120, 2), new Random(1));
        rules.addPlayer(ANA);
        rules.addPlayer(BEN);
        return rules;
    }

    private long nextShot() {
        clock += 1_000_000_000L;
        return clock;
    }

    private static ArenaPlayer player(ArenaRules rules, int id) {
        return rules.state().players().stream().filter(p -> p.id() == id).findFirst().orElseThrow();
    }

    /** Both ready, and the countdown run out. */
    private static void startMatch(ArenaRules rules) {
        rules.setReady(ANA, true);
        rules.setReady(BEN, true);
        assertEquals(ArenaRules.COUNTDOWN, rules.phase());
        rules.tick(ArenaRules.COUNTDOWN_SECONDS + 0.1f);
        assertEquals(ArenaRules.PLAYING, rules.phase());
    }

    @Test
    void twoPlayersEndUpOnOppositeTeams() {
        ArenaRules rules = twoPlayerArena();
        assertEquals(ArenaRules.RED, player(rules, ANA).team());
        assertEquals(ArenaRules.BLUE, player(rules, BEN).team());
    }

    @Test
    void theMatchWaitsForEveryoneToBeReady() {
        ArenaRules rules = twoPlayerArena();
        rules.setReady(ANA, true);
        assertEquals(ArenaRules.LOBBY, rules.phase(), "one of two ready is not enough");
        rules.setReady(BEN, true);
        assertEquals(ArenaRules.COUNTDOWN, rules.phase());

        rules.setReady(BEN, false);
        assertEquals(ArenaRules.LOBBY, rules.phase(), "changing your mind stops the countdown");
    }

    @Test
    void aTeamOfOneCannotPlayItself() {
        ArenaRules rules = twoPlayerArena();
        rules.joinTeam(BEN, ArenaRules.RED);
        rules.setReady(ANA, true);
        rules.setReady(BEN, true);
        assertEquals(ArenaRules.LOBBY, rules.phase(), "both on red: nobody to play against");
    }

    @Test
    void theMostVotedMapIsPlayed() {
        ArenaRules rules = twoPlayerArena();
        rules.vote(ANA, 1);
        rules.vote(BEN, 1);
        startMatch(rules);
        assertEquals(1, rules.state().map());
        assertEquals(List.of(0, 2), rules.state().votes());
    }

    @Test
    void hitsTakeHealthAndAKillScoresForTheTeam() {
        ArenaRules rules = twoPlayerArena();
        startMatch(rules);
        rules.drainEvents();

        assertEquals(ShotResult.HIT, rules.shoot(ANA, BEN, 60, 2, nextShot()));
        assertEquals(40, player(rules, BEN).health());
        assertEquals(ShotResult.KILL, rules.shoot(ANA, BEN, 60, 2, nextShot()));

        ArenaState state = rules.state();
        assertEquals(1, state.redScore());
        assertFalse(player(rules, BEN).alive());
        assertEquals(1, player(rules, ANA).kills());
        assertEquals(1, player(rules, BEN).deaths());
        assertTrue(rules.drainEvents().stream().anyMatch(e -> e.kind() == ArenaEvent.KILL
                && e.a() == ANA && e.b() == BEN && e.value() == 2), "the kill feed hears about it");
    }

    @Test
    void theDeadCannotShootOrBeShotAndComeBackAfterTheRespawnTime() {
        ArenaRules rules = twoPlayerArena();
        startMatch(rules);
        rules.shoot(ANA, BEN, 100, 0, nextShot());

        assertEquals(ShotResult.IGNORED, rules.shoot(BEN, ANA, 50, 0, nextShot()));
        assertEquals(ShotResult.MISS, rules.shoot(ANA, BEN, 50, 0, nextShot()), "no kicking them while down");

        rules.tick(2.1f);
        ArenaPlayer back = player(rules, BEN);
        assertTrue(back.alive());
        assertEquals(100, back.health());
    }

    @Test
    void noFriendlyFire() {
        ArenaRules rules = new ArenaRules(new ArenaRules.Settings(List.of("A"), 5, 100, 2f, 120, 2));
        rules.addPlayer(1);
        rules.addPlayer(2);
        rules.addPlayer(3); // red again, with 1
        rules.setReady(1, true);
        rules.setReady(2, true);
        rules.setReady(3, true);
        rules.tick(10f);
        assertEquals(ShotResult.MISS, rules.shoot(1, 3, 50, 0, nextShot()));
        assertEquals(100, player(rules, 3).health());
    }

    @Test
    void firingFasterThanAnyGunCanIsIgnored() {
        ArenaRules rules = twoPlayerArena();
        startMatch(rules);
        long now = nextShot();
        assertEquals(ShotResult.HIT, rules.shoot(ANA, BEN, 10, 0, now));
        assertEquals(ShotResult.IGNORED, rules.shoot(ANA, BEN, 10, 0, now + 10_000_000L));
        assertEquals(90, player(rules, BEN).health());
    }

    @Test
    void damageCannotBeMoreThanAWholeHealthBar() {
        ArenaRules rules = twoPlayerArena();
        startMatch(rules);
        rules.shoot(ANA, BEN, 60_000, 0, nextShot());
        assertEquals(1, rules.state().redScore(), "one kill, not six hundred");
    }

    @Test
    void reachingTheScoreLimitEndsTheMatchAndTheLobbyComesBack() {
        ArenaRules rules = twoPlayerArena();
        startMatch(rules);
        for (int kill = 0; kill < 3; kill++) {
            rules.shoot(BEN, ANA, 100, 0, nextShot());
            rules.tick(2.1f); // respawn
        }
        ArenaState done = rules.state();
        assertEquals(ArenaRules.RESULTS, done.phase());
        assertEquals(ArenaRules.BLUE, done.winner());

        rules.tick(ArenaRules.RESULTS_SECONDS + 0.1f);
        assertEquals(ArenaRules.LOBBY, rules.phase());
        assertFalse(player(rules, ANA).ready(), "everyone readies up again for the next one");
        assertEquals(-1, player(rules, ANA).vote());
    }

    @Test
    void runningOutOfTimeGivesItToWhoeverIsAhead() {
        ArenaRules rules = twoPlayerArena();
        startMatch(rules);
        rules.shoot(ANA, BEN, 100, 0, nextShot());
        rules.tick(121f);
        assertEquals(ArenaRules.RESULTS, rules.phase());
        assertEquals(ArenaRules.RED, rules.state().winner());
    }

    @Test
    void theLastOneLeftOnATeamLeavingHandsTheOtherTeamTheWin() {
        ArenaRules rules = twoPlayerArena();
        startMatch(rules);
        rules.removePlayer(BEN);
        assertEquals(ArenaRules.RESULTS, rules.phase());
        assertEquals(ArenaRules.RED, rules.state().winner());
    }

    @Test
    void practiceWorksWithOnePlayer() {
        ArenaRules rules = new ArenaRules(new ArenaRules.Settings(List.of("Range"), 5, 100, 2f, 120, 1));
        rules.addPlayer(ANA);
        rules.setReady(ANA, true);
        rules.tick(ArenaRules.COUNTDOWN_SECONDS + 0.1f);
        assertEquals(ArenaRules.PLAYING, rules.phase());
        assertEquals(ShotResult.MISS, rules.shoot(ANA, 0, 20, 0, nextShot()));
    }

    @Test
    void aBadSetupIsTidiedUpRatherThanBelieved() {
        ArenaRules.Settings settings = new ArenaRules.Settings(List.of("", "  "), -5, 0, Float.NaN, 1, 0);
        assertEquals(List.of("Arena"), settings.maps());
        assertEquals(1, settings.scoreLimit());
        assertEquals(1, settings.maxHealth());
        assertEquals(3f, settings.respawnSeconds());
        assertEquals(30, settings.matchSeconds());
        assertEquals(1, settings.minPlayers());
    }

    @Test
    void theStateSurvivesTheWire() throws Exception {
        ArenaRules rules = twoPlayerArena();
        rules.vote(ANA, 1);
        startMatch(rules);
        rules.selectGun(BEN, -1);
        rules.selectGun(ANA, 3);
        ArenaState sent = rules.state();
        byte[] frame = Protocol.encode(sent);
        Protocol.Message back = Protocol.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(frame)));
        assertEquals(sent, back);
        assertEquals(-1, ((ArenaState) back).players().stream().filter(p -> p.id() == BEN)
                .findFirst().orElseThrow().gun(), "no gun survives as -1");
    }
}

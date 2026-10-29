package de.nikey.witherboss.boss;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class WitherStoreTest {
    @TempDir Path dir;
    private final UUID player = UUID.randomUUID(), run = UUID.randomUUID();

    private void attempt(WitherStore s, boolean preview, String status, WitherStore.Materials materials) {
        s.attempt(new WitherStore.Attempt(run, player, List.of(player), "forge", status, preview, materials));
    }

    private void attempt(WitherStore s, boolean preview, String status) {
        attempt(s, preview, status, new WitherStore.Materials(3, 4, 0, 1));
    }

    @Test
    void crashRecoveryForfeitsRunningFightsAndPreservesRoster() throws Exception {
        try (WitherStore s = new WitherStore(dir)) {
            attempt(s, false, "RUNNING");
        }
        try (WitherStore s = new WitherStore(dir)) {
            s.recover(true);
            assertTrue(s.pending(player).isEmpty());
            assertEquals("CANCELLED", s.attempts.get(run).status());
            s.recover(true);
            assertEquals(List.of(player), s.attempts.get(run).participants());
        }
    }

    @Test
    void preparedSummoningsAreRefundedOrCancelledAfterACrash() throws Exception {
        try (WitherStore s = new WitherStore(dir)) {
            attempt(s, false, "PREPARED");
            s.recover(true);
            assertEquals("REFUND", s.attempts.get(run).status());
            assertEquals(List.of("skulls:3", "sand:4", "sigil"), s.pending(player).stream().map(c -> c.kind).sorted(Comparator.comparing(k -> k.equals("sigil") ? 2 : k.startsWith("skulls") ? 0 : 1)).toList());
        }
        Path other = dir.resolve("other");
        try (WitherStore s = new WitherStore(other)) {
            attempt(s, false, "PREPARED");
            s.recover(false);
            assertEquals("CANCELLED", s.attempts.get(run).status());
            assertTrue(s.pending(player).isEmpty());
        }
    }

    @Test
    void refundIsIdempotentAndOnlyForUnfinishedRealAttempts() throws Exception {
        try (WitherStore s = new WitherStore(dir)) {
            attempt(s, false, "RUNNING", new WitherStore.Materials(3, 2, 2, 0));
            assertTrue(s.refund(run));
            assertFalse(s.refund(run));
            assertEquals(3, s.pending(player).size());
            attempt(s, true, "RUNNING");
            assertFalse(s.refund(run));
            assertEquals("TECHNICAL", s.attempts.get(run).status());
        }
    }

    @Test
    void previewNeverGrantsRewardsOrVictories() throws Exception {
        try (WitherStore s = new WitherStore(dir)) {
            attempt(s, true, "RUNNING");
            assertFalse(s.victory(run, List.of(player)));
            assertEquals(0, s.victories());
            assertTrue(s.titles.isEmpty());
        }
    }

    @Test
    void victoryCountsEveryWinAndMarksTheFirstOne() throws Exception {
        try (WitherStore s = new WitherStore(dir)) {
            attempt(s, false, "RUNNING");
            assertTrue(s.victory(run, List.of(player)));
            assertTrue(s.defeated());
            assertFalse(s.victory(run, List.of(player)));
            UUID second = UUID.randomUUID();
            s.attempt(new WitherStore.Attempt(second, player, List.of(player), "forge", "RUNNING", false, new WitherStore.Materials(0, 0, 0)));
            assertFalse(s.victory(second, List.of(player)));
            assertEquals(2, s.victories());
            assertTrue(s.titles.contains(player));
        }
        try (WitherStore s = new WitherStore(dir)) {
            assertEquals(2, s.victories());
            assertTrue(s.defeated());
        }
    }

    @Test
    void claimsSurviveRestartAndDeliveryCrashesStayVisible() throws Exception {
        String id;
        try (WitherStore s = new WitherStore(dir)) {
            attempt(s, false, "RUNNING");
            var claim = s.add(run + "_loot_0", player, "xp:300");
            assertSame(claim, s.add(run + "_loot_0", player, "other"));
            s.add(run + "_loot_1", player, "cmd:say hi");
            s.save(true);
            id = claim.id;
            s.delivery(claim, WitherStore.Delivery.DELIVERING);
        }
        try (WitherStore s = new WitherStore(dir)) {
            assertEquals(WitherStore.Delivery.DELIVERING, s.claims.get(id).state);
            assertEquals("xp:300", s.claims.get(id).kind);
            assertEquals(1, s.pending(player).size());
        }
    }

    @Test
    void cooldownTimestampPersists() throws Exception {
        try (WitherStore s = new WitherStore(dir)) {
            s.finished(123_456L);
        }
        try (WitherStore s = new WitherStore(dir)) {
            assertEquals(123_456L, s.lastFinished());
        }
    }

    @Test
    void corruptStateFailsWithoutOverwriting() throws Exception {
        String contents = "version: 99\n";
        Files.writeString(dir.resolve("state.yml"), contents);
        assertThrows(IllegalArgumentException.class, () -> new WitherStore(dir));
        assertEquals(contents, Files.readString(dir.resolve("state.yml")));
    }

    @Test
    void invalidMaterialsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WitherStore.Materials(4, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WitherStore.Materials(3, 3, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> new WitherStore.Materials(3, 4, 0, 2));
    }
}

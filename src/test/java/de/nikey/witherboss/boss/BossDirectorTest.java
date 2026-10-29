package de.nikey.witherboss.boss;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BossDirectorTest {
    private static BossDirector.Context ctx(RaidState.Phase phase, int now, int near, int alive) {
        return new BossDirector.Context(phase, now, near, alive, false, 0);
    }

    @Test
    void neverRepeatsAndRespectsPhasePools() {
        for (RaidState.Phase phase : List.of(RaidState.Phase.GLUT, RaidState.Phase.HAMMER, RaidState.Phase.CORE)) {
            BossDirector d = new BossDirector(new Random(phase.ordinal()));
            BossDirector.Attack last = null;
            Set<BossDirector.Attack> seen = EnumSet.noneOf(BossDirector.Attack.class);
            for (int i = 0; i < 400; i++) {
                var c = d.choose(ctx(phase, 1000 + i * 200, 3, 6));
                assertNotEquals(last, c.primary());
                assertTrue(BossDirector.weights(phase).containsKey(c.primary()));
                assertTrue(BossDirector.compatible(c.primary(), c.escort()));
                last = c.primary();
                seen.add(c.primary());
            }
            assertEquals(BossDirector.weights(phase).keySet(), seen, "not every attack appears in " + phase);
        }
    }

    @Test
    void meleeOnlyWithSomeoneNearAndChainNeedsTwo() {
        BossDirector d = new BossDirector(new Random(3));
        for (int i = 0; i < 300; i++) {
            var c = d.choose(new BossDirector.Context(RaidState.Phase.GLUT, i * 300, 0, 1, false, 0));
            assertFalse(c.primary().melee);
            assertNotEquals(BossDirector.Attack.CHAIN, c.primary());
        }
        BossDirector solo = new BossDirector(new Random(4));
        boolean chain = false;
        for (int i = 0; i < 300; i++)
            chain |= solo.choose(new BossDirector.Context(RaidState.Phase.GLUT, i * 700, 0, 1, true, 0)).primary() == BossDirector.Attack.CHAIN;
        assertTrue(chain, "the solo test should show the chain as the distance variant");
    }

    @Test
    void escortsOnlyAfterFirstPhaseAndOnlyWithHeavyAttacks() {
        BossDirector glut = new BossDirector(new Random(5));
        for (int i = 0; i < 200; i++) assertNull(glut.choose(ctx(RaidState.Phase.GLUT, i * 300, 3, 4)).escort());
        BossDirector core = new BossDirector(new Random(6));
        int escorts = 0;
        for (int i = 0; i < 400; i++) {
            var c = core.choose(ctx(RaidState.Phase.CORE, 1000 + i * 120, 3, 6));
            if (c.escort() != null) {
                escorts++;
                assertTrue(BossDirector.ESCORTABLE.contains(c.primary()));
                assertEquals(BossDirector.Kind.SUPPORT, c.escort().kind);
                assertTrue(c.escortDelay() >= 8);
            }
        }
        assertTrue(escorts > 10);
        assertFalse(BossDirector.compatible(BossDirector.Attack.COMET, BossDirector.Attack.CHAIN));
        assertFalse(BossDirector.compatible(BossDirector.Attack.RAIN, BossDirector.Attack.SKULLS));
    }

    @Test
    void mawWaitsAfterCoreStartAndCooldownsHold() {
        BossDirector d = new BossDirector(new Random(7));
        for (int i = 0; i < 100; i++)
            assertNotEquals(BossDirector.Attack.MAW, d.choose(new BossDirector.Context(RaidState.Phase.CORE, 100 + i, 3, 6, false, 100)).primary());
        BossDirector c = new BossDirector(new Random(8));
        c.record(BossDirector.Attack.SLAM, 0);
        assertFalse(c.allowed(BossDirector.Attack.SLAM, ctx(RaidState.Phase.GLUT, 100, 3, 6)));
        assertTrue(c.allowed(BossDirector.Attack.SLAM, ctx(RaidState.Phase.GLUT, 400, 3, 6)) || c.history().getFirst() == BossDirector.Attack.SLAM);
    }

    @Test
    void newAttacksUnlockOverTimeAndRespectLimits() {
        BossDirector early = new BossDirector(new Random(11));
        for (int i = 0; i < 100; i++) {
            var c = early.choose(new BossDirector.Context(RaidState.Phase.HAMMER, 100 + i, 3, 6, false, 100));
            assertTrue(c.primary().unlock <= i, c.primary() + " too early");
            assertNotEquals(BossDirector.Attack.RETRIBUTION, c.primary());
            early.reset();
        }
        BossDirector late = new BossDirector(new Random(12));
        Set<BossDirector.Attack> seen = EnumSet.noneOf(BossDirector.Attack.class);
        for (int i = 0; i < 600; i++) seen.add(late.choose(new BossDirector.Context(RaidState.Phase.HAMMER, 5000 + i * 900, 3, 6, false, 0, 4, 0)).primary());
        assertTrue(seen.containsAll(List.of(BossDirector.Attack.MINIONS, BossDirector.Attack.BEACONS, BossDirector.Attack.GRID, BossDirector.Attack.MIASMA, BossDirector.Attack.BLADE, BossDirector.Attack.DEBRIS, BossDirector.Attack.SPIKES)));
        BossDirector crowded = new BossDirector(new Random(13));
        for (int i = 0; i < 300; i++) {
            var c = crowded.choose(new BossDirector.Context(RaidState.Phase.CORE, 5000 + i * 900, 3, 6, false, 0, 4, 6));
            assertNotEquals(BossDirector.Attack.MINIONS, c.primary());
            assertNotEquals(BossDirector.Attack.BEACONS, c.primary());
        }
    }

    @Test
    void largeGroupsGetMoreEscortsAndFasterPacing() {
        int small = 0, large = 0;
        BossDirector a = new BossDirector(new Random(14)), b = new BossDirector(new Random(15));
        for (int i = 0; i < 600; i++) {
            if (a.choose(new BossDirector.Context(RaidState.Phase.HAMMER, 5000 + i * 700, 3, 4, false, 0, 0, 0)).escort() != null) small++;
            if (b.choose(new BossDirector.Context(RaidState.Phase.HAMMER, 5000 + i * 700, 3, 8, false, 0, 4, 0)).escort() != null) large++;
        }
        assertTrue(large > small, small + " vs " + large);
        Random r = new Random(16);
        double calm = 0, hard = 0;
        for (int i = 0; i < 200; i++) {
            calm += BossDirector.gap(RaidState.Phase.HAMMER, 0, r, 0, 4);
            hard += BossDirector.gap(RaidState.Phase.HAMMER, 0, r, 8, 8);
        }
        assertTrue(hard < calm * 0.8);
    }

    @Test
    void gapsShrinkInLaterPhasesAndAfterTwelveMinutes() {
        Random r = new Random(9);
        for (int i = 0; i < 50; i++) {
            int glut = BossDirector.gap(RaidState.Phase.GLUT, 0, r), core = BossDirector.gap(RaidState.Phase.CORE, 0, r);
            assertTrue(glut >= 36 && glut <= 50);
            assertTrue(core >= 18 && core <= 32);
            assertTrue(BossDirector.gap(RaidState.Phase.CORE, 15_000, r) <= 26);
            assertTrue(BossDirector.gap(RaidState.Phase.CORE, 0, r, 8, 8) <= 22);
        }
    }

    @Test
    void disabledAttacksNeverAppearAsPrimaryOrEscort() {
        Set<String> off = Set.of("slam", "skulls", "chain", "minions", "beacons", "grid");
        for (RaidState.Phase phase : List.of(RaidState.Phase.GLUT, RaidState.Phase.HAMMER, RaidState.Phase.CORE)) {
            BossDirector d = new BossDirector(new Random(21 + phase.ordinal()));
            d.disable(off);
            for (int i = 0; i < 600; i++) {
                var c = d.choose(ctx(phase, 1000 + i * 150, 3, 8));
                assertFalse(off.contains(c.primary().key()), c.primary() + " is disabled");
                if (c.escort() != null) assertFalse(off.contains(c.escort().key()), c.escort() + " is disabled");
            }
        }
    }

    @Test
    void pausesShrinkWithTheConfiguredTimeLimit() {
        Random fixed = new Random(1);
        int early = BossDirector.gap(RaidState.Phase.HAMMER, 100, fixed, 0, 4, 30_000);
        int late = BossDirector.gap(RaidState.Phase.HAMMER, 20_000, new Random(1), 0, 4, 30_000);
        assertTrue(late < early);
        assertEquals(early, BossDirector.gap(RaidState.Phase.HAMMER, 20_000, new Random(1), 0, 4, 90_000));
    }
}

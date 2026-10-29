package de.nikey.witherboss.boss;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RaidStateTest {
    private static List<UUID> ids(int n) {
        List<UUID> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(UUID.randomUUID());
        return out;
    }

    private static RaidState started(List<UUID> ids) {
        RaidState r = new RaidState(ids, 5200, 1);
        r.begin();
        return r;
    }

    @Test
    void healthGrowsWithEveryPlayerAndLateJoinKeepsProgress() {
        assertEquals(2000, new RaidState(ids(1), 2000, 1).maximum(), 1e-9);
        assertEquals(12000, new RaidState(ids(6), 2000, 1).maximum(), 1e-9);
        assertEquals(16000, new RaidState(ids(8), 2000, 1).maximum(), 1e-9);
        assertEquals(12000, new RaidState(ids(1), 2000, 6).maximum(), 1e-9);
        List<UUID> ids = ids(4);
        RaidState r = started(ids);
        r.hit(ids.getFirst(), RaidState.Part.HEART, 400, true, 0, null);
        double fraction = r.fraction();
        assertTrue(r.enlist(UUID.randomUUID()));
        assertTrue(r.enlist(UUID.randomUUID()));
        assertEquals(fraction, r.fraction(), 1e-9);
        assertFalse(r.enlist(ids.getFirst()));
    }

    @Test
    void armourHeartAndCoreMultipliers() {
        assertEquals(0.5, RaidState.multiplier(RaidState.Phase.GLUT, RaidState.Part.BODY, false));
        assertEquals(0.5, RaidState.multiplier(RaidState.Phase.HAMMER, RaidState.Part.HEART, false));
        assertEquals(1.5, RaidState.multiplier(RaidState.Phase.GLUT, RaidState.Part.HEART, true));
        assertEquals(0.8, RaidState.multiplier(RaidState.Phase.CORE, RaidState.Part.BODY, true));
        List<UUID> ids = ids(1);
        RaidState r = started(ids);
        assertEquals(50, r.hit(ids.getFirst(), RaidState.Part.BODY, 100, false, 0, null), 1e-9);
        assertEquals(150, r.hit(ids.getFirst(), RaidState.Part.HEART, 100, true, 0, null), 1e-9);
    }

    @Test
    void hardPhaseGatesDiscardOverflow() {
        List<UUID> ids = ids(1);
        RaidState r = started(ids);
        double applied = r.hit(ids.getFirst(), RaidState.Part.HEART, 100_000, true, 0, null);
        assertEquals(5200 * (1 - RaidState.GLUT_FLOOR), applied, 1e-6);
        assertEquals(RaidState.Phase.HAMMER, r.phase());
        r.hit(ids.getFirst(), RaidState.Part.HEART, 100_000, true, 0, null);
        assertEquals(RaidState.Phase.CORE, r.phase());
        assertEquals(5200 * RaidState.HAMMER_FLOOR, r.health(), 1e-6);
        r.hit(ids.getFirst(), RaidState.Part.HEART, 100_000, true, 0, null);
        assertEquals(RaidState.Phase.DYING, r.phase());
        assertEquals(0, r.health(), 1e-9);
        assertEquals(0, r.hit(ids.getFirst(), RaidState.Part.HEART, 10, true, 0, null));
    }

    @Test
    void healIsCappedAtCurrentPhaseCeiling() {
        List<UUID> ids = ids(1);
        RaidState r = started(ids);
        r.hit(ids.getFirst(), RaidState.Part.HEART, 100, true, 0, null);
        r.heal(0.5);
        assertEquals(5200, r.health(), 1e-9);
        r.hit(ids.getFirst(), RaidState.Part.HEART, 100_000, true, 0, null);
        r.hit(ids.getFirst(), RaidState.Part.HEART, 200, true, 0, null);
        r.heal(0.5);
        assertEquals(5200 * RaidState.GLUT_FLOOR, r.health(), 1e-6);
        assertEquals(RaidState.Phase.HAMMER, r.phase());
    }

    @Test
    void projectilesBookOnceAndIntroOrUnknownRejected() {
        List<UUID> ids = ids(1);
        RaidState intro = new RaidState(ids, 5200, 1);
        assertEquals(0, intro.hit(ids.getFirst(), RaidState.Part.BODY, 10, false, 0, null));
        RaidState r = started(ids);
        UUID arrow = UUID.randomUUID();
        assertTrue(r.hit(ids.getFirst(), RaidState.Part.BODY, 10, false, 0, arrow) > 0);
        assertEquals(0, r.hit(ids.getFirst(), RaidState.Part.HEART, 10, true, 0, arrow));
        assertEquals(0, r.hit(UUID.randomUUID(), RaidState.Part.BODY, 10, false, 0, null));
        assertEquals(0, r.hit(ids.getFirst(), RaidState.Part.BODY, Double.NaN, false, 0, null));
    }

    @Test
    void threeSharedRevivesWithProtection() {
        List<UUID> ids = ids(4);
        RaidState r = started(ids);
        for (UUID id : ids.subList(0, 4)) assertTrue(r.fallen(id, false, 0));
        assertTrue(r.wiped());
        for (int i = 0; i < 3; i++) assertTrue(r.revive(ids.get(i), 100));
        assertFalse(r.revive(ids.get(3), 100));
        assertEquals(0, r.revives());
        assertFalse(r.living(ids.getFirst(), 120));
        assertTrue(r.living(ids.getFirst(), 160));
    }

    @Test
    void logoutReturnsOnlyWithinSixtySecondsAndCostsRevive() {
        List<UUID> ids = ids(2);
        RaidState r = started(ids);
        assertTrue(r.fallen(ids.getFirst(), true, 1_000));
        assertFalse(r.reconnect(ids.getFirst(), 62_000));
        assertTrue(r.fallen(ids.get(1), true, 1_000));
        assertTrue(r.reconnect(ids.get(1), 30_000));
        assertEquals(RaidState.Status.FALLEN, r.member(ids.get(1)).status);
    }

    @Test
    void eligibilityNeedsRealContribution() {
        RaidState.Member m = new RaidState.Member();
        assertFalse(m.eligible());
        m.actions = 1;
        assertTrue(m.eligible());
    }

    @Test
    void rulesControlRevivesAndDamageMultipliers() {
        List<UUID> ids = ids(2);
        RaidState.Rules rules = new RaidState.Rules(7, 0.25, 0.4, 2.0);
        RaidState r = new RaidState(ids, 1000, 2, rules);
        r.begin();
        assertEquals(7, r.revives());
        assertEquals(25, r.hit(ids.getFirst(), RaidState.Part.BODY, 100, false, 0, null), 1e-9);
        assertEquals(200, r.hit(ids.getFirst(), RaidState.Part.HEART, 100, true, 0, null), 1e-9);
        assertEquals(0.4, RaidState.multiplier(rules, RaidState.Phase.CORE, RaidState.Part.BODY, false));
    }

    @Test
    void healthNeverDropsBelowTheScalingFloor() {
        assertEquals(4000, new RaidState(ids(1), 2000, 2).maximum(), 1e-9);
        assertEquals(4000, new RaidState(ids(2), 2000, 2).maximum(), 1e-9);
        assertEquals(6000, new RaidState(ids(3), 2000, 2).maximum(), 1e-9);
    }

    @Test
    void lootEligibilityUsesTheConfiguredDamage() {
        RaidState.Member m = new RaidState.Member();
        m.damage = 30;
        assertTrue(m.eligible(30));
        assertFalse(m.eligible(31));
        m.actions = 1;
        assertTrue(m.eligible(1_000_000));
    }
}

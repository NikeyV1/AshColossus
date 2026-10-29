package de.nikey.witherboss.boss;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BossGeometryTest {
    @Test
    void sectorFollowsYawConvention() {
        var s = new BossGeometry.Sector(0, 0, 0, 8.5, Math.toRadians(55));
        assertTrue(s.contains(0, 5));
        assertFalse(s.contains(0, -5));
        assertTrue(s.contains(3, 4));
        assertFalse(s.contains(6, -1));
        var east = new BossGeometry.Sector(0, 0, Math.PI / 2, 8.5, Math.toRadians(55));
        assertTrue(east.contains(5, 0));
        assertFalse(east.contains(-5, 0));
    }

    @Test
    void laneRingCircle() {
        var lane = new BossGeometry.Lane(0, 0, 0, 20, 1.5);
        assertTrue(lane.contains(1.4, 10));
        assertFalse(lane.contains(1.6, 10));
        assertFalse(lane.contains(0, 21));
        assertFalse(lane.contains(0, -2));
        assertEquals(20, lane.endZ(), 1e-9);
        var ring = new BossGeometry.Ring(0, 0, 3.5, 11);
        assertFalse(ring.contains(1, 1));
        assertTrue(ring.contains(5, 5));
        assertFalse(ring.contains(10, 10));
        assertTrue(new BossGeometry.Circle(2, 2, 1).contains(2.5, 2.5));
    }

    @Test
    void pillarsClipLanesAndBlockLineOfSight() {
        var pillar = new BossGeometry.Pillar(-1, 9, 2, 12);
        assertEquals(9, BossGeometry.clip(0.5, 0, 0, 40, List.of(pillar)), 1e-9);
        assertEquals(40, BossGeometry.clip(0.5, 0, Math.PI, 40, List.of(pillar)), 1e-9);
        assertEquals(40, BossGeometry.clip(5, 0, 0, 40, List.of(pillar)), 1e-9);
        assertTrue(BossGeometry.occluded(0.5, 0, 0.5, 20, List.of(pillar)));
        assertFalse(BossGeometry.occluded(0.5, 0, 0.5, 8, List.of(pillar)));
        assertFalse(BossGeometry.occluded(6, 0, 6, 20, List.of(pillar)));
    }

    @Test
    void turningTakesTheShortWay() {
        assertEquals(Math.PI - 0.05, BossGeometry.turn(Math.PI - 0.1, -Math.PI + 0.1, 0.05), 1e-9);
        assertEquals(0.2, BossGeometry.turn(0, 1, 0.2), 1e-9);
        assertEquals(1, BossGeometry.turn(0.9, 1, 0.2), 1e-9);
        assertEquals(-Math.PI + 0.5, BossGeometry.wrap(Math.PI + 0.5), 1e-9);
    }

    private static List<BossGeometry.Pillar> realPillars() {
        var out = new java.util.ArrayList<BossGeometry.Pillar>();
        for (int i = 0; i < 3; i++) {
            int[] c = WitherArenaDesign.seal(i);
            out.add(new BossGeometry.Pillar(c[0] - 1, c[1] - 1, c[0] + 2, c[1] + 2));
        }
        return out;
    }

    private static double clearance(double x, double z, BossGeometry.Pillar p) {
        return Math.hypot(x - Math.clamp(x, p.minX(), p.maxX()), z - Math.clamp(z, p.minZ(), p.maxZ()));
    }

    @Test
    void bossWalkingHeadOnIntoAPillarSlidesAroundInsteadOfStickingToIt() {
        var pillars = realPillars();
        double x = 0.5, z = 8, widest = 0;
        for (int i = 0; i < 600; i++) {
            double dx = 0.5 - x, dz = 30 - z, d = Math.hypot(dx, dz);
            double[] next = BossGeometry.slide(x, z, x + dx / d * 0.3, z + dz / d * 0.3, pillars, 3.8);
            x = next[0];
            z = next[1];
            for (var p : pillars) assertTrue(clearance(x, z, p) >= 3.8 - 1e-6, "Pillar touched at " + x + "/" + z);
            widest = Math.max(widest, Math.abs(x - 0.5));
        }
        assertTrue(widest > 3, "Boss sticks to the pillar: side path " + widest);
    }

    @Test
    void randomWalksNeverPutTheBossIntoAPillarOrFreezeItOutsideTheirReach() {
        var pillars = realPillars();
        var random = new java.util.Random(7);
        for (int run = 0; run < 40; run++) {
            double x = 0.5, z = 4;
            double tx = (random.nextDouble() - .5) * 48, tz = (random.nextDouble() - .5) * 48;
            double startDistance = Math.hypot(tx - x, tz - z);
            for (int i = 0; i < 1200; i++) {
                if (i % 150 == 0) {
                    tx = (random.nextDouble() - .5) * 48;
                    tz = (random.nextDouble() - .5) * 48;
                    if (Math.hypot(tx, tz) > 24) {
                        double scale = 24 / Math.hypot(tx, tz);
                        tx *= scale;
                        tz *= scale;
                    }
                }
                double dx = tx - x, dz = tz - z, d = Math.hypot(dx, dz);
                if (d < 0.05) continue;
                double step = Math.min(0.4, d);
                double[] next = BossGeometry.slide(x, z, x + dx / d * step, z + dz / d * step, pillars, 3.8);
                x = next[0];
                z = next[1];
                for (var p : pillars) assertTrue(clearance(x, z, p) >= 3.8 - 1e-6, "Pillar touched at " + x + "/" + z + " in run " + run);
            }
            assertTrue(Double.isFinite(x) && Double.isFinite(z) && startDistance >= 0);
        }
    }

    @Test
    void aTargetInsideAPillarLeavesTheBossWhereItStands() {
        var pillars = realPillars();
        double[] result = BossGeometry.slide(0.5, 10, 0.5, 20, pillars, 3.8);
        assertEquals(0.5, result[0], 1e-9);
        assertEquals(10, result[1], 1e-9);
    }
}

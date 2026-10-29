package de.nikey.witherboss.boss;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.nio.file.*;
import java.util.*;

class WitherArenaDesignTest {
    private static final WitherArenaDesign DESIGN = WitherArenaDesign.create();
    private static final int STAND = WitherArenaDesign.STAND;

    private static boolean pillarZone(int x, int z) {
        for (int i = 0; i < 3; i++) {
            int[] s = WitherArenaDesign.seal(i);
            if (Math.abs(x - s[0]) <= 2 && Math.abs(z - s[1]) <= 2) return true;
        }
        return false;
    }

    private static boolean solid(Material material) {
        return material != Material.AIR
                && material != Material.LAVA
                && material != Material.SOUL_LANTERN
                && material != Material.SOUL_CAMPFIRE;
    }

    @Test
    void dumpForRendering() throws Exception {
        String target = System.getProperty("arena.dump");
        if (target == null) return;
        try (BufferedWriter out = Files.newBufferedWriter(Path.of(target))) {
            for (var entry : DESIGN.cells().entrySet()) {
                if (entry.getValue().material() == Material.AIR || entry.getValue().material() == Material.BARRIER) continue;
                long key = entry.getKey();
                out.write(
                        WitherArenaDesign.keyX(key)
                                + " "
                                + WitherArenaDesign.keyY(key)
                                + " "
                                + WitherArenaDesign.keyZ(key)
                                + " "
                                + entry.getValue().material().name()
                                + (entry.getValue().state() == null ? "" : " " + entry.getValue().state())
                                + "\n");
            }
        }
    }

    @Test
    void playAreaIsSolidFlatAndFreeOfObstaclesAboveTheFloor() {
        int cells = 0;
        for (int x = -40; x <= 40; x++)
            for (int z = -40; z <= 40; z++) {
                double r = Math.hypot(x, z);
                if (r > 36) continue;
                cells++;
                Material floor = DESIGN.material(x, STAND - 1, z);
                assertTrue(solid(floor), "Floor missing at " + x + "/" + z + ": " + floor);
                if (pillarZone(x, z) || r < 3) continue;
                for (int y = STAND; y <= STAND + 17; y++)
                    assertEquals(Material.AIR, DESIGN.material(x, y, z), "Obstacle at " + x + "/" + y + "/" + z);
            }
        assertTrue(cells > 4000);
    }

    @Test
    void pillarsAreSolidFromFloorToTheArenaValidationHeight() {
        for (int i = 0; i < 3; i++) {
            int[] c = WitherArenaDesign.seal(i);
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    for (int y = STAND; y < STAND + 8; y++)
                        assertTrue(solid(DESIGN.material(c[0] + dx, y, c[1] + dz)), "Pillar " + i + " at " + dx + "/" + y + "/" + dz);
        }
    }

    @Test
    void lavaNeverTouchesAirSideways() {
        int lava = 0;
        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (var entry : DESIGN.cells().entrySet()) {
            if (entry.getValue().material() != Material.LAVA) continue;
            lava++;
            long key = entry.getKey();
            int x = WitherArenaDesign.keyX(key), y = WitherArenaDesign.keyY(key), z = WitherArenaDesign.keyZ(key);
            for (int[] d : around) {
                Material neighbour = DESIGN.material(x + d[0], y, z + d[1]);
                boolean outerEdge = Math.hypot(x + d[0], z + d[1]) > WitherArenaDesign.OUTER;
                assertFalse(neighbour == Material.AIR && !outerEdge, "Lava leaks at " + x + "/" + y + "/" + z);
            }
            assertNotEquals(Material.AIR, DESIGN.material(x, y - 1, z), "Lava has no floor at " + x + "/" + y + "/" + z);
        }
        assertTrue(lava > 500);
    }

    @Test
    void noLavaCanBeReachedFromTheWaitingGalleryOrTheStairs() {
        for (int x = -18; x <= 18; x++)
            for (int z = -48; z <= -36; z++)
                for (int y = STAND - 2; y <= STAND + 8; y++)
                    assertNotEquals(Material.LAVA, DESIGN.material(x, y, z), "Lava in the waiting area at " + x + "/" + y + "/" + z);
    }

    @Test
    void waitingGalleryHasSafeFloorAndFreeAirAndTheStairsClimbFromTheArena() {
        assertTrue(solid(DESIGN.material(0, STAND + 1, -43)));
        for (int y = STAND + 2; y <= STAND + 6; y++) assertEquals(Material.AIR, DESIGN.material(0, y, -43), "Wait point blocked at y=" + y);
        for (int x = -18; x <= 18; x++)
            for (int z = -48; z <= -38; z++) {
                assertTrue(solid(DESIGN.material(x, STAND + 1, z)), "Gallery floor missing at " + x + "/" + z);
                assertEquals(Material.AIR, DESIGN.material(x, STAND + 2, z), "Gallery blocked at " + x + "/" + z);
                assertEquals(Material.AIR, DESIGN.material(x, STAND + 3, z), "Gallery blocked at " + x + "/" + z);
            }
        for (int x = -3; x <= 3; x++) {
            assertEquals(Material.POLISHED_BLACKSTONE_BRICK_STAIRS, DESIGN.material(x, STAND, -37));
            assertEquals(Material.POLISHED_BLACKSTONE_BRICK_STAIRS, DESIGN.material(x, STAND + 1, -38));
            assertEquals(Material.AIR, DESIGN.material(x, STAND + 1, -37));
            assertEquals(Material.AIR, DESIGN.material(x, STAND + 2, -38));
            assertEquals(Material.AIR, DESIGN.material(x, STAND + 3, -37));
        }
        assertTrue(solid(DESIGN.material(0, STAND - 1, -36)));
        assertEquals(Material.AIR, DESIGN.material(0, STAND, -36));
    }

    @Test
    void entryAndSoulLanternSpotsStandOnFloorWithFreeAirAbove() {
        for (int i = 0; i < 16; i++) {
            int[] s = WitherArenaDesign.soul(i);
            assertEquals(Material.SOUL_SOIL, DESIGN.material(s[0], STAND - 1, s[1]));
            assertEquals(Material.AIR, DESIGN.material(s[0], STAND, s[1]));
            assertEquals(Material.AIR, DESIGN.material(s[0], STAND + 1, s[1]));
        }
        assertTrue(solid(DESIGN.material(0, STAND - 1, -27)));
        assertEquals(Material.AIR, DESIGN.material(0, STAND, -27));
        assertEquals(Material.AIR, DESIGN.material(0, STAND + 1, -27));
    }

    @Test
    void parapetIsAThreeBlockWallAllAroundTheFloorExceptTheStairs() {
        for (int x = -40; x <= 40; x++)
            for (int z = -40; z <= 40; z++) {
                double r = Math.hypot(x, z);
                if (r < WitherArenaDesign.PLAY || r >= WitherArenaDesign.PARAPET) continue;
                if (WitherArenaDesign.galleryCell(x, z)) continue;
                assertEquals(Material.POLISHED_BLACKSTONE_BRICKS, DESIGN.material(x, STAND, z), "Parapet has a gap at " + x + "/" + z);
                assertEquals(Material.BARRIER, DESIGN.material(x, STAND + 1, z), "Barrier missing at " + x + "/" + z);
                assertEquals(Material.BARRIER, DESIGN.material(x, STAND + 2, z), "Barrier missing at " + x + "/" + z);
            }
    }

    @Test
    void everythingStaysInsideTheBuildLimitsAndTheMarkerIsSet() {
        int highest = Integer.MIN_VALUE, lowest = Integer.MAX_VALUE;
        for (long key : DESIGN.cells().keySet()) {
            highest = Math.max(highest, WitherArenaDesign.keyY(key));
            lowest = Math.min(lowest, WitherArenaDesign.keyY(key));
            assertTrue(Math.hypot(WitherArenaDesign.keyX(key), WitherArenaDesign.keyZ(key)) <= WitherArenaDesign.OUTER + 1);
        }
        assertTrue(highest < 140, "too high: " + highest);
        assertTrue(lowest >= WitherArenaDesign.BASE);
        assertEquals(WitherArenaDesign.MARKER, DESIGN.material(0, WitherArenaDesign.BASE, 0));
        assertEquals(Material.LODESTONE, DESIGN.material(0, STAND, 0));
    }

    @Test
    void statesAreOnlyUsedWhereTheyAreValid() {
        for (var cell : DESIGN.cells().values()) {
            if (cell.state() == null) continue;
            assertTrue(cell.material().name().endsWith("_STAIRS"));
            assertTrue(cell.state().matches("\\[[a-z_]+=[a-z_]+(,[a-z_]+=[a-z_]+)*\\]"));
        }
    }
}

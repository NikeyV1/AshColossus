package de.nikey.witherboss.boss;

import org.bukkit.Material;

import java.util.*;

public final class WitherArenaDesign {
    public record Cell(Material material, String state) {}

    public static final int VERSION = 2;
    public static final int STAND = 80, FLOOR = STAND - 1, BASE = STAND - 4;
    public static final double PLAY = 36.5, PARAPET = 37.5, MOAT = 43.5, EMBANKMENT = 44.5, TERRACE = 47;
    public static final int TIERS = 8, OUTER = 71, GALLERY_X = 19, GALLERY_FRONT = -36, GALLERY_BACK = -51;
    public static final double WALL_FROM = TERRACE + TIERS * 2, WALL_TO = WALL_FROM + 3.5;
    public static final Material MARKER = Material.REINFORCED_DEEPSLATE;

    private final Map<Long, Cell> cells = new HashMap<>();
    private final Map<String, Cell> interned = new HashMap<>();

    private WitherArenaDesign() {}

    public static WitherArenaDesign create() {
        WitherArenaDesign design = new WitherArenaDesign();
        design.paint();
        return design;
    }

    public static long key(int x, int y, int z) {
        return ((long) (x + 4096) << 40) | ((long) (y + 4096) << 20) | (z + 4096);
    }

    public static int keyX(long key) {
        return (int) (key >> 40) - 4096;
    }

    public static int keyY(long key) {
        return (int) ((key >> 20) & 0xFFFFF) - 4096;
    }

    public static int keyZ(long key) {
        return (int) (key & 0xFFFFF) - 4096;
    }

    public Map<Long, Cell> cells() {
        return Collections.unmodifiableMap(cells);
    }

    public Cell at(int x, int y, int z) {
        return cells.get(key(x, y, z));
    }

    public Material material(int x, int y, int z) {
        Cell cell = at(x, y, z);
        return cell == null ? Material.AIR : cell.material();
    }

    public static int[] seal(int index) {
        double[] offset = WitherArena.sealOffset(index);
        return new int[] {(int) Math.floor(.5 + offset[0]), (int) Math.floor(.5 + offset[1])};
    }

    public static int[] soul(int index) {
        double[] offset = WitherArena.soulOffset(index);
        return new int[] {(int) Math.floor(.5 + offset[0]), (int) Math.floor(.5 + offset[1])};
    }

    public static boolean galleryCell(int x, int z) {
        return Math.abs(x) <= GALLERY_X && z <= GALLERY_FRONT && z >= GALLERY_BACK && Math.hypot(x, z) >= PLAY;
    }

    private void put(int x, int y, int z, Material material) {
        put(x, y, z, material, null);
    }

    private void put(int x, int y, int z, Material material, String state) {
        cells.put(key(x, y, z), interned.computeIfAbsent(material + "|" + state, k -> new Cell(material, state)));
    }

    private void column(int x, int z, int from, int to, Material material) {
        for (int y = from; y <= to; y++) put(x, y, z, material);
    }

    private void clear(int x, int z, int from, int to) {
        for (int y = from; y <= to; y++) put(x, y, z, Material.AIR);
    }

    private static int hash(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h ^ (h >>> 16)) & 0x7FFFFFFF;
    }

    private static double angle(int x, int z) {
        double a = Math.atan2(z, x);
        return a < 0 ? a + Math.PI * 2 : a;
    }

    private static double offsetFromSector(double angle, double r, double sector) {
        return Math.abs(((angle + sector / 2) % sector) - sector / 2) * r;
    }

    private void paint() {
        for (int x = -OUTER; x <= OUTER; x++)
            for (int z = -OUTER; z <= OUTER; z++) {
                double r = Math.hypot(x, z);
                if (r > OUTER) continue;
                column(x, z, BASE, FLOOR - 1, r < PARAPET ? Material.POLISHED_BLACKSTONE : Material.DEEPSLATE_BRICKS);
                if (r < PLAY) floor(x, z, r);
                else if (r < PARAPET) parapet(x, z);
                else if (r < MOAT) moat(x, z);
                else if (r < EMBANKMENT) embankment(x, z);
                else if (r < TERRACE) terrace(x, z, r);
                else if (r < WALL_FROM) tier(x, z, r);
                else if (r < WALL_TO) wall(x, z);
            }
        for (int k = 0; k < 12; k++) tower(k);
        for (int k = 0; k < 12; k++) {
            double a = Math.toRadians(k * 30);
            int bx = (int) Math.round(Math.cos(a) * 45.8), bz = (int) Math.round(Math.sin(a) * 45.8);
            put(bx, STAND, bz, Material.SOUL_CAMPFIRE);
        }
        gallery();
        for (int i = 0; i < 3; i++) pillar(i);
        for (int i = 0; i < 16; i++) {
            int[] s = soul(i);
            put(s[0], FLOOR, s[1], Material.SOUL_SOIL);
        }
        put(0, STAND, 0, Material.LODESTONE);
        put(0, BASE, 0, MARKER);
    }

    private void floor(int x, int z, double r) {
        int h = hash(x, z) % 100;
        Material material;
        if (r < 1.6) material = Material.CRYING_OBSIDIAN;
        else if (r < 3.2 || r > 5.2 && r < 6.2) material = Material.CHISELED_POLISHED_BLACKSTONE;
        else if (r < 5.2) material = h < 12 ? Material.CRACKED_POLISHED_BLACKSTONE_BRICKS : Material.POLISHED_BLACKSTONE_BRICKS;
        else if (Math.abs(r - 12) < .75 || Math.abs(r - 24) < .75) material = Material.POLISHED_DEEPSLATE;
        else if (r >= 35) material = Material.DEEPSLATE_TILES;
        else if (r > 33.6) material = Material.CHISELED_POLISHED_BLACKSTONE;
        else if (r > 7 && offsetFromSector(angle(x, z), r, Math.PI / 6) < .6) material = Material.DEEPSLATE_TILES;
        else if (h < 9) material = Material.CRACKED_POLISHED_BLACKSTONE_BRICKS;
        else if (h < 22) material = Material.POLISHED_BLACKSTONE;
        else if (h < 26) material = Material.BLACKSTONE;
        else material = Material.POLISHED_BLACKSTONE_BRICKS;
        put(x, FLOOR, z, material);
    }

    private void parapet(int x, int z) {
        put(x, FLOOR, z, Material.DEEPSLATE_TILES);
        put(x, STAND, z, Material.POLISHED_BLACKSTONE_BRICKS);
        put(x, STAND + 1, z, Material.BARRIER);
        put(x, STAND + 2, z, Material.BARRIER);
    }

    private void moat(int x, int z) {
        put(x, FLOOR, z, Material.LAVA);
        put(x, FLOOR - 1, z, Material.LAVA);
        put(x, FLOOR - 2, z, Material.OBSIDIAN);
    }

    private void embankment(int x, int z) {
        put(x, FLOOR, z, Material.POLISHED_BLACKSTONE_BRICKS);
        put(x, STAND, z, Material.CHISELED_POLISHED_BLACKSTONE);
        if ((int) (angle(x, z) / (Math.PI * 2) * 144) % 6 == 0) put(x, STAND + 1, z, Material.SOUL_LANTERN);
    }

    private void terrace(int x, int z, double r) {
        Material material = r > 45.4 && r < 46.2 ? Material.CHISELED_POLISHED_BLACKSTONE : hash(x, z) % 100 < 14 ? Material.CRACKED_DEEPSLATE_BRICKS : Material.DEEPSLATE_TILES;
        put(x, FLOOR, z, material);
    }

    private void tier(int x, int z, double r) {
        int t = (int) ((r - TERRACE) / 2);
        boolean aisle = offsetFromSector(angle(x, z), r, Math.PI / 6) < 1.4;
        Material surface = aisle ? Material.POLISHED_DEEPSLATE : t % 2 == 0 ? Material.POLISHED_BLACKSTONE_BRICKS : Material.DEEPSLATE_BRICKS;
        if (!aisle && hash(x, z) % 100 < 6) surface = Material.CRACKED_DEEPSLATE_BRICKS;
        for (int y = FLOOR; y < STAND + t; y++) put(x, y, z, Material.DEEPSLATE_BRICKS);
        put(x, STAND + t, z, surface);
        if (aisle && offsetFromSector(angle(x, z), r, Math.PI / 6) < .5 && (r - TERRACE) % 2 < 1) put(x, STAND + t + 1, z, Material.SOUL_LANTERN);
    }

    private void wall(int x, int z) {
        double r = Math.hypot(x, z);
        int segment = (int) (angle(x, z) / (Math.PI * 2) * 120);
        boolean slit = r < WALL_FROM + 1.2 && Math.abs(Math.floorMod((int) (angle(x, z) / (Math.PI * 2) * 480), 40) - 20) <= 1;
        int top = STAND + 22 + (segment % 2 == 0 ? 2 : 0);
        for (int y = FLOOR; y <= top; y++) {
            Material material =
                    switch (y - STAND) {
                        case 9, 10 -> Material.CHISELED_POLISHED_BLACKSTONE;
                        case 17 -> Material.POLISHED_BLACKSTONE_BRICKS;
                        case 13, 14, 15 -> slit ? Material.SEA_LANTERN : Material.DEEPSLATE_BRICKS;
                        default -> hash(x, y * 31 + z) % 100 < 7 ? Material.CRACKED_DEEPSLATE_BRICKS : Material.DEEPSLATE_BRICKS;
                    };
            put(x, y, z, material);
        }
    }

    private void tower(int index) {
        double a = Math.toRadians(index * 30 + 15);
        int cx = (int) Math.round(Math.cos(a) * 65.5), cz = (int) Math.round(Math.sin(a) * 65.5);
        for (int dx = -5; dx <= 5; dx++)
            for (int dz = -5; dz <= 5; dz++) {
                double d = Math.hypot(dx, dz);
                int x = cx + dx, z = cz + dz;
                for (int y = FLOOR; y <= STAND + 32; y++) {
                    int h = y - STAND;
                    Material material = null;
                    if (h <= 16) {
                        if (d <= 4.7) material = h == 16 ? Material.CHISELED_POLISHED_BLACKSTONE : Material.DEEPSLATE_BRICKS;
                    } else if (h <= 29) {
                        if (d <= 3.7) material = h == 23 ? Material.CHISELED_POLISHED_BLACKSTONE : Material.DEEPSLATE_BRICKS;
                    } else if (h == 30) {
                        if (d <= 4.7) material = Material.POLISHED_BLACKSTONE_BRICKS;
                    } else if (h == 31) {
                        if (d <= 4.7) material = d < 2.2 ? Material.POLISHED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS;
                    } else if (d > 3.7 && d <= 4.7 && (Math.abs(dx) + Math.abs(dz)) % 2 == 0) material = Material.POLISHED_BLACKSTONE_BRICKS;
                    if (material != null) put(x, y, z, material);
                }
            }
        put(cx, STAND + 32, cz, Material.SOUL_CAMPFIRE);
        put(cx + 2, STAND + 32, cz, Material.SOUL_LANTERN);
        put(cx - 2, STAND + 32, cz, Material.SOUL_LANTERN);
        put(cx, STAND + 32, cz + 2, Material.SOUL_LANTERN);
        put(cx, STAND + 32, cz - 2, Material.SOUL_LANTERN);
    }

    private static final String NORTH_STAIR = "[facing=north,half=bottom,shape=straight]";

    private void gallery() {
        for (int x = -GALLERY_X; x <= GALLERY_X; x++)
            for (int z = GALLERY_BACK; z <= GALLERY_FRONT; z++) {
                if (!galleryCell(x, z)) continue;
                boolean aisle = Math.abs(x) <= 3;
                column(x, z, BASE, STAND, Material.DEEPSLATE_BRICKS);
                if (z >= -37) {
                    if (aisle && z == -37) put(x, STAND, z, Material.POLISHED_BLACKSTONE_BRICK_STAIRS, NORTH_STAIR);
                    else put(x, STAND, z, Material.POLISHED_BLACKSTONE_BRICKS);
                    clear(x, z, STAND + 1, STAND + 30);
                    continue;
                }
                if (aisle && z == -38) put(x, STAND + 1, z, Material.POLISHED_BLACKSTONE_BRICK_STAIRS, NORTH_STAIR);
                else {
                    Material surface = Math.abs(x) <= 1 ? Material.RED_NETHER_BRICKS : (x + z) % 2 == 0 ? Material.DEEPSLATE_TILES : Material.POLISHED_BLACKSTONE_BRICKS;
                    if (Math.abs(x) == GALLERY_X || z == GALLERY_BACK) surface = Material.CHISELED_POLISHED_BLACKSTONE;
                    put(x, STAND + 1, z, surface);
                }
                clear(x, z, STAND + 2, STAND + 30);
            }
        for (int x = -GALLERY_X - 2; x <= GALLERY_X + 2; x++)
            for (int z = GALLERY_BACK - 4; z <= GALLERY_FRONT; z++) {
                boolean rear = z < GALLERY_BACK;
                boolean flank = Math.abs(x) > GALLERY_X;
                if (!rear && !flank) continue;
                if (flank && Math.hypot(x, z) < PLAY) continue;
                int height = rear ? STAND + 15 + (hash(x, z) % 3 == 0 ? 1 : 0) : STAND + 15 - Math.max(0, z + 44);
                column(x, z, BASE, height, Material.DEEPSLATE_BRICKS);
                if (rear && z == GALLERY_BACK - 1) put(x, STAND + 10, z, Material.CHISELED_POLISHED_BLACKSTONE);
                if (flank) put(x, height, z, Material.POLISHED_BLACKSTONE_BRICKS);
                clear(x, z, height + 1, STAND + 30);
            }
        for (int x : new int[] {-17, -12, -7, 7, 12, 17}) {
            int z = GALLERY_BACK;
            for (int y = STAND + 2; y <= STAND + 10; y++)
                put(x, y, z, y == STAND + 10 ? Material.CHISELED_POLISHED_BLACKSTONE : Material.POLISHED_BASALT);
            put(x, STAND + 11, z, Material.SOUL_LANTERN);
        }
        for (int x : new int[] {-4, 4}) put(x, STAND + 2, GALLERY_BACK, Material.SOUL_CAMPFIRE);
    }

    private void pillar(int index) {
        int[] c = seal(index);
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                put(c[0] + dx, FLOOR, c[1] + dz, Material.CHISELED_POLISHED_BLACKSTONE);
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1)
                    for (int y = STAND; y <= STAND + 8; y++) put(c[0] + dx, y, c[1] + dz, pillarBlock(y - STAND, dx, dz));
            }
    }

    private static Material pillarBlock(int height, int dx, int dz) {
        return switch (height) {
            case 0, 1 -> Material.POLISHED_BLACKSTONE_BRICKS;
            case 5 -> Material.CHISELED_POLISHED_BLACKSTONE;
            case 8 -> (Math.abs(dx) == 1 && Math.abs(dz) == 1) || (dx == 0 && dz == 0) ? Material.SOUL_LANTERN : Material.CHISELED_POLISHED_BLACKSTONE;
            default -> Material.POLISHED_BASALT;
        };
    }
}

package de.nikey.witherboss.boss;

import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.util.Vector;

import java.util.*;

public record WitherArena(String name, UUID worldId, double x, double y, double z) {
    public World world() {
        return Bukkit.getWorld(worldId);
    }

    public Location center() {
        return new Location(
                Objects.requireNonNull(world(), "The arena world is not loaded."), x, y, z);
    }

    public Location waitPoint() {
        return center().add(0, 2, -43);
    }

    public Location entry() {
        return center().add(0, 1, -27);
    }

    public boolean waiting(Location l) {
        return near(l)
                && l.getZ() < z - 36
                && Math.abs(l.getX() - x) <= 18
                && l.getZ() >= z - 48
                && l.getY() >= y - 2
                && l.getY() <= y + 8;
    }

    public static double[] sealOffset(int index) {
        double a = Math.PI / 2 + index * Math.PI * 2 / 3;
        return new double[] {Math.cos(a) * 20, Math.sin(a) * 20};
    }

    public static double[] soulOffset(int index) {
        return new double[] {-9 + (index % 8) * 3, -28 + (index / 8) * 2};
    }

    public Location seal(int index) {
        double[] offset = sealOffset(index);
        return center().add(offset[0], 0, offset[1]);
    }

    public Location soul(int index) {
        double[] offset = soulOffset(index);
        return center().add(offset[0], 0, offset[1]);
    }

    public boolean leashed(Location l) {
        return l != null
                && l.getWorld() != null
                && l.getWorld().getUID().equals(worldId)
                && l.getY() >= y - 4
                && l.getY() <= y + 24
                && horizontal(l) <= 38.5 * 38.5;
    }

    public boolean contains(Location l) {
        return l != null
                && l.getWorld() != null
                && l.getWorld().getUID().equals(worldId)
                && l.getY() >= y - 2
                && l.getY() <= y + 20
                && horizontal(l) <= 36 * 36;
    }

    public boolean near(Location l) {
        return l != null
                && l.getWorld() != null
                && l.getWorld().getUID().equals(worldId)
                && horizontal(l) <= 78 * 78
                && Math.abs(l.getY() - y) < 35;
    }

    public double horizontal(Location l) {
        return Math.pow(l.getX() - x, 2) + Math.pow(l.getZ() - z, 2);
    }

    public boolean covered(Location player, Location source) {
        Vector from = source.toVector().setY(y + 1),
                delta = player.toVector().setY(y + 1).subtract(from);
        for (int i = 0; i < 3; i++) {
            Location p = seal(i);
            if (delta.lengthSquared() < .01) continue;
            var box =
                    new org.bukkit.util.BoundingBox(
                            p.getBlockX() - 1,
                            y,
                            p.getBlockZ() - 1,
                            p.getBlockX() + 2,
                            y + 9,
                            p.getBlockZ() + 2);
            if (box.rayTrace(from, delta.clone().normalize(), delta.length()) != null) return true;
        }
        return false;
    }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (world() == null) return List.of("The arena world is not loaded.");
        if (y < world().getMinHeight() + 3 || y + 21 >= world().getMaxHeight())
            return List.of("The arena is outside the world height.");
        for (int i = 0; i < 3; i++) {
            Location seal = seal(i);
            for (int px = -1; px <= 1; px++)
                for (int pz = -1; pz <= 1; pz++)
                    for (int py = 0; py < 8; py++)
                        if (!seal.clone().add(px, py, pz).getBlock().getType().isSolid())
                            return List.of("Cover pillar " + (i + 1) + " is incomplete.");
        }
        for (int dx = -36; dx <= 36; dx++)
            for (int dz = -36; dz <= 36; dz++)
                if (dx * dx + dz * dz <= 1296) {
                    if (!center().add(dx, -1, dz).getBlock().getType().isSolid()) {
                        errors.add("Missing arena floor at " + dx + "/" + dz);
                        return errors;
                    }
                    boolean pillar = false;
                    for (int i = 0; i < 3; i++) {
                        Location s = seal(i);
                        if (Math.abs(s.getBlockX() - (int) Math.floor(x + dx)) <= 2
                                && Math.abs(s.getBlockZ() - (int) Math.floor(z + dz)) <= 2)
                            pillar = true;
                    }
                    if (pillar || Math.hypot(dx, dz) < 3) continue;
                    for (int dy = 0; dy < 18; dy++)
                        if (!center().add(dx, dy, dz).getBlock().isPassable()) {
                            errors.add(
                                    "Walking space blocked at " + dx + "/" + dy + "/" + dz);
                            return errors;
                        }
                }
        if (!waitPoint().clone().add(0, -1, 0).getBlock().getType().isSolid())
            errors.add("The waiting area has no safe floor.");
        return errors;
    }

    public void write(ConfigurationSection s) {
        s.set("world", worldId.toString());
        s.set("x", x);
        s.set("y", y);
        s.set("z", z);
    }

    public static WitherArena read(String name, ConfigurationSection s) {
        if (!name.matches("[a-z0-9_-]{1,32}"))
            throw new IllegalArgumentException("Invalid arena name.");
        double x = s.getDouble("x"), y = s.getDouble("y"), z = s.getDouble("z");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("Invalid arena coordinates.");
        return new WitherArena(name, UUID.fromString(s.getString("world")), x, y, z);
    }
}

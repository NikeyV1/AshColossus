package de.nikey.witherboss.boss;

import org.bukkit.*;
import org.bukkit.block.*;

import java.time.*;
import java.util.*;

public final class WitherSummoning {
    private WitherSummoning() {}

    public record Window(boolean enabled, LocalTime from, LocalTime to, ZoneId zone) {
        public static final Window OFF = new Window(false, LocalTime.MIN, LocalTime.MAX, ZoneOffset.UTC);

        public boolean open(ZonedDateTime now) {
            if (!enabled) return true;
            LocalTime time = now.withZoneSameInstant(zone).toLocalTime();
            if (from.equals(to)) return true;
            if (from.isBefore(to)) return !time.isBefore(from) && time.isBefore(to);
            return !time.isBefore(from) || time.isBefore(to);
        }

        public String describe() {
            return from + " to " + to + " (" + zone + ")";
        }
    }

    public static LocalTime parseTime(String text, LocalTime fallback) {
        try {
            return LocalTime.parse(text.length() == 4 ? "0" + text : text);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static String blocked(boolean busy, boolean testing, int online, int minimumOnline, ZonedDateTime now, Window window, long cooldownSeconds) {
        if (busy) return "A boss event is already in progress.";
        if (testing) return null;
        if (online < minimumOnline) return "At least " + minimumOnline + " players must be online to summon the Ash Colossus.";
        if (!window.open(now)) return "The Ash Colossus can only be summoned between " + window.describe() + ".";
        if (cooldownSeconds > 0) return "The Ash Colossus is still recovering. Try again in " + (cooldownSeconds / 60 + 1) + " min.";
        return null;
    }

    public record Structure(List<BlockState> blocks, Block placed) {
        public int sand() {
            return (int) blocks.stream().filter(b -> b.getType() == Material.SOUL_SAND).count();
        }

        public int soil() {
            return 4 - sand();
        }

        public boolean unchanged(org.bukkit.block.data.BlockData replaced) {
            return blocks.stream()
                    .allMatch(b -> b.getBlock().getBlockData().equals(b.getBlock().equals(placed) ? replaced : b.getBlockData()));
        }

        public void consume() {
            for (BlockState block : blocks)
                if (!block.getBlock().equals(placed)) block.getBlock().setType(Material.AIR, false);
        }
    }

    public static Structure find(Block placed) {
        if (!skull(placed.getType())) return null;
        for (BlockFace axis : List.of(BlockFace.EAST, BlockFace.SOUTH))
            for (int offset = -1; offset <= 1; offset++) {
                Block middle = placed.getRelative(axis, offset);
                List<BlockState> blocks = new ArrayList<>();
                boolean valid = true;
                for (int side = -1; side <= 1; side++) {
                    Block top = middle.getRelative(axis, side), arm = top.getRelative(BlockFace.DOWN);
                    valid &= skull(top.getType()) && soul(arm.getType());
                    blocks.add(top.getState());
                    blocks.add(arm.getState());
                }
                Block base = middle.getRelative(BlockFace.DOWN, 2);
                valid &= soul(base.getType()) && base.getRelative(axis, -1).getType().isAir() && base.getRelative(axis, 1).getType().isAir();
                blocks.add(base.getState());
                if (valid) return new Structure(List.copyOf(blocks), placed);
            }
        return null;
    }

    private static boolean skull(Material type) {
        return type == Material.WITHER_SKELETON_SKULL || type == Material.WITHER_SKELETON_WALL_SKULL;
    }

    private static boolean soul(Material type) {
        return type == Material.SOUL_SAND || type == Material.SOUL_SOIL;
    }
}

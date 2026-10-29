package de.nikey.witherboss.boss;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.generator.ChunkGenerator;

import java.util.*;

public final class WitherArenaBuilder {
    private WitherArenaBuilder() {}

    public static WitherArena build(String name) {
        if (!name.matches("[a-z0-9_-]{1,32}"))
            throw new IllegalArgumentException("Name: a-z, 0-9, _ or -; at most 32 characters.");
        String worldName = "wither_boss_" + name;
        World loaded = Bukkit.getWorld(NamespacedKey.minecraft(worldName));
        if (loaded != null || existing(name)) {
            WitherArena preset = preset(name, loaded == null ? load(name) : loaded);
            if (preset == null)
                throw new IllegalArgumentException(
                        "The existing world is not a recognised preset arena: " + worldName);
            return preset;
        }
        World world = load(name);
        return construct(name, world);
    }

    public static boolean existing(String name) {
        return name.matches("[a-z0-9_-]{1,32}")
                && java.nio.file.Files.isDirectory(
                        Bukkit.getServer()
                                .getLevelDirectory()
                                .resolve("dimensions/minecraft/wither_boss_" + name));
    }

    public static WitherArena preset(String name, World world) {
        if (world == null
                || !world.getKey().equals(NamespacedKey.minecraft("wither_boss_" + name)))
            return null;
        WitherArena arena = new WitherArena(name, world.getUID(), .5, 80, .5);
        if (current(world)) return arena;
        for (int i = 0; i < 3; i++)
            if (arena.seal(i).getBlock().getType() == Material.POLISHED_BASALT) return arena;
        return null;
    }

    public static boolean current(World world) {
        return world.getBlockAt(0, WitherArenaDesign.BASE, 0).getType() == WitherArenaDesign.MARKER;
    }

    public static void renovate(World world) {
        for (int x = -WitherArenaDesign.OUTER - 1; x <= WitherArenaDesign.OUTER + 1; x++)
            for (int z = -WitherArenaDesign.OUTER - 1; z <= WitherArenaDesign.OUTER + 1; z++)
                if (Math.hypot(x, z) <= WitherArenaDesign.OUTER + 1)
                    for (int y = WitherArenaDesign.STAND; y <= WitherArenaDesign.STAND + 34; y++)
                        if (!world.getBlockAt(x, y, z).getType().isAir())
                            world.getBlockAt(x, y, z).setType(Material.AIR, false);
        apply(world, WitherArenaDesign.create());
        world.setSpawnLocation(
                new Location(world, .5, WitherArenaDesign.STAND + 2, .5 - 43));
        world.save();
    }

    static void apply(World world, WitherArenaDesign design) {
        long marker = WitherArenaDesign.key(0, WitherArenaDesign.BASE, 0);
        for (var entry : design.cells().entrySet()) {
            long key = entry.getKey();
            if (key == marker) continue;
            WitherArenaDesign.Cell cell = entry.getValue();
            Block block =
                    world.getBlockAt(
                            WitherArenaDesign.keyX(key),
                            WitherArenaDesign.keyY(key),
                            WitherArenaDesign.keyZ(key));
            if (cell.state() == null) {
                if (block.getType() != cell.material()) block.setType(cell.material(), false);
            } else
                block.setBlockData(
                        Bukkit.createBlockData(cell.material(), cell.state()), false);
        }
        world.getBlockAt(0, WitherArenaDesign.BASE, 0).setType(WitherArenaDesign.MARKER, false);
    }

    public static World load(String name) {
        if (!name.matches("[a-z0-9_-]{1,32}"))
            throw new IllegalArgumentException("Invalid arena name.");
        World world =
                WorldCreator.ofKey(NamespacedKey.minecraft("wither_boss_" + name))
                        .generator(
                                new ChunkGenerator() {
                                    @Override
                                    public boolean shouldGenerateNoise() {
                                        return false;
                                    }

                                    @Override
                                    public boolean shouldGenerateSurface() {
                                        return false;
                                    }

                                    @Override
                                    public boolean shouldGenerateCaves() {
                                        return false;
                                    }

                                    @Override
                                    public boolean shouldGenerateDecorations() {
                                        return false;
                                    }

                                    @Override
                                    public boolean shouldGenerateMobs() {
                                        return false;
                                    }

                                    @Override
                                    public boolean shouldGenerateStructures() {
                                        return false;
                                    }
                                })
                        .createWorld();
        if (world == null)
            throw new IllegalStateException("The arena world could not be created.");
        return world;
    }

    private static WitherArena construct(String name, World world) {
        WitherArena arena = new WitherArena(name, world.getUID(), .5, 80, .5);
        apply(world, WitherArenaDesign.create());
        world.setSpawnLocation(arena.waitPoint());
        world.save();
        return arena;
    }
}

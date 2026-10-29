package de.nikey.witherboss.loot;

import de.nikey.witherboss.util.AtomicFiles;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class LootTable {
    public record Participant(UUID id, double damage) {}

    public record Reward(UUID player, LootEntry.Type type, ItemStack item, String command, int xp) {}

    private final Path file;
    private final List<LootEntry> entries = new ArrayList<>();
    private boolean enabled = true;
    private int nextId = 1;

    public LootTable(Path file) {
        this.file = file;
    }

    public static LootTable load(Path file) throws IOException {
        LootTable table = new LootTable(file);
        if (!Files.exists(file)) {
            table.loadDefaults();
            table.save();
            return table;
        }
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(file.toFile());
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            Files.copy(file, file.resolveSibling("loot.yml.broken-" + System.currentTimeMillis()));
            throw new IOException("loot.yml is not valid YAML; a copy was saved as loot.yml.broken-*: " + e.getMessage(), e);
        }
        table.enabled = y.getBoolean("enabled", true);
        ConfigurationSection list = y.getConfigurationSection("entries");
        if (list != null)
            for (String key : list.getKeys(false)) {
                ConfigurationSection s = list.getConfigurationSection(key);
                if (s == null) continue;
                LootEntry entry = read(Integer.parseInt(key), s);
                if (entry != null) {
                    table.entries.add(entry);
                    table.nextId = Math.max(table.nextId, entry.id + 1);
                }
            }
        return table;
    }

    private static LootEntry read(int id, ConfigurationSection s) {
        LootEntry.Type type;
        try {
            type = LootEntry.Type.valueOf(s.getString("type", "ITEM").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        LootEntry entry = new LootEntry(id, type);
        entry.chance = Math.clamp(s.getDouble("chance", 100), 0, 100);
        entry.min = Math.max(0, s.getInt("min", 1));
        entry.max = Math.max(entry.min, s.getInt("max", entry.min));
        entry.scope = LootEntry.Scope.parse(s.getString("scope", "EACH_PLAYER"));
        entry.enabled = s.getBoolean("enabled", true);
        entry.command = s.getString("command", "");
        if (type == LootEntry.Type.ITEM) {
            String encoded = s.getString("item", "");
            try {
                entry.item = ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
            } catch (RuntimeException e) {
                return null;
            }
        }
        return entry;
    }

    public void loadDefaults() {
        entries.clear();
        nextId = 1;
        enabled = true;
        add(LootEntry.item(0, new ItemStack(Material.NETHER_STAR), 100, 1, 1, LootEntry.Scope.EACH_PLAYER));
        add(LootEntry.item(0, new ItemStack(Material.NETHERITE_SCRAP), 100, 2, 4, LootEntry.Scope.EACH_PLAYER));
        add(LootEntry.item(0, new ItemStack(Material.DIAMOND), 100, 4, 8, LootEntry.Scope.EACH_PLAYER));
        add(LootEntry.item(0, new ItemStack(Material.ENCHANTED_GOLDEN_APPLE), 30, 1, 1, LootEntry.Scope.EACH_PLAYER));
        add(LootEntry.item(0, new ItemStack(Material.TOTEM_OF_UNDYING), 20, 1, 1, LootEntry.Scope.EACH_PLAYER));
        add(LootEntry.item(0, new ItemStack(Material.BEACON), 15, 1, 1, LootEntry.Scope.RANDOM_PLAYER));
        add(LootEntry.xp(0, 300, 500, 100, LootEntry.Scope.EACH_PLAYER));
        add(LootEntry.trophy(0));
    }

    public LootEntry add(LootEntry template) {
        LootEntry entry = new LootEntry(nextId++, template.type);
        entry.item = template.item;
        entry.command = template.command;
        entry.chance = template.chance;
        entry.min = template.min;
        entry.max = template.max;
        entry.scope = template.scope;
        entry.enabled = template.enabled;
        entries.add(entry);
        return entry;
    }

    public boolean remove(int id) {
        return entries.removeIf(e -> e.id == id);
    }

    public LootEntry get(int id) {
        for (LootEntry e : entries) if (e.id == id) return e;
        return null;
    }

    public List<LootEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public boolean enabled() {
        return enabled;
    }

    public void enabled(boolean value) {
        enabled = value;
    }

    public synchronized void save() throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of(
                "Ash Colossus loot table. Edit in-game with /witherboss loot (GUI) or the sub-commands.",
                "chance: 0-100 (percent). scope: EACH_PLAYER, RANDOM_PLAYER or TOP_DAMAGE.",
                "type COMMAND runs the command from the console, %player% is replaced by the winner's name.",
                "Item entries store the complete item (enchantments, name, lore, custom data) as base64."));
        y.set("enabled", enabled);
        for (LootEntry e : entries) {
            String p = "entries." + e.id;
            y.set(p + ".type", e.type.name());
            y.set(p + ".enabled", e.enabled);
            y.set(p + ".chance", e.chance);
            y.set(p + ".scope", e.scope.name());
            y.set(p + ".min", e.min);
            y.set(p + ".max", e.max);
            if (e.type == LootEntry.Type.ITEM) {
                y.set(p + ".label", e.itemName());
                y.set(p + ".item", Base64.getEncoder().encodeToString(e.item.serializeAsBytes()));
            }
            if (e.type == LootEntry.Type.COMMAND) y.set(p + ".command", e.command);
        }
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling("loot.yml.tmp");
        Files.writeString(temp, y.saveToString(), StandardCharsets.UTF_8);
        AtomicFiles.replace(temp, file);
    }

    public List<Reward> roll(List<Participant> eligible, Random random) {
        List<Reward> out = new ArrayList<>();
        if (!enabled || eligible.isEmpty()) return out;
        Participant top = eligible.stream().max(Comparator.comparingDouble(Participant::damage)).orElseThrow();
        for (LootEntry e : entries) {
            if (!e.enabled) continue;
            switch (e.scope) {
                case EACH_PLAYER -> {
                    for (Participant p : eligible) if (hit(e, random)) out.add(reward(e, p.id(), random));
                }
                case RANDOM_PLAYER -> {
                    if (hit(e, random)) out.add(reward(e, eligible.get(random.nextInt(eligible.size())).id(), random));
                }
                case TOP_DAMAGE -> {
                    if (hit(e, random)) out.add(reward(e, top.id(), random));
                }
            }
        }
        return out;
    }

    private static boolean hit(LootEntry e, Random random) {
        return e.chance >= 100 || random.nextDouble() * 100 < e.chance;
    }

    private static Reward reward(LootEntry e, UUID player, Random random) {
        int amount = e.max > e.min ? e.min + random.nextInt(e.max - e.min + 1) : e.min;
        return switch (e.type) {
            case ITEM -> new Reward(player, e.type, e.item.asQuantity(Math.max(1, amount)), null, 0);
            case COMMAND -> new Reward(player, e.type, null, e.command, 0);
            case XP -> new Reward(player, e.type, null, null, Math.max(0, amount));
            case TROPHY -> new Reward(player, e.type, null, null, 0);
        };
    }
}

package de.nikey.witherboss.boss;

import de.nikey.witherboss.WitherBossPlugin;
import de.nikey.witherboss.loot.LootEntry;
import de.nikey.witherboss.loot.LootTable;
import de.nikey.witherboss.ui.Msg;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public final class WitherRewards {
    public static final String NAMESPACE = "wither_boss";

    private final WitherBossPlugin plugin;
    private final WitherStore store;
    private final NamespacedKey claimKey, ownerKey;

    public WitherRewards(WitherBossPlugin plugin, WitherStore store) {
        this.plugin = plugin;
        this.store = store;
        claimKey = new NamespacedKey(plugin, "claim");
        ownerKey = new NamespacedKey(plugin, "owner");
    }

    public static ItemStack model(String model) {
        return model(new NamespacedKey(NAMESPACE, model));
    }

    public static ItemStack model(NamespacedKey model) {
        ItemStack item = new ItemStack(Material.PAPER);
        var meta = item.getItemMeta();
        meta.setItemModel(model);
        item.setItemMeta(meta);
        return item;
    }

    public void queue(UUID attempt, List<LootTable.Reward> rewards) {
        int n = 0;
        for (LootTable.Reward r : rewards) {
            switch (r.type()) {
                case ITEM -> {
                    ItemStack rest = r.item().clone();
                    int max = Math.max(1, rest.getMaxStackSize());
                    while (rest.getAmount() > 0) {
                        int part = Math.min(max, rest.getAmount());
                        store.add(attempt + "_loot_" + n++, r.player(), "item:" + Base64.getEncoder().encodeToString(rest.asQuantity(part).serializeAsBytes()));
                        rest.setAmount(rest.getAmount() - part);
                    }
                }
                case COMMAND -> store.add(attempt + "_loot_" + n++, r.player(), "cmd:" + r.command());
                case XP -> {
                    if (r.xp() > 0) store.add(attempt + "_loot_" + n++, r.player(), "xp:" + r.xp());
                }
                case TROPHY -> store.add(attempt + "_loot_" + n++, r.player(), "trophy");
            }
        }
        store.save(true);
    }

    public ItemStack item(WitherStore.Claim c) {
        ItemStack item;
        if (c.kind.startsWith("item:")) {
            item = ItemStack.deserializeBytes(Base64.getDecoder().decode(c.kind.substring(5)));
            return item;
        } else if (c.kind.matches("(skulls|sand|soil):[1-4]")) {
            String[] refund = c.kind.split(":");
            item = new ItemStack(
                    switch (refund[0]) {
                        case "skulls" -> Material.WITHER_SKELETON_SKULL;
                        case "sand" -> Material.SOUL_SAND;
                        default -> Material.SOUL_SOIL;
                    },
                    Integer.parseInt(refund[1]));
            return item;
        } else if (c.kind.equals("sigil")) {
            return plugin.sigil().create();
        }
        item = model("trophy");
        var meta = item.getItemMeta();
        meta.displayName(Msg.title("Ash Colossus Trophy"));
        meta.lore(List.of(Msg.text("Bound to " + ownerName(c.owner) + " · right-click for a Forge Echo").color(Msg.MUTED)));
        meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, c.owner.toString());
        meta.getPersistentDataContainer().set(claimKey, PersistentDataType.STRING, c.id);
        item.setItemMeta(meta);
        return item;
    }

    private static String ownerName(UUID id) {
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name == null ? "its owner" : name;
    }

    private static boolean needsSpace(WitherStore.Claim c) {
        return c.kind.startsWith("item:") || c.kind.equals("trophy") || c.kind.equals("sigil") || c.kind.matches("(skulls|sand|soil):[1-4]");
    }

    static boolean fits(PlayerInventory inv, ItemStack stack) {
        int room = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack existing = inv.getItem(slot);
            if (existing == null || existing.getType().isAir()) room += stack.getMaxStackSize();
            else if (existing.isSimilar(stack)) room += Math.max(0, existing.getMaxStackSize() - existing.getAmount());
        }
        return room >= stack.getAmount();
    }

    public List<String> deliver(Player player) {
        List<String> received = new ArrayList<>();
        for (WitherStore.Claim c : store.pending(player.getUniqueId())) {
            try {
                if (needsSpace(c)) {
                    ItemStack stack = item(c);
                    if (!fits(player.getInventory(), stack)) continue;
                    store.delivery(c, WitherStore.Delivery.DELIVERING);
                    player.getInventory().addItem(stack);
                    player.saveData();
                    received.add(stack.getAmount() + "x " + LootEntry.nameOf(stack));
                } else if (c.kind.startsWith("xp:")) {
                    store.delivery(c, WitherStore.Delivery.DELIVERING);
                    int xp = Integer.parseInt(c.kind.substring(3));
                    player.giveExp(xp);
                    received.add(xp + " XP");
                } else if (c.kind.startsWith("cmd:")) {
                    store.delivery(c, WitherStore.Delivery.DELIVERING);
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.kind.substring(4).replace("%player%", player.getName()));
                } else {
                    continue;
                }
                store.delivery(c, WitherStore.Delivery.DELIVERED);
            } catch (RuntimeException failure) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not deliver claim " + c.id + ": " + failure.getMessage());
            }
        }
        return received;
    }

    public void claim(Player player) {
        List<String> received = deliver(player);
        int left = store.pending(player.getUniqueId()).size();
        if (!received.isEmpty()) Msg.send(player, "Received: " + String.join(", ", received) + ".");
        if (left > 0) Msg.send(player, left + " reward(s) are still waiting. Free some inventory space and run /witherboss claim again.");
        else if (received.isEmpty()) Msg.send(player, "You have no rewards waiting.");
    }

    public boolean bound(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(ownerKey);
    }

    public boolean owned(ItemStack item, Player player) {
        return bound(item)
                && player.getUniqueId().toString().equals(item.getItemMeta().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING));
    }

    public boolean trophy(ItemStack item, Player player) {
        if (!bound(item)) return false;
        String id = item.getItemMeta().getPersistentDataContainer().get(claimKey, PersistentDataType.STRING);
        WitherStore.Claim c = store.claims.get(id);
        return c != null && c.kind.equals("trophy") && c.owner.equals(player.getUniqueId()) && c.state == WitherStore.Delivery.DELIVERED;
    }
}

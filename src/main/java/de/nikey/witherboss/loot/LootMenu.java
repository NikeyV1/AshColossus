package de.nikey.witherboss.loot;

import de.nikey.witherboss.ui.Msg;

import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class LootMenu implements Listener {
    private static final int PAGE = 45;

    private abstract static class Holder implements InventoryHolder {
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class ListHolder extends Holder {
        int page;
    }

    private static final class EditHolder extends Holder {
        final int id;
        final int page;

        EditHolder(int id, int page) {
            this.id = id;
            this.page = page;
        }
    }

    private LootTable table;

    public LootMenu(LootTable table) {
        this.table = table;
    }

    public void replace(LootTable table) {
        this.table = table;
    }

    public void open(Player player) {
        openList(player, 0);
    }

    private void openList(Player player, int page) {
        int pages = Math.max(1, (table.entries().size() + PAGE - 1) / PAGE);
        int current = Math.clamp(page, 0, pages - 1);
        ListHolder holder = new ListHolder();
        holder.page = current;
        Inventory inv = Bukkit.createInventory(holder, 54, Msg.title("Boss Loot · page " + (current + 1) + "/" + pages));
        holder.inventory = inv;
        List<LootEntry> entries = table.entries();
        for (int i = 0; i < PAGE; i++) {
            int index = current * PAGE + i;
            if (index >= entries.size()) break;
            inv.setItem(i, icon(entries.get(index)));
        }
        ItemStack filler = Msg.item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);
        inv.setItem(45, Msg.item(Material.ARROW, "Previous page"));
        inv.setItem(47, Msg.item(Material.HOPPER, "Add an item", "Click an empty slot above while holding an item,", "or shift-click an item in your inventory.", "Your item is copied, not taken."));
        inv.setItem(48, Msg.item(Material.COMMAND_BLOCK, "Add a command reward", "/witherboss loot addcommand <chance> <command>", "Use %player% for the winner's name."));
        inv.setItem(49, Msg.item(table.enabled() ? Material.LIME_DYE : Material.GRAY_DYE, "Loot " + (table.enabled() ? "enabled" : "disabled"), "Click to toggle the whole table."));
        inv.setItem(50, Msg.item(Material.EXPERIENCE_BOTTLE, "Add XP reward", "/witherboss loot addxp <chance> <min> [max]"));
        inv.setItem(53, Msg.item(Material.ARROW, "Next page"));
        player.openInventory(inv);
    }

    private ItemStack icon(LootEntry e) {
        ItemStack stack = switch (e.type) {
            case ITEM -> e.item.clone();
            case COMMAND -> new ItemStack(Material.COMMAND_BLOCK);
            case XP -> new ItemStack(Material.EXPERIENCE_BOTTLE);
            case TROPHY -> new ItemStack(Material.PAPER);
        };
        stack.setAmount(1);
        List<Component> lore = new ArrayList<>();
        var existing = stack.lore();
        if (existing != null && e.type == LootEntry.Type.ITEM) lore.addAll(existing);
        lore.add(Msg.text(" "));
        lore.add(Msg.text("#" + e.id + " · " + e.label()));
        lore.add(Msg.text("Chance: " + e.chanceText() + " · " + e.scope.description));
        lore.add(Msg.text(e.enabled ? "Enabled" : "Disabled").color(e.enabled ? Msg.GOLD : Msg.MUTED));
        lore.add(Msg.text("Click to edit").color(Msg.MUTED));
        stack.editMeta(meta -> {
            meta.lore(lore);
            if (e.type != LootEntry.Type.ITEM) meta.displayName(Msg.title(e.label()));
        });
        return stack;
    }

    private void openEdit(Player player, int id, int page) {
        LootEntry e = table.get(id);
        if (e == null) {
            openList(player, page);
            return;
        }
        EditHolder holder = new EditHolder(id, page);
        Inventory inv = Bukkit.createInventory(holder, 27, Msg.title("Edit loot #" + id));
        holder.inventory = inv;
        render(inv, e);
        player.openInventory(inv);
    }

    private void render(Inventory inv, LootEntry e) {
        inv.clear();
        ItemStack filler = Msg.item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);
        inv.setItem(4, icon(e));
        inv.setItem(10, Msg.item(Material.RED_CONCRETE, "Chance -10%", "Right-click: -1%"));
        inv.setItem(11, Msg.item(Material.ORANGE_CONCRETE, "Chance -1%", "Right-click: -0.1%"));
        inv.setItem(12, Msg.item(Material.CLOCK, "Chance: " + e.chanceText(), "Chance that this reward drops."));
        inv.setItem(13, Msg.item(Material.LIME_CONCRETE, "Chance +1%", "Right-click: +0.1%"));
        inv.setItem(14, Msg.item(Material.GREEN_CONCRETE, "Chance +10%", "Right-click: +1%"));
        inv.setItem(16, Msg.item(Material.PLAYER_HEAD, "Who gets it: " + e.scope.name(), e.scope.description, "Click to switch."));
        if (e.hasAmount()) {
            String unit = e.type == LootEntry.Type.XP ? "XP" : "amount";
            int step = e.type == LootEntry.Type.XP ? 50 : 1;
            inv.setItem(19, Msg.item(Material.RED_STAINED_GLASS_PANE, "Min " + unit + " -" + step, "Shift-click: -" + step * 8));
            inv.setItem(20, Msg.item(Material.PAPER, "Min " + unit + ": " + e.min));
            inv.setItem(21, Msg.item(Material.LIME_STAINED_GLASS_PANE, "Min " + unit + " +" + step, "Shift-click: +" + step * 8));
            inv.setItem(23, Msg.item(Material.RED_STAINED_GLASS_PANE, "Max " + unit + " -" + step, "Shift-click: -" + step * 8));
            inv.setItem(24, Msg.item(Material.PAPER, "Max " + unit + ": " + e.max));
            inv.setItem(25, Msg.item(Material.LIME_STAINED_GLASS_PANE, "Max " + unit + " +" + step, "Shift-click: +" + step * 8));
        }
        inv.setItem(18, Msg.item(Material.ARROW, "Back"));
        inv.setItem(22, Msg.item(e.enabled ? Material.LIME_DYE : Material.GRAY_DYE, e.enabled ? "Enabled" : "Disabled", "Click to toggle."));
        inv.setItem(26, Msg.item(Material.BARRIER, "Delete this reward", "Shift-click to confirm."));
    }

    private void persist(Player player) {
        try {
            table.save();
        } catch (IOException ex) {
            Msg.send(player, "Could not save loot.yml: " + ex.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void click(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder) || !(event.getWhoClicked() instanceof Player player)) return;
        event.setCancelled(true);
        if (!player.hasPermission("witherboss.admin")) {
            player.closeInventory();
            return;
        }
        boolean top = event.getClickedInventory() == event.getView().getTopInventory();
        if (holder instanceof ListHolder list) {
            handleList(event, player, list, top);
        } else if (holder instanceof EditHolder edit && top) {
            handleEdit(event, player, edit);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void drag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    private void handleList(InventoryClickEvent event, Player player, ListHolder list, boolean top) {
        if (!top) {
            ItemStack clicked = event.getCurrentItem();
            if (event.isShiftClick() && clicked != null && !clicked.getType().isAir()) addItem(player, list, clicked);
            return;
        }
        int slot = event.getSlot();
        if (slot < PAGE) {
            int index = list.page * PAGE + slot;
            ItemStack cursor = event.getCursor();
            if (index < table.entries().size()) {
                openEdit(player, table.entries().get(index).id, list.page);
            } else if (!cursor.getType().isAir()) {
                addItem(player, list, cursor);
            }
            return;
        }
        switch (slot) {
            case 45 -> openList(player, list.page - 1);
            case 53 -> openList(player, list.page + 1);
            case 49 -> {
                table.enabled(!table.enabled());
                persist(player);
                openList(player, list.page);
            }
            default -> {}
        }
    }

    private void addItem(Player player, ListHolder list, ItemStack stack) {
        int amount = Math.max(1, stack.getAmount());
        LootEntry entry = table.add(LootEntry.item(0, stack, 100, amount, amount, LootEntry.Scope.EACH_PLAYER));
        persist(player);
        player.playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        openEdit(player, entry.id, list.page);
    }

    private void handleEdit(InventoryClickEvent event, Player player, EditHolder edit) {
        LootEntry e = table.get(edit.id);
        if (e == null) {
            openList(player, edit.page);
            return;
        }
        boolean fine = event.isRightClick();
        boolean shift = event.isShiftClick();
        switch (event.getSlot()) {
            case 10 -> e.chance = Math.clamp(e.chance - (fine ? 1 : 10), 0, 100);
            case 11 -> e.chance = Math.clamp(e.chance - (fine ? 0.1 : 1), 0, 100);
            case 13 -> e.chance = Math.clamp(e.chance + (fine ? 0.1 : 1), 0, 100);
            case 14 -> e.chance = Math.clamp(e.chance + (fine ? 1 : 10), 0, 100);
            case 16 -> e.scope = e.scope.next();
            case 22 -> e.enabled = !e.enabled;
            case 19, 21, 23, 25 -> {
                if (!e.hasAmount()) return;
                int step = (e.type == LootEntry.Type.XP ? 50 : 1) * (shift ? 8 : 1);
                int limit = e.type == LootEntry.Type.XP ? 100_000 : 99;
                int delta = (event.getSlot() == 19 || event.getSlot() == 23) ? -step : step;
                if (event.getSlot() < 22) e.min = Math.clamp(e.min + delta, e.type == LootEntry.Type.XP ? 0 : 1, limit);
                else e.max = Math.clamp(e.max + delta, e.type == LootEntry.Type.XP ? 0 : 1, limit);
                if (e.max < e.min) {
                    if (event.getSlot() < 22) e.max = e.min;
                    else e.min = e.max;
                }
            }
            case 18 -> {
                openList(player, edit.page);
                return;
            }
            case 26 -> {
                if (!shift) {
                    Msg.send(player, "Shift-click the barrier to delete this reward.");
                    return;
                }
                table.remove(e.id);
                persist(player);
                openList(player, edit.page);
                return;
            }
            default -> {
                return;
            }
        }
        e.chance = Math.round(e.chance * 10) / 10.0;
        persist(player);
        render(edit.inventory, e);
    }
}

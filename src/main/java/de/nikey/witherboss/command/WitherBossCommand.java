package de.nikey.witherboss.command;

import de.nikey.witherboss.WitherBossPlugin;
import de.nikey.witherboss.config.Tuning;
import de.nikey.witherboss.loot.LootEntry;
import de.nikey.witherboss.loot.LootTable;
import de.nikey.witherboss.ui.Msg;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

public final class WitherBossCommand implements CommandExecutor, TabCompleter {
    private final WitherBossPlugin plugin;

    public WitherBossCommand(WitherBossPlugin plugin) {
        this.plugin = plugin;
    }

    private static final List<String> PLAYER_HELP = List.of(
            "/witherboss info · state of the boss and how to summon it",
            "/witherboss join · /accept · /leave · take part in a fight",
            "/witherboss claim · collect rewards that did not fit your inventory",
            "/witherboss pack · load the boss resource pack",
            "/witherboss about · who made this");

    private static final List<String> ADMIN_HELP = List.of(
            "/witherboss summon · start the real event (staff, ignores conditions)",
            "/witherboss rehearse [seconds] · private full run without rewards",
            "/witherboss test [arena] [attack] · test fight or a single attack",
            "/witherboss stop · end the preparation or fight",
            "/witherboss loot · open the loot editor (also: list, add, addcommand, addxp, remove, set, preview, reset)",
            "/witherboss give sigil [player] [amount] · hand out Colossus Sigils",
            "/witherboss difficulty [easy|normal|hard|nightmare] · show or set the preset",
            "/witherboss config <get|set> <path> [value] · edit config.yml in-game",
            "/witherboss cooldown reset · allow the next summoning immediately",
            "/witherboss reload · reload config.yml and loot.yml",
            "/witherboss arena <set|build|rebuild|list|remove> [name]");

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            dispatch(sender, args);
        } catch (IllegalArgumentException refused) {
            Msg.send(sender, refused.getMessage() == null ? "Invalid input." : refused.getMessage());
        } catch (Exception failure) {
            Msg.send(sender, "That failed: " + failure.getClass().getSimpleName() + ". Details are in the console.");
            plugin.getLogger().log(Level.SEVERE, "Command failed: /" + label + " " + String.join(" ", args), failure);
        }
        return true;
    }

    private void dispatch(CommandSender sender, String[] args) throws Exception {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            Msg.send(sender, "Ash Colossus commands:");
            PLAYER_HELP.forEach(line -> sender.sendMessage(Msg.text(" " + line)));
            if (sender.hasPermission("witherboss.admin")) ADMIN_HELP.forEach(line -> sender.sendMessage(Msg.text(" " + line).color(Msg.MUTED)));
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission("witherboss.admin");
        switch (sub) {
            case "about" -> sender.sendMessage(plugin.credits().about());
            case "loot" -> {
                require(admin);
                loot(sender, Arrays.copyOfRange(args, 1, args.length));
            }
            case "give" -> {
                require(admin);
                give(sender, args);
            }
            case "reload" -> {
                require(admin);
                plugin.reloadEverything();
                Msg.send(sender, "config.yml and loot.yml reloaded. " + plugin.tuning().describe(5));
            }
            case "config" -> {
                require(admin);
                config(sender, args);
            }
            case "difficulty" -> {
                require(admin);
                difficulty(sender, args);
            }
            case "cooldown" -> {
                require(admin);
                if (args.length != 2 || !args[1].equalsIgnoreCase("reset")) throw new IllegalArgumentException("/witherboss cooldown reset");
                plugin.bosses().store().finished(0);
                Msg.send(sender, "Cooldown cleared.");
            }
            default -> plugin.bosses().command(sender, args);
        }
    }

    private static void require(boolean allowed) {
        if (!allowed) throw new IllegalArgumentException("You do not have permission for that.");
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("sigil")) throw new IllegalArgumentException("/witherboss give sigil [player] [amount]");
        Player target = args.length >= 3 ? plugin.getServer().getPlayerExact(args[2]) : sender instanceof Player p ? p : null;
        if (target == null) throw new IllegalArgumentException("Player not found.");
        int amount = args.length >= 4 ? number(args[3], 1, 64).intValue() : 1;
        ItemStack stack = plugin.sigil().create();
        stack.setAmount(amount);
        target.getInventory().addItem(stack).values().forEach(rest -> target.getWorld().dropItemNaturally(target.getLocation(), rest));
        Msg.send(sender, "Gave " + amount + " Colossus Sigil(s) to " + target.getName() + ".");
    }

    private void difficulty(CommandSender sender, String[] args) {
        if (args.length == 1) {
            Msg.send(sender, plugin.tuning().describe(5));
            Msg.send(sender, "Presets: EASY, NORMAL, HARD, NIGHTMARE. /witherboss difficulty <preset>");
            return;
        }
        Tuning.Preset preset = Tuning.Preset.parse(args[1]);
        if (!preset.name().equalsIgnoreCase(args[1])) throw new IllegalArgumentException("Presets: EASY, NORMAL, HARD, NIGHTMARE.");
        plugin.getConfig().set("difficulty.preset", preset.name());
        plugin.saveConfig();
        Msg.send(sender, "Difficulty set to " + preset.name() + ". " + plugin.tuning().describe(5));
    }

    private void config(CommandSender sender, String[] args) {
        FileConfiguration config = plugin.getConfig();
        if (args.length < 3) throw new IllegalArgumentException("/witherboss config get <path> | set <path> <value>");
        String path = args[2];
        if (!config.contains(path) || config.isConfigurationSection(path)) throw new IllegalArgumentException("Unknown setting: " + path);
        Object current = config.get(path);
        if (args[1].equalsIgnoreCase("get")) {
            Msg.send(sender, path + " = " + current);
            return;
        }
        if (!args[1].equalsIgnoreCase("set") || args.length < 4) throw new IllegalArgumentException("/witherboss config set <path> <value>");
        if (current instanceof List<?>) throw new IllegalArgumentException("Lists must be edited in config.yml.");
        String raw = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        Object value;
        if (current instanceof Boolean) {
            if (!raw.equalsIgnoreCase("true") && !raw.equalsIgnoreCase("false")) throw new IllegalArgumentException("Use true or false.");
            value = Boolean.parseBoolean(raw);
        } else if (current instanceof Integer) value = number(raw, Integer.MIN_VALUE, Integer.MAX_VALUE).intValue();
        else if (current instanceof Number) value = number(raw, -1e9, 1e9);
        else value = raw;
        config.set(path, value);
        plugin.saveConfig();
        Msg.send(sender, path + " = " + value + " (saved). " + plugin.tuning().describe(5));
    }

    private static Double number(String text, double min, double max) {
        double value;
        try {
            value = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text + "' is not a number.");
        }
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("The value must be between " + min + " and " + max + ".");
        return value;
    }

    private void loot(CommandSender sender, String[] args) throws IOException {
        LootTable table = plugin.loot();
        if (args.length == 0) {
            if (!(sender instanceof Player player)) throw new IllegalArgumentException("Open the editor in-game, or use /witherboss loot list.");
            plugin.lootMenu().open(player);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                Msg.send(sender, "Loot table (" + (table.enabled() ? "enabled" : "disabled") + "), " + table.entries().size() + " entries:");
                for (LootEntry e : table.entries()) sender.sendMessage(Msg.text(" " + e.summary()));
            }
            case "add" -> {
                if (!(sender instanceof Player p)) throw new IllegalArgumentException("Hold the item in your hand.");
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType() == Material.AIR) throw new IllegalArgumentException("Hold the item you want to add in your main hand.");
                double chance = args.length >= 2 ? number(args[1], 0, 100) : 100;
                int min = args.length >= 3 ? number(args[2], 1, 99).intValue() : hand.getAmount();
                int max = args.length >= 4 ? number(args[3], min, 99).intValue() : min;
                LootEntry e = table.add(LootEntry.item(0, hand, chance, min, max, LootEntry.Scope.EACH_PLAYER));
                table.save();
                Msg.send(sender, "Added " + e.summary());
            }
            case "addcommand" -> {
                if (args.length < 3) throw new IllegalArgumentException("/witherboss loot addcommand <chance> <command...> (use %player%)");
                double chance = number(args[1], 0, 100);
                String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                if (text.startsWith("/")) text = text.substring(1);
                LootEntry e = table.add(LootEntry.command(0, text, chance, LootEntry.Scope.EACH_PLAYER));
                table.save();
                Msg.send(sender, "Added " + e.summary());
            }
            case "addxp" -> {
                if (args.length < 3) throw new IllegalArgumentException("/witherboss loot addxp <chance> <min> [max]");
                int min = number(args[2], 1, 100_000).intValue();
                int max = args.length >= 4 ? number(args[3], min, 100_000).intValue() : min;
                LootEntry e = table.add(LootEntry.xp(0, min, max, number(args[1], 0, 100), LootEntry.Scope.EACH_PLAYER));
                table.save();
                Msg.send(sender, "Added " + e.summary());
            }
            case "remove" -> {
                if (args.length != 2) throw new IllegalArgumentException("/witherboss loot remove <id>");
                if (!table.remove(number(args[1], 0, 1e9).intValue())) throw new IllegalArgumentException("No entry with that id.");
                table.save();
                Msg.send(sender, "Removed.");
            }
            case "set" -> {
                if (args.length != 4) throw new IllegalArgumentException("/witherboss loot set <id> <chance|min|max|scope|enabled> <value>");
                LootEntry e = table.get(number(args[1], 0, 1e9).intValue());
                if (e == null) throw new IllegalArgumentException("No entry with that id.");
                switch (args[2].toLowerCase(Locale.ROOT)) {
                    case "chance" -> e.chance = number(args[3], 0, 100);
                    case "min" -> {
                        e.min = number(args[3], 0, 100_000).intValue();
                        e.max = Math.max(e.max, e.min);
                    }
                    case "max" -> {
                        e.max = number(args[3], 0, 100_000).intValue();
                        e.min = Math.min(e.min, e.max);
                    }
                    case "scope" -> e.scope = LootEntry.Scope.parse(args[3]);
                    case "enabled" -> e.enabled = Boolean.parseBoolean(args[3]);
                    default -> throw new IllegalArgumentException("Use chance, min, max, scope or enabled.");
                }
                table.save();
                Msg.send(sender, "Updated " + e.summary());
            }
            case "enable", "disable" -> {
                table.enabled(args[0].equalsIgnoreCase("enable"));
                table.save();
                Msg.send(sender, "Loot " + (table.enabled() ? "enabled" : "disabled") + ".");
            }
            case "preview" -> {
                if (!(sender instanceof Player p)) throw new IllegalArgumentException("Run this in-game.");
                int rolls = args.length >= 2 ? number(args[1], 1, 1000).intValue() : 1;
                Map<String, Integer> totals = new TreeMap<>();
                Random random = new Random();
                for (int i = 0; i < rolls; i++)
                    for (LootTable.Reward r : table.roll(List.of(new LootTable.Participant(p.getUniqueId(), 100)), random)) {
                        String key = switch (r.type()) {
                            case ITEM -> LootEntry.nameOf(r.item());
                            case COMMAND -> "/" + r.command();
                            case XP -> "XP";
                            case TROPHY -> "Trophy";
                        };
                        totals.merge(key, r.type() == LootEntry.Type.ITEM ? r.item().getAmount() : r.type() == LootEntry.Type.XP ? r.xp() : 1, Integer::sum);
                    }
                Msg.send(sender, "Simulated " + rolls + " victory roll(s) for one player:");
                totals.forEach((k, v) -> sender.sendMessage(Msg.text(" " + v + "x " + k)));
            }
            case "reset" -> {
                if (args.length != 2 || !args[1].equalsIgnoreCase("confirm")) throw new IllegalArgumentException("This replaces your whole table with the defaults. Run /witherboss loot reset confirm");
                table.loadDefaults();
                table.save();
                Msg.send(sender, "Loot table reset to the defaults.");
            }
            case "reload" -> {
                plugin.reloadEverything();
                Msg.send(sender, "Loot reloaded.");
            }
            default -> throw new IllegalArgumentException("/witherboss loot [list|add|addcommand|addxp|remove|set|enable|disable|preview|reset]");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission("witherboss.admin");
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        String first = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (admin && args.length == 2 && first.equals("loot"))
            out.addAll(List.of("list", "add", "addcommand", "addxp", "remove", "set", "enable", "disable", "preview", "reset", "reload"));
        else if (admin && args.length == 2 && first.equals("give")) out.add("sigil");
        else if (admin && args.length == 3 && first.equals("give")) plugin.getServer().getOnlinePlayers().forEach(p -> out.add(p.getName()));
        else if (admin && args.length == 2 && first.equals("difficulty")) for (Tuning.Preset preset : Tuning.Preset.values()) out.add(preset.name().toLowerCase(Locale.ROOT));
        else if (admin && args.length == 2 && first.equals("config")) out.addAll(List.of("get", "set"));
        else if (admin && args.length == 3 && first.equals("config")) {
            for (String key : plugin.getConfig().getKeys(true))
                if (!plugin.getConfig().isConfigurationSection(key) && !(plugin.getConfig().get(key) instanceof List<?>)) out.add(key);
        } else if (admin && args.length == 2 && first.equals("cooldown")) out.add("reset");
        else if (admin && first.equals("loot") && args.length == 3 && args[1].equalsIgnoreCase("set"))
            plugin.loot().entries().forEach(e -> out.add(Integer.toString(e.id)));
        else if (admin && first.equals("loot") && args.length == 3 && args[1].equalsIgnoreCase("remove"))
            plugin.loot().entries().forEach(e -> out.add(Integer.toString(e.id)));
        else if (admin && first.equals("loot") && args.length == 4 && args[1].equalsIgnoreCase("set")) out.addAll(List.of("chance", "min", "max", "scope", "enabled"));
        else if (admin && first.equals("loot") && args.length == 5 && args[1].equalsIgnoreCase("set") && args[3].equalsIgnoreCase("scope"))
            for (LootEntry.Scope s : LootEntry.Scope.values()) out.add(s.name());
        else if (args.length >= 1) {
            List<String> base = plugin.bosses().complete(sender, args);
            if (args.length == 1) out.addAll(base);
            else return base;
        }
        return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).distinct().sorted().toList();
    }
}

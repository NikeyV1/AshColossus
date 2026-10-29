package de.nikey.witherboss;

import de.nikey.witherboss.boss.Sigil;
import de.nikey.witherboss.boss.WitherListener;
import de.nikey.witherboss.boss.WitherService;
import de.nikey.witherboss.command.WitherBossCommand;
import de.nikey.witherboss.config.Tuning;
import de.nikey.witherboss.discord.DiscordNotifier;
import de.nikey.witherboss.loot.LootMenu;
import de.nikey.witherboss.loot.LootTable;
import de.nikey.witherboss.ui.Credits;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.logging.Level;

public final class WitherBossPlugin extends JavaPlugin {
    private WitherService bosses;
    private Tuning tuning;
    private LootTable loot;
    private LootMenu lootMenu;
    private Sigil sigil;
    private DiscordNotifier discord;
    private Credits credits;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        tuning = new Tuning(this::getConfig);
        discord = new DiscordNotifier(this);
        credits = new Credits(this);
        sigil = new Sigil(this);
        try {
            loot = LootTable.load(getDataFolder().toPath().resolve("loot.yml"));
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "loot.yml could not be loaded: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        lootMenu = new LootMenu(loot);
        try {
            bosses = new WitherService(this);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Ash Colossus could not start.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(new WitherListener(bosses), this);
        getServer().getPluginManager().registerEvents(lootMenu, this);
        Bukkit.getScheduler().runTaskTimer(this, bosses::tick, 1, 1);
        sigil.registerRecipe();
        PluginCommand command = getCommand("witherboss");
        if (command != null) {
            WitherBossCommand handler = new WitherBossCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
        getLogger().info("Ash Colossus by NikeyV1 (Buffed SMP), free boss plugin with built-in resource pack, " + credits.link());
    }

    @Override
    public void onDisable() {
        if (sigil != null) sigil.unregisterRecipe();
        if (bosses != null) {
            try {
                bosses.close();
            } catch (RuntimeException failure) {
                getLogger().log(Level.SEVERE, "Closing the boss fight failed; check state.yml before the next start.", failure);
            }
        }
        if (discord != null) discord.flush();
    }

    public void reloadEverything() throws IOException {
        reloadConfig();
        loot = LootTable.load(getDataFolder().toPath().resolve("loot.yml"));
        lootMenu.replace(loot);
        sigil.registerRecipe();
        if (!bosses.active() && !bosses.lobbyOpen()) bosses.reloadPack();
    }

    public WitherService bosses() {
        return bosses;
    }

    public Tuning tuning() {
        return tuning;
    }

    public LootTable loot() {
        return loot;
    }

    public LootMenu lootMenu() {
        return lootMenu;
    }

    public Sigil sigil() {
        return sigil;
    }

    public DiscordNotifier discord() {
        return discord;
    }

    public Credits credits() {
        return credits;
    }
}

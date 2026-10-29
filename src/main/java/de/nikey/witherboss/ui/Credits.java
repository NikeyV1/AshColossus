package de.nikey.witherboss.ui;

import de.nikey.witherboss.WitherBossPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

import org.bukkit.entity.Player;

import java.util.Collection;

public final class Credits {
    public static final String AUTHOR = "NikeyV1";
    public static final String SERVER = "Buffed SMP";

    private final WitherBossPlugin plugin;

    public Credits(WitherBossPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("credits.enabled", true);
    }

    public String link() {
        return plugin.getConfig().getString("credits.link", "https://github.com/NikeyV1/AshColossus");
    }

    public Component line() {
        return Msg.title("✦ Ash Colossus ")
                .append(Msg.text("by " + AUTHOR + " · " + SERVER + " · free boss plugin, resource pack included. "))
                .append(Msg.title("[Get it]").clickEvent(ClickEvent.openUrl(link())).hoverEvent(HoverEvent.showText(Msg.text(link()))));
    }

    public void intro(Player p) {
        if (enabled()) p.sendMessage(line());
    }

    public void victory(Collection<Player> players) {
        if (!enabled()) return;
        for (Player p : players) if (p.isOnline()) p.sendMessage(line());
    }

    public Component about() {
        return line();
    }
}

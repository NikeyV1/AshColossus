package de.nikey.witherboss.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;

public final class Msg {
    public static final TextColor RED = TextColor.color(0xE64B55);
    public static final TextColor LIGHT = TextColor.color(0xF4D9DA);
    public static final TextColor MUTED = TextColor.color(0xAC9DA2);
    public static final TextColor GOLD = TextColor.color(0xFFC83D);

    private Msg() {}

    public static Component text(String text) {
        return Component.text(text, LIGHT).decoration(TextDecoration.ITALIC, false);
    }

    public static Component title(String text) {
        return Component.text(text, RED).decoration(TextDecoration.ITALIC, false);
    }

    public static void send(CommandSender receiver, String text) {
        receiver.sendMessage(title("Colossus › ").append(text(text)));
    }

    public static ItemStack item(Material material, String name, String... lines) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(title(name));
            meta.lore(Arrays.stream(lines).map(line -> text(line).color(MUTED)).toList());
        });
        return item;
    }

    public static String time(long seconds) {
        long s = Math.max(0, seconds);
        return s / 60 + ":" + String.format("%02d", s % 60);
    }
}

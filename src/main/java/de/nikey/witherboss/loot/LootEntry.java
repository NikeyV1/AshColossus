package de.nikey.witherboss.loot;

import org.bukkit.inventory.ItemStack;

import java.util.Locale;

public final class LootEntry {
    public enum Type {
        ITEM,
        COMMAND,
        XP,
        TROPHY
    }

    public enum Scope {
        EACH_PLAYER("Every participant rolls separately"),
        RANDOM_PLAYER("One random participant"),
        TOP_DAMAGE("Only the top damage dealer");

        public final String description;

        Scope(String description) {
            this.description = description;
        }

        public Scope next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public static Scope parse(String value) {
            for (Scope s : values()) if (s.name().equalsIgnoreCase(value) || s.name().replace("_", "").equalsIgnoreCase(value)) return s;
            return EACH_PLAYER;
        }
    }

    public final int id;
    public Type type;
    public ItemStack item;
    public String command = "";
    public double chance = 100;
    public int min = 1, max = 1;
    public Scope scope = Scope.EACH_PLAYER;
    public boolean enabled = true;

    public LootEntry(int id, Type type) {
        this.id = id;
        this.type = type;
    }

    public static LootEntry item(int id, ItemStack stack, double chance, int min, int max, Scope scope) {
        LootEntry e = new LootEntry(id, Type.ITEM);
        e.item = stack.asOne();
        e.chance = chance;
        e.min = min;
        e.max = Math.max(min, max);
        e.scope = scope;
        return e;
    }

    public static LootEntry command(int id, String command, double chance, Scope scope) {
        LootEntry e = new LootEntry(id, Type.COMMAND);
        e.command = command;
        e.chance = chance;
        e.scope = scope;
        return e;
    }

    public static LootEntry xp(int id, int min, int max, double chance, Scope scope) {
        LootEntry e = new LootEntry(id, Type.XP);
        e.min = min;
        e.max = Math.max(min, max);
        e.chance = chance;
        e.scope = scope;
        return e;
    }

    public static LootEntry trophy(int id) {
        return new LootEntry(id, Type.TROPHY);
    }

    public boolean hasAmount() {
        return type == Type.ITEM || type == Type.XP;
    }

    public String chanceText() {
        return (chance == Math.rint(chance) ? Integer.toString((int) chance) : String.format(Locale.ROOT, "%.1f", chance)) + "%";
    }

    public String amountText() {
        return min == max ? Integer.toString(min) : min + "-" + max;
    }

    public String label() {
        return switch (type) {
            case ITEM -> amountText() + "x " + itemName();
            case COMMAND -> "Command: /" + command;
            case XP -> amountText() + " XP points";
            case TROPHY -> "Ash Colossus Trophy";
        };
    }

    public String itemName() {
        return nameOf(item);
    }

    public static String nameOf(ItemStack stack) {
        if (stack == null) return "?";
        var meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName())
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        String raw = stack.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    public String summary() {
        return "#" + id + " " + label() + " · " + chanceText() + " · " + scope.name().toLowerCase(Locale.ROOT) + (enabled ? "" : " · DISABLED");
    }
}

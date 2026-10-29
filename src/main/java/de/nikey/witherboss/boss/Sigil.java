package de.nikey.witherboss.boss;

import de.nikey.witherboss.WitherBossPlugin;
import de.nikey.witherboss.ui.Msg;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.logging.Level;

public final class Sigil {
    private final WitherBossPlugin plugin;
    private final NamespacedKey key, recipeKey;

    public Sigil(WitherBossPlugin plugin) {
        this.plugin = plugin;
        key = new NamespacedKey(plugin, "sigil");
        recipeKey = new NamespacedKey(plugin, "sigil_recipe");
    }

    public ItemStack create() {
        ItemStack item = WitherRewards.model("soul_core");
        item.editMeta(meta -> {
            meta.displayName(Msg.title("Colossus Sigil"));
            meta.lore(List.of(
                    Msg.text("Hold this in your other hand while you build a Wither.").color(Msg.MUTED),
                    Msg.text("It awakens the Ash Colossus instead of a normal Wither.").color(Msg.MUTED)));
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    public boolean is(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    public void registerRecipe() {
        Bukkit.removeRecipe(recipeKey);
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("sigil.recipe");
        if (section == null || !section.getBoolean("enabled", true)) return;
        try {
            List<String> shape = section.getStringList("shape");
            ShapedRecipe recipe = new ShapedRecipe(recipeKey, create());
            recipe.shape(shape.toArray(String[]::new));
            ConfigurationSection ingredients = section.getConfigurationSection("ingredients");
            if (ingredients == null) throw new IllegalArgumentException("sigil.recipe.ingredients is missing");
            for (String symbol : ingredients.getKeys(false)) {
                Material material = Material.matchMaterial(ingredients.getString(symbol, ""));
                if (material == null) throw new IllegalArgumentException("Unknown material for '" + symbol + "'");
                recipe.setIngredient(symbol.charAt(0), material);
            }
            Bukkit.addRecipe(recipe);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Sigil recipe is invalid and was not registered: " + e.getMessage());
        }
    }

    public void unregisterRecipe() {
        Bukkit.removeRecipe(recipeKey);
    }
}

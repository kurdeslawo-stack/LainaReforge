package pl.laina.reforge.service;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import pl.laina.reforge.LainaReforgePlugin;

import java.util.Map;

public final class CurrencyService {

    private final LainaReforgePlugin plugin;
    private final NamespacedKey currencyKey;

    public CurrencyService(LainaReforgePlugin plugin) {
        this.plugin = plugin;
        this.currencyKey = new NamespacedKey(plugin, "currency_type");
    }

    public ItemStack createShard(int amount) {
        Material material = Material.matchMaterial(plugin.getConfig().getString("currency.shard-material", "AMETHYST_SHARD"));
        if (material == null) {
            material = Material.AMETHYST_SHARD;
        }

        ItemStack stack = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        String name = plugin.getConfig().getString("currency.shard-name", "&dOdłamek Customu");
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(name));
        meta.getPersistentDataContainer().set(currencyKey, PersistentDataType.STRING, "shard");
        stack.setItemMeta(meta);
        return stack;
    }

    public void giveShards(Player player, int amount) {
        int left = Math.max(0, amount);
        while (left > 0) {
            int part = Math.min(64, left);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(createShard(part));
            overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
            left -= part;
        }
    }
}

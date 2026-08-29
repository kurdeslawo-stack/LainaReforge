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
import java.util.Optional;

public final class CurrencyService {

    private final LainaReforgePlugin plugin;
    private final NamespacedKey currencyKey;

    public CurrencyService(LainaReforgePlugin plugin) {
        this.plugin = plugin;
        this.currencyKey = new NamespacedKey(plugin, "currency_type");
    }

    public ItemStack createShard(int amount) {
        return createCurrency(
                plugin.getConfig().getString("currency.shard-material", "AMETHYST_SHARD"),
                Material.AMETHYST_SHARD,
                plugin.getConfig().getString("currency.shard-name", "&dOdłamek Customu"),
                "shard",
                amount
        );
    }

    public ItemStack createNugget(int amount) {
        return createCurrency(
                plugin.getConfig().getString("currency.nugget-material", "NETHERITE_SCRAP"),
                Material.NETHERITE_SCRAP,
                plugin.getConfig().getString("currency.nugget-name", "&6Bryłka Reforge"),
                "nugget",
                amount
        );
    }

    public Optional<String> getCurrencyType(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return Optional.empty();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(meta.getPersistentDataContainer().get(currencyKey, PersistentDataType.STRING));
    }

    public boolean isPluginCurrency(ItemStack item) {
        return getCurrencyType(item).isPresent();
    }

    public void giveShards(Player player, int amount) {
        giveCurrency(player, amount, true);
    }

    public void giveNuggets(Player player, int amount) {
        giveCurrency(player, amount, false);
    }

    private void giveCurrency(Player player, int amount, boolean shards) {
        int left = Math.max(0, amount);
        while (left > 0) {
            int part = Math.min(64, left);
            ItemStack stack = shards ? createShard(part) : createNugget(part);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
            overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
            left -= part;
        }
    }

    private ItemStack createCurrency(String configuredMaterial,
                                     Material fallback,
                                     String name,
                                     String type,
                                     int amount) {
        Material material = Material.matchMaterial(configuredMaterial == null ? "" : configuredMaterial);
        if (material == null) {
            material = fallback;
        }

        ItemStack stack = new ItemStack(material, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(name));
        meta.getPersistentDataContainer().set(currencyKey, PersistentDataType.STRING, type);
        stack.setItemMeta(meta);
        return stack;
    }
}

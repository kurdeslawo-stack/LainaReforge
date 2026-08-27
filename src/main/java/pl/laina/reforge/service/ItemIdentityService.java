package pl.laina.reforge.service;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import pl.laina.reforge.LainaReforgePlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ItemIdentityService {

    private final LainaReforgePlugin plugin;
    private final List<NamespacedKey> stringKeys = new ArrayList<>();
    private boolean allowMaterialFallback;

    public ItemIdentityService(LainaReforgePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        stringKeys.clear();
        allowMaterialFallback = plugin.getConfig().getBoolean("item-identification.allow-material-fallback", false);

        for (String rawKey : plugin.getConfig().getStringList("item-identification.pdc-string-keys")) {
            NamespacedKey key = NamespacedKey.fromString(rawKey.trim().toLowerCase(Locale.ROOT));
            if (key != null) {
                stringKeys.add(key);
            } else {
                plugin.getLogger().warning("Niepoprawny klucz PDC w config.yml: " + rawKey);
            }
        }
    }

    public Optional<String> identify(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return Optional.empty();
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            PersistentDataContainer container = meta.getPersistentDataContainer();
            for (NamespacedKey key : stringKeys) {
                String value = container.get(key, PersistentDataType.STRING);
                if (value != null && !value.isBlank()) {
                    return Optional.of(normalize(value));
                }
            }
        }

        if (allowMaterialFallback) {
            return Optional.of(item.getType().getKey().toString().toLowerCase(Locale.ROOT));
        }

        return Optional.empty();
    }

    public List<String> inspect(ItemStack item) {
        List<String> lines = new ArrayList<>();
        if (item == null || item.getType().isAir()) {
            lines.add("Przedmiot: AIR");
            return lines;
        }

        lines.add("Material: " + item.getType().getKey());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            lines.add("Brak ItemMeta/PDC.");
            return lines;
        }

        PersistentDataContainer container = meta.getPersistentDataContainer();
        if (container.getKeys().isEmpty()) {
            lines.add("PDC: brak kluczy");
        } else {
            lines.add("PDC keys:");
            for (NamespacedKey key : container.getKeys()) {
                String text = "- " + key;
                String stringValue = container.get(key, PersistentDataType.STRING);
                Integer intValue = container.get(key, PersistentDataType.INTEGER);
                if (stringValue != null) {
                    text += " = \"" + stringValue + "\" (STRING)";
                } else if (intValue != null) {
                    text += " = " + intValue + " (INTEGER)";
                }
                lines.add(text);
            }
        }

        identify(item).ifPresentOrElse(
                id -> lines.add("LainaReforge ID: " + id),
                () -> lines.add("LainaReforge ID: nierozpoznany")
        );
        return lines;
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}

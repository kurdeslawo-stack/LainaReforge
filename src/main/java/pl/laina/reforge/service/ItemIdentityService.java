package pl.laina.reforge.service;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import pl.laina.reforge.LainaReforgePlugin;
import pl.laina.reforge.runtime.RuntimeItemIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ItemIdentityService {

    private final LainaReforgePlugin plugin;
    private final NamespacedKey devItemIdKey;
    private final List<NamespacedKey> stringKeys = new ArrayList<>();
    private boolean allowMaterialFallback;
    private boolean developmentEnabled;

    public ItemIdentityService(LainaReforgePlugin plugin) {
        this.plugin = plugin;
        this.devItemIdKey = new NamespacedKey(plugin, "dev_item_id");
        reload();
    }

    public void reload() {
        stringKeys.clear();
        allowMaterialFallback = plugin.getConfig().getBoolean("item-identification.allow-material-fallback", false);
        developmentEnabled = plugin.getConfig().getBoolean("development.enabled", false);

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

            if (developmentEnabled) {
                String devId = container.get(devItemIdKey, PersistentDataType.STRING);
                if (devId != null && !devId.isBlank()) {
                    return Optional.of(normalize(devId));
                }
            }

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

    /** Uses the same identity boundary for the approved material+CMD runtime registry. */
    public Optional<RuntimeItemIdentity> identifyRuntime(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return Optional.empty();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasCustomModelData()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new RuntimeItemIdentity(
                    item.getType().getKey().getKey(), meta.getCustomModelData()));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public boolean applyDevId(ItemStack item, String itemId) {
        if (!developmentEnabled || item == null || item.getType().isAir() || itemId == null || itemId.isBlank()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        meta.getPersistentDataContainer().set(devItemIdKey, PersistentDataType.STRING, normalize(itemId));
        item.setItemMeta(meta);
        return true;
    }

    public boolean clearDevId(ItemStack item) {
        if (!developmentEnabled || item == null || item.getType().isAir()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        PersistentDataContainer container = meta.getPersistentDataContainer();
        if (!container.has(devItemIdKey, PersistentDataType.STRING)) {
            return false;
        }

        container.remove(devItemIdKey);
        item.setItemMeta(meta);
        return true;
    }

    public boolean isDevelopmentEnabled() {
        return developmentEnabled;
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
                lines.add(describePdcValue(container, key));
            }
        }

        identify(item).ifPresentOrElse(
                id -> lines.add("LainaReforge ID: " + id),
                () -> lines.add("LainaReforge ID: nierozpoznany")
        );
        identifyRuntime(item).ifPresentOrElse(
                id -> lines.add("Runtime material+CMD: " + id.key()),
                () -> lines.add("Runtime material+CMD: niepoprawne lub brak CMD")
        );
        return lines;
    }

    private String describePdcValue(PersistentDataContainer container, NamespacedKey key) {
        String prefix = "- " + key;
        try {
            if (container.has(key, PersistentDataType.STRING)) {
                String value = container.get(key, PersistentDataType.STRING);
                return prefix + " = \"" + value + "\" (STRING)";
            }
            if (container.has(key, PersistentDataType.INTEGER)) {
                Integer value = container.get(key, PersistentDataType.INTEGER);
                return prefix + " = " + value + " (INTEGER)";
            }
            if (container.has(key, PersistentDataType.LONG)) {
                Long value = container.get(key, PersistentDataType.LONG);
                return prefix + " = " + value + " (LONG)";
            }
            if (container.has(key, PersistentDataType.DOUBLE)) {
                Double value = container.get(key, PersistentDataType.DOUBLE);
                return prefix + " = " + value + " (DOUBLE)";
            }
            if (container.has(key, PersistentDataType.FLOAT)) {
                Float value = container.get(key, PersistentDataType.FLOAT);
                return prefix + " = " + value + " (FLOAT)";
            }
            if (container.has(key, PersistentDataType.BYTE)) {
                Byte value = container.get(key, PersistentDataType.BYTE);
                return prefix + " = " + value + " (BYTE)";
            }
            if (container.has(key, PersistentDataType.SHORT)) {
                Short value = container.get(key, PersistentDataType.SHORT);
                return prefix + " = " + value + " (SHORT)";
            }
            if (container.has(key, PersistentDataType.INTEGER_ARRAY)) {
                int[] value = container.get(key, PersistentDataType.INTEGER_ARRAY);
                return prefix + " (INTEGER_ARRAY, length=" + (value == null ? 0 : value.length) + ")";
            }
            if (container.has(key, PersistentDataType.LONG_ARRAY)) {
                long[] value = container.get(key, PersistentDataType.LONG_ARRAY);
                return prefix + " (LONG_ARRAY, length=" + (value == null ? 0 : value.length) + ")";
            }
            if (container.has(key, PersistentDataType.BYTE_ARRAY)) {
                byte[] value = container.get(key, PersistentDataType.BYTE_ARRAY);
                return prefix + " (BYTE_ARRAY, length=" + (value == null ? 0 : value.length) + ")";
            }
            return prefix + " (typ PDC nieobslugiwany przez inspect)";
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Nie udalo sie odczytac PDC " + key + " podczas /reforge inspect: "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            return prefix + " (nie udalo sie bezpiecznie odczytac wartosci)";
        }
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}

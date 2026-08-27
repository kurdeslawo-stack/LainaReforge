package pl.laina.reforge.service;

import org.bukkit.configuration.ConfigurationSection;
import pl.laina.reforge.LainaReforgePlugin;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RecycleValueService {

    private final LainaReforgePlugin plugin;
    private final Map<String, Integer> tierValues = new HashMap<>();
    private final Map<String, String> itemTiers = new HashMap<>();
    private final Map<String, Integer> explicitValues = new HashMap<>();
    private final Map<String, Integer> legacyValues = new HashMap<>();
    private final Set<String> blacklist = new HashSet<>();
    private int defaultValue;

    public RecycleValueService(LainaReforgePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        tierValues.clear();
        itemTiers.clear();
        explicitValues.clear();
        legacyValues.clear();
        blacklist.clear();

        defaultValue = Math.max(0, plugin.getConfig().getInt("recycling.default-value", 0));
        for (String id : plugin.getConfig().getStringList("recycling.blacklisted-ids")) {
            blacklist.add(normalize(id));
        }

        ConfigurationSection tiers = plugin.getConfig().getConfigurationSection("recycling.tiers");
        if (tiers != null) {
            for (String key : tiers.getKeys(false)) {
                tierValues.put(normalize(key), Math.max(0, tiers.getInt(key, 0)));
            }
        }

        ConfigurationSection items = plugin.getConfig().getConfigurationSection("recycling.items");
        if (items != null) {
            for (String rawId : items.getKeys(false)) {
                String id = normalize(rawId);
                ConfigurationSection item = items.getConfigurationSection(rawId);
                if (item == null) {
                    continue;
                }

                String tier = normalize(item.getString("tier", ""));
                if (!tier.isBlank()) {
                    itemTiers.put(id, tier);
                }

                if (item.contains("shards")) {
                    explicitValues.put(id, Math.max(0, item.getInt("shards", 0)));
                }
            }
        }

        // Wsteczna zgodnosc z pierwszym formatem configu.
        ConfigurationSection legacy = plugin.getConfig().getConfigurationSection("recycling.values");
        if (legacy != null) {
            for (String key : legacy.getKeys(false)) {
                legacyValues.put(normalize(key), Math.max(0, legacy.getInt(key, 0)));
            }
        }
    }

    public int getValue(String itemId) {
        String id = normalize(itemId);
        if (id.isBlank() || blacklist.contains(id)) {
            return 0;
        }

        Integer explicit = explicitValues.get(id);
        if (explicit != null) {
            return explicit;
        }

        String tier = itemTiers.get(id);
        if (tier != null) {
            Integer tierValue = tierValues.get(tier);
            if (tierValue != null) {
                return tierValue;
            }
        }

        Integer legacy = legacyValues.get(id);
        if (legacy != null) {
            return legacy;
        }

        return defaultValue;
    }

    public Optional<String> getTier(String itemId) {
        return Optional.ofNullable(itemTiers.get(normalize(itemId)));
    }

    public boolean isBlacklisted(String itemId) {
        return blacklist.contains(normalize(itemId));
    }

    public Map<String, Integer> getValues() {
        Map<String, Integer> resolved = new HashMap<>();
        Set<String> ids = new HashSet<>();
        ids.addAll(itemTiers.keySet());
        ids.addAll(explicitValues.keySet());
        ids.addAll(legacyValues.keySet());

        for (String id : ids) {
            resolved.put(id, getValue(id));
        }
        return Collections.unmodifiableMap(resolved);
    }

    public Map<String, Integer> getTierValues() {
        return Collections.unmodifiableMap(tierValues);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}

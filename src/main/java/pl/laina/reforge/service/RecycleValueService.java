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
    private final Map<String, String> itemCategories = new HashMap<>();
    private final Map<String, Boolean> itemRecyclableOverrides = new HashMap<>();
    private final Map<String, Boolean> categoryRecyclable = new HashMap<>();
    private final Map<String, Integer> explicitValues = new HashMap<>();
    private final Map<String, Integer> legacyValues = new HashMap<>();
    private final Set<String> blacklist = new HashSet<>();
    private final Set<String> blockedCategories = new HashSet<>();
    private int defaultValue;
    private boolean requireRegisteredItem;

    public RecycleValueService(LainaReforgePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        tierValues.clear();
        itemTiers.clear();
        itemCategories.clear();
        itemRecyclableOverrides.clear();
        categoryRecyclable.clear();
        explicitValues.clear();
        legacyValues.clear();
        blacklist.clear();
        blockedCategories.clear();

        defaultValue = Math.max(0, plugin.getConfig().getInt("recycling.default-value", 0));
        requireRegisteredItem = plugin.getConfig().getBoolean("recycling.require-registered-item", true);

        for (String id : plugin.getConfig().getStringList("recycling.blacklisted-ids")) {
            blacklist.add(normalize(id));
        }
        for (String category : plugin.getConfig().getStringList("recycling.blocked-categories")) {
            blockedCategories.add(normalize(category));
        }

        ConfigurationSection categories = plugin.getConfig().getConfigurationSection("recycling.categories");
        if (categories != null) {
            for (String rawCategory : categories.getKeys(false)) {
                ConfigurationSection category = categories.getConfigurationSection(rawCategory);
                if (category != null) {
                    categoryRecyclable.put(normalize(rawCategory), category.getBoolean("recyclable", true));
                }
            }
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

                String category = normalize(item.getString("category", ""));
                if (!category.isBlank()) {
                    itemCategories.put(id, category);
                }

                String tier = normalize(item.getString("tier", ""));
                if (!tier.isBlank()) {
                    itemTiers.put(id, tier);
                }

                if (item.contains("recyclable")) {
                    itemRecyclableOverrides.put(id, item.getBoolean("recyclable"));
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
        if (!isPolicyAllowed(id)) {
            return 0;
        }
        return resolveValue(id);
    }

    public Optional<String> getRejectionReason(String itemId) {
        String id = normalize(itemId);
        if (id.isBlank()) {
            return Optional.of("brak ID");
        }
        if (blacklist.contains(id)) {
            return Optional.of("item jest na blackliscie");
        }
        if (requireRegisteredItem && !isRegistered(id)) {
            return Optional.of("item nie jest jeszcze skonfigurowany");
        }

        String category = itemCategories.get(id);
        if (category != null) {
            if (blockedCategories.contains(category)) {
                return Optional.of("zablokowana kategoria: " + category);
            }
            if (Boolean.FALSE.equals(categoryRecyclable.get(category))) {
                return Optional.of("recycling wylaczony dla kategorii: " + category);
            }
        }

        if (Boolean.FALSE.equals(itemRecyclableOverrides.get(id))) {
            return Optional.of("recycling wylaczony dla tego itemu");
        }
        if (resolveValue(id) <= 0) {
            return Optional.of("brak przypisanej wartosci");
        }
        return Optional.empty();
    }

    public boolean isRecyclable(String itemId) {
        return getRejectionReason(itemId).isEmpty();
    }

    public Optional<String> getTier(String itemId) {
        return Optional.ofNullable(itemTiers.get(normalize(itemId)));
    }

    public Optional<String> getCategory(String itemId) {
        return Optional.ofNullable(itemCategories.get(normalize(itemId)));
    }

    public boolean isBlacklisted(String itemId) {
        return blacklist.contains(normalize(itemId));
    }

    public boolean isBlockedCategory(String category) {
        return blockedCategories.contains(normalize(category));
    }

    public boolean isRegistered(String itemId) {
        String id = normalize(itemId);
        return itemTiers.containsKey(id)
                || itemCategories.containsKey(id)
                || itemRecyclableOverrides.containsKey(id)
                || explicitValues.containsKey(id)
                || legacyValues.containsKey(id);
    }

    public Map<String, Integer> getValues() {
        Map<String, Integer> resolved = new HashMap<>();
        Set<String> ids = new HashSet<>();
        ids.addAll(itemTiers.keySet());
        ids.addAll(itemCategories.keySet());
        ids.addAll(itemRecyclableOverrides.keySet());
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

    private boolean isPolicyAllowed(String id) {
        if (id.isBlank() || blacklist.contains(id)) {
            return false;
        }
        if (requireRegisteredItem && !isRegistered(id)) {
            return false;
        }

        String category = itemCategories.get(id);
        if (category != null) {
            if (blockedCategories.contains(category)) {
                return false;
            }
            if (Boolean.FALSE.equals(categoryRecyclable.get(category))) {
                return false;
            }
        }

        return !Boolean.FALSE.equals(itemRecyclableOverrides.get(id));
    }

    private int resolveValue(String id) {
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

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}

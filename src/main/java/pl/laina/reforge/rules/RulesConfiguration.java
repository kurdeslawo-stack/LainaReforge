package pl.laina.reforge.rules;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Immutable, already validated policy snapshot. */
public final class RulesConfiguration {

    private final Map<String, Boolean> categoryPolicies;
    private final Map<String, Integer> tierValues;
    private final Map<String, ItemRule> items;
    private final Set<String> blacklistedIds;
    private final Set<String> blockedCategories;
    private final boolean valid;

    RulesConfiguration(Map<String, Boolean> categoryPolicies,
                       Map<String, Integer> tierValues,
                       Map<String, ItemRule> items,
                       Set<String> blacklistedIds,
                       Set<String> blockedCategories,
                       boolean valid) {
        this.categoryPolicies = Map.copyOf(new LinkedHashMap<>(categoryPolicies));
        this.tierValues = Map.copyOf(new LinkedHashMap<>(tierValues));
        this.items = Map.copyOf(new LinkedHashMap<>(items));
        this.blacklistedIds = Set.copyOf(new LinkedHashSet<>(blacklistedIds));
        this.blockedCategories = Set.copyOf(new LinkedHashSet<>(blockedCategories));
        this.valid = valid;
    }

    public static RulesConfiguration failClosed() {
        return new RulesConfiguration(Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), false);
    }

    public boolean valid() {
        return valid;
    }

    public boolean isConfigured(String itemId) {
        return items.containsKey(normalize(itemId));
    }

    public Set<String> configuredItemIds() {
        return items.keySet();
    }

    public int configuredItemCount() {
        return items.size();
    }

    public int recyclableItemCount() {
        int count = 0;
        for (String id : items.keySet()) {
            if (isStaticallyRecyclable(id)) {
                count++;
            }
        }
        return count;
    }

    public int blockedItemCount() {
        return configuredItemCount() - recyclableItemCount();
    }

    Optional<ItemRule> item(String id) {
        return Optional.ofNullable(items.get(normalize(id)));
    }

    boolean isBlacklisted(String id) {
        return blacklistedIds.contains(normalize(id));
    }

    boolean isBlockedCategory(String category) {
        return blockedCategories.contains(normalize(category));
    }

    boolean categoryAllows(String category) {
        return Boolean.TRUE.equals(categoryPolicies.get(normalize(category)));
    }

    Integer tierValue(String tier) {
        return tierValues.get(normalize(tier));
    }

    private boolean isStaticallyRecyclable(String id) {
        if (!valid || isBlacklisted(id)) {
            return false;
        }
        ItemRule item = items.get(id);
        if (item == null || Boolean.FALSE.equals(item.recyclable())) {
            return false;
        }
        if (isBlockedCategory(item.category()) || !categoryAllows(item.category())) {
            return false;
        }
        Integer value = item.shards() != null ? item.shards() : tierValue(item.tier());
        return value != null && value > 0;
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    record ItemRule(String category, String tier, Boolean recyclable, Integer shards) {
    }
}

package pl.laina.reforge.rules;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static pl.laina.reforge.rules.ConfigurationIssue.Severity.ERROR;
import static pl.laina.reforge.rules.ConfigurationIssue.Severity.WARNING;

public final class RulesConfigurationValidator {

    public static final int MAX_SHARD_VALUE = 1_000_000;

    private RulesConfigurationValidator() {
    }

    public static RulesConfigurationCandidate validate(ConfigurationSection root) {
        List<ConfigurationIssue> issues = new ArrayList<>();
        Map<String, Boolean> categories = new LinkedHashMap<>();
        Map<String, Integer> tiers = new LinkedHashMap<>();
        Map<String, RulesConfiguration.ItemRule> items = new LinkedHashMap<>();
        Set<String> blacklist = new LinkedHashSet<>();
        Set<String> blockedCategories = new LinkedHashSet<>();

        if (root == null) {
            error(issues, "MISSING_ROOT", "recycling", "Brak konfiguracji.");
            return candidate(categories, tiers, items, blacklist, blockedCategories, issues);
        }

        if (root.getBoolean("settings.allow-unknown-items", false)) {
            warning(issues, "IGNORED_UNSAFE_SETTING", "settings.allow-unknown-items",
                    "Rules Engine v2 zawsze blokuje nierozpoznane itemy.");
        }
        if (root.getBoolean("item-identification.allow-material-fallback", false)) {
            error(issues, "UNSAFE_IDENTITY_FALLBACK", "item-identification.allow-material-fallback",
                    "Material nie moze byc samodzielnym identyfikatorem customu.");
        }

        ConfigurationSection recycling = root.getConfigurationSection("recycling");
        if (recycling == null) {
            error(issues, "MISSING_SECTION", "recycling", "Brak wymaganej sekcji recycling.");
            return candidate(categories, tiers, items, blacklist, blockedCategories, issues);
        }

        readStringSet(recycling, "blacklisted-ids", blacklist, issues);
        readStringSet(recycling, "blocked-categories", blockedCategories, issues);
        readCategories(recycling.getConfigurationSection("categories"), categories, issues);
        readTiers(recycling.getConfigurationSection("tiers"), tiers, issues);
        readItems(recycling.getConfigurationSection("items"), items, issues);

        for (String category : blockedCategories) {
            if (!categories.containsKey(category)) {
                error(issues, "UNKNOWN_CATEGORY", "recycling.blocked-categories",
                        "Nieznana zablokowana kategoria: " + category);
            } else if (Boolean.TRUE.equals(categories.get(category))) {
                error(issues, "CONFLICTING_CATEGORY_POLICY", "recycling.categories." + category,
                        "Kategoria jest jednoczesnie zablokowana i oznaczona recyclable:true.");
            }
        }

        for (Map.Entry<String, RulesConfiguration.ItemRule> entry : items.entrySet()) {
            String id = entry.getKey();
            RulesConfiguration.ItemRule item = entry.getValue();
            String path = "recycling.items." + id;
            if (!item.category().isBlank() && !categories.containsKey(item.category())) {
                error(issues, "UNKNOWN_CATEGORY", path + ".category",
                        "Nieznana kategoria: " + item.category());
            }
            if (!item.tier().isBlank() && !tiers.containsKey(item.tier())) {
                error(issues, "UNKNOWN_TIER", path + ".tier", "Nieznany tier: " + item.tier());
            }
            if (Boolean.TRUE.equals(item.recyclable())) {
                if (blacklist.contains(id)) {
                    error(issues, "CONFLICTING_ITEM_POLICY", path + ".recyclable",
                            "recyclable:true jest sprzeczne z blacklisted-ids.");
                }
                if (blockedCategories.contains(item.category())
                        || Boolean.FALSE.equals(categories.get(item.category()))) {
                    error(issues, "CONFLICTING_ITEM_POLICY", path + ".recyclable",
                            "recyclable:true nie moze ominac zablokowanej kategorii.");
                }
            }
        }

        ConfigurationSection legacy = recycling.getConfigurationSection("values");
        if (legacy != null && !legacy.getKeys(false).isEmpty()) {
            for (String key : legacy.getKeys(false)) {
                error(issues, "LEGACY_ENTRY_INCOMPLETE", "recycling.values." + key,
                        "Wpis legacy nie ma wymaganej kategorii i tieru; przenies go do recycling.items.");
            }
        }

        return candidate(categories, tiers, items, blacklist, blockedCategories, issues);
    }

    private static void readCategories(ConfigurationSection section,
                                       Map<String, Boolean> result,
                                       List<ConfigurationIssue> issues) {
        if (section == null) {
            error(issues, "MISSING_SECTION", "recycling.categories", "Brak wymaganych kategorii.");
            return;
        }
        Map<String, String> normalizedKeys = new HashMap<>();
        for (String raw : section.getKeys(false)) {
            String normalized = normalizedUnique(raw, "recycling.categories", normalizedKeys, issues);
            ConfigurationSection category = section.getConfigurationSection(raw);
            if (category == null) {
                error(issues, "INVALID_TYPE", "recycling.categories." + raw,
                        "Kategoria musi byc sekcja z polem recyclable.");
                continue;
            }
            if (!category.contains("recyclable")) {
                error(issues, "MISSING_FIELD", "recycling.categories." + raw + ".recyclable",
                        "Brak wymaganego pola recyclable.");
                continue;
            }
            if (!category.isBoolean("recyclable")) {
                error(issues, "INVALID_TYPE", "recycling.categories." + raw + ".recyclable",
                        "recyclable musi byc wartoscia true/false.");
                continue;
            }
            if (!normalized.isBlank()) {
                result.put(normalized, category.getBoolean("recyclable"));
            }
        }
    }

    private static void readTiers(ConfigurationSection section,
                                  Map<String, Integer> result,
                                  List<ConfigurationIssue> issues) {
        if (section == null) {
            error(issues, "MISSING_SECTION", "recycling.tiers", "Brak wymaganych tierow.");
            return;
        }
        Map<String, String> normalizedKeys = new HashMap<>();
        for (String raw : section.getKeys(false)) {
            String path = "recycling.tiers." + raw;
            String normalized = normalizedUnique(raw, "recycling.tiers", normalizedKeys, issues);
            if (!section.isInt(raw)) {
                error(issues, "INVALID_TYPE", path, "Wartosc tieru musi byc liczba calkowita.");
                continue;
            }
            int value = section.getInt(raw);
            if (value <= 0 || value > MAX_SHARD_VALUE) {
                error(issues, "INVALID_VALUE", path,
                        "Wartosc tieru musi byc w zakresie 1-" + MAX_SHARD_VALUE + ".");
                continue;
            }
            if (!normalized.isBlank()) {
                result.put(normalized, value);
            }
        }
    }

    private static void readItems(ConfigurationSection section,
                                  Map<String, RulesConfiguration.ItemRule> result,
                                  List<ConfigurationIssue> issues) {
        if (section == null) {
            error(issues, "MISSING_SECTION", "recycling.items", "Brak wymaganej sekcji itemow.");
            return;
        }
        Map<String, String> normalizedKeys = new HashMap<>();
        for (String raw : section.getKeys(false)) {
            String id = normalizedUnique(raw, "recycling.items", normalizedKeys, issues);
            String path = "recycling.items." + raw;
            ConfigurationSection item = section.getConfigurationSection(raw);
            if (item == null) {
                error(issues, "INVALID_TYPE", path, "Wpis itemu musi byc sekcja.");
                continue;
            }

            String category = requiredString(item, "category", path, issues);
            String tier = requiredString(item, "tier", path, issues);
            Boolean recyclable = optionalBoolean(item, "recyclable", path, issues);
            Integer shards = optionalPositiveInt(item, "shards", path, issues);
            if (!id.isBlank()) {
                result.put(id, new RulesConfiguration.ItemRule(category, tier, recyclable, shards));
            }
        }
    }

    private static String requiredString(ConfigurationSection section,
                                         String key,
                                         String parentPath,
                                         List<ConfigurationIssue> issues) {
        String path = parentPath + "." + key;
        if (!section.contains(key)) {
            error(issues, "MISSING_FIELD", path, "Brak wymaganego pola " + key + ".");
            return "";
        }
        if (!section.isString(key)) {
            error(issues, "INVALID_TYPE", path, key + " musi byc tekstem.");
            return "";
        }
        String value = RulesConfiguration.normalize(section.getString(key, ""));
        if (value.isBlank()) {
            error(issues, "INVALID_VALUE", path, key + " nie moze byc puste.");
        }
        return value;
    }

    private static Boolean optionalBoolean(ConfigurationSection section,
                                           String key,
                                           String parentPath,
                                           List<ConfigurationIssue> issues) {
        if (!section.contains(key)) {
            return null;
        }
        if (!section.isBoolean(key)) {
            error(issues, "INVALID_TYPE", parentPath + "." + key, key + " musi byc true/false.");
            return null;
        }
        return section.getBoolean(key);
    }

    private static Integer optionalPositiveInt(ConfigurationSection section,
                                               String key,
                                               String parentPath,
                                               List<ConfigurationIssue> issues) {
        if (!section.contains(key)) {
            return null;
        }
        String path = parentPath + "." + key;
        if (!section.isInt(key)) {
            error(issues, "INVALID_TYPE", path, key + " musi byc liczba calkowita.");
            return null;
        }
        int value = section.getInt(key);
        if (value <= 0 || value > MAX_SHARD_VALUE) {
            error(issues, "INVALID_VALUE", path,
                    key + " musi byc w zakresie 1-" + MAX_SHARD_VALUE + ".");
            return null;
        }
        return value;
    }

    private static void readStringSet(ConfigurationSection parent,
                                      String key,
                                      Set<String> result,
                                      List<ConfigurationIssue> issues) {
        Object rawValue = parent.get(key);
        if (rawValue == null) {
            return;
        }
        if (!(rawValue instanceof List<?> values)) {
            error(issues, "INVALID_TYPE", "recycling." + key, "Pole musi byc lista tekstow.");
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            String path = "recycling." + key + "[" + index + "]";
            if (!(value instanceof String text)) {
                error(issues, "INVALID_TYPE", path, "Wpis musi byc tekstem.");
                continue;
            }
            String normalized = RulesConfiguration.normalize(text);
            if (normalized.isBlank()) {
                error(issues, "INVALID_VALUE", path, "Wpis nie moze byc pusty.");
            } else if (!seen.add(normalized)) {
                error(issues, "DUPLICATE_ENTRY", path, "Powtorzony wpis: " + normalized);
            } else {
                result.add(normalized);
            }
        }
    }

    private static String normalizedUnique(String raw,
                                           String parentPath,
                                           Map<String, String> normalizedKeys,
                                           List<ConfigurationIssue> issues) {
        String normalized = RulesConfiguration.normalize(raw);
        if (normalized.isBlank()) {
            error(issues, "INVALID_KEY", parentPath, "Klucz nie moze byc pusty.");
            return "";
        }
        String previous = normalizedKeys.putIfAbsent(normalized, raw);
        if (previous != null) {
            error(issues, "AMBIGUOUS_KEY", parentPath + "." + raw,
                    "Klucze '" + previous + "' i '" + raw + "' sa niejednoznaczne po normalizacji.");
        }
        return normalized;
    }

    private static RulesConfigurationCandidate candidate(Map<String, Boolean> categories,
                                                         Map<String, Integer> tiers,
                                                         Map<String, RulesConfiguration.ItemRule> items,
                                                         Set<String> blacklist,
                                                         Set<String> blockedCategories,
                                                         List<ConfigurationIssue> issues) {
        ConfigurationValidationReport report = new ConfigurationValidationReport(issues);
        RulesConfiguration configuration = new RulesConfiguration(
                categories, tiers, items, blacklist, blockedCategories, report.isValid());
        return new RulesConfigurationCandidate(configuration, report);
    }

    private static void error(List<ConfigurationIssue> issues, String code, String path, String message) {
        issues.add(new ConfigurationIssue(ERROR, code, path, message));
    }

    private static void warning(List<ConfigurationIssue> issues, String code, String path, String message) {
        issues.add(new ConfigurationIssue(WARNING, code, path, message));
    }
}

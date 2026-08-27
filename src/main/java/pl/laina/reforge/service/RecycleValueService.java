package pl.laina.reforge.service;

import org.bukkit.configuration.ConfigurationSection;
import pl.laina.reforge.LainaReforgePlugin;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class RecycleValueService {

    private final LainaReforgePlugin plugin;
    private final Map<String, Integer> values = new HashMap<>();
    private final Set<String> blacklist = new HashSet<>();
    private int defaultValue;

    public RecycleValueService(LainaReforgePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        values.clear();
        blacklist.clear();

        defaultValue = Math.max(0, plugin.getConfig().getInt("recycling.default-value", 0));
        blacklist.addAll(plugin.getConfig().getStringList("recycling.blacklisted-ids"));

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("recycling.values");
        if (section == null) {
            return;
        }

        for (String key : section.getKeys(false)) {
            int value = Math.max(0, section.getInt(key, 0));
            values.put(normalize(key), value);
        }
    }

    public int getValue(String itemId) {
        String normalized = normalize(itemId);
        if (blacklist.contains(normalized)) {
            return 0;
        }
        return values.getOrDefault(normalized, defaultValue);
    }

    public boolean isBlacklisted(String itemId) {
        return blacklist.contains(normalize(itemId));
    }

    public Map<String, Integer> getValues() {
        return Collections.unmodifiableMap(values);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}

package pl.laina.reforge.service;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.laina.reforge.LainaReforgePlugin;
import pl.laina.reforge.rules.RecyclingRulesEngine;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PendingItemService {

    private final LainaReforgePlugin plugin;
    private final Map<String, Long> lastRecorded = new HashMap<>();
    private File file;
    private YamlConfiguration data;
    private boolean enabled;
    private long cooldownMillis;

    public PendingItemService(LainaReforgePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("discovery.enabled", true);
        cooldownMillis = Math.max(0L, plugin.getConfig().getLong("discovery.record-cooldown-seconds", 60L)) * 1000L;

        String fileName = plugin.getConfig().getString("discovery.file", "pending-items.yml");
        if (fileName == null || fileName.isBlank()) {
            fileName = "pending-items.yml";
        }

        file = new File(plugin.getDataFolder(), fileName);
        data = YamlConfiguration.loadConfiguration(file);
    }

    public boolean record(Player player, String itemId, ItemStack item) {
        if (!enabled || player == null || item == null || item.getType().isAir()) {
            return false;
        }

        String id = normalize(itemId);
        if (id.isBlank()) {
            return false;
        }

        long now = System.currentTimeMillis();
        Long previous = lastRecorded.get(id);
        if (previous != null && now - previous < cooldownMillis) {
            return false;
        }
        lastRecorded.put(id, now);

        String key = encodeKey(id);
        String path = "items." + key;
        boolean firstEver = !data.contains(path + ".id");

        data.set(path + ".id", id);
        data.set(path + ".material", item.getType().getKey().toString());
        data.set(path + ".last-seen", Instant.ofEpochMilli(now).toString());
        data.set(path + ".last-player.name", player.getName());
        data.set(path + ".last-player.uuid", player.getUniqueId().toString());
        data.set(path + ".sightings", data.getInt(path + ".sightings", 0) + 1);

        if (firstEver) {
            data.set(path + ".first-seen", Instant.ofEpochMilli(now).toString());
            plugin.getLogger().info("LainaReforge discovery: nowy nieskonfigurowany custom '" + id
                    + "' (" + item.getType().getKey() + ") od gracza " + player.getName());
        }

        save();
        return firstEver;
    }

    public int cleanupConfigured(RecyclingRulesEngine rulesEngine) {
        if (data == null) {
            return 0;
        }

        ConfigurationSection items = data.getConfigurationSection("items");
        if (items == null) {
            return 0;
        }

        int removed = 0;
        for (String key : new ArrayList<>(items.getKeys(false))) {
            String id = data.getString("items." + key + ".id", "");
            if (!id.isBlank() && rulesEngine.isConfigured(id)) {
                data.set("items." + key, null);
                lastRecorded.remove(normalize(id));
                removed++;
            }
        }

        if (removed > 0) {
            save();
        }
        return removed;
    }

    public List<PendingItemInfo> list(int limit) {
        ConfigurationSection items = data == null ? null : data.getConfigurationSection("items");
        if (items == null) {
            return List.of();
        }

        List<PendingItemInfo> result = new ArrayList<>();
        for (String key : items.getKeys(false)) {
            String path = "items." + key;
            String id = data.getString(path + ".id", "");
            if (id.isBlank()) {
                continue;
            }
            result.add(new PendingItemInfo(
                    id,
                    data.getString(path + ".material", "unknown"),
                    data.getInt(path + ".sightings", 0),
                    data.getString(path + ".last-player.name", "unknown"),
                    data.getString(path + ".last-seen", "unknown")
            ));
        }

        result.sort(Comparator.comparingInt(PendingItemInfo::sightings).reversed()
                .thenComparing(PendingItemInfo::id));
        if (limit > 0 && result.size() > limit) {
            return List.copyOf(result.subList(0, limit));
        }
        return List.copyOf(result);
    }

    public int count() {
        ConfigurationSection items = data == null ? null : data.getConfigurationSection("items");
        return items == null ? 0 : items.getKeys(false).size();
    }

    public boolean isEnabled() {
        return enabled;
    }

    private void save() {
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Nie udalo sie utworzyc katalogu pluginu dla discovery queue.");
                return;
            }
            data.save(file);
        } catch (IOException exception) {
            plugin.getLogger().severe("Nie udalo sie zapisac kolejki nowych customow: " + exception.getMessage());
        }
    }

    private String encodeKey(String id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(id.getBytes(StandardCharsets.UTF_8));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record PendingItemInfo(String id,
                                  String material,
                                  int sightings,
                                  String lastPlayer,
                                  String lastSeen) {
    }
}

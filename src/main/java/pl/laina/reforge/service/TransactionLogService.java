package pl.laina.reforge.service;

import org.bukkit.entity.Player;
import pl.laina.reforge.LainaReforgePlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

public final class TransactionLogService {

    private final LainaReforgePlugin plugin;

    public TransactionLogService(LainaReforgePlugin plugin) {
        this.plugin = plugin;
    }

    public void logRecycle(Player player, Map<String, Integer> items, int shards) {
        if (!plugin.getConfig().getBoolean("logging.transactions", true)) {
            return;
        }

        String itemSummary = items.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> sanitize(entry.getKey()) + "x" + entry.getValue())
                .collect(Collectors.joining(","));

        String line = Instant.now()
                + "\t" + player.getUniqueId()
                + "\t" + sanitize(player.getName())
                + "\tshards=" + Math.max(0, shards)
                + "\titems=" + itemSummary
                + System.lineSeparator();

        Path file = plugin.getDataFolder().toPath().resolve("recycling-transactions.log");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            plugin.getLogger().warning("Nie udalo sie zapisac transakcji recyclingu: " + exception.getMessage());
        }
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\t', '_').replace('\n', '_').replace('\r', '_').replace(',', '_');
    }
}

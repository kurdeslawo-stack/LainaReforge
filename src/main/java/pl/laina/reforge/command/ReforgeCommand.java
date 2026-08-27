package pl.laina.reforge.command;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pl.laina.reforge.LainaReforgePlugin;
import pl.laina.reforge.service.RecycleValueService;

import java.util.List;

public final class ReforgeCommand implements CommandExecutor, TabCompleter {

    private final LainaReforgePlugin plugin;
    private final RecycleValueService recycleValueService;

    public ReforgeCommand(LainaReforgePlugin plugin, RecycleValueService recycleValueService) {
        this.plugin = plugin;
        this.recycleValueService = recycleValueService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(color("&dLainaReforge &7- system recyclingu i reforgingu przedmiotow."));
            sender.sendMessage(color("&7Uzyj &f/reforge help &7aby zobaczyc dostepne komendy."));
            return true;
        }

        if (args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(color("&d/reforge &7- informacje o pluginie"));
            if (sender.hasPermission("lainareforge.admin")) {
                sender.sendMessage(color("&d/reforge reload &7- przeladowuje konfiguracje"));
                sender.sendMessage(color("&d/reforge value <id> &7- pokazuje wartosc recyclingu"));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("lainareforge.admin")) {
                sender.sendMessage(message("messages.no-permission", "&cNie masz uprawnien."));
                return true;
            }

            plugin.reloadPlugin();
            sender.sendMessage(message("messages.reloaded", "&aKonfiguracja zostala przeladowana."));
            return true;
        }

        if (args[0].equalsIgnoreCase("value")) {
            if (!sender.hasPermission("lainareforge.admin")) {
                sender.sendMessage(message("messages.no-permission", "&cNie masz uprawnien."));
                return true;
            }

            if (args.length < 2) {
                sender.sendMessage(color("&cUzycie: /reforge value <item_id>"));
                return true;
            }

            String itemId = args[1];
            int value = recycleValueService.getValue(itemId);
            sender.sendMessage(color("&7Wartosc &f" + itemId + "&7: &d" + value + " &7odlamkow."));
            return true;
        }

        sender.sendMessage(color("&cNieznana komenda. Uzyj /reforge help."));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            if (sender.hasPermission("lainareforge.admin")) {
                return List.of("help", "reload", "value").stream()
                        .filter(value -> value.startsWith(args[0].toLowerCase()))
                        .toList();
            }
            return List.of("help").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase()))
                    .toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("value") && sender.hasPermission("lainareforge.admin")) {
            return recycleValueService.getValues().keySet().stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase()))
                    .sorted()
                    .toList();
        }

        return List.of();
    }

    private String message(String path, String fallback) {
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        String text = plugin.getConfig().getString(path, fallback);
        return color(prefix + text);
    }

    @SuppressWarnings("deprecation")
    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}

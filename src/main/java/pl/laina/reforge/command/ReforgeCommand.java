package pl.laina.reforge.command;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pl.laina.reforge.LainaReforgePlugin;
import pl.laina.reforge.gui.RecyclerMenu;
import pl.laina.reforge.service.ItemIdentityService;
import pl.laina.reforge.service.RecycleValueService;

import java.util.List;

public final class ReforgeCommand implements CommandExecutor, TabCompleter {

    private final LainaReforgePlugin plugin;
    private final RecycleValueService recycleValueService;
    private final ItemIdentityService itemIdentityService;
    private final RecyclerMenu recyclerMenu;

    public ReforgeCommand(LainaReforgePlugin plugin,
                          RecycleValueService recycleValueService,
                          ItemIdentityService itemIdentityService,
                          RecyclerMenu recyclerMenu) {
        this.plugin = plugin;
        this.recycleValueService = recycleValueService;
        this.itemIdentityService = itemIdentityService;
        this.recyclerMenu = recyclerMenu;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                recyclerMenu.open(player);
            } else {
                sender.sendMessage(color("&dLainaReforge &7- uzyj /reforge help."));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(color("&d/reforge &7- otwiera recycler"));
            if (sender.hasPermission("lainareforge.admin")) {
                sender.sendMessage(color("&d/reforge reload &7- przeladowuje konfiguracje"));
                sender.sendMessage(color("&d/reforge value <id> &7- pokazuje wartosc recyclingu"));
                sender.sendMessage(color("&d/reforge inspect &7- pokazuje dane przedmiotu w rece"));
                if (itemIdentityService.isDevelopmentEnabled()) {
                    sender.sendMessage(color("&d/reforge devitem <id> &7- nadaje testowe ID przedmiotowi w rece"));
                    sender.sendMessage(color("&d/reforge devclear &7- usuwa testowe ID z przedmiotu"));
                }
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

        if (args[0].equalsIgnoreCase("inspect")) {
            if (!sender.hasPermission("lainareforge.admin")) {
                sender.sendMessage(message("messages.no-permission", "&cNie masz uprawnien."));
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(color("&cTa komenda wymaga gracza."));
                return true;
            }

            ItemStack item = player.getInventory().getItemInMainHand();
            sender.sendMessage(color("&d--- LainaReforge inspect ---"));
            itemIdentityService.inspect(item).forEach(line -> sender.sendMessage(color("&7" + line)));
            return true;
        }

        if (args[0].equalsIgnoreCase("devitem")) {
            if (!sender.hasPermission("lainareforge.admin")) {
                sender.sendMessage(message("messages.no-permission", "&cNie masz uprawnien."));
                return true;
            }
            if (!itemIdentityService.isDevelopmentEnabled()) {
                sender.sendMessage(color("&cTryb developerski jest wylaczony w config.yml."));
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(color("&cTa komenda wymaga gracza."));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(color("&cUzycie: /reforge devitem <item_id>"));
                return true;
            }

            ItemStack item = player.getInventory().getItemInMainHand();
            if (!itemIdentityService.applyDevId(item, args[1])) {
                sender.sendMessage(color("&cNie udalo sie nadac ID. Trzymaj normalny przedmiot w glownej rece."));
                return true;
            }

            int value = recycleValueService.getValue(args[1]);
            sender.sendMessage(color("&aNadano testowe ID &f" + args[1].toLowerCase() + "&a. Wartosc recyclingu: &d" + value + "&a."));
            return true;
        }

        if (args[0].equalsIgnoreCase("devclear")) {
            if (!sender.hasPermission("lainareforge.admin")) {
                sender.sendMessage(message("messages.no-permission", "&cNie masz uprawnien."));
                return true;
            }
            if (!itemIdentityService.isDevelopmentEnabled()) {
                sender.sendMessage(color("&cTryb developerski jest wylaczony w config.yml."));
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(color("&cTa komenda wymaga gracza."));
                return true;
            }

            ItemStack item = player.getInventory().getItemInMainHand();
            if (itemIdentityService.clearDevId(item)) {
                sender.sendMessage(color("&aUsunieto testowe ID z przedmiotu."));
            } else {
                sender.sendMessage(color("&eTen przedmiot nie ma testowego ID."));
            }
            return true;
        }

        sender.sendMessage(color("&cNieznana komenda. Uzyj /reforge help."));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options;
            if (sender.hasPermission("lainareforge.admin")) {
                options = itemIdentityService.isDevelopmentEnabled()
                        ? List.of("help", "inspect", "reload", "value", "devitem", "devclear")
                        : List.of("help", "inspect", "reload", "value");
            } else {
                options = List.of("help");
            }
            return options.stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase()))
                    .toList();
        }

        if (args.length == 2
                && (args[0].equalsIgnoreCase("value") || args[0].equalsIgnoreCase("devitem"))
                && sender.hasPermission("lainareforge.admin")) {
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

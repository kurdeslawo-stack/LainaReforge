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
import pl.laina.reforge.rules.ConfigurationIssue;
import pl.laina.reforge.rules.ConfigurationValidationReport;
import pl.laina.reforge.rules.RecyclingDecision;
import pl.laina.reforge.rules.RecyclingReasonText;
import pl.laina.reforge.rules.RecyclingRulesEngine;
import pl.laina.reforge.rules.RuleEvaluationInput;
import pl.laina.reforge.rules.RulesConfiguration;
import pl.laina.reforge.rules.RulesConfigurationCandidate;
import pl.laina.reforge.rules.RulesConfigurationService;
import pl.laina.reforge.service.ItemIdentityService;
import pl.laina.reforge.service.PendingItemService;

import java.util.List;
import java.util.Locale;

public final class ReforgeCommand implements CommandExecutor, TabCompleter {

    private final LainaReforgePlugin plugin;
    private final RecyclingRulesEngine rulesEngine;
    private final RulesConfigurationService configurationService;
    private final ItemIdentityService itemIdentityService;
    private final RecyclerMenu recyclerMenu;
    private final PendingItemService pendingItemService;

    public ReforgeCommand(LainaReforgePlugin plugin,
                          RecyclingRulesEngine rulesEngine,
                          RulesConfigurationService configurationService,
                          ItemIdentityService itemIdentityService,
                          RecyclerMenu recyclerMenu,
                          PendingItemService pendingItemService) {
        this.plugin = plugin;
        this.rulesEngine = rulesEngine;
        this.configurationService = configurationService;
        this.itemIdentityService = itemIdentityService;
        this.recyclerMenu = recyclerMenu;
        this.pendingItemService = pendingItemService;
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

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (subcommand.equals("help")) {
            sendHelp(sender);
            return true;
        }
        if (subcommand.equals("reload")) {
            return handleReload(sender, args);
        }
        if (subcommand.equals("why")) {
            return handleWhy(sender);
        }
        if (subcommand.equals("audit")) {
            return handleAudit(sender);
        }
        if (subcommand.equals("pending")) {
            return handlePending(sender);
        }
        if (subcommand.equals("value")) {
            return handleValue(sender, args);
        }
        if (subcommand.equals("inspect")) {
            return handleInspect(sender);
        }
        if (subcommand.equals("devitem")) {
            return handleDevItem(sender, args);
        }
        if (subcommand.equals("devclear")) {
            return handleDevClear(sender);
        }

        sender.sendMessage(color("&cNieznana komenda. Uzyj /reforge help."));
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(color("&d/reforge &7- otwiera recycler"));
        if (!sender.hasPermission("lainareforge.admin")) {
            return;
        }
        sender.sendMessage(color("&d/reforge why &7- wyjasnia decyzje dla itemu w rece"));
        sender.sendMessage(color("&d/reforge audit &7- podsumowuje Rules Engine"));
        sender.sendMessage(color("&d/reforge reload [--check] &7- sprawdza lub aktywuje konfiguracje"));
        sender.sendMessage(color("&d/reforge value <id> &7- pokazuje polityke dla technicznego ID"));
        sender.sendMessage(color("&d/reforge inspect &7- pokazuje dane diagnostyczne itemu"));
        sender.sendMessage(color("&d/reforge pending &7- pokazuje Discovery Queue"));
        if (itemIdentityService.isDevelopmentEnabled()) {
            sender.sendMessage(color("&d/reforge devitem <id> &7- nadaje testowe ID"));
            sender.sendMessage(color("&d/reforge devclear &7- usuwa testowe ID"));
        }
    }

    private boolean handleReload(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length > 2 || (args.length == 2 && !args[1].equalsIgnoreCase("--check"))) {
            sender.sendMessage(color("&cUzycie: /reforge reload [--check]"));
            return true;
        }

        if (args.length == 2) {
            RulesConfigurationCandidate candidate = configurationService.validateDisk();
            sender.sendMessage(color("&d--- Rules Engine config check ---"));
            sendValidationSummary(sender, candidate.report());
            sender.sendMessage(color("&7Zmiany nie zostaly aktywowane."));
            return true;
        }

        LainaReforgePlugin.ReloadResult result = plugin.reloadPlugin();
        if (result.activated()) {
            sender.sendMessage(message("messages.reloaded", "&aKonfiguracja zostala przeladowana."));
            sendValidationSummary(sender, result.report());
        } else {
            sender.sendMessage(color("&cKonfiguracja jest bledna. Zachowano ostatnia poprawna wersje."));
            sendValidationSummary(sender, result.report());
        }
        return true;
    }

    private boolean handleWhy(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cTa komenda wymaga gracza trzymajacego item."));
            return true;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        RecyclingDecision decision = rulesEngine.evaluate(item);
        sender.sendMessage(color("&d--- LainaReforge why ---"));
        sendDecision(sender, decision);
        return true;
    }

    private boolean handleAudit(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return true;
        }
        RulesConfiguration rules = rulesEngine.configuration();
        ConfigurationValidationReport lastCheck = configurationService.lastCheckReport();
        sender.sendMessage(color("&d--- LainaReforge Rules Engine audit ---"));
        sender.sendMessage(color("&7Skonfigurowane itemy: &f" + rules.configuredItemCount()));
        sender.sendMessage(color("&7Recyclable: &a" + rules.recyclableItemCount()));
        sender.sendMessage(color("&7Zablokowane: &c" + rules.blockedItemCount()));
        sender.sendMessage(color("&7Pending / nierozpoznane konfiguracje: &e" + pendingItemService.count()));
        sender.sendMessage(color("&7Bledy ostatniego sprawdzenia: &c" + lastCheck.errorCount()));
        sender.sendMessage(color("&7Ostrzezenia ostatniego sprawdzenia: &e" + lastCheck.warningCount()));
        sender.sendMessage(color("&7Aktywny snapshot: " + (rules.valid() ? "&aPOPRAWNY" : "&cFAIL-CLOSED")));
        return true;
    }

    private boolean handlePending(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (!pendingItemService.isEnabled()) {
            sender.sendMessage(color("&eDiscovery Queue jest wylaczona w config.yml."));
            return true;
        }
        List<PendingItemService.PendingItemInfo> pending = pendingItemService.list(10);
        sender.sendMessage(color("&d--- LainaReforge Discovery Queue ---"));
        sender.sendMessage(color("&7Oczekujace customy: &f" + pendingItemService.count()));
        if (pending.isEmpty()) {
            sender.sendMessage(color("&aBrak nowych itemow do sklasyfikowania."));
            return true;
        }
        for (PendingItemService.PendingItemInfo info : pending) {
            sender.sendMessage(color("&f" + info.id()
                    + " &8| &7material: &f" + info.material()
                    + " &8| &7wykrycia: &d" + info.sightings()
                    + " &8| &7ostatnio: &f" + info.lastPlayer()));
        }
        if (pendingItemService.count() > pending.size()) {
            sender.sendMessage(color("&8Pokazano 10 najczesciej wykrywanych itemow."));
        }
        return true;
    }

    private boolean handleValue(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(color("&cUzycie: /reforge value <item_id>"));
            return true;
        }
        RecyclingDecision decision = rulesEngine.evaluate(RuleEvaluationInput.identified(args[1]));
        sender.sendMessage(color("&d--- LainaReforge policy ---"));
        sendDecision(sender, decision);
        return true;
    }

    private boolean handleInspect(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cTa komenda wymaga gracza."));
            return true;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        sender.sendMessage(color("&d--- LainaReforge inspect ---"));
        itemIdentityService.inspect(item).forEach(line -> sender.sendMessage(color("&7" + line)));
        sender.sendMessage(color("&8Pelna decyzje bez ujawniania PDC pokazuje /reforge why."));
        return true;
    }

    private boolean handleDevItem(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
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
            sender.sendMessage(color("&cNie udalo sie nadac ID. Trzymaj normalny item w rece."));
            return true;
        }
        RecyclingDecision decision = rulesEngine.evaluate(item);
        sender.sendMessage(color("&aNadano testowe ID. Wynik: &f" + decision.reasonCode()));
        return true;
    }

    private boolean handleDevClear(CommandSender sender) {
        if (!requireAdmin(sender)) {
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
        sender.sendMessage(itemIdentityService.clearDevId(item)
                ? color("&aUsunieto testowe ID z itemu.")
                : color("&eTen item nie ma testowego ID."));
        return true;
    }

    private void sendDecision(CommandSender sender, RecyclingDecision decision) {
        sender.sendMessage(color("&7Rozpoznany: " + (decision.recognized() ? "&aTAK" : "&cNIE")));
        sender.sendMessage(color("&7ID: &f" + displayOrMissing(decision.technicalId())));
        sender.sendMessage(color("&7Kategoria: &f" + displayOrMissing(decision.category())));
        sender.sendMessage(color("&7Tier: &f" + displayOrMissing(decision.tier())));
        sender.sendMessage(color("&7Recycling: " + (decision.recyclable() ? "&aDOZWOLONY" : "&cZABLOKOWANY")));
        sender.sendMessage(color("&7Wartosc: &d" + (decision.recyclable() ? decision.shardValue() : 0)
                + " &7odlamkow"));
        sender.sendMessage(color("&7Reason code: &f" + decision.reasonCode()));
        sender.sendMessage(color("&7Zrodlo reguly: &f" + decision.ruleSource()));
        sender.sendMessage(color("&7Wyjasnienie: &f" + RecyclingReasonText.describe(decision)));
    }

    private void sendValidationSummary(CommandSender sender, ConfigurationValidationReport report) {
        sender.sendMessage(color("&7Poprawna: " + (report.isValid() ? "&aTAK" : "&cNIE")));
        sender.sendMessage(color("&7Bledy: &c" + report.errorCount()
                + " &8| &7ostrzezenia: &e" + report.warningCount()));
        report.issues().stream().limit(5).forEach(issue -> {
            String color = issue.severity() == ConfigurationIssue.Severity.ERROR ? "&c" : "&e";
            sender.sendMessage(color(color + "- [" + issue.code() + "] " + issue.path() + ": " + issue.message()));
        });
        if (report.issues().size() > 5) {
            sender.sendMessage(color("&8...oraz " + (report.issues().size() - 5) + " kolejnych problemow."));
        }
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission("lainareforge.admin")) {
            return true;
        }
        sender.sendMessage(message("messages.no-permission", "&cNie masz uprawnien."));
        return false;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options;
            if (sender.hasPermission("lainareforge.admin")) {
                options = itemIdentityService.isDevelopmentEnabled()
                        ? List.of("help", "why", "audit", "inspect", "pending", "reload", "value", "devitem", "devclear")
                        : List.of("help", "why", "audit", "inspect", "pending", "reload", "value");
            } else {
                options = List.of("help");
            }
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return options.stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reload")
                && sender.hasPermission("lainareforge.admin")) {
            return "--check".startsWith(args[1].toLowerCase(Locale.ROOT)) ? List.of("--check") : List.of();
        }
        if (args.length == 2
                && (args[0].equalsIgnoreCase("value") || args[0].equalsIgnoreCase("devitem"))
                && sender.hasPermission("lainareforge.admin")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return rulesEngine.configuration().configuredItemIds().stream()
                    .filter(value -> value.startsWith(prefix)).sorted().toList();
        }
        return List.of();
    }

    private String displayOrMissing(String value) {
        return value == null || value.isBlank() ? "brak" : value;
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

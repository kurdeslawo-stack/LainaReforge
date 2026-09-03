package pl.laina.reforge;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.plugin.java.JavaPlugin;
import pl.laina.reforge.command.ReforgeCommand;
import pl.laina.reforge.gui.RecyclerMenu;
import pl.laina.reforge.listener.RecyclerListener;
import pl.laina.reforge.rules.ConfigurationIssue;
import pl.laina.reforge.rules.ConfigurationValidationReport;
import pl.laina.reforge.rules.RecyclingRulesEngine;
import pl.laina.reforge.rules.RulesConfigurationCandidate;
import pl.laina.reforge.rules.RulesConfigurationService;
import pl.laina.reforge.rules.RulesConfigurationValidator;
import pl.laina.reforge.runtime.ApprovedRecyclingRegistryLoader;
import pl.laina.reforge.service.CurrencyService;
import pl.laina.reforge.service.ItemIdentityBridge;
import pl.laina.reforge.service.ItemIdentityService;
import pl.laina.reforge.service.PendingItemService;
import pl.laina.reforge.service.TransactionLogService;

import java.nio.file.Path;
import java.util.List;

public final class LainaReforgePlugin extends JavaPlugin {

    private RulesConfigurationService rulesConfigurationService;
    private RecyclingRulesEngine rulesEngine;
    private ApprovedRecyclingRegistryLoader approvedRegistryLoader;
    private ItemIdentityService itemIdentityService;
    private CurrencyService currencyService;
    private TransactionLogService transactionLogService;
    private PendingItemService pendingItemService;
    private RecyclerMenu recyclerMenu;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        itemIdentityService = new ItemIdentityService(this);
        currencyService = new CurrencyService(this);
        transactionLogService = new TransactionLogService(this);
        pendingItemService = new PendingItemService(this);
        Path runtimePath = runtimeConfigPath();
        if (!runtimePath.toFile().exists()) {
            saveResource("recycling-runtime.yml", false);
        }
        approvedRegistryLoader = new ApprovedRecyclingRegistryLoader();
        ApprovedRecyclingRegistryLoader.ReloadResult initialRegistry =
                approvedRegistryLoader.reload(runtimePath);
        logRegistryResult("start", initialRegistry);

        rulesConfigurationService = new RulesConfigurationService(this);
        RulesConfigurationCandidate initial = rulesConfigurationService.loadInitial();
        logValidationReport("start", initial.report());
        rulesEngine = new RecyclingRulesEngine(
                new ItemIdentityBridge(itemIdentityService),
                currencyService,
                rulesConfigurationService.activeConfiguration(),
                approvedRegistryLoader);
        pendingItemService.cleanupConfigured(rulesEngine);

        recyclerMenu = new RecyclerMenu(
                this,
                rulesEngine,
                currencyService,
                transactionLogService,
                pendingItemService
        );

        ReforgeCommand command = new ReforgeCommand(
                this,
                rulesEngine,
                rulesConfigurationService,
                itemIdentityService,
                recyclerMenu,
                pendingItemService
        );
        if (getCommand("reforge") != null) {
            getCommand("reforge").setExecutor(command);
            getCommand("reforge").setTabCompleter(command);
        }

        getServer().getPluginManager().registerEvents(new RecyclerListener(recyclerMenu), this);
        getLogger().info("LainaReforge uruchomiony. Wersja: " + getPluginMeta().getVersion());
    }

    @Override
    public void onDisable() {
        if (recyclerMenu != null) {
            int restoredMenus = recyclerMenu.closeOpenMenus();
            if (restoredMenus > 0) {
                getLogger().info("Zwrocono przedmioty z " + restoredMenus
                        + " otwartych recyclerow przed wylaczeniem pluginu.");
            }
        }
        getLogger().info("LainaReforge wylaczony.");
    }

    public ReloadResult reloadPlugin() {
        RulesConfigurationCandidate diskCandidate = rulesConfigurationService.validateDisk();
        logValidationReport("reload", diskCandidate.report());
        if (!diskCandidate.report().isValid()) {
            return new ReloadResult(false, diskCandidate.report(), 0, List.of());
        }
        ApprovedRecyclingRegistryLoader.Candidate registryCandidate = validateRuntimeRegistry();
        if (!registryCandidate.valid()) {
            logRegistryErrors("reload", registryCandidate.errors());
            return new ReloadResult(false, diskCandidate.report(), 0, registryCandidate.errors());
        }

        // Revalidate the exact Bukkit instance to close the file-change window between checks.
        String previousConfiguration = getConfig().saveToString();
        reloadConfig();
        RulesConfigurationCandidate loadedCandidate = RulesConfigurationValidator.validate(getConfig());
        if (!loadedCandidate.report().isValid()) {
            try {
                getConfig().loadFromString(previousConfiguration);
            } catch (InvalidConfigurationException impossible) {
                getLogger().severe("Nie udalo sie odtworzyc poprzedniej konfiguracji w pamieci: "
                        + impossible.getMessage());
            }
            logValidationReport("reload-race-check", loadedCandidate.report());
            return new ReloadResult(false, loadedCandidate.report(), 0, List.of());
        }

        rulesEngine.activate(loadedCandidate.configuration());
        rulesConfigurationService.activate(loadedCandidate);
        ApprovedRecyclingRegistryLoader.ReloadResult registryResult =
                approvedRegistryLoader.activate(registryCandidate);
        logRegistryResult("reload", registryResult);
        itemIdentityService.reload();
        pendingItemService.reload();
        int cleaned = pendingItemService.cleanupConfigured(rulesEngine);
        if (cleaned > 0) {
            getLogger().info("Discovery queue: usunieto " + cleaned
                    + " itemow, ktore sa juz skonfigurowane.");
        }
        return new ReloadResult(true, loadedCandidate.report(), cleaned, List.of());
    }

    public ApprovedRecyclingRegistryLoader.Candidate validateRuntimeRegistry() {
        return approvedRegistryLoader.validate(runtimeConfigPath());
    }

    private Path runtimeConfigPath() {
        return getDataFolder().toPath().resolve("recycling-runtime.yml");
    }

    private void logRegistryResult(String operation, ApprovedRecyclingRegistryLoader.ReloadResult result) {
        if (result.activated()) {
            getLogger().info("Approved decisions registry (" + operation + "): aktywowano "
                    + result.activeIdentities() + " identities.");
        } else {
            logRegistryErrors(operation, result.errors());
            getLogger().severe("Approved decisions registry: zachowano last-known-good; bez poprawnego "
                    + "snapshotu wszystkie itemy sa blokowane.");
        }
    }

    private void logRegistryErrors(String operation, List<String> errors) {
        for (String error : errors) {
            getLogger().severe("Approved decisions registry (" + operation + "): " + error);
        }
    }

    private void logValidationReport(String operation, ConfigurationValidationReport report) {
        if (report.issues().isEmpty()) {
            getLogger().info("Rules Engine config (" + operation + "): poprawna.");
            return;
        }
        for (ConfigurationIssue issue : report.issues()) {
            String line = "Rules Engine config [" + issue.code() + "] " + issue.path() + ": " + issue.message();
            if (issue.severity() == ConfigurationIssue.Severity.ERROR) {
                getLogger().severe(line);
            } else {
                getLogger().warning(line);
            }
        }
    }

    public record ReloadResult(boolean activated,
                               ConfigurationValidationReport report,
                               int cleanedPendingItems,
                               List<String> runtimeErrors) {
        public ReloadResult {
            runtimeErrors = List.copyOf(runtimeErrors);
        }
    }
}

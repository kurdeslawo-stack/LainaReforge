package pl.laina.reforge;

import org.bukkit.plugin.java.JavaPlugin;
import pl.laina.reforge.command.ReforgeCommand;
import pl.laina.reforge.gui.RecyclerMenu;
import pl.laina.reforge.listener.RecyclerListener;
import pl.laina.reforge.service.CurrencyService;
import pl.laina.reforge.service.ItemIdentityService;
import pl.laina.reforge.service.PendingItemService;
import pl.laina.reforge.service.RecycleValueService;
import pl.laina.reforge.service.TransactionLogService;

public final class LainaReforgePlugin extends JavaPlugin {

    private RecycleValueService recycleValueService;
    private ItemIdentityService itemIdentityService;
    private CurrencyService currencyService;
    private TransactionLogService transactionLogService;
    private PendingItemService pendingItemService;
    private RecyclerMenu recyclerMenu;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        recycleValueService = new RecycleValueService(this);
        itemIdentityService = new ItemIdentityService(this);
        currencyService = new CurrencyService(this);
        transactionLogService = new TransactionLogService(this);
        pendingItemService = new PendingItemService(this);
        pendingItemService.cleanupConfigured(recycleValueService);

        recyclerMenu = new RecyclerMenu(
                this,
                itemIdentityService,
                recycleValueService,
                currencyService,
                transactionLogService,
                pendingItemService
        );

        ReforgeCommand command = new ReforgeCommand(
                this,
                recycleValueService,
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
        getLogger().info("LainaReforge wylaczony.");
    }

    public void reloadPlugin() {
        reloadConfig();
        recycleValueService.reload();
        itemIdentityService.reload();
        pendingItemService.reload();
        int cleaned = pendingItemService.cleanupConfigured(recycleValueService);
        if (cleaned > 0) {
            getLogger().info("Discovery queue: usunieto " + cleaned + " itemow, ktore sa juz skonfigurowane.");
        }
    }
}

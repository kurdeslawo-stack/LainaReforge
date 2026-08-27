package pl.laina.reforge;

import org.bukkit.plugin.java.JavaPlugin;
import pl.laina.reforge.command.ReforgeCommand;
import pl.laina.reforge.service.RecycleValueService;

public final class LainaReforgePlugin extends JavaPlugin {

    private RecycleValueService recycleValueService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        recycleValueService = new RecycleValueService(this);

        ReforgeCommand command = new ReforgeCommand(this, recycleValueService);
        if (getCommand("reforge") != null) {
            getCommand("reforge").setExecutor(command);
            getCommand("reforge").setTabCompleter(command);
        }

        getLogger().info("LainaReforge uruchomiony. Wersja: " + getPluginMeta().getVersion());
    }

    @Override
    public void onDisable() {
        getLogger().info("LainaReforge wylaczony.");
    }

    public void reloadPlugin() {
        reloadConfig();
        recycleValueService.reload();
    }
}

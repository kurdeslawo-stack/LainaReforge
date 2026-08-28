package pl.laina.reforge.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.laina.reforge.LainaReforgePlugin;
import pl.laina.reforge.service.CurrencyService;
import pl.laina.reforge.service.ItemIdentityService;
import pl.laina.reforge.service.RecycleValueService;
import pl.laina.reforge.service.TransactionLogService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RecyclerMenu {

    public static final int SIZE = 54;
    public static final int INPUT_MAX_SLOT = 44;
    public static final int INFO_SLOT = 45;
    public static final int CONFIRM_SLOT = 49;
    public static final int CANCEL_SLOT = 53;

    private final LainaReforgePlugin plugin;
    private final ItemIdentityService itemIdentityService;
    private final RecycleValueService recycleValueService;
    private final CurrencyService currencyService;
    private final TransactionLogService transactionLogService;

    public RecyclerMenu(LainaReforgePlugin plugin,
                        ItemIdentityService itemIdentityService,
                        RecycleValueService recycleValueService,
                        CurrencyService currencyService,
                        TransactionLogService transactionLogService) {
        this.plugin = plugin;
        this.itemIdentityService = itemIdentityService;
        this.recycleValueService = recycleValueService;
        this.currencyService = currencyService;
        this.transactionLogService = transactionLogService;
    }

    public void open(Player player) {
        RecyclerHolder holder = new RecyclerHolder();
        String title = plugin.getConfig().getString("gui.title", "LainaReforge - Recykling");
        Inventory inventory = Bukkit.createInventory(holder, SIZE, Component.text(title));
        holder.attach(inventory);
        decorate(inventory);
        player.openInventory(inventory);
    }

    public void decorate(Inventory inventory) {
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.DARK_GRAY);
        for (int slot = 45; slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }

        inventory.setItem(CONFIRM_SLOT, named(Material.LIME_DYE,
                "Przetop przedmioty", NamedTextColor.GREEN));
        inventory.setItem(CANCEL_SLOT, named(Material.BARRIER,
                "Anuluj", NamedTextColor.RED));
        updatePreview(inventory);
    }

    public void queuePreviewRefresh(Inventory inventory) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (inventory.getHolder() instanceof RecyclerHolder) {
                updatePreview(inventory);
            }
        });
    }

    public void updatePreview(Inventory inventory) {
        RecycleResult result = calculate(inventory);
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta meta = info.getItemMeta();
        meta.displayName(Component.text("Podglad przetopienia", NamedTextColor.AQUA));

        List<Component> lore = new ArrayList<>();
        if (result.itemAmounts().isEmpty() && result.invalidItems().isEmpty()) {
            lore.add(Component.text("Wrzuc customowe przedmioty do gornych 5 rzedow.", NamedTextColor.GRAY));
        } else {
            lore.add(Component.text("Wartosc: " + result.totalShards() + " odlamkow", NamedTextColor.LIGHT_PURPLE));
            lore.add(Component.text("Rozpoznane typy: " + result.itemAmounts().size(), NamedTextColor.GRAY));
            if (!result.invalidItems().isEmpty()) {
                lore.add(Component.text("Zablokowane:", NamedTextColor.RED));
                for (String invalid : result.invalidItems()) {
                    lore.add(Component.text("- " + invalid, NamedTextColor.RED));
                }
            } else {
                lore.add(Component.text("Wszystkie przedmioty sa poprawne.", NamedTextColor.GREEN));
            }
        }

        meta.lore(lore);
        info.setItemMeta(meta);
        inventory.setItem(INFO_SLOT, info);
    }

    public RecycleResult calculate(Inventory inventory) {
        long totalShards = 0;
        int stacks = 0;
        Set<String> invalid = new LinkedHashSet<>();
        Map<String, Integer> itemAmounts = new LinkedHashMap<>();

        for (int slot = 0; slot <= INPUT_MAX_SLOT; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }

            if (currencyService.isPluginCurrency(item)) {
                invalid.add("waluta LainaReforge - nie mozna przetopic");
                continue;
            }

            Optional<String> id = itemIdentityService.identify(item);
            if (id.isEmpty()) {
                invalid.add(item.getType().getKey() + " - nierozpoznany custom");
                continue;
            }

            Optional<String> rejection = recycleValueService.getRejectionReason(id.get());
            if (rejection.isPresent()) {
                invalid.add(id.get() + " - " + rejection.get());
                continue;
            }

            int value = recycleValueService.getValue(id.get());
            totalShards += (long) value * item.getAmount();
            itemAmounts.merge(id.get(), item.getAmount(), Integer::sum);
            stacks++;
        }

        int safeTotal = totalShards > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalShards;
        return new RecycleResult(safeTotal, stacks, List.copyOf(invalid), Map.copyOf(itemAmounts));
    }

    public boolean confirm(Player player, Inventory inventory) {
        RecycleResult result = calculate(inventory);

        if (!result.invalidItems().isEmpty()) {
            String prefix = plugin.getConfig().getString("messages.prefix", "");
            player.sendMessage(Component.text(stripLegacy(prefix))
                    .append(Component.text("Nie mozna przetopic: " + String.join(", ", result.invalidItems()), NamedTextColor.RED)));
            updatePreview(inventory);
            return false;
        }

        if (result.totalShards() <= 0 || result.itemAmounts().isEmpty()) {
            player.sendMessage(Component.text("Najpierw wrzuc przedmioty do recyclingu.", NamedTextColor.RED));
            return false;
        }

        // RecycleResult zostal policzony bezposrednio przed usunieciem itemow.
        // Dopiero teraz czyscimy input i wyplacamy walute.
        for (int slot = 0; slot <= INPUT_MAX_SLOT; slot++) {
            inventory.setItem(slot, null);
        }

        currencyService.giveShards(player, result.totalShards());
        transactionLogService.logRecycle(player, result.itemAmounts(), result.totalShards());
        player.sendMessage(Component.text("Przetopiono przedmioty. Otrzymano "
                + result.totalShards() + " odlamkow.", NamedTextColor.GREEN));
        player.closeInventory();
        return true;
    }

    public void returnItems(Player player, Inventory inventory) {
        for (int slot = 0; slot <= INPUT_MAX_SLOT; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }

            inventory.setItem(slot, null);
            player.getInventory().addItem(item).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
    }

    private ItemStack named(Material material, String name, NamedTextColor color) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color));
        item.setItemMeta(meta);
        return item;
    }

    private String stripLegacy(String text) {
        return text == null ? "" : text.replaceAll("(?i)&[0-9A-FK-ORX]", "");
    }

    public record RecycleResult(int totalShards,
                                int stacks,
                                List<String> invalidItems,
                                Map<String, Integer> itemAmounts) {
    }
}

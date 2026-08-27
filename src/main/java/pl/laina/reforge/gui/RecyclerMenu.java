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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    public RecyclerMenu(LainaReforgePlugin plugin,
                        ItemIdentityService itemIdentityService,
                        RecycleValueService recycleValueService,
                        CurrencyService currencyService) {
        this.plugin = plugin;
        this.itemIdentityService = itemIdentityService;
        this.recycleValueService = recycleValueService;
        this.currencyService = currencyService;
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

        inventory.setItem(INFO_SLOT, named(Material.PAPER,
                "Wrzuc customowe przedmioty do gornych 5 rzedow", NamedTextColor.AQUA));
        inventory.setItem(CONFIRM_SLOT, named(Material.LIME_DYE,
                "Przetop przedmioty", NamedTextColor.GREEN));
        inventory.setItem(CANCEL_SLOT, named(Material.BARRIER,
                "Anuluj", NamedTextColor.RED));
    }

    public RecycleResult calculate(Inventory inventory) {
        int totalShards = 0;
        int stacks = 0;
        List<String> invalid = new ArrayList<>();

        for (int slot = 0; slot <= INPUT_MAX_SLOT; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }

            Optional<String> id = itemIdentityService.identify(item);
            if (id.isEmpty()) {
                invalid.add(item.getType().getKey().toString());
                continue;
            }

            int value = recycleValueService.getValue(id.get());
            if (value <= 0 || recycleValueService.isBlacklisted(id.get())) {
                invalid.add(id.get());
                continue;
            }

            totalShards += value * item.getAmount();
            stacks++;
        }

        return new RecycleResult(totalShards, stacks, invalid);
    }

    public boolean confirm(Player player, Inventory inventory) {
        RecycleResult result = calculate(inventory);

        if (!result.invalidItems().isEmpty()) {
            String prefix = plugin.getConfig().getString("messages.prefix", "");
            player.sendMessage(Component.text(stripLegacy(prefix))
                    .append(Component.text("Nie mozna przetopic: " + String.join(", ", result.invalidItems()), NamedTextColor.RED)));
            return false;
        }

        if (result.totalShards() <= 0) {
            player.sendMessage(Component.text("Najpierw wrzuc przedmioty do recyclingu.", NamedTextColor.RED));
            return false;
        }

        for (int slot = 0; slot <= INPUT_MAX_SLOT; slot++) {
            inventory.setItem(slot, null);
        }

        currencyService.giveShards(player, result.totalShards());
        player.sendMessage(Component.text("Przetopiono przedmioty. Otrzymano " + result.totalShards() + " odlamkow.", NamedTextColor.GREEN));
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

    public record RecycleResult(int totalShards, int stacks, List<String> invalidItems) {
    }
}

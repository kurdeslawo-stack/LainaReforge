package pl.laina.reforge.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import pl.laina.reforge.gui.RecyclerHolder;
import pl.laina.reforge.gui.RecyclerMenu;

public final class RecyclerListener implements Listener {

    private final RecyclerMenu recyclerMenu;

    public RecyclerListener(RecyclerMenu recyclerMenu) {
        this.recyclerMenu = recyclerMenu;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof RecyclerHolder)) {
            return;
        }

        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }

        if (event.isShiftClick()) {
            event.setCancelled(true);
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot < 0) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (rawSlot >= top.getSize()) {
            return;
        }

        if (rawSlot > RecyclerMenu.INPUT_MAX_SLOT) {
            event.setCancelled(true);
        }

        if (rawSlot == RecyclerMenu.CONFIRM_SLOT) {
            recyclerMenu.confirm(player, top);
            return;
        }

        if (rawSlot == RecyclerMenu.CANCEL_SLOT) {
            player.closeInventory();
            return;
        }

        if (rawSlot <= RecyclerMenu.INPUT_MAX_SLOT) {
            recyclerMenu.queuePreviewRefresh(top);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof RecyclerHolder)) {
            return;
        }

        boolean touchesInput = false;
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < top.getSize() && rawSlot > RecyclerMenu.INPUT_MAX_SLOT) {
                event.setCancelled(true);
                return;
            }
            if (rawSlot <= RecyclerMenu.INPUT_MAX_SLOT) {
                touchesInput = true;
            }
        }

        if (touchesInput) {
            recyclerMenu.queuePreviewRefresh(top);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory top = event.getInventory();
        if (!(top.getHolder() instanceof RecyclerHolder)) {
            return;
        }

        if (event.getPlayer() instanceof Player player) {
            recyclerMenu.returnItems(player, top);
        }
    }
}

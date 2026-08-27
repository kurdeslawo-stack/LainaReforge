package pl.laina.reforge.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class RecyclerHolder implements InventoryHolder {

    private Inventory inventory;

    public void attach(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("Recycler inventory has not been attached yet");
        }
        return inventory;
    }
}

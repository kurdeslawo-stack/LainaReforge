package pl.laina.reforge.service;

import org.bukkit.inventory.ItemStack;
import pl.laina.reforge.runtime.RuntimeItemIdentity;

import java.util.Optional;

/** Replaceable identity boundary for PDC, a future Laina API, or another stable identifier. */
public interface ItemIdentifier {
    Optional<String> identify(ItemStack item);

    default Optional<RuntimeItemIdentity> identifyRuntime(ItemStack item) {
        return Optional.empty();
    }
}

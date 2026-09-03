package pl.laina.reforge.runtime;

import org.bukkit.inventory.ItemStack;

import java.util.Optional;

@FunctionalInterface
public interface RuntimeItemIdentityResolver {
    Optional<RuntimeItemIdentity> identifyRuntime(ItemStack item);
}

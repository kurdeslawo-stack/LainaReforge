package pl.laina.reforge.service;

import org.bukkit.inventory.ItemStack;
import pl.laina.reforge.runtime.RuntimeItemIdentity;

import java.util.Objects;
import java.util.Optional;

/** Adapter keeping the existing identity implementation behind a replaceable contract. */
public final class ItemIdentityBridge implements ItemIdentifier {

    private final ItemIdentityService delegate;

    public ItemIdentityBridge(ItemIdentityService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Optional<String> identify(ItemStack item) {
        return delegate.identify(item);
    }

    @Override
    public Optional<RuntimeItemIdentity> identifyRuntime(ItemStack item) {
        return delegate.identifyRuntime(item);
    }
}

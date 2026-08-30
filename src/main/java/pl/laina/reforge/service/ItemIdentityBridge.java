package pl.laina.reforge.service;

import org.bukkit.inventory.ItemStack;

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
}

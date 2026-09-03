package pl.laina.reforge.runtime;

import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Immutable, validated economic decision snapshot. */
public final class ApprovedRecyclingRegistry {
    private final Map<RuntimeItemIdentity, Entry> entries;

    public ApprovedRecyclingRegistry(Map<RuntimeItemIdentity, Entry> entries) {
        this.entries = Collections.unmodifiableMap(new TreeMap<>(entries));
    }

    public static ApprovedRecyclingRegistry empty() {
        return new ApprovedRecyclingRegistry(Map.of());
    }

    public RecyclingLookupResult lookup(RuntimeItemIdentity identity) {
        if (identity == null) {
            return invalidIdentity();
        }
        Entry entry = entries.get(identity);
        if (entry == null) {
            return new RecyclingLookupResult(
                    RecyclingLookupResult.Status.NOT_CONFIGURED, 0, "", "");
        }
        return entry.recyclable()
                ? new RecyclingLookupResult(RecyclingLookupResult.Status.APPROVED,
                        entry.shards(), entry.sourceItem(), entry.modelPath())
                : new RecyclingLookupResult(RecyclingLookupResult.Status.REJECTED,
                        0, entry.sourceItem(), entry.modelPath());
    }

    public RecyclingLookupResult lookup(ItemStack item, RuntimeItemIdentityResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        Optional<RuntimeItemIdentity> identity = resolver.identifyRuntime(item);
        return identity.map(this::lookup).orElseGet(ApprovedRecyclingRegistry::invalidIdentity);
    }

    private static RecyclingLookupResult invalidIdentity() {
        return new RecyclingLookupResult(RecyclingLookupResult.Status.INVALID_IDENTITY, 0, "", "");
    }

    public int size() {
        return entries.size();
    }

    public Map<RuntimeItemIdentity, Entry> entries() {
        return entries;
    }

    public record Entry(boolean recyclable, int shards, String sourceItem, String modelPath) {
        public Entry {
            sourceItem = Objects.requireNonNull(sourceItem, "sourceItem").trim();
            modelPath = Objects.requireNonNull(modelPath, "modelPath").trim();
            if (sourceItem.isEmpty() || modelPath.isEmpty()) {
                throw new IllegalArgumentException("Runtime entry requires source_item and model_path");
            }
            if (recyclable && shards <= 0) {
                throw new IllegalArgumentException("Recyclable entry requires positive shards");
            }
            if (recyclable && shards > RecyclingSafetyLimits.MAX_SHARDS_PER_ITEM) {
                throw new IllegalArgumentException("Recyclable entry exceeds max shards per item ("
                        + RecyclingSafetyLimits.MAX_SHARDS_PER_ITEM + ")");
            }
            if (!recyclable && shards != 0) {
                throw new IllegalArgumentException("Rejected entry requires shards=0");
            }
        }
    }
}

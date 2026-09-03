package pl.laina.reforge.runtime;

import java.util.Objects;

public record RecyclingLookupResult(
        Status status,
        int shards,
        String sourceItem,
        String modelPath
) {
    public RecyclingLookupResult {
        status = Objects.requireNonNull(status, "status");
        sourceItem = Objects.requireNonNullElse(sourceItem, "");
        modelPath = Objects.requireNonNullElse(modelPath, "");
        if (status == Status.APPROVED && shards <= 0) {
            throw new IllegalArgumentException("APPROVED lookup requires positive shards");
        }
        if (status != Status.APPROVED && shards != 0) {
            throw new IllegalArgumentException("Blocked lookup cannot expose shards");
        }
    }

    public boolean recyclable() {
        return status == Status.APPROVED;
    }

    public enum Status {
        APPROVED,
        REJECTED,
        NOT_CONFIGURED,
        INVALID_IDENTITY
    }
}

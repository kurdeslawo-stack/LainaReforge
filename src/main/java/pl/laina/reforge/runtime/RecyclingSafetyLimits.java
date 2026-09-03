package pl.laina.reforge.runtime;

/** Technical guardrails against accidental main-thread item floods. */
public final class RecyclingSafetyLimits {
    public static final int MAX_SHARDS_PER_ITEM = 256;
    public static final int MAX_SHARDS_PER_TRANSACTION = 4096;

    private RecyclingSafetyLimits() {
    }
}

package pl.laina.reforge.rules;

/** The exact rule layer that produced a recycling decision. */
public enum RecyclingRuleSource {
    INPUT,
    LAINAREFORGE_CURRENCY_PDC,
    IDENTITY_PROVIDER,
    DISCOVERY_QUEUE,
    ITEM_BLACKLIST,
    ITEM_OVERRIDE,
    CATEGORY_BLOCKLIST,
    CATEGORY_POLICY,
    ITEM_SHARD_VALUE,
    TIER_VALUE,
    APPROVED_DECISIONS_REGISTRY,
    CONFIGURATION_FAIL_CLOSED,
    TRANSACTION_SAFETY
}

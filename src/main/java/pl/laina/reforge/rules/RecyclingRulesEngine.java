package pl.laina.reforge.rules;

import org.bukkit.inventory.ItemStack;
import pl.laina.reforge.service.CurrencyService;
import pl.laina.reforge.service.ItemIdentifier;

import java.util.Objects;

/** The only component allowed to decide whether an item may be destroyed for shards. */
public final class RecyclingRulesEngine {

    private final ItemIdentifier itemIdentifier;
    private final CurrencyService currencyService;
    private volatile RulesConfiguration configuration;

    public RecyclingRulesEngine(ItemIdentifier itemIdentifier,
                                CurrencyService currencyService,
                                RulesConfiguration initialConfiguration) {
        this.itemIdentifier = Objects.requireNonNull(itemIdentifier, "itemIdentifier");
        this.currencyService = Objects.requireNonNull(currencyService, "currencyService");
        this.configuration = Objects.requireNonNull(initialConfiguration, "initialConfiguration");
    }

    /** Constructor for pure policy tests and non-Bukkit tooling. */
    public RecyclingRulesEngine(RulesConfiguration initialConfiguration) {
        this.itemIdentifier = null;
        this.currencyService = null;
        this.configuration = Objects.requireNonNull(initialConfiguration, "initialConfiguration");
    }

    public void activate(RulesConfiguration validatedConfiguration) {
        Objects.requireNonNull(validatedConfiguration, "validatedConfiguration");
        if (!validatedConfiguration.valid()) {
            throw new IllegalArgumentException("Cannot activate an invalid rules configuration");
        }
        configuration = validatedConfiguration;
    }

    public RecyclingDecision evaluate(ItemStack item) {
        if (itemIdentifier == null || currencyService == null) {
            throw new IllegalStateException("ItemStack evaluation requires the Bukkit identity/currency adapter");
        }
        if (item == null || item.getType().isAir()) {
            return evaluate(RuleEvaluationInput.empty());
        }
        String currencyType = currencyService.getCurrencyType(item).orElse("");
        if (!currencyType.isBlank()) {
            return evaluate(RuleEvaluationInput.currency(currencyType, ""));
        }
        return evaluate(itemIdentifier.identify(item)
                .map(RuleEvaluationInput::identified)
                .orElseGet(RuleEvaluationInput::unidentified));
    }

    /** Pure policy entry point used by tests and non-Bukkit diagnostics. */
    public RecyclingDecision evaluate(RuleEvaluationInput input) {
        Objects.requireNonNull(input, "input");
        if (!input.itemPresent()) {
            return blocked(false, "", "", "", RecyclingReasonCode.BLOCKED_NO_ITEM,
                    RecyclingRuleSource.INPUT);
        }
        if (input.pluginCurrency()) {
            String type = RulesConfiguration.normalize(input.currencyType());
            String id = "lainareforge:currency/" + (type.isBlank() ? "unknown" : type);
            return blocked(true, id, "currency", "", RecyclingReasonCode.BLOCKED_PLUGIN_CURRENCY,
                    RecyclingRuleSource.LAINAREFORGE_CURRENCY_PDC);
        }

        String id = RulesConfiguration.normalize(input.technicalId());
        if (id.isBlank()) {
            return blocked(false, "", "", "", RecyclingReasonCode.BLOCKED_UNRECOGNIZED,
                    RecyclingRuleSource.IDENTITY_PROVIDER);
        }

        RulesConfiguration snapshot = configuration;
        if (!snapshot.valid()) {
            return blocked(true, id, "", "", RecyclingReasonCode.BLOCKED_INVALID_CONFIGURATION,
                    RecyclingRuleSource.CONFIGURATION_FAIL_CLOSED);
        }

        RulesConfiguration.ItemRule item = snapshot.item(id).orElse(null);
        String category = item == null ? "" : item.category();
        String tier = item == null ? "" : item.tier();

        if (snapshot.isBlacklisted(id)) {
            return blocked(true, id, category, tier, RecyclingReasonCode.BLOCKED_BLACKLISTED_ID,
                    RecyclingRuleSource.ITEM_BLACKLIST);
        }
        if (item == null) {
            return blocked(true, id, "", "", RecyclingReasonCode.BLOCKED_PENDING_CLASSIFICATION,
                    RecyclingRuleSource.DISCOVERY_QUEUE);
        }
        if (Boolean.FALSE.equals(item.recyclable())) {
            return blocked(true, id, category, tier, RecyclingReasonCode.BLOCKED_EXPLICIT_ITEM,
                    RecyclingRuleSource.ITEM_OVERRIDE);
        }
        if (snapshot.isBlockedCategory(category)) {
            return blocked(true, id, category, tier, RecyclingReasonCode.BLOCKED_CATEGORY,
                    RecyclingRuleSource.CATEGORY_BLOCKLIST);
        }
        if (!snapshot.categoryAllows(category)) {
            return blocked(true, id, category, tier, RecyclingReasonCode.BLOCKED_CATEGORY_POLICY,
                    RecyclingRuleSource.CATEGORY_POLICY);
        }

        Integer value = item.shards() != null ? item.shards() : snapshot.tierValue(tier);
        if (value == null || value <= 0) {
            RecyclingRuleSource source = item.shards() != null
                    ? RecyclingRuleSource.ITEM_SHARD_VALUE : RecyclingRuleSource.TIER_VALUE;
            return blocked(true, id, category, tier, RecyclingReasonCode.BLOCKED_MISSING_VALUE, source);
        }

        RecyclingReasonCode reason = Boolean.TRUE.equals(item.recyclable())
                ? RecyclingReasonCode.ALLOWED_EXPLICIT_ITEM
                : RecyclingReasonCode.ALLOWED_CATEGORY;
        RecyclingRuleSource source = Boolean.TRUE.equals(item.recyclable())
                ? RecyclingRuleSource.ITEM_OVERRIDE
                : RecyclingRuleSource.CATEGORY_POLICY;
        return new RecyclingDecision(true, id, category, tier, true, value, reason, source);
    }

    public boolean isConfigured(String itemId) {
        return configuration.isConfigured(itemId);
    }

    public RulesConfiguration configuration() {
        return configuration;
    }

    private RecyclingDecision blocked(boolean recognized,
                                      String id,
                                      String category,
                                      String tier,
                                      RecyclingReasonCode reason,
                                      RecyclingRuleSource source) {
        return new RecyclingDecision(recognized, id, category, tier, false, 0, reason, source);
    }
}

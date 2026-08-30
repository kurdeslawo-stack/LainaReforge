package pl.laina.reforge.rules;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecyclingRulesEngineTest {

    @Test
    void allowedCategoryProducesCompleteDecisionAndTierValue() throws Exception {
        RecyclingDecision decision = engine(validYaml()).evaluate(RuleEvaluationInput.identified("default_item"));

        assertTrue(decision.recognized());
        assertTrue(decision.recyclable());
        assertEquals("default_item", decision.technicalId());
        assertEquals("allowed", decision.category());
        assertEquals("common", decision.tier());
        assertEquals(4, decision.shardValue());
        assertEquals(RecyclingReasonCode.ALLOWED_CATEGORY, decision.reasonCode());
        assertEquals(RecyclingRuleSource.CATEGORY_POLICY, decision.ruleSource());
    }

    @Test
    void explicitAllowAndDenyHaveStableOutcomes() throws Exception {
        RecyclingRulesEngine engine = engine(validYaml());

        RecyclingDecision allowed = engine.evaluate(RuleEvaluationInput.identified("explicit_allow"));
        RecyclingDecision denied = engine.evaluate(RuleEvaluationInput.identified("explicit_deny"));

        assertTrue(allowed.recyclable());
        assertEquals(RecyclingReasonCode.ALLOWED_EXPLICIT_ITEM, allowed.reasonCode());
        assertEquals(7, allowed.shardValue());
        assertFalse(denied.recyclable());
        assertEquals(0, denied.shardValue());
        assertEquals(RecyclingReasonCode.BLOCKED_EXPLICIT_ITEM, denied.reasonCode());
    }

    @Test
    void blockedCategoryCannotBeRecycled() throws Exception {
        RecyclingDecision decision = engine(validYaml()).evaluate(RuleEvaluationInput.identified("blocked_item"));

        assertFalse(decision.recyclable());
        assertEquals(RecyclingReasonCode.BLOCKED_CATEGORY, decision.reasonCode());
        assertEquals(RecyclingRuleSource.CATEGORY_BLOCKLIST, decision.ruleSource());
    }

    @Test
    void pluginCurrencyHardBlockWinsOverForgedAllowedIdentity() throws Exception {
        RecyclingDecision decision = engine(validYaml())
                .evaluate(RuleEvaluationInput.currency("shard", "explicit_allow"));

        assertTrue(decision.recognized());
        assertFalse(decision.recyclable());
        assertEquals("lainareforge:currency/shard", decision.technicalId());
        assertEquals(RecyclingReasonCode.BLOCKED_PLUGIN_CURRENCY, decision.reasonCode());
        assertEquals(RecyclingRuleSource.LAINAREFORGE_CURRENCY_PDC, decision.ruleSource());
    }

    @Test
    void unidentifiedItemIsFailClosed() throws Exception {
        RecyclingDecision decision = engine(validYaml()).evaluate(RuleEvaluationInput.unidentified());

        assertFalse(decision.recognized());
        assertFalse(decision.recyclable());
        assertEquals(RecyclingReasonCode.BLOCKED_UNRECOGNIZED, decision.reasonCode());
    }

    @Test
    void identifiedButUnconfiguredItemRequiresClassification() throws Exception {
        RecyclingDecision decision = engine(validYaml()).evaluate(RuleEvaluationInput.identified("new_custom"));

        assertTrue(decision.recognized());
        assertFalse(decision.recyclable());
        assertTrue(decision.requiresClassification());
        assertEquals(RecyclingReasonCode.BLOCKED_PENDING_CLASSIFICATION, decision.reasonCode());
        assertEquals(RecyclingRuleSource.DISCOVERY_QUEUE, decision.ruleSource());
    }

    @Test
    void missingValueIsBlockedEvenAfterPositiveCategoryPolicy() {
        RulesConfiguration incompleteValue = new RulesConfiguration(
                Map.of("allowed", true),
                Map.of(),
                Map.of("item", new RulesConfiguration.ItemRule("allowed", "missing", null, null)),
                Set.of(), Set.of(), true);

        RecyclingDecision decision = new RecyclingRulesEngine(incompleteValue)
                .evaluate(RuleEvaluationInput.identified("item"));

        assertFalse(decision.recyclable());
        assertEquals(0, decision.shardValue());
        assertEquals(RecyclingReasonCode.BLOCKED_MISSING_VALUE, decision.reasonCode());
    }

    @Test
    void hardCategoryBlockWinsOverExplicitAllow() {
        RulesConfiguration conflicting = new RulesConfiguration(
                Map.of("forbidden", true), Map.of("common", 4),
                Map.of("item", new RulesConfiguration.ItemRule("forbidden", "common", true, 9)),
                Set.of(), Set.of("forbidden"), true);

        RecyclingDecision decision = new RecyclingRulesEngine(conflicting)
                .evaluate(RuleEvaluationInput.identified("item"));

        assertFalse(decision.recyclable());
        assertEquals(RecyclingReasonCode.BLOCKED_CATEGORY, decision.reasonCode());
    }

    @Test
    void blacklistWinsOverExplicitAllow() {
        RulesConfiguration conflicting = new RulesConfiguration(
                Map.of("allowed", true), Map.of("common", 4),
                Map.of("item", new RulesConfiguration.ItemRule("allowed", "common", true, 9)),
                Set.of("item"), Set.of(), true);

        RecyclingDecision decision = new RecyclingRulesEngine(conflicting)
                .evaluate(RuleEvaluationInput.identified("item"));

        assertFalse(decision.recyclable());
        assertEquals(RecyclingReasonCode.BLOCKED_BLACKLISTED_ID, decision.reasonCode());
    }

    @Test
    void invalidActiveSnapshotFailsClosed() {
        RecyclingDecision decision = new RecyclingRulesEngine(RulesConfiguration.failClosed())
                .evaluate(RuleEvaluationInput.identified("anything"));

        assertEquals(RecyclingReasonCode.BLOCKED_INVALID_CONFIGURATION, decision.reasonCode());
        assertFalse(decision.recyclable());
    }

    @Test
    void reasonCodeNamesRemainStable() {
        assertEquals(RecyclingReasonCode.ALLOWED_CATEGORY,
                RecyclingReasonCode.valueOf("ALLOWED_CATEGORY"));
        assertEquals(RecyclingReasonCode.BLOCKED_PLUGIN_CURRENCY,
                RecyclingReasonCode.valueOf("BLOCKED_PLUGIN_CURRENCY"));
        assertEquals(RecyclingReasonCode.BLOCKED_PENDING_CLASSIFICATION,
                RecyclingReasonCode.valueOf("BLOCKED_PENDING_CLASSIFICATION"));
        assertEquals(RecyclingReasonCode.BLOCKED_EXPLICIT_ITEM,
                RecyclingReasonCode.valueOf("BLOCKED_EXPLICIT_ITEM"));
    }

    private RecyclingRulesEngine engine(String yaml) throws InvalidConfigurationException {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(yaml);
        RulesConfigurationCandidate candidate = RulesConfigurationValidator.validate(configuration);
        assertTrue(candidate.report().isValid(), () -> candidate.report().issues().toString());
        return new RecyclingRulesEngine(candidate.configuration());
    }

    private String validYaml() {
        return """
                settings:
                  allow-unknown-items: false
                item-identification:
                  allow-material-fallback: false
                recycling:
                  blacklisted-ids:
                    - hard_block
                  blocked-categories:
                    - forbidden
                  categories:
                    allowed:
                      recyclable: true
                    forbidden:
                      recyclable: false
                  tiers:
                    common: 4
                  items:
                    default_item:
                      category: allowed
                      tier: common
                    explicit_allow:
                      category: allowed
                      tier: common
                      recyclable: true
                      shards: 7
                    explicit_deny:
                      category: allowed
                      tier: common
                      recyclable: false
                    blocked_item:
                      category: forbidden
                      tier: common
                """;
    }
}

package pl.laina.reforge.rules;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.laina.reforge.runtime.ApprovedRecyclingRegistryLoader;
import pl.laina.reforge.runtime.RuntimeItemIdentity;

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

    @Test
    void approvedRegistryIsEconomicSourceOfTruthWithExactPayout() throws Exception {
        ApprovedRecyclingRegistryLoader registry = registry("""
                items:
                  "diamond_sword:123":
                    recyclable: true
                    shards: 5
                    source_item: "Approved_Item"
                    model_path: "swords/approved"
                """);
        RecyclingDecision decision = new RecyclingRulesEngine(configuration(validYaml()), registry)
                .evaluate(RuleEvaluationInput.runtimeIdentified(
                        "explicit_deny", new RuntimeItemIdentity("diamond_sword", 123)));

        assertTrue(decision.recyclable());
        assertEquals(5, decision.shardValue());
        assertEquals("diamond_sword:123", decision.technicalId());
        assertEquals(RecyclingReasonCode.ALLOWED_APPROVED_DECISION, decision.reasonCode());
        assertEquals(RecyclingRuleSource.APPROVED_DECISIONS_REGISTRY, decision.ruleSource());
    }

    @Test
    void rejectedAndMissingRuntimeIdentitiesFailClosed() throws Exception {
        ApprovedRecyclingRegistryLoader registry = registry("""
                items:
                  "bow:456":
                    recyclable: false
                    shards: 0
                    source_item: "Rejected_Item"
                    model_path: "bows/rejected"
                """);
        RecyclingRulesEngine engine = new RecyclingRulesEngine(configuration(validYaml()), registry);

        RecyclingDecision rejected = engine.evaluate(RuleEvaluationInput.runtimeIdentified(
                "default_item", new RuntimeItemIdentity("bow", 456)));
        RecyclingDecision missing = engine.evaluate(RuleEvaluationInput.runtimeIdentified(
                "default_item", new RuntimeItemIdentity("bow", 999)));
        RecyclingDecision invalid = engine.evaluate(RuleEvaluationInput.invalidRuntimeIdentity("default_item"));

        assertFalse(rejected.recyclable());
        assertEquals(RecyclingReasonCode.BLOCKED_APPROVED_DECISION_REJECTED, rejected.reasonCode());
        assertFalse(missing.recyclable());
        assertTrue(missing.requiresClassification());
        assertEquals(RecyclingReasonCode.BLOCKED_APPROVED_DECISION_NOT_CONFIGURED, missing.reasonCode());
        assertFalse(invalid.recyclable());
        assertEquals(RecyclingReasonCode.BLOCKED_INVALID_IDENTITY, invalid.reasonCode());
    }

    @Test
    void legacyBlacklistRemainsHardSafetyAboveApprovedDecision() throws Exception {
        ApprovedRecyclingRegistryLoader registry = registry("""
                items:
                  "diamond_sword:123":
                    recyclable: true
                    shards: 5
                    source_item: "Approved_Item"
                    model_path: "swords/approved"
                """);
        RecyclingDecision decision = new RecyclingRulesEngine(configuration(validYaml()), registry)
                .evaluate(RuleEvaluationInput.runtimeIdentified(
                        "hard_block", new RuntimeItemIdentity("diamond_sword", 123)));

        assertFalse(decision.recyclable());
        assertEquals(0, decision.shardValue());
        assertEquals(RecyclingReasonCode.BLOCKED_BLACKLISTED_ID, decision.reasonCode());
    }

    private RecyclingRulesEngine engine(String yaml) throws InvalidConfigurationException {
        return new RecyclingRulesEngine(configuration(yaml));
    }

    private RulesConfiguration configuration(String yaml) throws InvalidConfigurationException {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(yaml);
        RulesConfigurationCandidate candidate = RulesConfigurationValidator.validate(configuration);
        assertTrue(candidate.report().isValid(), () -> candidate.report().issues().toString());
        return candidate.configuration();
    }

    private ApprovedRecyclingRegistryLoader registry(String yaml) {
        ApprovedRecyclingRegistryLoader loader = new ApprovedRecyclingRegistryLoader();
        assertTrue(loader.reload(yaml).activated());
        return loader;
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

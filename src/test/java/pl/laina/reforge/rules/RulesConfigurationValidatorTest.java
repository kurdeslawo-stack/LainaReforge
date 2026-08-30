package pl.laina.reforge.rules;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulesConfigurationValidatorTest {

    @Test
    void validConfigurationIsAccepted() throws Exception {
        RulesConfigurationCandidate candidate = validate("""
                item-identification:
                  allow-material-fallback: false
                recycling:
                  blocked-categories: []
                  blacklisted-ids: []
                  categories:
                    equipment:
                      recyclable: true
                  tiers:
                    rare: 4
                  items:
                    sword:
                      category: equipment
                      tier: rare
                """);

        assertTrue(candidate.report().isValid());
        assertTrue(candidate.configuration().isConfigured("sword"));
    }

    @Test
    void unknownCategoryTierMissingFieldsAndNegativeValueAreRejected() throws Exception {
        RulesConfigurationCandidate candidate = validate("""
                recycling:
                  blocked-categories: []
                  blacklisted-ids: []
                  categories:
                    equipment:
                      recyclable: true
                  tiers:
                    rare: -4
                  items:
                    first:
                      category: missing_category
                      tier: missing_tier
                    second:
                      category: equipment
                      shards: -1
                """);

        Set<String> codes = codes(candidate);
        assertFalse(candidate.report().isValid());
        assertTrue(codes.contains("INVALID_VALUE"));
        assertTrue(codes.contains("UNKNOWN_CATEGORY"));
        assertTrue(codes.contains("UNKNOWN_TIER"));
        assertTrue(codes.contains("MISSING_FIELD"));
    }

    @Test
    void conflictingExplicitAllowAndBlockedCategoryIsRejected() throws Exception {
        RulesConfigurationCandidate candidate = validate("""
                recycling:
                  blocked-categories: [currency]
                  blacklisted-ids: []
                  categories:
                    currency:
                      recyclable: false
                  tiers:
                    common: 1
                  items:
                    token:
                      category: currency
                      tier: common
                      recyclable: true
                """);

        assertFalse(candidate.report().isValid());
        assertTrue(codes(candidate).contains("CONFLICTING_ITEM_POLICY"));
    }

    @Test
    void normalizedDuplicateKeysAndListEntriesAreRejected() throws Exception {
        RulesConfigurationCandidate candidate = validate("""
                recycling:
                  blocked-categories: []
                  blacklisted-ids: [SAME, same]
                  categories:
                    Equipment:
                      recyclable: true
                    equipment:
                      recyclable: true
                  tiers:
                    common: 1
                  items: {}
                """);

        assertFalse(candidate.report().isValid());
        assertTrue(codes(candidate).contains("AMBIGUOUS_KEY"));
        assertTrue(codes(candidate).contains("DUPLICATE_ENTRY"));
    }

    @Test
    void unsafeMaterialOnlyIdentityAndLegacyEntriesAreRejected() throws Exception {
        RulesConfigurationCandidate candidate = validate("""
                item-identification:
                  allow-material-fallback: true
                recycling:
                  blocked-categories: []
                  blacklisted-ids: []
                  categories:
                    equipment:
                      recyclable: true
                  tiers:
                    common: 1
                  items: {}
                  values:
                    old_item: 5
                """);

        assertFalse(candidate.report().isValid());
        assertTrue(codes(candidate).contains("UNSAFE_IDENTITY_FALLBACK"));
        assertTrue(codes(candidate).contains("LEGACY_ENTRY_INCOMPLETE"));
    }

    private RulesConfigurationCandidate validate(String yaml) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(yaml);
        return RulesConfigurationValidator.validate(configuration);
    }

    private Set<String> codes(RulesConfigurationCandidate candidate) {
        return candidate.report().issues().stream().map(ConfigurationIssue::code).collect(Collectors.toSet());
    }
}

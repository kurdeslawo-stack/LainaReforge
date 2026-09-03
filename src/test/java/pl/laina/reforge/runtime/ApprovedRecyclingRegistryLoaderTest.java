package pl.laina.reforge.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovedRecyclingRegistryLoaderTest {
    private static final String VALID = """
            items:
              "diamond_sword:101":
                recyclable: true
                shards: 3
                source_item: "Approved_Item"
                model_path: "swords/approved"
              "bow:202":
                recyclable: false
                shards: 0
                source_item: "Rejected_Item"
                model_path: "bows/rejected"
            """;

    @Test
    void resolvesApprovedRejectedAndNotConfiguredExactly() {
        ApprovedRecyclingRegistryLoader loader = new ApprovedRecyclingRegistryLoader();
        assertTrue(loader.reload(VALID).activated());

        RecyclingLookupResult approved = loader.lookup(new RuntimeItemIdentity("DIAMOND_SWORD", 101));
        RecyclingLookupResult rejected = loader.lookup(new RuntimeItemIdentity("bow", 202));
        RecyclingLookupResult missing = loader.lookup(new RuntimeItemIdentity("bow", 101));

        assertEquals(RecyclingLookupResult.Status.APPROVED, approved.status());
        assertEquals(3, approved.shards());
        assertEquals(RecyclingLookupResult.Status.REJECTED, rejected.status());
        assertEquals(0, rejected.shards());
        assertEquals(RecyclingLookupResult.Status.NOT_CONFIGURED, missing.status());
    }

    @Test
    void malformedOrUnsafeEntriesAreRejected() {
        ApprovedRecyclingRegistryLoader loader = new ApprovedRecyclingRegistryLoader();

        assertFalse(loader.validate(VALID.replace("shards: 3", "shards: 0")).valid());
        assertFalse(loader.validate(VALID.replace("recyclable: false", "recyclable: true")).valid());
        assertFalse(loader.validate(VALID.replace("model_path: \"bows/rejected\"",
                "model_path: \"bows/rejected\"\n    unexpected: true")).valid());
        assertFalse(loader.validate(VALID.replace("diamond_sword:101", "diamond_sword:not-a-number")).valid());
    }

    @Test
    void invalidReloadRetainsLastKnownGoodSnapshot() {
        ApprovedRecyclingRegistryLoader loader = new ApprovedRecyclingRegistryLoader();
        assertTrue(loader.reload(VALID).activated());

        var failed = loader.reload(VALID.replace("shards: 3", "shards: -1"));

        assertFalse(failed.activated());
        assertEquals(2, failed.activeIdentities());
        assertEquals(RecyclingLookupResult.Status.APPROVED,
                loader.lookup(new RuntimeItemIdentity("diamond_sword", 101)).status());
    }

    @Test
    void firstInvalidLoadKeepsEmptyFailClosedSnapshot() {
        ApprovedRecyclingRegistryLoader loader = new ApprovedRecyclingRegistryLoader();

        assertFalse(loader.reload("items:\n  broken").activated());
        assertEquals(0, loader.snapshot().size());
        assertEquals(RecyclingLookupResult.Status.NOT_CONFIGURED,
                loader.lookup(new RuntimeItemIdentity("diamond_sword", 101)).status());
    }

    @Test
    void renderIsStableAndRoundTrips() {
        ApprovedRecyclingRegistryLoader loader = new ApprovedRecyclingRegistryLoader();
        var candidate = loader.validate(VALID);
        assertTrue(candidate.valid());

        String first = ApprovedRecyclingRegistryLoader.render(candidate.registry());
        String second = ApprovedRecyclingRegistryLoader.render(loader.validate(first).registry());

        assertEquals(first, second);
        assertTrue(first.indexOf("bow:202") < first.indexOf("diamond_sword:101"));
    }
}

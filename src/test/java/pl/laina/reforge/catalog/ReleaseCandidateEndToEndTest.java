package pl.laina.reforge.catalog;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.laina.reforge.runtime.ApprovedRecyclingRegistryLoader;
import pl.laina.reforge.runtime.RecyclingLookupResult;
import pl.laina.reforge.runtime.RecyclingSafetyLimits;
import pl.laina.reforge.runtime.RuntimeItemIdentity;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseCandidateEndToEndTest {
    private static RecyclingDecisionQueueGenerator.DecisionQueue queue;
    private static List<RecyclingDecisionQueueGenerator.QueueItem> fixtures;

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void loadProductionQueue() throws Exception {
        queue = RecyclingReviewPanelGenerator.parseQueue(Files.readString(
                Path.of("generated/recycling-decision-queue.yml"), StandardCharsets.UTF_8));
        fixtures = queue.items().stream()
                .filter(item -> item.identities().size() == 1)
                .limit(4)
                .toList();
        assertEquals(4, fixtures.size());
    }

    @Test
    void isolatedDecisionsCompileToExactFailClosedRuntime() throws Exception {
        Path decisions = temporaryDirectory.resolve("rc-smoke-decisions.yml");
        Path runtime = temporaryDirectory.resolve("rc-smoke-runtime.yml");
        Path report = temporaryDirectory.resolve("rc-smoke-report.txt");
        Files.writeString(decisions, decisionsYaml(), StandardCharsets.UTF_8);

        int exit = RecyclingRuntimeCompiler.execute(new RecyclingRuntimeCompiler.Options(
                        Path.of("generated/recycling-decision-queue.yml"), decisions, runtime, report),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, exit);
        ApprovedRecyclingRegistryLoader loader = new ApprovedRecyclingRegistryLoader();
        assertTrue(loader.reload(runtime).activated());
        assertLookup(loader, fixtures.get(0), RecyclingLookupResult.Status.APPROVED, 3);
        assertLookup(loader, fixtures.get(1), RecyclingLookupResult.Status.REJECTED, 0);
        assertLookup(loader, fixtures.get(2), RecyclingLookupResult.Status.APPROVED, 7);
        assertLookup(loader, fixtures.get(3), RecyclingLookupResult.Status.NOT_CONFIGURED, 0);

        var approvedIdentity = fixtures.get(0).identities().getFirst();
        String otherMaterial = approvedIdentity.material().equals("stone") ? "dirt" : "stone";
        assertEquals(RecyclingLookupResult.Status.NOT_CONFIGURED,
                loader.lookup(new RuntimeItemIdentity(otherMaterial, approvedIdentity.cmd())).status());
        assertEquals(3, loader.snapshot().size());
    }

    @Test
    void invalidRuntimeVariantsAreRejectedAndLastKnownGoodSurvives() {
        ApprovedRecyclingRegistryLoader loader = new ApprovedRecyclingRegistryLoader();
        String valid = runtimeYaml(fixtures.get(0), 3);
        assertTrue(loader.reload(valid).activated());

        assertRejected(loader, "items:\n  broken");
        assertRejected(loader, valid + valid.substring(valid.indexOf("  \"")));
        assertRejected(loader, valid.replace("shards: 3",
                "shards: " + (RecyclingSafetyLimits.MAX_SHARDS_PER_ITEM + 1)));
        assertRejected(loader, valid.replace(fixtures.get(0).identities().getFirst().material(),
                "not_a_real_material"));
        assertRejected(loader, valid.replace(":" + fixtures.get(0).identities().getFirst().cmd(), ":0"));
        assertFalse(loader.reload(temporaryDirectory.resolve("missing-runtime.yml")).activated());

        assertLookup(loader, fixtures.get(0), RecyclingLookupResult.Status.APPROVED, 3);
        assertEquals(1, loader.snapshot().size());
    }

    @Test
    void repositoryReleasePreflightPasses() {
        ReleasePreflight.Result result = ReleasePreflight.inspect(Path.of("."));
        assertTrue(result.passed(), () -> result.details().toString());
        assertEquals(List.of("Java", "Repository", "Catalog", "Queue", "Panel", "Snapshot",
                "Decisions", "Runtime", "Plugin metadata"), result.checks().keySet().stream().toList());
    }

    private void assertRejected(ApprovedRecyclingRegistryLoader loader, String invalid) {
        assertFalse(loader.reload(invalid).activated());
        assertEquals(1, loader.snapshot().size());
    }

    private static void assertLookup(ApprovedRecyclingRegistryLoader loader,
                                     RecyclingDecisionQueueGenerator.QueueItem item,
                                     RecyclingLookupResult.Status status, int shards) {
        var identity = item.identities().getFirst();
        var result = loader.lookup(new RuntimeItemIdentity(identity.material(), identity.cmd()));
        assertEquals(status, result.status());
        assertEquals(shards, result.shards());
    }

    private static String decisionsYaml() {
        return "items:\n"
                + decision(fixtures.get(0).logicalId(), "APPROVED", true, 3)
                + decision(fixtures.get(1).logicalId(), "REJECTED", false, 0)
                + decision(fixtures.get(2).logicalId(), "APPROVED", true, 7);
    }

    private static String decision(String id, String status, boolean recyclable, int shards) {
        return "  \"" + escape(id) + "\":\n"
                + "    status: " + status + "\n"
                + "    recyclable: " + recyclable + "\n"
                + "    shards: " + shards + "\n"
                + "    reviewed_by: \"rc-test\"\n"
                + "    reviewed_at: \"2026-09-04T12:00:00Z\"\n"
                + "    note: \"isolated fixture\"\n";
    }

    private static String runtimeYaml(RecyclingDecisionQueueGenerator.QueueItem item, int shards) {
        var identity = item.identities().getFirst();
        return "items:\n"
                + "  \"" + identity.material() + ":" + identity.cmd() + "\":\n"
                + "    recyclable: true\n"
                + "    shards: " + shards + "\n"
                + "    source_item: \"" + escape(item.logicalId()) + "\"\n"
                + "    model_path: \"" + escape(identity.modelPath()) + "\"\n";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

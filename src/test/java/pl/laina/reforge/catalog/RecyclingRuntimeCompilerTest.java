package pl.laina.reforge.catalog;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.laina.reforge.runtime.ApprovedRecyclingRegistryLoader;
import pl.laina.reforge.runtime.RecyclingLookupResult;
import pl.laina.reforge.runtime.RuntimeItemIdentity;
import pl.laina.reforge.runtime.RecyclingSafetyLimits;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.DecisionStatus.APPROVED;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.DecisionStatus.PENDING;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.DecisionStatus.REJECTED;

class RecyclingRuntimeCompilerTest {
    private static RecyclingDecisionQueueGenerator.DecisionQueue queue;
    private static RecyclingDecisionQueueGenerator.QueueItem singleIdentity;
    private static RecyclingDecisionQueueGenerator.QueueItem multipleIdentities;
    private static RecyclingDecisionQueueGenerator.QueueItem unmappedIdentity;

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void loadQueue() throws Exception {
        queue = RecyclingReviewPanelGenerator.parseQueue(Files.readString(
                Path.of("generated/recycling-decision-queue.yml"), StandardCharsets.UTF_8));
        singleIdentity = queue.items().stream().filter(item -> item.identities().size() == 1).findFirst().orElseThrow();
        multipleIdentities = queue.items().stream().filter(item -> item.identities().size() > 1).findFirst().orElseThrow();
        unmappedIdentity = queue.items().stream().filter(item -> item.mappingStatus()
                == RecyclingDecisionQueueGenerator.MappingStatus.UNMAPPED).findFirst().orElseThrow();
    }

    @Test
    void approvedDecisionExportsExactShardValueForEveryGroupedIdentity() {
        var compilation = RecyclingRuntimeCompiler.compile(queue,
                Map.of(multipleIdentities.logicalId(), approved(4)));

        assertEquals(1, compilation.approved());
        assertEquals(multipleIdentities.identities().size(), compilation.registry().size());
        multipleIdentities.identities().forEach(identity -> {
            var result = compilation.registry().lookup(
                    new RuntimeItemIdentity(identity.material(), identity.cmd()));
            assertEquals(RecyclingLookupResult.Status.APPROVED, result.status());
            assertEquals(4, result.shards());
            assertEquals(multipleIdentities.logicalId(), result.sourceItem());
        });
    }

    @Test
    void rejectedDecisionExportsSafeZeroShardEntries() {
        var compilation = RecyclingRuntimeCompiler.compile(queue,
                Map.of(singleIdentity.logicalId(), rejected()));
        var identity = singleIdentity.identities().getFirst();
        var result = compilation.registry().lookup(new RuntimeItemIdentity(identity.material(), identity.cmd()));

        assertEquals(RecyclingLookupResult.Status.REJECTED, result.status());
        assertEquals(0, result.shards());
    }

    @Test
    void compilesHumanDecisionForUnmappedIdentity() {
        var compilation = RecyclingRuntimeCompiler.compile(queue,
                Map.of(unmappedIdentity.logicalId(), approved(2)));
        var identity = unmappedIdentity.identities().getFirst();
        var result = compilation.registry().lookup(new RuntimeItemIdentity(identity.material(), identity.cmd()));

        assertEquals(RecyclingLookupResult.Status.APPROVED, result.status());
        assertEquals(2, result.shards());
        assertEquals(unmappedIdentity.logicalId(), result.sourceItem());
    }

    @Test
    void pendingUnknownAndInvalidSemanticsRejectWholeCompilation() {
        assertThrows(IllegalArgumentException.class, () -> RecyclingRuntimeCompiler.compile(queue,
                Map.of(singleIdentity.logicalId(), decision(PENDING, null, null))));
        assertThrows(IllegalArgumentException.class, () -> RecyclingRuntimeCompiler.compile(queue,
                Map.of("not_in_queue", approved(2))));
        assertThrows(IllegalArgumentException.class, () -> RecyclingRuntimeCompiler.compile(queue,
                Map.of(singleIdentity.logicalId(), decision(APPROVED, true, 0))));
        assertThrows(IllegalArgumentException.class, () -> RecyclingRuntimeCompiler.compile(queue,
                Map.of(singleIdentity.logicalId(), decision(REJECTED, false, 2))));
    }

    @Test
    void compilerAcceptsPerItemLimitAndRejectsAnythingAboveIt() {
        var atLimit = RecyclingRuntimeCompiler.compile(queue,
                Map.of(singleIdentity.logicalId(), approved(RecyclingSafetyLimits.MAX_SHARDS_PER_ITEM)));
        assertEquals(1, atLimit.registry().size());

        assertThrows(IllegalArgumentException.class, () -> RecyclingRuntimeCompiler.compile(queue,
                Map.of(singleIdentity.logicalId(),
                        approved(RecyclingSafetyLimits.MAX_SHARDS_PER_ITEM + 1))));
    }

    @Test
    void duplicateIdentityAssignmentsRejectWholeQueue() {
        var first = queue.items().get(0);
        var secondOriginal = queue.items().get(1);
        var second = new RecyclingDecisionQueueGenerator.QueueItem(
                secondOriginal.logicalId(), secondOriginal.name(), secondOriginal.wiki(),
                secondOriginal.mappingStatus(), secondOriginal.priority(),
                secondOriginal.reviewReason(), first.identities(), secondOriginal.acquisition(),
                secondOriginal.evidence(), secondOriginal.systemProposal(), secondOriginal.decision());
        var invalidQueue = new RecyclingDecisionQueueGenerator.DecisionQueue(java.util.List.of(first, second));

        assertThrows(IllegalArgumentException.class, () -> RecyclingRuntimeCompiler.compile(invalidQueue,
                Map.of(first.logicalId(), approved(1), second.logicalId(), approved(2))));
    }

    @Test
    void outputIsDeterministicAndValidatedByRuntimeLoader() {
        var decisions = Map.of(singleIdentity.logicalId(), approved(3),
                multipleIdentities.logicalId(), rejected());
        String first = ApprovedRecyclingRegistryLoader.render(
                RecyclingRuntimeCompiler.compile(queue, decisions).registry());
        String second = ApprovedRecyclingRegistryLoader.render(
                RecyclingRuntimeCompiler.compile(queue, decisions).registry());

        assertEquals(first, second);
        assertTrue(new ApprovedRecyclingRegistryLoader().validate(first).valid());
    }

    @Test
    void failedExecutionDoesNotReplaceExistingRuntimeOrCatalog() throws Exception {
        Path decisions = temporaryDirectory.resolve("bad-decisions.yml");
        Path output = temporaryDirectory.resolve("runtime.yml");
        Path report = temporaryDirectory.resolve("report.txt");
        Files.writeString(decisions, "items:\n  \"unknown\":\n    status: APPROVED\n", StandardCharsets.UTF_8);
        Files.writeString(output, "sentinel", StandardCharsets.UTF_8);
        byte[] catalogBefore = Files.readAllBytes(Path.of("src/main/resources/items.yml"));

        int exit = RecyclingRuntimeCompiler.execute(new RecyclingRuntimeCompiler.Options(
                        Path.of("generated/recycling-decision-queue.yml"), decisions, output, report),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(1, exit);
        assertEquals("sentinel", Files.readString(output));
        assertFalse(Files.exists(report));
        assertArrayEquals(catalogBefore, Files.readAllBytes(Path.of("src/main/resources/items.yml")));
    }

    @Test
    void reportDoesNotClaimThatCompilerRanTheTestSuite() throws Exception {
        Path output = temporaryDirectory.resolve("empty-runtime.yml");
        Path report = temporaryDirectory.resolve("runtime-report.txt");

        int exit = RecyclingRuntimeCompiler.execute(new RecyclingRuntimeCompiler.Options(
                        Path.of("generated/recycling-decision-queue.yml"),
                        Path.of("recycling-decisions.yml"), output, report),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, exit);
        String text = Files.readString(report);
        assertTrue(text.contains("Compiler validation: PASS"));
        assertTrue(text.contains("Runtime config validation: PASS"));
        assertTrue(text.contains("Test suite status: NOT_RUN_BY_COMPILER"));
        assertFalse(text.contains("tests: PASS"));
    }

    private static RecyclingReviewPanelGenerator.ReviewDecision approved(int shards) {
        return decision(APPROVED, true, shards);
    }

    private static RecyclingReviewPanelGenerator.ReviewDecision rejected() {
        return decision(REJECTED, false, 0);
    }

    private static RecyclingReviewPanelGenerator.ReviewDecision decision(
            RecyclingDecisionQueueGenerator.DecisionStatus status, Boolean recyclable, Integer shards) {
        return new RecyclingReviewPanelGenerator.ReviewDecision(
                status, recyclable, shards, "tester", "2026-09-03T12:00:00Z", "");
    }
}

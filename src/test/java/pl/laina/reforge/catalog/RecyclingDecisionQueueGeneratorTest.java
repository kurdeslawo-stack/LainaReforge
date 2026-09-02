package pl.laina.reforge.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.Acquisition;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.AnalysisData;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.AnalysisItem;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.Decision;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.DecisionQueue;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.DecisionStatus;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.Identity;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.Priority;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.QueueItem;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.SystemProposal;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.SystemProposalValue;

class RecyclingDecisionQueueGeneratorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void generatedDecisionIsPendingWithNullValues() {
        DecisionQueue queue = generate(item("Test_Item", "Test Item", Set.of("DROP"), List.of("drop"),
                SystemProposalValue.UNKNOWN));

        Decision decision = queue.items().getFirst().decision();
        assertEquals(DecisionStatus.PENDING, decision.status());
        assertNull(decision.recyclable());
        assertNull(decision.shards());
        String yaml = RecyclingDecisionQueueGenerator.renderQueue(queue);
        assertTrue(yaml.contains("      status: PENDING"));
        assertTrue(yaml.contains("      shards: null"));
    }

    @Test
    void groupsSeveralCatalogRecordsIntoOneLogicalItem() {
        AnalysisItem item = item("Shared_Item", "Shared Item", Set.of("DROP"), List.of("drop"),
                SystemProposalValue.UNKNOWN);
        ItemEconomyAnalyzer.Catalog catalog = catalog(
                record("diamond_sword:1", "diamond_sword", 1, "swords/shared", "Shared_Item", "Shared Item"),
                record("netherite_sword:1", "netherite_sword", 1, "swords/shared", "Shared_Item", "Shared Item"));

        DecisionQueue queue = RecyclingDecisionQueueGenerator.generate(
                catalog, new AnalysisData(Map.of(item.logicalId(), item)), Set.of());

        assertEquals(1, queue.items().size());
        assertEquals(2, queue.items().getFirst().identities().size());
    }

    @Test
    void preservesAllTechnicalIdentities() {
        DecisionQueue queue = RecyclingDecisionQueueGenerator.generate(
                catalog(
                        record("diamond_pickaxe:7", "diamond_pickaxe", 7, "pickaxes/test", "Test_Item", "Test Item"),
                        record("netherite_pickaxe:7", "netherite_pickaxe", 7, "pickaxes/test", "Test_Item", "Test Item")),
                analysis(item("Test_Item", "Test Item", Set.of(), List.of(), SystemProposalValue.UNKNOWN)),
                Set.of());

        assertEquals(List.of(
                        new Identity("diamond_pickaxe", 7, "pickaxes/test"),
                        new Identity("netherite_pickaxe", 7, "pickaxes/test")),
                queue.items().getFirst().identities());
    }

    @Test
    void assignsHighPriorityToInfiniteOrFarmable() {
        DecisionQueue queue = generate(item("Farmable", "Farmable", Set.of("INFINITE_OR_FARMABLE"),
                List.of("fishing"), SystemProposalValue.UNKNOWN));

        assertEquals(Priority.HIGH, queue.items().getFirst().priority());
    }

    @Test
    void assignsHighPriorityToNoProposal() {
        DecisionQueue queue = generate(item("No_Item", "No Item", Set.of(), List.of(), SystemProposalValue.NO));

        assertEquals(Priority.HIGH, queue.items().getFirst().priority());
    }

    @Test
    void assignsHighPriorityToManualReviewMultiSource() {
        AnalysisItem item = item("Mixed_Item", "Mixed Item", Set.of("EVENT", "DROP"),
                List.of("event", "drop"), SystemProposalValue.UNKNOWN);

        DecisionQueue queue = RecyclingDecisionQueueGenerator.generate(
                catalog(recordFor(item)), analysis(item), Set.of(item.wiki()));

        assertEquals(Priority.HIGH, queue.items().getFirst().priority());
        assertTrue(queue.items().getFirst().reviewReason().contains("Kilka źródeł"));
    }

    @Test
    void assignsMediumPriorityToConcreteUnknownProposal() {
        DecisionQueue queue = generate(item("Drop_Item", "Drop Item", Set.of("DROP"), List.of("drop"),
                SystemProposalValue.UNKNOWN));

        assertEquals(Priority.MEDIUM, queue.items().getFirst().priority());
    }

    @Test
    void assignsLowPriorityToUnknownAcquisition() {
        DecisionQueue queue = generate(item("Unknown_Item", "Unknown Item", Set.of("UNKNOWN"), List.of(),
                SystemProposalValue.UNKNOWN));

        assertEquals(Priority.LOW, queue.items().getFirst().priority());
    }

    @Test
    void acceptsValidApprovedDecision() {
        QueueItem approved = queueItem("Approved", new Decision(
                DecisionStatus.APPROVED, true, 5, "admin", "2026-09-02T12:00:00Z", "OK"));

        assertTrue(RecyclingDecisionQueueValidator.validate(new DecisionQueue(List.of(approved))).valid());
    }

    @Test
    void acceptsValidRejectedDecision() {
        QueueItem rejected = queueItem("Rejected", new Decision(
                DecisionStatus.REJECTED, false, 0, "admin", "2026-09-02T12:00:00Z", "Nie"));

        assertTrue(RecyclingDecisionQueueValidator.validate(new DecisionQueue(List.of(rejected))).valid());
    }

    @Test
    void rejectsInvalidDecisionStates() {
        QueueItem pending = queueItem("Pending", new Decision(DecisionStatus.PENDING, false, 0, null, null, ""));
        QueueItem approved = queueItem("Approved", new Decision(DecisionStatus.APPROVED, false, 0, null, null, ""));
        QueueItem rejected = queueItem("Rejected", new Decision(DecisionStatus.REJECTED, true, 2, null, null, ""));

        RecyclingDecisionQueueValidator.ValidationResult result = RecyclingDecisionQueueValidator.validate(
                new DecisionQueue(List.of(pending, approved, rejected)));

        assertFalse(result.valid());
        Set<String> codes = result.errors().stream().map(
                RecyclingDecisionQueueValidator.ValidationError::code).collect(java.util.stream.Collectors.toSet());
        assertTrue(codes.contains("PENDING_RECYCLABLE_NOT_NULL"));
        assertTrue(codes.contains("PENDING_SHARDS_NOT_NULL"));
        assertTrue(codes.contains("APPROVED_NOT_RECYCLABLE"));
        assertTrue(codes.contains("APPROVED_INVALID_SHARDS"));
        assertTrue(codes.contains("REJECTED_RECYCLABLE_NOT_FALSE"));
        assertTrue(codes.contains("REJECTED_INVALID_SHARDS"));
    }

    @Test
    void detectsDuplicateLogicalItemsAndCrossItemIdentity() {
        Identity shared = new Identity("diamond_sword", 10, "swords/shared");
        QueueItem first = queueItem("First", Decision.pending(), List.of(shared));
        QueueItem duplicateLogical = queueItem("First", Decision.pending(), List.of(
                new Identity("iron_sword", 11, "swords/other")));
        QueueItem conflictingIdentity = queueItem("Second", Decision.pending(), List.of(shared));

        RecyclingDecisionQueueValidator.ValidationResult result = RecyclingDecisionQueueValidator.validate(
                new DecisionQueue(List.of(first, duplicateLogical, conflictingIdentity)));

        Set<String> codes = result.errors().stream().map(
                RecyclingDecisionQueueValidator.ValidationError::code).collect(java.util.stream.Collectors.toSet());
        assertTrue(codes.contains("DUPLICATE_LOGICAL_ITEM"));
        assertTrue(codes.contains("DUPLICATE_IDENTITY"));
        assertEquals(1, result.duplicateIdentityAssignments());
    }

    @Test
    void rejectsQueueItemWithoutIdentities() {
        QueueItem item = queueItem("NoIdentity", Decision.pending(), List.of());

        RecyclingDecisionQueueValidator.ValidationResult result =
                RecyclingDecisionQueueValidator.validate(new DecisionQueue(List.of(item)));

        assertTrue(result.errors().stream().anyMatch(error -> error.code().equals("MISSING_IDENTITIES")));
    }

    @Test
    void sortsDeterministicallyByPriorityThenName() {
        AnalysisItem low = item("Low", "Ącki", Set.of("UNKNOWN"), List.of(), SystemProposalValue.UNKNOWN);
        AnalysisItem highZ = item("High_Z", "Żaba", Set.of("REPEATABLE"), List.of(), SystemProposalValue.UNKNOWN);
        AnalysisItem highA = item("High_A", "Adam", Set.of(), List.of(), SystemProposalValue.NO);
        AnalysisItem medium = item("Medium", "Beta", Set.of("DROP"), List.of("drop"),
                SystemProposalValue.UNKNOWN);
        ItemEconomyAnalyzer.Catalog catalog = catalog(
                recordFor(low), recordFor(highZ), recordFor(highA), recordFor(medium));

        DecisionQueue queue = RecyclingDecisionQueueGenerator.generate(catalog,
                analysis(low, highZ, highA, medium), Set.of());

        assertEquals(List.of("High_A", "High_Z", "Medium", "Low"),
                queue.items().stream().map(QueueItem::logicalId).toList());
        assertEquals(RecyclingDecisionQueueGenerator.renderQueue(queue),
                RecyclingDecisionQueueGenerator.renderQueue(
                        RecyclingDecisionQueueGenerator.generate(catalog,
                                analysis(medium, highA, low, highZ), Set.of())));
    }

    @Test
    void fullGeneratorDoesNotChangeItemsYaml() throws Exception {
        Path catalog = RecyclingDecisionQueueGenerator.DEFAULT_CATALOG;
        String before = Files.readString(catalog, StandardCharsets.UTF_8);
        Path output = temporaryDirectory.resolve("queue.yml");
        Path report = temporaryDirectory.resolve("report.txt");
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int code = RecyclingDecisionQueueGenerator.execute(
                new RecyclingDecisionQueueGenerator.Options(
                        catalog,
                        RecyclingDecisionQueueGenerator.DEFAULT_ANALYSIS,
                        RecyclingDecisionQueueGenerator.DEFAULT_MANUAL_REVIEW,
                        output,
                        report),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));

        assertEquals(0, code, errors.toString(StandardCharsets.UTF_8));
        assertEquals(before, Files.readString(catalog, StandardCharsets.UTF_8));
        assertTrue(Files.readString(output, StandardCharsets.UTF_8).contains("      shards: null"));
    }

    @Test
    void generatorIsNotRegisteredInPluginRuntime() throws Exception {
        String pluginYaml = Files.readString(Path.of("src/main/resources/plugin.yml"), StandardCharsets.UTF_8);

        assertFalse(pluginYaml.contains("RecyclingDecisionQueueGenerator"));
        assertFalse(pluginYaml.contains("recycling-decision-queue"));
    }

    private static DecisionQueue generate(AnalysisItem item) {
        return RecyclingDecisionQueueGenerator.generate(
                catalog(recordFor(item)), analysis(item), Set.of());
    }

    private static AnalysisData analysis(AnalysisItem... items) {
        return new AnalysisData(java.util.Arrays.stream(items).collect(
                java.util.stream.Collectors.toMap(AnalysisItem::logicalId, value -> value)));
    }

    private static AnalysisItem item(
            String wiki,
            String name,
            Set<String> tags,
            List<String> sources,
            SystemProposalValue proposal
    ) {
        return new AnalysisItem(wiki, wiki, name, sources.isEmpty() ? "Brak danych." : "Opis źródła.",
                sources, tags, List.of("Wiki: evidence"), proposal, "System reason.");
    }

    private static ItemEconomyAnalyzer.Catalog catalog(ItemEconomyAnalyzer.CatalogRecord... records) {
        return new ItemEconomyAnalyzer.Catalog(List.of(records));
    }

    private static ItemEconomyAnalyzer.CatalogRecord recordFor(AnalysisItem item) {
        int cmd = Math.abs(item.logicalId().hashCode()) + 1;
        return record("diamond_sword:" + cmd, "diamond_sword", cmd,
                "swords/" + item.logicalId().toLowerCase(java.util.Locale.ROOT), item.wiki(), item.name());
    }

    private static ItemEconomyAnalyzer.CatalogRecord record(
            String key,
            String material,
            int cmd,
            String modelPath,
            String wiki,
            String name
    ) {
        return new ItemEconomyAnalyzer.CatalogRecord(key, material, cmd, modelPath, wiki, name, 0);
    }

    private static QueueItem queueItem(String logicalId, Decision decision) {
        return queueItem(logicalId, decision, List.of(
                new Identity("diamond_sword", Math.abs(logicalId.hashCode()) + 1, "swords/" + logicalId)));
    }

    private static QueueItem queueItem(String logicalId, Decision decision, List<Identity> identities) {
        return new QueueItem(logicalId, logicalId, logicalId, Priority.LOW, "Review", identities,
                new Acquisition("Summary", Set.of("UNKNOWN")), List.of(),
                new SystemProposal(SystemProposalValue.UNKNOWN, "Reason"), decision);
    }
}

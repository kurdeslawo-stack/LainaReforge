package pl.laina.reforge.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.laina.reforge.catalog.CatalogEvolutionUpdater.ChangeStatus.CHANGED;
import static pl.laina.reforge.catalog.CatalogEvolutionUpdater.ChangeStatus.NEW;
import static pl.laina.reforge.catalog.CatalogEvolutionUpdater.ChangeStatus.REMOVED;
import static pl.laina.reforge.catalog.CatalogEvolutionUpdater.ChangeStatus.UNCHANGED;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.DecisionStatus.APPROVED;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.DecisionStatus.PENDING;

class CatalogEvolutionUpdaterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void oneNewIdentityAmongOneHundredOldIsClassifiedSafely() {
        var before = snapshot(100, "old");
        var afterItems = new ArrayList<>(items(100, "old"));
        afterItems.add(item(100, "new/path", "new-fingerprint"));

        Map<String, CatalogEvolutionUpdater.Change> changes = CatalogEvolutionUpdater.diff(
                before, CatalogEvolutionUpdater.snapshotOf(afterItems));

        assertEquals(100, count(changes, UNCHANGED));
        assertEquals(1, count(changes, NEW));
    }

    @Test
    void missingIdentityIsRemoved() {
        var changes = CatalogEvolutionUpdater.diff(snapshot(2, "old"), snapshot(1, "old"));

        assertEquals(1, count(changes, REMOVED));
        assertEquals(1, count(changes, UNCHANGED));
    }

    @Test
    void sameIdentityWithDifferentModelIsChangedAndOldDecisionCannotReachRuntime() {
        var before = snapshot(Map.of("apple:1000", snapshotItem(0, "food/apple_pie", "old")));
        var after = snapshot(Map.of("apple:1000", snapshotItem(0, "food/god_apple", "new")));
        var previous = queue(List.of(queueItem(0, approved(3))));
        var updated = CatalogEvolutionUpdater.updateQueue(previous,
                CatalogEvolutionUpdater.diff(before, after), after);

        assertEquals(1, updated.items().size());
        assertTrue(updated.items().getFirst().logicalId().startsWith("changed::apple:1000::"));
        assertEquals(PENDING, updated.items().getFirst().decision().status());
        assertEquals("food/apple_pie", updated.items().getFirst().catalogEvolution().beforeModelPath());
        assertEquals(0, RecyclingRuntimeCompiler.compile(updated, Map.of()).registry().size());
        assertThrows(IllegalArgumentException.class, () -> RecyclingRuntimeCompiler.compile(updated,
                Map.of("item-0", reviewApproved(3))));
    }

    @Test
    void identicalFingerprintPreservesExistingDecisionAndMapping() {
        var before = snapshot(1, "same");
        var generation = generation(items(1, "same"));
        var catalog = catalog(List.of(record(0, "Wiki_Item", "Wiki Item", 4)));
        var result = CatalogEvolutionUpdater.evolve(before, generation, catalog,
                queue(List.of(queueItem(0, approved(4)))));

        assertEquals(UNCHANGED, result.changes().get("apple:1000").status());
        assertEquals(APPROVED, result.queue().items().getFirst().decision().status());
        assertEquals("Wiki_Item", result.catalogItems().getFirst().wiki());
        assertEquals("Wiki Item", result.catalogItems().getFirst().name());
        assertEquals(4, result.catalogItems().getFirst().shards());
    }

    @Test
    void newIdentityStartsPendingAndNotConfigured() {
        var before = snapshot(Map.of("apple:1000", snapshotItem(0, "model/0", "old")));
        var afterItems = List.of(item(0, "model/0", "old"), item(1, "model/1", "new"));
        var after = CatalogEvolutionUpdater.snapshotOf(afterItems);
        var updated = CatalogEvolutionUpdater.updateQueue(queue(List.of(queueItem(0,
                        RecyclingDecisionQueueGenerator.Decision.pending()))),
                CatalogEvolutionUpdater.diff(before, after), after);

        var added = updated.items().stream().filter(value -> value.catalogEvolution().status()
                == RecyclingDecisionQueueGenerator.CatalogStatus.NEW).findFirst().orElseThrow();
        assertEquals(PENDING, added.decision().status());
        assertEquals(0, RecyclingRuntimeCompiler.compile(updated, Map.of()).registry().size());
    }

    @Test
    void addingFiveItemsPreservesAllOldDecisions() {
        var before = snapshot(5, "old");
        var afterItems = new ArrayList<>(items(5, "old"));
        for (int index = 5; index < 10; index++) {
            afterItems.add(item(index, "model/" + index, "new-" + index));
        }
        var after = CatalogEvolutionUpdater.snapshotOf(afterItems);
        var previousItems = new ArrayList<RecyclingDecisionQueueGenerator.QueueItem>();
        for (int index = 0; index < 5; index++) {
            previousItems.add(queueItem(index, approved(index + 1)));
        }
        var updated = CatalogEvolutionUpdater.updateQueue(queue(previousItems),
                CatalogEvolutionUpdater.diff(before, after), after);

        assertEquals(5, updated.items().stream().filter(item -> item.decision().status() == APPROVED).count());
        assertEquals(5, updated.items().stream().filter(item -> item.catalogEvolution().status()
                == RecyclingDecisionQueueGenerator.CatalogStatus.NEW).count());
    }

    @Test
    void invalidZipLeavesEveryOutputUntouched() throws Exception {
        Path invalidZip = temporaryDirectory.resolve("invalid.zip");
        writeZip(invalidZip, "apple.json", "{broken");
        var options = options(invalidZip, false);
        Map<Path, byte[]> before = seedOutputs(options, "sentinel");

        int exit = CatalogEvolutionUpdater.execute(options, sink(), sink());

        assertEquals(3, exit);
        before.forEach((path, bytes) -> assertTrue(java.util.Arrays.equals(bytes, read(path)), path.toString()));
    }

    @Test
    void conflictingZipLeavesEveryOutputUntouched() throws Exception {
        Path conflictingZip = temporaryDirectory.resolve("conflicting.zip");
        writeZip(conflictingZip, Map.of(
                "first/apple.json", modelJson(1000, "food/apple_pie"),
                "second/apple.json", modelJson(1000, "food/god_apple")));
        var options = options(conflictingZip, false);
        Map<Path, byte[]> before = seedOutputs(options, "sentinel");

        int exit = CatalogEvolutionUpdater.execute(options, sink(), sink());

        assertEquals(2, exit);
        before.forEach((path, bytes) -> assertTrue(java.util.Arrays.equals(bytes, read(path)), path.toString()));
    }

    @Test
    void dryRunChangesNothingOnDisk() throws Exception {
        Path catalog = temporaryDirectory.resolve("items.yml");
        Path queue = temporaryDirectory.resolve("queue.yml");
        Files.copy(Path.of("src/main/resources/items.yml"), catalog);
        Files.copy(Path.of("generated/recycling-decision-queue.yml"), queue);
        var options = new CatalogEvolutionUpdater.Options(Path.of("items.zip"), catalog,
                temporaryDirectory.resolve("catalog-report.txt"), temporaryDirectory.resolve("snapshot.yml"),
                temporaryDirectory.resolve("evolution.txt"), queue, temporaryDirectory.resolve("queue-report.txt"),
                temporaryDirectory.resolve("panel.html"), temporaryDirectory.resolve("panel-report.txt"), true);
        byte[] catalogBefore = Files.readAllBytes(catalog);
        byte[] queueBefore = Files.readAllBytes(queue);

        assertEquals(0, CatalogEvolutionUpdater.execute(options, sink(), sink()));
        assertTrue(java.util.Arrays.equals(catalogBefore, Files.readAllBytes(catalog)));
        assertTrue(java.util.Arrays.equals(queueBefore, Files.readAllBytes(queue)));
        assertFalse(Files.exists(options.snapshot()));
        assertFalse(Files.exists(options.panel()));
    }

    @Test
    void removedIdentityCannotEnterRuntime() {
        var before = snapshot(1, "old");
        var after = new CatalogEvolutionUpdater.Snapshot(Map.of());
        var updated = CatalogEvolutionUpdater.updateQueue(queue(List.of(queueItem(0, approved(3)))),
                CatalogEvolutionUpdater.diff(before, after), after);

        assertTrue(updated.items().isEmpty());
        assertEquals(0, RecyclingRuntimeCompiler.compile(updated, Map.of()).registry().size());
    }

    @Test
    void repositoryBaselineHasExactly1757UnchangedIdentities() throws Exception {
        var generated = ItemCatalogGenerator.generate(Path.of("items.zip"));
        var current = ItemEconomyAnalyzer.Catalog.parse(
                Files.readString(Path.of("src/main/resources/items.yml"), StandardCharsets.UTF_8));
        var baseline = CatalogEvolutionUpdater.bootstrapSnapshot(generated, current);
        var changes = CatalogEvolutionUpdater.diff(baseline,
                CatalogEvolutionUpdater.snapshotOf(generated.items()));

        assertEquals(1757, count(changes, UNCHANGED));
        assertEquals(0, count(changes, NEW));
        assertEquals(0, count(changes, CHANGED));
        assertEquals(0, count(changes, REMOVED));
    }

    @Test
    void newAndChangedItemsAreNeverAutomaticallyApproved() {
        var before = snapshot(Map.of("apple:1000", snapshotItem(0, "old/path", "old")));
        var after = snapshot(Map.of(
                "apple:1000", snapshotItem(0, "changed/path", "changed"),
                "apple:1001", snapshotItem(1, "new/path", "new")));
        var updated = CatalogEvolutionUpdater.updateQueue(queue(List.of(queueItem(0, approved(3)))),
                CatalogEvolutionUpdater.diff(before, after), after);

        assertEquals(2, updated.items().size());
        assertTrue(updated.items().stream().allMatch(item -> item.decision().status() == PENDING));
    }

    @Test
    void aSecondChangeGetsANewLogicalIdAndCannotReuseThePreviousReview() {
        var first = snapshot(Map.of("apple:1000", snapshotItem(0, "food/first", "first")));
        var second = snapshot(Map.of("apple:1000", snapshotItem(0, "food/second", "second")));
        var third = snapshot(Map.of("apple:1000", snapshotItem(0, "food/third", "third")));
        var firstUpdate = CatalogEvolutionUpdater.updateQueue(queue(List.of(queueItem(0, approved(3)))),
                CatalogEvolutionUpdater.diff(first, second), second);
        String reviewedChangedId = firstUpdate.items().getFirst().logicalId();
        var reviewedQueue = new RecyclingDecisionQueueGenerator.DecisionQueue(List.of(
                withDecision(firstUpdate.items().getFirst(), approved(4))));

        var secondUpdate = CatalogEvolutionUpdater.updateQueue(reviewedQueue,
                CatalogEvolutionUpdater.diff(second, third), third);

        assertEquals(PENDING, secondUpdate.items().getFirst().decision().status());
        assertFalse(reviewedChangedId.equals(secondUpdate.items().getFirst().logicalId()));
        assertEquals(reviewedChangedId,
                secondUpdate.items().getFirst().catalogEvolution().previousLogicalId());
        assertThrows(IllegalArgumentException.class, () -> RecyclingRuntimeCompiler.compile(secondUpdate,
                Map.of(reviewedChangedId, reviewApproved(4))));
    }

    @Test
    void snapshotSerializationIsDeterministicAndRoundTrips() {
        var snapshot = snapshot(3, "stable");
        String yaml = CatalogEvolutionUpdater.renderSnapshot(snapshot);

        assertEquals(yaml, CatalogEvolutionUpdater.renderSnapshot(snapshot));
        assertEquals(snapshot, CatalogEvolutionUpdater.parseSnapshot(yaml));
    }

    private CatalogEvolutionUpdater.Options options(Path source, boolean dryRun) {
        return new CatalogEvolutionUpdater.Options(source, temporaryDirectory.resolve("items.yml"),
                temporaryDirectory.resolve("catalog-report.txt"), temporaryDirectory.resolve("snapshot.yml"),
                temporaryDirectory.resolve("evolution.txt"), temporaryDirectory.resolve("queue.yml"),
                temporaryDirectory.resolve("queue-report.txt"), temporaryDirectory.resolve("panel.html"),
                temporaryDirectory.resolve("panel-report.txt"), dryRun);
    }

    private static Map<Path, byte[]> seedOutputs(CatalogEvolutionUpdater.Options options, String value)
            throws Exception {
        Map<Path, byte[]> values = new TreeMap<>();
        for (Path path : List.of(options.catalog(), options.catalogReport(), options.snapshot(),
                options.evolutionReport(), options.queue(), options.queueReport(), options.panel(),
                options.panelReport())) {
            Files.writeString(path, value, StandardCharsets.UTF_8);
            values.put(path, Files.readAllBytes(path));
        }
        return values;
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static PrintStream sink() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    private static void writeZip(Path path, String name, String content) throws Exception {
        writeZip(path, Map.of(name, content));
    }

    private static void writeZip(Path path, Map<String, String> entries) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, String> entry : new TreeMap<>(entries).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private static String modelJson(int customModelData, String modelPath) {
        return """
                {"model":{"type":"range_dispatch","property":"custom_model_data","entries":[
                  {"threshold":%d,"model":{"type":"model","model":"%s"}}
                ]}}
                """.formatted(customModelData, modelPath);
    }

    private static long count(Map<String, CatalogEvolutionUpdater.Change> changes,
                              CatalogEvolutionUpdater.ChangeStatus status) {
        return changes.values().stream().filter(change -> change.status() == status).count();
    }

    private static CatalogEvolutionUpdater.Snapshot snapshot(int count, String fingerprintPrefix) {
        return CatalogEvolutionUpdater.snapshotOf(items(count, fingerprintPrefix));
    }

    private static CatalogEvolutionUpdater.Snapshot snapshot(
            Map<String, CatalogEvolutionUpdater.SnapshotItem> items
    ) {
        return new CatalogEvolutionUpdater.Snapshot(items);
    }

    private static CatalogEvolutionUpdater.SnapshotItem snapshotItem(
            int index, String modelPath, String fingerprint
    ) {
        return new CatalogEvolutionUpdater.SnapshotItem("apple", 1000 + index,
                modelPath.substring(modelPath.lastIndexOf('/') + 1), modelPath, fingerprint);
    }

    private static List<ItemCatalogGenerator.CatalogItem> items(int count, String fingerprintPrefix) {
        List<ItemCatalogGenerator.CatalogItem> items = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            items.add(item(index, "model/" + index, fingerprintPrefix + "-" + index));
        }
        return items;
    }

    private static ItemCatalogGenerator.CatalogItem item(int index, String path, String fingerprint) {
        return new ItemCatalogGenerator.CatalogItem("apple", 1000 + index,
                path.substring(path.lastIndexOf('/') + 1), path, "misc", "", "", 0, fingerprint);
    }

    private static ItemCatalogGenerator.GenerationResult generation(
            List<ItemCatalogGenerator.CatalogItem> items
    ) {
        return new ItemCatalogGenerator.GenerationResult(1, items, items.size(), items.size(),
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static ItemEconomyAnalyzer.Catalog catalog(List<ItemEconomyAnalyzer.CatalogRecord> records) {
        return new ItemEconomyAnalyzer.Catalog(records);
    }

    private static ItemEconomyAnalyzer.CatalogRecord record(
            int index, String wiki, String name, int shards
    ) {
        return new ItemEconomyAnalyzer.CatalogRecord("apple:" + (1000 + index), "apple", 1000 + index,
                "model/" + index, wiki, name, shards);
    }

    private static RecyclingDecisionQueueGenerator.DecisionQueue queue(
            List<RecyclingDecisionQueueGenerator.QueueItem> items
    ) {
        return new RecyclingDecisionQueueGenerator.DecisionQueue(items);
    }

    private static RecyclingDecisionQueueGenerator.QueueItem queueItem(
            int index, RecyclingDecisionQueueGenerator.Decision decision
    ) {
        return new RecyclingDecisionQueueGenerator.QueueItem("item-" + index, "Item " + index,
                "Wiki_" + index, RecyclingDecisionQueueGenerator.MappingStatus.MAPPED,
                RecyclingDecisionQueueGenerator.Priority.LOW, "review", List.of(
                new RecyclingDecisionQueueGenerator.Identity("apple", 1000 + index, "model/" + index)),
                new RecyclingDecisionQueueGenerator.Acquisition("UNKNOWN", Set.of("UNKNOWN")), List.of(),
                new RecyclingDecisionQueueGenerator.SystemProposal(
                        RecyclingDecisionQueueGenerator.SystemProposalValue.UNKNOWN, "unknown"), decision);
    }

    private static RecyclingDecisionQueueGenerator.Decision approved(int shards) {
        return new RecyclingDecisionQueueGenerator.Decision(APPROVED, true, shards,
                "reviewer", "2026-09-03T20:00:00Z", "");
    }

    private static RecyclingDecisionQueueGenerator.QueueItem withDecision(
            RecyclingDecisionQueueGenerator.QueueItem item,
            RecyclingDecisionQueueGenerator.Decision decision
    ) {
        return new RecyclingDecisionQueueGenerator.QueueItem(item.logicalId(), item.name(), item.wiki(),
                item.mappingStatus(), item.priority(), item.reviewReason(), item.identities(), item.acquisition(),
                item.evidence(), item.systemProposal(), item.catalogEvolution(), decision);
    }

    private static RecyclingReviewPanelGenerator.ReviewDecision reviewApproved(int shards) {
        return RecyclingReviewPanelGenerator.validateReviewDecision(
                "APPROVED", true, shards, "reviewer", "2026-09-03T20:00:00Z", "");
    }
}

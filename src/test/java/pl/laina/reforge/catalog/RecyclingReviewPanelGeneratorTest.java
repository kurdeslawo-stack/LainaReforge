package pl.laina.reforge.catalog;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.DecisionStatus.APPROVED;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.DecisionStatus.REJECTED;

class RecyclingReviewPanelGeneratorTest {
    private static final Path QUEUE_PATH = Path.of("generated/recycling-decision-queue.yml");
    private static RecyclingDecisionQueueGenerator.DecisionQueue queue;
    private static String queueYaml;

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void loadQueue() throws Exception {
        queueYaml = Files.readString(QUEUE_PATH, StandardCharsets.UTF_8);
        queue = RecyclingReviewPanelGenerator.parseQueue(queueYaml);
    }

    @Test
    void panelContainsAll1590LogicalReviewEntries() {
        String html = RecyclingReviewPanelGenerator.renderPanel(queue);

        assertEquals(1590, queue.items().size());
        assertEquals(1590, occurrences(html, "\"id\":"));
        for (var item : queue.items()) {
            assertTrue(html.contains("\"id\":\"" + jsonEscape(item.logicalId()) + "\""), item.logicalId());
        }
    }

    @Test
    void preservesEveryIdentity() {
        assertEquals(1757, queue.identityCount());
        assertEquals(858, queue.mappedIdentityCount());
        assertEquals(899, queue.unmappedIdentityCount());
        assertEquals(1757, queue.items().stream().mapToInt(item -> item.identities().size()).sum());
        String html = RecyclingReviewPanelGenerator.renderPanel(queue);
        for (var item : queue.items()) {
            for (var identity : item.identities()) {
                assertTrue(html.contains("\"modelPath\":\"" + jsonEscape(identity.modelPath()) + "\""));
            }
        }
    }

    @Test
    void rejectedDecisionHasSafeSemantics() {
        var decision = RecyclingReviewPanelGenerator.validateReviewDecision(
                "REJECTED", false, 0, "reviewer", "2026-09-02T18:00:00Z", "notatka");

        assertEquals(REJECTED, decision.status());
        assertFalse(decision.recyclable());
        assertEquals(0, decision.shards());
    }

    @Test
    void approvedDecisionKeepsPositiveCustomShards() {
        var decision = RecyclingReviewPanelGenerator.validateReviewDecision(
                "APPROVED", true, 17, "reviewer", "2026-09-02T18:00:00Z", "");

        assertEquals(APPROVED, decision.status());
        assertTrue(decision.recyclable());
        assertEquals(17, decision.shards());
    }

    @Test
    void customShardSafetyAcceptsBoundaryValues() {
        assertEquals(1, approved(1, "2026-09-02T18:00:00Z").shards());
        assertEquals(256, approved(256, "2026-09-02T18:00:00Z").shards());
    }

    @Test
    void customShardSafetyRejectsValuesOutsideBoundary() {
        assertThrows(IllegalArgumentException.class, () -> approved(0, "2026-09-02T18:00:00Z"));
        assertThrows(IllegalArgumentException.class, () -> approved(257, "2026-09-02T18:00:00Z"));
    }

    @Test
    void invalidCustomShardsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RecyclingReviewPanelGenerator.validateReviewDecision(
                "APPROVED", true, 0, "", "2026-09-02T18:00:00Z", ""));
        assertThrows(IllegalArgumentException.class, () -> RecyclingReviewPanelGenerator.validateReviewDecision(
                "APPROVED", true, -2, "", "2026-09-02T18:00:00Z", ""));
        assertTrue(RecyclingReviewPanelGenerator.renderPanel(queue)
                .contains("Podaj liczbę całkowitą od 1 do ${MAX_SHARDS_PER_ITEM}."));
    }

    @Test
    void importsValidDecisions() {
        Map<String, RecyclingReviewPanelGenerator.ReviewDecision> imported =
                RecyclingReviewPanelGenerator.parseDecisionImport(validImport("Epicki_Szlamowy_Miecz"),
                        logicalIds());

        assertEquals(1, imported.size());
        assertEquals(APPROVED, imported.get("Epicki_Szlamowy_Miecz").status());
        assertEquals(3, imported.get("Epicki_Szlamowy_Miecz").shards());
        assertEquals("e2ot3rror", imported.get("Epicki_Szlamowy_Miecz").reviewedBy());
    }

    @Test
    void importsDecisionForUnmappedIdentity() {
        String logicalId = queue.items().stream()
                .filter(item -> item.mappingStatus()
                        == RecyclingDecisionQueueGenerator.MappingStatus.UNMAPPED)
                .findFirst().orElseThrow().logicalId();

        Map<String, RecyclingReviewPanelGenerator.ReviewDecision> imported =
                RecyclingReviewPanelGenerator.parseDecisionImport(validImport(logicalId), logicalIds());

        assertEquals(1, imported.size());
        assertEquals(APPROVED, imported.get(logicalId).status());
        assertEquals(3, imported.get(logicalId).shards());
    }

    @Test
    void rejectsUnknownImportedItem() {
        assertThrows(IllegalArgumentException.class, () ->
                RecyclingReviewPanelGenerator.parseDecisionImport(validImport("Nie_Istnieje"), logicalIds()));
    }

    @Test
    void rejectsInvalidImportedDecisionState() {
        String invalid = validImport("Epicki_Szlamowy_Miecz")
                .replace("status: APPROVED", "status: PENDING");

        assertThrows(IllegalArgumentException.class, () ->
                RecyclingReviewPanelGenerator.parseDecisionImport(invalid, logicalIds()));
    }

    @Test
    void rejectsNonIsoReviewTimestamp() {
        String invalid = validImport("Epicki_Szlamowy_Miecz")
                .replace("2026-09-02T18:00:00Z", "wczoraj");

        assertThrows(IllegalArgumentException.class, () ->
                RecyclingReviewPanelGenerator.parseDecisionImport(invalid, logicalIds()));
    }

    @Test
    void localStorageKeyIsStable() {
        assertEquals("laina-reforge.recycling-decisions.v1",
                RecyclingReviewPanelGenerator.LOCAL_STORAGE_KEY);
        assertTrue(RecyclingReviewPanelGenerator.renderPanel(queue)
                .contains("const STORAGE_KEY = \"laina-reforge.recycling-decisions.v1\";"));
    }

    @Test
    void filtersDoNotModifyEmbeddedQueue() {
        String before = RecyclingDecisionQueueGenerator.renderQueue(queue);
        String html = RecyclingReviewPanelGenerator.renderPanel(queue);
        String after = RecyclingDecisionQueueGenerator.renderQueue(queue);

        assertEquals(before, after);
        assertTrue(html.contains("visibleItems=QUEUE.filter("));
        assertFalse(html.contains("QUEUE.splice("));
        assertFalse(html.contains("QUEUE.sort("));
    }

    @Test
    void progressSeparatesMappedAndUnmappedItemsAndPendingPriorities() {
        var mapped = queue.items().stream().filter(item -> item.mappingStatus()
                == RecyclingDecisionQueueGenerator.MappingStatus.MAPPED).findFirst().orElseThrow();
        var unmapped = queue.items().stream().filter(item -> item.mappingStatus()
                == RecyclingDecisionQueueGenerator.MappingStatus.UNMAPPED).findFirst().orElseThrow();
        var progress = RecyclingReviewPanelGenerator.calculateProgress(queue, Map.of(
                mapped.logicalId(), approved(1, "2026-09-02T18:00:00Z"),
                unmapped.logicalId(), RecyclingReviewPanelGenerator.validateReviewDecision(
                        "REJECTED", false, 0, "", "2026-09-02T18:01:00Z", "")));

        assertEquals(2, progress.reviewed());
        assertEquals(1588, progress.pending());
        assertEquals(1, progress.mappedReviewed());
        assertEquals(691, progress.mappedTotal());
        assertEquals(1, progress.unmappedReviewed());
        assertEquals(899, progress.unmappedTotal());
        assertEquals(progress.pending(), progress.highPending() + progress.mediumPending() + progress.lowPending());
        assertTrue(progress.partialExport());
    }

    @Test
    void completedProgressDoesNotNeedPartialExportWarning() {
        Map<String, RecyclingReviewPanelGenerator.ReviewDecision> decisions = queue.items().stream()
                .collect(Collectors.toMap(RecyclingDecisionQueueGenerator.QueueItem::logicalId,
                        item -> approved(1, "2026-09-02T18:00:00Z")));

        assertFalse(RecyclingReviewPanelGenerator.calculateProgress(queue, decisions).partialExport());
    }

    @Test
    void searchFindsLogicalIdAndMaterialCmdIdentity() {
        var item = queue.items().get(0);
        var identity = item.identities().get(0);

        assertTrue(RecyclingReviewPanelGenerator.matchesSearch(item, item.logicalId()));
        assertTrue(RecyclingReviewPanelGenerator.matchesSearch(item,
                identity.material() + ":" + identity.cmd()));
        assertFalse(RecyclingReviewPanelGenerator.matchesSearch(item, "definitely-not-an-item"));
    }

    @Test
    void editingDecisionCreatesFreshReviewTimestamp() {
        var before = approved(2, "2026-09-02T18:00:00Z");
        var after = approved(4, "2026-09-02T18:05:00Z");

        assertEquals(2, before.shards());
        assertEquals(4, after.shards());
        assertFalse(before.reviewedAt().equals(after.reviewedAt()));
    }

    @Test
    void panelIncludesResetAndExportSafetyWorkflow() {
        String html = RecyclingReviewPanelGenerator.renderPanel(queue);

        assertTrue(html.contains("Reset usunie ${count} lokalnych decyzji"));
        assertTrue(html.contains("Potwierdź ponownie: usunąć wszystkie lokalne decyzje?"));
        assertTrue(html.contains("Ten eksport nie obejmuje wszystkich itemów."));
        assertTrue(html.contains("BACKUP DECISIONS"));
        assertTrue(html.contains("LAST_CHANGE_KEY"));
    }

    @Test
    void importExportFormatRemainsRoundTripCompatible() {
        String yaml = validImport("Epicki_Szlamowy_Miecz");
        var first = RecyclingReviewPanelGenerator.parseDecisionImport(yaml, logicalIds());
        var decision = first.get("Epicki_Szlamowy_Miecz");
        String exported = validImport("Epicki_Szlamowy_Miecz")
                .replace("shards: 3", "shards: " + decision.shards());

        assertEquals(first, RecyclingReviewPanelGenerator.parseDecisionImport(exported, logicalIds()));
    }

    @Test
    void panelIsSelfContainedAndOffersRequiredActions() {
        String html = RecyclingReviewPanelGenerator.renderPanel(queue);

        assertTrue(html.contains("EXPORT DECISIONS"));
        assertTrue(html.contains("IMPORT DECISIONS"));
        assertTrue(html.contains("RESET LOCAL DECISIONS"));
        assertTrue(html.contains("ODRZUĆ"));
        assertTrue(html.contains("5 SHARDS"));
        assertTrue(html.contains("POMIŃ"));
        assertTrue(html.contains("https://wiki.laina.pl/index.php?title="));
        assertTrue(html.contains("Mapping <select id=\"mappingFilter\""));
        assertTrue(html.contains("Catalog identities</span><b>1757</b>"));
        assertTrue(html.contains("Mapped identities</span><b>858</b>"));
        assertTrue(html.contains("Unmapped identities</span><b>899</b>"));
        assertTrue(html.contains("Coverage</span><b>1757 / 1757</b>"));
        assertTrue(html.contains("BRAK WIKI"));
        assertTrue(html.contains("if(item.mappingStatus==='MAPPED')"));
        assertTrue(html.contains("min=\"1\" max=\"256\""));
        assertTrue(html.contains("Następny wymagający decyzji"));
        assertTrue(html.contains("data-queue=\"UNMAPPED\""));
        assertTrue(html.contains("Globalnie ${GLOBAL_INDEX.get(item.id)}"));
        assertTrue(html.contains("event.key==='a'||event.key==='A'"));
        assertFalse(html.contains("fetch("));
        assertFalse(html.contains("<script src="));
        assertFalse(html.contains("<link rel=\"stylesheet\""));
    }

    @Test
    void generationIsDeterministic() {
        assertEquals(RecyclingReviewPanelGenerator.renderPanel(queue),
                RecyclingReviewPanelGenerator.renderPanel(queue));
    }

    @Test
    void generatorDoesNotChangeItemsYaml() throws Exception {
        Path catalog = Path.of("src/main/resources/items.yml");
        byte[] before = Files.readAllBytes(catalog);
        Path output = temporaryDirectory.resolve("panel/index.html");
        Path report = temporaryDirectory.resolve("report.txt");
        var options = new RecyclingReviewPanelGenerator.Options(QUEUE_PATH, output, report);

        int exit = RecyclingReviewPanelGenerator.execute(options,
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, exit);
        assertTrue(Files.size(output) > 0);
        assertTrue(Files.readString(report).contains("Generator self-tests: PASS"));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(catalog)));
    }

    private static Set<String> logicalIds() {
        return queue.items().stream().map(RecyclingDecisionQueueGenerator.QueueItem::logicalId)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static RecyclingReviewPanelGenerator.ReviewDecision approved(int shards, String reviewedAt) {
        return RecyclingReviewPanelGenerator.validateReviewDecision(
                "APPROVED", true, shards, "reviewer", reviewedAt, "");
    }

    private static String validImport(String logicalId) {
        return """
                items:
                  "%s":
                    status: APPROVED
                    recyclable: true
                    shards: 3
                    reviewed_by: "e2ot3rror"
                    reviewed_at: "2026-09-02T18:00:00Z"
                    note: "sprawdzone"
                """.formatted(logicalId);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

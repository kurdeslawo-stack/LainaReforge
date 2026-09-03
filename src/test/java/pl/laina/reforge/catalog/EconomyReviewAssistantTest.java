package pl.laina.reforge.catalog;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyReviewAssistantTest {
    private static RecyclingDecisionQueueGenerator.DecisionQueue baseline;

    @BeforeAll
    static void loadBaseline() throws Exception {
        baseline = RecyclingReviewPanelGenerator.parseQueue(Files.readString(
                Path.of("generated/recycling-decision-queue.yml"), StandardCharsets.UTF_8));
    }

    @Test
    void threeApprovedPeersHaveMedianFour() {
        var statistics = EconomyReviewAssistant.statistics(List.of(2, 4, 6));

        assertTrue(statistics.sufficient());
        assertEquals(4.0, statistics.median());
        assertEquals(4.0, statistics.average());
    }

    @Test
    void valueTwentyAgainstMedianFourIsHighOutlier() {
        var risk = EconomyReviewAssistant.analyze(item("target", Set.of()), 20,
                EconomyReviewAssistant.statistics(List.of(2, 4, 6)));

        assertTrue(risk.flags().contains("OUTLIER HIGH"));
        assertEquals(5.0, risk.medianRatio());
    }

    @Test
    void valueOneAgainstMedianFourIsLowOutlier() {
        var risk = EconomyReviewAssistant.analyze(item("target", Set.of()), 1,
                EconomyReviewAssistant.statistics(List.of(2, 4, 6)));

        assertTrue(risk.flags().contains("OUTLIER LOW"));
        assertEquals(0.25, risk.medianRatio());
    }

    @Test
    void farmableItemWithHighValueHasHighRisk() {
        var risk = EconomyReviewAssistant.analyze(item("farmable", Set.of("INFINITE_OR_FARMABLE")), 12,
                EconomyReviewAssistant.statistics(List.of()));

        assertEquals(EconomyReviewAssistant.RiskLevel.HIGH, risk.level());
        assertTrue(risk.flags().contains("HIGH ECONOMY RISK"));
    }

    @Test
    void noApprovedPeersDoesNotCreateFalseStatistics() {
        var statistics = EconomyReviewAssistant.statistics(List.of());

        assertFalse(statistics.sufficient());
        assertEquals(0, statistics.count());
        assertEquals(0.0, statistics.median());
    }

    @Test
    void oneOrTwoPeersRemainInsufficient() {
        assertFalse(EconomyReviewAssistant.statistics(List.of(4)).sufficient());
        assertFalse(EconomyReviewAssistant.statistics(List.of(2, 6)).sufficient());
    }

    @Test
    void similarItemControlNavigatesWithoutMakingDecision() {
        String html = RecyclingReviewPanelGenerator.renderPanel(baseline);

        assertTrue(html.contains("currentId=peer.id;selectedShards=null;showView('review');render()"));
        assertFalse(functionLine(html, "renderEconomyAssistant").contains("appendHistory"));
        assertFalse(functionLine(html, "renderEconomyAssistant").contains("localStorage"));
    }

    @Test
    void shardAnalysisDoesNotWriteDecisionOrHistory() {
        String body = functionLine(RecyclingReviewPanelGenerator.renderPanel(baseline), "stageShards");

        assertFalse(body.contains("localStorage"));
        assertFalse(body.contains("appendHistory"));
        assertFalse(body.contains("decisions["));
    }

    @Test
    void economyOverviewDoesNotWriteHistory() {
        String body = functionLine(RecyclingReviewPanelGenerator.renderPanel(baseline), "renderEconomyOverview");

        assertFalse(body.contains("appendHistory"));
        assertFalse(body.contains("localStorage"));
        assertFalse(body.contains("history="));
    }

    @Test
    void cleanBaselineKeepsFullCoverageAndZeroAutomaticApprovals() {
        assertEquals(1590, baseline.items().size());
        assertEquals(1757, baseline.identityCount());
        assertEquals(0, baseline.items().stream()
                .filter(item -> item.decision().status()
                        == RecyclingDecisionQueueGenerator.DecisionStatus.APPROVED)
                .count());
    }

    @Test
    void panelUsesSharedRuntimeLimitsAndKeepsImportExportFormat() {
        String html = RecyclingReviewPanelGenerator.renderPanel(baseline);

        assertTrue(html.contains("const MAX_SHARDS_PER_ITEM = 256;"));
        assertTrue(html.contains("const MAX_SHARDS_PER_TRANSACTION = 4096;"));
        assertTrue(html.contains("function decisionYaml()"));
        assertTrue(html.contains("function parseImport(yaml)"));
        assertTrue(html.contains("Podejrzane wyceny"));
        assertTrue(html.contains(">Ekonomia<"));
    }

    private static RecyclingDecisionQueueGenerator.QueueItem item(String id, Set<String> tags) {
        return new RecyclingDecisionQueueGenerator.QueueItem(id, id, "Wiki_" + id,
                RecyclingDecisionQueueGenerator.MappingStatus.MAPPED,
                RecyclingDecisionQueueGenerator.Priority.MEDIUM, "review",
                List.of(new RecyclingDecisionQueueGenerator.Identity("diamond_sword", 1000,
                        "swords/" + id)),
                new RecyclingDecisionQueueGenerator.Acquisition("summary", tags), List.of("evidence"),
                new RecyclingDecisionQueueGenerator.SystemProposal(
                        RecyclingDecisionQueueGenerator.SystemProposalValue.UNKNOWN, "reason"),
                RecyclingDecisionQueueGenerator.Decision.pending());
    }

    private static String functionLine(String html, String name) {
        String marker = "function " + name + "(";
        int start = html.indexOf(marker);
        int end = html.indexOf('\n', start);
        return html.substring(start, end);
    }
}

package pl.laina.reforge.catalog;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerAcceptancePreparationTest {
    @Test
    void fixtureLogicalItemsAndIdentitiesExistInProductionQueue() throws Exception {
        var queue = RecyclingReviewPanelGenerator.parseQueue(Files.readString(
                Path.of("generated/recycling-decision-queue.yml"), StandardCharsets.UTF_8));
        Set<String> logicalIds = queue.items().stream()
                .map(RecyclingDecisionQueueGenerator.QueueItem::logicalId).collect(java.util.stream.Collectors.toSet());
        assertTrue(logicalIds.containsAll(Set.of("Ametystowa_Marchew", "Ametystowa_Rybka",
                "Ametystowy_Burak", "Ametystowy_Ziemniak",
                "unmapped::carved_pumpkin:2350507", "unmapped::echo_shard:2350507")));
    }

    @Test
    void checklistUsesExactSameCmdDifferentMaterialPair() throws Exception {
        String checklist = read("generated/player-acceptance-checklist.txt");
        assertTrue(checklist.contains("carved_pumpkin:2350507"));
        assertTrue(checklist.contains("echo_shard:2350507"));
        assertTrue(checklist.contains("floats:[2350507.0f]"));
    }

    @Test
    void everyPlayerResultStartsNotRun() throws Exception {
        String result = read("generated/player-acceptance-result.txt");
        assertTrue(result.contains("PLAYER ACCEPTANCE: NOT RUN"));
        assertEquals(20, result.lines().filter(line -> line.endsWith(": NOT RUN")).count());
        assertFalse(result.contains(": PASS"));
    }

    @Test
    void prepareUsesIgnoredFixtureAndNeverProductionFiles() throws Exception {
        String script = read("tools/prepare-player-test.ps1");
        assertTrue(script.contains("target\\player-acceptance"));
        assertTrue(script.contains("target\\player-acceptance-backup"));
        assertTrue(script.contains("[string]::Join([Environment]::NewLine, $manifestLines)"));
        assertFalse(script.contains("src\\main\\resources\\recycling-runtime.yml"));
        assertFalse(script.contains("recycling-decisions.yml'\n+$runtimeTarget"));
    }

    @Test
    void restoreHasServerGuardAndNonMutatingVerificationMode() throws Exception {
        String script = read("tools/restore-after-player-test.ps1");
        assertTrue(script.contains("[switch]$VerifyOnly"));
        assertTrue(script.contains("Get-NetTCPConnection"));
        assertTrue(script.indexOf("Assert-ServerStopped") < script.indexOf("Copy-Item -LiteralPath $jarBackup"));
        assertTrue(script.contains("RESTORE READY"));
        assertTrue(script.contains("kontroli integralnosci SHA-256"));
        assertTrue(script.contains("Backup pochodzi z innej sciezki serwera"));
    }

    @Test
    void productionDecisionAndRuntimeBaselinesRemainEmpty() throws Exception {
        String decisions = read("recycling-decisions.yml");
        String runtime = read("src/main/resources/recycling-runtime.yml");
        assertEquals(0, decisions.lines().filter(line -> line.startsWith("  \"")).count());
        assertTrue(runtime.contains("items: {}"));
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}

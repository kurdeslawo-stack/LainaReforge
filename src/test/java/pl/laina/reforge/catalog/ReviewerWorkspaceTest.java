package pl.laina.reforge.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewerWorkspaceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void currentPanelAndQueuePassPreflight() throws Exception {
        Path root = workspaceCopy();

        ProcessResult result = startPreflight(root);

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("[OK] Panel"));
        assertTrue(result.output().contains("PREFLIGHT_CONSISTENCY_PASS"));
    }

    @Test
    void missingPanelFailsWithFriendlyMessage() throws Exception {
        Path root = workspaceCopy();
        Files.delete(root.resolve("generated/recycling-review-panel/index.html"));

        ProcessResult result = startPreflight(root);

        assertEquals(1, result.exitCode());
        assertTrue(result.output().contains("Brak wymaganego pliku: Panel"));
        assertFalse(result.output().contains("NoSuchFileException"));
    }

    @Test
    void missingQueueFailsClosed() throws Exception {
        Path root = workspaceCopy();
        Files.delete(root.resolve("generated/recycling-decision-queue.yml"));

        ProcessResult result = startPreflight(root);

        assertEquals(1, result.exitCode());
        assertTrue(result.output().contains("Brak wymaganego pliku: Queue"));
    }

    @Test
    void stalePanelAgainstChangedQueueFailsClosed() throws Exception {
        Path root = workspaceCopy();
        Files.writeString(root.resolve("generated/recycling-decision-queue.yml"),
                "\n# simulated catalog update\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        ProcessResult result = startPreflight(root);

        assertEquals(1, result.exitCode());
        assertTrue(result.output().contains("STALE_PANEL"));
    }

    @Test
    void startPreflightDoesNotChangeDecisionOrHistoryFiles() throws Exception {
        Path root = workspaceCopy();
        Path decisions = root.resolve("recycling-decisions.yml");
        Path history = root.resolve("recycling-decision-history.yml");
        Files.writeString(decisions, "decisions-sentinel", StandardCharsets.UTF_8);
        Files.writeString(history, "history-sentinel", StandardCharsets.UTF_8);
        byte[] decisionsBefore = Files.readAllBytes(decisions);
        byte[] historyBefore = Files.readAllBytes(history);

        assertEquals(0, startPreflight(root).exitCode());
        assertTrue(Arrays.equals(decisionsBefore, Files.readAllBytes(decisions)));
        assertTrue(Arrays.equals(historyBefore, Files.readAllBytes(history)));
    }

    @Test
    void readinessUiClassifiesPendingEvolutionAndEconomyRisk() throws Exception {
        String html = Files.readString(Path.of("generated/recycling-review-panel/index.html"),
                StandardCharsets.UTF_8);
        String readiness = functionLine(html, "showReadiness");

        assertTrue(readiness.contains("groups.BLOCKING.push(`${s.pending} itemów bez decyzji`)"));
        assertTrue(readiness.contains("groups.BLOCKING.push(`${s.newPending} NEW itemów bez decyzji`)"));
        assertTrue(readiness.contains("groups.BLOCKING.push(`${s.changedPending} CHANGED itemów bez decyzji`)"));
        assertTrue(readiness.contains("groups.WARNING.push(`${s.highRisk} zatwierdzonych itemów ma HIGH ECONOMY RISK`)"));
        assertFalse(readiness.contains("groups.BLOCKING.push(`${s.highRisk}"));
    }

    @Test
    void sessionReportIsSafeAndContainsNoItemsZipPayload() throws Exception {
        String html = Files.readString(Path.of("generated/recycling-review-panel/index.html"),
                StandardCharsets.UTF_8);
        String report = functionLine(html, "sessionReport");

        assertTrue(report.contains("LainaReforge Review Session"));
        assertTrue(report.contains("Decisions file format version: 1"));
        assertTrue(report.contains("queue SHA-256"));
        assertFalse(report.toLowerCase().contains("items.zip"));
    }

    @Test
    void invalidDecisionsDoNotModifyProductionRuntime() throws Exception {
        Path decisions = temporaryDirectory.resolve("invalid-decisions.yml");
        Files.writeString(decisions, "items:\n  broken: true\n", StandardCharsets.UTF_8);
        Path productionRuntime = Path.of("src/main/resources/recycling-runtime.yml");
        byte[] runtimeBefore = Files.readAllBytes(productionRuntime);

        ProcessResult result = validate(decisions, temporaryDirectory.resolve("invalid-validation"));

        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains("NOT READY FOR DEPLOYMENT"));
        assertTrue(Arrays.equals(runtimeBefore, Files.readAllBytes(productionRuntime)));
    }

    @Test
    void validEmptyDecisionExportPassesTechnicalValidation() throws Exception {
        Path decisions = temporaryDirectory.resolve("valid-decisions.yml");
        Files.writeString(decisions, "items:\n", StandardCharsets.UTF_8);

        ProcessResult result = validate(decisions, temporaryDirectory.resolve("valid-validation"));

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Decisions: PASS"));
        assertTrue(result.output().contains("Compiler: PASS"));
        assertTrue(result.output().contains("Runtime validation: PASS"));
        assertTrue(result.output().contains("PENDING: 1590"));
    }

    @Test
    void launchersAreReviewerFriendlyAndNeverDeploy() throws Exception {
        String start = Files.readString(Path.of("START-REVIEW.cmd"), StandardCharsets.UTF_8);
        String validation = Files.readString(Path.of("tools/validate-decisions.ps1"), StandardCharsets.UTF_8);

        assertTrue(start.contains("-NoProfile"));
        assertTrue(start.contains("-ExecutionPolicy Bypass"));
        assertFalse(validation.contains("DeployPath"));
        assertFalse(validation.contains("Start-Process"));
    }

    @Test
    void baselineStillHasFullCoverageAndNoAutomaticApprovals() throws Exception {
        var queue = RecyclingReviewPanelGenerator.parseQueue(Files.readString(
                Path.of("generated/recycling-decision-queue.yml"), StandardCharsets.UTF_8));

        assertEquals(1757, queue.identityCount());
        assertEquals(0, queue.items().stream().filter(item -> item.decision().status()
                == RecyclingDecisionQueueGenerator.DecisionStatus.APPROVED).count());
    }

    private Path workspaceCopy() throws Exception {
        Path root = temporaryDirectory.resolve("workspace-" + System.nanoTime());
        copy("generated/recycling-review-panel/index.html", root);
        copy("generated/recycling-decision-queue.yml", root);
        copy("generated/item-catalog-snapshot.yml", root);
        copy("src/main/resources/items.yml", root);
        return root;
    }

    private static void copy(String relative, Path root) throws Exception {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.copy(Path.of(relative), target);
    }

    private ProcessResult startPreflight(Path root) throws Exception {
        return runPowerShell(Path.of("tools/start-review.ps1"),
                "-RepositoryRoot", root.toString(), "-PreflightOnly");
    }

    private ProcessResult validate(Path decisions, Path validationPath) throws Exception {
        return runPowerShell(Path.of("tools/validate-decisions.ps1"),
                "-RepositoryRoot", Path.of("").toAbsolutePath().toString(),
                "-DecisionsPath", decisions.toString(),
                "-ValidationPath", validationPath.toString(), "-SkipCompile");
    }

    private static ProcessResult runPowerShell(Path script, String... arguments) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("powershell.exe");
        command.add("-NoProfile");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-File");
        command.add(script.toAbsolutePath().toString());
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.waitFor(), output);
    }

    private static String functionLine(String html, String name) {
        int start = html.indexOf("function " + name + "(");
        int end = html.indexOf('\n', start);
        return html.substring(start, end);
    }

    private record ProcessResult(int exitCode, String output) {
    }
}

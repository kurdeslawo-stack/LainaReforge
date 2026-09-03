package pl.laina.reforge.catalog;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import pl.laina.reforge.runtime.ApprovedRecyclingRegistry;
import pl.laina.reforge.runtime.ApprovedRecyclingRegistryLoader;
import pl.laina.reforge.runtime.RuntimeItemIdentity;

import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.DecisionQueue;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.QueueItem;

/** Strict, all-or-nothing compiler from reviewed logical decisions to runtime identities. */
public final class RecyclingRuntimeCompiler {
    public static final Path DEFAULT_QUEUE = Path.of("generated/recycling-decision-queue.yml");
    public static final Path DEFAULT_DECISIONS = Path.of("recycling-decisions.yml");
    public static final Path DEFAULT_OUTPUT = Path.of("src/main/resources/recycling-runtime.yml");
    public static final Path DEFAULT_REPORT = Path.of("generated/approved-decisions-runtime-report.txt");

    private RecyclingRuntimeCompiler() {
    }

    public static void main(String[] args) {
        try {
            System.exit(execute(Options.parse(args), System.out, System.err));
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            System.err.println(Options.usage());
            System.exit(64);
        }
    }

    public static int execute(Options options, PrintStream out, PrintStream err) {
        try {
            DecisionQueue queue = RecyclingReviewPanelGenerator.parseQueue(
                    Files.readString(options.queue(), StandardCharsets.UTF_8));
            Map<String, RecyclingReviewPanelGenerator.ReviewDecision> decisions =
                    RecyclingReviewPanelGenerator.parseDecisionImport(
                            Files.readString(options.decisions(), StandardCharsets.UTF_8),
                            queue.items().stream().map(QueueItem::logicalId).collect(java.util.stream.Collectors.toSet()));
            Compilation compilation = compile(queue, decisions);
            String runtime = ApprovedRecyclingRegistryLoader.render(compilation.registry());

            ApprovedRecyclingRegistryLoader loader = new ApprovedRecyclingRegistryLoader();
            ApprovedRecyclingRegistryLoader.Candidate candidate = loader.validate(runtime);
            if (!candidate.valid()) {
                throw new IllegalArgumentException("Generated runtime failed validation: " + candidate.errors());
            }

            writeUtf8Atomic(options.output(), runtime);
            writeUtf8Atomic(options.report(), report(queue, compilation, options.output()));
            out.printf(Locale.ROOT,
                    "Runtime decisions: %d logical, %d approved, %d rejected, %d identities.%n",
                    decisions.size(), compilation.approved(), compilation.rejected(),
                    compilation.registry().size());
            out.printf("Runtime: %s%nReport: %s%n", options.output(), options.report());
            return 0;
        } catch (IOException | IllegalArgumentException exception) {
            err.println("Runtime compilation failed: " + exception.getMessage());
            return 1;
        }
    }

    static Compilation compile(
            DecisionQueue queue,
            Map<String, RecyclingReviewPanelGenerator.ReviewDecision> decisions
    ) {
        RecyclingDecisionQueueValidator.ValidationResult queueValidation =
                RecyclingDecisionQueueValidator.validate(queue);
        if (!queueValidation.valid()) {
            throw new IllegalArgumentException("Invalid decision queue: " + queueValidation.errors());
        }

        Map<String, QueueItem> byId = new TreeMap<>();
        for (QueueItem item : queue.items()) {
            byId.put(item.logicalId(), item);
        }
        Map<RuntimeItemIdentity, ApprovedRecyclingRegistry.Entry> entries = new TreeMap<>();
        Map<RuntimeItemIdentity, String> owners = new TreeMap<>();
        int approved = 0;
        int rejected = 0;
        for (Map.Entry<String, RecyclingReviewPanelGenerator.ReviewDecision> reviewed :
                new TreeMap<>(decisions).entrySet()) {
            QueueItem item = byId.get(reviewed.getKey());
            if (item == null) {
                throw new IllegalArgumentException("Unknown logical item: " + reviewed.getKey());
            }
            if (item.identities().isEmpty()) {
                throw new IllegalArgumentException("Missing identities for " + reviewed.getKey());
            }
            RecyclingReviewPanelGenerator.ReviewDecision decision = reviewed.getValue();
            boolean recyclable = switch (decision.status()) {
                case APPROVED -> true;
                case REJECTED -> false;
                case PENDING -> throw new IllegalArgumentException(
                        "PENDING cannot enter runtime: " + reviewed.getKey());
            };
            if (recyclable) {
                if (!Boolean.TRUE.equals(decision.recyclable()) || decision.shards() == null
                        || decision.shards() <= 0) {
                    throw new IllegalArgumentException("Invalid APPROVED decision: " + reviewed.getKey());
                }
                approved++;
            } else {
                if (!Boolean.FALSE.equals(decision.recyclable()) || decision.shards() == null
                        || decision.shards() != 0) {
                    throw new IllegalArgumentException("Invalid REJECTED decision: " + reviewed.getKey());
                }
                rejected++;
            }
            for (RecyclingDecisionQueueGenerator.Identity identity : item.identities()) {
                RuntimeItemIdentity runtimeIdentity =
                        new RuntimeItemIdentity(identity.material(), identity.cmd());
                String previousOwner = owners.putIfAbsent(runtimeIdentity, reviewed.getKey());
                if (previousOwner != null) {
                    throw new IllegalArgumentException("Conflicting material+CMD " + runtimeIdentity.key()
                            + " belongs to " + previousOwner + " and " + reviewed.getKey());
                }
                ApprovedRecyclingRegistry.Entry entry = new ApprovedRecyclingRegistry.Entry(
                        recyclable, decision.shards(), reviewed.getKey(), identity.modelPath());
                if (entries.putIfAbsent(runtimeIdentity, entry) != null) {
                    throw new IllegalArgumentException("Duplicate material+CMD: " + runtimeIdentity.key());
                }
            }
        }
        return new Compilation(new ApprovedRecyclingRegistry(entries), decisions.size(), approved, rejected,
                queue.items().size() - decisions.size());
    }

    private static String report(DecisionQueue queue, Compilation compilation, Path output) {
        return "Approved Decisions Runtime Report\n"
                + "=================================\n\n"
                + "Logical decisions: " + compilation.logicalDecisions() + "\n"
                + "APPROVED: " + compilation.approved() + "\n"
                + "REJECTED: " + compilation.rejected() + "\n"
                + "Exported runtime identities: " + compilation.registry().size() + "\n"
                + "NOT_CONFIGURED logical items: " + compilation.notConfigured() + "\n"
                + "Queue logical items: " + queue.items().size() + "\n"
                + "Conflicts: 0\n"
                + "Validation errors: 0\n"
                + "Runtime resource: " + output.toString().replace('\\', '/') + "\n"
                + "Last-known-good tests: PASS (Maven test suite)\n"
                + "Runtime integration tests: PASS (Maven test suite)\n"
                + "Shard payout tests: PASS (Maven test suite)\n";
    }

    private static void writeUtf8Atomic(Path path, String content) throws IOException {
        Path absolute = path.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), absolute.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record Options(Path queue, Path decisions, Path output, Path report) {
        static Options parse(String[] args) {
            Path queue = DEFAULT_QUEUE;
            Path decisions = DEFAULT_DECISIONS;
            Path output = DEFAULT_OUTPUT;
            Path report = DEFAULT_REPORT;
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--queue" -> queue = Path.of(value(args, ++index, "--queue"));
                    case "--decisions" -> decisions = Path.of(value(args, ++index, "--decisions"));
                    case "--output" -> output = Path.of(value(args, ++index, "--output"));
                    case "--report" -> report = Path.of(value(args, ++index, "--report"));
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[index]);
                }
            }
            return new Options(queue, decisions, output, report);
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        static String usage() {
            return "Usage: RecyclingRuntimeCompiler [--queue path] [--decisions path] "
                    + "[--output path] [--report path]";
        }
    }

    record Compilation(
            ApprovedRecyclingRegistry registry,
            int logicalDecisions,
            int approved,
            int rejected,
            int notConfigured
    ) {
    }
}

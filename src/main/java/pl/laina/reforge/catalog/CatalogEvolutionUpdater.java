package pl.laina.reforge.catalog;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.Acquisition;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.CatalogEvolution;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.CatalogStatus;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.Decision;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.DecisionQueue;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.Identity;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.MappingStatus;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.Priority;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.QueueItem;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.SystemProposal;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.SystemProposalValue;

/** Offline, fail-closed catalog evolution workflow. Never participates in plugin runtime. */
public final class CatalogEvolutionUpdater {
    public static final Path DEFAULT_SNAPSHOT = Path.of("generated/item-catalog-snapshot.yml");
    public static final Path DEFAULT_EVOLUTION_REPORT = Path.of("generated/catalog-evolution-report.txt");

    private static final Pattern SNAPSHOT_KEY = Pattern.compile("(?m)^  \\\"([^\\\"]+)\\\":\\r?$");

    private CatalogEvolutionUpdater() {
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

    static int execute(Options options, PrintStream out, PrintStream err) {
        try {
            ItemCatalogGenerator.GenerationResult generated = ItemCatalogGenerator.generate(options.source());
            if (generated.hasBlockingErrors()) {
                err.printf(Locale.ROOT, "Catalog source rejected: conflicts=%d, invalid=%d, parse/schema=%d.%n",
                        generated.conflicts().size(), generated.invalidCmd().size(),
                        generated.parseErrors().size() + generated.schemaErrors().size());
                return generated.conflicts().isEmpty() ? 3 : 2;
            }

            String currentCatalogText = Files.readString(options.catalog(), StandardCharsets.UTF_8);
            ItemEconomyAnalyzer.Catalog currentCatalog = ItemEconomyAnalyzer.Catalog.parse(currentCatalogText);
            Snapshot before = Files.exists(options.snapshot())
                    ? parseSnapshot(Files.readString(options.snapshot(), StandardCharsets.UTF_8))
                    : bootstrapSnapshot(generated, currentCatalog);
            verifyCatalogMatchesSnapshot(currentCatalog, before);
            DecisionQueue previousQueue = RecyclingReviewPanelGenerator.parseQueue(
                    Files.readString(options.queue(), StandardCharsets.UTF_8));
            EvolutionResult evolution = evolve(before, generated, currentCatalog, previousQueue);

            String report = renderEvolutionReport(evolution);
            out.print(report);
            if (options.dryRun()) {
                out.println("DRY RUN: no files written.");
                return 0;
            }

            RecyclingDecisionQueueValidator.ValidationResult queueValidation =
                    RecyclingDecisionQueueValidator.validate(evolution.queue(), Map.of(),
                            catalogRecords(evolution.catalogItems()));
            if (!queueValidation.valid()) {
                queueValidation.errors().forEach(error -> err.println(error.code() + ": " + error.message()));
                return 5;
            }
            String html = RecyclingReviewPanelGenerator.renderPanel(evolution.queue());
            List<String> panelErrors = RecyclingReviewPanelGenerator.selfCheck(evolution.queue(), html);
            if (!panelErrors.isEmpty()) {
                panelErrors.forEach(err::println);
                return 5;
            }

            ItemCatalogGenerator.GenerationResult renderedCatalog = withItems(generated, evolution.catalogItems());
            Map<Path, String> outputs = new LinkedHashMap<>();
            outputs.put(options.catalog(), ItemCatalogGenerator.renderYaml(renderedCatalog));
            outputs.put(options.catalogReport(), ItemCatalogGenerator.renderReport(renderedCatalog,
                    options.source().getFileName().toString()));
            outputs.put(options.snapshot(), renderSnapshot(evolution.after()));
            outputs.put(options.evolutionReport(), report);
            outputs.put(options.queue(), RecyclingDecisionQueueGenerator.renderQueue(evolution.queue()));
            outputs.put(options.queueReport(), RecyclingDecisionQueueGenerator.renderReport(
                    evolution.queue(), queueValidation));
            outputs.put(options.panel(), html);
            outputs.put(options.panelReport(), RecyclingReviewPanelGenerator.renderReport(
                    evolution.queue(), html, options.panel(), queueValidation, panelErrors));
            writeTransaction(outputs);
            out.println("Catalog, queue, panel and snapshot updated atomically.");
            return 0;
        } catch (IOException | IllegalArgumentException exception) {
            err.println("Catalog evolution failed: " + exception.getMessage());
            return 4;
        }
    }

    static EvolutionResult evolve(
            Snapshot before,
            ItemCatalogGenerator.GenerationResult generated,
            ItemEconomyAnalyzer.Catalog currentCatalog,
            DecisionQueue previousQueue
    ) {
        Snapshot after = snapshotOf(generated.items());
        Map<String, Change> changes = diff(before, after);
        Map<String, ItemEconomyAnalyzer.CatalogRecord> currentByKey = new TreeMap<>();
        currentCatalog.records().forEach(record -> currentByKey.put(record.key(), record));

        List<ItemCatalogGenerator.CatalogItem> catalogItems = new ArrayList<>();
        for (ItemCatalogGenerator.CatalogItem item : generated.items()) {
            Change change = changes.get(key(item.material(), item.cmd()));
            ItemEconomyAnalyzer.CatalogRecord old = currentByKey.get(key(item.material(), item.cmd()));
            boolean unchanged = change.status() == ChangeStatus.UNCHANGED && old != null;
            catalogItems.add(new ItemCatalogGenerator.CatalogItem(item.material(), item.cmd(), item.model(),
                    item.modelPath(), item.type(), unchanged ? old.wiki() : "", unchanged ? old.name() : "",
                    unchanged ? old.shards() : 0, item.fingerprint()));
        }
        DecisionQueue queue = updateQueue(previousQueue, changes, after);
        return new EvolutionResult(before, after, changes, List.copyOf(catalogItems), queue);
    }

    static Map<String, Change> diff(Snapshot before, Snapshot after) {
        Map<String, Change> changes = new TreeMap<>();
        java.util.Set<String> keys = new java.util.TreeSet<>(before.items().keySet());
        keys.addAll(after.items().keySet());
        for (String key : keys) {
            SnapshotItem old = before.items().get(key);
            SnapshotItem current = after.items().get(key);
            ChangeStatus status;
            if (old == null) {
                status = ChangeStatus.NEW;
            } else if (current == null) {
                status = ChangeStatus.REMOVED;
            } else if (old.fingerprint().equals(current.fingerprint())
                    && old.modelPath().equals(current.modelPath())) {
                status = ChangeStatus.UNCHANGED;
            } else {
                status = ChangeStatus.CHANGED;
            }
            changes.put(key, new Change(key, status, old, current));
        }
        return Map.copyOf(changes);
    }

    static DecisionQueue updateQueue(DecisionQueue previous, Map<String, Change> changes, Snapshot after) {
        Map<String, QueueItem> previousOwner = new TreeMap<>();
        for (QueueItem item : previous.items()) {
            for (Identity identity : item.identities()) {
                previousOwner.put(identity.key(), item);
            }
        }
        List<QueueItem> items = new ArrayList<>();
        for (QueueItem oldItem : previous.items()) {
            List<Identity> retained = oldItem.identities().stream()
                    .filter(identity -> changes.get(identity.key()) != null
                            && changes.get(identity.key()).status() == ChangeStatus.UNCHANGED)
                    .toList();
            if (!retained.isEmpty()) {
                items.add(new QueueItem(oldItem.logicalId(), oldItem.name(), oldItem.wiki(),
                        oldItem.mappingStatus(), oldItem.priority(), oldItem.reviewReason(), retained,
                        oldItem.acquisition(), oldItem.evidence(), oldItem.systemProposal(),
                        CatalogEvolution.unchanged(), oldItem.decision()));
            }
        }
        for (Change change : changes.values()) {
            if (change.status() != ChangeStatus.NEW && change.status() != ChangeStatus.CHANGED) {
                continue;
            }
            SnapshotItem current = change.after();
            Identity identity = new Identity(current.material(), current.cmd(), current.modelPath());
            QueueItem owner = previousOwner.get(change.key());
            CatalogStatus status = change.status() == ChangeStatus.NEW ? CatalogStatus.NEW : CatalogStatus.CHANGED;
            String logicalId = status == CatalogStatus.NEW
                    ? "unmapped::" + change.key()
                    : "changed::" + change.key() + "::" + current.fingerprint();
            String reason = status == CatalogStatus.NEW
                    ? "Nowy item bez pewnego mapowania do Wiki. Wymagana ręczna decyzja."
                    : "Definicja techniczna itemu uległa zmianie. Wymagany ponowny review.";
            items.add(new QueueItem(logicalId, fallbackName(current), "", MappingStatus.UNMAPPED, Priority.LOW,
                    reason, List.of(identity), new Acquisition("UNKNOWN", java.util.Set.of("UNKNOWN")),
                    List.of(), new SystemProposal(SystemProposalValue.UNKNOWN, "Brak pewnych danych z Wiki."),
                    new CatalogEvolution(status, change.before() == null ? null : change.before().modelPath(),
                            owner == null ? null : owner.logicalId()), Decision.pending()));
        }
        items.sort(QueueItem.COMPARATOR);
        return new DecisionQueue(items);
    }

    static Snapshot bootstrapSnapshot(
            ItemCatalogGenerator.GenerationResult generated,
            ItemEconomyAnalyzer.Catalog currentCatalog
    ) {
        Map<String, ItemEconomyAnalyzer.CatalogRecord> current = new TreeMap<>();
        currentCatalog.records().forEach(record -> current.put(record.key(), record));
        Snapshot snapshot = snapshotOf(generated.items());
        if (!current.keySet().equals(snapshot.items().keySet())) {
            throw new IllegalArgumentException("Cannot bootstrap snapshot: items.yml differs from items.zip keys");
        }
        for (SnapshotItem item : snapshot.items().values()) {
            if (!item.modelPath().equals(current.get(item.key()).modelPath())) {
                throw new IllegalArgumentException("Cannot bootstrap snapshot: model_path differs for " + item.key());
            }
        }
        return snapshot;
    }

    static void verifyCatalogMatchesSnapshot(ItemEconomyAnalyzer.Catalog catalog, Snapshot snapshot) {
        Map<String, ItemEconomyAnalyzer.CatalogRecord> records = new TreeMap<>();
        catalog.records().forEach(record -> records.put(record.key(), record));
        if (!records.keySet().equals(snapshot.items().keySet())) {
            throw new IllegalArgumentException("items.yml does not match the previous catalog snapshot keys");
        }
        for (SnapshotItem item : snapshot.items().values()) {
            if (!item.modelPath().equals(records.get(item.key()).modelPath())) {
                throw new IllegalArgumentException("items.yml does not match snapshot model_path for " + item.key());
            }
        }
    }

    static Snapshot snapshotOf(List<ItemCatalogGenerator.CatalogItem> items) {
        Map<String, SnapshotItem> snapshot = new TreeMap<>();
        for (ItemCatalogGenerator.CatalogItem item : items) {
            if (item.fingerprint() == null || item.fingerprint().isBlank()) {
                throw new IllegalArgumentException("Missing source fingerprint for " + key(item.material(), item.cmd()));
            }
            SnapshotItem value = new SnapshotItem(item.material(), item.cmd(), item.model(), item.modelPath(),
                    item.fingerprint());
            if (snapshot.putIfAbsent(value.key(), value) != null) {
                throw new IllegalArgumentException("Duplicate snapshot identity: " + value.key());
            }
        }
        return new Snapshot(snapshot);
    }

    static String renderSnapshot(Snapshot snapshot) {
        StringBuilder yaml = new StringBuilder("# Deterministic technical snapshot for catalog evolution.\nitems:\n");
        for (SnapshotItem item : snapshot.items().values()) {
            yaml.append("  ").append(quote(item.key())).append(":\n")
                    .append("    material: ").append(quote(item.material())).append('\n')
                    .append("    cmd: ").append(item.cmd()).append('\n')
                    .append("    model: ").append(quote(item.model())).append('\n')
                    .append("    model_path: ").append(quote(item.modelPath())).append('\n')
                    .append("    fingerprint: ").append(quote(item.fingerprint())).append('\n');
        }
        return yaml.toString();
    }

    static Snapshot parseSnapshot(String yaml) {
        List<KeyMatch> matches = new ArrayList<>();
        Matcher matcher = SNAPSHOT_KEY.matcher(yaml);
        while (matcher.find()) {
            matches.add(new KeyMatch(matcher.start(), matcher.end(), unescape(matcher.group(1))));
        }
        Map<String, SnapshotItem> items = new TreeMap<>();
        for (int index = 0; index < matches.size(); index++) {
            KeyMatch match = matches.get(index);
            int end = index + 1 < matches.size() ? matches.get(index + 1).start() : yaml.length();
            String block = yaml.substring(match.end(), end);
            String material = field(block, "material");
            int cmd = Integer.parseInt(plainField(block, "cmd"));
            SnapshotItem item = new SnapshotItem(material, cmd, field(block, "model"),
                    field(block, "model_path"), field(block, "fingerprint"));
            if (!match.key().equals(item.key()) || items.putIfAbsent(item.key(), item) != null) {
                throw new IllegalArgumentException("Invalid or duplicate snapshot key: " + match.key());
            }
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Snapshot contains no items");
        }
        return new Snapshot(items);
    }

    static String renderEvolutionReport(EvolutionResult result) {
        StringBuilder report = new StringBuilder("Catalog Evolution Report\n========================\n\n");
        for (ChangeStatus status : ChangeStatus.values()) {
            report.append(status).append(": ")
                    .append(result.changes().values().stream().filter(change -> change.status() == status).count())
                    .append('\n');
        }
        report.append('\n');
        for (ChangeStatus status : List.of(ChangeStatus.NEW, ChangeStatus.CHANGED, ChangeStatus.REMOVED)) {
            report.append(status).append("\n").append("-".repeat(status.name().length())).append('\n');
            List<Change> selected = result.changes().values().stream()
                    .filter(change -> change.status() == status).toList();
            if (selected.isEmpty()) {
                report.append("- none\n\n");
            } else {
                for (Change change : selected) {
                    report.append("- ").append(change.key()).append(": ")
                            .append(change.before() == null ? "<missing>" : change.before().modelPath())
                            .append(" -> ")
                            .append(change.after() == null ? "<removed>" : change.after().modelPath()).append('\n');
                }
                report.append('\n');
            }
        }
        return report.toString().stripTrailing() + '\n';
    }

    private static List<ItemEconomyAnalyzer.CatalogRecord> catalogRecords(
            List<ItemCatalogGenerator.CatalogItem> items
    ) {
        return items.stream().map(item -> new ItemEconomyAnalyzer.CatalogRecord(
                key(item.material(), item.cmd()), item.material(), item.cmd(), item.modelPath(),
                item.wiki(), item.name(), item.shards())).toList();
    }

    private static ItemCatalogGenerator.GenerationResult withItems(
            ItemCatalogGenerator.GenerationResult source,
            List<ItemCatalogGenerator.CatalogItem> items
    ) {
        return new ItemCatalogGenerator.GenerationResult(source.jsonFilesAnalyzed(), items,
                source.uniqueCmdCount(), source.uniqueKeyCount(), source.modelsOnMultipleMaterials(),
                source.duplicates(), source.conflicts(), source.invalidCmd(), source.filesWithoutCmd(),
                source.parseErrors(), source.schemaErrors());
    }

    private static String fallbackName(SnapshotItem item) {
        String path = item.modelPath();
        int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String value = (separator >= 0 ? path.substring(separator + 1) : path)
                .replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ").trim();
        return value.isBlank() ? item.material() + " " + item.cmd() : value;
    }

    private static String field(String block, String name) {
        Matcher matcher = Pattern.compile("(?m)^    " + name + ": \\\"(.*)\\\"\\r?$").matcher(block);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Missing snapshot field " + name);
        }
        return unescape(matcher.group(1));
    }

    private static String plainField(String block, String name) {
        Matcher matcher = Pattern.compile("(?m)^    " + name + ": ([^\\r\\n]+)\\r?$").matcher(block);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Missing snapshot field " + name);
        }
        return matcher.group(1);
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String key(String material, int cmd) {
        return material + ":" + cmd;
    }

    private static void writeTransaction(Map<Path, String> outputs) throws IOException {
        Map<Path, Path> staged = new LinkedHashMap<>();
        Map<Path, byte[]> originals = new LinkedHashMap<>();
        try {
            for (Map.Entry<Path, String> output : outputs.entrySet()) {
                Path target = output.getKey().toAbsolutePath().normalize();
                Files.createDirectories(target.getParent());
                Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".stage");
                Files.writeString(temporary, output.getValue(), StandardCharsets.UTF_8);
                staged.put(target, temporary);
                originals.put(target, Files.exists(target) ? Files.readAllBytes(target) : null);
            }
            List<Path> replaced = new ArrayList<>();
            try {
                for (Map.Entry<Path, Path> entry : staged.entrySet()) {
                    move(entry.getValue(), entry.getKey());
                    replaced.add(entry.getKey());
                }
            } catch (IOException failure) {
                for (int index = replaced.size() - 1; index >= 0; index--) {
                    Path target = replaced.get(index);
                    byte[] original = originals.get(target);
                    if (original == null) {
                        Files.deleteIfExists(target);
                    } else {
                        Files.write(target, original);
                    }
                }
                throw failure;
            }
        } finally {
            for (Path temporary : staged.values()) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    enum ChangeStatus { UNCHANGED, NEW, CHANGED, REMOVED }

    record SnapshotItem(String material, int cmd, String model, String modelPath, String fingerprint) {
        String key() {
            return CatalogEvolutionUpdater.key(material, cmd);
        }
    }

    record Snapshot(Map<String, SnapshotItem> items) {
        Snapshot {
            items = java.util.Collections.unmodifiableMap(new TreeMap<>(items));
        }
    }

    record Change(String key, ChangeStatus status, SnapshotItem before, SnapshotItem after) {
    }

    record EvolutionResult(
            Snapshot before,
            Snapshot after,
            Map<String, Change> changes,
            List<ItemCatalogGenerator.CatalogItem> catalogItems,
            DecisionQueue queue
    ) {
        EvolutionResult {
            changes = java.util.Collections.unmodifiableMap(new TreeMap<>(changes));
            catalogItems = List.copyOf(catalogItems);
        }
    }

    record Options(
            Path source,
            Path catalog,
            Path catalogReport,
            Path snapshot,
            Path evolutionReport,
            Path queue,
            Path queueReport,
            Path panel,
            Path panelReport,
            boolean dryRun
    ) {
        static Options parse(String[] args) {
            Path source = ItemCatalogGenerator.DEFAULT_SOURCE;
            Path catalog = ItemCatalogGenerator.DEFAULT_CATALOG;
            Path catalogReport = ItemCatalogGenerator.DEFAULT_REPORT;
            Path snapshot = DEFAULT_SNAPSHOT;
            Path evolutionReport = DEFAULT_EVOLUTION_REPORT;
            Path queue = RecyclingDecisionQueueGenerator.DEFAULT_OUTPUT;
            Path queueReport = RecyclingDecisionQueueGenerator.DEFAULT_REPORT;
            Path panel = RecyclingReviewPanelGenerator.DEFAULT_OUTPUT;
            Path panelReport = RecyclingReviewPanelGenerator.DEFAULT_REPORT;
            boolean dryRun = false;
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--source" -> source = Path.of(value(args, ++index, "--source"));
                    case "--catalog" -> catalog = Path.of(value(args, ++index, "--catalog"));
                    case "--snapshot" -> snapshot = Path.of(value(args, ++index, "--snapshot"));
                    case "--dry-run" -> dryRun = true;
                    default -> throw new IllegalArgumentException("Unknown option: " + args[index]);
                }
            }
            return new Options(source, catalog, catalogReport, snapshot, evolutionReport, queue, queueReport,
                    panel, panelReport, dryRun);
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        static String usage() {
            return "Usage: CatalogEvolutionUpdater [--source items.zip] [--catalog items.yml] "
                    + "[--snapshot snapshot.yml] [--dry-run]";
        }
    }

    private record KeyMatch(int start, int end, String key) {
    }
}

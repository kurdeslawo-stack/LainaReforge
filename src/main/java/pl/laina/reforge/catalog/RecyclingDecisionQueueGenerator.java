package pl.laina.reforge.catalog;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone maintenance CLI that prepares human recycling decisions from ETAP 3 artifacts.
 * It is deliberately not connected to the Paper plugin lifecycle.
 */
public final class RecyclingDecisionQueueGenerator {
    public static final Path DEFAULT_CATALOG = Path.of("src/main/resources/items.yml");
    public static final Path DEFAULT_ANALYSIS = Path.of("generated/item-economy-analysis.yml");
    public static final Path DEFAULT_MANUAL_REVIEW = Path.of("generated/item-economy-manual-review.yml");
    public static final Path DEFAULT_OUTPUT = Path.of("generated/recycling-decision-queue.yml");
    public static final Path DEFAULT_REPORT = Path.of("generated/recycling-decision-queue-report.txt");

    private static final Pattern ITEM_KEY = Pattern.compile("(?m)^  \\\"([^\\\"]+)\\\":\\r?$");
    private static final Pattern MANUAL_WIKI = Pattern.compile("(?m)^  - wiki: \\\"(.*)\\\"\\r?$");
    private static final Set<String> MEDIUM_TAGS = Set.of(
            "KEY_REWARD", "EVENT", "QUEST", "SHOP", "CRAFT", "DROP");

    private RecyclingDecisionQueueGenerator() {
    }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            System.exit(execute(options, System.out, System.err));
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            System.err.println(Options.usage());
            System.exit(64);
        }
    }

    public static int execute(Options options, PrintStream out, PrintStream err) {
        try {
            ItemEconomyAnalyzer.Catalog catalog = ItemEconomyAnalyzer.Catalog.parse(
                    Files.readString(options.catalog(), StandardCharsets.UTF_8));
            AnalysisData analysis = AnalysisData.parse(
                    Files.readString(options.analysis(), StandardCharsets.UTF_8));
            Set<String> manualReview = parseManualReview(
                    Files.readString(options.manualReview(), StandardCharsets.UTF_8));

            DecisionQueue queue = generate(catalog, analysis, manualReview);
            RecyclingDecisionQueueValidator.ValidationResult validation =
                    RecyclingDecisionQueueValidator.validate(queue, analysis.items(), catalog.records());

            writeUtf8Atomic(options.output(), renderQueue(queue));
            writeUtf8Atomic(options.report(), renderReport(queue, validation));

            out.printf(Locale.ROOT,
                    "Recycling decision queue: %d logical items, %d identities, validation errors %d.%n",
                    queue.items().size(), queue.identityCount(), validation.errors().size());
            out.printf("Queue: %s%nReport: %s%n", options.output(), options.report());
            if (!validation.valid()) {
                validation.errors().forEach(error -> err.println(error.code() + ": " + error.message()));
                return 2;
            }
            return 0;
        } catch (IOException | IllegalArgumentException exception) {
            err.println("Recycling decision queue generation failed: " + exception.getMessage());
            return 1;
        }
    }

    static DecisionQueue generate(
            ItemEconomyAnalyzer.Catalog catalog,
            AnalysisData analysis,
            Set<String> manualReview
    ) {
        Map<String, List<ItemEconomyAnalyzer.CatalogRecord>> recordsByWiki = catalog.recordsByWiki();
        Set<String> missingAnalysis = new TreeSet<>(recordsByWiki.keySet());
        missingAnalysis.removeAll(analysis.items().keySet());
        if (!missingAnalysis.isEmpty()) {
            throw new IllegalArgumentException("Mapped catalog items missing ETAP 3 analysis: " + missingAnalysis);
        }

        List<QueueItem> queueItems = new ArrayList<>();
        for (AnalysisItem source : analysis.items().values()) {
            if (source.wiki().isBlank() || source.name().isBlank()) {
                throw new IllegalArgumentException("ETAP 3 item has blank wiki/name: " + source.logicalId());
            }
            if (!source.logicalId().equals(source.wiki())) {
                throw new IllegalArgumentException("Logical key/wiki mismatch: " + source.logicalId());
            }
            if (source.proposal() == SystemProposalValue.YES) {
                throw new IllegalArgumentException("ETAP 3 proposal YES is not allowed: " + source.wiki());
            }

            List<ItemEconomyAnalyzer.CatalogRecord> catalogRecords = recordsByWiki.get(source.wiki());
            if (catalogRecords == null || catalogRecords.isEmpty()) {
                throw new IllegalArgumentException("No catalog identities for ETAP 3 item: " + source.wiki());
            }
            List<Identity> identities = catalogRecords.stream()
                    .map(record -> new Identity(record.material(), record.cmd(), record.modelPath()))
                    .sorted(Identity.COMPARATOR)
                    .toList();

            boolean manualMultiSource = manualReview.contains(source.wiki()) && source.sources().size() > 1;
            Priority priority = priority(source, manualMultiSource);
            queueItems.add(new QueueItem(
                    source.logicalId(),
                    source.name(),
                    source.wiki(),
                    MappingStatus.MAPPED,
                    priority,
                    reviewReason(source, priority, manualMultiSource),
                    identities,
                    new Acquisition(source.summary(), source.tags()),
                    source.evidence(),
                    new SystemProposal(source.proposal(), source.proposalReason()),
                    Decision.pending()));
        }

        for (ItemEconomyAnalyzer.CatalogRecord record : catalog.records()) {
            if (record.mapped()) {
                continue;
            }
            Identity identity = new Identity(record.material(), record.cmd(), record.modelPath());
            queueItems.add(new QueueItem(
                    "unmapped::" + identity.key(),
                    fallbackName(record),
                    "",
                    MappingStatus.UNMAPPED,
                    Priority.LOW,
                    "Brak pewnego mapowania do Wiki. Wymagana ręczna decyzja.",
                    List.of(identity),
                    new Acquisition("UNKNOWN", Set.of("UNKNOWN")),
                    List.of(),
                    new SystemProposal(SystemProposalValue.UNKNOWN, "Brak pewnych danych z Wiki."),
                    Decision.pending()));
        }

        queueItems.sort(QueueItem.COMPARATOR);
        return new DecisionQueue(queueItems);
    }

    private static String fallbackName(ItemEconomyAnalyzer.CatalogRecord record) {
        String path = record.modelPath();
        int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String basename = separator >= 0 ? path.substring(separator + 1) : path;
        String fallback = basename.replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ").trim();
        return fallback.isBlank() ? record.material() + " " + record.cmd() : fallback;
    }

    private static Priority priority(AnalysisItem item, boolean manualMultiSource) {
        if (item.proposal() == SystemProposalValue.NO
                || item.tags().contains("INFINITE_OR_FARMABLE")
                || item.tags().contains("REPEATABLE")
                || manualMultiSource) {
            return Priority.HIGH;
        }
        if (!item.sources().isEmpty() || item.tags().stream().anyMatch(MEDIUM_TAGS::contains)) {
            return Priority.MEDIUM;
        }
        return Priority.LOW;
    }

    private static String reviewReason(AnalysisItem item, Priority priority, boolean manualMultiSource) {
        if (item.tags().contains("INFINITE_OR_FARMABLE") || item.tags().contains("REPEATABLE")
                || item.proposal() == SystemProposalValue.NO) {
            return "Powtarzalne źródło może umożliwiać farmienie odłamków.";
        }
        if (manualMultiSource) {
            return "Kilka źródeł o różnym wpływie wymaga ręcznej oceny.";
        }
        if (priority == Priority.MEDIUM && item.tags().contains("LIMITED")) {
            return "Źródło jest ograniczone, ale Wiki nie daje podstaw do automatycznej decyzji.";
        }
        if (priority == Priority.MEDIUM) {
            return "Wiki opisuje źródło, ale decyzja recyclingowa wymaga zatwierdzenia.";
        }
        return "Brak wystarczających danych o źródle; wymagana jest ręczna decyzja.";
    }

    static String renderQueue(DecisionQueue queue) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("# Generated from ETAP 3 artifacts. Decisions must be completed by a human.\n");
        yaml.append("items:\n");
        for (QueueItem item : queue.items()) {
            yaml.append("  ").append(yamlQuote(item.logicalId())).append(":\n");
            yaml.append("    name: ").append(yamlQuote(item.name())).append('\n');
            yaml.append("    wiki: ").append(yamlQuote(item.wiki())).append('\n');
            yaml.append("    mapping_status: ").append(item.mappingStatus()).append('\n');
            yaml.append("    catalog_status: ").append(item.catalogEvolution().status()).append('\n');
            yaml.append("    before_model_path: ")
                    .append(nullableQuoted(item.catalogEvolution().beforeModelPath())).append('\n');
            yaml.append("    previous_logical_id: ")
                    .append(nullableQuoted(item.catalogEvolution().previousLogicalId())).append('\n');
            yaml.append("    priority: ").append(item.priority()).append('\n');
            yaml.append("    review_reason: ").append(yamlQuote(item.reviewReason())).append('\n');
            yaml.append("    identities:\n");
            for (Identity identity : item.identities()) {
                yaml.append("      - material: ").append(yamlQuote(identity.material())).append('\n');
                yaml.append("        cmd: ").append(identity.cmd()).append('\n');
                yaml.append("        model_path: ").append(yamlQuote(identity.modelPath())).append('\n');
            }
            yaml.append("    acquisition:\n");
            yaml.append("      summary: ").append(yamlQuote(item.acquisition().summary())).append('\n');
            yaml.append("      tags:");
            appendList(yaml, item.acquisition().tags(), 8);
            yaml.append("    evidence:");
            appendList(yaml, item.evidence(), 6);
            yaml.append("    system_proposal:\n");
            yaml.append("      recyclable: ").append(item.systemProposal().recyclable()).append('\n');
            yaml.append("      reason: ").append(yamlQuote(item.systemProposal().reason())).append('\n');
            yaml.append("    decision:\n");
            yaml.append("      status: ").append(item.decision().status()).append('\n');
            yaml.append("      recyclable: ").append(nullable(item.decision().recyclable())).append('\n');
            yaml.append("      shards: ").append(nullable(item.decision().shards())).append('\n');
            yaml.append("      reviewed_by: ").append(nullableQuoted(item.decision().reviewedBy())).append('\n');
            yaml.append("      reviewed_at: ").append(nullableQuoted(item.decision().reviewedAt())).append('\n');
            yaml.append("      note: ").append(yamlQuote(item.decision().note())).append('\n');
        }
        return yaml.toString();
    }

    static String renderReport(
            DecisionQueue queue,
            RecyclingDecisionQueueValidator.ValidationResult validation
    ) {
        StringBuilder report = new StringBuilder();
        report.append("Recycling Decision Queue Report\n================================\n\n");
        report.append("Logical items: ").append(queue.items().size()).append('\n');
        report.append("Identity material+CMD: ").append(queue.identityCount()).append('\n');
        report.append("MAPPED logical items: ").append(queue.mappingCounts().get(MappingStatus.MAPPED)).append('\n');
        report.append("UNMAPPED logical items: ").append(queue.mappingCounts().get(MappingStatus.UNMAPPED)).append('\n');
        report.append("Mapped identities: ").append(queue.mappedIdentityCount()).append('\n');
        report.append("Unmapped identities: ").append(queue.unmappedIdentityCount()).append("\n\n");
        report.append("Priority distribution\n---------------------\n");
        for (Priority priority : Priority.values()) {
            report.append("- ").append(priority).append(": ").append(queue.priorityCounts().get(priority)).append('\n');
        }
        report.append("\nSystem proposal distribution\n----------------------------\n");
        for (SystemProposalValue value : SystemProposalValue.values()) {
            report.append("- ").append(value).append(": ").append(queue.proposalCounts().get(value)).append('\n');
        }
        report.append("\nDecision status distribution\n----------------------------\n");
        for (DecisionStatus status : DecisionStatus.values()) {
            report.append("- ").append(status).append(": ").append(queue.statusCounts().get(status)).append('\n');
        }
        report.append("\nValidation\n----------\n");
        report.append("- errors: ").append(validation.errors().size()).append('\n');
        report.append("- duplicate identity assignments: ")
                .append(validation.duplicateIdentityAssignments()).append('\n');
        if (!validation.errors().isEmpty()) {
            report.append("\nValidation errors\n-----------------\n");
            validation.errors().forEach(error -> report.append("- ").append(error.code())
                    .append(": ").append(error.message()).append('\n'));
        }
        return report.toString();
    }

    private static void appendList(StringBuilder yaml, Collection<String> values, int spaces) {
        if (values.isEmpty()) {
            yaml.append(" []\n");
            return;
        }
        yaml.append('\n');
        String indentation = " ".repeat(spaces);
        for (String value : values) {
            yaml.append(indentation).append("- ").append(yamlQuote(value)).append('\n');
        }
    }

    private static String nullable(Object value) {
        return value == null ? "null" : value.toString();
    }

    private static String nullableQuoted(String value) {
        return value == null ? "null" : yamlQuote(value);
    }

    private static String yamlQuote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static Set<String> parseManualReview(String yaml) {
        Set<String> result = new TreeSet<>();
        Matcher matcher = MANUAL_WIKI.matcher(yaml);
        while (matcher.find()) {
            result.add(unescape(matcher.group(1)));
        }
        return Collections.unmodifiableSet(result);
    }

    private static void writeUtf8Atomic(Path path, String content) throws IOException {
        Path absolute = path.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), absolute.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String sortKey(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('_', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String section(String block, String startExpression, String endExpression) {
        Matcher matcher = Pattern.compile(startExpression + "(.*?)" + endExpression,
                Pattern.MULTILINE | Pattern.DOTALL).matcher(block);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String requiredQuotedField(String block, String expression, String description) {
        Matcher matcher = Pattern.compile(expression, Pattern.MULTILINE).matcher(block);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Missing " + description);
        }
        return unescape(matcher.group(1));
    }

    private static List<String> quotedList(String section) {
        List<String> values = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?m)^\\s+- \\\"(.*)\\\"\\r?$").matcher(section);
        while (matcher.find()) {
            values.add(unescape(matcher.group(1)));
        }
        return List.copyOf(values);
    }

    private static String unescape(String value) {
        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!escaped && character == '\\') {
                escaped = true;
                continue;
            }
            if (escaped) {
                result.append(switch (character) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    default -> character;
                });
                escaped = false;
            } else {
                result.append(character);
            }
        }
        if (escaped) {
            result.append('\\');
        }
        return result.toString();
    }

    public record Options(
            Path catalog,
            Path analysis,
            Path manualReview,
            Path output,
            Path report
    ) {
        static Options parse(String[] args) {
            Path catalog = DEFAULT_CATALOG;
            Path analysis = DEFAULT_ANALYSIS;
            Path manualReview = DEFAULT_MANUAL_REVIEW;
            Path output = DEFAULT_OUTPUT;
            Path report = DEFAULT_REPORT;
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--catalog" -> catalog = Path.of(requireValue(args, ++index, "--catalog"));
                    case "--analysis" -> analysis = Path.of(requireValue(args, ++index, "--analysis"));
                    case "--manual-review" -> manualReview = Path.of(requireValue(args, ++index, "--manual-review"));
                    case "--output" -> output = Path.of(requireValue(args, ++index, "--output"));
                    case "--report" -> report = Path.of(requireValue(args, ++index, "--report"));
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[index]);
                }
            }
            return new Options(catalog, analysis, manualReview, output, report);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        static String usage() {
            return "Usage: RecyclingDecisionQueueGenerator [--catalog path] [--analysis path] "
                    + "[--manual-review path] [--output path] [--report path]";
        }
    }

    static final class AnalysisData {
        private final Map<String, AnalysisItem> items;

        AnalysisData(Map<String, AnalysisItem> items) {
            this.items = Collections.unmodifiableMap(new TreeMap<>(items));
        }

        Map<String, AnalysisItem> items() {
            return items;
        }

        static AnalysisData parse(String yaml) {
            List<MatcherSnapshot> matches = new ArrayList<>();
            Matcher matcher = ITEM_KEY.matcher(yaml);
            while (matcher.find()) {
                matches.add(new MatcherSnapshot(matcher.start(), matcher.end(), unescape(matcher.group(1))));
            }
            if (matches.isEmpty()) {
                throw new IllegalArgumentException("Economy analysis contains no items");
            }

            Map<String, AnalysisItem> items = new TreeMap<>();
            for (int index = 0; index < matches.size(); index++) {
                MatcherSnapshot current = matches.get(index);
                int end = index + 1 < matches.size() ? matches.get(index + 1).start() : yaml.length();
                String block = yaml.substring(current.end(), end);
                String wiki = requiredQuotedField(block, "^    wiki: \\\"(.*)\\\"\\r?$",
                        "wiki for " + current.key());
                String name = requiredQuotedField(block, "^    name: \\\"(.*)\\\"\\r?$",
                        "name for " + current.key());
                String summary = requiredQuotedField(block, "^      summary: \\\"(.*)\\\"\\r?$",
                        "acquisition summary for " + current.key());
                List<String> sources = quotedList(section(block,
                        "^      sources:(?: \\[\\])?\\r?$", "^      repeatable:"));
                List<String> evidence = quotedList(section(block,
                        "^    evidence:(?: \\[\\])?\\r?$", "^    system_inference:"));
                Set<String> tags = Collections.unmodifiableSet(new TreeSet<>(quotedList(section(block,
                        "^      supply_tags:(?: \\[\\])?\\r?$", "^    proposal:"))));
                Matcher proposalMatcher = Pattern.compile("(?m)^        value: (YES|NO|UNKNOWN)\\r?$")
                        .matcher(block);
                if (!proposalMatcher.find()) {
                    throw new IllegalArgumentException("Missing proposal for " + current.key());
                }
                SystemProposalValue proposal = SystemProposalValue.valueOf(proposalMatcher.group(1));
                String proposalReason = requiredQuotedField(block,
                        "^        reason: \\\"(.*)\\\"\\r?$", "proposal reason for " + current.key());
                AnalysisItem item = new AnalysisItem(current.key(), wiki, name, summary, sources,
                        tags, evidence, proposal, proposalReason);
                if (items.putIfAbsent(current.key(), item) != null) {
                    throw new IllegalArgumentException("Duplicate economy logical item: " + current.key());
                }
            }
            return new AnalysisData(items);
        }
    }

    public record AnalysisItem(
            String logicalId,
            String wiki,
            String name,
            String summary,
            List<String> sources,
            Set<String> tags,
            List<String> evidence,
            SystemProposalValue proposal,
            String proposalReason
    ) {
        public AnalysisItem {
            sources = List.copyOf(sources);
            tags = Collections.unmodifiableSet(new TreeSet<>(tags));
            evidence = List.copyOf(evidence);
        }
    }

    public record Identity(String material, int cmd, String modelPath) {
        static final Comparator<Identity> COMPARATOR = Comparator.comparing(Identity::material)
                .thenComparingInt(Identity::cmd).thenComparing(Identity::modelPath);

        String key() {
            return material + ":" + cmd;
        }
    }

    public record Acquisition(String summary, Set<String> tags) {
        public Acquisition {
            tags = Collections.unmodifiableSet(new TreeSet<>(tags));
        }
    }

    public record SystemProposal(SystemProposalValue recyclable, String reason) {
    }

    public record Decision(
            DecisionStatus status,
            Boolean recyclable,
            Integer shards,
            String reviewedBy,
            String reviewedAt,
            String note
    ) {
        static Decision pending() {
            return new Decision(DecisionStatus.PENDING, null, null, null, null, "");
        }
    }

    public record QueueItem(
            String logicalId,
            String name,
            String wiki,
            MappingStatus mappingStatus,
            Priority priority,
            String reviewReason,
            List<Identity> identities,
            Acquisition acquisition,
            List<String> evidence,
            SystemProposal systemProposal,
            CatalogEvolution catalogEvolution,
            Decision decision
    ) {
        static final Comparator<QueueItem> COMPARATOR = Comparator.comparing(QueueItem::priority)
                .thenComparing(item -> sortKey(item.name()))
                .thenComparing(item -> sortKey(item.wiki()))
                .thenComparing(QueueItem::logicalId);

        public QueueItem {
            mappingStatus = java.util.Objects.requireNonNull(mappingStatus, "mappingStatus");
            catalogEvolution = java.util.Objects.requireNonNull(catalogEvolution, "catalogEvolution");
            identities = identities.stream().sorted(Identity.COMPARATOR).toList();
            evidence = List.copyOf(evidence);
        }

        public QueueItem(String logicalId, String name, String wiki, MappingStatus mappingStatus,
                         Priority priority, String reviewReason, List<Identity> identities,
                         Acquisition acquisition, List<String> evidence, SystemProposal systemProposal,
                         Decision decision) {
            this(logicalId, name, wiki, mappingStatus, priority, reviewReason, identities, acquisition,
                    evidence, systemProposal, CatalogEvolution.unchanged(), decision);
        }
    }

    public record DecisionQueue(List<QueueItem> items) {
        public DecisionQueue {
            items = List.copyOf(items);
        }

        int identityCount() {
            return items.stream().mapToInt(item -> item.identities().size()).sum();
        }

        int mappedIdentityCount() {
            return identityCount(MappingStatus.MAPPED);
        }

        int unmappedIdentityCount() {
            return identityCount(MappingStatus.UNMAPPED);
        }

        private int identityCount(MappingStatus status) {
            return items.stream().filter(item -> item.mappingStatus() == status)
                    .mapToInt(item -> item.identities().size()).sum();
        }

        Map<MappingStatus, Integer> mappingCounts() {
            Map<MappingStatus, Integer> counts = initializedCounts(MappingStatus.values());
            items.forEach(item -> counts.put(item.mappingStatus(), counts.get(item.mappingStatus()) + 1));
            return counts;
        }

        Map<Priority, Integer> priorityCounts() {
            Map<Priority, Integer> counts = initializedCounts(Priority.values());
            items.forEach(item -> counts.put(item.priority(), counts.get(item.priority()) + 1));
            return counts;
        }

        Map<SystemProposalValue, Integer> proposalCounts() {
            Map<SystemProposalValue, Integer> counts = initializedCounts(SystemProposalValue.values());
            items.forEach(item -> counts.put(item.systemProposal().recyclable(),
                    counts.get(item.systemProposal().recyclable()) + 1));
            return counts;
        }

        Map<DecisionStatus, Integer> statusCounts() {
            Map<DecisionStatus, Integer> counts = initializedCounts(DecisionStatus.values());
            items.forEach(item -> counts.put(item.decision().status(), counts.get(item.decision().status()) + 1));
            return counts;
        }

        private static <E extends Enum<E>> Map<E, Integer> initializedCounts(E[] values) {
            Map<E, Integer> counts = new EnumMap<>(values[0].getDeclaringClass());
            for (E value : values) {
                counts.put(value, 0);
            }
            return counts;
        }
    }

    public enum Priority { HIGH, MEDIUM, LOW }

    public enum MappingStatus { MAPPED, UNMAPPED }

    public enum CatalogStatus { UNCHANGED, NEW, CHANGED }

    public record CatalogEvolution(CatalogStatus status, String beforeModelPath, String previousLogicalId) {
        static CatalogEvolution unchanged() {
            return new CatalogEvolution(CatalogStatus.UNCHANGED, null, null);
        }
    }

    public enum SystemProposalValue { YES, NO, UNKNOWN }

    public enum DecisionStatus { PENDING, APPROVED, REJECTED }

    private record MatcherSnapshot(int start, int end, String key) {
    }
}

package pl.laina.reforge.catalog;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.Acquisition;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.CatalogEvolution;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.CatalogStatus;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.Decision;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.DecisionQueue;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.DecisionStatus;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.Identity;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.MappingStatus;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.Priority;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.QueueItem;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.SystemProposal;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.SystemProposalValue;
import static pl.laina.reforge.runtime.RecyclingSafetyLimits.MAX_SHARDS_PER_ITEM;
import static pl.laina.reforge.runtime.RecyclingSafetyLimits.MAX_SHARDS_PER_TRANSACTION;

/** Generates a self-contained, local-only review panel from the ETAP 4 queue. */
public final class RecyclingReviewPanelGenerator {
    public static final Path DEFAULT_INPUT = Path.of("generated/recycling-decision-queue.yml");
    public static final Path DEFAULT_OUTPUT = Path.of("generated/recycling-review-panel/index.html");
    public static final Path DEFAULT_REPORT = Path.of("generated/recycling-review-panel-report.txt");
    public static final String LOCAL_STORAGE_KEY = "laina-reforge.recycling-decisions.v1";
    public static final String HISTORY_STORAGE_KEY = LOCAL_STORAGE_KEY + ".history";

    private static final Pattern ITEM_KEY = Pattern.compile("(?m)^  \\\"([^\\\"]+)\\\":\\r?$");
    private static final Pattern IDENTITY = Pattern.compile(
            "(?m)^      - material: \\\"(.*)\\\"\\r?\\n"
                    + "        cmd: ([0-9]+)\\r?\\n"
                    + "        model_path: \\\"(.*)\\\"\\r?$");

    private RecyclingReviewPanelGenerator() {
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
            String input = Files.readString(options.input(), StandardCharsets.UTF_8);
            DecisionQueue queue = parseQueue(input);
            RecyclingDecisionQueueValidator.ValidationResult validation =
                    RecyclingDecisionQueueValidator.validate(queue);
            if (!validation.valid()) {
                validation.errors().forEach(error -> err.println(error.code() + ": " + error.message()));
                return 2;
            }

            String html = renderPanel(queue);
            List<String> selfCheckErrors = selfCheck(queue, html);
            if (!selfCheckErrors.isEmpty()) {
                selfCheckErrors.forEach(err::println);
                return 2;
            }
            writeUtf8Atomic(options.output(), html);
            writeUtf8Atomic(options.report(), renderReport(
                    queue, html, options.output(), validation, selfCheckErrors));
            out.printf(Locale.ROOT,
                    "Recycling review panel: %d logical items, %d identities, %d UTF-8 bytes.%n",
                    queue.items().size(), queue.identityCount(), html.getBytes(StandardCharsets.UTF_8).length);
            out.printf("Panel: %s%nReport: %s%n", options.output(), options.report());
            return 0;
        } catch (IOException | IllegalArgumentException exception) {
            err.println("Recycling review panel generation failed: " + exception.getMessage());
            return 1;
        }
    }

    static DecisionQueue parseQueue(String yaml) {
        List<ItemMatch> matches = new ArrayList<>();
        Matcher matcher = ITEM_KEY.matcher(yaml);
        while (matcher.find()) {
            matches.add(new ItemMatch(matcher.start(), matcher.end(), unescape(matcher.group(1))));
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("Recycling decision queue contains no items");
        }

        List<QueueItem> items = new ArrayList<>();
        Set<String> logicalIds = new TreeSet<>();
        for (int index = 0; index < matches.size(); index++) {
            ItemMatch current = matches.get(index);
            int end = index + 1 < matches.size() ? matches.get(index + 1).start() : yaml.length();
            String block = yaml.substring(current.end(), end);
            if (!logicalIds.add(current.key())) {
                throw new IllegalArgumentException("Duplicate logical item: " + current.key());
            }

            String name = requiredQuoted(block, "^    name: \\\"(.*)\\\"\\r?$", "name", current.key());
            String wiki = requiredQuoted(block, "^    wiki: \\\"(.*)\\\"\\r?$", "wiki", current.key());
            MappingStatus mappingStatus = MappingStatus.valueOf(requiredPlain(block,
                    "^    mapping_status: (MAPPED|UNMAPPED)\\r?$", "mapping_status", current.key()));
            String rawCatalogStatus = optionalPlain(block,
                    "^    catalog_status: (UNCHANGED|NEW|CHANGED)\\r?$");
            CatalogStatus catalogStatus = rawCatalogStatus == null
                    ? CatalogStatus.UNCHANGED : CatalogStatus.valueOf(rawCatalogStatus);
            String beforeModelPath = optionalNullableQuoted(block, "before_model_path");
            String previousLogicalId = optionalNullableQuoted(block, "previous_logical_id");
            Priority priority = Priority.valueOf(requiredPlain(block,
                    "^    priority: (HIGH|MEDIUM|LOW)\\r?$", "priority", current.key()));
            String reviewReason = requiredQuoted(block,
                    "^    review_reason: \\\"(.*)\\\"\\r?$", "review_reason", current.key());
            List<Identity> identities = parseIdentities(block, current.key());
            String summary = requiredQuoted(block,
                    "^      summary: \\\"(.*)\\\"\\r?$", "acquisition summary", current.key());
            Set<String> tags = Collections.unmodifiableSet(new TreeSet<>(quotedList(section(block,
                    "^      tags:(?: \\[\\])?\\r?$", "^    evidence:"))));
            List<String> evidence = quotedList(section(block,
                    "^    evidence:(?: \\[\\])?\\r?$", "^    system_proposal:"));
            SystemProposalValue proposal = SystemProposalValue.valueOf(requiredPlain(block,
                    "(?ms)^    system_proposal:.*?^      recyclable: (YES|NO|UNKNOWN)\\r?$",
                    "system proposal", current.key()));
            String proposalReason = requiredQuoted(section(block,
                            "^    system_proposal:\\r?$", "^    decision:"),
                    "^      reason: \\\"(.*)\\\"\\r?$", "proposal reason", current.key());
            Decision decision = parseDecision(block, current.key());
            items.add(new QueueItem(current.key(), name, wiki, mappingStatus, priority, reviewReason, identities,
                    new Acquisition(summary, tags), evidence, new SystemProposal(proposal, proposalReason),
                    new CatalogEvolution(catalogStatus, beforeModelPath, previousLogicalId), decision));
        }
        return new DecisionQueue(items);
    }

    private static List<Identity> parseIdentities(String block, String logicalId) {
        List<Identity> identities = new ArrayList<>();
        Matcher matcher = IDENTITY.matcher(block);
        while (matcher.find()) {
            identities.add(new Identity(unescape(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    unescape(matcher.group(3))));
        }
        if (identities.isEmpty()) {
            throw new IllegalArgumentException("Missing identities for " + logicalId);
        }
        return List.copyOf(identities);
    }

    private static Decision parseDecision(String block, String logicalId) {
        String decisionBlock = section(block, "^    decision:\\r?$", "\\z");
        DecisionStatus status = DecisionStatus.valueOf(requiredPlain(decisionBlock,
                "^      status: (PENDING|APPROVED|REJECTED)\\r?$", "decision status", logicalId));
        Boolean recyclable = parseNullableBoolean(requiredPlain(decisionBlock,
                "^      recyclable: (true|false|null)\\r?$", "decision recyclable", logicalId));
        Integer shards = parseNullableInteger(requiredPlain(decisionBlock,
                "^      shards: ([0-9]+|null)\\r?$", "decision shards", logicalId));
        String reviewedBy = parseNullableQuoted(decisionBlock, "reviewed_by", logicalId);
        String reviewedAt = parseNullableQuoted(decisionBlock, "reviewed_at", logicalId);
        String note = requiredQuoted(decisionBlock,
                "^      note: \\\"(.*)\\\"\\r?$", "decision note", logicalId);
        return new Decision(status, recyclable, shards, reviewedBy, reviewedAt, note);
    }

    static ReviewDecision validateReviewDecision(
            String status,
            Boolean recyclable,
            Integer shards,
            String reviewedBy,
            String reviewedAt,
            String note
    ) {
        DecisionStatus parsedStatus;
        try {
            parsedStatus = DecisionStatus.valueOf(status);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid decision status: " + status);
        }
        if (parsedStatus == DecisionStatus.PENDING) {
            throw new IllegalArgumentException("Imported decisions cannot be PENDING");
        }
        if (parsedStatus == DecisionStatus.APPROVED
                && (!Boolean.TRUE.equals(recyclable) || shards == null
                || shards <= 0 || shards > MAX_SHARDS_PER_ITEM)) {
            throw new IllegalArgumentException("APPROVED requires recyclable=true and shards in range 1-"
                    + MAX_SHARDS_PER_ITEM);
        }
        if (parsedStatus == DecisionStatus.REJECTED
                && (!Boolean.FALSE.equals(recyclable) || shards == null || shards != 0)) {
            throw new IllegalArgumentException("REJECTED requires recyclable=false and shards=0");
        }
        if (reviewedAt == null || reviewedAt.isBlank()) {
            throw new IllegalArgumentException("reviewed_at is required");
        }
        try {
            Instant.parse(reviewedAt);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("reviewed_at must use ISO-8601");
        }
        return new ReviewDecision(parsedStatus, recyclable, shards,
                reviewedBy == null ? "" : reviewedBy, reviewedAt, note == null ? "" : note);
    }

    static PanelProgress calculateProgress(DecisionQueue queue, Map<String, ReviewDecision> decisions) {
        int approved = 0;
        int rejected = 0;
        int mappedReviewed = 0;
        int unmappedReviewed = 0;
        int mappedTotal = 0;
        int unmappedTotal = 0;
        int highPending = 0;
        int mediumPending = 0;
        int lowPending = 0;
        for (QueueItem item : queue.items()) {
            boolean reviewed = decisions.containsKey(item.logicalId());
            if (item.mappingStatus() == MappingStatus.MAPPED) {
                mappedTotal++;
                mappedReviewed += reviewed ? 1 : 0;
            } else {
                unmappedTotal++;
                unmappedReviewed += reviewed ? 1 : 0;
            }
            if (reviewed) {
                if (decisions.get(item.logicalId()).status() == DecisionStatus.APPROVED) {
                    approved++;
                } else {
                    rejected++;
                }
            } else {
                switch (item.priority()) {
                    case HIGH -> highPending++;
                    case MEDIUM -> mediumPending++;
                    case LOW -> lowPending++;
                }
            }
        }
        int reviewed = approved + rejected;
        return new PanelProgress(reviewed, queue.items().size() - reviewed, approved, rejected,
                mappedReviewed, mappedTotal, unmappedReviewed, unmappedTotal,
                highPending, mediumPending, lowPending);
    }

    static boolean matchesSearch(QueueItem item, String query) {
        String normalized = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return true;
        }
        List<String> values = new ArrayList<>();
        values.add(item.logicalId());
        values.add(item.name());
        values.add(item.wiki());
        for (Identity identity : item.identities()) {
            values.add(identity.material());
            values.add(Integer.toString(identity.cmd()));
            values.add(identity.modelPath());
            values.add(identity.material() + ":" + identity.cmd());
        }
        return values.stream().anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(normalized));
    }

    static HistoryEntry createHistoryEntry(
            QueueItem item,
            ReviewDecision previous,
            ReviewDecision next,
            boolean imported
    ) {
        if (item == null || next == null) {
            throw new IllegalArgumentException("History requires item and new decision");
        }
        HistoryAction action;
        if (imported) {
            action = HistoryAction.IMPORTED;
        } else if (previous != null) {
            action = HistoryAction.EDITED;
        } else if (next.status() == DecisionStatus.APPROVED) {
            action = HistoryAction.APPROVED;
        } else {
            action = HistoryAction.REJECTED;
        }
        List<String> identities = item.identities().stream()
                .map(identity -> identity.material() + ":" + identity.cmd())
                .toList();
        return new HistoryEntry(next.reviewedAt(), next.reviewedBy(), item.logicalId(), item.name(), identities,
                action, previous == null ? null : previous.status(), next.status(),
                previous == null ? null : previous.shards(), next.shards(),
                previous == null ? null : previous.note(), next.note());
    }

    static Map<String, ReviewDecision> parseDecisionImport(String yaml, Set<String> knownItems) {
        List<String> lines = yaml.lines().toList();
        int first = firstContentLine(lines);
        if (first < 0 || !lines.get(first).equals("items:")) {
            throw new IllegalArgumentException("Import must start with items:");
        }
        Map<String, ReviewDecision> decisions = new LinkedHashMap<>();
        int index = first + 1;
        while (index < lines.size()) {
            String line = lines.get(index);
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                index++;
                continue;
            }
            Matcher keyMatcher = Pattern.compile("^  \\\"(.*)\\\":$").matcher(line);
            if (!keyMatcher.matches()) {
                throw new IllegalArgumentException("Invalid item entry at line " + (index + 1));
            }
            String logicalId = unescape(keyMatcher.group(1));
            if (!knownItems.contains(logicalId)) {
                throw new IllegalArgumentException("Unknown logical item: " + logicalId);
            }
            if (decisions.containsKey(logicalId)) {
                throw new IllegalArgumentException("Duplicate imported item: " + logicalId);
            }
            Map<String, String> fields = new LinkedHashMap<>();
            index++;
            while (index < lines.size() && !lines.get(index).matches("^  \\\".*\\\":$")) {
                String fieldLine = lines.get(index);
                if (!fieldLine.isBlank() && !fieldLine.stripLeading().startsWith("#")) {
                    Matcher fieldMatcher = Pattern.compile("^    ([a-z_]+): (.*)$").matcher(fieldLine);
                    if (!fieldMatcher.matches()) {
                        throw new IllegalArgumentException("Invalid decision field at line " + (index + 1));
                    }
                    if (fields.putIfAbsent(fieldMatcher.group(1), fieldMatcher.group(2)) != null) {
                        throw new IllegalArgumentException("Duplicate field for " + logicalId);
                    }
                }
                index++;
            }
            requireExactFields(fields, logicalId);
            ReviewDecision decision = validateReviewDecision(
                    fields.get("status"), parseBoolean(fields.get("recyclable"), logicalId),
                    parseInteger(fields.get("shards"), logicalId),
                    parseQuoted(fields.get("reviewed_by"), "reviewed_by", logicalId),
                    parseQuoted(fields.get("reviewed_at"), "reviewed_at", logicalId),
                    parseQuoted(fields.get("note"), "note", logicalId));
            decisions.put(logicalId, decision);
        }
        return Collections.unmodifiableMap(decisions);
    }

    private static void requireExactFields(Map<String, String> fields, String logicalId) {
        Set<String> expected = Set.of("status", "recyclable", "shards", "reviewed_by", "reviewed_at", "note");
        if (!fields.keySet().equals(expected)) {
            throw new IllegalArgumentException("Invalid fields for " + logicalId + ": " + fields.keySet());
        }
    }

    private static Boolean parseBoolean(String value, String logicalId) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("Invalid recyclable for " + logicalId);
        };
    }

    private static Integer parseInteger(String value, String logicalId) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid shards for " + logicalId);
        }
    }

    private static String parseQuoted(String value, String field, String logicalId) {
        if (value == null || value.length() < 2 || value.charAt(0) != '"'
                || value.charAt(value.length() - 1) != '"') {
            throw new IllegalArgumentException("Invalid " + field + " for " + logicalId);
        }
        return unescape(value.substring(1, value.length() - 1));
    }

    private static int firstContentLine(List<String> lines) {
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!line.isBlank() && !line.stripLeading().startsWith("#")) {
                return index;
            }
        }
        return -1;
    }

    static String renderPanel(DecisionQueue queue) {
        String data = toJson(queue).replace("</", "<\\/");
        String queueFingerprint = sha256(RecyclingDecisionQueueGenerator.renderQueue(queue));
        return HTML_TEMPLATE
                .replace("__QUEUE_DATA__", data)
                .replace("__QUEUE_FINGERPRINT__", queueFingerprint)
                .replace("__STORAGE_KEY__", jsonString(LOCAL_STORAGE_KEY))
                .replace("__MAX_SHARDS_PER_ITEM__", Integer.toString(MAX_SHARDS_PER_ITEM))
                .replace("__MAX_SHARDS_PER_TRANSACTION__", Integer.toString(MAX_SHARDS_PER_TRANSACTION))
                .replace("__CATALOG_IDENTITIES__", Integer.toString(queue.identityCount()))
                .replace("__MAPPED_IDENTITIES__", Integer.toString(queue.mappedIdentityCount()))
                .replace("__UNMAPPED_IDENTITIES__", Integer.toString(queue.unmappedIdentityCount()));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String toJson(DecisionQueue queue) {
        StringBuilder json = new StringBuilder("[");
        for (int itemIndex = 0; itemIndex < queue.items().size(); itemIndex++) {
            if (itemIndex > 0) {
                json.append(',');
            }
            QueueItem item = queue.items().get(itemIndex);
            json.append('{')
                    .append("\"id\":").append(jsonString(item.logicalId())).append(',')
                    .append("\"name\":").append(jsonString(item.name())).append(',')
                    .append("\"wiki\":").append(jsonString(item.wiki())).append(',')
                    .append("\"mappingStatus\":").append(jsonString(item.mappingStatus().name())).append(',')
                    .append("\"catalogStatus\":").append(jsonString(item.catalogEvolution().status().name())).append(',')
                    .append("\"beforeModelPath\":").append(nullableJson(item.catalogEvolution().beforeModelPath())).append(',')
                    .append("\"previousLogicalId\":").append(nullableJson(item.catalogEvolution().previousLogicalId())).append(',')
                    .append("\"type\":").append(jsonString(EconomyReviewAssistant.technicalType(item))).append(',')
                    .append("\"modelGroup\":").append(jsonString(EconomyReviewAssistant.modelGroup(item))).append(',')
                    .append("\"priority\":").append(jsonString(item.priority().name())).append(',')
                    .append("\"reviewReason\":").append(jsonString(item.reviewReason())).append(',')
                    .append("\"identities\":[");
            for (int identityIndex = 0; identityIndex < item.identities().size(); identityIndex++) {
                if (identityIndex > 0) {
                    json.append(',');
                }
                Identity identity = item.identities().get(identityIndex);
                json.append('{')
                        .append("\"material\":").append(jsonString(identity.material())).append(',')
                        .append("\"cmd\":").append(identity.cmd()).append(',')
                        .append("\"modelPath\":").append(jsonString(identity.modelPath()))
                        .append('}');
            }
            json.append("],\"acquisition\":{")
                    .append("\"summary\":").append(jsonString(item.acquisition().summary())).append(',')
                    .append("\"tags\":").append(jsonArray(item.acquisition().tags())).append("},")
                    .append("\"evidence\":").append(jsonArray(item.evidence())).append(',')
                    .append("\"proposal\":{")
                    .append("\"value\":").append(jsonString(item.systemProposal().recyclable().name())).append(',')
                    .append("\"reason\":").append(jsonString(item.systemProposal().reason())).append("},")
                    .append("\"initialStatus\":").append(jsonString(item.decision().status().name()))
                    .append('}');
        }
        return json.append(']').toString();
    }

    private static String jsonArray(Iterable<String> values) {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (String value : values) {
            if (!first) {
                json.append(',');
            }
            json.append(jsonString(value));
            first = false;
        }
        return json.append(']').toString();
    }

    private static String nullableJson(String value) {
        return value == null ? "null" : jsonString(value);
    }

    private static String jsonString(String value) {
        StringBuilder json = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> json.append("\\\\");
                case '"' -> json.append("\\\"");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                case '\u2028' -> json.append("\\u2028");
                case '\u2029' -> json.append("\\u2029");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        return json.append('"').toString();
    }

    static List<String> selfCheck(DecisionQueue queue, String html) {
        List<String> errors = new ArrayList<>();
        if (queue.items().isEmpty()) {
            errors.add("SELF_CHECK: queue is empty");
        }
        if (!html.contains("const QUEUE = [") || !html.contains(LOCAL_STORAGE_KEY)
                || !html.contains("HISTORY_STORAGE_KEY")
                || !html.contains("<meta name=\"laina-queue-sha256\" content=\""
                + sha256(RecyclingDecisionQueueGenerator.renderQueue(queue)) + "\">")) {
            errors.add("SELF_CHECK: embedded data or storage key is missing");
        }
        for (QueueItem item : queue.items()) {
            if (!html.contains(jsonString(item.logicalId()))) {
                errors.add("SELF_CHECK: missing logical item " + item.logicalId());
            }
        }
        return List.copyOf(errors);
    }

    static String renderReport(
            DecisionQueue queue,
            String html,
            Path output,
            RecyclingDecisionQueueValidator.ValidationResult validation,
            List<String> selfCheckErrors
    ) {
        Map<Priority, Integer> priorities = queue.priorityCounts();
        return "Recycling Review Panel Report\n"
                + "=============================\n\n"
                + "Logical items: " + queue.items().size() + "\n"
                + "Identities: " + queue.identityCount() + "\n"
                + "Mapped identities: " + queue.mappedIdentityCount() + "\n"
                + "Unmapped identities: " + queue.unmappedIdentityCount() + "\n"
                + "Coverage: " + queue.identityCount() + " / " + queue.identityCount() + "\n"
                + "HIGH: " + priorities.get(Priority.HIGH) + "\n"
                + "MEDIUM: " + priorities.get(Priority.MEDIUM) + "\n"
                + "LOW: " + priorities.get(Priority.LOW) + "\n"
                + "Generated HTML: " + output.toString().replace('\\', '/') + "\n"
                + "HTML size (UTF-8 bytes): " + html.getBytes(StandardCharsets.UTF_8).length + "\n"
                + "Decision history storage: " + HISTORY_STORAGE_KEY + "\n"
                + "Economy review assistant: ENABLED (advisory only)\n"
                + "Minimum approved peers for statistics: "
                + EconomyReviewAssistant.MIN_COMPARABLE_APPROVED + "\n"
                + "Validation errors: " + validation.errors().size() + "\n"
                + "Duplicate identities: " + validation.duplicateIdentityAssignments() + "\n"
                + "Generator self-tests: " + (selfCheckErrors.isEmpty() ? "PASS" : "FAIL") + "\n";
    }

    private static String section(String block, String startExpression, String endExpression) {
        Matcher matcher = Pattern.compile(startExpression + "(.*?)" + endExpression,
                Pattern.MULTILINE | Pattern.DOTALL).matcher(block);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String requiredQuoted(String block, String expression, String field, String logicalId) {
        Matcher matcher = Pattern.compile(expression, Pattern.MULTILINE).matcher(block);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Missing " + field + " for " + logicalId);
        }
        return unescape(matcher.group(1));
    }

    private static String requiredPlain(String block, String expression, String field, String logicalId) {
        Matcher matcher = Pattern.compile(expression, Pattern.MULTILINE | Pattern.DOTALL).matcher(block);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Missing " + field + " for " + logicalId);
        }
        return matcher.group(1);
    }

    private static String optionalPlain(String block, String expression) {
        Matcher matcher = Pattern.compile(expression, Pattern.MULTILINE).matcher(block);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String optionalNullableQuoted(String block, String field) {
        Matcher matcher = Pattern.compile("(?m)^    " + field + ": (null|\\\"(.*)\\\")\\r?$").matcher(block);
        if (!matcher.find() || "null".equals(matcher.group(1))) {
            return null;
        }
        return unescape(matcher.group(2));
    }

    private static List<String> quotedList(String section) {
        List<String> values = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?m)^\\s+- \\\"(.*)\\\"\\r?$").matcher(section);
        while (matcher.find()) {
            values.add(unescape(matcher.group(1)));
        }
        return List.copyOf(values);
    }

    private static String parseNullableQuoted(String block, String field, String logicalId) {
        Matcher matcher = Pattern.compile("(?m)^      " + field + ": (null|\\\"(.*)\\\")\\r?$").matcher(block);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Missing " + field + " for " + logicalId);
        }
        return "null".equals(matcher.group(1)) ? null : unescape(matcher.group(2));
    }

    private static Boolean parseNullableBoolean(String value) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            case "null" -> null;
            default -> throw new IllegalArgumentException("Invalid nullable boolean: " + value);
        };
    }

    private static Integer parseNullableInteger(String value) {
        return "null".equals(value) ? null : Integer.valueOf(value);
    }

    private static String unescape(String value) {
        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!escaped && character == '\\') {
                escaped = true;
            } else if (escaped) {
                result.append(switch (character) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
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

    public record Options(Path input, Path output, Path report) {
        static Options parse(String[] args) {
            Path input = DEFAULT_INPUT;
            Path output = DEFAULT_OUTPUT;
            Path report = DEFAULT_REPORT;
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--input" -> input = Path.of(requireValue(args, ++index, "--input"));
                    case "--output" -> output = Path.of(requireValue(args, ++index, "--output"));
                    case "--report" -> report = Path.of(requireValue(args, ++index, "--report"));
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[index]);
                }
            }
            return new Options(input, output, report);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        static String usage() {
            return "Usage: RecyclingReviewPanelGenerator [--input path] [--output path] [--report path]";
        }
    }

    public record ReviewDecision(
            DecisionStatus status,
            Boolean recyclable,
            Integer shards,
            String reviewedBy,
            String reviewedAt,
            String note
    ) {
    }

    enum HistoryAction {
        APPROVED,
        REJECTED,
        EDITED,
        IMPORTED,
        CATALOG_CHANGED
    }

    record HistoryEntry(
            String timestamp,
            String reviewedBy,
            String logicalItemId,
            String displayName,
            List<String> identities,
            HistoryAction action,
            DecisionStatus previousStatus,
            DecisionStatus newStatus,
            Integer previousShards,
            Integer newShards,
            String previousNote,
            String newNote
    ) {
    }

    record PanelProgress(
            int reviewed,
            int pending,
            int approved,
            int rejected,
            int mappedReviewed,
            int mappedTotal,
            int unmappedReviewed,
            int unmappedTotal,
            int highPending,
            int mediumPending,
            int lowPending
    ) {
        boolean partialExport() {
            return pending > 0;
        }
    }

    private record ItemMatch(int start, int end, String key) {
    }

    private static final String HTML_TEMPLATE = String.join("", """
            <!doctype html>
            <html lang="pl">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <meta name="laina-queue-sha256" content="__QUEUE_FINGERPRINT__">
              <title>LainaReforge — Recycling Review</title>
              <style>
                :root{color-scheme:dark;--bg:#080c12;--surface:#101722;--panel:#131c28;--panel2:#192432;--raised:#1e2b3b;--line:#29384a;--line-soft:#202c3a;--text:#f0f4f8;--muted:#8fa1b5;--muted2:#66798d;--gold:#e5b95f;--gold-soft:#302710;--red:#f06a6a;--red-soft:#30191d;--green:#5bd68a;--green-soft:#13291f;--blue:#67b7ff;--blue-soft:#12283b;--violet:#a98cff;--shadow:0 18px 50px #0006;--radius:14px}
                *{box-sizing:border-box}html{scroll-behavior:smooth}body{margin:0;background:radial-gradient(circle at 85% -10%,#152945 0,transparent 32rem),var(--bg);color:var(--text);font:14px/1.5 Inter,ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif;letter-spacing:.005em}button,input,select,textarea{font:inherit;border:1px solid var(--line);background:#0c131d;color:var(--text);border-radius:9px}button{cursor:pointer;padding:9px 13px;transition:border-color .15s,background .15s,transform .15s}button:hover{border-color:#4b6582;background:#172231}button:active{transform:translateY(1px)}button:focus-visible,input:focus-visible,select:focus-visible,textarea:focus-visible,a:focus-visible{outline:2px solid var(--blue);outline-offset:2px}button:disabled{cursor:not-allowed;opacity:.45}.skip-link{position:fixed;left:12px;top:-60px;z-index:99;background:var(--blue);color:#06101b;padding:10px 14px;border-radius:8px}.skip-link:focus{top:12px}.app{max-width:1720px;margin:auto;padding:20px 24px 48px}.top{display:flex;gap:24px;align-items:center;justify-content:space-between;margin-bottom:14px}.brand{display:flex;align-items:center;gap:13px}.brand-mark{display:grid;place-items:center;width:42px;height:42px;border:1px solid #7f6634;border-radius:12px;background:linear-gradient(145deg,#342a17,#171713);color:var(--gold);font-weight:900;font-size:18px;box-shadow:inset 0 1px #ffffff10}.eyebrow{color:var(--gold);font-size:11px;font-weight:750;text-transform:uppercase;letter-spacing:.14em}.top h1{font-size:20px;margin:1px 0 0;line-height:1.25}.position{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;color:var(--muted);font-size:12px;text-align:right}.muted{color:var(--muted)}.tabs{display:flex;gap:6px;padding:5px;background:#0c121b;border:1px solid var(--line-soft);border-radius:12px;margin-bottom:14px;position:sticky;top:8px;z-index:20;box-shadow:0 8px 24px #0004}.tabs button{border:0;background:transparent;color:var(--muted);font-weight:650}.tabs button.active{color:var(--text);background:var(--raised);box-shadow:0 2px 8px #0004}.tabs .tab-spacer{flex:1}.local-state{align-self:center;padding:0 10px;color:var(--muted);font-size:12px}.local-state strong{color:var(--text)}.hero-grid{display:grid;grid-template-columns:minmax(270px,1.2fr) repeat(3,minmax(135px,.55fr));gap:9px;margin:10px 0}.hero-progress,.metric,.toolbar,.card,.queues{background:linear-gradient(145deg,var(--panel),#101821);border:1px solid var(--line-soft);border-radius:var(--radius);box-shadow:0 8px 28px #0002}.hero-progress{grid-row:span 2;padding:16px 18px;display:grid;align-content:center;gap:9px}.hero-progress .summary{display:flex;justify-content:space-between;align-items:end}.hero-progress b{font-size:26px}.progress-track{height:8px;background:#080d13;border-radius:999px;overflow:hidden}.progress-fill{display:block;width:0;height:100%;background:linear-gradient(90deg,var(--blue),var(--violet));border-radius:inherit;transition:width .25s}.metric{padding:11px 13px}.metric span{color:var(--muted);font-size:11px;text-transform:uppercase;letter-spacing:.07em}.metric b{display:block;font-size:18px;margin-top:2px}.metric.compact b{font-size:15px}.catalog-strip{display:grid;grid-template-columns:repeat(4,1fr);gap:1px;background:var(--line-soft);border:1px solid var(--line-soft);border-radius:12px;overflow:hidden;margin:9px 0}.catalog-strip .metric{border:0;border-radius:0;box-shadow:none;background:#101721}.workspace-tools{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:9px;margin:10px 0}.queues{padding:8px;display:flex;gap:6px;overflow:auto;margin:0}.queues button{white-space:nowrap;border-color:transparent;background:transparent;color:var(--muted)}.queues button.active{border-color:#7c6331;color:var(--gold);background:var(--gold-soft)}.filter-toggle{display:none}.toolbar{padding:10px;display:flex;flex-wrap:wrap;gap:8px;margin:9px 0}.toolbar label{display:grid;gap:4px;color:var(--muted);font-size:11px;text-transform:uppercase;letter-spacing:.05em}.toolbar input,.toolbar select{padding:8px 10px;min-width:122px;color:var(--text)}.toolbar .search{flex:1;min-width:300px;align-self:end}.layout{display:grid;grid-template-columns:minmax(0,1fr) 390px;gap:14px;align-items:start}.content-stack{min-width:0;display:grid;gap:12px}.card{padding:19px}.item-card{min-height:430px}.item-head{display:flex;gap:16px;justify-content:space-between;align-items:flex-start;border-bottom:1px solid var(--line-soft);padding-bottom:15px}.item-head h2{margin:2px 0 5px;font-size:28px;letter-spacing:-.025em}.badges{display:flex;flex-wrap:wrap;gap:6px}.badge{display:inline-flex;align-items:center;border:1px solid var(--line);border-radius:999px;padding:3px 8px;font-size:11px;font-weight:700;letter-spacing:.035em}.HIGH{color:#ff8b86;border-color:#754046;background:#28171b}.MEDIUM{color:#f0c66c;border-color:#66552e;background:#261f12}.LOW{color:#9aabbd}.APPROVED{color:var(--green);border-color:#2c6343;background:var(--green-soft)}.REJECTED{color:var(--red);border-color:#70393e;background:var(--red-soft)}.PENDING{color:#a7b5c5}.MAPPED{color:var(--blue);border-color:#315b7c;background:var(--blue-soft)}.UNMAPPED,.NEW{color:#ffc671;border-color:#775c2d;background:#2b2213}.CHANGED{color:#ff8b86;border-color:#754046;background:#28171b}.no-wiki,.catalog-warning{border:1px solid #725a2b;background:linear-gradient(100deg,#2b2111,#1d1a13);color:#ffd28b;padding:13px 14px;border-radius:10px;font-weight:650;margin-top:13px}.catalog-warning{border-color:#784148;background:linear-gradient(100deg,#2d171b,#1d1518);color:#ffa09b}.item-card h3,.decision h3,.economy-box h3{font-size:11px;text-transform:uppercase;letter-spacing:.12em;color:var(--muted);margin:19px 0 8px}.identity{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;background:#0d141e;border:1px solid var(--line-soft);padding:9px 11px;border-radius:8px;margin:5px 0;overflow-wrap:anywhere;color:#c8d7e7}.evidence{margin:0;padding-left:20px}.evidence li{margin:5px 0}.proposal,.existing{border:1px solid #5d4b27;border-left:3px solid var(--gold);padding:10px 12px;background:#1e1c16;border-radius:8px}.existing{border-color:#294d68;border-left-color:var(--blue);background:#111f2b;line-height:1.65}.decision{position:sticky;top:70px;max-height:calc(100vh - 86px);overflow:auto;box-shadow:var(--shadow)}.decision-lead{display:flex;align-items:center;justify-content:space-between;gap:10px}.decision-lead h2{font-size:18px;margin:0}.decision label{display:block;margin:11px 0 5px;color:var(--muted);font-size:12px}.decision input,.decision textarea{width:100%;padding:9px 10px}.decision textarea{min-height:72px;resize:vertical}.actions{display:grid;grid-template-columns:repeat(3,1fr);gap:7px;margin-top:12px}.actions button{font-weight:700}.actions .reject{border-color:#7a3f45;color:#ff8d88;background:#241519}.actions .approve{border-color:#315d43;color:#78e49f;background:#12231a}.actions .skip{grid-column:span 3;color:var(--muted)}.actions.picker{box-shadow:0 0 0 2px var(--gold);border-radius:10px;padding:3px}.custom{display:flex;gap:7px;margin-top:8px}.custom input{min-width:0}.custom button{white-space:nowrap}.analysis{margin-top:9px;padding:10px;border:1px solid #294d68;border-left:3px solid var(--blue);background:#111f2b;border-radius:8px}.analysis.HIGH{border-color:#713a40;color:var(--text);background:#28171b}.analysis.MEDIUM{border-color:#66542c;color:var(--text);background:#261f12}.confirm-approve{width:100%;margin-top:8px;border-color:#33714b;background:#173323;color:#85eca9;font-weight:800}.nav{display:grid;grid-template-columns:1fr 1fr;gap:7px;margin-top:9px}.nav .wide{grid-column:span 2}.jump{display:flex;gap:7px;margin-top:8px}.jump input{width:100%;padding:8px}.key-hints{display:flex;gap:8px;flex-wrap:wrap;color:var(--muted2);font-size:11px;margin-top:8px}.key-hints kbd{border:1px solid var(--line);border-bottom-width:2px;background:#0a111a;border-radius:5px;padding:1px 5px;color:var(--muted)}.io{display:grid;grid-template-columns:1fr 1fr;gap:6px}.io button{font-size:12px}.io .wide{grid-column:1/-1}.danger{border-color:#713a40;color:#ff8b86}.message{min-height:22px;margin-top:8px;color:var(--muted)}.recent button{display:block;width:100%;text-align:left;margin:5px 0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;border-color:transparent;background:#101722;color:var(--muted)}.item-history{display:grid;gap:6px}.item-history-row{display:grid;grid-template-columns:auto 1fr auto;gap:8px;align-items:center;font-size:12px;padding:7px 0;border-bottom:1px solid var(--line-soft)}.history-list{display:grid;gap:8px}.history-entry{background:var(--panel);border:1px solid var(--line-soft);border-radius:11px;padding:14px}.history-head{display:flex;justify-content:space-between;gap:8px;align-items:flex-start}.history-diff{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;margin-top:7px}.history-note{white-space:pre-wrap}.history-tools{justify-content:space-between}.history-tools .filters{display:flex;flex-wrap:wrap;gap:8px}.history-tools input,.history-tools select{padding:7px 9px}a{color:#7bc2ff;text-decoration:none}a:hover{text-decoration:underline}.empty{text-align:center;padding:64px 20px;color:var(--muted)}dialog{width:min(560px,calc(100vw - 30px));background:var(--panel);color:var(--text);border:1px solid var(--line);border-radius:15px;padding:22px;box-shadow:var(--shadow)}dialog::backdrop{background:#02050acc;backdrop-filter:blur(3px)}.dialog-actions{display:flex;justify-content:flex-end;gap:8px;margin-top:17px}.warning{color:#ffd18a;background:#2d2414;border:1px solid #674f26;padding:11px;border-radius:8px}.economy-box{padding:16px;border:1px solid var(--line-soft);background:linear-gradient(145deg,var(--panel),#101721);border-radius:var(--radius)}.economy-box h3{margin-top:0}.similar-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:7px}.similar-item{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:7px;text-align:left;width:100%;background:#0f1721}.similar-item span:first-child{min-width:0}.similar-item strong,.similar-item small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.similar-item small{color:var(--muted)}.economy-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}.economy-list{display:grid;gap:6px}.economy-row{display:flex;justify-content:space-between;gap:8px;border:0;border-bottom:1px solid var(--line-soft);border-radius:0;padding:8px 2px;background:transparent;text-align:left;width:100%}.calculator{display:flex;flex-wrap:wrap;align-items:end;gap:10px}.calculator label{display:grid;gap:4px}.calculator input{padding:8px}.risk-HIGH{color:#ff8b86}.risk-MEDIUM{color:#efc36a}.risk-LOW{color:#9aabbd}.safety-note{font-size:12px;color:var(--muted);padding:9px 10px;border:1px dashed var(--line);border-radius:8px;margin-top:9px}.section-title{display:flex;align-items:center;justify-content:space-between;gap:10px}.section-title h2{margin:0;font-size:18px}.session-card{margin:9px 0}.session-card summary{cursor:pointer;color:var(--muted);padding:4px;font-weight:650}.session-card[open] summary{margin-bottom:10px}.session-grid{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:8px}.view-heading{display:flex;justify-content:space-between;align-items:end;margin:16px 0 10px}.view-heading h2{margin:0;font-size:23px}.view-heading p{margin:3px 0 0}.review-safety{display:flex;gap:8px;align-items:center;color:var(--muted);font-size:12px}.review-safety::before{content:'✓';display:grid;place-items:center;width:20px;height:20px;border-radius:50%;background:var(--green-soft);color:var(--green)}
                @media(max-width:1180px){.layout{grid-template-columns:minmax(0,1fr) 350px}.hero-grid{grid-template-columns:minmax(250px,1fr) repeat(2,1fr)}.hero-progress{grid-row:span 3}.session-grid{grid-template-columns:repeat(3,1fr)}.similar-grid{grid-template-columns:1fr}}
                @media(max-width:900px){.app{padding:12px}.layout{grid-template-columns:1fr}.decision{position:static;max-height:none}.hero-grid{grid-template-columns:1fr 1fr}.hero-progress{grid-column:1/-1;grid-row:auto}.top{align-items:flex-start;flex-direction:column}.position{text-align:left}.workspace-tools{grid-template-columns:1fr}.catalog-strip{grid-template-columns:1fr 1fr}.economy-grid{grid-template-columns:1fr}.tabs{top:4px}.local-state{display:none}}
                @media(max-width:600px){.hero-grid,.catalog-strip,.session-grid{grid-template-columns:1fr 1fr}.metric{padding:9px}.toolbar label{width:calc(50% - 4px)}.toolbar select{width:100%;min-width:0}.toolbar .search{width:100%;min-width:0}.item-head{display:grid}.item-head h2{font-size:23px}.card{padding:14px}.io{grid-template-columns:1fr}.tabs button{padding:8px}.key-hints{display:none}}
                @media(prefers-reduced-motion:reduce){*{scroll-behavior:auto!important;transition:none!important}}
              </style>
            </head>
            <body data-ui-version="review-cockpit-v2">
            <a class="skip-link" href="#itemCard">Przejdź do aktualnego itemu</a>
            <main class="app">
              <header class="top"><div class="brand"><div class="brand-mark" aria-hidden="true">LR</div><div><div class="eyebrow">Economy governance</div><h1>LainaReforge · Review Workspace</h1><div class="muted">Lokalny panel decyzji. Żadne dane nie opuszczają tej przeglądarki.</div></div></div><div id="position" class="position" aria-live="polite"></div></header>
              <nav class="tabs" aria-label="Główne widoki"><button id="reviewTab" class="active">Przegląd</button><button id="economyTab">Ekonomia</button><button id="historyTab">Historia <span id="historyCount">0</span></button><span class="tab-spacer"></span><span class="local-state">Lokalne decyzje: <strong id="localDecisionCount">0</strong> · zmiana: <span id="lastChanged">brak</span></span></nav>
              <div id="reviewView">
                <section class="hero-grid" aria-label="Postęp przeglądu">
                  <div class="hero-progress"><div class="summary"><div><div class="eyebrow">Postęp decyzji</div><span>Reviewed / Total</span></div><b id="reviewedCount">0 / 0</b></div><div class="progress-track" aria-hidden="true"><span id="reviewProgressBar" class="progress-fill"></span></div><div class="muted"><span id="reviewProgressPercent">0%</span> katalogu ma decyzję człowieka</div></div>
                  <div class="metric"><span>Pending</span><b id="pendingCount">0</b></div><div class="metric"><span>Approved</span><b id="approvedCount">0</b></div><div class="metric"><span>Rejected</span><b id="rejectedCount">0</b></div>
                  <div class="metric compact"><span>MAPPED reviewed / total</span><b id="mappedProgress">0 / 0</b></div><div class="metric compact"><span>UNMAPPED reviewed / total</span><b id="unmappedProgress">0 / 0</b></div><div class="metric compact"><span>HIGH · MEDIUM · LOW pending</span><b><span id="highPending">0</span> · <span id="mediumPending">0</span> · <span id="lowPending">0</span></b></div>
                </section>
                <section class="catalog-strip" aria-label="Pokrycie katalogu"><div class="metric"><span>Catalog identities</span><b>__CATALOG_IDENTITIES__</b></div><div class="metric"><span>Mapped identities</span><b>__MAPPED_IDENTITIES__</b></div><div class="metric"><span>Unmapped identities</span><b>__UNMAPPED_IDENTITIES__</b></div><div class="metric"><span>Coverage</span><b>__CATALOG_IDENTITIES__ / __CATALOG_IDENTITIES__</b></div></section>
                <details class="card session-card"><summary>Stan sesji, kopie bezpieczeństwa i gotowość wdrożenia</summary><div class="session-grid"><div class="metric"><span>Itemy / identities</span><b><span id="sessionItems">0</span> / <span id="sessionIdentities">__CATALOG_IDENTITIES__</span></b></div><div class="metric"><span>Reviewed / pending</span><b><span id="sessionReviewed">0</span> / <span id="sessionPending">0</span></b></div><div class="metric"><span>Approved / rejected</span><b><span id="sessionApproved">0</span> / <span id="sessionRejected">0</span></b></div><div class="metric"><span>NEW / CHANGED pending</span><b><span id="sessionNew">0</span> / <span id="sessionChanged">0</span></b></div><div class="metric"><span>HIGH economy risk</span><b id="sessionHighRisk">0</b></div><div class="metric"><span>Ostatnia zmiana</span><b id="sessionLastChanged">brak</b></div></div></details>
                <div class="workspace-tools"><section class="queues" aria-label="Kolejki"><button data-queue="ALL" class="active">Wszystkie</button><button data-queue="PENDING">Do sprawdzenia</button><button data-queue="NEW_CHANGED">Nowe i zmienione</button><button data-queue="OUTLIERS">Podejrzane wyceny</button><button data-queue="HIGH">HIGH priority</button><button data-queue="UNMAPPED">UNMAPPED</button><button data-queue="MAPPED">MAPPED</button><button data-queue="APPROVED">APPROVED</button><button data-queue="REJECTED">REJECTED</button></section></div>
                <section class="toolbar" aria-label="Filtry">
                  <label>Status <select id="statusFilter"><option>ALL</option><option>PENDING</option><option>APPROVED</option><option>REJECTED</option></select></label><label>Katalog <select id="catalogFilter"><option>ALL</option><option value="NEW_CHANGED">NEW + CHANGED</option><option>NEW</option><option>CHANGED</option><option>UNCHANGED</option></select></label><label>Mapping <select id="mappingFilter"><option>ALL</option><option>MAPPED</option><option>UNMAPPED</option></select></label><label>Priority <select id="priorityFilter"><option>ALL</option><option>HIGH</option><option>MEDIUM</option><option>LOW</option></select></label><label>Proposal <select id="proposalFilter"><option>ALL</option><option>NO</option><option>UNKNOWN</option></select></label><label>Tag <select id="tagFilter"><option>ALL</option><option>INFINITE_OR_FARMABLE</option><option>REPEATABLE</option><option>LIMITED</option><option>KEY_REWARD</option><option>EVENT</option><option>QUEST</option><option>SHOP</option><option>CRAFT</option><option>DROP</option><option>UNKNOWN</option></select></label><input id="search" class="search" type="search" aria-label="Szukaj w katalogu" placeholder="Szukaj: logical id, nazwa, wiki, material:CMD, model_path">
                </section>
                <div class="view-heading"><div><h2>Stanowisko recenzenta</h2><p class="muted">Najpierw oceń źródło i ryzyko, potem podejmij ręczną decyzję.</p></div><div class="review-safety">System doradza — człowiek zatwierdza</div></div>
                <div class="layout"><div class="content-stack"><section id="itemCard" class="card item-card" tabindex="-1"></section><section id="economyAssistant" class="economy-box"></section></div><aside class="card decision" aria-label="Panel decyzji">
                  <div class="decision-lead"><h2>Decyzja</h2><span class="badge PENDING">HUMAN ONLY</span></div><h3>Aktualny stan</h3><div id="currentDecision" class="existing muted">PENDING — brak zapisanej decyzji</div><button id="showItemHistory">Pokaż historię tego itemu</button>
                  <h3>Osoba zatwierdzająca</h3><label for="reviewer">reviewed_by (dla tej sesji)</label><input id="reviewer" autocomplete="name" placeholder="np. e2ot3rror"><label for="note">Notatka do decyzji</label><textarea id="note" placeholder="Opcjonalnie: uzasadnienie lub kontekst"></textarea>
                  <h3>Wybierz wynik</h3><div id="shardPicker" class="actions"><button class="reject" data-action="reject">ODRZUĆ</button><button class="approve" data-preview-shards="1">1 SHARD</button><button class="approve" data-preview-shards="2">2 SHARDS</button><button class="approve" data-preview-shards="3">3 SHARDS</button><button class="approve" data-preview-shards="4">4 SHARDS</button><button class="approve" data-preview-shards="5">5 SHARDS</button><button class="skip" data-action="skip">POMIŃ</button></div>
                  <div class="custom"><input id="customShards" type="number" min="1" max="__MAX_SHARDS_PER_ITEM__" step="1" placeholder="Custom shards (1–__MAX_SHARDS_PER_ITEM__)" aria-label="Własna liczba shardów"><button id="customApprove">ANALIZUJ</button></div><div id="shardAnalysis" class="analysis muted">Wybierz wartość, aby zobaczyć analizę.</div><button id="confirmShardDecision" class="confirm-approve" disabled>ZAPISZ APPROVED</button><div class="safety-note">Wartość nie jest zapisywana po samym wyborze. Zatwierdzenie wymaga osobnego kliknięcia.</div>
                  <h3>Nawigacja</h3><div class="nav"><button id="previous">← Poprzedni</button><button id="next">Następny →</button><button id="nextPending" class="wide">Następny wymagający decyzji</button><button id="nextHigh" class="wide">Następny HIGH</button></div><div class="jump"><input id="jumpInput" list="itemList" placeholder="Nazwa, logical id lub material:CMD"><datalist id="itemList"></datalist><button id="jumpButton">Idź</button></div><div class="key-hints"><span><kbd>A</kbd> shards</span><span><kbd>R</kbd> odrzuć</span><span><kbd>S</kbd> pomiń</span><span><kbd>←</kbd><kbd>→</kbd> nawigacja</span></div>
                  <h3>Historia tego itemu</h3><div id="itemHistoryPreview" class="item-history muted">Brak wcześniejszych zmian.</div>
                  <h3>Ostatnio przeglądane</h3><div id="recentList" class="recent muted">Brak</div>
                  <h3>Dane lokalne</h3><div class="io"><button id="exportButton">EXPORT DECISIONS</button><button id="backupButton">BACKUP DECISIONS</button><button id="sessionBackupButton">BACKUP SESJI</button><button id="sessionReportButton">RAPORT SESJI</button><button id="readinessButton">SPRAWDŹ GOTOWOŚĆ</button><button id="importButton">IMPORT DECISIONS</button><button id="resetButton" class="danger wide">RESET LOCAL DECISIONS</button><input id="importFile" type="file" accept=".yml,.yaml,text/yaml" hidden></div><div id="message" class="message" role="status" aria-live="polite"></div>
                </aside></div>
              </div>
              <section id="historyView" hidden><div class="view-heading"><div><h2>Historia decyzji</h2><p class="muted">Niezależny, append-only dziennik zmian zapisany lokalnie.</p></div></div><div class="toolbar history-tools"><div class="filters"><label>Reviewer <input id="historyReviewer" placeholder="Wszyscy"></label><label>Action <select id="historyAction"><option>ALL</option><option>APPROVED</option><option>REJECTED</option><option>EDITED</option><option>IMPORTED</option><option>CATALOG_CHANGED</option></select></label><label>Nowy status <select id="historyStatus"><option>ALL</option><option>PENDING</option><option>APPROVED</option><option>REJECTED</option></select></label><input id="historySearch" class="search" type="search" placeholder="Item, logical id lub material:CMD"></div><div class="io"><button id="exportHistory">EXPORT HISTORY</button><button id="resetHistory" class="danger">RESET HISTORY</button></div></div><div id="historyMessage" class="message" role="status" aria-live="polite"></div><div id="historyList" class="history-list"></div></section>
              <section id="economyView" hidden><div class="view-heading"><div><h2>Obraz ekonomii</h2><p class="muted">Dane pomocnicze — nigdy automatyczna decyzja.</p></div></div><section id="economyMetrics" class="hero-grid"></section><div class="economy-grid"><section class="card"><h2>Rozkład wycen</h2><div id="economyDistribution" class="economy-list"></div></section><section class="card"><h2>Najwyższe / najniższe</h2><div id="economyExtremes" class="economy-list"></div></section><section class="card"><h2>Największe outliery</h2><div id="economyOutliers" class="economy-list"></div></section></div><section class="card" style="margin-top:10px"><h2>Symulacja recyclingu</h2><div class="calculator"><label>Ilość itemów<input id="whatIfCount" type="number" min="0" step="1" value="1"></label><label>Shards / item<input id="whatIfShards" type="number" min="0" max="__MAX_SHARDS_PER_ITEM__" step="1" value="1"></label><div id="whatIfResult" class="analysis">1 × 1 = 1 shard</div></div></section></section>
            </main>
            <dialog id="exportDialog"><h2>Podsumowanie eksportu</h2><div id="exportSummary"></div><div id="exportWarning" class="warning"></div><div class="dialog-actions"><button id="cancelExport">Anuluj</button><button id="confirmExport">Potwierdź eksport</button></div></dialog>
            <dialog id="readinessDialog"><h2>Gotowość review</h2><div id="readinessResults"></div><div class="dialog-actions"><button id="closeReadiness">Zamknij</button></div></dialog>
            <script>
            """, """
            'use strict';
            const QUEUE = __QUEUE_DATA__;
            const QUEUE_FINGERPRINT = '__QUEUE_FINGERPRINT__';
            const STORAGE_KEY = __STORAGE_KEY__;
            const HISTORY_STORAGE_KEY = STORAGE_KEY + '.history';
            const REVIEWER_KEY = STORAGE_KEY + '.reviewer';
            const LAST_CHANGE_KEY = STORAGE_KEY + '.last-change';
            const RECENT_KEY = STORAGE_KEY + '.recent';
            const MAX_SHARDS_PER_ITEM = __MAX_SHARDS_PER_ITEM__;
            const MAX_SHARDS_PER_TRANSACTION = __MAX_SHARDS_PER_TRANSACTION__;
            const CATALOG_IDENTITIES = __CATALOG_IDENTITIES__;
            const MIN_COMPARABLE_APPROVED = 3;
            const ITEM_BY_ID = new Map(QUEUE.map(item => [item.id, item]));
            const EVOLUTION_PREVIOUS_IDS = new Set(QUEUE.map(item=>item.previousLogicalId).filter(Boolean));
            const GLOBAL_INDEX = new Map(QUEUE.map((item,index) => [item.id,index+1]));
            const filters = {status:'ALL',catalog:'ALL',mapping:'ALL',priority:'ALL',proposal:'ALL',tag:'ALL',search:''};
            let decisions = loadDecisions();
            let history = loadHistory();
            let recent = loadRecent();
            let visibleItems = QUEUE.slice();
            let currentId = visibleItems[0]?.id || null;
            let selectedShards = null;
            let selectedShardsItemId = null;
            let activeQueue = 'ALL';
            const $ = id => document.getElementById(id);

            function loadDecisions(){try{return validateStoredObject(JSON.parse(localStorage.getItem(STORAGE_KEY)||'{}'))}catch(error){console.warn(error);return {}}}
            function loadHistory(){try{const value=JSON.parse(localStorage.getItem(HISTORY_STORAGE_KEY)||'[]');return Array.isArray(value)?value.filter(validateHistoryEntry):[]}catch(error){console.warn(error);return []}}
            function loadRecent(){try{const value=JSON.parse(localStorage.getItem(RECENT_KEY)||'[]');return Array.isArray(value)?value.filter(id=>ITEM_BY_ID.has(id)).slice(0,8):[]}catch(error){return []}}
            function validateStoredObject(value){if(!value||Array.isArray(value)||typeof value!=='object')return {};const clean={};for(const [id,decision] of Object.entries(value)){if(!ITEM_BY_ID.has(id)&&!EVOLUTION_PREVIOUS_IDS.has(id))continue;try{clean[id]=validateDecision(decision)}catch(error){console.warn('Pomijam błędną decyzję',id,error)}}return clean}
            function validateDecision(value){if(!value||typeof value!=='object')throw new Error('Decyzja musi być obiektem');const {status,recyclable,shards,reviewed_by,reviewed_at,note}=value;if(status==='APPROVED'){if(recyclable!==true||!Number.isInteger(shards)||shards<1||shards>MAX_SHARDS_PER_ITEM)throw new Error(`APPROVED wymaga shards od 1 do ${MAX_SHARDS_PER_ITEM}`)}else if(status==='REJECTED'){if(recyclable!==false||shards!==0)throw new Error('REJECTED wymaga shards=0')}else{throw new Error('Dozwolone są tylko APPROVED i REJECTED')}if(typeof reviewed_by!=='string'||typeof reviewed_at!=='string'||!reviewed_at||typeof note!=='string')throw new Error('Niepoprawne metadane decyzji');return {status,recyclable,shards,reviewed_by,reviewed_at,note}}
            function validateHistoryEntry(entry){return Boolean(entry&&typeof entry.timestamp==='string'&&typeof entry.reviewed_by==='string'&&typeof entry.logical_item_id==='string'&&typeof entry.display_name==='string'&&Array.isArray(entry.identities)&&['APPROVED','REJECTED','EDITED','IMPORTED','CATALOG_CHANGED'].includes(entry.action)&&(entry.previous_status===null||['APPROVED','REJECTED'].includes(entry.previous_status))&&['PENDING','APPROVED','REJECTED'].includes(entry.new_status))}
            function historyEntry(item,previous,next,imported=false,timestamp=next.reviewed_at){return {timestamp,reviewed_by:next.reviewed_by,logical_item_id:item.id,display_name:item.name,identities:item.identities.map(identity=>`${identity.material}:${identity.cmd}`),action:imported?'IMPORTED':previous?'EDITED':next.status,previous_status:previous?.status??null,new_status:next.status,previous_shards:previous?.shards??null,new_shards:next.shards,previous_note:previous?.note??null,new_note:next.note}}
            function appendHistory(entries){if(!entries.length)return;const updated=[...history,...entries];localStorage.setItem(HISTORY_STORAGE_KEY,JSON.stringify(updated));history=updated;$('historyCount').textContent=history.length}
            function reconcileCatalogChanges(){const entries=[];let decisionsChanged=false;for(const item of QUEUE.filter(candidate=>candidate.catalogStatus==='CHANGED')){const previousId=item.previousLogicalId;if(!previousId||!decisions[previousId])continue;const alreadyRecorded=history.some(entry=>entry.action==='CATALOG_CHANGED'&&entry.logical_item_id===item.id);if(!alreadyRecorded){const previous=decisions[previousId];entries.push({timestamp:new Date().toISOString(),reviewed_by:'',logical_item_id:item.id,display_name:item.name,identities:item.identities.map(identity=>`${identity.material}:${identity.cmd}`),action:'CATALOG_CHANGED',previous_status:previous.status,new_status:'PENDING',previous_shards:previous.shards,new_shards:null,previous_note:previous.note,new_note:`Model zmieniony: ${item.beforeModelPath||'—'} -> ${item.identities[0]?.modelPath||'—'}`})}if(!ITEM_BY_ID.has(previousId)){delete decisions[previousId];decisionsChanged=true}}appendHistory(entries);if(decisionsChanged){localStorage.setItem(STORAGE_KEY,JSON.stringify(decisions));localStorage.setItem(LAST_CHANGE_KEY,new Date().toISOString())}}
            function save(){localStorage.setItem(STORAGE_KEY,JSON.stringify(decisions));localStorage.setItem(LAST_CHANGE_KEY,new Date().toISOString());updateProgress()}
            function effectiveStatus(item){return decisions[item.id]?.status||item.initialStatus}
            function searchable(item){return [item.id,item.name,item.wiki,...item.identities.flatMap(identity=>[identity.material,String(identity.cmd),identity.modelPath,`${identity.material}:${identity.cmd}`])].join(' ').toLocaleLowerCase('pl')}
            function meaningfulTags(item){return item.acquisition.tags.filter(tag=>tag!=='UNKNOWN')}
            function similarityScore(source,candidate){if(source.id===candidate.id)return -1;let score=source.type===candidate.type?4:0;if(source.modelGroup&&source.modelGroup===candidate.modelGroup)score+=3;const tags=new Set(meaningfulTags(source));score+=Math.min(2,meaningfulTags(candidate).filter(tag=>tags.has(tag)).length)*2;if(source.mappingStatus===candidate.mappingStatus)score++;if(source.proposal.value===candidate.proposal.value)score++;return score}
            function allSimilar(item){return QUEUE.filter(candidate=>candidate.id!==item.id).map(candidate=>({item:candidate,score:similarityScore(item,candidate)})).filter(value=>value.score>=4).sort((a,b)=>b.score-a.score||a.item.id.localeCompare(b.item.id,'pl'))}
            function percentile(values,p){const position=(values.length-1)*p;const lower=Math.floor(position),upper=Math.ceil(position);return lower===upper?values[lower]:values[lower]+(values[upper]-values[lower])*(position-lower)}
            function shardStatistics(values){const sorted=[...values].sort((a,b)=>a-b);if(sorted.length<MIN_COMPARABLE_APPROVED)return {count:sorted.length,sufficient:false};return {count:sorted.length,sufficient:true,min:sorted[0],max:sorted.at(-1),median:percentile(sorted,.5),average:sorted.reduce((sum,value)=>sum+value,0)/sorted.length,p25:percentile(sorted,.25),p75:percentile(sorted,.75)}}
            function peerStatistics(item){return shardStatistics(allSimilar(item).map(value=>decisions[value.item.id]).filter(decision=>decision?.status==='APPROVED').map(decision=>decision.shards))}
            function riskAnalysis(item,shards,stats=peerStatistics(item)){const flags=[],reasons=[];const ratio=stats.sufficient&&stats.median>0?shards/stats.median:null;if(ratio!==null&&ratio>2){flags.push('OUTLIER HIGH');reasons.push(`${ratio.toFixed(1)}× mediana podobnych itemów`)}else if(ratio!==null&&ratio<.5){flags.push('OUTLIER LOW');reasons.push(`${ratio.toFixed(1)}× mediana podobnych itemów`)}const highValue=ratio!==null?ratio>1.5:shards>=6;if(item.acquisition.tags.includes('INFINITE_OR_FARMABLE')&&highValue){flags.push('HIGH ECONOMY RISK');reasons.push('INFINITE_OR_FARMABLE przy wysokiej wycenie')}if(item.acquisition.tags.includes('REPEATABLE')&&ratio!==null&&ratio>2){if(!flags.includes('HIGH ECONOMY RISK'))flags.push('HIGH ECONOMY RISK');reasons.push('REPEATABLE i wycena ponad 2× mediany')}for(const tag of ['LIMITED','EVENT','QUEST'])if(item.acquisition.tags.includes(tag))reasons.push(`${tag} — item może być rzadki`);const level=flags.includes('HIGH ECONOMY RISK')?'HIGH':flags.length||reasons.length?'MEDIUM':'LOW';if(level==='LOW')reasons.push('Brak wykrytych deterministycznych flag ryzyka');return {level,flags,reasons,ratio}}
            function isOutlier(item){const decision=decisions[item.id];if(decision?.status!=='APPROVED')return false;const risk=riskAnalysis(item,decision.shards);return risk.flags.includes('OUTLIER HIGH')||risk.flags.includes('OUTLIER LOW')||risk.flags.includes('HIGH ECONOMY RISK')}
            function renderEconomyAssistant(card,item){const box=text('section','','economy-box');box.append(text('h3','Podobne itemy'));const matches=allSimilar(item).slice(0,8);if(!matches.length){box.append(text('div','Brak wystarczających danych ekonomicznych.','muted'));card.append(box);return}const grid=text('div','','similar-grid');for(const match of matches){const peer=match.item,decision=decisions[peer.id];const button=text('button','','similar-item');const main=document.createElement('span');main.append(text('strong',peer.name),text('small',`${peer.identities[0].material}:${peer.identities[0].cmd} · ${peer.type} · ${peer.acquisition.tags.join(', ')||'brak tagów'}`));button.append(main,text('span',decision?.status==='APPROVED'?`APPROVED · ${decision.shards}`:decision?.status||'PENDING','badge '+(decision?.status||'PENDING')));button.addEventListener('click',()=>{currentId=peer.id;selectedShards=null;showView('review');render()});grid.append(button)}box.append(grid);const stats=peerStatistics(item);box.append(text('h3','Statystyki wyceny'));if(!stats.sufficient)box.append(text('div',`Za mało danych do sensownego porównania (${stats.count}/${MIN_COMPARABLE_APPROVED}).`,'muted'));else box.append(text('div',`Podobne zatwierdzone: ${stats.count} · zakres ${stats.min}–${stats.max} · mediana ${formatNumber(stats.median)} · średnia ${formatNumber(stats.average)} · P25/P75 ${formatNumber(stats.p25)}/${formatNumber(stats.p75)}`));if((item.catalogStatus==='NEW'||item.catalogStatus==='CHANGED')&&!stats.sufficient)box.append(text('div','Brak wystarczających danych ekonomicznych.','warning'));card.append(box)}
            function updateEconomyAssistant(){const host=$('economyAssistant'),item=current();host.replaceChildren();if(!item){host.hidden=true;return}if(selectedShardsItemId!==null&&selectedShardsItemId!==item.id)clearShardSelection();host.hidden=false;renderEconomyAssistant(host,item)}
            function formatNumber(value){return Number.isInteger(value)?String(value):value.toFixed(1)}
            function stageShards(value){const shards=Number(value);if(!Number.isInteger(shards)||shards<1||shards>MAX_SHARDS_PER_ITEM){showMessage(`Podaj liczbę całkowitą od 1 do ${MAX_SHARDS_PER_ITEM}.`,true);return}const item=current();if(!item)return;selectedShards=shards;selectedShardsItemId=item.id;const stats=peerStatistics(item),risk=riskAnalysis(item,shards,stats),analysis=$('shardAnalysis');analysis.className='analysis '+risk.level;analysis.replaceChildren(text('strong',`Wybrana wartość: ${shards} shards · ryzyko ${risk.level}`));if(stats.sufficient)analysis.append(text('div',`Mediana podobnych: ${formatNumber(stats.median)}${risk.ratio===null?'':` · relacja ${risk.ratio.toFixed(1)}×`}`));else analysis.append(text('div','Za mało danych do sensownego porównania.'));for(const flag of risk.flags)analysis.append(text('div',flag,'risk-'+risk.level));for(const reason of risk.reasons)analysis.append(text('div','• '+reason));$('confirmShardDecision').disabled=false;showMessage('Analiza gotowa. Decyzja nie została jeszcze zapisana.')}
            function applyFilters(){const query=filters.search.trim().toLocaleLowerCase('pl');visibleItems=QUEUE.filter(item=>(activeQueue!=='OUTLIERS'||isOutlier(item))&&(filters.status==='ALL'||effectiveStatus(item)===filters.status)&&(filters.catalog==='ALL'||filters.catalog==='NEW_CHANGED'&&(item.catalogStatus==='NEW'||item.catalogStatus==='CHANGED')||item.catalogStatus===filters.catalog)&&(filters.mapping==='ALL'||item.mappingStatus===filters.mapping)&&(filters.priority==='ALL'||item.priority===filters.priority)&&(filters.proposal==='ALL'||item.proposal.value===filters.proposal)&&(filters.tag==='ALL'||item.acquisition.tags.includes(filters.tag))&&(!query||searchable(item).includes(query)));if(!visibleItems.some(item=>item.id===currentId))currentId=visibleItems[0]?.id||null;render()}
            function applyQueue(view){activeQueue=view;for(const key of ['status','catalog','mapping','priority','proposal','tag'])filters[key]='ALL';filters.search='';if(['PENDING','APPROVED','REJECTED'].includes(view))filters.status=view;else if(['MAPPED','UNMAPPED'].includes(view))filters.mapping=view;else if(view==='HIGH')filters.priority='HIGH';else if(view==='NEW_CHANGED')filters.catalog='NEW_CHANGED';for(const [id,key] of [['statusFilter','status'],['catalogFilter','catalog'],['mappingFilter','mapping'],['priorityFilter','priority'],['proposalFilter','proposal'],['tagFilter','tag']])$(id).value=filters[key];$('search').value='';document.querySelectorAll('[data-queue]').forEach(button=>button.classList.toggle('active',button.dataset.queue===view));applyFilters()}
            function current(){return ITEM_BY_ID.get(currentId)}
            function text(tag,value,className=''){const node=document.createElement(tag);node.textContent=value;if(className)node.className=className;return node}
            function render(){const card=$('itemCard');card.replaceChildren();const item=current();if(!item){card.append(text('div','Brak itemów dla wybranych filtrów.','empty'));$('position').textContent='Globalnie 0 / '+QUEUE.length+' · W filtrze 0 / 0';$('note').value='';$('currentDecision').textContent='PENDING — brak zapisanej decyzji';updateProgress();return}const head=text('div','','item-head');const title=document.createElement('div');title.append(text('h2',item.name),text('div',item.id,'muted'));const badges=text('div','','badges');badges.append(text('span',item.priority,'badge '+item.priority),text('span',effectiveStatus(item),'badge '+effectiveStatus(item)),text('span',item.mappingStatus==='UNMAPPED'?'BRAK WIKI':'MAPPED','badge '+item.mappingStatus));if(item.catalogStatus==='NEW')badges.append(text('span','NOWY ITEM','badge NEW'));if(item.catalogStatus==='CHANGED')badges.append(text('span','ZMIENIONY ITEM','badge CHANGED'));head.append(title,badges);card.append(head);if(item.catalogStatus==='CHANGED'){const after=item.identities[0]?.modelPath||'—';card.append(text('div',`Definicja identity uległa zmianie i wymaga ponownego review. BEFORE: ${item.beforeModelPath||'—'} · AFTER: ${after}`,'catalog-warning'))}if(item.mappingStatus==='MAPPED'){card.append(text('h3','Wiki'));const wiki=document.createElement('a');wiki.href='https://wiki.laina.pl/index.php?title='+encodeURIComponent(item.wiki);wiki.target='_blank';wiki.rel='noopener noreferrer';wiki.textContent=item.name+' — otwórz Wiki ↗';card.append(wiki)}else{card.append(text('div','Brak pewnego mapowania. Decyzja musi zostać wykonana ręcznie.','no-wiki'))}card.append(text('h3','Material / CMD / model_path'));for(const identity of item.identities)card.append(text('div',`${identity.material}:${identity.cmd} · ${identity.modelPath}`,'identity'));card.append(text('h3','Zdobycie'),text('p',item.acquisition.summary));const tags=text('div','','badges');for(const tag of item.acquisition.tags)tags.append(text('span',tag,'badge'));card.append(tags,text('h3','Evidence'));const evidence=text('ul','','evidence');for(const entry of item.evidence)evidence.append(text('li',entry));card.append(evidence,text('h3','System proposal'));const proposal=text('div','','proposal');proposal.append(text('strong',item.proposal.value),text('div',item.proposal.reason));card.append(proposal,text('h3','Powód przeglądu'),text('p',item.reviewReason));const existing=decisions[item.id];$('note').value=existing?.note||'';$('currentDecision').textContent=existing?`${existing.status} · shards: ${existing.shards} · ${existing.reviewed_by||'bez reviewera'} · ${existing.reviewed_at}${existing.note?' · '+existing.note:''}`:'PENDING — brak zapisanej decyzji';const filterIndex=visibleItems.findIndex(candidate=>candidate.id===item.id)+1;$('position').textContent=`Globalnie ${GLOBAL_INDEX.get(item.id)} / ${QUEUE.length} · W filtrze ${filterIndex} / ${visibleItems.length}`;updateProgress()}
            function remember(id){recent=[id,...recent.filter(value=>value!==id)].slice(0,8);localStorage.setItem(RECENT_KEY,JSON.stringify(recent));renderRecent()}
            function renderRecent(){const target=$('recentList');target.replaceChildren();if(!recent.length){target.textContent='Brak';return}for(const id of recent){const item=ITEM_BY_ID.get(id);const button=text('button',item.name);button.title=id;button.addEventListener('click',()=>{currentId=id;render()});target.append(button)}}
            function clearShardSelection(){selectedShards=null;selectedShardsItemId=null;$('confirmShardDecision').disabled=true;$('shardAnalysis').className='analysis muted';$('shardAnalysis').textContent='Wybierz wartość, aby zobaczyć analizę.'}
            function decide(status,shards){const item=current();if(!item)return;try{const approved=status==='APPROVED';const previous=decisions[item.id]||null;const next=validateDecision({status,recyclable:approved,shards,reviewed_by:$('reviewer').value.trim(),reviewed_at:new Date().toISOString(),note:$('note').value});appendHistory([historyEntry(item,previous,next)]);decisions[item.id]=next;remember(item.id);save();clearShardSelection();showMessage('Decyzja zapisana.');const decidedId=item.id;applyFilters();if(currentId===decidedId)moveNext()}catch(error){showMessage(error.message,true)}}
            function customDecision(){const raw=$('customShards').value.trim();const shards=Number(raw);if(!/^\\d+$/.test(raw)||!Number.isInteger(shards)||shards<1||shards>MAX_SHARDS_PER_ITEM){showMessage(`Podaj liczbę całkowitą od 1 do ${MAX_SHARDS_PER_ITEM}.`,true);return}stageShards(shards)}
            function focusShardPicker(){const picker=$('shardPicker');picker.classList.add('picker');picker.querySelector('[data-preview-shards="1"]').focus();showMessage(`Wybierz 1–5 shards albo wpisz własną wartość 1–${MAX_SHARDS_PER_ITEM}.`);setTimeout(()=>picker.classList.remove('picker'),1500)}
            function move(delta){if(!visibleItems.length)return;const index=visibleItems.findIndex(item=>item.id===currentId);currentId=visibleItems[(index+delta+visibleItems.length)%visibleItems.length].id;clearShardSelection();render()}
            function moveNext(){move(1)}
            function nextMatching(predicate){if(!QUEUE.length)return;let index=QUEUE.findIndex(item=>item.id===currentId);for(let step=1;step<=QUEUE.length;step++){const candidate=QUEUE[(index+step)%QUEUE.length];if(predicate(candidate)){currentId=candidate.id;render();return}}showMessage('Brak pasującego itemu.')}
            function progress(){const values={reviewed:0,pending:0,approved:0,rejected:0,mappedReviewed:0,mappedTotal:0,unmappedReviewed:0,unmappedTotal:0,highPending:0,mediumPending:0,lowPending:0};for(const item of QUEUE){const decision=decisions[item.id];const reviewed=Boolean(decision);if(item.mappingStatus==='MAPPED'){values.mappedTotal++;if(reviewed)values.mappedReviewed++}else{values.unmappedTotal++;if(reviewed)values.unmappedReviewed++}if(reviewed){values.reviewed++;values[decision.status==='APPROVED'?'approved':'rejected']++}else{values.pending++;values[item.priority.toLowerCase()+'Pending']++}}return values}
            function sessionState(){const p=progress(),pending=item=>effectiveStatus(item)==='PENDING',newPending=QUEUE.filter(item=>item.catalogStatus==='NEW'&&pending(item)).length,changedPending=QUEUE.filter(item=>item.catalogStatus==='CHANGED'&&pending(item)).length,highRisk=QUEUE.filter(item=>isOutlier(item)&&riskAnalysis(item,decisions[item.id].shards).level==='HIGH').length,invalidShards=Object.values(decisions).filter(value=>value.status==='APPROVED'&&(!Number.isInteger(value.shards)||value.shards<1||value.shards>MAX_SHARDS_PER_ITEM)).length,missingReviewer=Object.values(decisions).filter(value=>!value.reviewed_by.trim()).length,identityCount=QUEUE.reduce((sum,item)=>sum+item.identities.length,0);return {...p,newPending,changedPending,highRisk,invalidShards,missingReviewer,identityCount,catalogConsistent:identityCount===CATALOG_IDENTITIES}}
            function updateProgress(){const p=progress(),percent=QUEUE.length?Math.round(p.reviewed*100/QUEUE.length):0;$('reviewedCount').textContent=`${p.reviewed} / ${QUEUE.length}`;$('reviewProgressBar').style.width=percent+'%';$('reviewProgressPercent').textContent=percent+'%';$('pendingCount').textContent=p.pending;$('approvedCount').textContent=p.approved;$('rejectedCount').textContent=p.rejected;$('mappedProgress').textContent=`${p.mappedReviewed} / ${p.mappedTotal}`;$('unmappedProgress').textContent=`${p.unmappedReviewed} / ${p.unmappedTotal}`;$('highPending').textContent=p.highPending;$('mediumPending').textContent=p.mediumPending;$('lowPending').textContent=p.lowPending;$('localDecisionCount').textContent=Object.keys(decisions).length;const changed=localStorage.getItem(LAST_CHANGE_KEY);$('lastChanged').textContent=changed?new Date(changed).toLocaleString('pl-PL'):'brak';updateSessionInfo()}
            function updateSessionInfo(){const s=sessionState(),changed=localStorage.getItem(LAST_CHANGE_KEY);$('sessionItems').textContent=QUEUE.length;$('sessionReviewed').textContent=s.reviewed;$('sessionPending').textContent=s.pending;$('sessionApproved').textContent=s.approved;$('sessionRejected').textContent=s.rejected;$('sessionNew').textContent=s.newPending;$('sessionChanged').textContent=s.changedPending;$('sessionHighRisk').textContent=s.highRisk;$('sessionLastChanged').textContent=changed?new Date(changed).toLocaleString('pl-PL'):'brak'}
            function quoteYaml(value){return JSON.stringify(value)}
            function decisionYaml(){const ids=Object.keys(decisions).sort((a,b)=>a.localeCompare(b,'pl'));const lines=['# Exported by LainaReforge Recycling Review Panel','items:'];for(const id of ids){const d=validateDecision(decisions[id]);lines.push(`  ${quoteYaml(id)}:`,`    status: ${d.status}`,`    recyclable: ${d.recyclable}`,`    shards: ${d.shards}`,`    reviewed_by: ${quoteYaml(d.reviewed_by)}`,`    reviewed_at: ${quoteYaml(d.reviewed_at)}`,`    note: ${quoteYaml(d.note)}`)}return lines.join(String.fromCharCode(10))+String.fromCharCode(10)}
            function downloadDecisions(filename){const blob=new Blob([decisionYaml()],{type:'text/yaml;charset=utf-8'});const url=URL.createObjectURL(blob);const anchor=document.createElement('a');anchor.href=url;anchor.download=filename;anchor.click();URL.revokeObjectURL(url);showMessage(`Wyeksportowano ${Object.keys(decisions).length} decyzji.`)}
            function downloadText(filename,content,type='text/plain;charset=utf-8'){const blob=new Blob([content],{type}),url=URL.createObjectURL(blob),anchor=document.createElement('a');anchor.href=url;anchor.download=filename;anchor.click();URL.revokeObjectURL(url)}
            function backupSession(){downloadDecisions('recycling-decisions-backup.yml');setTimeout(()=>{exportHistory();showMessage('Uruchomiono eksport decyzji i historii. Jeśli przeglądarka pobrała tylko jeden plik, użyj osobno EXPORT HISTORY w zakładce Historia.')},250)}
            function sessionReport(){const s=sessionState(),reviewer=$('reviewer').value.trim()||'brak';return ['LainaReforge Review Session','',`Catalog identities: ${CATALOG_IDENTITIES}`,`Logical items: ${QUEUE.length}`,'',`Reviewed: ${s.reviewed}`,`Pending: ${s.pending}`,`Approved: ${s.approved}`,`Rejected: ${s.rejected}`,'',`NEW pending: ${s.newPending}`,`CHANGED pending: ${s.changedPending}`,'',`Economy HIGH RISK: ${s.highRisk}`,`Economy outliers: ${QUEUE.filter(isOutlier).length}`,'',`Reviewer: ${reviewer}`,`Generated at: ${new Date().toISOString()}`,'',`Decisions file format version: 1`,`Catalog snapshot/fingerprint info: ${CATALOG_IDENTITIES} identities; queue SHA-256 ${QUEUE_FINGERPRINT}`].join(String.fromCharCode(10))+String.fromCharCode(10)}
            function exportSessionReport(){downloadText('review-session-report.txt',sessionReport());showMessage('Wyeksportowano raport sesji.')}
            function showReadiness(){const s=sessionState(),groups={BLOCKING:[],WARNING:[],INFO:[]};if(s.pending)groups.BLOCKING.push(`${s.pending} itemów bez decyzji`);if(s.newPending)groups.BLOCKING.push(`${s.newPending} NEW itemów bez decyzji`);if(s.changedPending)groups.BLOCKING.push(`${s.changedPending} CHANGED itemów bez decyzji`);if(s.invalidShards)groups.BLOCKING.push(`${s.invalidShards} niepoprawnych wartości shards`);if(!s.catalogConsistent)groups.BLOCKING.push('Niezgodna liczba identities katalogu');if(s.missingReviewer)groups.WARNING.push(`${s.missingReviewer} decyzji bez reviewera`);if(s.highRisk)groups.WARNING.push(`${s.highRisk} zatwierdzonych itemów ma HIGH ECONOMY RISK`);if(s.catalogConsistent)groups.INFO.push(`Catalog consistency: ${s.identityCount} / ${CATALOG_IDENTITIES}`);groups.INFO.push(`${s.reviewed} / ${QUEUE.length} reviewed`);const target=$('readinessResults');target.replaceChildren();for(const [level,entries] of Object.entries(groups)){target.append(text('h3',level,'risk-'+(level==='BLOCKING'?'HIGH':level==='WARNING'?'MEDIUM':'LOW')));const list=text('ul');for(const entry of entries.length?entries:['brak'])list.append(text('li',entry));target.append(list)}$('readinessDialog').showModal()}
            function openExportSummary(){const p=progress();$('exportSummary').innerHTML=`Reviewed: <b>${p.reviewed} / ${QUEUE.length}</b><br>Approved: <b>${p.approved}</b><br>Rejected: <b>${p.rejected}</b><br>Pending: <b>${p.pending}</b><br>MAPPED pending: <b>${p.mappedTotal-p.mappedReviewed}</b><br>UNMAPPED pending: <b>${p.unmappedTotal-p.unmappedReviewed}</b><br>HIGH pending: <b>${p.highPending}</b>`;$('exportWarning').textContent=p.pending>0?'Ten eksport nie obejmuje wszystkich itemów. Brak decyzji pozostanie NOT_CONFIGURED.':'Wszystkie itemy mają decyzję.';$('exportDialog').showModal()}
            function unquoteYaml(value){let parsed;try{parsed=JSON.parse(value)}catch(error){throw new Error('Oczekiwano poprawnego tekstu w cudzysłowie')}if(typeof parsed!=='string')throw new Error('Oczekiwano tekstu w cudzysłowie');return parsed}
            function isItemLine(line){return line.startsWith('  "')&&line.endsWith('":')}
            function parseImport(yaml){const newline=String.fromCharCode(10);const carriageReturn=String.fromCharCode(13);const lines=yaml.split(newline).map(line=>line.endsWith(carriageReturn)?line.slice(0,-1):line);let index=0;while(index<lines.length&&(lines[index].trim()===''||lines[index].trim().startsWith('#')))index++;if(lines[index++]!=='items:')throw new Error('Plik musi zaczynać się od items:');const imported={};while(index<lines.length){while(index<lines.length&&(lines[index].trim()===''||lines[index].trim().startsWith('#')))index++;if(index>=lines.length)break;const itemLine=lines[index++];if(!isItemLine(itemLine))throw new Error(`Niepoprawny wpis w linii ${index}`);const id=unquoteYaml(itemLine.slice(2,-1));if(!ITEM_BY_ID.has(id))throw new Error(`Nieznany item: ${id}`);if(imported[id])throw new Error(`Duplikat itemu: ${id}`);const fields={};while(index<lines.length&&!isItemLine(lines[index])){const line=lines[index++];if(line.trim()===''||line.trim().startsWith('#'))continue;if(!line.startsWith('    '))throw new Error(`Niepoprawne pole w linii ${index}`);const separator=line.indexOf(': ',4);if(separator<5)throw new Error(`Niepoprawne pole w linii ${index}`);const name=line.slice(4,separator);if(!Array.from(name).every(character=>(character>='a'&&character<='z')||character==='_'))throw new Error(`Niepoprawna nazwa pola w linii ${index}`);if(name in fields)throw new Error(`Duplikat pola ${name}`);fields[name]=line.slice(separator+2)}const required=['status','recyclable','shards','reviewed_by','reviewed_at','note'];if(Object.keys(fields).sort().join(',')!==required.slice().sort().join(','))throw new Error(`Niepoprawny zestaw pól dla ${id}`);const recyclable=fields.recyclable==='true'?true:fields.recyclable==='false'?false:null;const numericShards=Number(fields.shards);const shards=Number.isInteger(numericShards)&&numericShards>=0&&String(numericShards)===fields.shards?numericShards:null;imported[id]=validateDecision({status:fields.status,recyclable,shards,reviewed_by:unquoteYaml(fields.reviewed_by),reviewed_at:unquoteYaml(fields.reviewed_at),note:unquoteYaml(fields.note)})}return imported}
            function sameDecision(left,right){return left&&right&&left.status===right.status&&left.recyclable===right.recyclable&&left.shards===right.shards&&left.reviewed_by===right.reviewed_by&&left.reviewed_at===right.reviewed_at&&left.note===right.note}
            async function importFile(file){try{const imported=parseImport(await file.text());const overlap=Object.keys(imported).filter(id=>decisions[id]);if(overlap.length&&!confirm(`Import nadpisze ${overlap.length} lokalnych decyzji. Kontynuować?`))return;const entries=[];const importedAt=new Date().toISOString();for(const [id,next] of Object.entries(imported)){const previous=decisions[id]||null;if(!sameDecision(previous,next))entries.push(historyEntry(ITEM_BY_ID.get(id),previous,next,true,importedAt))}appendHistory(entries);decisions={...decisions,...imported};save();applyFilters();showMessage(`Zaimportowano ${Object.keys(imported).length} decyzji.`)}catch(error){showMessage(`Import odrzucony: ${error.message}`,true)}finally{$('importFile').value=''}}
            function resetLocal(){const count=Object.keys(decisions).length;if(count>0&&!confirm(`Reset usunie ${count} lokalnych decyzji. Kontynuować?`))return;if(count>0&&!confirm('Potwierdź ponownie: usunąć wszystkie lokalne decyzje?'))return;decisions={};localStorage.removeItem(STORAGE_KEY);localStorage.removeItem(LAST_CHANGE_KEY);applyFilters();showMessage('Lokalne decyzje zostały usunięte.')}
            function economyRow(item,label){const row=text('button','','economy-row');row.append(text('span',item.name),text('strong',label));row.addEventListener('click',()=>{currentId=item.id;selectedShards=null;showView('review');render()});return row}
            function renderEconomyOverview(){const approved=QUEUE.map(item=>({item,decision:decisions[item.id]})).filter(value=>value.decision?.status==='APPROVED');const rejected=Object.values(decisions).filter(value=>value.status==='REJECTED').length,pending=QUEUE.length-approved.length-rejected,stats=shardStatistics(approved.map(value=>value.decision.shards));const riskItems=approved.map(value=>({...value,risk:riskAnalysis(value.item,value.decision.shards)}));const highRisk=riskItems.filter(value=>value.risk.level==='HIGH');const metrics=$('economyMetrics');metrics.replaceChildren();for(const [label,value] of [['Approved',approved.length],['Rejected',rejected],['Pending',pending],['Mediana shards',stats.sufficient?formatNumber(stats.median):'za mało danych'],['Średnia',stats.sufficient?formatNumber(stats.average):'za mało danych'],['HIGH RISK',highRisk.length]]){const node=text('div','','metric');node.append(text('span',label),text('b',String(value)));metrics.append(node)}const buckets=[['1 shard',v=>v===1],['2 shards',v=>v===2],['3 shards',v=>v===3],['4 shards',v=>v===4],['5 shards',v=>v===5],['6–10',v=>v>=6&&v<=10],['11–25',v=>v>=11&&v<=25],['26–50',v=>v>=26&&v<=50],['51+',v=>v>=51]];const distribution=$('economyDistribution');distribution.replaceChildren();for(const [label,predicate] of buckets){const row=text('div','','economy-row');row.append(text('span',label),text('strong',String(approved.filter(value=>predicate(value.decision.shards)).length)));distribution.append(row)}const extremes=$('economyExtremes');extremes.replaceChildren(text('h3','Najwyższe'));for(const value of [...approved].sort((a,b)=>b.decision.shards-a.decision.shards||a.item.id.localeCompare(b.item.id,'pl')).slice(0,5))extremes.append(economyRow(value.item,String(value.decision.shards)));extremes.append(text('h3','Najniższe'));for(const value of [...approved].sort((a,b)=>a.decision.shards-b.decision.shards||a.item.id.localeCompare(b.item.id,'pl')).slice(0,5))extremes.append(economyRow(value.item,String(value.decision.shards)));const outliers=$('economyOutliers');outliers.replaceChildren();const sorted=riskItems.filter(value=>value.risk.flags.length).sort((a,b)=>(b.risk.ratio??0)-(a.risk.ratio??0)||a.item.id.localeCompare(b.item.id,'pl')).slice(0,10);if(!sorted.length)outliers.append(text('div','Brak wykrytych outlierów.','muted'));for(const value of sorted)outliers.append(economyRow(value.item,`${value.decision.shards} · ${value.risk.flags.join(', ')}`));renderWhatIf()}
            function renderWhatIf(){const count=Number($('whatIfCount').value),shards=Number($('whatIfShards').value),target=$('whatIfResult');if(!Number.isInteger(count)||count<0||!Number.isInteger(shards)||shards<0){target.textContent='Wpisz nieujemne liczby całkowite.';target.className='analysis HIGH';return}const total=count*shards,warnings=[];if(shards>MAX_SHARDS_PER_ITEM)warnings.push(`shards/item przekracza limit ${MAX_SHARDS_PER_ITEM}`);if(total>MAX_SHARDS_PER_TRANSACTION)warnings.push(`suma przekracza limit transakcji ${MAX_SHARDS_PER_TRANSACTION}`);target.textContent=`${count} × ${shards} = ${total} shards${warnings.length?' · OSTRZEŻENIE: '+warnings.join('; '):''}`;target.className='analysis '+(warnings.length?'HIGH':'LOW')}
            function showView(name){const historyMode=name==='history',economyMode=name==='economy';$('reviewView').hidden=historyMode||economyMode;$('historyView').hidden=!historyMode;$('economyView').hidden=!economyMode;$('reviewTab').classList.toggle('active',!historyMode&&!economyMode);$('historyTab').classList.toggle('active',historyMode);$('economyTab').classList.toggle('active',economyMode);$('position').hidden=historyMode||economyMode;if(historyMode)renderHistory();if(economyMode)renderEconomyOverview()}
            function renderHistory(){const reviewer=$('historyReviewer').value.trim().toLocaleLowerCase('pl');const action=$('historyAction').value;const status=$('historyStatus').value;const query=$('historySearch').value.trim().toLocaleLowerCase('pl');const entries=[...history].reverse().filter(entry=>(!reviewer||entry.reviewed_by.toLocaleLowerCase('pl').includes(reviewer))&&(action==='ALL'||entry.action===action)&&(status==='ALL'||entry.new_status===status)&&(!query||[entry.display_name,entry.logical_item_id,...entry.identities].join(' ').toLocaleLowerCase('pl').includes(query)));const list=$('historyList');list.replaceChildren();if(!entries.length){list.append(text('div','Brak wpisów historii dla wybranych filtrów.','empty card'));return}for(const entry of entries){const card=text('article','','history-entry');const head=text('div','','history-head');const title=document.createElement('div');title.append(text('strong',entry.display_name),text('div',entry.logical_item_id+' · '+entry.identities.join(', '),'muted'));head.append(title,text('span',entry.action,'badge '+entry.action));card.append(head,text('div',`${entry.previous_status??'PENDING'} → ${entry.new_status} · shards ${entry.previous_shards??'—'} → ${entry.new_shards}`,'history-diff'),text('div',`${new Date(entry.timestamp).toLocaleString('pl-PL')} · ${entry.reviewed_by||'bez reviewera'}`,'muted'));if(entry.previous_note!==entry.new_note)card.append(text('div',`Notatka: ${entry.previous_note??'—'} → ${entry.new_note||'—'}`,'history-note'));list.append(card)}}
            function renderItemHistoryPreview(){const target=$('itemHistoryPreview'),item=current();if(!target||!item)return;const entries=history.filter(entry=>entry.logical_item_id===item.id).slice(-3).reverse();target.replaceChildren();if(!entries.length){target.append(text('div','Brak wcześniejszych zmian.','muted'));return}for(const entry of entries){const row=text('div','','item-history-row');row.append(text('span',entry.action,'badge '+entry.action),text('span',`${entry.previous_status??'PENDING'} → ${entry.new_status} · ${entry.previous_shards??'—'} → ${entry.new_shards}`),text('time',new Date(entry.timestamp).toLocaleDateString('pl-PL'),'muted'));target.append(row)}}
            function showCurrentItemHistory(){const item=current();if(!item)return;$('historySearch').value=item.id;showView('history')}
            function historyYaml(){const lines=['# Exported by LainaReforge Recycling Review Panel','history:'];for(const entry of history){lines.push('  - timestamp: '+quoteYaml(entry.timestamp),'    reviewed_by: '+quoteYaml(entry.reviewed_by),'    logical_item_id: '+quoteYaml(entry.logical_item_id),'    display_name: '+quoteYaml(entry.display_name),'    identities:');for(const identity of entry.identities)lines.push('      - '+quoteYaml(identity));lines.push('    action: '+entry.action,'    previous_status: '+(entry.previous_status??'null'),'    new_status: '+entry.new_status,'    previous_shards: '+(entry.previous_shards??'null'),'    new_shards: '+entry.new_shards,'    previous_note: '+(entry.previous_note===null?'null':quoteYaml(entry.previous_note)),'    new_note: '+quoteYaml(entry.new_note))}return lines.join(String.fromCharCode(10))+String.fromCharCode(10)}
            function exportHistory(){const blob=new Blob([historyYaml()],{type:'text/yaml;charset=utf-8'});const url=URL.createObjectURL(blob);const anchor=document.createElement('a');anchor.href=url;anchor.download='recycling-decision-history.yml';anchor.click();URL.revokeObjectURL(url);$('historyMessage').textContent=`Wyeksportowano ${history.length} wpisów historii.`}
            function resetHistory(){const count=history.length;if(!count){$('historyMessage').textContent='Historia jest pusta.';return}if(!confirm(`Reset historii usunie ${count} wpisów. Kontynuować?`))return;if(!confirm('Potwierdź ponownie: trwale usunąć historię decyzji?'))return;history=[];localStorage.removeItem(HISTORY_STORAGE_KEY);$('historyCount').textContent='0';renderHistory();$('historyMessage').textContent='Historia decyzji została usunięta. Snapshot decyzji pozostał bez zmian.'}
            function jump(){const query=$('jumpInput').value.trim().toLocaleLowerCase('pl');const item=QUEUE.find(candidate=>searchable(candidate).split(' ').includes(query))||QUEUE.find(candidate=>searchable(candidate).includes(query));if(!item){showMessage('Nie znaleziono itemu.',true);return}currentId=item.id;clearShardSelection();render()}
            function showMessage(value,error=false){$('message').textContent=value;$('message').style.color=error?'var(--red)':'var(--muted)'}
            for(const id of ['statusFilter','catalogFilter','mappingFilter','priorityFilter','proposalFilter','tagFilter'])$(id).addEventListener('change',()=>{activeQueue='CUSTOM'},{capture:true});$('search').addEventListener('input',()=>{activeQueue='CUSTOM'},{capture:true});
            document.querySelectorAll('[data-preview-shards]').forEach(button=>button.addEventListener('click',()=>stageShards(Number(button.dataset.previewShards))));
            $('customShards').addEventListener('input',event=>{const raw=event.target.value.trim();if(/^\\d+$/.test(raw)){const value=Number(raw);if(Number.isInteger(value)&&value>=1&&value<=MAX_SHARDS_PER_ITEM)stageShards(value)}});
            $('confirmShardDecision').addEventListener('click',()=>{if(selectedShards!==null&&selectedShardsItemId===current()?.id)decide('APPROVED',selectedShards)});
            $('economyTab').addEventListener('click',()=>showView('economy'));
            for(const id of ['whatIfCount','whatIfShards'])$(id).addEventListener('input',renderWhatIf);
            $('sessionBackupButton').addEventListener('click',backupSession);
            $('sessionReportButton').addEventListener('click',exportSessionReport);
            $('readinessButton').addEventListener('click',showReadiness);
            $('closeReadiness').addEventListener('click',()=>$('readinessDialog').close());
            document.addEventListener('keydown',event=>{if($('economyView').hidden===false)event.stopImmediatePropagation()},{capture:true});
            for(const [id,key] of [['statusFilter','status'],['catalogFilter','catalog'],['mappingFilter','mapping'],['priorityFilter','priority'],['proposalFilter','proposal'],['tagFilter','tag']])$(id).addEventListener('change',event=>{filters[key]=event.target.value;document.querySelectorAll('[data-queue]').forEach(button=>button.classList.remove('active'));applyFilters()});$('search').addEventListener('input',event=>{filters.search=event.target.value;document.querySelectorAll('[data-queue]').forEach(button=>button.classList.remove('active'));applyFilters()});document.querySelectorAll('[data-queue]').forEach(button=>button.addEventListener('click',()=>applyQueue(button.dataset.queue)));document.querySelectorAll('[data-shards]').forEach(button=>button.addEventListener('click',()=>decide('APPROVED',Number(button.dataset.shards))));document.querySelector('[data-action="reject"]').addEventListener('click',()=>decide('REJECTED',0));document.querySelector('[data-action="skip"]').addEventListener('click',moveNext);$('customApprove').addEventListener('click',customDecision);$('previous').addEventListener('click',()=>move(-1));$('next').addEventListener('click',moveNext);$('nextPending').addEventListener('click',()=>nextMatching(item=>effectiveStatus(item)==='PENDING'));$('nextHigh').addEventListener('click',()=>nextMatching(item=>item.priority==='HIGH'&&effectiveStatus(item)==='PENDING'));$('jumpButton').addEventListener('click',jump);$('jumpInput').addEventListener('keydown',event=>{if(event.key==='Enter')jump()});$('showItemHistory').addEventListener('click',showCurrentItemHistory);$('reviewTab').addEventListener('click',()=>showView('review'));$('historyTab').addEventListener('click',()=>showView('history'));for(const id of ['historyReviewer','historySearch'])$(id).addEventListener('input',renderHistory);for(const id of ['historyAction','historyStatus'])$(id).addEventListener('change',renderHistory);$('exportHistory').addEventListener('click',exportHistory);$('resetHistory').addEventListener('click',resetHistory);$('exportButton').addEventListener('click',openExportSummary);$('confirmExport').addEventListener('click',()=>{$('exportDialog').close();downloadDecisions('recycling-decisions.yml')});$('cancelExport').addEventListener('click',()=>$('exportDialog').close());$('backupButton').addEventListener('click',()=>downloadDecisions('recycling-decisions-backup.yml'));$('importButton').addEventListener('click',()=>$('importFile').click());$('importFile').addEventListener('change',event=>event.target.files[0]&&importFile(event.target.files[0]));$('resetButton').addEventListener('click',resetLocal);$('reviewer').value=sessionStorage.getItem(REVIEWER_KEY)||'';$('reviewer').addEventListener('input',event=>sessionStorage.setItem(REVIEWER_KEY,event.target.value));document.addEventListener('keydown',event=>{if($('historyView').hidden===false||event.ctrlKey||event.metaKey||event.altKey||['INPUT','TEXTAREA','SELECT','BUTTON'].includes(document.activeElement?.tagName))return;if(event.key==='a'||event.key==='A'){event.preventDefault();focusShardPicker()}else if(event.key==='r'||event.key==='R'){event.preventDefault();decide('REJECTED',0)}else if(event.key==='s'||event.key==='S'){event.preventDefault();moveNext()}else if(event.key==='ArrowLeft'){event.preventDefault();move(-1)}else if(event.key==='ArrowRight'){event.preventDefault();moveNext()}});for(const item of QUEUE){const option=document.createElement('option');option.value=item.id;option.label=item.name;$('itemList').append(option);for(const identity of item.identities){const identityOption=document.createElement('option');identityOption.value=`${identity.material}:${identity.cmd}`;identityOption.label=item.name;$('itemList').append(identityOption)}}
            new MutationObserver(()=>{updateEconomyAssistant();renderItemHistoryPreview()}).observe($('itemCard'),{childList:true});
            reconcileCatalogChanges();$('historyCount').textContent=history.length;renderRecent();render();
            </script>
            </body>
            </html>
            """);
}

package pl.laina.reforge.catalog;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.Decision;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.DecisionQueue;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.DecisionStatus;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.Identity;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.MappingStatus;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.Priority;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.QueueItem;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.SystemProposal;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.SystemProposalValue;

/** Generates a self-contained, local-only review panel from the ETAP 4 queue. */
public final class RecyclingReviewPanelGenerator {
    public static final Path DEFAULT_INPUT = Path.of("generated/recycling-decision-queue.yml");
    public static final Path DEFAULT_OUTPUT = Path.of("generated/recycling-review-panel/index.html");
    public static final Path DEFAULT_REPORT = Path.of("generated/recycling-review-panel-report.txt");
    public static final String LOCAL_STORAGE_KEY = "laina-reforge.recycling-decisions.v1";

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
                    new Acquisition(summary, tags), evidence,
                    new SystemProposal(proposal, proposalReason), decision));
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
                && (!Boolean.TRUE.equals(recyclable) || shards == null || shards <= 0)) {
            throw new IllegalArgumentException("APPROVED requires recyclable=true and positive shards");
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
        return HTML_TEMPLATE
                .replace("__QUEUE_DATA__", data)
                .replace("__STORAGE_KEY__", jsonString(LOCAL_STORAGE_KEY))
                .replace("__CATALOG_IDENTITIES__", Integer.toString(queue.identityCount()))
                .replace("__MAPPED_IDENTITIES__", Integer.toString(queue.mappedIdentityCount()))
                .replace("__UNMAPPED_IDENTITIES__", Integer.toString(queue.unmappedIdentityCount()));
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

    private static List<String> selfCheck(DecisionQueue queue, String html) {
        List<String> errors = new ArrayList<>();
        if (queue.items().isEmpty()) {
            errors.add("SELF_CHECK: queue is empty");
        }
        if (!html.contains("const QUEUE = [") || !html.contains(LOCAL_STORAGE_KEY)) {
            errors.add("SELF_CHECK: embedded data or storage key is missing");
        }
        for (QueueItem item : queue.items()) {
            if (!html.contains(jsonString(item.logicalId()))) {
                errors.add("SELF_CHECK: missing logical item " + item.logicalId());
            }
        }
        return List.copyOf(errors);
    }

    private static String renderReport(
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

    private record ItemMatch(int start, int end, String key) {
    }

    private static final String HTML_TEMPLATE = """
            <!doctype html>
            <html lang="pl">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>LainaReforge — Recycling Review</title>
              <style>
                :root{color-scheme:dark;--bg:#0d1117;--panel:#161b22;--panel2:#1c2430;--line:#30363d;--text:#e6edf3;--muted:#8b949e;--gold:#d6a94f;--red:#da5757;--green:#3fb950;--blue:#58a6ff}
                *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:14px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif}
                button,input,select,textarea{font:inherit}button,select,input,textarea{border:1px solid var(--line);background:#0d1117;color:var(--text);border-radius:7px}
                button{cursor:pointer;padding:8px 12px}button:hover{border-color:var(--blue)}button:focus-visible,input:focus-visible,select:focus-visible,textarea:focus-visible{outline:2px solid var(--blue);outline-offset:1px}
                .app{max-width:1450px;margin:auto;padding:16px}.top{display:flex;gap:12px;align-items:center;justify-content:space-between;margin-bottom:12px}.top h1{font-size:20px;margin:0}.muted{color:var(--muted)}
                .progress{display:grid;grid-template-columns:repeat(4,minmax(120px,1fr));gap:8px;margin:12px 0}.metric,.toolbar,.card{background:var(--panel);border:1px solid var(--line);border-radius:10px}.metric{padding:10px 12px}.metric b{display:block;font-size:19px}
                .toolbar{padding:10px;display:flex;flex-wrap:wrap;gap:8px;margin-bottom:12px}.toolbar label{display:flex;align-items:center;gap:6px}.toolbar input,.toolbar select{padding:7px 9px;min-width:130px}.toolbar .search{flex:1;min-width:240px}
                .layout{display:grid;grid-template-columns:minmax(0,1fr) 330px;gap:12px}.card{padding:16px}.item-head{display:flex;gap:10px;justify-content:space-between;align-items:flex-start;border-bottom:1px solid var(--line);padding-bottom:12px}.item-head h2{margin:0 0 4px;font-size:24px}.badges{display:flex;flex-wrap:wrap;gap:6px}.badge{border:1px solid var(--line);border-radius:999px;padding:3px 8px;font-size:12px}.HIGH{color:#ff7b72;border-color:#7d3535}.MEDIUM{color:#e3b341;border-color:#705c22}.LOW{color:#8b949e}.APPROVED{color:var(--green)}.REJECTED{color:var(--red)}.UNMAPPED{color:#ffb86c;border-color:#855d2b}
                h3{font-size:13px;text-transform:uppercase;letter-spacing:.08em;color:var(--muted);margin:18px 0 7px}.identity{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;background:var(--panel2);padding:7px 9px;border-radius:6px;margin:5px 0;overflow-wrap:anywhere}.evidence{margin:0;padding-left:20px}.proposal{border-left:3px solid var(--gold);padding:8px 12px;background:var(--panel2)}
                .decision label{display:block;margin:10px 0 5px}.decision input,.decision textarea{width:100%;padding:8px}.decision textarea{min-height:86px;resize:vertical}.actions{display:grid;grid-template-columns:repeat(3,1fr);gap:7px;margin-top:12px}.actions .reject{border-color:#7d3535}.actions .approve{border-color:#2f6f3e}.actions .skip{grid-column:span 3}.custom{display:flex;gap:7px;margin-top:8px}.custom input{min-width:0}.custom button{white-space:nowrap}
                .nav{display:grid;grid-template-columns:1fr 1fr;gap:7px;margin-top:12px}.nav button:nth-child(3),.nav button:nth-child(4){grid-column:span 2}.jump{display:flex;gap:7px;margin-top:8px}.jump input{width:100%;padding:8px}.io{display:flex;flex-wrap:wrap;gap:7px}.danger{border-color:#7d3535;color:#ff7b72}.message{min-height:21px;margin-top:8px;color:var(--muted)}a{color:var(--blue)}.empty{text-align:center;padding:60px 20px;color:var(--muted)}
                @media(max-width:900px){.layout{grid-template-columns:1fr}.progress{grid-template-columns:1fr 1fr}.top{align-items:flex-start;flex-direction:column}}
              </style>
            </head>
            <body>
            <main class="app">
              <div class="top"><div><h1>LainaReforge — przegląd decyzji</h1><div class="muted">Lokalny panel. Dane pozostają w tej przeglądarce do czasu eksportu.</div></div><div id="position" class="muted"></div></div>
              <section class="progress"><div class="metric"><span>Reviewed</span><b id="reviewedCount">0 / 0</b></div><div class="metric"><span>Pending</span><b id="pendingCount">0</b></div><div class="metric"><span>Approved</span><b id="approvedCount">0</b></div><div class="metric"><span>Rejected</span><b id="rejectedCount">0</b></div></section>
              <section class="progress"><div class="metric"><span>Catalog identities</span><b>__CATALOG_IDENTITIES__</b></div><div class="metric"><span>Mapped identities</span><b>__MAPPED_IDENTITIES__</b></div><div class="metric"><span>Unmapped identities</span><b>__UNMAPPED_IDENTITIES__</b></div><div class="metric"><span>Coverage</span><b>__CATALOG_IDENTITIES__ / __CATALOG_IDENTITIES__</b></div></section>
              <section class="toolbar" aria-label="Filtry">
                <label>Status <select id="statusFilter"><option>ALL</option><option>PENDING</option><option>APPROVED</option><option>REJECTED</option></select></label>
                <label>Mapping <select id="mappingFilter"><option>ALL</option><option>MAPPED</option><option>UNMAPPED</option></select></label>
                <label>Priority <select id="priorityFilter"><option>ALL</option><option>HIGH</option><option>MEDIUM</option><option>LOW</option></select></label>
                <label>Proposal <select id="proposalFilter"><option>ALL</option><option>NO</option><option>UNKNOWN</option></select></label>
                <label>Tag <select id="tagFilter"><option>ALL</option><option>INFINITE_OR_FARMABLE</option><option>REPEATABLE</option><option>LIMITED</option><option>KEY_REWARD</option><option>EVENT</option><option>QUEST</option><option>SHOP</option><option>CRAFT</option><option>DROP</option><option>UNKNOWN</option></select></label>
                <input id="search" class="search" type="search" placeholder="Szukaj: name, wiki, material, CMD, model_path">
              </section>
              <div class="layout"><section id="itemCard" class="card"></section><aside class="card decision">
                <h3>Reviewer</h3><label for="reviewer">reviewed_by (dla tej sesji)</label><input id="reviewer" autocomplete="name" placeholder="np. e2ot3rror">
                <label for="note">Notatka do bieżącej decyzji</label><textarea id="note" placeholder="Opcjonalna notatka"></textarea>
                <div class="actions"><button class="reject" data-action="reject">ODRZUĆ</button><button class="approve" data-shards="1">1 SHARD</button><button class="approve" data-shards="2">2 SHARDS</button><button class="approve" data-shards="3">3 SHARDS</button><button class="approve" data-shards="4">4 SHARDS</button><button class="approve" data-shards="5">5 SHARDS</button><button class="skip" data-action="skip">POMIŃ</button></div>
                <div class="custom"><input id="customShards" type="number" min="1" step="1" placeholder="Custom shards"><button id="customApprove">ZATWIERDŹ</button></div>
                <h3>Nawigacja</h3><div class="nav"><button id="previous">Poprzedni</button><button id="next">Następny</button><button id="nextPending">Następny PENDING</button><button id="nextHigh">Następny HIGH</button></div>
                <div class="jump"><input id="jumpInput" list="itemList" placeholder="Nazwa lub wiki"><datalist id="itemList"></datalist><button id="jumpButton">Idź</button></div>
                <h3>Dane lokalne</h3><div class="io"><button id="exportButton">EXPORT DECISIONS</button><button id="importButton">IMPORT DECISIONS</button><button id="resetButton" class="danger">RESET LOCAL DECISIONS</button><input id="importFile" type="file" accept=".yml,.yaml,text/yaml" hidden></div>
                <div id="message" class="message" role="status"></div>
              </aside></div>
            </main>
            <script>
            'use strict';
            const QUEUE = __QUEUE_DATA__;
            const STORAGE_KEY = __STORAGE_KEY__;
            const REVIEWER_KEY = STORAGE_KEY + '.reviewer';
            const ITEM_BY_ID = new Map(QUEUE.map(item => [item.id, item]));
            const filters = {status:'ALL',mapping:'ALL',priority:'ALL',proposal:'ALL',tag:'ALL',search:''};
            let decisions = loadDecisions();
            let visibleItems = QUEUE.slice();
            let currentId = visibleItems[0]?.id || null;
            const $ = id => document.getElementById(id);

            function loadDecisions(){try{const parsed=JSON.parse(localStorage.getItem(STORAGE_KEY)||'{}');return validateStoredObject(parsed)}catch(error){console.warn(error);return {}}}
            function validateStoredObject(value){if(!value||Array.isArray(value)||typeof value!=='object')return {};const clean={};for(const [id,decision] of Object.entries(value)){if(!ITEM_BY_ID.has(id))continue;try{clean[id]=validateDecision(decision)}catch(error){console.warn('Pomijam błędną decyzję',id,error)}}return clean}
            function validateDecision(value){if(!value||typeof value!=='object')throw new Error('Decyzja musi być obiektem');const {status,recyclable,shards,reviewed_by,reviewed_at,note}=value;if(status==='APPROVED'){if(recyclable!==true||!Number.isInteger(shards)||shards<=0)throw new Error('APPROVED wymaga dodatniej liczby shards')}else if(status==='REJECTED'){if(recyclable!==false||shards!==0)throw new Error('REJECTED wymaga shards=0')}else{throw new Error('Dozwolone są tylko APPROVED i REJECTED')}if(typeof reviewed_by!=='string'||typeof reviewed_at!=='string'||!reviewed_at||typeof note!=='string')throw new Error('Niepoprawne metadane decyzji');return {status,recyclable,shards,reviewed_by,reviewed_at,note}}
            function save(){localStorage.setItem(STORAGE_KEY,JSON.stringify(decisions));updateProgress()}
            function effectiveStatus(item){return decisions[item.id]?.status||item.initialStatus}
            function searchable(item){return [item.name,item.wiki,...item.identities.flatMap(id=>[id.material,String(id.cmd),id.modelPath])].join(' ').toLocaleLowerCase('pl')}
            function applyFilters(){const query=filters.search.trim().toLocaleLowerCase('pl');visibleItems=QUEUE.filter(item=>(filters.status==='ALL'||effectiveStatus(item)===filters.status)&&(filters.mapping==='ALL'||item.mappingStatus===filters.mapping)&&(filters.priority==='ALL'||item.priority===filters.priority)&&(filters.proposal==='ALL'||item.proposal.value===filters.proposal)&&(filters.tag==='ALL'||item.acquisition.tags.includes(filters.tag))&&(!query||searchable(item).includes(query)));if(!visibleItems.some(item=>item.id===currentId))currentId=visibleItems[0]?.id||null;render()}
            function current(){return ITEM_BY_ID.get(currentId)}
            function text(tag,value,className=''){const node=document.createElement(tag);node.textContent=value;if(className)node.className=className;return node}
            function render(){const card=$('itemCard');card.replaceChildren();const item=current();if(!item){card.append(text('div','Brak itemów dla wybranych filtrów.','empty'));$('position').textContent='0 / 0';$('note').value='';updateProgress();return}const head=text('div','', 'item-head');const title=document.createElement('div');title.append(text('h2',item.name),text('div',item.wiki||item.id,'muted'));const badges=text('div','', 'badges');badges.append(text('span',item.priority,'badge '+item.priority),text('span',effectiveStatus(item),'badge '+effectiveStatus(item)),text('span',item.mappingStatus==='UNMAPPED'?'BRAK WIKI':'MAPPED','badge '+item.mappingStatus));head.append(title,badges);card.append(head);card.append(text('h3','Wiki'));if(item.mappingStatus==='MAPPED'){const wiki=document.createElement('a');wiki.href='https://wiki.laina.pl/index.php?title='+encodeURIComponent(item.wiki);wiki.target='_blank';wiki.rel='noopener noreferrer';wiki.textContent='Otwórz stronę Wiki ↗';card.append(wiki)}else{card.append(text('div','Brak pewnego mapowania do Wiki.','muted'))}card.append(text('h3','Identities'));for(const identity of item.identities)card.append(text('div',`${identity.material} · CMD ${identity.cmd} · ${identity.modelPath}`,'identity'));card.append(text('h3','Zdobycie'),text('p',item.acquisition.summary));const tags=text('div','','badges');for(const tag of item.acquisition.tags)tags.append(text('span',tag,'badge'));card.append(tags,text('h3','Evidence'));const evidence=text('ul','','evidence');for(const entry of item.evidence)evidence.append(text('li',entry));card.append(evidence,text('h3','System proposal'));const proposal=text('div','', 'proposal');proposal.append(text('strong',item.proposal.value),text('div',item.proposal.reason));card.append(proposal,text('h3','Powód przeglądu'),text('p',item.reviewReason));const existing=decisions[item.id];$('note').value=existing?.note||'';$('position').textContent=`${visibleItems.findIndex(x=>x.id===item.id)+1} / ${visibleItems.length}`;updateProgress()}
            function decide(status,shards){const item=current();if(!item)return;const approved=status==='APPROVED';const decidedId=item.id;decisions[item.id]=validateDecision({status,recyclable:approved,shards,reviewed_by:$('reviewer').value.trim(),reviewed_at:new Date().toISOString(),note:$('note').value});save();applyFilters();if(currentId===decidedId)moveNext()}
            function customDecision(){const raw=$('customShards').value.trim();const shards=Number(raw);if(!/^\\d+$/.test(raw)||!Number.isInteger(shards)||shards<=0){showMessage('Custom shards musi być dodatnią liczbą całkowitą.',true);return}decide('APPROVED',shards);$('customShards').value=''}
            function move(delta){if(!visibleItems.length)return;const index=visibleItems.findIndex(item=>item.id===currentId);currentId=visibleItems[(index+delta+visibleItems.length)%visibleItems.length].id;render()}
            function moveNext(){move(1)}
            function nextMatching(predicate){if(!QUEUE.length)return;let index=QUEUE.findIndex(item=>item.id===currentId);for(let step=1;step<=QUEUE.length;step++){const candidate=QUEUE[(index+step)%QUEUE.length];if(predicate(candidate)){currentId=candidate.id;render();return}}showMessage('Brak pasującego itemu.')}
            function updateProgress(){const approved=Object.values(decisions).filter(d=>d.status==='APPROVED').length;const rejected=Object.values(decisions).filter(d=>d.status==='REJECTED').length;const reviewed=approved+rejected;$('reviewedCount').textContent=`${reviewed} / ${QUEUE.length}`;$('pendingCount').textContent=QUEUE.length-reviewed;$('approvedCount').textContent=approved;$('rejectedCount').textContent=rejected}
            function quoteYaml(value){return JSON.stringify(value)}
            function exportDecisions(){const ids=Object.keys(decisions).sort((a,b)=>a.localeCompare(b,'pl'));const lines=['# Exported by LainaReforge Recycling Review Panel','items:'];for(const id of ids){const d=validateDecision(decisions[id]);lines.push(`  ${quoteYaml(id)}:`,`    status: ${d.status}`,`    recyclable: ${d.recyclable}`,`    shards: ${d.shards}`,`    reviewed_by: ${quoteYaml(d.reviewed_by)}`,`    reviewed_at: ${quoteYaml(d.reviewed_at)}`,`    note: ${quoteYaml(d.note)}`)}const newline=String.fromCharCode(10);const blob=new Blob([lines.join(newline)+newline],{type:'text/yaml;charset=utf-8'});const url=URL.createObjectURL(blob);const anchor=document.createElement('a');anchor.href=url;anchor.download='recycling-decisions.yml';anchor.click();URL.revokeObjectURL(url);showMessage(`Wyeksportowano ${ids.length} decyzji.`)}
            function unquoteYaml(value){let parsed;try{parsed=JSON.parse(value)}catch(error){throw new Error('Oczekiwano poprawnego tekstu w cudzysłowie')}if(typeof parsed!=='string')throw new Error('Oczekiwano tekstu w cudzysłowie');return parsed}
            function isItemLine(line){return line.startsWith('  "')&&line.endsWith('":')}
            function parseImport(yaml){const newline=String.fromCharCode(10);const carriageReturn=String.fromCharCode(13);const lines=yaml.split(newline).map(line=>line.endsWith(carriageReturn)?line.slice(0,-1):line);let index=0;while(index<lines.length&&(lines[index].trim()===''||lines[index].trim().startsWith('#')))index++;if(lines[index++]!=='items:')throw new Error('Plik musi zaczynać się od items:');const imported={};while(index<lines.length){while(index<lines.length&&(lines[index].trim()===''||lines[index].trim().startsWith('#')))index++;if(index>=lines.length)break;const itemLine=lines[index++];if(!isItemLine(itemLine))throw new Error(`Niepoprawny wpis w linii ${index}`);const id=unquoteYaml(itemLine.slice(2,-1));if(!ITEM_BY_ID.has(id))throw new Error(`Nieznany item: ${id}`);if(imported[id])throw new Error(`Duplikat itemu: ${id}`);const fields={};while(index<lines.length&&!isItemLine(lines[index])){const line=lines[index++];if(line.trim()===''||line.trim().startsWith('#'))continue;if(!line.startsWith('    '))throw new Error(`Niepoprawne pole w linii ${index}`);const separator=line.indexOf(': ',4);if(separator<5)throw new Error(`Niepoprawne pole w linii ${index}`);const name=line.slice(4,separator);if(!Array.from(name).every(character=>(character>='a'&&character<='z')||character==='_'))throw new Error(`Niepoprawna nazwa pola w linii ${index}`);if(name in fields)throw new Error(`Duplikat pola ${name}`);fields[name]=line.slice(separator+2)}const required=['status','recyclable','shards','reviewed_by','reviewed_at','note'];if(Object.keys(fields).sort().join(',')!==required.slice().sort().join(','))throw new Error(`Niepoprawny zestaw pól dla ${id}`);const recyclable=fields.recyclable==='true'?true:fields.recyclable==='false'?false:null;const numericShards=Number(fields.shards);const shards=Number.isInteger(numericShards)&&numericShards>=0&&String(numericShards)===fields.shards?numericShards:null;imported[id]=validateDecision({status:fields.status,recyclable,shards,reviewed_by:unquoteYaml(fields.reviewed_by),reviewed_at:unquoteYaml(fields.reviewed_at),note:unquoteYaml(fields.note)})}return imported}
            async function importFile(file){try{const imported=parseImport(await file.text());const overlap=Object.keys(imported).filter(id=>decisions[id]);if(overlap.length&&!confirm(`Import nadpisze ${overlap.length} lokalnych decyzji. Kontynuować?`))return;decisions={...decisions,...imported};save();applyFilters();showMessage(`Zaimportowano ${Object.keys(imported).length} decyzji.`)}catch(error){showMessage(`Import odrzucony: ${error.message}`,true)}finally{$('importFile').value=''}}
            function resetLocal(){if(!confirm('Usunąć wszystkie lokalne decyzje? Tej operacji nie można cofnąć bez wcześniejszego eksportu.'))return;decisions={};localStorage.removeItem(STORAGE_KEY);applyFilters();showMessage('Lokalne decyzje zostały usunięte.')}
            function jump(){const query=$('jumpInput').value.trim().toLocaleLowerCase('pl');const item=QUEUE.find(x=>x.id.toLocaleLowerCase('pl')===query||x.wiki.toLocaleLowerCase('pl')===query||x.name.toLocaleLowerCase('pl')===query);if(!item){showMessage('Nie znaleziono itemu.',true);return}currentId=item.id;render()}
            function showMessage(value,error=false){$('message').textContent=value;$('message').style.color=error?'var(--red)':'var(--muted)'}
            for(const [id,key] of [['statusFilter','status'],['mappingFilter','mapping'],['priorityFilter','priority'],['proposalFilter','proposal'],['tagFilter','tag']])$(id).addEventListener('change',event=>{filters[key]=event.target.value;applyFilters()});$('search').addEventListener('input',event=>{filters.search=event.target.value;applyFilters()});document.querySelectorAll('[data-shards]').forEach(button=>button.addEventListener('click',()=>decide('APPROVED',Number(button.dataset.shards))));document.querySelector('[data-action="reject"]').addEventListener('click',()=>decide('REJECTED',0));document.querySelector('[data-action="skip"]').addEventListener('click',moveNext);$('customApprove').addEventListener('click',customDecision);$('previous').addEventListener('click',()=>move(-1));$('next').addEventListener('click',moveNext);$('nextPending').addEventListener('click',()=>nextMatching(item=>effectiveStatus(item)==='PENDING'));$('nextHigh').addEventListener('click',()=>nextMatching(item=>item.priority==='HIGH'));$('jumpButton').addEventListener('click',jump);$('jumpInput').addEventListener('keydown',event=>{if(event.key==='Enter')jump()});$('exportButton').addEventListener('click',exportDecisions);$('importButton').addEventListener('click',()=>$('importFile').click());$('importFile').addEventListener('change',event=>event.target.files[0]&&importFile(event.target.files[0]));$('resetButton').addEventListener('click',resetLocal);$('reviewer').value=sessionStorage.getItem(REVIEWER_KEY)||'';$('reviewer').addEventListener('input',event=>sessionStorage.setItem(REVIEWER_KEY,event.target.value));for(const item of QUEUE){const option=document.createElement('option');option.value=item.wiki||item.id;option.label=item.name;$('itemList').append(option)}
            render();
            </script>
            </body>
            </html>
            """;
}

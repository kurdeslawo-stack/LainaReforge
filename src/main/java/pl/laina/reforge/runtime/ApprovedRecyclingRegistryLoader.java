package pl.laina.reforge.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns the active snapshot and performs atomic last-known-good reloads. */
public final class ApprovedRecyclingRegistryLoader {
    private static final Pattern ITEM_KEY = Pattern.compile("^  \\\"(.*)\\\":$");
    private static final Pattern FIELD = Pattern.compile("^    ([a-z_]+): (.*)$");
    private static final Set<String> REQUIRED_FIELDS =
            Set.of("recyclable", "shards", "source_item", "model_path");

    private volatile ApprovedRecyclingRegistry active = ApprovedRecyclingRegistry.empty();

    public Candidate validate(Path path) {
        try {
            return validate(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            return Candidate.invalid("Cannot read " + path + ": " + exception.getMessage());
        }
    }

    public Candidate validate(String yaml) {
        try {
            return Candidate.valid(parse(yaml));
        } catch (IllegalArgumentException exception) {
            return Candidate.invalid(exception.getMessage());
        }
    }

    public ReloadResult reload(Path path) {
        return activate(validate(path));
    }

    public ReloadResult reload(String yaml) {
        return activate(validate(yaml));
    }

    public ReloadResult activate(Candidate candidate) {
        if (!candidate.valid()) {
            return new ReloadResult(false, candidate.errors(), active.size());
        }
        active = candidate.registry();
        return new ReloadResult(true, List.of(), active.size());
    }

    public ApprovedRecyclingRegistry snapshot() {
        return active;
    }

    public RecyclingLookupResult lookup(RuntimeItemIdentity identity) {
        return active.lookup(identity);
    }

    static ApprovedRecyclingRegistry parse(String yaml) {
        List<String> lines = yaml.lines().toList();
        int index = firstContentLine(lines);
        if (index < 0) {
            throw new IllegalArgumentException("Runtime config is empty");
        }
        if (lines.get(index).equals("items: {}")) {
            ensureNoContent(lines, index + 1);
            return ApprovedRecyclingRegistry.empty();
        }
        if (!lines.get(index).equals("items:")) {
            throw new IllegalArgumentException("Runtime config must start with items:");
        }

        Map<RuntimeItemIdentity, ApprovedRecyclingRegistry.Entry> entries = new TreeMap<>();
        index++;
        while (index < lines.size()) {
            String line = lines.get(index);
            if (isIgnorable(line)) {
                index++;
                continue;
            }
            Matcher itemMatcher = ITEM_KEY.matcher(line);
            if (!itemMatcher.matches()) {
                throw new IllegalArgumentException("Malformed runtime item at line " + (index + 1));
            }
            int itemLineNumber = index + 1;
            RuntimeItemIdentity identity = RuntimeItemIdentity.parse(unescape(itemMatcher.group(1)))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Invalid material+CMD key at line " + itemLineNumber));
            if (entries.containsKey(identity)) {
                throw new IllegalArgumentException("Duplicate material+CMD: " + identity.key());
            }
            index++;
            Map<String, String> fields = new LinkedHashMap<>();
            while (index < lines.size() && !ITEM_KEY.matcher(lines.get(index)).matches()) {
                String fieldLine = lines.get(index);
                if (!isIgnorable(fieldLine)) {
                    Matcher fieldMatcher = FIELD.matcher(fieldLine);
                    if (!fieldMatcher.matches()) {
                        throw new IllegalArgumentException("Malformed field at line " + (index + 1));
                    }
                    if (fields.putIfAbsent(fieldMatcher.group(1), fieldMatcher.group(2)) != null) {
                        throw new IllegalArgumentException("Duplicate field " + fieldMatcher.group(1)
                                + " for " + identity.key());
                    }
                }
                index++;
            }
            if (!fields.keySet().equals(REQUIRED_FIELDS)) {
                throw new IllegalArgumentException("Invalid fields for " + identity.key() + ": " + fields.keySet());
            }
            boolean recyclable = parseBoolean(fields.get("recyclable"), identity.key());
            int shards = parseInteger(fields.get("shards"), identity.key());
            String sourceItem = parseQuoted(fields.get("source_item"), "source_item", identity.key());
            String modelPath = parseQuoted(fields.get("model_path"), "model_path", identity.key());
            try {
                entries.put(identity, new ApprovedRecyclingRegistry.Entry(
                        recyclable, shards, sourceItem, modelPath));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(identity.key() + ": " + exception.getMessage());
            }
        }
        return new ApprovedRecyclingRegistry(entries);
    }

    public static String render(ApprovedRecyclingRegistry registry) {
        if (registry.entries().isEmpty()) {
            return "# Compiled approved recycling decisions. Unknown identities are blocked.\nitems: {}\n";
        }
        StringBuilder yaml = new StringBuilder(
                "# Compiled approved recycling decisions. Unknown identities are blocked.\nitems:\n");
        registry.entries().forEach((identity, entry) -> yaml
                .append("  ").append(quote(identity.key())).append(":\n")
                .append("    recyclable: ").append(entry.recyclable()).append('\n')
                .append("    shards: ").append(entry.shards()).append('\n')
                .append("    source_item: ").append(quote(entry.sourceItem())).append('\n')
                .append("    model_path: ").append(quote(entry.modelPath())).append('\n'));
        return yaml.toString();
    }

    private static int firstContentLine(List<String> lines) {
        for (int index = 0; index < lines.size(); index++) {
            if (!isIgnorable(lines.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private static void ensureNoContent(List<String> lines, int start) {
        for (int index = start; index < lines.size(); index++) {
            if (!isIgnorable(lines.get(index))) {
                throw new IllegalArgumentException("Unexpected content after items: {}");
            }
        }
    }

    private static boolean isIgnorable(String line) {
        return line.isBlank() || line.stripLeading().startsWith("#");
    }

    private static boolean parseBoolean(String value, String key) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("Invalid recyclable for " + key);
        };
    }

    private static int parseInteger(String value, String key) {
        try {
            if (!value.matches("0|[1-9][0-9]*")) {
                throw new NumberFormatException();
            }
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid shards for " + key);
        }
    }

    private static String parseQuoted(String value, String field, String key) {
        if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            throw new IllegalArgumentException("Invalid " + field + " for " + key);
        }
        return unescape(value.substring(1, value.length() - 1));
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
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
                    case '\\', '"' -> character;
                    default -> throw new IllegalArgumentException("Unsupported YAML escape: \\" + character);
                });
                escaped = false;
            } else {
                result.append(character);
            }
        }
        if (escaped) {
            throw new IllegalArgumentException("Incomplete YAML escape");
        }
        return result.toString();
    }

    public record Candidate(boolean valid, ApprovedRecyclingRegistry registry, List<String> errors) {
        public Candidate {
            errors = List.copyOf(errors);
        }

        static Candidate valid(ApprovedRecyclingRegistry registry) {
            return new Candidate(true, registry, List.of());
        }

        static Candidate invalid(String error) {
            return new Candidate(false, null, List.of(error));
        }
    }

    public record ReloadResult(boolean activated, List<String> errors, int activeIdentities) {
        public ReloadResult {
            errors = Collections.unmodifiableList(new ArrayList<>(errors));
        }
    }
}

package pl.laina.reforge.catalog;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Standalone, deterministic generator for the custom item catalog.
 *
 * <p>This class is deliberately not wired into the plugin lifecycle. It only turns the supplied
 * model-definition ZIP into data files maintained in the repository.</p>
 */
public final class ItemCatalogGenerator {
    public static final Path DEFAULT_SOURCE = Path.of("items.zip");
    public static final Path DEFAULT_CATALOG = Path.of("src/main/resources/items.yml");
    public static final Path DEFAULT_REPORT = Path.of("generated/item-catalog-report.txt");

    private static final Comparator<CatalogItem> ITEM_ORDER = Comparator
            .comparing(CatalogItem::material)
            .thenComparingInt(CatalogItem::cmd)
            .thenComparing(CatalogItem::modelPath);
    private static final List<String> MODEL_CHILD_ORDER = List.of(
            "on_false", "fallback", "on_true", "cases", "entries", "model");
    private static final Set<String> IGNORED_MODEL_FIELDS = Set.of(
            "type", "property", "threshold", "scale", "when", "component");
    private static final Set<String> CONSUMABLE_MATERIALS = Set.of(
            "apple", "baked_potato", "cooked_chicken", "cooked_porkchop",
            "enchanted_golden_apple", "golden_carrot", "potion", "tropical_fish",
            "experience_bottle");
    private static final Set<String> MATERIAL_MATERIALS = Set.of(
            "bone", "echo_shard", "emerald", "egg", "ender_pearl", "shulker_shell", "snowball");

    private ItemCatalogGenerator() {
    }

    public static void main(String[] args) {
        if (args.length > 3) {
            System.err.println("Usage: ItemCatalogGenerator [items.zip] [items.yml] [report.txt]");
            System.exit(64);
        }

        Path source = args.length >= 1 ? Path.of(args[0]) : DEFAULT_SOURCE;
        Path catalog = args.length >= 2 ? Path.of(args[1]) : DEFAULT_CATALOG;
        Path report = args.length >= 3 ? Path.of(args[2]) : DEFAULT_REPORT;
        System.exit(execute(source, catalog, report, System.out, System.err));
    }

    /**
     * Runs the generator and returns a process-friendly exit code.
     *
     * <ul>
     *     <li>0 - catalog generated successfully</li>
     *     <li>2 - conflicting material+CMD definitions</li>
     *     <li>3 - invalid source data</li>
     *     <li>4 - input/output failure</li>
     * </ul>
     */
    public static int execute(
            Path source,
            Path catalog,
            Path report,
            PrintStream out,
            PrintStream err
    ) {
        try {
            GenerationResult result = generate(source);
            writeUtf8(report, renderReport(result, source.getFileName().toString()));

            if (result.hasBlockingErrors()) {
                int exitCode = result.conflicts().isEmpty() ? 3 : 2;
                err.printf(
                        Locale.ROOT,
                        "Catalog not written: conflicts=%d, invalid CMD=%d, parse/schema errors=%d.%n",
                        result.conflicts().size(),
                        result.invalidCmd().size(),
                        result.parseErrors().size() + result.schemaErrors().size());
                return exitCode;
            }

            writeUtf8(catalog, renderYaml(result));
            out.printf(
                    Locale.ROOT,
                    "Item catalog generated: json=%d, entries=%d, unique CMD=%d, unique keys=%d, shared models=%d.%n",
                    result.jsonFilesAnalyzed(),
                    result.items().size(),
                    result.uniqueCmdCount(),
                    result.uniqueKeyCount(),
                    result.modelsOnMultipleMaterials().size());
            out.printf(
                    Locale.ROOT,
                    "Warnings: files without CMD=%d, duplicates=%d. Report: %s%n",
                    result.filesWithoutCmd().size(),
                    result.duplicates().size(),
                    report);
            return 0;
        } catch (IOException exception) {
            err.println("Catalog generation failed: " + exception.getMessage());
            return 4;
        }
    }

    public static GenerationResult generate(Path source) throws IOException {
        List<SourceDefinition> definitions = new ArrayList<>();
        List<String> invalidCmd = new ArrayList<>();
        List<String> filesWithoutCmd = new ArrayList<>();
        List<String> parseErrors = new ArrayList<>();
        List<String> schemaErrors = new ArrayList<>();
        int jsonFiles;

        try (ZipFile zipFile = new ZipFile(source.toFile(), StandardCharsets.UTF_8)) {
            List<? extends ZipEntry> entries = zipFile.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted(Comparator.comparing(entry -> normalizeEntryName(entry.getName())))
                    .toList();
            jsonFiles = entries.size();

            if (entries.isEmpty()) {
                schemaErrors.add("ZIP: no JSON files found");
            }

            for (ZipEntry entry : entries) {
                String entryName = normalizeEntryName(entry.getName());
                String material = materialFromEntry(entryName);
                if (!material.matches("[a-z0-9_]+")) {
                    schemaErrors.add(entryName + ": invalid Minecraft material filename '" + material + "'");
                    continue;
                }

                String json;
                try (var input = zipFile.getInputStream(entry)) {
                    json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
                if (!json.isEmpty() && json.charAt(0) == '\uFEFF') {
                    json = json.substring(1);
                }

                Object root;
                try {
                    root = new JsonParser(json).parse();
                } catch (IllegalArgumentException exception) {
                    parseErrors.add(entryName + ": " + exception.getMessage());
                    continue;
                }

                List<Map<String, Object>> dispatchers = new ArrayList<>();
                findCustomModelDispatchers(root, dispatchers);
                if (dispatchers.isEmpty()) {
                    filesWithoutCmd.add(entryName + ": no custom_model_data dispatcher");
                    continue;
                }

                for (int dispatcherIndex = 0; dispatcherIndex < dispatchers.size(); dispatcherIndex++) {
                    Map<String, Object> dispatcher = dispatchers.get(dispatcherIndex);
                    Object rawEntries = dispatcher.get("entries");
                    if (!(rawEntries instanceof List<?> modelEntries)) {
                        schemaErrors.add(entryName + ": custom_model_data dispatcher has no entries array");
                        continue;
                    }

                    for (int entryIndex = 0; entryIndex < modelEntries.size(); entryIndex++) {
                        String location = entryName + "#dispatcher[" + dispatcherIndex + "]/entries[" + entryIndex + "]";
                        Object rawEntry = modelEntries.get(entryIndex);
                        if (!(rawEntry instanceof Map<?, ?>)) {
                            schemaErrors.add(location + ": entry is not an object");
                            continue;
                        }

                        Map<String, Object> modelEntry = asStringMap(rawEntry);
                        Integer cmd = parseCmd(modelEntry.get("threshold"));
                        if (cmd == null) {
                            invalidCmd.add(location + ": missing or invalid integer CMD");
                            continue;
                        }

                        LinkedHashSet<String> allModelPaths = new LinkedHashSet<>();
                        collectLeafModelPaths(modelEntry.get("model"), allModelPaths);
                        if (allModelPaths.isEmpty()) {
                            schemaErrors.add(location + ": no leaf model path");
                            continue;
                        }

                        List<String> preferredPaths = allModelPaths.stream()
                                .filter(path -> !isVanillaModel(path))
                                .toList();
                        if (preferredPaths.isEmpty()) {
                            preferredPaths = List.copyOf(allModelPaths);
                        }
                        String primaryPath = preferredPaths.getFirst();
                        definitions.add(new SourceDefinition(
                                new CatalogKey(material, cmd),
                                primaryPath,
                                List.copyOf(allModelPaths),
                                location));
                    }
                }
            }
        }

        definitions.sort(Comparator
                .comparing((SourceDefinition definition) -> definition.key().material())
                .thenComparingInt(definition -> definition.key().cmd())
                .thenComparing(SourceDefinition::source));

        Map<CatalogKey, SourceDefinition> uniqueDefinitions = new LinkedHashMap<>();
        List<String> duplicates = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        Set<Integer> uniqueCmd = new TreeSet<>();
        Set<CatalogKey> uniqueKeys = new HashSet<>();

        for (SourceDefinition definition : definitions) {
            uniqueCmd.add(definition.key().cmd());
            uniqueKeys.add(definition.key());
            SourceDefinition previous = uniqueDefinitions.putIfAbsent(definition.key(), definition);
            if (previous == null) {
                continue;
            }

            if (previous.modelPaths().equals(definition.modelPaths())) {
                duplicates.add(formatKey(definition.key()) + " -> " + definition.primaryModelPath()
                        + " (" + previous.source() + ", " + definition.source() + ")");
            } else {
                conflicts.add(formatKey(definition.key()) + " -> "
                        + previous.primaryModelPath() + " [" + previous.source() + "] vs "
                        + definition.primaryModelPath() + " [" + definition.source() + "]");
            }
        }

        List<CatalogItem> items = uniqueDefinitions.values().stream()
                .map(definition -> new CatalogItem(
                        definition.key().material(),
                        definition.key().cmd(),
                        simpleModelName(definition.primaryModelPath()),
                        definition.primaryModelPath(),
                        inferType(definition.key().material(), definition.primaryModelPath()),
                        "",
                        "",
                        0))
                .sorted(ITEM_ORDER)
                .toList();

        Map<String, Set<String>> materialsByModel = new TreeMap<>();
        for (CatalogItem item : items) {
            materialsByModel.computeIfAbsent(item.modelPath(), ignored -> new TreeSet<>()).add(item.material());
        }
        Map<String, List<String>> modelsOnMultipleMaterials = new TreeMap<>();
        materialsByModel.forEach((model, materials) -> {
            if (materials.size() > 1) {
                modelsOnMultipleMaterials.put(model, List.copyOf(materials));
            }
        });

        return new GenerationResult(
                jsonFiles,
                items,
                uniqueCmd.size(),
                uniqueKeys.size(),
                modelsOnMultipleMaterials,
                duplicates,
                conflicts,
                invalidCmd,
                filesWithoutCmd,
                parseErrors,
                schemaErrors);
    }

    static String renderYaml(GenerationResult result) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("# Generated by ItemCatalogGenerator from items.zip. Do not edit generated fields by hand.\n");
        yaml.append("items:\n");
        for (CatalogItem item : result.items()) {
            yaml.append("  ").append(yamlQuote(item.material() + ":" + item.cmd())).append(":\n");
            yaml.append("    material: ").append(yamlQuote(item.material())).append('\n');
            yaml.append("    cmd: ").append(item.cmd()).append('\n');
            yaml.append("    model: ").append(yamlQuote(item.model())).append('\n');
            yaml.append("    model_path: ").append(yamlQuote(item.modelPath())).append('\n');
            yaml.append("    type: ").append(yamlQuote(item.type())).append('\n');
            yaml.append("    wiki: ").append(yamlQuote(item.wiki())).append('\n');
            yaml.append("    name: ").append(yamlQuote(item.name())).append('\n');
            yaml.append("    shards: ").append(item.shards()).append('\n');
        }
        return yaml.toString();
    }

    static String renderReport(GenerationResult result, String sourceName) {
        StringBuilder report = new StringBuilder();
        report.append("Item Catalog Generator Report\n");
        report.append("=============================\n");
        report.append("Source: ").append(sourceName).append("\n\n");
        report.append("JSON files analyzed: ").append(result.jsonFilesAnalyzed()).append('\n');
        report.append("Catalog entries: ").append(result.items().size()).append('\n');
        report.append("Unique CMD values: ").append(result.uniqueCmdCount()).append('\n');
        report.append("Unique material+CMD keys: ").append(result.uniqueKeyCount()).append('\n');
        report.append("Models on multiple materials: ").append(result.modelsOnMultipleMaterials().size()).append('\n');
        report.append("Exact duplicate definitions: ").append(result.duplicates().size()).append('\n');
        report.append("Conflicts: ").append(result.conflicts().size()).append('\n');
        report.append("Invalid or missing CMD: ")
                .append(result.invalidCmd().size() + result.filesWithoutCmd().size()).append('\n');
        report.append("Parse errors: ").append(result.parseErrors().size()).append('\n');
        report.append("Schema errors: ").append(result.schemaErrors().size()).append("\n\n");

        appendSection(report, "Conflicts", result.conflicts());
        appendSection(report, "Exact duplicate definitions", result.duplicates());
        appendSection(report, "Invalid CMD entries", result.invalidCmd());
        appendSection(report, "Files without CMD mappings", result.filesWithoutCmd());
        appendSection(report, "Parse errors", result.parseErrors());
        appendSection(report, "Schema errors", result.schemaErrors());

        report.append("Models on multiple materials\n");
        report.append("----------------------------\n");
        if (result.modelsOnMultipleMaterials().isEmpty()) {
            report.append("- none\n");
        } else {
            result.modelsOnMultipleMaterials().forEach((model, materials) -> report
                    .append("- ").append(model).append(" => ").append(String.join(", ", materials)).append('\n'));
        }
        return report.toString();
    }

    public static String inferType(String material, String modelPath) {
        String normalizedMaterial = material.toLowerCase(Locale.ROOT);
        String normalizedModel = modelPath.toLowerCase(Locale.ROOT);
        String modelGroup = normalizedModel.contains("/")
                ? normalizedModel.substring(0, normalizedModel.indexOf('/'))
                : "";

        switch (modelGroup) {
            case "bows", "crossbows" -> {
                return "bow";
            }
            case "armor", "hat", "pumpkin", "shield" -> {
                return "armor";
            }
            case "swords", "mace" -> {
                return "weapon";
            }
            case "axes", "brush", "hoes", "pickaxes", "rods", "shears", "shovels" -> {
                return "tool";
            }
            case "fish", "food", "potions" -> {
                return "consumable";
            }
            case "block", "emeralds", "pearl", "solid" -> {
                return "material";
            }
            case "banner", "books", "buckets", "card", "keys", "menu", "misc", "totems" -> {
                return "misc";
            }
            default -> {
                // Fall back to the base Minecraft material for uncategorized model groups.
            }
        }

        if (normalizedMaterial.equals("bow")
                || normalizedMaterial.equals("crossbow")
                || normalizedModel.startsWith("bows/")
                || normalizedModel.startsWith("crossbows/")) {
            return "bow";
        }
        if (normalizedMaterial.equals("elytra")
                || normalizedMaterial.equals("shield")
                || normalizedMaterial.equals("carved_pumpkin")
                || hasAnySuffix(normalizedMaterial, "_helmet", "_chestplate", "_leggings", "_boots")) {
            return "armor";
        }
        if (normalizedMaterial.equals("mace")
                || normalizedMaterial.equals("trident")
                || normalizedMaterial.endsWith("_sword")
                || normalizedModel.startsWith("swords/")
                || normalizedModel.startsWith("weapons/")) {
            return "weapon";
        }
        if (normalizedMaterial.equals("brush")
                || normalizedMaterial.equals("fishing_rod")
                || normalizedMaterial.equals("shears")
                || hasAnySuffix(normalizedMaterial, "_pickaxe", "_axe", "_shovel", "_hoe")) {
            return "tool";
        }
        if (CONSUMABLE_MATERIALS.contains(normalizedMaterial)
                || normalizedModel.startsWith("food/")
                || normalizedModel.startsWith("consumables/")) {
            return "consumable";
        }
        if (MATERIAL_MATERIALS.contains(normalizedMaterial)) {
            return "material";
        }
        return "misc";
    }

    private static void findCustomModelDispatchers(Object node, List<Map<String, Object>> output) {
        if (node instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = asStringMap(rawMap);
            Object property = map.get("property");
            if (property instanceof String propertyName
                    && stripMinecraftNamespace(propertyName).equals("custom_model_data")) {
                output.add(map);
            }
            for (Object child : map.values()) {
                findCustomModelDispatchers(child, output);
            }
        } else if (node instanceof List<?> list) {
            for (Object child : list) {
                findCustomModelDispatchers(child, output);
            }
        }
    }

    private static void collectLeafModelPaths(Object node, LinkedHashSet<String> output) {
        if (node instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = asStringMap(rawMap);
            String type = map.get("type") instanceof String value ? stripMinecraftNamespace(value) : "";
            Object model = map.get("model");
            if (type.equals("model") && model instanceof String path) {
                String normalizedPath = normalizeModelPath(path);
                if (!normalizedPath.isBlank()) {
                    output.add(normalizedPath);
                }
                return;
            }

            Set<String> visited = new HashSet<>();
            for (String childName : MODEL_CHILD_ORDER) {
                if (map.containsKey(childName)) {
                    visited.add(childName);
                    collectLeafModelPaths(map.get(childName), output);
                }
            }
            map.keySet().stream()
                    .filter(key -> !visited.contains(key))
                    .filter(key -> !IGNORED_MODEL_FIELDS.contains(key))
                    .sorted()
                    .forEach(key -> collectLeafModelPaths(map.get(key), output));
        } else if (node instanceof List<?> list) {
            for (Object child : list) {
                collectLeafModelPaths(child, output);
            }
        }
    }

    private static Integer parseCmd(Object value) {
        if (!(value instanceof BigDecimal number)) {
            return null;
        }
        try {
            int cmd = number.intValueExact();
            return cmd >= 0 ? cmd : null;
        } catch (ArithmeticException ignored) {
            return null;
        }
    }

    private static Map<String, Object> asStringMap(Object value) {
        Map<?, ?> rawMap = (Map<?, ?>) value;
        Map<String, Object> result = new LinkedHashMap<>();
        rawMap.forEach((key, child) -> result.put(String.valueOf(key), child));
        return result;
    }

    private static String materialFromEntry(String entryName) {
        int slash = entryName.lastIndexOf('/');
        String filename = slash >= 0 ? entryName.substring(slash + 1) : entryName;
        return filename.substring(0, filename.length() - ".json".length()).toLowerCase(Locale.ROOT);
    }

    private static String normalizeEntryName(String value) {
        return value.replace('\\', '/');
    }

    private static String normalizeModelPath(String value) {
        String path = value.trim().replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        return path;
    }

    private static String simpleModelName(String modelPath) {
        int separator = Math.max(modelPath.lastIndexOf('/'), modelPath.lastIndexOf(':'));
        return separator >= 0 ? modelPath.substring(separator + 1) : modelPath;
    }

    private static String stripMinecraftNamespace(String value) {
        return value.startsWith("minecraft:") ? value.substring("minecraft:".length()) : value;
    }

    private static boolean isVanillaModel(String modelPath) {
        return modelPath.startsWith("minecraft:");
    }

    private static boolean hasAnySuffix(String value, String... suffixes) {
        for (String suffix : suffixes) {
            if (value.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static String formatKey(CatalogKey key) {
        return key.material() + ":" + key.cmd();
    }

    private static String yamlQuote(String value) {
        return '"' + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + '"';
    }

    private static void appendSection(StringBuilder report, String title, List<String> values) {
        report.append(title).append('\n');
        report.append("-".repeat(title.length())).append('\n');
        if (values.isEmpty()) {
            report.append("- none\n\n");
            return;
        }
        for (String value : values) {
            report.append("- ").append(value).append('\n');
        }
        report.append('\n');
    }

    private static void writeUtf8(Path path, String content) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                path,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    public record CatalogItem(
            String material,
            int cmd,
            String model,
            String modelPath,
            String type,
            String wiki,
            String name,
            int shards
    ) {
    }

    public record GenerationResult(
            int jsonFilesAnalyzed,
            List<CatalogItem> items,
            int uniqueCmdCount,
            int uniqueKeyCount,
            Map<String, List<String>> modelsOnMultipleMaterials,
            List<String> duplicates,
            List<String> conflicts,
            List<String> invalidCmd,
            List<String> filesWithoutCmd,
            List<String> parseErrors,
            List<String> schemaErrors
    ) {
        public GenerationResult {
            items = List.copyOf(items);
            modelsOnMultipleMaterials = immutableSortedMap(modelsOnMultipleMaterials);
            duplicates = List.copyOf(duplicates);
            conflicts = List.copyOf(conflicts);
            invalidCmd = List.copyOf(invalidCmd);
            filesWithoutCmd = List.copyOf(filesWithoutCmd);
            parseErrors = List.copyOf(parseErrors);
            schemaErrors = List.copyOf(schemaErrors);
        }

        public boolean hasBlockingErrors() {
            return !conflicts.isEmpty()
                    || !invalidCmd.isEmpty()
                    || !parseErrors.isEmpty()
                    || !schemaErrors.isEmpty();
        }

        private static Map<String, List<String>> immutableSortedMap(Map<String, List<String>> source) {
            Map<String, List<String>> copy = new TreeMap<>();
            source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            return Collections.unmodifiableMap(copy);
        }
    }

    private record CatalogKey(String material, int cmd) {
    }

    private record SourceDefinition(
            CatalogKey key,
            String primaryModelPath,
            List<String> modelPaths,
            String source
    ) {
    }

    /** Minimal strict JSON parser, kept local so the catalog tool has no runtime dependencies. */
    private static final class JsonParser {
        private final String input;
        private int index;

        private JsonParser(String input) {
            this.input = input;
        }

        private Object parse() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (index != input.length()) {
                throw error("unexpected trailing content");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (index >= input.length()) {
                throw error("unexpected end of input");
            }
            return switch (input.charAt(index)) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) {
                return result;
            }
            while (true) {
                skipWhitespace();
                if (index >= input.length() || input.charAt(index) != '"') {
                    throw error("expected object key");
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                if (result.containsKey(key)) {
                    throw error("duplicate object key '" + key + "'");
                }
                result.put(key, value);
                skipWhitespace();
                if (consume('}')) {
                    return result;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) {
                return result;
            }
            while (true) {
                result.add(parseValue());
                skipWhitespace();
                if (consume(']')) {
                    return result;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (index < input.length()) {
                char current = input.charAt(index++);
                if (current == '"') {
                    return result.toString();
                }
                if (current == '\\') {
                    if (index >= input.length()) {
                        throw error("unfinished string escape");
                    }
                    char escaped = input.charAt(index++);
                    switch (escaped) {
                        case '"', '\\', '/' -> result.append(escaped);
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> result.append(parseUnicodeEscape());
                        default -> throw error("invalid string escape");
                    }
                } else {
                    if (current < 0x20) {
                        throw error("unescaped control character in string");
                    }
                    result.append(current);
                }
            }
            throw error("unterminated string");
        }

        private char parseUnicodeEscape() {
            if (index + 4 > input.length()) {
                throw error("unfinished unicode escape");
            }
            int value = 0;
            for (int offset = 0; offset < 4; offset++) {
                int digit = Character.digit(input.charAt(index++), 16);
                if (digit < 0) {
                    throw error("invalid unicode escape");
                }
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private Object parseLiteral(String expected, Object value) {
            if (!input.startsWith(expected, index)) {
                throw error("invalid value");
            }
            index += expected.length();
            return value;
        }

        private BigDecimal parseNumber() {
            int start = index;
            if (consume('-') && index >= input.length()) {
                throw error("invalid number");
            }
            if (consume('0')) {
                if (index < input.length() && Character.isDigit(input.charAt(index))) {
                    throw error("leading zero in number");
                }
            } else {
                consumeDigits(true);
            }
            if (consume('.')) {
                consumeDigits(true);
            }
            if (index < input.length() && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
                index++;
                if (index < input.length() && (input.charAt(index) == '+' || input.charAt(index) == '-')) {
                    index++;
                }
                consumeDigits(true);
            }
            try {
                return new BigDecimal(input.substring(start, index));
            } catch (NumberFormatException exception) {
                throw error("invalid number");
            }
        }

        private void consumeDigits(boolean requireAtLeastOne) {
            int start = index;
            while (index < input.length() && Character.isDigit(input.charAt(index))) {
                index++;
            }
            if (requireAtLeastOne && start == index) {
                throw error("expected digit");
            }
        }

        private void skipWhitespace() {
            while (index < input.length()) {
                char current = input.charAt(index);
                if (current != ' ' && current != '\n' && current != '\r' && current != '\t') {
                    return;
                }
                index++;
            }
        }

        private boolean consume(char expected) {
            if (index < input.length() && input.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consume(expected)) {
                throw error("expected '" + expected + "'");
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at character " + index);
        }
    }
}

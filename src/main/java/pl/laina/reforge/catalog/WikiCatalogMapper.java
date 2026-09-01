package pl.laina.reforge.catalog;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
 * Standalone maintenance tool mapping generated item models to Wiki Laina.PL pages.
 *
 * <p>The mapper is intentionally not connected to the plugin lifecycle. It performs conservative,
 * exact image-name matching and only accepts a page that directly belongs to the {@code Przedmioty}
 * category. Uncertain data stays unmapped and is written to the manual-review report.</p>
 */
public final class WikiCatalogMapper {
    public static final Path DEFAULT_CATALOG = Path.of("src/main/resources/items.yml");
    public static final Path DEFAULT_CACHE = Path.of("generated/wiki-api-cache.xml");
    public static final Path DEFAULT_REPORT = Path.of("generated/wiki-mapping-report.txt");
    public static final Path DEFAULT_MANUAL_REVIEW = Path.of("generated/wiki-manual-review.yml");
    public static final String DEFAULT_API = "https://wiki.laina.pl/api.php";
    public static final long DEFAULT_DELAY_MILLIS = 250L;

    private static final Pattern RECORD_KEY = Pattern.compile("^  \"([^\"]+)\":$");
    private static final Pattern STRING_FIELD = Pattern.compile("^    ([a-z_]+): \"(.*)\"$");
    private static final Pattern INTEGER_FIELD = Pattern.compile("^    ([a-z_]+): (-?[0-9]+)$");
    private static final String ITEM_CATEGORY = normalizeCategory("Kategoria:Przedmioty");

    private WikiCatalogMapper() {
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
            CatalogDocument catalog = CatalogDocument.parse(Files.readString(options.catalog(), StandardCharsets.UTF_8));
            WikiSnapshot snapshot = options.refresh()
                    ? new WikiSnapshot()
                    : WikiSnapshot.load(options.cache());
            List<String> apiErrors = new ArrayList<>();

            if (!options.offline()) {
                MediaWikiApiClient client = new MediaWikiApiClient(
                        options.apiEndpoint(),
                        options.delayMillis(),
                        apiErrors);
                hydrateSnapshot(catalog, snapshot, client);
                snapshot.save(options.cache());
            } else {
                addOfflineCacheErrors(catalog, snapshot, apiErrors);
            }

            MappingResult result = map(catalog, snapshot, apiErrors);
            String updatedCatalog = catalog.render(result.decisionsByModelPath());
            assertShardsUnchanged(catalog, CatalogDocument.parse(updatedCatalog));

            writeUtf8Atomic(options.catalog(), updatedCatalog);
            writeUtf8Atomic(options.report(), renderReport(result));
            writeUtf8Atomic(options.manualReview(), renderManualReview(result));

            out.printf(
                    Locale.ROOT,
                    "Wiki mapping complete: model paths=%d, mapped model paths=%d, mapped records=%d/%d, manual review=%d.%n",
                    result.uniqueModelPaths(),
                    result.mappedModelPaths(),
                    result.mappedRecords(),
                    result.totalRecords(),
                    result.manualReviewCount());
            out.printf(
                    Locale.ROOT,
                    "Images=%d, missing=%d, ambiguous=%d, HTTP/API errors=%d.%n",
                    result.matchedImageFiles(),
                    result.missingImages(),
                    result.ambiguousMatches(),
                    result.apiErrors().size());
            if (!result.apiErrors().isEmpty()) {
                err.println("Wiki mapping completed conservatively with API errors; affected models remain empty.");
                return 2;
            }
            return 0;
        } catch (IOException exception) {
            err.println("Wiki mapping failed: " + exception.getMessage());
            return 3;
        }
    }

    private static void hydrateSnapshot(
            CatalogDocument catalog,
            WikiSnapshot snapshot,
            MediaWikiApiClient client
    ) {
        if (!snapshot.imageInventoryComplete()) {
            client.fetchAllImages(snapshot);
        }

        Set<String> requiredImages = matchingImages(catalog.uniqueBasenames(), snapshot.images()).values().stream()
                .flatMap(Collection::stream)
                .collect(TreeSet::new, Set::add, Set::addAll);
        client.fetchMissingFileUsage(snapshot, requiredImages);

        Set<String> requiredPages = new TreeSet<>();
        for (String image : requiredImages) {
            List<WikiUsage> usages = snapshot.fileUsage(image);
            if (usages == null) {
                continue;
            }
            usages.stream()
                    .filter(usage -> usage.namespace() == 0)
                    .map(WikiUsage::title)
                    .forEach(requiredPages::add);
        }
        client.fetchMissingPageCategories(snapshot, requiredPages);
    }

    private static void addOfflineCacheErrors(
            CatalogDocument catalog,
            WikiSnapshot snapshot,
            List<String> errors
    ) {
        if (!snapshot.imageInventoryComplete()) {
            errors.add("offline cache has no complete image inventory");
            return;
        }
        Set<String> requiredImages = matchingImages(catalog.uniqueBasenames(), snapshot.images()).values().stream()
                .flatMap(Collection::stream)
                .collect(TreeSet::new, Set::add, Set::addAll);
        for (String image : requiredImages) {
            if (snapshot.fileUsage(image) == null) {
                errors.add("offline cache has no file usage for " + image);
                continue;
            }
            for (WikiUsage usage : snapshot.fileUsage(image)) {
                if (usage.namespace() == 0 && snapshot.page(usage.title()) == null) {
                    errors.add("offline cache has no categories for " + usage.title());
                }
            }
        }
    }

    static MappingResult map(
            CatalogDocument catalog,
            WikiSnapshot snapshot,
            List<String> apiErrors
    ) {
        Map<String, List<CatalogRecord>> recordsByModelPath = catalog.recordsByModelPath();
        Map<String, List<String>> modelPathsByBasename = catalog.modelPathsByBasename();
        Map<String, List<String>> imagesByBasename = matchingImages(modelPathsByBasename.keySet(), snapshot.images());
        Map<String, MappingDecision> decisionsByModelPath = new TreeMap<>();
        Set<String> matchedImages = new TreeSet<>();
        Map<String, Integer> imageCountByPage = imageCountByPage(snapshot);

        for (Map.Entry<String, List<CatalogRecord>> entry : recordsByModelPath.entrySet()) {
            String modelPath = entry.getKey();
            String basename = entry.getValue().getFirst().model();
            List<String> images = imagesByBasename.getOrDefault(basename, List.of());
            matchedImages.addAll(images);
            MappingDecision decision;

            if (modelPathsByBasename.getOrDefault(basename, List.of()).size() > 1) {
                decision = MappingDecision.review(
                        modelPath,
                        basename,
                        images,
                        potentialPagesForImages(images, snapshot),
                        "BASENAME_COLLISION");
            } else if (images.isEmpty()) {
                decision = MappingDecision.review(modelPath, basename, images, List.of(), "missing_image");
            } else if (images.size() > 1) {
                decision = MappingDecision.review(modelPath, basename, images, List.of(), "ambiguous_image");
            } else {
                decision = decideFromImage(modelPath, basename, images.getFirst(), snapshot, imageCountByPage);
            }
            decisionsByModelPath.put(modelPath, decision);
        }

        int mappedRecords = 0;
        int mappedModelPaths = 0;
        int missingImages = 0;
        int ambiguousMatches = 0;
        int manualReview = (int) modelPathsByBasename.values().stream().filter(paths -> paths.size() > 1).count();
        for (Map.Entry<String, MappingDecision> entry : decisionsByModelPath.entrySet()) {
            MappingDecision decision = entry.getValue();
            if (decision.mapped()) {
                mappedModelPaths++;
                mappedRecords += recordsByModelPath.get(entry.getKey()).size();
            } else {
                if (!decision.reason().equals("BASENAME_COLLISION")) {
                    manualReview++;
                }
                if (decision.reason().equals("missing_image")) {
                    missingImages++;
                }
                if (decision.reason().equals("ambiguous_image")
                        || decision.reason().equals("ambiguous_page")) {
                    ambiguousMatches++;
                }
            }
        }

        return new MappingResult(
                catalog.records().size(),
                recordsByModelPath.size(),
                modelPathsByBasename.size(),
                (int) modelPathsByBasename.values().stream().filter(paths -> paths.size() > 1).count(),
                matchedImages.size(),
                mappedModelPaths,
                mappedRecords,
                missingImages,
                ambiguousMatches,
                manualReview,
                decisionsByModelPath,
                recordsByModelPath,
                modelPathsByBasename,
                List.copyOf(apiErrors));
    }

    private static MappingDecision decideFromImage(
            String modelPath,
            String basename,
            String image,
            WikiSnapshot snapshot,
            Map<String, Integer> imageCountByPage
    ) {
        List<WikiUsage> usages = snapshot.fileUsage(image);
        if (usages == null) {
            return MappingDecision.review(modelPath, basename, List.of(image), List.of(), "api_data_missing");
        }

        List<String> potentialPages = usages.stream()
                .filter(usage -> usage.namespace() == 0)
                .map(WikiUsage::title)
                .distinct()
                .sorted()
                .toList();
        List<String> itemPages = new ArrayList<>();
        List<String> collectionPages = new ArrayList<>();
        boolean incompletePageData = false;
        for (String title : potentialPages) {
            WikiPage page = snapshot.page(title);
            if (page == null) {
                incompletePageData = true;
            } else if (page.exists() && page.categories().stream()
                    .map(WikiCatalogMapper::normalizeCategory)
                    .anyMatch(ITEM_CATEGORY::equals)) {
                if (imageCountByPage.getOrDefault(page.title(), 0) == 1) {
                    itemPages.add(page.title());
                } else {
                    collectionPages.add(page.title());
                }
            }
        }
        itemPages = itemPages.stream().distinct().sorted().toList();

        if (incompletePageData) {
            return MappingDecision.review(modelPath, basename, List.of(image), potentialPages, "api_data_missing");
        }
        if (itemPages.size() == 1) {
            String title = itemPages.getFirst();
            return MappingDecision.mapped(modelPath, basename, image, potentialPages, title);
        }
        if (itemPages.size() > 1) {
            return MappingDecision.review(modelPath, basename, List.of(image), potentialPages, "ambiguous_page");
        }
        if (!collectionPages.isEmpty()) {
            return MappingDecision.review(modelPath, basename, List.of(image), potentialPages, "collection_page");
        }
        return MappingDecision.review(modelPath, basename, List.of(image), potentialPages, "no_item_page");
    }

    private static List<String> potentialPagesForImages(List<String> images, WikiSnapshot snapshot) {
        Set<String> pages = new TreeSet<>();
        for (String image : images) {
            List<WikiUsage> usages = snapshot.fileUsage(image);
            if (usages == null) {
                continue;
            }
            usages.stream()
                    .filter(usage -> usage.namespace() == 0)
                    .map(WikiUsage::title)
                    .forEach(pages::add);
        }
        return List.copyOf(pages);
    }

    private static Map<String, Integer> imageCountByPage(WikiSnapshot snapshot) {
        Map<String, Set<String>> imagesByPage = new TreeMap<>();
        snapshot.fileUsageEntries().forEach((image, usages) -> usages.stream()
                .filter(usage -> usage.namespace() == 0)
                .forEach(usage -> imagesByPage
                        .computeIfAbsent(usage.title(), ignored -> new TreeSet<>())
                        .add(image)));
        Map<String, Integer> result = new TreeMap<>();
        imagesByPage.forEach((page, images) -> result.put(page, images.size()));
        return result;
    }

    private static Map<String, List<String>> matchingImages(
            Collection<String> models,
            Collection<String> images
    ) {
        Map<String, List<String>> imageIndex = new TreeMap<>();
        for (String image : images) {
            String normalized = normalizeImageName(image);
            if (normalized == null) {
                continue;
            }
            imageIndex.computeIfAbsent(normalized, ignored -> new ArrayList<>()).add(image);
        }
        imageIndex.values().forEach(list -> list.sort(String::compareTo));

        Map<String, List<String>> result = new TreeMap<>();
        for (String model : models) {
            result.put(model, List.copyOf(imageIndex.getOrDefault(normalizeModelName(model), List.of())));
        }
        return result;
    }

    static String normalizeModelName(String value) {
        String decoded = decodeUrl(value).trim();
        if (decoded.toLowerCase(Locale.ROOT).endsWith(".png")) {
            decoded = decoded.substring(0, decoded.length() - 4);
        }
        return normalizeComparableName(decoded);
    }

    static String normalizeImageName(String value) {
        String decoded = decodeUrl(value).trim();
        if (!decoded.toLowerCase(Locale.ROOT).endsWith(".png")) {
            return null;
        }
        return normalizeComparableName(decoded.substring(0, decoded.length() - 4));
    }

    private static String normalizeComparableName(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
                .replace('_', ' ')
                .trim()
                .replaceAll("\\s+", " ");
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String decodeUrl(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }

    private static String normalizeCategory(String value) {
        return normalizeComparableName(value);
    }

    private static void assertShardsUnchanged(CatalogDocument before, CatalogDocument after) {
        Map<String, Integer> expected = new TreeMap<>();
        before.records().forEach(record -> expected.put(record.key(), record.shards()));
        Map<String, Integer> actual = new TreeMap<>();
        after.records().forEach(record -> actual.put(record.key(), record.shards()));
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Wiki mapping attempted to change shards values");
        }
    }

    static String renderReport(MappingResult result) {
        double percentage = result.totalRecords() == 0
                ? 0.0
                : result.mappedRecords() * 100.0 / result.totalRecords();
        StringBuilder report = new StringBuilder();
        report.append("Wiki Catalog Mapping Report\n");
        report.append("===========================\n\n");
        report.append("Unique model paths: ").append(result.uniqueModelPaths()).append('\n');
        report.append("Unique basenames: ").append(result.uniqueBasenames()).append('\n');
        report.append("Basename collisions: ").append(result.basenameCollisions()).append('\n');
        report.append("Matching image files found: ").append(result.matchedImageFiles()).append('\n');
        report.append("Unambiguously mapped model paths: ").append(result.mappedModelPaths()).append('\n');
        report.append("Catalog records updated: ").append(result.mappedRecords()).append('\n');
        report.append(String.format(Locale.ROOT, "Catalog coverage: %.2f%%\n", percentage));
        report.append("Missing images: ").append(result.missingImages()).append('\n');
        report.append("Ambiguous matches: ").append(result.ambiguousMatches()).append('\n');
        report.append("Manual-review cases: ").append(result.manualReviewCount()).append('\n');
        report.append("HTTP/API errors: ").append(result.apiErrors().size()).append("\n\n");

        Map<String, Long> reasons = new TreeMap<>();
        result.decisionsByModelPath().values().stream()
                .filter(decision -> !decision.mapped())
                .filter(decision -> !decision.reason().equals("BASENAME_COLLISION"))
                .forEach(decision -> reasons.merge(decision.reason(), 1L, Long::sum));
        if (result.basenameCollisions() > 0) {
            reasons.put("BASENAME_COLLISION", (long) result.basenameCollisions());
        }
        report.append("Manual-review reasons\n");
        report.append("---------------------\n");
        if (reasons.isEmpty()) {
            report.append("- none\n");
        } else {
            reasons.forEach((reason, count) -> report.append("- ").append(reason).append(": ").append(count).append('\n'));
        }
        report.append("\nHTTP/API errors\n");
        report.append("---------------\n");
        if (result.apiErrors().isEmpty()) {
            report.append("- none\n");
        } else {
            result.apiErrors().forEach(error -> report.append("- ").append(error).append('\n'));
        }
        return report.toString();
    }

    static String renderManualReview(MappingResult result) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("manual_review:\n");
        for (Map.Entry<String, List<String>> entry : result.modelPathsByBasename().entrySet()) {
            String basename = entry.getKey();
            List<String> modelPaths = entry.getValue();
            MappingDecision decision = result.decisionsByModelPath().get(modelPaths.getFirst());
            if (decision == null || decision.mapped()) {
                continue;
            }
            yaml.append("  - model: ").append(yamlQuote(basename)).append('\n');
            yaml.append("    basename: ").append(yamlQuote(basename)).append('\n');
            yaml.append("    model_paths:");
            appendYamlList(yaml, modelPaths, 6);
            yaml.append("    records:\n");
            for (CatalogRecord record : result.recordsForModelPaths(modelPaths)) {
                yaml.append("      - material: ").append(yamlQuote(record.material())).append('\n');
                yaml.append("        cmd: ").append(record.cmd()).append('\n');
                yaml.append("        model_path: ").append(yamlQuote(record.modelPath())).append('\n');
            }
            yaml.append("    found_images:");
            appendYamlList(yaml, decision.images(), 6);
            yaml.append("    potential_pages:");
            appendYamlList(yaml, decision.potentialPages(), 6);
            yaml.append("    reason: ").append(yamlQuote(decision.reason())).append('\n');
        }
        return yaml.toString();
    }

    private static void appendYamlList(StringBuilder yaml, List<String> values, int indentation) {
        if (values.isEmpty()) {
            yaml.append(" []\n");
            return;
        }
        yaml.append('\n');
        String prefix = " ".repeat(indentation);
        for (String value : values) {
            yaml.append(prefix).append("- ").append(yamlQuote(value)).append('\n');
        }
    }

    private static String yamlQuote(String value) {
        return '"' + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + '"';
    }

    static Document parseXml(byte[] bytes) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        } catch (ParserConfigurationException | org.xml.sax.SAXException exception) {
            throw new IOException("invalid XML: " + exception.getMessage(), exception);
        }
    }

    private static void writeUtf8Atomic(Path path, String content) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
        try {
            Files.writeString(
                    temporary,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(
                        temporary,
                        absolute,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record Options(
            Path catalog,
            Path cache,
            Path report,
            Path manualReview,
            String apiEndpoint,
            long delayMillis,
            boolean refresh,
            boolean offline
    ) {
        static Options parse(String[] args) {
            Path catalog = DEFAULT_CATALOG;
            Path cache = DEFAULT_CACHE;
            Path report = DEFAULT_REPORT;
            Path manualReview = DEFAULT_MANUAL_REVIEW;
            String api = DEFAULT_API;
            long delay = DEFAULT_DELAY_MILLIS;
            boolean refresh = false;
            boolean offline = false;

            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--catalog" -> catalog = Path.of(requireValue(args, ++index, "--catalog"));
                    case "--cache" -> cache = Path.of(requireValue(args, ++index, "--cache"));
                    case "--report" -> report = Path.of(requireValue(args, ++index, "--report"));
                    case "--manual-review" -> manualReview = Path.of(requireValue(args, ++index, "--manual-review"));
                    case "--api" -> api = requireValue(args, ++index, "--api");
                    case "--delay-ms" -> delay = parseDelay(requireValue(args, ++index, "--delay-ms"));
                    case "--refresh" -> refresh = true;
                    case "--offline" -> offline = true;
                    default -> throw new IllegalArgumentException("Unknown option: " + args[index]);
                }
            }
            if (refresh && offline) {
                throw new IllegalArgumentException("--refresh and --offline cannot be used together");
            }
            return new Options(catalog, cache, report, manualReview, api, delay, refresh, offline);
        }

        static String usage() {
            return "Usage: WikiCatalogMapper [--catalog path] [--cache path] [--report path] "
                    + "[--manual-review path] [--delay-ms number] [--refresh|--offline]";
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        private static long parseDelay(String value) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed < 0) {
                    throw new NumberFormatException();
                }
                return parsed;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("--delay-ms must be a non-negative integer");
            }
        }
    }

    static final class CatalogDocument {
        private final List<String> lines;
        private final List<CatalogRecord> records;

        private CatalogDocument(List<String> lines, List<CatalogRecord> records) {
            this.lines = List.copyOf(lines);
            this.records = List.copyOf(records);
        }

        static CatalogDocument parse(String content) {
            List<String> lines = new ArrayList<>(List.of(content.split("\\R", -1)));
            List<CatalogRecord> records = new ArrayList<>();
            RecordBuilder current = null;

            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                Matcher keyMatcher = RECORD_KEY.matcher(line);
                if (keyMatcher.matches()) {
                    if (current != null) {
                        records.add(current.build());
                    }
                    current = new RecordBuilder(keyMatcher.group(1));
                    continue;
                }
                if (current == null) {
                    continue;
                }
                Matcher stringMatcher = STRING_FIELD.matcher(line);
                if (stringMatcher.matches()) {
                    current.stringField(stringMatcher.group(1), unescapeYaml(stringMatcher.group(2)), index);
                    continue;
                }
                Matcher integerMatcher = INTEGER_FIELD.matcher(line);
                if (integerMatcher.matches()) {
                    current.integerField(integerMatcher.group(1), integerMatcher.group(2));
                }
            }
            if (current != null) {
                records.add(current.build());
            }
            if (records.isEmpty()) {
                throw new IllegalArgumentException("items.yml contains no catalog records");
            }
            return new CatalogDocument(lines, records);
        }

        List<CatalogRecord> records() {
            return records;
        }

        Set<String> uniqueBasenames() {
            Set<String> basenames = new TreeSet<>();
            records.forEach(record -> basenames.add(record.model()));
            return Collections.unmodifiableSet(basenames);
        }

        Map<String, List<CatalogRecord>> recordsByModelPath() {
            Map<String, List<CatalogRecord>> grouped = new TreeMap<>();
            for (CatalogRecord record : records) {
                grouped.computeIfAbsent(record.modelPath(), ignored -> new ArrayList<>()).add(record);
            }
            grouped.replaceAll((ignored, value) -> value.stream()
                    .sorted(Comparator.comparing(CatalogRecord::material).thenComparingInt(CatalogRecord::cmd))
                    .toList());
            return Collections.unmodifiableMap(grouped);
        }

        Map<String, List<String>> modelPathsByBasename() {
            Map<String, Set<String>> grouped = new TreeMap<>();
            for (CatalogRecord record : records) {
                grouped.computeIfAbsent(record.model(), ignored -> new TreeSet<>()).add(record.modelPath());
            }
            Map<String, List<String>> result = new TreeMap<>();
            grouped.forEach((basename, paths) -> result.put(basename, List.copyOf(paths)));
            return Collections.unmodifiableMap(result);
        }

        String render(Map<String, MappingDecision> decisionsByModelPath) {
            List<String> updated = new ArrayList<>(lines);
            for (CatalogRecord record : records) {
                MappingDecision decision = decisionsByModelPath.get(record.modelPath());
                String page = decision != null && decision.mapped() ? decision.pageTitle() : "";
                String name = page.isEmpty() ? "" : page.replace('_', ' ');
                String wiki = page.isEmpty() ? "" : page.replace(' ', '_');
                updated.set(record.wikiLine(), "    wiki: " + yamlQuote(wiki));
                updated.set(record.nameLine(), "    name: " + yamlQuote(name));
            }
            return String.join("\n", updated);
        }

        private static String unescapeYaml(String value) {
            StringBuilder result = new StringBuilder();
            boolean escaped = false;
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (!escaped) {
                    if (current == '\\') {
                        escaped = true;
                    } else {
                        result.append(current);
                    }
                    continue;
                }
                switch (current) {
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    default -> result.append(current);
                }
                escaped = false;
            }
            if (escaped) {
                result.append('\\');
            }
            return result.toString();
        }
    }

    public record CatalogRecord(
            String key,
            String material,
            int cmd,
            String model,
            String modelPath,
            int shards,
            int wikiLine,
            int nameLine
    ) {
    }

    private static final class RecordBuilder {
        private final String key;
        private String material;
        private Integer cmd;
        private String model;
        private String modelPath;
        private Integer shards;
        private Integer wikiLine;
        private Integer nameLine;

        private RecordBuilder(String key) {
            this.key = key;
        }

        private void stringField(String field, String value, int line) {
            switch (field) {
                case "material" -> material = value;
                case "model" -> model = value;
                case "model_path" -> modelPath = value;
                case "wiki" -> wikiLine = line;
                case "name" -> nameLine = line;
                default -> {
                }
            }
        }

        private void integerField(String field, String value) {
            try {
                switch (field) {
                    case "cmd" -> cmd = Integer.parseInt(value);
                    case "shards" -> shards = Integer.parseInt(value);
                    default -> {
                    }
                }
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid integer " + field + " in " + key);
            }
        }

        private CatalogRecord build() {
            if (material == null || cmd == null || model == null || modelPath == null
                    || shards == null || wikiLine == null || nameLine == null) {
                throw new IllegalArgumentException("Incomplete catalog record: " + key);
            }
            return new CatalogRecord(key, material, cmd, model, modelPath, shards, wikiLine, nameLine);
        }
    }

    public record WikiUsage(int namespace, String title) {
    }

    public record WikiPage(String title, boolean exists, List<String> categories) {
        public WikiPage {
            categories = categories.stream().distinct().sorted().toList();
        }
    }

    static final class WikiSnapshot {
        private boolean imageInventoryComplete;
        private final Set<String> images = new TreeSet<>();
        private final Map<String, List<WikiUsage>> fileUsage = new TreeMap<>();
        private final Map<String, WikiPage> pages = new TreeMap<>();

        boolean imageInventoryComplete() {
            return imageInventoryComplete;
        }

        void markImageInventoryComplete() {
            imageInventoryComplete = true;
        }

        Set<String> images() {
            return Collections.unmodifiableSet(images);
        }

        void addImages(Collection<String> values) {
            images.addAll(values);
        }

        List<WikiUsage> fileUsage(String image) {
            return fileUsage.get(image);
        }

        Map<String, List<WikiUsage>> fileUsageEntries() {
            return Collections.unmodifiableMap(fileUsage);
        }

        void putFileUsage(String image, Collection<WikiUsage> usages) {
            fileUsage.put(image, usages.stream()
                    .distinct()
                    .sorted(Comparator.comparingInt(WikiUsage::namespace).thenComparing(WikiUsage::title))
                    .toList());
        }

        WikiPage page(String title) {
            return pages.get(title);
        }

        void putPage(WikiPage page) {
            pages.put(page.title(), page);
        }

        static WikiSnapshot load(Path path) throws IOException {
            WikiSnapshot snapshot = new WikiSnapshot();
            if (!Files.exists(path)) {
                return snapshot;
            }
            Document document = parseXml(Files.readAllBytes(path));
            Element root = document.getDocumentElement();
            if (!root.getTagName().equals("wiki-cache") || !root.getAttribute("version").equals("1")) {
                throw new IOException("unsupported wiki cache format");
            }
            snapshot.imageInventoryComplete = Boolean.parseBoolean(root.getAttribute("images-complete"));

            for (Element image : childElements(root, "images", "image")) {
                snapshot.images.add(image.getAttribute("name"));
            }
            for (Element usageElement : childElements(root, "file-usages", "file-usage")) {
                String image = usageElement.getAttribute("image");
                List<WikiUsage> usages = new ArrayList<>();
                for (Element page : directChildren(usageElement, "page")) {
                    usages.add(new WikiUsage(
                            Integer.parseInt(page.getAttribute("ns")),
                            page.getAttribute("title")));
                }
                snapshot.putFileUsage(image, usages);
            }
            for (Element pageElement : childElements(root, "pages", "page")) {
                List<String> categories = directChildren(pageElement, "category").stream()
                        .map(element -> element.getAttribute("title"))
                        .toList();
                snapshot.putPage(new WikiPage(
                        pageElement.getAttribute("title"),
                        Boolean.parseBoolean(pageElement.getAttribute("exists")),
                        categories));
            }
            return snapshot;
        }

        void save(Path path) throws IOException {
            StringBuilder xml = new StringBuilder();
            xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            xml.append("<wiki-cache version=\"1\" images-complete=\"")
                    .append(imageInventoryComplete).append("\">\n");
            xml.append("  <images>\n");
            for (String image : images) {
                xml.append("    <image name=\"").append(xmlEscape(image)).append("\"/>\n");
            }
            xml.append("  </images>\n");
            xml.append("  <file-usages>\n");
            for (Map.Entry<String, List<WikiUsage>> entry : fileUsage.entrySet()) {
                xml.append("    <file-usage image=\"").append(xmlEscape(entry.getKey())).append("\">\n");
                for (WikiUsage usage : entry.getValue()) {
                    xml.append("      <page ns=\"").append(usage.namespace()).append("\" title=\"")
                            .append(xmlEscape(usage.title())).append("\"/>\n");
                }
                xml.append("    </file-usage>\n");
            }
            xml.append("  </file-usages>\n");
            xml.append("  <pages>\n");
            for (WikiPage page : pages.values()) {
                xml.append("    <page title=\"").append(xmlEscape(page.title())).append("\" exists=\"")
                        .append(page.exists()).append("\">\n");
                for (String category : page.categories()) {
                    xml.append("      <category title=\"").append(xmlEscape(category)).append("\"/>\n");
                }
                xml.append("    </page>\n");
            }
            xml.append("  </pages>\n");
            xml.append("</wiki-cache>\n");
            writeUtf8Atomic(path, xml.toString());
        }

        private static List<Element> childElements(Element root, String containerName, String childName) {
            for (Element container : directChildren(root, containerName)) {
                return directChildren(container, childName);
            }
            return List.of();
        }

        private static List<Element> directChildren(Element parent, String tagName) {
            List<Element> result = new ArrayList<>();
            NodeList children = parent.getChildNodes();
            for (int index = 0; index < children.getLength(); index++) {
                Node child = children.item(index);
                if (child instanceof Element element && element.getTagName().equals(tagName)) {
                    result.add(element);
                }
            }
            return result;
        }
    }

    public record MappingDecision(
            String modelPath,
            String basename,
            boolean mapped,
            List<String> images,
            List<String> potentialPages,
            String pageTitle,
            String reason
    ) {
        public MappingDecision {
            images = images.stream().distinct().sorted().toList();
            potentialPages = potentialPages.stream().distinct().sorted().toList();
        }

        static MappingDecision mapped(
                String modelPath,
                String basename,
                String image,
                List<String> pages,
                String title
        ) {
            return new MappingDecision(modelPath, basename, true, List.of(image), pages, title, "mapped");
        }

        static MappingDecision review(
                String modelPath,
                String basename,
                List<String> images,
                List<String> pages,
                String reason
        ) {
            return new MappingDecision(modelPath, basename, false, images, pages, "", reason);
        }
    }

    public record MappingResult(
            int totalRecords,
            int uniqueModelPaths,
            int uniqueBasenames,
            int basenameCollisions,
            int matchedImageFiles,
            int mappedModelPaths,
            int mappedRecords,
            int missingImages,
            int ambiguousMatches,
            int manualReviewCount,
            Map<String, MappingDecision> decisionsByModelPath,
            Map<String, List<CatalogRecord>> recordsByModelPath,
            Map<String, List<String>> modelPathsByBasename,
            List<String> apiErrors
    ) {
        public MappingResult {
            decisionsByModelPath = Collections.unmodifiableMap(new TreeMap<>(decisionsByModelPath));
            Map<String, List<CatalogRecord>> recordCopy = new TreeMap<>();
            recordsByModelPath.forEach((modelPath, records) -> recordCopy.put(modelPath, List.copyOf(records)));
            recordsByModelPath = Collections.unmodifiableMap(recordCopy);
            Map<String, List<String>> pathCopy = new TreeMap<>();
            modelPathsByBasename.forEach((basename, paths) -> pathCopy.put(basename, List.copyOf(paths)));
            modelPathsByBasename = Collections.unmodifiableMap(pathCopy);
            apiErrors = List.copyOf(apiErrors);
        }

        List<CatalogRecord> recordsForModelPaths(Collection<String> modelPaths) {
            return modelPaths.stream()
                    .flatMap(modelPath -> recordsByModelPath.getOrDefault(modelPath, List.of()).stream())
                    .sorted(Comparator.comparing(CatalogRecord::modelPath)
                            .thenComparing(CatalogRecord::material)
                            .thenComparingInt(CatalogRecord::cmd))
                    .toList();
        }
    }

    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&apos;");
    }
}

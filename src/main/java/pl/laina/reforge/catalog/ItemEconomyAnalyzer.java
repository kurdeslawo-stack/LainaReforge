package pl.laina.reforge.catalog;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
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
 * Standalone, conservative acquisition analyzer for Wiki-mapped catalog entries.
 * This maintenance tool is deliberately not connected to the Paper plugin lifecycle.
 */
public final class ItemEconomyAnalyzer {
    public static final Path DEFAULT_CATALOG = Path.of("src/main/resources/items.yml");
    public static final Path DEFAULT_CACHE = Path.of("generated/item-economy-wiki-cache.xml");
    public static final Path DEFAULT_ANALYSIS = Path.of("generated/item-economy-analysis.yml");
    public static final Path DEFAULT_REPORT = Path.of("generated/item-economy-report.txt");
    public static final Path DEFAULT_MANUAL_REVIEW = Path.of("generated/item-economy-manual-review.yml");
    public static final String DEFAULT_API = "https://wiki.laina.pl/api.php";
    public static final long DEFAULT_DELAY_MILLIS = 250L;

    private static final int API_BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 2;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String USER_AGENT =
            "LainaReforge-ItemEconomyAnalyzer/1.0 (catalog maintenance; contact: repository maintainers)";
    private static final Pattern RECORD_KEY = Pattern.compile("^  \\\"([^\\\"]+)\\\":$");
    private static final Pattern STRING_FIELD = Pattern.compile("^    ([a-z_]+): \\\"(.*)\\\"$");
    private static final Pattern INTEGER_FIELD = Pattern.compile("^    ([a-z_]+): (-?[0-9]+)$");
    private static final Pattern HEADING = Pattern.compile("(?m)^\\s*(={2,6})\\s*(.*?)\\s*\\1\\s*$");
    private static final Pattern CATEGORY = Pattern.compile("(?iu)\\[\\[Kategoria:([^]\\n|]+)");
    private static final Pattern ACQUISITION_WORD = Pattern.compile(
            "(?iu)\\b(jak zdobyć|zdobyć|zdobycia|otrzymać|uzyskać|dostępn\\w*|nagrod\\w*)\\b");
    private static final Pattern UNAVAILABLE = Pattern.compile(
            "(?iu)\\b(aktualnie niedostępn\\w*|już niedostępn\\w*|wycofan\\w*|bezźródłow\\w*)\\b");
    private static final Pattern ONE_TIME = Pattern.compile(
            "(?iu)\\b(jednoraz\\w*|tylko raz|raz na (konto|gracza|postać)|jedna sztuka na)\\b");
    private static final Pattern LIMITED = Pattern.compile(
            "(?iu)\\b(limitowan\\w*|ograniczon\\w*|tylko podczas|wyłącznie podczas|aktualnie niedostępn\\w*|wycofan\\w*)\\b");
    private static final Pattern EXPLICIT_REPEATABLE_SOURCE = Pattern.compile(
            "(?iu)(?:"
                    + "(?:zdobyć|otrzymać|uzyskać|kupić|zakupić|wylosować)[^\\n.;]{0,100}"
                    + "(?:wielokrotnie|bez limitu|dowoln\\w+ liczb\\w*)"
                    + "|(?:boss|mob|przeciwnik)[^\\n.;]{0,100}"
                    + "można[^\\n.;]{0,60}(?:pokonać|zabić)[^\\n.;]{0,40}wielokrotnie"
                    + "|któreg\\w* można[^\\n.;]{0,40}(?:pokonać|zabić)[^\\n.;]{0,40}wielokrotnie"
                    + "|(?:źródł\\w*|drop|łup)[^\\n.;]{0,80}(?:odnawia się|regeneruj\\w*)"
                    + "|(?:stale|ciągle) dostępn\\w* (?:drop|łup|źródł\\w*)"
                    + ")");
    private static final Pattern LEAF_HARVEST_SOURCE = Pattern.compile(
            "(?iu)\\bzbierając\\b[^\\n.;]{0,80}\\bz liści\\b");
    private static final Pattern CURRENCY_EXCHANGE_SOURCE = Pattern.compile(
            "(?iu)\\bdostępn\\w* za \\d+[^\\n.;]{0,80}\\b(?:coin\\w*|monet\\w*|punkt\\w*)\\b");

    private ItemEconomyAnalyzer() {
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
            Catalog catalog = Catalog.parse(Files.readString(options.catalog(), StandardCharsets.UTF_8));
            EconomyCache cache = options.refresh() ? new EconomyCache() : EconomyCache.load(options.cache());
            List<String> apiErrors = new ArrayList<>();

            if (!options.offline()) {
                EconomyApiClient client = new EconomyApiClient(options.api(), options.delayMillis(), apiErrors);
                client.fetchMissingPages(cache, catalog.wikiTitles());
                cache.save(options.cache());
            } else {
                for (String title : catalog.wikiTitles()) {
                    if (cache.page(title) == null) {
                        apiErrors.add("offline cache miss: " + title);
                    }
                }
            }

            AnalysisResult result = analyze(catalog, cache, apiErrors);
            writeUtf8Atomic(options.analysis(), renderAnalysis(result));
            writeUtf8Atomic(options.report(), renderReport(result));
            writeUtf8Atomic(options.manualReview(), renderManualReview(result));

            out.printf(Locale.ROOT,
                    "Economy analysis: %d items, %d with acquisition data, %d UNKNOWN, manual review %d, API errors %d.%n",
                    result.totalItems(), result.withConcreteAcquisition(), result.unknownItems(),
                    result.manualReviewItems(), result.apiErrors().size());
            out.printf("Analysis: %s%nReport: %s%nManual review: %s%nCache: %s%n",
                    options.analysis(), options.report(), options.manualReview(), options.cache());
            return 0;
        } catch (IOException | IllegalArgumentException exception) {
            err.println("Item economy analysis failed: " + exception.getMessage());
            return 1;
        }
    }

    static AnalysisResult analyze(Catalog catalog, EconomyCache cache, List<String> apiErrors) {
        Map<String, ItemAnalysis> analyses = new TreeMap<>();
        for (Map.Entry<String, List<CatalogRecord>> entry : catalog.recordsByWiki().entrySet()) {
            String wiki = entry.getKey();
            List<CatalogRecord> records = entry.getValue();
            EconomyPage page = cache.page(wiki);
            analyses.put(wiki, analyzePage(wiki, records.getFirst().name(), records, page));
        }
        return new AnalysisResult(analyses, apiErrors);
    }

    static ItemAnalysis analyzePage(
            String wiki,
            String name,
            List<CatalogRecord> records,
            EconomyPage page
    ) {
        if (page == null || !page.exists() || page.wikitext().isBlank()) {
            return unknownAnalysis(wiki, name, records, "MISSING_PAGE_CONTENT");
        }

        List<String> categories = extractCategories(page.wikitext());
        List<String> evidence = extractAcquisitionEvidence(page.wikitext());
        String searchable = String.join("\n", evidence) + "\n" + String.join("\n", categories);
        Set<Source> sources = detectSources(searchable, evidence, categories);
        boolean unavailable = UNAVAILABLE.matcher(searchable).find();
        boolean oneTime = ONE_TIME.matcher(searchable).find();
        boolean limited = LIMITED.matcher(searchable).find();
        boolean explicitlyRepeatable = EXPLICIT_REPEATABLE_SOURCE.matcher(searchable).find();
        boolean renewable = explicitlyRepeatable
                || sources.contains(Source.FISHING) || sources.contains(Source.FARMING);
        boolean ordinarySupplyMethod = sources.contains(Source.SHOP) || sources.contains(Source.CRAFT)
                || sources.contains(Source.DROP) || sources.contains(Source.FISHING) || sources.contains(Source.FARMING);
        boolean mixedImpact = ordinarySupplyMethod && (limited || oneTime || sources.contains(Source.EVENT)
                || sources.contains(Source.KEY_REWARD) || sources.contains(Source.QUEST));
        boolean contradiction = oneTime && (explicitlyRepeatable || renewable);

        Set<SupplyTag> tags = new TreeSet<>(Comparator.comparing(Enum::name));
        sources.forEach(source -> tags.add(source.tag));
        if (oneTime) {
            tags.add(SupplyTag.ONE_TIME);
        }
        if (limited || unavailable) {
            tags.add(SupplyTag.LIMITED);
        }
        if (explicitlyRepeatable || renewable) {
            tags.add(SupplyTag.REPEATABLE);
        }
        if (renewable && !limited && !oneTime) {
            tags.add(SupplyTag.INFINITE_OR_FARMABLE);
        }
        if (tags.isEmpty()) {
            tags.add(SupplyTag.UNKNOWN);
        }

        List<String> reviewReasons = new ArrayList<>();
        if (evidence.isEmpty()) {
            reviewReasons.add("INSUFFICIENT_DATA");
        } else if (sources.isEmpty() && !unavailable && !limited && !oneTime) {
            reviewReasons.add("UNCLEAR_SOURCE");
        }
        if (mixedImpact) {
            reviewReasons.add("MIXED_SUPPLY_IMPACT");
        }
        if (contradiction) {
            reviewReasons.add("CONTRADICTORY_INFORMATION");
        }

        Proposal proposal;
        if (tags.contains(SupplyTag.INFINITE_OR_FARMABLE) && !mixedImpact && !contradiction) {
            proposal = new Proposal(ProposalValue.NO, Confidence.MEDIUM,
                    "System wykrył odnawialne lub farmowalne źródło; wymagana jest ostrożność ekonomiczna.");
        } else {
            proposal = new Proposal(ProposalValue.UNKNOWN, Confidence.LOW,
                    "Dane o zdobyciu nie wystarczają do bezpiecznej decyzji o recyclingu.");
        }

        String summary = evidence.isEmpty()
                ? "Brak jednoznacznej informacji o zdobyciu na stronie Wiki."
                : String.join("; ", evidence);
        return new ItemAnalysis(
                wiki,
                name,
                records,
                summary,
                sources,
                state(explicitlyRepeatable || renewable),
                state(renewable),
                state(sources.contains(Source.KEY_REWARD)),
                state(sources.contains(Source.EVENT)),
                state(sources.contains(Source.QUEST)),
                state(sources.contains(Source.SHOP)),
                state(sources.contains(Source.CRAFT)),
                state(sources.contains(Source.DROP)),
                state(limited),
                state(oneTime),
                evidence,
                tags,
                proposal,
                reviewReasons);
    }

    private static ItemAnalysis unknownAnalysis(
            String wiki,
            String name,
            List<CatalogRecord> records,
            String reason
    ) {
        return new ItemAnalysis(
                wiki, name, records, "Brak danych strony w cache Wiki.", Set.of(),
                FactState.UNKNOWN, FactState.UNKNOWN, FactState.UNKNOWN, FactState.UNKNOWN,
                FactState.UNKNOWN, FactState.UNKNOWN, FactState.UNKNOWN, FactState.UNKNOWN,
                FactState.UNKNOWN, FactState.UNKNOWN, List.of(), Set.of(SupplyTag.UNKNOWN),
                new Proposal(ProposalValue.UNKNOWN, Confidence.LOW,
                        "Brak danych Wiki do przygotowania propozycji."),
                List.of(reason));
    }

    private static FactState state(boolean positive) {
        return positive ? FactState.TRUE : FactState.UNKNOWN;
    }

    private static Set<Source> detectSources(
            String searchable,
            List<String> evidence,
            List<String> categories
    ) {
        String normalized = searchable.toLowerCase(Locale.ROOT);
        Set<Source> result = new TreeSet<>(Comparator.comparing(Enum::name));
        if (containsAny(normalized, "klucz", "ze skrzyni", "z automatu", "wylosowa")) {
            result.add(Source.KEY_REWARD);
        }
        if (containsAny(normalized, "event", "wydarzeni")
                || categories.stream().anyMatch(category -> normalize(category).contains("eventow"))) {
            result.add(Source.EVENT);
        }
        if (containsAny(normalized, "quest", "zadani")) {
            result.add(Source.QUEST);
        }
        if (containsAny(normalized, "sklep", "shop", "kupić", "zakupi", "do kupienia", "/sklep")
                || CURRENCY_EXCHANGE_SOURCE.matcher(searchable).find()) {
            result.add(Source.SHOP);
        }
        if (containsAny(normalized, "craft", "receptur", "stworzyć", "wytworzyć", "stole kowalskim" )
                || categories.stream().anyMatch(category -> normalize(category).contains("craftowal"))) {
            result.add(Source.CRAFT);
        }
        if (containsAny(normalized, "drop", "wypada", "wypaść", "pokonani", "pokonać", "zabici", "zabicie", "łup")) {
            result.add(Source.DROP);
        }
        if (containsAny(normalized, "łowić", "łowiąc", "złowić", "z łowienia", "połów", "połow")) {
            result.add(Source.FISHING);
        }
        if (containsAny(normalized, "zbierając posadzone", "zbierać posadzone", "z upraw", "z hodowli")
                || LEAF_HARVEST_SOURCE.matcher(searchable).find()) {
            result.add(Source.FARMING);
        }
        return Collections.unmodifiableSet(result);
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    static List<String> extractAcquisitionEvidence(String wikitext) {
        Matcher headingMatcher = HEADING.matcher(wikitext);
        List<HeadingRange> headings = new ArrayList<>();
        while (headingMatcher.find()) {
            headings.add(new HeadingRange(headingMatcher.start(), headingMatcher.end(), cleanText(headingMatcher.group(2))));
        }

        LinkedHashSet<String> evidence = new LinkedHashSet<>();
        for (int index = 0; index < headings.size(); index++) {
            HeadingRange heading = headings.get(index);
            String normalizedHeading = normalize(heading.title());
            if (!normalizedHeading.matches(".*(jak zdobyc|zdobywanie|zdobycie|sposob zdobycia).*")) {
                continue;
            }
            int end = index + 1 < headings.size() ? headings.get(index + 1).start() : wikitext.length();
            addEvidenceLines(evidence, wikitext.substring(heading.end(), end));
        }

        int firstHeading = headings.isEmpty() ? wikitext.length() : headings.getFirst().start();
        for (String line : wikitext.substring(0, firstHeading).split("\\R")) {
            String cleaned = cleanText(line);
            if (!cleaned.isBlank() && ACQUISITION_WORD.matcher(cleaned).find()) {
                evidence.add(cleaned);
            }
        }
        return evidence.stream().filter(value -> !value.isBlank()).limit(20).toList();
    }

    private static void addEvidenceLines(Set<String> evidence, String section) {
        for (String line : section.split("\\R")) {
            String cleaned = cleanText(line.replaceFirst("^\\s*[*#:;]+\\s*", ""));
            if (!cleaned.isBlank() && !cleaned.toLowerCase(Locale.ROOT).startsWith("autor")) {
                evidence.add(cleaned);
            }
        }
    }

    private static List<String> extractCategories(String wikitext) {
        Set<String> categories = new TreeSet<>();
        Matcher matcher = CATEGORY.matcher(wikitext);
        while (matcher.find()) {
            categories.add(cleanText(matcher.group(1)));
        }
        return List.copyOf(categories);
    }

    static String cleanText(String value) {
        String result = value;
        result = result.replaceAll("(?is)<!--.*?-->", " ");
        result = result.replaceAll("(?iu)\\[\\[(?:Plik|File):[^]]+]]", " ");
        result = result.replaceAll("(?iu)\\[\\[Kategoria:[^]]+]]", " ");
        result = result.replaceAll("\\[\\[[^]|]+\\|([^]]+)]]", "$1");
        result = result.replaceAll("\\[\\[([^]]+)]]", "$1");
        result = result.replaceAll("(?s)\\{\\{.*?}}", " ");
        result = result.replaceAll("<br\\s*/?>", " ");
        result = result.replaceAll("<[^>]+>", " ");
        result = result.replace("'''", "").replace("''", "");
        result = result.replace("&nbsp;", " ").replace("&amp;", "&");
        return result.replaceAll("\\s+", " ").trim();
    }

    static String renderAnalysis(AnalysisResult result) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("# Generated from Wiki Laina.PL by ItemEconomyAnalyzer. Proposals are not final decisions.\n");
        yaml.append("items:\n");
        for (ItemAnalysis item : result.items().values()) {
            yaml.append("  ").append(yamlQuote(item.wiki())).append(":\n");
            yaml.append("    wiki: ").append(yamlQuote(item.wiki())).append("\n");
            yaml.append("    name: ").append(yamlQuote(item.name())).append("\n");
            yaml.append("    catalog_records:\n");
            for (CatalogRecord record : item.records()) {
                yaml.append("      - key: ").append(yamlQuote(record.key())).append("\n");
                yaml.append("        material: ").append(yamlQuote(record.material())).append("\n");
                yaml.append("        cmd: ").append(record.cmd()).append("\n");
                yaml.append("        model_path: ").append(yamlQuote(record.modelPath())).append("\n");
            }
            yaml.append("    acquisition:\n");
            yaml.append("      summary: ").append(yamlQuote(item.summary())).append("\n");
            yaml.append("      sources:");
            appendInlineList(yaml, item.sources().stream().map(Source::yamlName).toList());
            yaml.append("      repeatable: ").append(item.repeatable().yaml()).append("\n");
            yaml.append("      renewable: ").append(item.renewable().yaml()).append("\n");
            yaml.append("      from_key: ").append(item.fromKey().yaml()).append("\n");
            yaml.append("      from_event: ").append(item.fromEvent().yaml()).append("\n");
            yaml.append("      from_quest: ").append(item.fromQuest().yaml()).append("\n");
            yaml.append("      from_shop: ").append(item.fromShop().yaml()).append("\n");
            yaml.append("      craftable: ").append(item.craftable().yaml()).append("\n");
            yaml.append("      from_drop: ").append(item.fromDrop().yaml()).append("\n");
            yaml.append("      limited: ").append(item.limited().yaml()).append("\n");
            yaml.append("      one_time: ").append(item.oneTime().yaml()).append("\n");
            yaml.append("    evidence:\n");
            if (item.evidence().isEmpty()) {
                yaml.append("      []\n");
            } else {
                for (String evidence : item.evidence()) {
                    yaml.append("      - ").append(yamlQuote("Wiki: " + evidence)).append("\n");
                }
            }
            yaml.append("    system_inference:\n");
            yaml.append("      supply_tags:");
            appendInlineList(yaml, item.tags().stream().map(Enum::name).toList());
            yaml.append("    proposal:\n");
            yaml.append("      recyclable:\n");
            yaml.append("        value: ").append(item.proposal().value()).append("\n");
            yaml.append("        confidence: ").append(item.proposal().confidence()).append("\n");
            yaml.append("        reason: ").append(yamlQuote(item.proposal().reason())).append("\n");
        }
        return yaml.toString();
    }

    static String renderReport(AnalysisResult result) {
        StringBuilder report = new StringBuilder();
        report.append("Item Economy Analysis Report\n============================\n\n");
        report.append("Analyzed unique items: ").append(result.totalItems()).append('\n');
        report.append("Items with concrete acquisition data: ").append(result.withConcreteAcquisition()).append('\n');
        report.append("Items with UNKNOWN acquisition: ").append(result.unknownItems()).append('\n');
        report.append("Manual-review items: ").append(result.manualReviewItems()).append("\n\n");
        report.append("Supply tag distribution\n-----------------------\n");
        for (SupplyTag tag : SupplyTag.values()) {
            report.append("- ").append(tag).append(": ").append(result.tagCounts().get(tag)).append('\n');
        }
        report.append("\nProposal distribution\n---------------------\n");
        for (ProposalValue value : ProposalValue.values()) {
            report.append("- ").append(value).append(": ").append(result.proposalCounts().get(value)).append('\n');
        }
        report.append("\nHTTP/API errors\n---------------\n");
        if (result.apiErrors().isEmpty()) {
            report.append("- none\n");
        } else {
            result.apiErrors().forEach(error -> report.append("- ").append(error).append('\n'));
        }
        return report.toString();
    }

    static String renderManualReview(AnalysisResult result) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("# Generated manual-review queue for acquisition/economy analysis.\nitems:\n");
        for (ItemAnalysis item : result.items().values()) {
            if (item.reviewReasons().isEmpty()) {
                continue;
            }
            yaml.append("  - wiki: ").append(yamlQuote(item.wiki())).append('\n');
            yaml.append("    name: ").append(yamlQuote(item.name())).append('\n');
            yaml.append("    records:\n");
            for (CatalogRecord record : item.records()) {
                yaml.append("      - ").append(yamlQuote(record.key() + " (" + record.modelPath() + ")")).append('\n');
            }
            yaml.append("    reasons:");
            appendInlineList(yaml, item.reviewReasons());
            yaml.append("    evidence:");
            appendInlineList(yaml, item.evidence());
        }
        return yaml.toString();
    }

    private static void appendInlineList(StringBuilder yaml, Collection<String> values) {
        if (values.isEmpty()) {
            yaml.append(" []\n");
            return;
        }
        yaml.append('\n');
        for (String value : values) {
            yaml.append("        - ").append(yamlQuote(value)).append('\n');
        }
    }

    private static String yamlQuote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static String normalize(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('_', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
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

    public record Options(
            Path catalog,
            Path cache,
            Path analysis,
            Path report,
            Path manualReview,
            String api,
            long delayMillis,
            boolean refresh,
            boolean offline
    ) {
        static Options parse(String[] args) {
            Path catalog = DEFAULT_CATALOG;
            Path cache = DEFAULT_CACHE;
            Path analysis = DEFAULT_ANALYSIS;
            Path report = DEFAULT_REPORT;
            Path manual = DEFAULT_MANUAL_REVIEW;
            String api = DEFAULT_API;
            long delay = DEFAULT_DELAY_MILLIS;
            boolean refresh = false;
            boolean offline = false;
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--catalog" -> catalog = Path.of(requireValue(args, ++index, "--catalog"));
                    case "--cache" -> cache = Path.of(requireValue(args, ++index, "--cache"));
                    case "--analysis" -> analysis = Path.of(requireValue(args, ++index, "--analysis"));
                    case "--report" -> report = Path.of(requireValue(args, ++index, "--report"));
                    case "--manual-review" -> manual = Path.of(requireValue(args, ++index, "--manual-review"));
                    case "--api" -> api = requireValue(args, ++index, "--api");
                    case "--delay-ms" -> delay = Long.parseLong(requireValue(args, ++index, "--delay-ms"));
                    case "--refresh" -> refresh = true;
                    case "--offline" -> offline = true;
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[index]);
                }
            }
            if (refresh && offline) {
                throw new IllegalArgumentException("--refresh and --offline cannot be used together");
            }
            if (delay < 0) {
                throw new IllegalArgumentException("--delay-ms must not be negative");
            }
            return new Options(catalog, cache, analysis, report, manual, api, delay, refresh, offline);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        static String usage() {
            return "Usage: ItemEconomyAnalyzer [--catalog path] [--cache path] [--analysis path] "
                    + "[--report path] [--manual-review path] [--api url] [--delay-ms n] [--refresh|--offline]";
        }
    }

    static final class Catalog {
        private final List<CatalogRecord> records;

        Catalog(List<CatalogRecord> records) {
            this.records = records.stream()
                    .sorted(Comparator.comparing(CatalogRecord::key))
                    .toList();
        }

        static Catalog parse(String yaml) {
            List<CatalogRecord> records = new ArrayList<>();
            RecordBuilder current = null;
            for (String line : yaml.split("\\R")) {
                Matcher key = RECORD_KEY.matcher(line);
                if (key.matches()) {
                    if (current != null) {
                        records.add(current.build());
                    }
                    current = new RecordBuilder(key.group(1));
                    continue;
                }
                if (current == null) {
                    continue;
                }
                Matcher string = STRING_FIELD.matcher(line);
                if (string.matches()) {
                    current.stringField(string.group(1), unescape(string.group(2)));
                    continue;
                }
                Matcher integer = INTEGER_FIELD.matcher(line);
                if (integer.matches()) {
                    current.integerField(integer.group(1), integer.group(2));
                }
            }
            if (current != null) {
                records.add(current.build());
            }
            if (records.isEmpty()) {
                throw new IllegalArgumentException("items.yml contains no records");
            }
            return new Catalog(records);
        }

        Set<String> wikiTitles() {
            Set<String> titles = new TreeSet<>();
            records.stream().filter(CatalogRecord::mapped).forEach(record -> titles.add(record.wiki()));
            return titles;
        }

        Map<String, List<CatalogRecord>> recordsByWiki() {
            Map<String, List<CatalogRecord>> grouped = new TreeMap<>();
            for (CatalogRecord record : records) {
                if (record.mapped()) {
                    grouped.computeIfAbsent(record.wiki(), ignored -> new ArrayList<>()).add(record);
                }
            }
            grouped.replaceAll((ignored, value) -> value.stream()
                    .sorted(Comparator.comparing(CatalogRecord::key)).toList());
            return grouped;
        }

        List<CatalogRecord> records() {
            return records;
        }

        private static String unescape(String value) {
            return value.replace("\\\"", "\\\"").replace("\\\\", "\\");
        }
    }

    public record CatalogRecord(
            String key,
            String material,
            int cmd,
            String modelPath,
            String wiki,
            String name,
            int shards
    ) {
        boolean mapped() {
            return !wiki.isBlank() && !name.isBlank();
        }
    }

    private static final class RecordBuilder {
        private final String key;
        private String material;
        private Integer cmd;
        private String modelPath;
        private String wiki = "";
        private String name = "";
        private Integer shards;

        private RecordBuilder(String key) {
            this.key = key;
        }

        private void stringField(String field, String value) {
            switch (field) {
                case "material" -> material = value;
                case "model_path" -> modelPath = value;
                case "wiki" -> wiki = value;
                case "name" -> name = value;
                default -> { }
            }
        }

        private void integerField(String field, String value) {
            try {
                if (field.equals("cmd")) {
                    cmd = Integer.parseInt(value);
                } else if (field.equals("shards")) {
                    shards = Integer.parseInt(value);
                }
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid " + field + " in " + key, exception);
            }
        }

        private CatalogRecord build() {
            if (material == null || cmd == null || modelPath == null || shards == null) {
                throw new IllegalArgumentException("Incomplete catalog record: " + key);
            }
            if (wiki.isBlank() != name.isBlank()) {
                throw new IllegalArgumentException("Partial wiki/name mapping: " + key);
            }
            return new CatalogRecord(key, material, cmd, modelPath, wiki, name, shards);
        }
    }

    public record EconomyPage(String title, boolean exists, long revisionId, String timestamp, String wikitext) {
    }

    static final class EconomyCache {
        private final Map<String, EconomyPage> pages = new TreeMap<>();

        EconomyPage page(String requestedTitle) {
            return pages.get(normalizeTitle(requestedTitle));
        }

        void put(EconomyPage page) {
            pages.put(normalizeTitle(page.title()), page);
        }

        static EconomyCache load(Path path) throws IOException {
            EconomyCache cache = new EconomyCache();
            if (!Files.exists(path)) {
                return cache;
            }
            Document document = WikiCatalogMapper.parseXml(Files.readAllBytes(path));
            Element root = document.getDocumentElement();
            if (!root.getTagName().equals("item-economy-wiki-cache") || !root.getAttribute("version").equals("1")) {
                throw new IOException("unsupported item economy cache format");
            }
            NodeList nodes = root.getElementsByTagName("page");
            for (int index = 0; index < nodes.getLength(); index++) {
                Element page = (Element) nodes.item(index);
                String encoded = page.getTextContent().trim();
                String content = encoded.isEmpty() ? "" : new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                cache.put(new EconomyPage(
                        page.getAttribute("title"),
                        Boolean.parseBoolean(page.getAttribute("exists")),
                        parseLong(page.getAttribute("revision-id")),
                        page.getAttribute("timestamp"),
                        content));
            }
            return cache;
        }

        void save(Path path) throws IOException {
            StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            xml.append("<item-economy-wiki-cache version=\"1\">\n");
            for (EconomyPage page : pages.values()) {
                xml.append("  <page title=\"").append(xmlEscape(page.title())).append("\" exists=\"")
                        .append(page.exists()).append("\" revision-id=\"").append(page.revisionId())
                        .append("\" timestamp=\"").append(xmlEscape(page.timestamp())).append("\">");
                if (!page.wikitext().isEmpty()) {
                    xml.append(Base64.getEncoder().encodeToString(page.wikitext().getBytes(StandardCharsets.UTF_8)));
                }
                xml.append("</page>\n");
            }
            xml.append("</item-economy-wiki-cache>\n");
            writeUtf8Atomic(path, xml.toString());
        }

        private static long parseLong(String value) {
            try {
                return value.isBlank() ? 0L : Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
    }

    private static final class EconomyApiClient {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        private final String endpoint;
        private final long delayMillis;
        private final List<String> errors;
        private long lastRequestNanos;

        private EconomyApiClient(String endpoint, long delayMillis, List<String> errors) {
            this.endpoint = endpoint;
            this.delayMillis = delayMillis;
            this.errors = errors;
        }

        private void fetchMissingPages(EconomyCache cache, Collection<String> titles) {
            List<String> missing = titles.stream().filter(title -> cache.page(title) == null).sorted().toList();
            for (int start = 0; start < missing.size(); start += API_BATCH_SIZE) {
                fetchBatch(cache, missing.subList(start, Math.min(start + API_BATCH_SIZE, missing.size())));
            }
        }

        private void fetchBatch(EconomyCache cache, List<String> titles) {
            Map<String, String> requested = new TreeMap<>();
            titles.forEach(title -> requested.put(normalizeTitle(title), title));
            Map<String, String> parameters = new TreeMap<>();
            parameters.put("action", "query");
            parameters.put("prop", "revisions");
            parameters.put("rvprop", "ids|timestamp|content");
            parameters.put("rvslots", "main");
            parameters.put("titles", String.join("|", titles));
            parameters.put("format", "xml");
            parameters.put("maxlag", "5");
            parameters.put("utf8", "1");
            try {
                Document document = request(parameters);
                NodeList pageNodes = document.getElementsByTagName("page");
                Set<String> received = new TreeSet<>();
                for (int index = 0; index < pageNodes.getLength(); index++) {
                    Element page = (Element) pageNodes.item(index);
                    String title = page.getAttribute("title");
                    String normalized = normalizeTitle(title);
                    String requestedTitle = requested.getOrDefault(normalized, title.replace(' ', '_'));
                    received.add(normalizeTitle(requestedTitle));
                    boolean exists = !page.hasAttribute("missing") && !page.hasAttribute("invalid");
                    NodeList revisions = page.getElementsByTagName("rev");
                    NodeList slots = page.getElementsByTagName("slot");
                    long revisionId = revisions.getLength() == 0 ? 0L
                            : EconomyCache.parseLong(((Element) revisions.item(0)).getAttribute("revid"));
                    String timestamp = revisions.getLength() == 0 ? ""
                            : ((Element) revisions.item(0)).getAttribute("timestamp");
                    String content = slots.getLength() == 0 ? "" : slots.item(0).getTextContent();
                    cache.put(new EconomyPage(requestedTitle, exists, revisionId, timestamp, content));
                }
                for (String title : titles) {
                    if (!received.contains(normalizeTitle(title))) {
                        cache.put(new EconomyPage(title, false, 0L, "", ""));
                    }
                }
            } catch (IOException exception) {
                errors.add("pages [" + titles.getFirst() + " ...]: " + exception.getMessage());
            }
        }

        private Document request(Map<String, String> parameters) throws IOException {
            String query = parameters.entrySet().stream()
                    .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining("&"));
            URI uri = URI.create(endpoint + "?" + query);
            IOException lastFailure = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                rateLimit();
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(REQUEST_TIMEOUT)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/xml")
                        .GET().build();
                try {
                    HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() == 200) {
                        Document document = WikiCatalogMapper.parseXml(response.body());
                        NodeList apiErrorNodes = document.getElementsByTagName("error");
                        if (apiErrorNodes.getLength() > 0) {
                            Element apiError = (Element) apiErrorNodes.item(0);
                            throw new IOException("API " + apiError.getAttribute("code")
                                    + ": " + apiError.getAttribute("info"));
                        }
                        return document;
                    }
                    lastFailure = new IOException("HTTP " + response.statusCode());
                    if (response.statusCode() != 429 && response.statusCode() < 500) {
                        throw lastFailure;
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("request interrupted", exception);
                } catch (IOException exception) {
                    lastFailure = exception;
                }
                if (attempt < MAX_ATTEMPTS) {
                    pause(Math.max(1000L, delayMillis));
                }
            }
            throw lastFailure == null ? new IOException("unknown HTTP failure") : lastFailure;
        }

        private void rateLimit() throws IOException {
            if (lastRequestNanos != 0L && delayMillis > 0L) {
                long remaining = Duration.ofMillis(delayMillis).toNanos() - (System.nanoTime() - lastRequestNanos);
                if (remaining > 0L) {
                    pause(Duration.ofNanos(remaining).toMillis() + 1L);
                }
            }
            lastRequestNanos = System.nanoTime();
        }

        private static void pause(long millis) throws IOException {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("rate-limit wait interrupted", exception);
            }
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }

    private static String normalizeTitle(String value) {
        return value.replace('_', ' ').replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;");
    }

    private record HeadingRange(int start, int end, String title) {
    }

    public enum FactState {
        TRUE("true"), FALSE("false"), UNKNOWN("UNKNOWN");

        private final String yaml;

        FactState(String yaml) {
            this.yaml = yaml;
        }

        String yaml() {
            return yaml;
        }
    }

    public enum SupplyTag {
        INFINITE_OR_FARMABLE, REPEATABLE, LIMITED, ONE_TIME, KEY_REWARD,
        EVENT, QUEST, SHOP, CRAFT, DROP, UNKNOWN
    }

    enum Source {
        KEY_REWARD("key", SupplyTag.KEY_REWARD),
        EVENT("event", SupplyTag.EVENT),
        QUEST("quest", SupplyTag.QUEST),
        SHOP("shop", SupplyTag.SHOP),
        CRAFT("craft", SupplyTag.CRAFT),
        DROP("drop", SupplyTag.DROP),
        FISHING("fishing", SupplyTag.INFINITE_OR_FARMABLE),
        FARMING("farming", SupplyTag.INFINITE_OR_FARMABLE);

        private final String yamlName;
        private final SupplyTag tag;

        Source(String yamlName, SupplyTag tag) {
            this.yamlName = yamlName;
            this.tag = tag;
        }

        String yamlName() {
            return yamlName;
        }
    }

    public enum ProposalValue { YES, NO, UNKNOWN }

    public enum Confidence { HIGH, MEDIUM, LOW }

    public record Proposal(ProposalValue value, Confidence confidence, String reason) {
    }

    public record ItemAnalysis(
            String wiki,
            String name,
            List<CatalogRecord> records,
            String summary,
            Set<Source> sources,
            FactState repeatable,
            FactState renewable,
            FactState fromKey,
            FactState fromEvent,
            FactState fromQuest,
            FactState fromShop,
            FactState craftable,
            FactState fromDrop,
            FactState limited,
            FactState oneTime,
            List<String> evidence,
            Set<SupplyTag> tags,
            Proposal proposal,
            List<String> reviewReasons
    ) {
        public ItemAnalysis {
            records = List.copyOf(records);
            sources = Collections.unmodifiableSet(new TreeSet<>(sources));
            evidence = List.copyOf(evidence);
            tags = Collections.unmodifiableSet(new TreeSet<>(tags));
            reviewReasons = reviewReasons.stream().distinct().sorted().toList();
        }

        boolean concreteAcquisition() {
            return !sources.isEmpty();
        }
    }

    public record AnalysisResult(Map<String, ItemAnalysis> items, List<String> apiErrors) {
        public AnalysisResult {
            items = Collections.unmodifiableMap(new TreeMap<>(items));
            apiErrors = List.copyOf(apiErrors);
        }

        int totalItems() {
            return items.size();
        }

        int withConcreteAcquisition() {
            return (int) items.values().stream().filter(ItemAnalysis::concreteAcquisition).count();
        }

        int unknownItems() {
            return totalItems() - withConcreteAcquisition();
        }

        int manualReviewItems() {
            return (int) items.values().stream().filter(item -> !item.reviewReasons().isEmpty()).count();
        }

        Map<SupplyTag, Integer> tagCounts() {
            Map<SupplyTag, Integer> counts = new EnumMap<>(SupplyTag.class);
            for (SupplyTag tag : SupplyTag.values()) {
                counts.put(tag, 0);
            }
            items.values().forEach(item -> item.tags().forEach(tag -> counts.put(tag, counts.get(tag) + 1)));
            return counts;
        }

        Map<ProposalValue, Integer> proposalCounts() {
            Map<ProposalValue, Integer> counts = new EnumMap<>(ProposalValue.class);
            for (ProposalValue value : ProposalValue.values()) {
                counts.put(value, 0);
            }
            items.values().forEach(item -> counts.put(item.proposal().value(), counts.get(item.proposal().value()) + 1));
            return counts;
        }
    }
}

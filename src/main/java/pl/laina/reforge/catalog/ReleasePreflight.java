package pl.laina.reforge.catalog;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import pl.laina.reforge.runtime.ApprovedRecyclingRegistryLoader;

/** Offline, read-only release consistency check using the production parsers. */
public final class ReleasePreflight {
    public static final String RELEASE_VERSION = "0.1.0-rc1";

    private static final List<String> REQUIRED_FILES = List.of(
            "pom.xml",
            "src/main/resources/plugin.yml",
            "src/main/resources/config.yml",
            "src/main/resources/items.yml",
            "src/main/resources/recycling-runtime.yml",
            "generated/item-catalog-snapshot.yml",
            "generated/recycling-decision-queue.yml",
            "generated/recycling-review-panel/index.html",
            "recycling-decisions.yml"
    );
    private static final Pattern MAVEN_VERSION = Pattern.compile(
            "<version>\\s*([^<]+)\\s*</version>");
    private static final Pattern PLUGIN_VERSION = Pattern.compile("(?m)^version:\\s*['\"]?([^'\"\\s]+)");

    private ReleasePreflight() {
    }

    public static void main(String[] args) {
        Path root = args.length == 0 ? Path.of(".") : Path.of(args[0]);
        Result result = inspect(root);
        print(result, System.out);
        System.exit(result.passed() ? 0 : 1);
    }

    static Result inspect(Path root) {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Map<String, Check> checks = new LinkedHashMap<>();
        List<String> details = new ArrayList<>();

        check(checks, "Java", details, () -> {
            int feature = Runtime.version().feature();
            require(feature >= 25, "Wymagana Java 25 lub nowsza; wykryto " + feature + ".");
            return "Java " + feature;
        });
        check(checks, "Repository", details, () -> {
            for (String required : REQUIRED_FILES) {
                require(Files.isRegularFile(absoluteRoot.resolve(required)), "Brak pliku: " + required);
            }
            return REQUIRED_FILES.size() + " wymaganych plików";
        });

        Holder context = new Holder();
        check(checks, "Catalog", details, () -> {
            context.catalog = ItemEconomyAnalyzer.Catalog.parse(read(absoluteRoot,
                    "src/main/resources/items.yml"));
            require(context.catalog.records().size() == 1757,
                    "Katalog ma " + context.catalog.records().size() + " rekordów zamiast 1757.");
            return context.catalog.records().size() + " identities";
        });
        check(checks, "Queue", details, () -> {
            context.queue = RecyclingReviewPanelGenerator.parseQueue(read(absoluteRoot,
                    "generated/recycling-decision-queue.yml"));
            require(context.catalog != null, "Nie można porównać kolejki bez poprawnego katalogu.");
            var validation = RecyclingDecisionQueueValidator.validate(
                    context.queue, Map.of(), context.catalog.records());
            require(validation.valid(), "Walidacja kolejki: " + validation.errors());
            require(context.queue.identityCount() == context.catalog.records().size(),
                    "Kolejka nie pokrywa całego katalogu.");
            return context.queue.items().size() + " logical / " + context.queue.identityCount() + " identities";
        });
        check(checks, "Panel", details, () -> {
            require(context.queue != null, "Nie można sprawdzić panelu bez poprawnej kolejki.");
            String html = read(absoluteRoot, "generated/recycling-review-panel/index.html");
            List<String> errors = RecyclingReviewPanelGenerator.selfCheck(context.queue, html);
            require(errors.isEmpty(), String.join("; ", errors));
            require(!html.contains("<script src=") && !html.contains("https://cdn")
                            && !html.contains("http://cdn"),
                    "Panel zawiera zewnętrzny skrypt lub CDN.");
            return "self-contained, fingerprint zgodny";
        });
        check(checks, "Snapshot", details, () -> {
            var snapshot = CatalogEvolutionUpdater.parseSnapshot(read(absoluteRoot,
                    "generated/item-catalog-snapshot.yml"));
            require(context.catalog != null, "Nie można porównać snapshotu bez poprawnego katalogu.");
            require(snapshot.items().size() == context.catalog.records().size(),
                    "Snapshot i katalog mają różną liczbę identities.");
            return snapshot.items().size() + " identities";
        });
        check(checks, "Decisions", details, () -> {
            require(context.queue != null, "Nie można sprawdzić decyzji bez poprawnej kolejki.");
            var known = context.queue.items().stream()
                    .map(RecyclingDecisionQueueGenerator.QueueItem::logicalId)
                    .collect(java.util.stream.Collectors.toSet());
            var decisions = RecyclingReviewPanelGenerator.parseDecisionImport(
                    read(absoluteRoot, "recycling-decisions.yml"), known);
            context.compilation = RecyclingRuntimeCompiler.compile(context.queue, decisions);
            return decisions.size() + " decyzji / " + context.compilation.registry().size() + " identities";
        });
        check(checks, "Runtime", details, () -> {
            var candidate = new ApprovedRecyclingRegistryLoader().validate(
                    absoluteRoot.resolve("src/main/resources/recycling-runtime.yml"));
            require(candidate.valid(), "Runtime: " + candidate.errors());
            return candidate.registry().size() + " identities";
        });
        check(checks, "Plugin metadata", details, () -> {
            String pom = read(absoluteRoot, "pom.xml");
            String plugin = read(absoluteRoot, "src/main/resources/plugin.yml");
            Matcher maven = MAVEN_VERSION.matcher(pom);
            Matcher pluginYaml = PLUGIN_VERSION.matcher(plugin);
            require(maven.find() && RELEASE_VERSION.equals(maven.group(1).trim()),
                    "pom.xml nie deklaruje " + RELEASE_VERSION + ".");
            require(pluginYaml.find() && RELEASE_VERSION.equals(pluginYaml.group(1)),
                    "plugin.yml nie deklaruje " + RELEASE_VERSION + ".");
            require(plugin.contains("name: LainaReforge")
                            && plugin.contains("main: pl.laina.reforge.LainaReforgePlugin")
                            && plugin.contains("api-version: '26.2'"),
                    "Niepełne metadane plugin.yml.");
            return RELEASE_VERSION + " / Paper 26.2";
        });
        return new Result(checks, details);
    }

    static void print(Result result, PrintStream out) {
        out.println("================================");
        out.println("LainaReforge Release Preflight");
        out.println("================================");
        out.println();
        result.checks().forEach((name, value) -> out.println(name + ": " + (value.passed() ? "PASS" : "FAIL")));
        if (!result.details().isEmpty()) {
            out.println();
            result.details().forEach(detail -> out.println("- " + detail));
        }
        out.println();
        out.println("RELEASE PREFLIGHT: " + (result.passed() ? "PASS" : "FAIL"));
    }

    private static void check(Map<String, Check> checks, String name, List<String> details,
                              CheckedSupplier action) {
        try {
            checks.put(name, new Check(true, action.get()));
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            checks.put(name, new Check(false, message));
            details.add(name + ": " + message);
        }
    }

    private static String read(Path root, String relative) throws IOException {
        return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier {
        String get() throws Exception;
    }

    private static final class Holder {
        private ItemEconomyAnalyzer.Catalog catalog;
        private RecyclingDecisionQueueGenerator.DecisionQueue queue;
        private RecyclingRuntimeCompiler.Compilation compilation;
    }

    public record Check(boolean passed, String detail) {
    }

    public record Result(Map<String, Check> checks, List<String> details) {
        public Result {
            checks = Collections.unmodifiableMap(new LinkedHashMap<>(checks));
            details = List.copyOf(details);
        }

        public boolean passed() {
            return checks.values().stream().allMatch(Check::passed);
        }
    }
}

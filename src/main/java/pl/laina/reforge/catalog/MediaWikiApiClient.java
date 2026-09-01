package pl.laina.reforge.catalog;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Sequential, rate-limited MediaWiki API reader used only by the catalog maintenance tool. */
final class MediaWikiApiClient {
    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 2;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String USER_AGENT =
            "LainaReforge-WikiCatalogMapper/1.0 (catalog maintenance; contact: repository maintainers)";

    private final HttpClient httpClient;
    private final String endpoint;
    private final long delayMillis;
    private final List<String> errors;
    private long lastRequestNanos;

    MediaWikiApiClient(String endpoint, long delayMillis, List<String> errors) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.endpoint = endpoint;
        this.delayMillis = delayMillis;
        this.errors = errors;
    }

    void fetchAllImages(WikiCatalogMapper.WikiSnapshot snapshot) {
        String continuation = null;
        do {
            Map<String, String> parameters = new TreeMap<>();
            parameters.put("action", "query");
            parameters.put("list", "allimages");
            parameters.put("ailimit", "max");
            parameters.put("aiprop", "url");
            if (continuation != null) {
                parameters.put("aicontinue", continuation);
            }

            Document document;
            try {
                document = request(parameters);
            } catch (IOException exception) {
                errors.add("allimages: " + exception.getMessage());
                return;
            }

            NodeList images = document.getElementsByTagName("img");
            List<String> names = new ArrayList<>();
            for (int index = 0; index < images.getLength(); index++) {
                Element image = (Element) images.item(index);
                String name = image.getAttribute("name");
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
            snapshot.addImages(names);
            continuation = continuation(document, "aicontinue");
        } while (continuation != null);
        snapshot.markImageInventoryComplete();
    }

    void fetchMissingFileUsage(
            WikiCatalogMapper.WikiSnapshot snapshot,
            Collection<String> requiredImages
    ) {
        List<String> missing = requiredImages.stream()
                .filter(image -> snapshot.fileUsage(image) == null)
                .sorted()
                .toList();
        for (List<String> batch : batches(missing)) {
            fetchFileUsageBatch(snapshot, batch);
        }
    }

    private void fetchFileUsageBatch(
            WikiCatalogMapper.WikiSnapshot snapshot,
            List<String> batch
    ) {
        Map<String, List<String>> requestedByNormalizedName = new TreeMap<>();
        Map<String, List<WikiCatalogMapper.WikiUsage>> usagesByImage = new TreeMap<>();
        for (String image : batch) {
            String normalized = WikiCatalogMapper.normalizeImageName(image);
            requestedByNormalizedName.computeIfAbsent(normalized, ignored -> new ArrayList<>()).add(image);
            usagesByImage.put(image, new ArrayList<>());
        }

        String continuation = null;
        do {
            Map<String, String> parameters = new TreeMap<>();
            parameters.put("action", "query");
            parameters.put("prop", "fileusage");
            parameters.put("titles", batch.stream().map(image -> "Plik:" + image).collect(java.util.stream.Collectors.joining("|")));
            parameters.put("fulimit", "max");
            if (continuation != null) {
                parameters.put("fucontinue", continuation);
            }

            Document document;
            try {
                document = request(parameters);
            } catch (IOException exception) {
                errors.add("fileusage [" + batch.getFirst() + " ...]: " + exception.getMessage());
                return;
            }

            NodeList pages = document.getElementsByTagName("page");
            for (int index = 0; index < pages.getLength(); index++) {
                Element page = (Element) pages.item(index);
                if (!page.hasAttribute("ns") || !page.getAttribute("ns").equals("6")) {
                    continue;
                }
                String filename = stripNamespace(page.getAttribute("title"));
                String normalized = WikiCatalogMapper.normalizeImageName(filename);
                List<String> requested = requestedByNormalizedName.get(normalized);
                if (requested == null) {
                    continue;
                }
                NodeList usages = page.getElementsByTagName("fu");
                for (int usageIndex = 0; usageIndex < usages.getLength(); usageIndex++) {
                    Element usage = (Element) usages.item(usageIndex);
                    WikiCatalogMapper.WikiUsage wikiUsage = new WikiCatalogMapper.WikiUsage(
                            parseInteger(usage.getAttribute("ns"), -1),
                            usage.getAttribute("title"));
                    for (String requestedImage : requested) {
                        usagesByImage.get(requestedImage).add(wikiUsage);
                    }
                }
            }
            continuation = continuation(document, "fucontinue");
        } while (continuation != null);

        usagesByImage.forEach(snapshot::putFileUsage);
    }

    void fetchMissingPageCategories(
            WikiCatalogMapper.WikiSnapshot snapshot,
            Collection<String> requiredPages
    ) {
        List<String> missing = requiredPages.stream()
                .filter(title -> snapshot.page(title) == null)
                .sorted()
                .toList();
        for (List<String> batch : batches(missing)) {
            fetchPageCategoryBatch(snapshot, batch);
        }
    }

    private void fetchPageCategoryBatch(
            WikiCatalogMapper.WikiSnapshot snapshot,
            List<String> batch
    ) {
        Map<String, String> requestedByNormalizedTitle = new TreeMap<>();
        Map<String, PageAccumulator> pagesByRequestedTitle = new TreeMap<>();
        for (String title : batch) {
            requestedByNormalizedTitle.put(normalizePageTitle(title), title);
            pagesByRequestedTitle.put(title, new PageAccumulator(title));
        }

        String continuation = null;
        do {
            Map<String, String> parameters = new TreeMap<>();
            parameters.put("action", "query");
            parameters.put("prop", "categories");
            parameters.put("titles", String.join("|", batch));
            parameters.put("cllimit", "max");
            if (continuation != null) {
                parameters.put("clcontinue", continuation);
            }

            Document document;
            try {
                document = request(parameters);
            } catch (IOException exception) {
                errors.add("categories [" + batch.getFirst() + " ...]: " + exception.getMessage());
                return;
            }

            NodeList pages = document.getElementsByTagName("page");
            for (int index = 0; index < pages.getLength(); index++) {
                Element page = (Element) pages.item(index);
                String apiTitle = page.getAttribute("title");
                String requestedTitle = requestedByNormalizedTitle.get(normalizePageTitle(apiTitle));
                if (requestedTitle == null) {
                    continue;
                }
                PageAccumulator accumulator = pagesByRequestedTitle.get(requestedTitle);
                accumulator.canonicalTitle = apiTitle;
                accumulator.exists = !page.hasAttribute("missing") && !page.hasAttribute("invalid");
                NodeList categories = page.getElementsByTagName("cl");
                for (int categoryIndex = 0; categoryIndex < categories.getLength(); categoryIndex++) {
                    accumulator.categories.add(((Element) categories.item(categoryIndex)).getAttribute("title"));
                }
            }
            continuation = continuation(document, "clcontinue");
        } while (continuation != null);

        for (PageAccumulator accumulator : pagesByRequestedTitle.values()) {
            snapshot.putPage(new WikiCatalogMapper.WikiPage(
                    accumulator.canonicalTitle,
                    accumulator.exists,
                    List.copyOf(accumulator.categories)));
        }
    }

    private Document request(Map<String, String> parameters) throws IOException {
        Map<String, String> complete = new TreeMap<>(parameters);
        complete.put("format", "xml");
        complete.put("maxlag", "5");
        complete.put("utf8", "1");
        URI uri = URI.create(endpoint + "?" + encodeQuery(complete));
        IOException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            rateLimit();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/xml")
                    .GET()
                    .build();
            HttpResponse<byte[]> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("request interrupted", exception);
            } catch (IOException exception) {
                lastFailure = exception;
                if (attempt < MAX_ATTEMPTS) {
                    retryPause();
                    continue;
                }
                throw exception;
            }

            if (response.statusCode() == 200) {
                Document document = WikiCatalogMapper.parseXml(response.body());
                NodeList apiErrors = document.getElementsByTagName("error");
                if (apiErrors.getLength() > 0) {
                    Element apiError = (Element) apiErrors.item(0);
                    String message = "API " + apiError.getAttribute("code") + ": " + apiError.getAttribute("info");
                    lastFailure = new IOException(message);
                    if (attempt < MAX_ATTEMPTS) {
                        retryPause();
                        continue;
                    }
                    throw lastFailure;
                }
                return document;
            }

            String responseText = new String(response.body(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ")
                    .trim();
            if (responseText.length() > 200) {
                responseText = responseText.substring(0, 200);
            }
            lastFailure = new IOException("HTTP " + response.statusCode() + (responseText.isEmpty() ? "" : ": " + responseText));
            if ((response.statusCode() == 429 || response.statusCode() >= 500) && attempt < MAX_ATTEMPTS) {
                retryPause();
                continue;
            }
            throw lastFailure;
        }
        throw lastFailure == null ? new IOException("unknown HTTP failure") : lastFailure;
    }

    private void rateLimit() throws IOException {
        if (lastRequestNanos != 0L && delayMillis > 0L) {
            long elapsedNanos = System.nanoTime() - lastRequestNanos;
            long requiredNanos = Duration.ofMillis(delayMillis).toNanos();
            long remainingNanos = requiredNanos - elapsedNanos;
            if (remainingNanos > 0L) {
                try {
                    Thread.sleep(Duration.ofNanos(remainingNanos));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("rate-limit wait interrupted", exception);
                }
            }
        }
        lastRequestNanos = System.nanoTime();
    }

    private void retryPause() throws IOException {
        try {
            Thread.sleep(Math.max(1000L, delayMillis));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("retry wait interrupted", exception);
        }
    }

    private static String continuation(Document document, String attribute) {
        NodeList continuations = document.getElementsByTagName("continue");
        if (continuations.getLength() == 0) {
            return null;
        }
        Element element = (Element) continuations.item(0);
        String value = element.getAttribute(attribute);
        return value.isBlank() ? null : value;
    }

    private static String stripNamespace(String title) {
        int separator = title.indexOf(':');
        return separator >= 0 ? title.substring(separator + 1) : title;
    }

    private static String normalizePageTitle(String title) {
        return title.replace('_', ' ').trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static int parseInteger(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String encodeQuery(Map<String, String> parameters) {
        return parameters.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static <T> List<List<T>> batches(List<T> values) {
        List<List<T>> result = new ArrayList<>();
        for (int start = 0; start < values.size(); start += BATCH_SIZE) {
            result.add(values.subList(start, Math.min(start + BATCH_SIZE, values.size())));
        }
        return result;
    }

    private static final class PageAccumulator {
        private String canonicalTitle;
        private boolean exists;
        private final Set<String> categories = new TreeSet<>();

        private PageAccumulator(String requestedTitle) {
            this.canonicalTitle = requestedTitle;
        }
    }
}

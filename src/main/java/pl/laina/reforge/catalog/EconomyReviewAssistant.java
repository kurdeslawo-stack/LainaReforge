package pl.laina.reforge.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.QueueItem;

/** Deterministic, read-only advisory calculations for the local review panel. */
final class EconomyReviewAssistant {
    static final int MIN_COMPARABLE_APPROVED = 3;
    static final int MAX_SIMILAR_ITEMS = 8;

    private EconomyReviewAssistant() {
    }

    static String technicalType(QueueItem item) {
        String material = item.identities().getFirst().material().toLowerCase(Locale.ROOT);
        String path = item.identities().getFirst().modelPath().toLowerCase(Locale.ROOT);
        if (material.contains("bow") || material.equals("crossbow") || path.contains("bow")) return "bow";
        if (material.matches(".*(helmet|chestplate|leggings|boots)$") || path.contains("armor/")) return "armor";
        if (material.matches(".*(sword|trident|mace)$") || path.contains("sword") || path.contains("weapon")) return "weapon";
        if (material.matches(".*(pickaxe|axe|shovel|hoe|shears|fishing_rod)$") || path.contains("tool")) return "tool";
        if (material.matches(".*(apple|bread|carrot|potato|stew|potion|food)$") || path.startsWith("food/")) return "consumable";
        if (material.matches(".*(ingot|nugget|diamond|emerald|coal|quartz|scrap)$")) return "material";
        return "misc";
    }

    static String modelGroup(QueueItem item) {
        String path = item.identities().getFirst().modelPath().replace('\\', '/');
        int separator = path.indexOf('/');
        return separator > 0 ? path.substring(0, separator).toLowerCase(Locale.ROOT) : "";
    }

    static int similarityScore(QueueItem source, QueueItem candidate) {
        if (source.logicalId().equals(candidate.logicalId())) return Integer.MIN_VALUE;
        int score = technicalType(source).equals(technicalType(candidate)) ? 4 : 0;
        String sourceGroup = modelGroup(source);
        if (!sourceGroup.isBlank() && sourceGroup.equals(modelGroup(candidate))) score += 3;
        Set<String> sourceTags = meaningfulTags(source);
        Set<String> candidateTags = meaningfulTags(candidate);
        int sharedTags = 0;
        for (String tag : sourceTags) sharedTags += candidateTags.contains(tag) ? 1 : 0;
        score += Math.min(sharedTags, 2) * 2;
        score += source.mappingStatus() == candidate.mappingStatus() ? 1 : 0;
        score += source.systemProposal().recyclable() == candidate.systemProposal().recyclable() ? 1 : 0;
        return score;
    }

    static List<SimilarItem> similarItems(
            QueueItem source,
            List<QueueItem> all,
            Map<String, RecyclingReviewPanelGenerator.ReviewDecision> decisions
    ) {
        return all.stream()
                .filter(candidate -> !candidate.logicalId().equals(source.logicalId()))
                .map(candidate -> new SimilarItem(candidate, similarityScore(source, candidate),
                        decisions.get(candidate.logicalId())))
                .filter(candidate -> candidate.score() >= 4)
                .sorted(Comparator.comparingInt(SimilarItem::score).reversed()
                        .thenComparing(value -> value.item().logicalId()))
                .limit(MAX_SIMILAR_ITEMS)
                .toList();
    }

    static EconomyStatistics statistics(List<Integer> approvedShards) {
        if (approvedShards.size() < MIN_COMPARABLE_APPROVED) return EconomyStatistics.insufficient(approvedShards.size());
        List<Integer> sorted = approvedShards.stream().sorted().toList();
        double average = sorted.stream().mapToInt(Integer::intValue).average().orElse(0);
        return new EconomyStatistics(sorted.size(), sorted.getFirst(), sorted.getLast(), percentile(sorted, 0.5),
                average, percentile(sorted, 0.25), percentile(sorted, 0.75), true);
    }

    static RiskAnalysis analyze(QueueItem item, int shards, EconomyStatistics statistics) {
        LinkedHashSet<String> flags = new LinkedHashSet<>();
        List<String> reasons = new ArrayList<>();
        Set<String> tags = item.acquisition().tags();
        Double ratio = statistics.sufficient() && statistics.median() > 0 ? shards / statistics.median() : null;
        if (ratio != null && ratio > 2.0) {
            flags.add("OUTLIER HIGH");
            reasons.add(formatRatio(ratio) + "× mediana podobnych itemów");
        } else if (ratio != null && ratio < 0.5) {
            flags.add("OUTLIER LOW");
            reasons.add(formatRatio(ratio) + "× mediana podobnych itemów");
        }
        boolean highValue = ratio != null ? ratio > 1.5 : shards >= 6;
        if (tags.contains("INFINITE_OR_FARMABLE") && highValue) {
            flags.add("HIGH ECONOMY RISK");
            reasons.add("INFINITE_OR_FARMABLE przy wysokiej wycenie");
        }
        if (tags.contains("REPEATABLE") && ratio != null && ratio > 2.0) {
            flags.add("HIGH ECONOMY RISK");
            reasons.add("REPEATABLE i wycena ponad 2× mediany");
        }
        for (String tag : List.of("LIMITED", "EVENT", "QUEST")) {
            if (tags.contains(tag)) reasons.add(tag + " — item może być rzadki");
        }
        RiskLevel level = flags.contains("HIGH ECONOMY RISK") ? RiskLevel.HIGH
                : flags.isEmpty() && reasons.isEmpty() ? RiskLevel.LOW : RiskLevel.MEDIUM;
        if (level == RiskLevel.LOW) reasons.add("Brak wykrytych deterministycznych flag ryzyka");
        return new RiskAnalysis(level, List.copyOf(flags), List.copyOf(reasons), ratio);
    }

    private static Set<String> meaningfulTags(QueueItem item) {
        LinkedHashSet<String> tags = new LinkedHashSet<>(item.acquisition().tags());
        tags.remove("UNKNOWN");
        return tags;
    }

    private static double percentile(List<Integer> sorted, double percentile) {
        double position = (sorted.size() - 1) * percentile;
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted.get(lower);
        return sorted.get(lower) + (sorted.get(upper) - sorted.get(lower)) * (position - lower);
    }

    private static String formatRatio(double ratio) {
        return String.format(Locale.ROOT, "%.1f", ratio);
    }

    enum RiskLevel { LOW, MEDIUM, HIGH }

    record SimilarItem(QueueItem item, int score, RecyclingReviewPanelGenerator.ReviewDecision decision) {
    }

    record EconomyStatistics(int count, int min, int max, double median, double average,
                             double p25, double p75, boolean sufficient) {
        static EconomyStatistics insufficient(int count) {
            return new EconomyStatistics(count, 0, 0, 0, 0, 0, 0, false);
        }
    }

    record RiskAnalysis(RiskLevel level, List<String> flags, List<String> reasons, Double medianRatio) {
    }
}

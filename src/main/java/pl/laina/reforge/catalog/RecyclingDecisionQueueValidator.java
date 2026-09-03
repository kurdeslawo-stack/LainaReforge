package pl.laina.reforge.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.AnalysisItem;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.CatalogStatus;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.Decision;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.DecisionQueue;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.DecisionStatus;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.Identity;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.MappingStatus;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.QueueItem;

/** Validates generated and subsequently human-edited recycling decision queues. */
public final class RecyclingDecisionQueueValidator {
    private RecyclingDecisionQueueValidator() {
    }

    public static ValidationResult validate(DecisionQueue queue) {
        return validate(queue, Map.of());
    }

    static ValidationResult validate(DecisionQueue queue, Map<String, AnalysisItem> expectedItems) {
        return validate(queue, expectedItems, List.of());
    }

    static ValidationResult validate(
            DecisionQueue queue,
            Map<String, AnalysisItem> expectedItems,
            List<ItemEconomyAnalyzer.CatalogRecord> catalogRecords
    ) {
        List<ValidationError> errors = new ArrayList<>();
        Map<String, String> logicalOwners = new TreeMap<>();
        Map<String, String> identityOwners = new TreeMap<>();
        TreeSet<String> duplicateIdentities = new TreeSet<>();

        for (QueueItem item : queue.items()) {
            String previousLogical = logicalOwners.putIfAbsent(item.logicalId(), item.wiki());
            if (previousLogical != null) {
                errors.add(error("DUPLICATE_LOGICAL_ITEM", item.logicalId(),
                        "Logical item occurs more than once."));
            }
            if (item.name().isBlank() || item.mappingStatus() == MappingStatus.MAPPED && item.wiki().isBlank()) {
                errors.add(error("MISSING_WIKI_OR_NAME", item.logicalId(),
                        "MAPPED item requires wiki and every item requires name."));
            }
            if (item.mappingStatus() == MappingStatus.UNMAPPED) {
                validateUnmapped(item, errors);
            }
            validateCatalogEvolution(item, errors);
            if (item.identities().isEmpty()) {
                errors.add(error("MISSING_IDENTITIES", item.logicalId(),
                        "Queue item must contain at least one identity."));
            }
            validateDecision(item.logicalId(), item.decision(), errors);

            for (Identity identity : item.identities()) {
                String previousOwner = identityOwners.putIfAbsent(identity.key(), item.logicalId());
                if (previousOwner != null) {
                    duplicateIdentities.add(identity.key());
                    errors.add(error("DUPLICATE_IDENTITY", item.logicalId(),
                            identity.key() + " is also assigned to " + previousOwner + "."));
                }
            }

            if (!expectedItems.isEmpty()) {
                if (item.mappingStatus() == MappingStatus.UNMAPPED) {
                    continue;
                }
                AnalysisItem expected = expectedItems.get(item.logicalId());
                if (expected == null) {
                    errors.add(error("UNEXPECTED_LOGICAL_ITEM", item.logicalId(),
                            "Queue item is absent from ETAP 3 input."));
                } else if (!expected.wiki().equals(item.wiki()) || !expected.name().equals(item.name())) {
                    errors.add(error("INPUT_WIKI_NAME_MISMATCH", item.logicalId(),
                            "Queue wiki/name differs from ETAP 3 input."));
                }
            }
        }

        validateCatalogCoverage(catalogRecords, identityOwners, errors);

        if (!expectedItems.isEmpty()) {
            for (String expected : expectedItems.keySet()) {
                if (!logicalOwners.containsKey(expected)) {
                    errors.add(error("MISSING_LOGICAL_ITEM", expected,
                            "ETAP 3 item is missing from the queue."));
                }
            }
        }

        errors.sort(java.util.Comparator.comparing(ValidationError::code)
                .thenComparing(ValidationError::logicalId)
                .thenComparing(ValidationError::message));
        return new ValidationResult(errors, duplicateIdentities.size());
    }

    private static void validateUnmapped(QueueItem item, List<ValidationError> errors) {
        if (!item.wiki().isBlank()) {
            errors.add(error("UNMAPPED_HAS_WIKI", item.logicalId(), "UNMAPPED item must have blank wiki."));
        }
        if (item.identities().size() != 1) {
            errors.add(error("UNMAPPED_IDENTITY_COUNT", item.logicalId(),
                    "UNMAPPED item must contain exactly one identity."));
        } else if (!validUnmappedLogicalId(item)) {
            errors.add(error("UNMAPPED_LOGICAL_ID", item.logicalId(),
                    "UNMAPPED logical id must be derived from material+CMD."));
        }
        if (item.priority() != RecyclingDecisionQueueGenerator.Priority.LOW
                || !item.acquisition().tags().equals(java.util.Set.of("UNKNOWN"))
                || !item.acquisition().summary().equals("UNKNOWN")
                || item.systemProposal().recyclable()
                != RecyclingDecisionQueueGenerator.SystemProposalValue.UNKNOWN) {
            errors.add(error("UNMAPPED_UNSAFE_DEFAULTS", item.logicalId(),
                    "UNMAPPED item must retain LOW/UNKNOWN conservative defaults."));
        }
    }

    private static boolean validUnmappedLogicalId(QueueItem item) {
        String identityKey = item.identities().getFirst().key();
        if (item.logicalId().equals("unmapped::" + identityKey)) {
            return true;
        }
        String changedPrefix = "changed::" + identityKey + "::";
        return item.catalogEvolution().status() == CatalogStatus.CHANGED
                && item.logicalId().startsWith(changedPrefix)
                && item.logicalId().length() > changedPrefix.length();
    }

    private static void validateCatalogEvolution(QueueItem item, List<ValidationError> errors) {
        CatalogStatus status = item.catalogEvolution().status();
        if (status == CatalogStatus.UNCHANGED) {
            return;
        }
        if (item.mappingStatus() != MappingStatus.UNMAPPED || item.identities().size() != 1
                || item.decision().status() != DecisionStatus.PENDING) {
            errors.add(error("CATALOG_CHANGE_UNSAFE_DEFAULTS", item.logicalId(),
                    "NEW/CHANGED item must be a single UNMAPPED identity with PENDING decision."));
        }
        if (status == CatalogStatus.NEW && item.catalogEvolution().beforeModelPath() != null) {
            errors.add(error("NEW_HAS_BEFORE_MODEL", item.logicalId(), "NEW item cannot have before_model_path."));
        }
        if (status == CatalogStatus.CHANGED
                && (item.catalogEvolution().beforeModelPath() == null
                || item.catalogEvolution().beforeModelPath().isBlank())) {
            errors.add(error("CHANGED_MISSING_BEFORE_MODEL", item.logicalId(),
                    "CHANGED item requires before_model_path."));
        }
    }

    private static void validateCatalogCoverage(
            List<ItemEconomyAnalyzer.CatalogRecord> catalogRecords,
            Map<String, String> identityOwners,
            List<ValidationError> errors
    ) {
        if (catalogRecords.isEmpty()) {
            return;
        }
        Map<String, ItemEconomyAnalyzer.CatalogRecord> catalogByIdentity = new TreeMap<>();
        for (ItemEconomyAnalyzer.CatalogRecord record : catalogRecords) {
            if (catalogByIdentity.putIfAbsent(record.key(), record) != null) {
                errors.add(error("DUPLICATE_CATALOG_IDENTITY", record.key(),
                        "Catalog contains duplicate material+CMD."));
            }
        }
        for (String key : catalogByIdentity.keySet()) {
            if (!identityOwners.containsKey(key)) {
                errors.add(error("MISSING_CATALOG_IDENTITY", key,
                        "Catalog identity is missing from the review queue."));
            }
        }
        for (String key : identityOwners.keySet()) {
            if (!catalogByIdentity.containsKey(key)) {
                errors.add(error("UNKNOWN_QUEUE_IDENTITY", identityOwners.get(key),
                        key + " does not exist in items.yml."));
            }
        }
    }

    private static void validateDecision(
            String logicalId,
            Decision decision,
            List<ValidationError> errors
    ) {
        if (decision == null || decision.status() == null) {
            errors.add(error("MISSING_DECISION_STATUS", logicalId, "Decision status is required."));
            return;
        }
        if (decision.note() == null) {
            errors.add(error("NULL_DECISION_NOTE", logicalId, "Decision note must not be null."));
        }
        switch (decision.status()) {
            case PENDING -> {
                if (decision.recyclable() != null) {
                    errors.add(error("PENDING_RECYCLABLE_NOT_NULL", logicalId,
                            "PENDING decision requires recyclable: null."));
                }
                if (decision.shards() != null) {
                    errors.add(error("PENDING_SHARDS_NOT_NULL", logicalId,
                            "PENDING decision requires shards: null."));
                }
            }
            case APPROVED -> {
                if (!Boolean.TRUE.equals(decision.recyclable())) {
                    errors.add(error("APPROVED_NOT_RECYCLABLE", logicalId,
                            "APPROVED decision requires recyclable: true."));
                }
                if (decision.shards() == null || decision.shards() <= 0) {
                    errors.add(error("APPROVED_INVALID_SHARDS", logicalId,
                            "APPROVED decision requires positive integer shards."));
                }
            }
            case REJECTED -> {
                if (!Boolean.FALSE.equals(decision.recyclable())) {
                    errors.add(error("REJECTED_RECYCLABLE_NOT_FALSE", logicalId,
                            "REJECTED decision requires recyclable: false."));
                }
                if (decision.shards() == null || decision.shards() != 0) {
                    errors.add(error("REJECTED_INVALID_SHARDS", logicalId,
                            "REJECTED decision requires shards: 0."));
                }
            }
        }
    }

    private static ValidationError error(String code, String logicalId, String message) {
        return new ValidationError(code, logicalId, message);
    }

    public record ValidationError(String code, String logicalId, String message) {
    }

    public record ValidationResult(List<ValidationError> errors, int duplicateIdentityAssignments) {
        public ValidationResult {
            errors = Collections.unmodifiableList(new ArrayList<>(errors));
        }

        public boolean valid() {
            return errors.isEmpty();
        }
    }
}

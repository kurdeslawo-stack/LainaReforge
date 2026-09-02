package pl.laina.reforge.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.AnalysisItem;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.Decision;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.DecisionQueue;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.DecisionStatus;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.Identity;
import static pl.laina.reforge.catalog.RecyclingDecisionQueueGenerator.QueueItem;

/** Validates generated and subsequently human-edited recycling decision queues. */
public final class RecyclingDecisionQueueValidator {
    private RecyclingDecisionQueueValidator() {
    }

    public static ValidationResult validate(DecisionQueue queue) {
        return validate(queue, Map.of());
    }

    static ValidationResult validate(DecisionQueue queue, Map<String, AnalysisItem> expectedItems) {
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
            if (item.wiki().isBlank() || item.name().isBlank()) {
                errors.add(error("MISSING_WIKI_OR_NAME", item.logicalId(),
                        "Queue item must contain wiki and name."));
            }
            if (item.identities().isEmpty()) {
                errors.add(error("MISSING_IDENTITIES", item.logicalId(),
                        "Queue item must contain at least one identity."));
            }
            validateDecision(item.logicalId(), item.decision(), errors);

            for (Identity identity : item.identities()) {
                String previousOwner = identityOwners.putIfAbsent(identity.key(), item.logicalId());
                if (previousOwner != null && !previousOwner.equals(item.logicalId())) {
                    duplicateIdentities.add(identity.key());
                    errors.add(error("DUPLICATE_IDENTITY", item.logicalId(),
                            identity.key() + " is also assigned to " + previousOwner + "."));
                }
            }

            if (!expectedItems.isEmpty()) {
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

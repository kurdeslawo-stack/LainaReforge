package pl.laina.reforge.rules;

import java.util.Objects;

public record RecyclingDecision(boolean recognized,
                                String technicalId,
                                String category,
                                String tier,
                                boolean recyclable,
                                int shardValue,
                                RecyclingReasonCode reasonCode,
                                RecyclingRuleSource ruleSource) {

    public RecyclingDecision {
        technicalId = Objects.requireNonNullElse(technicalId, "");
        category = Objects.requireNonNullElse(category, "");
        tier = Objects.requireNonNullElse(tier, "");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        ruleSource = Objects.requireNonNull(ruleSource, "ruleSource");
        if (!recyclable && shardValue != 0) {
            throw new IllegalArgumentException("A blocked decision cannot expose a shard value");
        }
        if (shardValue < 0) {
            throw new IllegalArgumentException("shardValue cannot be negative");
        }
    }

    public boolean requiresClassification() {
        return reasonCode == RecyclingReasonCode.BLOCKED_PENDING_CLASSIFICATION
                || reasonCode == RecyclingReasonCode.BLOCKED_APPROVED_DECISION_NOT_CONFIGURED;
    }
}

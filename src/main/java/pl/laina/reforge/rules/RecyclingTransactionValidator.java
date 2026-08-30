package pl.laina.reforge.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds an all-or-nothing payout plan from fresh Rules Engine decisions. */
public final class RecyclingTransactionValidator {

    public TransactionPlan validate(List<EvaluatedStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return new TransactionPlan(false, 0, Map.of(), List.of());
        }

        List<RecyclingDecision> blocked = new ArrayList<>();
        Map<String, Integer> amounts = new LinkedHashMap<>();
        long total = 0;
        for (EvaluatedStack stack : stacks) {
            if (stack == null || stack.amount() <= 0 || stack.decision() == null) {
                continue;
            }
            RecyclingDecision decision = stack.decision();
            if (!decision.recyclable()) {
                blocked.add(decision);
                continue;
            }
            total += (long) decision.shardValue() * stack.amount();
            amounts.merge(decision.technicalId(), stack.amount(), Integer::sum);
        }

        if (!blocked.isEmpty() || amounts.isEmpty()) {
            return new TransactionPlan(false, 0, Map.of(), blocked);
        }
        if (total <= 0 || total > Integer.MAX_VALUE) {
            RecyclingDecision overflow = new RecyclingDecision(
                    true, "", "", "", false, 0,
                    RecyclingReasonCode.BLOCKED_REWARD_OVERFLOW,
                    RecyclingRuleSource.TRANSACTION_SAFETY);
            return new TransactionPlan(false, 0, Map.of(), List.of(overflow));
        }
        return new TransactionPlan(true, (int) total, Map.copyOf(amounts), List.of());
    }

    public record EvaluatedStack(int amount, RecyclingDecision decision) {
    }

    public record TransactionPlan(boolean allowed,
                                  int totalShards,
                                  Map<String, Integer> itemAmounts,
                                  List<RecyclingDecision> blockedDecisions) {
        public TransactionPlan {
            itemAmounts = Map.copyOf(itemAmounts);
            blockedDecisions = List.copyOf(blockedDecisions);
        }
    }
}

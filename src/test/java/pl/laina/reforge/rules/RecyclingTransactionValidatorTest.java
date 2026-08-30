package pl.laina.reforge.rules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecyclingTransactionValidatorTest {

    private final RecyclingTransactionValidator validator = new RecyclingTransactionValidator();

    @Test
    void validFreshDecisionsProduceCompletePayoutPlan() {
        RecyclingDecision first = allowed("first", 4);
        RecyclingDecision second = allowed("second", 2);

        RecyclingTransactionValidator.TransactionPlan plan = validator.validate(List.of(
                new RecyclingTransactionValidator.EvaluatedStack(2, first),
                new RecyclingTransactionValidator.EvaluatedStack(3, second)));

        assertTrue(plan.allowed());
        assertEquals(14, plan.totalShards());
        assertEquals(2, plan.itemAmounts().get("first"));
        assertEquals(3, plan.itemAmounts().get("second"));
    }

    @Test
    void oneChangedOrBlockedItemCancelsEntireTransaction() {
        RecyclingDecision accepted = allowed("first", 4);
        RecyclingDecision changed = new RecyclingDecision(
                true, "second", "equipment", "common", false, 0,
                RecyclingReasonCode.BLOCKED_EXPLICIT_ITEM, RecyclingRuleSource.ITEM_OVERRIDE);

        RecyclingTransactionValidator.TransactionPlan plan = validator.validate(List.of(
                new RecyclingTransactionValidator.EvaluatedStack(2, accepted),
                new RecyclingTransactionValidator.EvaluatedStack(1, changed)));

        assertFalse(plan.allowed());
        assertEquals(0, plan.totalShards());
        assertTrue(plan.itemAmounts().isEmpty());
        assertEquals(List.of(changed), plan.blockedDecisions());
    }

    @Test
    void rewardOverflowFailsClosedWithoutPartialPayout() {
        RecyclingDecision accepted = allowed("first", 1_000_000);

        RecyclingTransactionValidator.TransactionPlan plan = validator.validate(List.of(
                new RecyclingTransactionValidator.EvaluatedStack(Integer.MAX_VALUE, accepted)));

        assertFalse(plan.allowed());
        assertEquals(0, plan.totalShards());
        assertTrue(plan.itemAmounts().isEmpty());
        assertEquals(RecyclingReasonCode.BLOCKED_REWARD_OVERFLOW,
                plan.blockedDecisions().getFirst().reasonCode());
    }

    private RecyclingDecision allowed(String id, int value) {
        return new RecyclingDecision(true, id, "equipment", "common", true, value,
                RecyclingReasonCode.ALLOWED_CATEGORY, RecyclingRuleSource.CATEGORY_POLICY);
    }
}

package pl.laina.reforge.rules;

import java.util.Objects;

/** Safe input produced by the identification/currency adapter before policy evaluation. */
public record RuleEvaluationInput(boolean itemPresent,
                                  boolean pluginCurrency,
                                  String currencyType,
                                  String technicalId) {

    public RuleEvaluationInput {
        currencyType = Objects.requireNonNullElse(currencyType, "");
        technicalId = Objects.requireNonNullElse(technicalId, "");
    }

    public static RuleEvaluationInput empty() {
        return new RuleEvaluationInput(false, false, "", "");
    }

    public static RuleEvaluationInput unidentified() {
        return new RuleEvaluationInput(true, false, "", "");
    }

    public static RuleEvaluationInput identified(String technicalId) {
        return new RuleEvaluationInput(true, false, "", technicalId);
    }

    public static RuleEvaluationInput currency(String type, String forgedTechnicalId) {
        return new RuleEvaluationInput(true, true, type, forgedTechnicalId);
    }
}

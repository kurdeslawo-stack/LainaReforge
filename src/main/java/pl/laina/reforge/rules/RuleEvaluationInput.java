package pl.laina.reforge.rules;

import pl.laina.reforge.runtime.RuntimeItemIdentity;

import java.util.Objects;

/** Safe input produced by the identification/currency adapter before policy evaluation. */
public record RuleEvaluationInput(boolean itemPresent,
                                  boolean pluginCurrency,
                                  String currencyType,
                                  String technicalId,
                                  boolean runtimeLookupRequired,
                                  RuntimeItemIdentity runtimeIdentity) {

    public RuleEvaluationInput {
        currencyType = Objects.requireNonNullElse(currencyType, "");
        technicalId = Objects.requireNonNullElse(technicalId, "");
    }

    public static RuleEvaluationInput empty() {
        return new RuleEvaluationInput(false, false, "", "", false, null);
    }

    public static RuleEvaluationInput unidentified() {
        return new RuleEvaluationInput(true, false, "", "", false, null);
    }

    public static RuleEvaluationInput identified(String technicalId) {
        return new RuleEvaluationInput(true, false, "", technicalId, false, null);
    }

    public static RuleEvaluationInput currency(String type, String forgedTechnicalId) {
        return new RuleEvaluationInput(true, true, type, forgedTechnicalId, false, null);
    }

    public static RuleEvaluationInput runtimeIdentified(String technicalId, RuntimeItemIdentity identity) {
        return new RuleEvaluationInput(true, false, "", technicalId, true,
                Objects.requireNonNull(identity, "identity"));
    }

    public static RuleEvaluationInput invalidRuntimeIdentity(String technicalId) {
        return new RuleEvaluationInput(true, false, "", technicalId, true, null);
    }
}

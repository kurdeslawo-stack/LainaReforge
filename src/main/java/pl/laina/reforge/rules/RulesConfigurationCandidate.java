package pl.laina.reforge.rules;

import java.util.Objects;

public record RulesConfigurationCandidate(RulesConfiguration configuration,
                                          ConfigurationValidationReport report) {
    public RulesConfigurationCandidate {
        configuration = Objects.requireNonNull(configuration, "configuration");
        report = Objects.requireNonNull(report, "report");
    }
}

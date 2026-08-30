package pl.laina.reforge.rules;

import java.util.List;

public record ConfigurationValidationReport(List<ConfigurationIssue> issues) {

    public ConfigurationValidationReport {
        issues = List.copyOf(issues);
    }

    public static ConfigurationValidationReport valid() {
        return new ConfigurationValidationReport(List.of());
    }

    public int errorCount() {
        return (int) issues.stream()
                .filter(issue -> issue.severity() == ConfigurationIssue.Severity.ERROR)
                .count();
    }

    public int warningCount() {
        return (int) issues.stream()
                .filter(issue -> issue.severity() == ConfigurationIssue.Severity.WARNING)
                .count();
    }

    public boolean isValid() {
        return errorCount() == 0;
    }
}

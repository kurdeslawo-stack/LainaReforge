package pl.laina.reforge.rules;

import java.util.Objects;

public record ConfigurationIssue(Severity severity, String code, String path, String message) {

    public ConfigurationIssue {
        severity = Objects.requireNonNull(severity, "severity");
        code = Objects.requireNonNull(code, "code");
        path = Objects.requireNonNullElse(path, "");
        message = Objects.requireNonNull(message, "message");
    }

    public enum Severity {
        ERROR,
        WARNING
    }
}

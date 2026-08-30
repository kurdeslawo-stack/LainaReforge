package pl.laina.reforge.rules;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import pl.laina.reforge.LainaReforgePlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static pl.laina.reforge.rules.ConfigurationIssue.Severity.ERROR;

/** Validates candidates and owns the last-good configuration snapshot. */
public final class RulesConfigurationService {

    private final LainaReforgePlugin plugin;
    private RulesConfiguration activeConfiguration = RulesConfiguration.failClosed();
    private ConfigurationValidationReport activeReport = ConfigurationValidationReport.valid();
    private ConfigurationValidationReport lastCheckReport = ConfigurationValidationReport.valid();

    public RulesConfigurationService(LainaReforgePlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public RulesConfigurationCandidate loadInitial() {
        RulesConfigurationCandidate candidate = RulesConfigurationValidator.validate(plugin.getConfig());
        lastCheckReport = candidate.report();
        if (candidate.report().isValid()) {
            activate(candidate);
        } else {
            activeConfiguration = RulesConfiguration.failClosed();
            activeReport = candidate.report();
        }
        return candidate;
    }

    public RulesConfigurationCandidate validateDisk() {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            ConfigurationIssue issue = new ConfigurationIssue(
                    ERROR,
                    "YAML_LOAD_ERROR",
                    "config.yml",
                    "Nie mozna bezpiecznie wczytac YAML: " + safeMessage(exception));
            RulesConfigurationCandidate failed = new RulesConfigurationCandidate(
                    RulesConfiguration.failClosed(),
                    new ConfigurationValidationReport(List.of(issue)));
            lastCheckReport = failed.report();
            return failed;
        }

        RulesConfigurationCandidate candidate = RulesConfigurationValidator.validate(configuration);
        lastCheckReport = candidate.report();
        return candidate;
    }

    public void activate(RulesConfigurationCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (!candidate.report().isValid() || !candidate.configuration().valid()) {
            throw new IllegalArgumentException("Cannot activate invalid configuration");
        }
        activeConfiguration = candidate.configuration();
        activeReport = candidate.report();
    }

    public RulesConfiguration activeConfiguration() {
        return activeConfiguration;
    }

    public ConfigurationValidationReport activeReport() {
        return activeReport;
    }

    public ConfigurationValidationReport lastCheckReport() {
        return lastCheckReport;
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.replace('\n', ' ').replace('\r', ' ');
    }
}

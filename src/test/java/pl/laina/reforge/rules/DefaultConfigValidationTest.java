package pl.laina.reforge.rules;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultConfigValidationTest {

    @Test
    void shippedConfigIsValidForRulesEngineV2() {
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(
                new File("src/main/resources/config.yml"));

        RulesConfigurationCandidate candidate = RulesConfigurationValidator.validate(configuration);

        assertTrue(candidate.report().isValid(), () -> candidate.report().issues().toString());
    }
}

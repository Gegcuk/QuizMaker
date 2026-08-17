package uk.gegc.quizmaker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Deployment AI provider attempt budget configuration contract")
class DeploymentAiAttemptBudgetConfigurationContractTest {

    private static final String PROPERTY = "ai.rate-limit.max-attempts-per-task";
    private static final String ENVIRONMENT_VARIABLE = "AI_RATE_LIMIT_MAX_ATTEMPTS_PER_TASK";
    private static final String DEFAULT_VALUE = "5";

    @Test
    @DisplayName("Keeps packaged, template, and deployed provider attempt defaults aligned")
    void keepsApplicationAndDeploymentPropertiesAligned() throws IOException {
        Properties applicationDefaults = load(Path.of("src/main/resources/application.properties"));
        Properties productionTemplate = load(Path.of("src/main/resources/application-prod.properties.example"));
        Properties deployedProduction = load(Path.of("server/backend/application-prod.properties"));

        assertThat(applicationDefaults.getProperty(PROPERTY)).isEqualTo(DEFAULT_VALUE);
        assertThat(productionTemplate.getProperty(PROPERTY)).isEqualTo(DEFAULT_VALUE);
        assertThat(deployedProduction.getProperty(PROPERTY))
                .isEqualTo("${" + ENVIRONMENT_VARIABLE + ":" + DEFAULT_VALUE + "}");
    }

    @Test
    @DisplayName("Compose forwards the provider attempt budget with its safe default")
    void composeForwardsProviderAttemptBudget() throws IOException {
        String compose = Files.readString(Path.of("server/backend/docker-compose.yml"));

        assertThat(compose).contains(
                "- " + ENVIRONMENT_VARIABLE + "=${" + ENVIRONMENT_VARIABLE + ":-" + DEFAULT_VALUE + "}");
    }

    @Test
    @DisplayName("Production environment example documents the provider attempt budget")
    void productionEnvironmentDocumentsProviderAttemptBudget() throws IOException {
        String environmentExample = Files.readString(Path.of("server/backend/env.production.example"));

        assertThat(environmentExample).contains(ENVIRONMENT_VARIABLE + "=" + DEFAULT_VALUE);
    }

    private Properties load(Path path) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }
        return properties;
    }
}

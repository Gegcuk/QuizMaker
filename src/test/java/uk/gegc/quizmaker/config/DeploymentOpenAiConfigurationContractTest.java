package uk.gegc.quizmaker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Deployment OpenAI configuration contract")
class DeploymentOpenAiConfigurationContractTest {

    private static final Path APPLICATION_PROPERTIES = Path.of("src/main/resources/application.properties");
    private static final Path PRODUCTION_TEMPLATE =
            Path.of("src/main/resources/application-prod.properties.example");
    private static final Path DEPLOYED_PRODUCTION_PROPERTIES =
            Path.of("server/backend/application-prod.properties");
    private static final Path PRODUCTION_ENVIRONMENT_EXAMPLE =
            Path.of("server/backend/env.production.example");
    private static final Path DEPLOYMENT_COMPOSE_FILE = Path.of("server/backend/docker-compose.yml");
    private static final Path DEPLOYMENT_WORKFLOW = Path.of(".github/workflows/deploy-backend.yml");

    private static final List<Setting> SETTINGS = List.of(
            new Setting("spring.ai.openai.chat.options.model", "OPENAI_MODEL", "gpt-5.6-luna"),
            new Setting("spring.ai.openai.chat.options.temperature", "OPENAI_TEMPERATURE", "1.0"),
            new Setting(
                    "spring.ai.openai.chat.options.max-completion-tokens",
                    "OPENAI_MAX_COMPLETION_TOKENS",
                    "16000"
            )
    );

    @Test
    @DisplayName("Keeps packaged and production OpenAI defaults aligned for Luna")
    void keepsPackagedAndProductionDefaultsAlignedForLuna() throws IOException {
        Properties applicationDefaults = load(APPLICATION_PROPERTIES);
        Properties productionTemplate = load(PRODUCTION_TEMPLATE);
        Properties deployedProduction = load(DEPLOYED_PRODUCTION_PROPERTIES);

        SETTINGS.forEach(setting -> {
            String expectedValue = "${" + setting.environmentVariable() + ":" + setting.defaultValue() + "}";
            assertThat(applicationDefaults.getProperty(setting.property())).isEqualTo(expectedValue);
            assertThat(productionTemplate.getProperty(setting.property())).isEqualTo(expectedValue);
            assertThat(deployedProduction.getProperty(setting.property())).isEqualTo(expectedValue);
        });

        assertThat(applicationDefaults).doesNotContainKey("spring.ai.openai.chat.options.max-tokens");
        assertThat(productionTemplate).doesNotContainKey("spring.ai.openai.chat.options.max-tokens");
        assertThat(deployedProduction).doesNotContainKey("spring.ai.openai.chat.options.max-tokens");
    }

    @Test
    @DisplayName("Forwards every OpenAI option from the server environment into the backend container")
    void forwardsEveryOpenAiOptionIntoBackendContainer() throws IOException {
        String compose = Files.readString(DEPLOYMENT_COMPOSE_FILE);

        SETTINGS.forEach(setting -> assertThat(compose).contains(
                "- " + setting.environmentVariable() + "=${" + setting.environmentVariable()
                        + ":-" + setting.defaultValue() + "}"
        ));
    }

    @Test
    @DisplayName("Writes every OpenAI option into the protected production environment file")
    void writesEveryOpenAiOptionIntoProductionEnvironment() throws IOException {
        String workflow = Files.readString(DEPLOYMENT_WORKFLOW);

        SETTINGS.forEach(setting -> assertThat(workflow).contains(
                setting.environmentVariable() + "=\"${{ secrets." + setting.environmentVariable()
                        + " || '" + setting.defaultValue() + "' }}\""
        ));
    }

    @Test
    @DisplayName("Documents every configurable OpenAI option for manual deployments")
    void documentsEveryOpenAiOptionForManualDeployments() throws IOException {
        String environmentExample = Files.readString(PRODUCTION_ENVIRONMENT_EXAMPLE);

        SETTINGS.forEach(setting -> assertThat(environmentExample)
                .contains(setting.environmentVariable() + "=" + setting.defaultValue()));
    }

    private Properties load(Path path) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }
        return properties;
    }

    private record Setting(String property, String environmentVariable, String defaultValue) {
    }
}

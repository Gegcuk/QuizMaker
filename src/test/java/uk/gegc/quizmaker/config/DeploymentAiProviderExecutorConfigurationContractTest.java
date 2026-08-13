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

@DisplayName("Deployment AI provider executor configuration contract")
class DeploymentAiProviderExecutorConfigurationContractTest {

    private static final List<Setting> SETTINGS = List.of(
            new Setting("async.ai.provider.core-pool-size", "ASYNC_AI_PROVIDER_CORE_POOL_SIZE", "8"),
            new Setting("async.ai.provider.max-pool-size", "ASYNC_AI_PROVIDER_MAX_POOL_SIZE", "16"),
            new Setting("async.ai.provider.queue-capacity", "ASYNC_AI_PROVIDER_QUEUE_CAPACITY", "50"),
            new Setting("async.ai.provider.keep-alive-seconds", "ASYNC_AI_PROVIDER_KEEP_ALIVE_SECONDS", "60"),
            new Setting("async.ai.provider.await-termination-seconds", "ASYNC_AI_PROVIDER_AWAIT_TERMINATION_SECONDS", "30")
    );

    @Test
    @DisplayName("Keeps packaged, template, and deployed provider executor defaults aligned")
    void keepsApplicationAndDeploymentPropertiesAligned() throws IOException {
        Properties applicationDefaults = load(Path.of("src/main/resources/application.properties"));
        Properties productionTemplate = load(Path.of("src/main/resources/application-prod.properties.example"));
        Properties deployedProduction = load(Path.of("server/backend/application-prod.properties"));

        SETTINGS.forEach(setting -> {
            assertThat(applicationDefaults.getProperty(setting.property())).isEqualTo(setting.defaultValue());
            assertThat(productionTemplate.getProperty(setting.property())).isEqualTo(setting.defaultValue());
            assertThat(deployedProduction.getProperty(setting.property()))
                    .isEqualTo("${" + setting.environmentVariable() + ":" + setting.defaultValue() + "}");
        });
    }

    @Test
    @DisplayName("Compose forwards every provider executor setting with its safe default")
    void composeForwardsProviderExecutorSettings() throws IOException {
        String compose = Files.readString(Path.of("server/backend/docker-compose.yml"));

        SETTINGS.forEach(setting -> assertThat(compose).contains(
                "- " + setting.environmentVariable() + "=${" + setting.environmentVariable()
                        + ":-" + setting.defaultValue() + "}"));
    }

    @Test
    @DisplayName("Production environment example documents every provider executor setting")
    void productionEnvironmentDocumentsProviderExecutorSettings() throws IOException {
        String environmentExample = Files.readString(Path.of("server/backend/env.production.example"));

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

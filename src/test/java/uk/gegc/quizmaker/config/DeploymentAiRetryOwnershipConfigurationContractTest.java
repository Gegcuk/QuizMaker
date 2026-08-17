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

@DisplayName("Deployment AI retry ownership configuration contract")
class DeploymentAiRetryOwnershipConfigurationContractTest {

    private static final String PROPERTY = "spring.ai.retry.max-attempts";
    private static final String SINGLE_ATTEMPT = "1";
    private static final List<Path> CONFIGURATION_FILES = List.of(
            Path.of("src/main/resources/application.properties"),
            Path.of("src/main/resources/application-prod.properties.example"),
            Path.of("server/backend/application-prod.properties")
    );

    @Test
    @DisplayName("Keeps the application as the single retry owner in every runtime configuration")
    void keepsSingleRetryOwnerAcrossRuntimeConfigurations() throws IOException {
        for (Path configurationFile : CONFIGURATION_FILES) {
            assertThat(load(configurationFile).getProperty(PROPERTY))
                    .as("%s in %s", PROPERTY, configurationFile)
                    .isEqualTo(SINGLE_ATTEMPT);
        }
    }

    private Properties load(Path path) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }
        return properties;
    }
}

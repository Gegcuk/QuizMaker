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

@DisplayName("Deployment authentication session configuration contract")
class DeploymentAuthSessionConfigurationContractTest {

    private static final List<Path> CONFIGURATION_FILES = List.of(
            Path.of("src/main/resources/application.properties"),
            Path.of("src/main/resources/application-prod.properties.example"),
            Path.of("server/backend/application-prod.properties")
    );

    @Test
    @DisplayName("Keeps twelve-hour access and four-day rolling session validity in every runtime configuration")
    void keepsApprovedSessionLifetimesAcrossRuntimeConfigurations() throws IOException {
        for (Path configurationFile : CONFIGURATION_FILES) {
            Properties properties = load(configurationFile);

            assertThat(properties.getProperty("jwt.access-expiration-ms"))
                    .as("access validity in %s", configurationFile)
                    .isEqualTo("43200000");
            assertThat(properties.getProperty("jwt.refresh-expiration-ms"))
                    .as("rolling session validity in %s", configurationFile)
                    .isEqualTo("345600000");
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

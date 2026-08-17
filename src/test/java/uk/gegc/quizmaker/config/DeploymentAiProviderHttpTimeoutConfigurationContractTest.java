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

@DisplayName("Deployment AI provider HTTP timeout configuration contract")
class DeploymentAiProviderHttpTimeoutConfigurationContractTest {

    private static final String HTTP_CLIENT_FACTORY_PROPERTY = "spring.http.client.factory";
    private static final String CONNECT_TIMEOUT_PROPERTY = "spring.http.client.connect-timeout";
    private static final String READ_TIMEOUT_PROPERTY = "spring.http.client.read-timeout";
    private static final String HTTP_CLIENT_FACTORY = "jdk";
    private static final String CONNECT_TIMEOUT = "${AI_PROVIDER_CONNECT_TIMEOUT:10s}";
    private static final String READ_TIMEOUT = "${AI_PROVIDER_READ_TIMEOUT:180s}";
    private static final List<Path> CONFIGURATION_FILES = List.of(
            Path.of("src/main/resources/application.properties"),
            Path.of("src/main/resources/application-prod.properties.example"),
            Path.of("server/backend/application-prod.properties")
    );

    @Test
    @DisplayName("Keeps bounded overridable provider timeouts in every runtime configuration")
    void keepsProviderTimeoutsAcrossRuntimeConfigurations() throws IOException {
        for (Path configurationFile : CONFIGURATION_FILES) {
            Properties properties = load(configurationFile);

            assertThat(properties.getProperty(HTTP_CLIENT_FACTORY_PROPERTY))
                    .as("%s in %s", HTTP_CLIENT_FACTORY_PROPERTY, configurationFile)
                    .isEqualTo(HTTP_CLIENT_FACTORY);
            assertThat(properties.getProperty(CONNECT_TIMEOUT_PROPERTY))
                    .as("%s in %s", CONNECT_TIMEOUT_PROPERTY, configurationFile)
                    .isEqualTo(CONNECT_TIMEOUT);
            assertThat(properties.getProperty(READ_TIMEOUT_PROPERTY))
                    .as("%s in %s", READ_TIMEOUT_PROPERTY, configurationFile)
                    .isEqualTo(READ_TIMEOUT);
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

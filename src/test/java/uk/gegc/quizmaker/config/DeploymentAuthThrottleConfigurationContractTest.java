package uk.gegc.quizmaker.config;

import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.auth.config.AuthRateLimitProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Arrays;
import java.util.Properties;

import static org.assertj.core.api.Assertions.*;

class DeploymentAuthThrottleConfigurationContractTest {
    @Test
    void browserClientsCanReadRetryAfterInEveryShippedConfiguration() throws Exception {
        for (String file : new String[]{"src/main/resources/application.properties",
                "server/backend/application-prod.properties", "src/main/resources/application-prod.properties.example"}) {
            Properties configuration = new Properties();
            try (var reader = Files.newBufferedReader(Path.of(file))) {
                configuration.load(reader);
            }
            assertThat(Arrays.asList(configuration.getProperty("app.cors.exposed-headers").split(",")))
                    .as(file).contains("Retry-After");
        }
    }

    @Test
    void everyConfiguredAllowanceAndWindowHasMatchingProductionOverrideAndProfileADefault() throws Exception {
        String application = Files.readString(Path.of("src/main/resources/application.properties"));
        String compose = Files.readString(Path.of("server/backend/docker-compose.yml"));
        String deployment = Files.readString(Path.of(".github/workflows/deploy-backend.yml"));
        new AuthRateLimitProperties().getLimits().forEach((name, limit) -> {
            String environment = "AUTH_LIMIT_" + name.toUpperCase(Locale.ROOT).replace('-', '_');
            assertThat(application).contains("app.auth.rate-limit.limits." + name + ".requests=${" + environment + "_REQUESTS:" + limit.getRequests() + "}");
            assertThat(application).contains("app.auth.rate-limit.limits." + name + ".window-seconds=${" + environment + "_WINDOW_SECONDS:" + limit.getWindowSeconds() + "}");
            assertThat(compose).contains(environment + "_REQUESTS=${" + environment + "_REQUESTS:-" + limit.getRequests() + "}");
            assertThat(compose).contains(environment + "_WINDOW_SECONDS=${" + environment + "_WINDOW_SECONDS:-" + limit.getWindowSeconds() + "}");
            assertThat(deployment).contains("vars." + environment + "_REQUESTS || '" + limit.getRequests() + "'");
            assertThat(deployment).contains("vars." + environment + "_WINDOW_SECONDS || '" + limit.getWindowSeconds() + "'");
        });
    }
}

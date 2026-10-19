package uk.gegc.quizmaker.config;

import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.auth.config.AuthRateLimitProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.*;

class DeploymentAuthThrottleConfigurationContractTest {
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

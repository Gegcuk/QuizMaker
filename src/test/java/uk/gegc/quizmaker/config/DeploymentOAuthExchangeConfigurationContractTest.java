package uk.gegc.quizmaker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Deployment OAuth exchange configuration contract")
class DeploymentOAuthExchangeConfigurationContractTest {

    private static final Path APPLICATION_PROPERTIES = Path.of("src/main/resources/application.properties");
    private static final Path DEPLOYMENT_WORKFLOW = Path.of(".github/workflows/deploy-backend.yml");
    private static final Path DEPLOYMENT_COMPOSE_FILE = Path.of("server/backend/docker-compose.yml");
    private static final Path SERVER_SETUP = Path.of("server/backend/server-setup.sh");
    private static final Path PRODUCTION_PROPERTIES = Path.of("server/backend/application-prod.properties");

    @Test
    @DisplayName("passes an optional fixed legacy cutoff and keeps the secure two-minute exchange default")
    void deployment_passesDatedCompatibilityConfigurationWithoutHardCodedCutoff() throws IOException {
        String application = Files.readString(APPLICATION_PROPERTIES);
        String workflow = Files.readString(DEPLOYMENT_WORKFLOW);
        String compose = Files.readString(DEPLOYMENT_COMPOSE_FILE);

        assertThat(application).contains(
                "app.oauth2.exchange.code-ttl=${OAUTH2_EXCHANGE_CODE_TTL:PT2M}",
                "app.oauth2.exchange.legacy-token-redirect-until=${OAUTH2_LEGACY_TOKEN_REDIRECT_UNTIL:}",
                "app.oauth2.exchange.clients.quizzence-web.redirect-uri=${OAUTH2_REDIRECT_URI:"
        );
        assertThat(workflow).contains(
                "OAUTH2_EXCHANGE_CODE_TTL=\"${{ vars.OAUTH2_EXCHANGE_CODE_TTL || 'PT2M' }}\"",
                "OAUTH2_LEGACY_TOKEN_REDIRECT_UNTIL=\"${{ vars.OAUTH2_LEGACY_TOKEN_REDIRECT_UNTIL || '' }}\""
        );
        assertThat(compose).contains(
                "OAUTH2_EXCHANGE_CODE_TTL=${OAUTH2_EXCHANGE_CODE_TTL:-PT2M}",
                "OAUTH2_LEGACY_TOKEN_REDIRECT_UNTIL=${OAUTH2_LEGACY_TOKEN_REDIRECT_UNTIL:-}"
        );
    }

    @Test
    @DisplayName("the trusted edge overwrites untrusted forwarded IP input used by exchange limiting")
    void nginx_overwritesForwardedIpInsteadOfAppendingClientInput() throws IOException {
        String setup = Files.readString(SERVER_SETUP);
        String production = Files.readString(PRODUCTION_PROPERTIES);
        int apiLocation = setup.indexOf("location /api/");
        int nextLocation = setup.indexOf("location = /actuator/health/liveness", apiLocation);
        String apiProxy = setup.substring(apiLocation, nextLocation);

        assertThat(apiProxy)
                .contains("proxy_set_header X-Forwarded-For $remote_addr;")
                .doesNotContain("$proxy_add_x_forwarded_for");
        assertThat(production).contains("server.forward-headers-strategy=NATIVE");
    }
}

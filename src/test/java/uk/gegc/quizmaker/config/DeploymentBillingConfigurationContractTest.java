package uk.gegc.quizmaker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Deployment billing configuration contract")
class DeploymentBillingConfigurationContractTest {

    private static final Path DEPLOYMENT_WORKFLOW = Path.of(".github/workflows/deploy-backend.yml");
    private static final Path DEPLOYMENT_COMPOSE_FILE = Path.of("server/backend/docker-compose.yml");

    @Test
    @DisplayName("uses the canonical ratio for preflight and backend startup before services start")
    void deploymentWorkflow_usesTypedBillingPreflightBeforeStartingServices() throws IOException {
        String workflow = Files.readString(DEPLOYMENT_WORKFLOW);
        String compose = Files.readString(DEPLOYMENT_COMPOSE_FILE);

        String canonicalFallback = "BILLING_TOKEN_TO_LLM_RATIO=\"${{ secrets.BILLING_TOKEN_TO_LLM_RATIO || '1000' }}\"";
        String preflightCommand = "docker compose --env-file .env run --rm --no-deps quizmaker-backend --config-preflight";
        String mysqlStartup = "docker compose --env-file .env up -d mysql";
        String candidateStartup = "docker compose --env-file .env run -d --name \"$candidate\" --no-deps quizmaker-backend";

        assertThat(workflow).contains(canonicalFallback, preflightCommand, mysqlStartup, candidateStartup);
        assertThat(workflow).doesNotContain("docker run --rm --env-file .env");
        assertThat(compose).contains("- BILLING_TOKEN_TO_LLM_RATIO=${BILLING_TOKEN_TO_LLM_RATIO}");
        assertThat(workflow.indexOf(preflightCommand)).isLessThan(workflow.indexOf(mysqlStartup));
        assertThat(workflow.indexOf(preflightCommand)).isLessThan(workflow.indexOf(candidateStartup));
    }
}

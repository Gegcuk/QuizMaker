package uk.gegc.quizmaker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Deployment health configuration contract")
class DeploymentHealthConfigurationContractTest {

    private static final String LOCAL_READINESS = "http://localhost:8080/actuator/health/readiness";
    private static final String PUBLIC_READINESS = "https://www.quizzence.com/actuator/health/readiness";

    @Test
    @DisplayName("Docker image and Compose use readiness for ongoing container health")
    void containerHealthChecks_useReadiness() throws IOException {
        String dockerfile = Files.readString(Path.of("server/backend/Dockerfile"));
        String compose = Files.readString(Path.of("server/backend/docker-compose.yml"));

        assertThat(dockerfile).contains("CMD curl -f " + LOCAL_READINESS + " || exit 1");
        assertThat(compose).contains("[\"CMD\", \"curl\", \"-f\", \"" + LOCAL_READINESS + "\"]");
        assertThat(dockerfile).doesNotContain("/actuator/health/startup");
        assertThat(compose).doesNotContain("/actuator/health/startup");
    }

    @Test
    @DisplayName("Local deployment smoke check uses readiness instead of aggregate health")
    void localDeploymentSmokeCheck_usesReadiness() throws IOException {
        String deployScript = Files.readString(Path.of("server/backend/deploy.sh"));

        assertThat(deployScript).contains("curl -f " + LOCAL_READINESS);
        assertThat(deployScript).doesNotContain("/actuator/health/startup");
    }

    @Test
    @DisplayName("CD verifies the canonical host readiness endpoint without accepting a redirect")
    void productionDeploymentSmokeCheck_usesCanonicalReadinessUrl() throws IOException {
        String workflow = Files.readString(Path.of(".github/workflows/deploy-backend.yml"));

        assertThat(workflow).contains("curl --fail --silent --show-error --max-time 15 " + PUBLIC_READINESS);
        assertThat(workflow).doesNotContain("https://quizzence.com/actuator/health");
        assertThat(workflow).doesNotContain("https://www.quizzence.com/actuator/health >/dev/null");
    }
}

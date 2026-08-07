package uk.gegc.quizmaker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Deployment health configuration contract")
class DeploymentHealthConfigurationContractTest {

    private static final String PRIVATE_READINESS = "http://127.0.0.1:8081/actuator/health/readiness";
    private static final String LOCAL_LIVENESS = "http://localhost:8080/actuator/health/liveness";
    private static final String PUBLIC_LIVENESS = "https://www.quizzence.com/actuator/health/liveness";

    @Test
    @DisplayName("Docker image and Compose use readiness for ongoing container health")
    void containerHealthChecks_useReadiness() throws IOException {
        String dockerfile = Files.readString(Path.of("server/backend/Dockerfile"));
        String compose = Files.readString(Path.of("server/backend/docker-compose.yml"));

        assertThat(dockerfile).contains("CMD curl -f " + PRIVATE_READINESS + " || exit 1");
        assertThat(compose).contains("[\"CMD\", \"curl\", \"-f\", \"" + PRIVATE_READINESS + "\"]");
        assertThat(compose).doesNotContain("127.0.0.1:8081:8081");
        assertThat(dockerfile).doesNotContain("/actuator/health/startup");
        assertThat(compose).doesNotContain("/actuator/health/startup");
    }

    @Test
    @DisplayName("Local deployment smoke check uses public liveness after Docker verifies readiness")
    void localDeploymentSmokeCheck_usesPublicLiveness() throws IOException {
        String deployScript = Files.readString(Path.of("server/backend/deploy.sh"));

        assertThat(deployScript).contains("curl -f " + LOCAL_LIVENESS);
        assertThat(deployScript).doesNotContain("localhost:8080/actuator/health/readiness");
    }

    @Test
    @DisplayName("CD verifies canonical public liveness without exposing readiness")
    void productionDeploymentSmokeCheck_usesCanonicalLivenessUrl() throws IOException {
        String workflow = Files.readString(Path.of(".github/workflows/deploy-backend.yml"));

        assertThat(workflow).contains("curl --fail --silent --show-error --max-time 15 " + PUBLIC_LIVENESS);
        assertThat(workflow).doesNotContain("https://www.quizzence.com/actuator/health/readiness");
        assertThat(workflow).doesNotContain("https://quizzence.com/actuator/health");
        assertThat(workflow).doesNotContain("https://www.quizzence.com/actuator/health >/dev/null");
    }

    @Test
    @DisplayName("Server setup proxies only public liveness and rejects other Actuator paths")
    void nginxTemplate_exposesOnlyLiveness() throws IOException {
        String setupScript = Files.readString(Path.of("server/backend/server-setup.sh"));

        assertThat(setupScript).contains("location = /actuator/health/liveness {");
        assertThat(setupScript).contains("proxy_pass http://localhost:8080;");
        assertThat(setupScript).contains("location /actuator/ {", "return 404;");
        assertThat(setupScript).doesNotContain("location /actuator/ {\n        proxy_pass");
    }
}

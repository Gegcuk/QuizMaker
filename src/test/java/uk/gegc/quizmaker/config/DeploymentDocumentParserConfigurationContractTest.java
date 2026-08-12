package uk.gegc.quizmaker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Deployment document parser isolation contract")
class DeploymentDocumentParserConfigurationContractTest {

    private static final Map<String, String> PARSER_DEFAULTS = Map.of(
            "DOCUMENT_MAX_CONCURRENT_PARSES", "2",
            "DOCUMENT_MAX_CONCURRENT_PARSES_PER_USER", "1",
            "DOCUMENT_PARSE_TIMEOUT", "PT60S",
            "DOCUMENT_PARSER_WORKER_MAX_HEAP_BYTES", "402653184",
            "DOCUMENT_PARSER_WORKER_MAX_OUTPUT_BYTES", "16777216",
            "DOCUMENT_PARSER_TERMINATION_GRACE", "PT1S",
            "DOCUMENT_PARSER_FORCE_KILL_TIMEOUT", "PT5S",
            "DOCUMENT_PARSER_SHUTDOWN_TIMEOUT", "PT10S",
            "DOCUMENT_STAGING_RETENTION", "PT24H"
    );

    @Test
    @DisplayName("Compose passes every parser resource and lifecycle limit with the documented safe default")
    void composePassesParserProcessLimits() throws IOException {
        String compose = Files.readString(Path.of("server/backend/docker-compose.yml"));

        PARSER_DEFAULTS.forEach((name, value) -> assertThat(compose)
                .contains("- " + name + "=${" + name + ":-" + value + "}"));
    }

    @Test
    @DisplayName("Production environment example lists every parser resource and lifecycle setting")
    void productionEnvironmentDocumentsParserProcessLimits() throws IOException {
        String environmentExample = Files.readString(Path.of("server/backend/env.production.example"));

        PARSER_DEFAULTS.forEach((name, value) -> assertThat(environmentExample)
                .contains(name + "=" + value));
    }

    @Test
    @DisplayName("Container keeps parser workers non-root without exposing the Docker control socket")
    void containerKeepsParserWorkersWithinNonRootRuntime() throws IOException {
        String dockerfile = Files.readString(Path.of("server/backend/Dockerfile"));
        String compose = Files.readString(Path.of("server/backend/docker-compose.yml"));

        assertThat(dockerfile).contains("USER quizmaker");
        assertThat(compose).contains("uploads_data:/app/uploads");
        assertThat(compose).doesNotContain("/var/run/docker.sock");
    }
}

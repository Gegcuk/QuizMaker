package uk.gegc.quizmaker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Fail-closed Flyway configuration")
class ProductionFlywayConfigurationTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("runtimePropertyFiles")
    @DisplayName("uses immutable ordered history and disables destructive recovery")
    void runtimeConfigurationUsesStrictFlywayPolicy(String profile, Path propertyFile) throws IOException {
        FlywayProperties properties = bind(load(propertyFile));

        assertThat(properties.isEnabled()).as(profile).isTrue();
        assertThat(properties.isValidateOnMigrate()).as(profile).isTrue();
        assertThat(properties.isValidateMigrationNaming()).as(profile).isTrue();
        assertThat(properties.isFailOnMissingLocations()).as(profile).isTrue();
        assertThat(properties.isCleanDisabled()).as(profile).isTrue();
        assertThat(properties.isBaselineOnMigrate()).as(profile).isFalse();
        assertThat(properties.isOutOfOrder()).as(profile).isFalse();
    }

    @Test
    @DisplayName("leaves migration and history decisions to Spring Boot and Flyway")
    void applicationSourceContainsNoRepairOrPreMigrationBusinessDdl() throws IOException {
        try (Stream<Path> paths = Files.walk(Path.of("src/main/java"))) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                assertThat(Files.readString(source))
                        .as("unsafe Flyway startup override in %s", source)
                        .doesNotContain("FlywayMigrationStrategy", ".repair(",
                                "CREATE TABLE IF NOT EXISTS quiz_generation_jobs");
            }
        }
    }

    @Test
    @DisplayName("checks a candidate before handing off from the healthy backend")
    void deploymentChecksCandidateHealthBeforeStoppingHealthyBackend() throws IOException {
        String workflow = Files.readString(Path.of(".github/workflows/deploy-backend.yml"));
        int candidateStart = workflow.indexOf("docker compose --env-file .env run -d --name \"$candidate\"");
        int candidateHealth = workflow.indexOf("if ! wait_for_health \"$candidate\" \"Candidate backend\"");
        int healthyBackendStop = workflow.lastIndexOf("docker compose --env-file .env stop quizmaker-backend");

        assertThat(candidateStart).isNotNegative().isLessThan(candidateHealth);
        assertThat(candidateHealth).isNotNegative().isLessThan(healthyBackendStop);
        assertThat(workflow).doesNotContain("flyway:repair", "flyway.repair");
    }

    private static Stream<Arguments> runtimePropertyFiles() {
        return Stream.of(
                Arguments.of("default", Path.of("src/main/resources/application.properties")),
                Arguments.of("production", Path.of("server/backend/application-prod.properties"))
        );
    }

    private static FlywayProperties bind(Properties properties) {
        Map<String, Object> source = new LinkedHashMap<>();
        properties.stringPropertyNames().forEach(name -> source.put(name, properties.getProperty(name)));
        return new Binder(new MapConfigurationPropertySource(source))
                .bind("spring.flyway", Bindable.of(FlywayProperties.class))
                .orElseThrow(() -> new IllegalStateException("Flyway properties were not bound"));
    }

    private static Properties load(Path path) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }
        return properties;
    }
}

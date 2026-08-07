package uk.gegc.quizmaker.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointProperties;
import org.springframework.boot.actuate.endpoint.Show;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Production health configuration")
class ProductionHealthConfigurationTest {

    private static Properties productionProperties;
    private static HealthEndpointProperties healthProperties;

    @BeforeAll
    static void loadProductionConfiguration() throws IOException {
        productionProperties = load(Path.of("server/backend/application-prod.properties"));
        Map<String, Object> source = new LinkedHashMap<>();
        productionProperties.stringPropertyNames()
                .forEach(name -> source.put(name, productionProperties.getProperty(name)));
        healthProperties = new Binder(new MapConfigurationPropertySource(source))
                .bind("management.endpoint.health", Bindable.of(HealthEndpointProperties.class))
                .get();
    }

    @Test
    @DisplayName("Default configuration never exposes health details or components")
    void defaultConfiguration_redactsHealthDiagnostics() throws IOException {
        Properties defaults = load(Path.of("src/main/resources/application.properties"));

        assertThat(defaults.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
        assertThat(defaults.getProperty("management.endpoint.health.show-components")).isEqualTo("never");
    }

    @Test
    @DisplayName("Production aggregate diagnostics are visible only after authorization")
    void productionAggregate_usesAuthorizedDiagnosticVisibility() {
        assertThat(healthProperties.getShowDetails()).isEqualTo(Show.WHEN_AUTHORIZED);
        assertThat(healthProperties.getShowComponents()).isEqualTo(Show.WHEN_AUTHORIZED);
    }

    @Test
    @DisplayName("Every public probe is bound as a status-only health group")
    void publicGroups_neverExposeDetailsOrComponents() {
        assertThat(healthProperties.getGroup()).containsKeys("liveness", "readiness", "startup");

        for (String groupName : Set.of("liveness", "readiness", "startup")) {
            HealthEndpointProperties.Group group = healthProperties.getGroup().get(groupName);
            assertThat(group.getShowDetails()).as("%s details", groupName).isEqualTo(Show.NEVER);
            assertThat(group.getShowComponents()).as("%s components", groupName).isEqualTo(Show.NEVER);
        }
    }

    @Test
    @DisplayName("Readiness covers serving dependencies while optional SES remains diagnostic-only")
    void readiness_includesDatabaseAndDiskButExcludesSes() {
        assertThat(productionProperties.getProperty("management.health.probes.enabled")).isEqualTo("true");
        assertThat(healthProperties.getGroup().get("liveness").getInclude())
                .containsExactlyInAnyOrder("livenessState", "ping");
        assertThat(healthProperties.getGroup().get("readiness").getInclude())
                .containsExactlyInAnyOrder("readinessState", "db", "diskSpace")
                .doesNotContain("awsSes");
        assertThat(healthProperties.getGroup().get("startup").getInclude())
                .containsExactlyInAnyOrder("ping", "db")
                .doesNotContain("awsSes");
    }

    private static Properties load(Path path) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }
        return properties;
    }
}

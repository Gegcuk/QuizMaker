package uk.gegc.quizmaker.shared.config;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration;
import uk.gegc.quizmaker.features.auth.application.AuthSessionMetricsService;
import uk.gegc.quizmaker.features.auth.application.AuthSessionService;
import uk.gegc.quizmaker.features.auth.infra.security.CustomOAuth2UserService;
import uk.gegc.quizmaker.features.auth.infra.security.OAuth2AuthenticationFailureHandler;
import uk.gegc.quizmaker.features.auth.infra.security.OAuth2AuthenticationSuccessHandler;
import uk.gegc.quizmaker.shared.api.UtilityController;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = HealthEndpointSecurityTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "management.server.address=127.0.0.1",
                "management.endpoints.web.exposure.include=health",
                "management.endpoint.health.show-details=when-authorized",
                "management.endpoint.health.show-components=when-authorized",
                "management.endpoint.health.probes.enabled=true",
                "management.health.diskspace.enabled=false",
                "management.health.mail.enabled=false",
                "management.endpoint.health.group.liveness.include=livenessState,ping",
                "management.endpoint.health.group.liveness.show-details=never",
                "management.endpoint.health.group.liveness.show-components=never",
                "management.endpoint.health.group.readiness.include=readinessState,db,diskSpace",
                "management.endpoint.health.group.readiness.show-details=never",
                "management.endpoint.health.group.readiness.show-components=never",
                "management.endpoint.health.group.startup.include=ping,db",
                "management.endpoint.health.group.startup.show-details=never",
                "management.endpoint.health.group.startup.show-components=never",
                "spring.config.import=",
                "debug=false",
                "logging.level.root=WARN",
                "logging.level.org.springframework=WARN",
                "logging.level.uk.gegc.quizmaker=WARN"
        }
)
// Repository policy assigns every Spring Boot context test to the serial context lane.
@Tag("db-serial")
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Real Actuator health boundary")
class HealthEndpointSecurityTest {

    private static final String ADMIN_TOKEN = "admin-access-token";
    private static final String REGULAR_TOKEN = "regular-access-token";

    @LocalServerPort
    private int applicationPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProbeState probeState;

    @MockitoBean
    private AuthSessionService authSessionService;

    @MockitoBean
    private AuthSessionMetricsService authSessionMetricsService;

    @MockitoBean(name = "corsConfigurationSource")
    private CorsConfigurationSource corsConfigurationSource;

    @MockitoBean
    private TrustedProxyUtil trustedProxyUtil;

    @MockitoBean
    private CustomOAuth2UserService customOAuth2UserService;

    @MockitoBean
    private OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;

    @MockitoBean
    private OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;

    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @BeforeEach
    void setUp() {
        probeState.reset();
        when(authSessionService.authenticateAccessToken(ADMIN_TOKEN))
                .thenReturn(authentication("SYSTEM_ADMIN"));
        when(authSessionService.authenticateAccessToken(REGULAR_TOKEN))
                .thenReturn(authentication("USER_READ"));
    }

    @Test
    @DisplayName("Application listener exposes status-only liveness and the legacy alias")
    void applicationListener_exposesOnlyPublicLivenessContracts() {
        assertStatusOnly(get(applicationPort, "/actuator/health/liveness"), HttpStatus.OK, "UP");
        assertStatusOnly(get(applicationPort, "/api/v1/health"), HttpStatus.OK, "UP");

        assertProblem(get(applicationPort, "/actuator/health/readiness"), HttpStatus.UNAUTHORIZED);
        assertProblem(get(applicationPort, "/actuator/health/startup"), HttpStatus.UNAUTHORIZED);
        assertThat(get(applicationPort, "/actuator/health", ADMIN_TOKEN).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(applicationPort, "/actuator/health/db", ADMIN_TOKEN).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Private management listener exposes status-only liveness, readiness, and startup probes")
    void managementListener_exposesPrivateStatusOnlyProbes() {
        assertThat(managementPort).isNotEqualTo(applicationPort);
        assertStatusOnly(get(managementPort, "/actuator/health/liveness"), HttpStatus.OK, "UP");
        assertStatusOnly(get(managementPort, "/actuator/health/readiness"), HttpStatus.OK, "UP");
        assertStatusOnly(get(managementPort, "/actuator/health/startup"), HttpStatus.OK, "UP");
    }

    @Test
    @DisplayName("Management diagnostics require SYSTEM_ADMIN and expose real components only to that permission")
    void managementDiagnostics_enforceOperatorPermission() {
        assertProblem(get(managementPort, "/actuator/health"), HttpStatus.UNAUTHORIZED);
        assertProblem(get(managementPort, "/actuator/health/db", REGULAR_TOKEN), HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> aggregate = get(managementPort, "/actuator/health", ADMIN_TOKEN);
        assertThat(aggregate.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(aggregate.getBody()).isNotNull();
        assertThat(aggregate.getBody().path("components").fieldNames()).toIterable()
                .contains("db", "diskSpace", "awsSes")
                .doesNotContain("mail");

        ResponseEntity<JsonNode> database = get(managementPort, "/actuator/health/db", ADMIN_TOKEN);
        assertThat(database.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(database.getBody()).isNotNull();
        assertThat(database.getBody().path("details").path("probe").asText()).isEqualTo("database");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RequiredContributor.class, names = {"DATABASE", "DISK"})
    @DisplayName("A required contributor failure makes readiness unavailable without affecting liveness")
    void requiredContributorFailure_returnsRedactedReadiness503(RequiredContributor contributor) {
        probeState.set(contributor, ProbeMode.DOWN);

        assertStatusOnly(
                get(managementPort, "/actuator/health/readiness"),
                HttpStatus.SERVICE_UNAVAILABLE,
                "DOWN"
        );
        assertStatusOnly(get(applicationPort, "/actuator/health/liveness"), HttpStatus.OK, "UP");
    }

    @Test
    @DisplayName("A contributor exception is contained and redacted at the readiness boundary")
    void requiredContributorException_returnsRedactedReadiness503() {
        probeState.set(RequiredContributor.DATABASE, ProbeMode.THROW);

        ResponseEntity<JsonNode> readiness = get(managementPort, "/actuator/health/readiness");

        assertStatusOnly(readiness, HttpStatus.SERVICE_UNAVAILABLE, "DOWN");
        assertThat(readiness.getBody().toString()).doesNotContain("sensitive-database-host");
        assertStatusOnly(get(applicationPort, "/actuator/health/liveness"), HttpStatus.OK, "UP");
    }

    @Test
    @DisplayName("Optional SES failure does not remove readiness")
    void optionalSesFailure_doesNotAffectReadiness() {
        probeState.set(RequiredContributor.AWS_SES, ProbeMode.DOWN);

        assertStatusOnly(get(managementPort, "/actuator/health/readiness"), HttpStatus.OK, "UP");
    }

    private ResponseEntity<JsonNode> get(int port, String path) {
        return get(port, path, null);
    }

    private ResponseEntity<JsonNode> get(int port, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(
                "http://127.0.0.1:" + port + path,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class
        );
    }

    private static void assertStatusOnly(
            ResponseEntity<JsonNode> response,
            HttpStatus expectedStatus,
            String expectedHealth
    ) {
        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        assertThat(response.getHeaders().getCacheControl()).contains("no-cache", "no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().fieldNames()).toIterable().containsExactly("status");
        assertThat(response.getBody().path("status").asText()).isEqualTo(expectedHealth);
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, HttpStatus expectedStatus) {
        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("status").asInt()).isEqualTo(expectedStatus.value());
        assertThat(response.getBody().has("components")).isFalse();
        assertThat(response.getBody().has("details")).isFalse();
    }

    private static UsernamePasswordAuthenticationToken authentication(String authority) {
        return UsernamePasswordAuthenticationToken.authenticated(
                "health-test-user",
                "not-used",
                List.of(new SimpleGrantedAuthority(authority))
        );
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            OpenAiAutoConfiguration.class
    })
    @Import({SecurityConfig.class, UtilityController.class, ProbeConfiguration.class})
    static class TestApplication {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        @Bean
        ProbeState probeState() {
            return new ProbeState();
        }

        @Bean
        HealthIndicator dbHealthIndicator(ProbeState state) {
            return indicator(state, RequiredContributor.DATABASE);
        }

        @Bean
        HealthIndicator diskSpaceHealthIndicator(ProbeState state) {
            return indicator(state, RequiredContributor.DISK);
        }

        @Bean
        HealthIndicator awsSesHealthIndicator(ProbeState state) {
            return indicator(state, RequiredContributor.AWS_SES);
        }

        private static HealthIndicator indicator(ProbeState state, RequiredContributor contributor) {
            return new AbstractHealthIndicator() {
                @Override
                protected void doHealthCheck(Health.Builder builder) {
                    state.contribute(contributor, builder);
                }
            };
        }
    }

    enum RequiredContributor {
        DATABASE("database"),
        DISK("disk"),
        AWS_SES("aws-ses");

        private final String detail;

        RequiredContributor(String detail) {
            this.detail = detail;
        }
    }

    enum ProbeMode {
        UP,
        DOWN,
        THROW
    }

    static final class ProbeState {

        private final Map<RequiredContributor, AtomicReference<ProbeMode>> modes =
                new EnumMap<>(RequiredContributor.class);

        ProbeState() {
            for (RequiredContributor contributor : RequiredContributor.values()) {
                modes.put(contributor, new AtomicReference<>(ProbeMode.UP));
            }
        }

        void reset() {
            modes.values().forEach(mode -> mode.set(ProbeMode.UP));
        }

        void set(RequiredContributor contributor, ProbeMode mode) {
            modes.get(contributor).set(mode);
        }

        void contribute(RequiredContributor contributor, Health.Builder builder) {
            switch (modes.get(contributor).get()) {
                case UP -> builder.up().withDetail("probe", contributor.detail);
                case DOWN -> builder.down().withDetail("reason", "controlled-test-failure");
                case THROW -> throw new IllegalStateException("sensitive-database-host");
            }
        }
    }
}

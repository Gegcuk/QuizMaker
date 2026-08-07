package uk.gegc.quizmaker.shared.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfigurationSource;
import uk.gegc.quizmaker.features.auth.application.AuthSessionMetricsService;
import uk.gegc.quizmaker.features.auth.application.AuthSessionService;
import uk.gegc.quizmaker.features.auth.infra.security.CustomOAuth2UserService;
import uk.gegc.quizmaker.features.auth.infra.security.OAuth2AuthenticationFailureHandler;
import uk.gegc.quizmaker.features.auth.infra.security.OAuth2AuthenticationSuccessHandler;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = HealthEndpointSecurityTest.HealthStubController.class)
@Import({SecurityConfig.class, HealthEndpointSecurityTest.HealthStubController.class})
@DisplayName("Health endpoint authorization")
class HealthEndpointSecurityTest {

    @Autowired
    private MockMvc mockMvc;

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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/actuator/health/liveness",
            "/actuator/health/readiness",
            "/actuator/health/startup"
    })
    @DisplayName("Exact GET probes allow anonymous monitoring")
    void publicProbe_whenAnonymous_isAllowed(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/actuator/health",
            "/actuator/health/awsSes",
            "/actuator/health/db"
    })
    @DisplayName("Aggregate and component diagnostics reject anonymous callers")
    void diagnosticHealth_whenAnonymous_isUnauthorized(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(authorities = "USER_READ")
    @DisplayName("Authenticated users without SYSTEM_ADMIN cannot read component diagnostics")
    void diagnosticHealth_whenRegularUser_isForbidden() throws Exception {
        mockMvc.perform(get("/actuator/health/awsSes"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "SYSTEM_ADMIN")
    @DisplayName("SYSTEM_ADMIN can read aggregate and component diagnostics")
    void diagnosticHealth_whenSystemAdmin_isAllowed() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/awsSes"))
                .andExpect(status().isOk());
    }

    @RestController
    public static class HealthStubController {

        @GetMapping("/actuator/health")
        Map<String, String> aggregate() {
            return Map.of("status", "UP");
        }

        @GetMapping("/actuator/health/{component}")
        Map<String, String> component(@PathVariable String component) {
            return Map.of("status", "UP", "component", component);
        }
    }
}

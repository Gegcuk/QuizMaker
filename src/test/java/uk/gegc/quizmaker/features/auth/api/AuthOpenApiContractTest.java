package uk.gegc.quizmaker.features.auth.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.auth.application.AuthService;
import uk.gegc.quizmaker.features.auth.application.OAuthExchangeService;
import uk.gegc.quizmaker.features.auth.api.dto.RefreshRequest;
import uk.gegc.quizmaker.features.auth.domain.exception.AuthSessionStoreUnavailableException;
import uk.gegc.quizmaker.shared.config.OpenApiConfig;
import uk.gegc.quizmaker.shared.config.OpenApiGroupConfig;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AuthController.class, OAuthCodeExchangeController.class})
@Import({
        OpenApiConfig.class,
        OpenApiGroupConfig.class,
        SpringDocConfiguration.class,
        SpringDocWebMvcConfiguration.class,
        MultipleOpenApiSupportConfiguration.class,
        AuthOpenApiContractTest.SpringDocTestConfig.class,
        OAuthCodeExchangeWebConfiguration.class
})
class AuthOpenApiContractTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SpringDocConfigProperties.class)
    static class SpringDocTestConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private OAuthExchangeService oauthExchangeService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private TrustedProxyUtil trustedProxyUtil;

    @Test
    @WithMockUser
    @DisplayName("GET /v3/api-docs/auth documents type-restricted refresh and idempotent logout")
    void authOpenApiContract() throws Exception {
        JsonNode specification = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs/auth"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(specification.at("/paths/~1api~1v1~1auth~1refresh/post/description").asText())
                .contains("type=refresh", "rolling", "four days", "single-use", "503");
        assertResponseDocumented(specification, "/paths/~1api~1v1~1auth~1refresh/post/responses/401");
        assertResponseDocumented(specification, "/paths/~1api~1v1~1auth~1refresh/post/responses/503");
        assertThat(specification.at("/paths/~1api~1v1~1auth~1logout/post/description").asText())
                .contains("type=access", "idempotent");
        assertResponseDocumented(specification, "/paths/~1api~1v1~1auth~1logout/post/responses/204");
        assertResponseDocumented(specification, "/paths/~1api~1v1~1auth~1logout/post/responses/401");
        assertThat(specification.at("/paths/~1api~1v1~1auth~1logout/post/parameters/0/name").asText())
                .isEqualTo("Authorization");
        assertThat(specification.at("/paths/~1api~1v1~1auth~1logout/post/security/0").isObject()).isTrue();
        assertThat(specification.at("/paths/~1api~1v1~1auth~1logout/post/security/0").size()).isZero();
        assertThat(specification.at("/components/schemas/JwtResponse/properties/accessToken/description").asText())
                .contains("type=access");
        assertThat(specification.at("/components/schemas/JwtResponse/properties/accessExpiresInMs/example").asLong())
                .isEqualTo(43_200_000L);
        assertThat(specification.at("/components/schemas/JwtResponse/properties/refreshToken/description").asText())
                .contains("cannot authenticate protected endpoints");
        assertThat(specification.at("/components/schemas/JwtResponse/properties/refreshExpiresInMs/description").asText())
                .contains("rolling", "four days", "ordinary API requests do not");
        assertThat(specification.at("/components/schemas/JwtResponse/properties/refreshExpiresInMs/example").asLong())
                .isEqualTo(345_600_000L);
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/v1/auth/oauth/exchange documents automatic one-time S256 exchange without URL credentials")
    void oauthExchangeOpenApiContract() throws Exception {
        JsonNode specification = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs/auth"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        String operationPointer = "/paths/~1api~1v1~1auth~1oauth~1exchange/post";
        JsonNode operation = specification.at(operationPointer);

        assertThat(operation.isMissingNode()).isFalse();
        assertThat(operation.path("description").asText())
                .contains("two-minute", "single-use", "S256", "exact registered client", "no pagination")
                .contains("no additional action from the user", "online", "restarts sign-in");
        assertThat(operation.at("/security/0").isObject()).isTrue();
        assertThat(operation.at("/security/0").size()).isZero();
        assertThat(operation.at("/requestBody/content/application~1json/schema/$ref").asText())
                .endsWith("/OAuthCodeExchangeRequest");
        assertThat(operation.at("/responses/200/content/application~1json/schema/$ref").asText())
                .endsWith("/JwtResponse");
        for (String status : java.util.List.of("400", "401", "409", "429", "503")) {
            assertResponseDocumented(specification, operationPointer + "/responses/" + status);
        }

        JsonNode requestSchema = specification.at("/components/schemas/OAuthCodeExchangeRequest");
        java.util.List<String> requiredProperties = new java.util.ArrayList<>();
        requestSchema.path("required").forEach(node -> requiredProperties.add(node.asText()));
        assertThat(requiredProperties)
                .containsExactlyInAnyOrder("code", "clientId", "redirectUri", "codeVerifier");
        assertThat(requestSchema.at("/properties/code/minLength").asInt()).isEqualTo(43);
        assertThat(requestSchema.at("/properties/code/maxLength").asInt()).isEqualTo(43);
        assertThat(requestSchema.at("/properties/code/pattern").asText()).isEqualTo("[A-Za-z0-9_-]{43}");
        assertThat(requestSchema.at("/properties/codeVerifier/minLength").asInt()).isEqualTo(43);
        assertThat(requestSchema.at("/properties/codeVerifier/maxLength").asInt()).isEqualTo(128);
        assertThat(requestSchema.at("/properties/codeVerifier/pattern").asText())
                .isEqualTo("[A-Za-z0-9\\-._~]{43,128}");
        assertThat(requestSchema.at("/properties/redirectUri/maxLength").asInt()).isEqualTo(2048);

        assertThat(specification.at("/paths/~1api~1v1~1auth~1login/post/description").asText())
                .contains(
                        "Google or GitHub",
                        "client_id=quizzence-web",
                        "redirect_uri={exact-encoded-callback}",
                        "code_challenge={S256-challenge}",
                        "code_challenge_method=S256",
                        "POST /api/v1/auth/oauth/exchange",
                        "not placed in the redirect URL"
                )
                .doesNotContain("JWT tokens in the URL query parameters");
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/v1/auth/refresh exposes a bounded retryable response when session state is unavailable")
    void refresh_sessionStoreUnavailable_returnsBounded503() throws Exception {
        RefreshRequest request = new RefreshRequest("syntactically-valid-refresh-token");
        when(authService.refresh(request)).thenThrow(new AuthSessionStoreUnavailableException(
                new IllegalStateException("sensitive-database-host")
        ));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "3"))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/auth-session-unavailable"))
                .andExpect(jsonPath("$.title").value("Authentication Temporarily Unavailable"))
                .andExpect(jsonPath("$.detail")
                        .value("Authentication session state is temporarily unavailable. Please retry."))
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sensitive-database-host"))));
    }

    private void assertResponseDocumented(JsonNode specification, String responsePointer) {
        assertThat(specification.at(responsePointer).isMissingNode()).isFalse();
    }
}
